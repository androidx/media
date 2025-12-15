/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.muxer;

import static androidx.media3.muxer.Boxes.LARGE_SIZE_BOX_HEADER_SIZE;
import static androidx.media3.muxer.Boxes.getAxteBoxHeader;
import static androidx.media3.muxer.MuxerUtil.getAuxiliaryTracksLengthMetadata;
import static androidx.media3.muxer.MuxerUtil.getAuxiliaryTracksOffsetMetadata;
import static androidx.media3.muxer.MuxerUtil.isAuxiliaryTrack;
import static androidx.media3.muxer.MuxerUtil.isMetadataSupported;
import static androidx.media3.muxer.MuxerUtil.populateAuxiliaryTracksMetadata;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.annotation.ElementType.TYPE_USE;

import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.container.MdtaMetadataEntry;
import androidx.media3.container.Mp4LocationData;
import androidx.media3.container.Mp4OrientationData;
import androidx.media3.container.Mp4TimestampData;
import androidx.media3.container.XmpData;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;

/**
 * A muxer for creating an MP4 container file.
 *
 * <p>Muxer supports muxing of:
 *
 * <ul>
 *   <li>Video Codecs:
 *       <ul>
 *         <li>AV1
 *         <li>MPEG-4
 *         <li>H.263
 *         <li>H.264 (AVC)
 *         <li>H.265 (HEVC)
 *         <li>VP9
 *         <li>APV
 *         <li>Dolby Vision
 *       </ul>
 *   <li>Audio Codecs:
 *       <ul>
 *         <li>AAC
 *         <li>AMR-NB (Narrowband AMR)
 *         <li>AMR-WB (Wideband AMR)
 *         <li>Opus
 *         <li>Vorbis
 *         <li>Raw Audio
 *       </ul>
 *   <li>Metadata
 * </ul>
 *
 * <p>All the operations are performed on the caller thread.
 *
 * <p>To create an MP4 container file, the caller must:
 *
 * <ul>
 *   <li>Add tracks using {@link #addTrack(int, Format)} which will return a track id.
 *   <li>Use the associated track id when {@linkplain #writeSampleData(int, ByteBuffer, BufferInfo)
 *       writing samples} for that track.
 *   <li>{@link #close} the muxer when all data has been written.
 * </ul>
 *
 * <p>Some key points:
 *
 * <ul>
 *   <li>Tracks can be added at any point, even after writing some samples to other tracks.
 *   <li>The caller is responsible for ensuring that samples of different track types are well
 *       interleaved by calling {@link #writeSampleData(int, ByteBuffer, BufferInfo)} in an order
 *       that interleaves samples from different tracks.
 *   <li>When writing a file, if an error occurs and the muxer is not closed, then the output MP4
 *       file may still have some partial data.
 * </ul>
 */
@UnstableApi
public final class Mp4Muxer implements Muxer {
  /** Parameters for {@link #FILE_FORMAT_MP4_WITH_AUXILIARY_TRACKS_EXTENSION}. */
  public static final class Mp4AtFileParameters {
    public final boolean shouldInterleaveSamples;

    /**
     * Creates an instance.
     *
     * @param shouldInterleaveSamples Whether to interleave auxiliary track samples with primary
     *     track samples.
     */
    public Mp4AtFileParameters(boolean shouldInterleaveSamples) {
      this.shouldInterleaveSamples = shouldInterleaveSamples;
    }
  }

  /** Behavior for the duration of the last sample. */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({
    LAST_SAMPLE_DURATION_BEHAVIOR_SET_TO_ZERO,
    LAST_SAMPLE_DURATION_BEHAVIOR_SET_FROM_END_OF_STREAM_BUFFER_OR_DUPLICATE_PREVIOUS
  })
  public @interface LastSampleDurationBehavior {}

  /** The duration of the last sample is set to 0. */
  public static final int LAST_SAMPLE_DURATION_BEHAVIOR_SET_TO_ZERO = 0;

  /**
   * Use the {@link C#BUFFER_FLAG_END_OF_STREAM end of stream sample} to set the duration of the
   * last sample.
   *
   * <p>After {@linkplain #writeSampleData writing} all the samples for a track, the app must
   * {@linkplain #writeSampleData write} an empty sample with flag {@link
   * C#BUFFER_FLAG_END_OF_STREAM}. The timestamp of this sample should be equal to the desired track
   * duration.
   *
   * <p>Once a sample with flag {@link C#BUFFER_FLAG_END_OF_STREAM} is {@linkplain #writeSampleData
   * written}, no more samples can be written for that track.
   *
   * <p>If no explicit {@link C#BUFFER_FLAG_END_OF_STREAM} sample is passed, then the duration of
   * the last sample will be same as that of the sample before that.
   */
  public static final int
      LAST_SAMPLE_DURATION_BEHAVIOR_SET_FROM_END_OF_STREAM_BUFFER_OR_DUPLICATE_PREVIOUS = 1;

  /** The specific MP4 file format. */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({FILE_FORMAT_DEFAULT, FILE_FORMAT_MP4_WITH_AUXILIARY_TRACKS_EXTENSION})
  public @interface FileFormat {}

  /** The default MP4 format. */
  public static final int FILE_FORMAT_DEFAULT = 0;

  /**
   * The MP4 With Auxiliary Tracks Extension (MP4-AT) file format. In this file format all the
   * tracks with {@linkplain Format#auxiliaryTrackType} set to {@link
   * C#AUXILIARY_TRACK_TYPE_ORIGINAL}, {@link C#AUXILIARY_TRACK_TYPE_DEPTH_LINEAR}, {@link
   * C#AUXILIARY_TRACK_TYPE_DEPTH_INVERSE}, or {@link C#AUXILIARY_TRACK_TYPE_DEPTH_METADATA} are
   * written in the Auxiliary Tracks MP4 (axte box). The rest of the tracks are written as usual.
   *
   * <p>See the file format at https://developer.android.com/media/platform/mp4-at-file-format.
   */
  public static final int FILE_FORMAT_MP4_WITH_AUXILIARY_TRACKS_EXTENSION = 1;

  /** A builder for {@link Mp4Muxer} instances. */
  public static final class Builder {
    private final SeekableMuxerOutput seekableMuxerOutput;

    @Nullable private Supplier<String> cacheFileSupplier;
    private @LastSampleDurationBehavior int lastSampleDurationBehavior;
    @Nullable private AnnexBToAvccConverter annexBToAvccConverter;
    private boolean sampleCopyEnabled;
    private boolean sampleBatchingEnabled;
    private boolean attemptStreamableOutputEnabled;
    private @FileFormat int outputFileFormat;
    @Nullable private Mp4AtFileParameters mp4AtFileParameters;
    private int freeSpaceAfterFtypInBytes;

    /**
     * @deprecated Use {@link Mp4Muxer.Builder#Builder(SeekableMuxerOutput)} instead.
     */
    @Deprecated
    public Builder(FileOutputStream outputStream) {
      this(SeekableMuxerOutput.of(outputStream));
    }

    /**
     * Creates an instance.
     *
     * @param seekableMuxerOutput A {@link SeekableMuxerOutput} to write output to. It will be
     *     automatically {@linkplain SeekableMuxerOutput#close() closed} by the muxer when {@link
     *     Muxer#close()} is called.
     */
    public Builder(SeekableMuxerOutput seekableMuxerOutput) {
      this.seekableMuxerOutput = seekableMuxerOutput;
      lastSampleDurationBehavior =
          LAST_SAMPLE_DURATION_BEHAVIOR_SET_FROM_END_OF_STREAM_BUFFER_OR_DUPLICATE_PREVIOUS;
      attemptStreamableOutputEnabled = true;
      outputFileFormat = FILE_FORMAT_DEFAULT;
    }

    /**
     * Sets a {@link Supplier} that provides an absolute path of a cache file.
     *
     * <p>Every call to {@link Supplier#get()} must return a new cache file path.
     *
     * <p>This must be set when {@link Mp4AtFileParameters#shouldInterleaveSamples} is set to {@code
     * false}.
     *
     * <p>The app is responsible for deleting the cache file after {@linkplain Muxer#close()
     * closing} the muxer.
     */
    @CanIgnoreReturnValue
    public Mp4Muxer.Builder setCacheFileSupplier(Supplier<String> cacheFileSupplier) {
      this.cacheFileSupplier = cacheFileSupplier;
      return this;
    }

    /**
     * Sets the {@link LastSampleDurationBehavior}.
     *
     * <p>The default value is {@link
     * #LAST_SAMPLE_DURATION_BEHAVIOR_SET_FROM_END_OF_STREAM_BUFFER_OR_DUPLICATE_PREVIOUS}.
     */
    @CanIgnoreReturnValue
    public Mp4Muxer.Builder setLastSampleDurationBehavior(
        @LastSampleDurationBehavior int lastSampleDurationBehavior) {
      this.lastSampleDurationBehavior = lastSampleDurationBehavior;
      return this;
    }

    /**
     * Sets the {@link AnnexBToAvccConverter} to be used by the muxer to convert H.264 and H.265 NAL
     * units from the Annex-B format (using start codes to delineate NAL units) to the AVCC format
     * (which uses length prefixes).
     *
     * <p>The default value is {@link AnnexBToAvccConverter#DEFAULT}.
     */
    @CanIgnoreReturnValue
    public Mp4Muxer.Builder setAnnexBToAvccConverter(AnnexBToAvccConverter annexBToAvccConverter) {
      this.annexBToAvccConverter = annexBToAvccConverter;
      return this;
    }

    /**
     * Sets whether to enable the sample copy.
     *
     * <p>If the sample copy is enabled, {@link #writeSampleData(int, ByteBuffer, BufferInfo)}
     * copies the input {@link ByteBuffer} and {@link BufferInfo} before it returns, so it is safe
     * to reuse them immediately. Otherwise, the muxer takes ownership of the {@link ByteBuffer} and
     * the {@link BufferInfo} and the caller must not modify them.
     *
     * <p>Note: Sample copying is only effective when {@link #setSampleBatchingEnabled(boolean)
     * sample batching} is also enabled. If sample batching is disabled, samples are written
     * immediately upon arrival, and copying is not performed, regardless of this setting.
     *
     * <p>The default value is {@code false}.
     */
    @CanIgnoreReturnValue
    public Mp4Muxer.Builder setSampleCopyingEnabled(boolean enabled) {
      this.sampleCopyEnabled = enabled;
      return this;
    }

    /**
     * Sets whether to enable sample batching.
     *
     * <p>If sample batching is enabled, samples are written in batches for each track, otherwise
     * samples are written as they {@linkplain #writeSampleData(int, ByteBuffer, BufferInfo)
     * arrive}.
     *
     * <p>When sample batching is enabled, and {@linkplain #setSampleCopyingEnabled(boolean) sample
     * copying} is disabled the {@link ByteBuffer} and {@link BufferInfo} provided to {@link
     * #writeSampleData(int, ByteBuffer, BufferInfo)} must not be modified. Otherwise, if sample
     * batching is disabled or sample copying is enabled, the {@link ByteBuffer} and {@link
     * BufferInfo} can be modified after calling {@link #writeSampleData(int, ByteBuffer,
     * BufferInfo)}.
     *
     * <p>The default value is {@code false}.
     */
    @CanIgnoreReturnValue
    public Mp4Muxer.Builder setSampleBatchingEnabled(boolean enabled) {
      this.sampleBatchingEnabled = enabled;
      return this;
    }

    /**
     * Sets whether to attempt to write a file where the metadata is stored at the start, which can
     * make the file more efficient to read sequentially.
     *
     * <p>Setting to {@code true} does not guarantee a streamable MP4 output.
     *
     * <p>The default value is {@code true}.
     */
    @CanIgnoreReturnValue
    public Mp4Muxer.Builder setAttemptStreamableOutputEnabled(
        boolean attemptStreamableOutputEnabled) {
      this.attemptStreamableOutputEnabled = attemptStreamableOutputEnabled;
      return this;
    }

    /**
     * Sets the specific MP4 file format.
     *
     * <p>The default value is {@link #FILE_FORMAT_DEFAULT}.
     *
     * <p>For {@link #FILE_FORMAT_MP4_WITH_AUXILIARY_TRACKS_EXTENSION}, {@link Mp4AtFileParameters}
     * must also be {@linkplain #setMp4AtFileParameters(Mp4AtFileParameters)} set}.
     */
    @CanIgnoreReturnValue
    public Mp4Muxer.Builder setOutputFileFormat(@FileFormat int fileFormat) {
      this.outputFileFormat = fileFormat;
      return this;
    }

    /** Sets the {@link Mp4AtFileParameters}. */
    @CanIgnoreReturnValue
    public Mp4Muxer.Builder setMp4AtFileParameters(Mp4AtFileParameters mp4AtFileParameters) {
      this.mp4AtFileParameters = mp4AtFileParameters;
      return this;
    }

    /**
     * Sets the amount of free space (in bytes) to be reserved immediately after the {@code ftyp}
     * box (File Type box) in the MP4 file.
     *
     * <p>The {@code moov} box (Movie Box) is written in the reserved space if {@link
     * #setAttemptStreamableOutputEnabled(boolean)} is set to {@code true}, and the size of the
     * {@code moov} box is not greater than {@code bytes}. Otherwise, a {@code free} box of the
     * requested size is written.
     *
     * <p>By default 400_000 bytes are reserved if {@link
     * #setAttemptStreamableOutputEnabled(boolean)} is set to {@code true}.
     *
     * <p>This method is experimental and will be renamed or removed in a future release.
     */
    @CanIgnoreReturnValue
    public Mp4Muxer.Builder experimentalSetFreeSpaceAfterFileTypeBox(int bytes) {
      checkArgument(bytes >= 0);
      this.freeSpaceAfterFtypInBytes = bytes;
      return this;
    }

    /** Builds an {@link Mp4Muxer} instance. */
    public Mp4Muxer build() {
      if (outputFileFormat == FILE_FORMAT_MP4_WITH_AUXILIARY_TRACKS_EXTENSION) {
        checkArgument(
            mp4AtFileParameters != null,
            "Mp4AtFileParameters must be set for FILE_FORMAT_MP4_WITH_AUXILIARY_TRACKS_EXTENSION");
        checkArgument(
            mp4AtFileParameters.shouldInterleaveSamples || cacheFileSupplier != null,
            "CacheFileSupplier must be set when Mp4AtFileParameters.shouldInterleaveSamples is set"
                + " to false");
      }
      return new Mp4Muxer(
          seekableMuxerOutput,
          cacheFileSupplier,
          lastSampleDurationBehavior,
          annexBToAvccConverter == null ? AnnexBToAvccConverter.DEFAULT : annexBToAvccConverter,
          sampleCopyEnabled,
          sampleBatchingEnabled,
          attemptStreamableOutputEnabled,
          outputFileFormat,
          mp4AtFileParameters,
          freeSpaceAfterFtypInBytes);
    }
  }

  // LINT.IfChange(supported_mime_types)
  /** A list of supported video {@linkplain MimeTypes sample MIME types}. */
  public static final ImmutableList<String> SUPPORTED_VIDEO_SAMPLE_MIME_TYPES =
      ImmutableList.of(
          MimeTypes.VIDEO_AV1,
          MimeTypes.VIDEO_H263,
          MimeTypes.VIDEO_H264,
          MimeTypes.VIDEO_H265,
          MimeTypes.VIDEO_MP4V,
          MimeTypes.VIDEO_VP9,
          MimeTypes.VIDEO_APV,
          MimeTypes.VIDEO_DOLBY_VISION);

  /** A list of supported audio {@linkplain MimeTypes sample MIME types}. */
  public static final ImmutableList<String> SUPPORTED_AUDIO_SAMPLE_MIME_TYPES =
      ImmutableList.of(
          MimeTypes.AUDIO_AAC,
          MimeTypes.AUDIO_AMR_NB,
          MimeTypes.AUDIO_AMR_WB,
          MimeTypes.AUDIO_OPUS,
          MimeTypes.AUDIO_VORBIS,
          MimeTypes.AUDIO_RAW);

  // LINT.ThenChange(Boxes.java:codec_specific_boxes)

  private static final String TAG = "Mp4Muxer";

  private final SeekableMuxerOutput muxerOutput;
  @Nullable private final Supplier<String> cacheFileSupplier;
  private final @LastSampleDurationBehavior int lastSampleDurationBehavior;
  private final AnnexBToAvccConverter annexBToAvccConverter;
  private final boolean sampleCopyEnabled;
  private final boolean sampleBatchingEnabled;
  private final boolean attemptStreamableOutputEnabled;
  private final int freeSpaceAfterFtypInBytes;
  private final @FileFormat int outputFileFormat;
  @Nullable private final Mp4AtFileParameters mp4AtFileParameters;
  private final MetadataCollector metadataCollector;
  private final Mp4Writer mp4Writer;
  private final List<Track> trackIdToTrack;
  private final List<Track> auxiliaryTracks;

  @Nullable private String cacheFilePath;
  @Nullable private SeekableMuxerOutput cacheMuxerOutput;
  @Nullable private MetadataCollector auxiliaryTracksMetadataCollector;
  @Nullable private Mp4Writer auxiliaryTracksMp4Writer;

  private int nextTrackId;

  private Mp4Muxer(
      SeekableMuxerOutput seekableMuxerOutput,
      @Nullable Supplier<String> cacheFileSupplier,
      @LastSampleDurationBehavior int lastFrameDurationBehavior,
      AnnexBToAvccConverter annexBToAvccConverter,
      boolean sampleCopyEnabled,
      boolean sampleBatchingEnabled,
      boolean attemptStreamableOutputEnabled,
      @FileFormat int outputFileFormat,
      @Nullable Mp4AtFileParameters mp4AtFileParameters,
      int freeSpaceAfterFtypInBytes) {
    this.muxerOutput = seekableMuxerOutput;
    this.cacheFileSupplier = cacheFileSupplier;
    this.lastSampleDurationBehavior = lastFrameDurationBehavior;
    this.annexBToAvccConverter = annexBToAvccConverter;
    this.sampleCopyEnabled = sampleBatchingEnabled && sampleCopyEnabled;
    this.sampleBatchingEnabled = sampleBatchingEnabled;
    this.attemptStreamableOutputEnabled = attemptStreamableOutputEnabled;
    this.outputFileFormat = outputFileFormat;
    this.mp4AtFileParameters = mp4AtFileParameters;
    this.freeSpaceAfterFtypInBytes = freeSpaceAfterFtypInBytes;
    metadataCollector = new MetadataCollector();
    mp4Writer =
        new Mp4Writer(
            muxerOutput,
            metadataCollector,
            annexBToAvccConverter,
            lastFrameDurationBehavior,
            sampleCopyEnabled,
            sampleBatchingEnabled,
            attemptStreamableOutputEnabled,
            freeSpaceAfterFtypInBytes);
    trackIdToTrack = new ArrayList<>();
    auxiliaryTracks = new ArrayList<>();
  }

  /**
   * {@inheritDoc}
   *
   * <p>Tracks can be added at any point before the muxer is closed, even after writing samples to
   * other tracks.
   *
   * <p>The order of tracks remains same in which they are added.
   *
   * @param format The {@link Format} for the track.
   * @return A unique track id. The track id is non-negative. It should be used in {@link
   *     #writeSampleData}.
   * @throws MuxerException If an error occurs while adding track.
   */
  @Override
  public int addTrack(Format format) throws MuxerException {
    return addTrack(/* sortKey= */ 1, format);
  }

  /**
   * Adds a track of the given media format.
   *
   * <p>Tracks can be added at any point before the muxer is closed, even after writing samples to
   * other tracks.
   *
   * <p>The final order of tracks is determined by the provided sort key. Tracks with a lower sort
   * key will be written before tracks with a higher sort key. Ordering between tracks with the same
   * sort key is not specified.
   *
   * @param sortKey The key used for sorting the track list.
   * @param format The {@link Format} for the track.
   * @return A unique track id. The track id is non-negative. It should be used in {@link
   *     #writeSampleData}.
   * @throws MuxerException If an error occurs while adding track.
   */
  public int addTrack(int sortKey, Format format) throws MuxerException {
    Track track;
    if (outputFileFormat == FILE_FORMAT_MP4_WITH_AUXILIARY_TRACKS_EXTENSION
        && isAuxiliaryTrack(format)) {
      if (checkNotNull(mp4AtFileParameters).shouldInterleaveSamples) {
        // Auxiliary tracks are handled by the primary Mp4Writer.
        track = mp4Writer.addAuxiliaryTrack(nextTrackId++, sortKey, format);
      } else {
        // Auxiliary tracks are handled by the auxiliary tracks Mp4Writer.
        try {
          ensureSetupForAuxiliaryTracks();
        } catch (FileNotFoundException e) {
          throw new MuxerException("Cache file not found", e);
        }
        track = auxiliaryTracksMp4Writer.addTrack(nextTrackId++, sortKey, format);
        auxiliaryTracks.add(track);
      }
    } else {
      track = mp4Writer.addTrack(nextTrackId++, sortKey, format);
    }
    trackIdToTrack.add(track);
    return track.id;
  }

  /**
   * {@inheritDoc}
   *
   * <p>The muxer's handling of sample {@link ByteBuffer} and {@link BufferInfo} depends on the
   * {@link Builder#setSampleBatchingEnabled(boolean) sample batching} and {@link
   * Builder#setSampleCopyingEnabled(boolean) sample copying} settings:
   *
   * <ul>
   *   <li>If {@linkplain Builder#setSampleBatchingEnabled(boolean) sample batching} is disabled:
   *       Samples are written immediately upon arrival. The caller can safely modify or reuse these
   *       objects immediately after this method returns.
   *   <li>If {@linkplain Builder#setSampleBatchingEnabled(boolean) sample batching} is enabled:
   *       <ul>
   *         <li>If {@linkplain Builder#setSampleCopyingEnabled(boolean) sample copying} is enabled:
   *             The muxer makes internal copies of the provided {@link ByteBuffer} and {@link
   *             BufferInfo}. The caller can safely modify or reuse these objects immediately after
   *             this method returns.
   *         <li>If {@linkplain Builder#setSampleCopyingEnabled(boolean) sample copying} is
   *             disabled: The muxer takes ownership of the {@link ByteBuffer} and {@link
   *             BufferInfo}. The caller must not modify these objects after this method returns.
   *       </ul>
   * </ul>
   *
   * @param trackId The track id for which this sample is being written.
   * @param byteBuffer The encoded sample.
   * @param bufferInfo The {@link BufferInfo} related to this sample.
   * @throws MuxerException If an error occurs while writing data to the output file.
   */
  @Override
  public void writeSampleData(int trackId, ByteBuffer byteBuffer, BufferInfo bufferInfo)
      throws MuxerException {
    checkArgument(trackId < trackIdToTrack.size(), "Track id is invalid");
    checkNotNull(byteBuffer);
    checkNotNull(bufferInfo);
    checkArgument(byteBuffer.remaining() == bufferInfo.size);
    Track track = trackIdToTrack.get(trackId);
    try {
      if (auxiliaryTracks.contains(track)) {
        checkNotNull(auxiliaryTracksMp4Writer).writeSampleData(track, byteBuffer, bufferInfo);
      } else {
        mp4Writer.writeSampleData(track, byteBuffer, bufferInfo);
      }
    } catch (IOException e) {
      throw new MuxerException(
          "Failed to write sample for presentationTimeUs="
              + bufferInfo.presentationTimeUs
              + ", size="
              + bufferInfo.size,
          e);
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>List of supported {@linkplain Metadata.Entry metadata entries}:
   *
   * <ul>
   *   <li>{@link Mp4OrientationData}
   *   <li>{@link Mp4LocationData}
   *   <li>{@link Mp4TimestampData}
   *   <li>{@link MdtaMetadataEntry}: Only {@linkplain MdtaMetadataEntry#TYPE_INDICATOR_STRING
   *       string type} or {@linkplain MdtaMetadataEntry#TYPE_INDICATOR_FLOAT32 float type} value is
   *       supported.
   *   <li>{@link XmpData}
   * </ul>
   *
   * @param metadataEntry The {@linkplain Metadata.Entry metadata}. An {@link
   *     IllegalArgumentException} is thrown if the {@linkplain Metadata.Entry metadata} is not
   *     supported.
   */
  @Override
  public void addMetadataEntry(Metadata.Entry metadataEntry) {
    checkArgument(isMetadataSupported(metadataEntry), "Unsupported metadata");
    metadataCollector.addMetadata(metadataEntry);
  }

  @Override
  public void close() throws MuxerException {
    @Nullable MuxerException exception = null;
    try {
      finishWritingAuxiliaryTracks();
      finishWritingPrimaryVideoTracks();
      appendAuxiliaryTracksDataToTheOutputFile();
    } catch (IOException e) {
      exception = new MuxerException("Failed to finish writing data", e);
    }
    try {
      muxerOutput.close();
    } catch (IOException e) {
      if (exception == null) {
        exception = new MuxerException("Failed to close output stream", e);
      } else {
        Log.e(TAG, "Failed to close output stream", e);
      }
    }
    if (cacheMuxerOutput != null) {
      try {
        cacheMuxerOutput.close();
      } catch (IOException e) {
        if (exception == null) {
          exception = new MuxerException("Failed to close the cache file output stream", e);
        } else {
          Log.e(TAG, "Failed to close cache file output stream", e);
        }
      }
    }
    if (exception != null) {
      throw exception;
    }
  }

  @EnsuresNonNull({"auxiliaryTracksMp4Writer"})
  private void ensureSetupForAuxiliaryTracks() throws FileNotFoundException {
    if (auxiliaryTracksMp4Writer == null) {
      cacheFilePath = checkNotNull(cacheFileSupplier).get();
      cacheMuxerOutput = SeekableMuxerOutput.of(cacheFilePath);
      auxiliaryTracksMetadataCollector = new MetadataCollector();
      auxiliaryTracksMp4Writer =
          new Mp4Writer(
              cacheMuxerOutput,
              checkNotNull(auxiliaryTracksMetadataCollector),
              annexBToAvccConverter,
              lastSampleDurationBehavior,
              sampleCopyEnabled,
              sampleBatchingEnabled,
              attemptStreamableOutputEnabled,
              freeSpaceAfterFtypInBytes);
    }
  }

  private void finishWritingAuxiliaryTracks() throws IOException {
    if (auxiliaryTracksMp4Writer == null) {
      // Auxiliary tracks were not added.
      return;
    }
    populateAuxiliaryTracksMetadata(
        checkNotNull(auxiliaryTracksMetadataCollector),
        metadataCollector.timestampData,
        /* samplesInterleaved= */ false,
        auxiliaryTracks);
    checkNotNull(auxiliaryTracksMp4Writer).finishWritingSamplesAndFinalizeMoovBox();
  }

  private void finishWritingPrimaryVideoTracks() throws IOException {
    // The exact offset is known after writing all the data in mp4Writer.
    MdtaMetadataEntry placeholderAuxiliaryTracksOffset = getAuxiliaryTracksOffsetMetadata(0L);
    if (auxiliaryTracksMp4Writer != null) {
      long auxiliaryTracksDataSize = checkNotNull(cacheMuxerOutput).getSize();
      long axteBoxSize = LARGE_SIZE_BOX_HEADER_SIZE + auxiliaryTracksDataSize;
      metadataCollector.addMetadata(getAuxiliaryTracksLengthMetadata(axteBoxSize));
      metadataCollector.addMetadata(placeholderAuxiliaryTracksOffset);
    }
    mp4Writer.finishWritingSamplesAndFinalizeMoovBox();
    if (auxiliaryTracksMp4Writer != null) {
      long primaryVideoDataSize = muxerOutput.getSize();
      metadataCollector.removeMdtaMetadataEntry(placeholderAuxiliaryTracksOffset);
      metadataCollector.addMetadata(getAuxiliaryTracksOffsetMetadata(primaryVideoDataSize));
      mp4Writer.finalizeMoovBox();
      checkState(
          muxerOutput.getSize() == primaryVideoDataSize,
          "The auxiliary tracks offset should remain the same");
    }
  }

  private void appendAuxiliaryTracksDataToTheOutputFile() throws IOException {
    if (auxiliaryTracksMp4Writer == null) {
      // Auxiliary tracks were not added.
      return;
    }
    muxerOutput.setPosition(muxerOutput.getSize());
    try (FileInputStream cacheFileInputStream = new FileInputStream(checkNotNull(cacheFilePath))) {
      FileChannel cacheFileChannel = cacheFileInputStream.getChannel();
      long cacheFileSize = cacheFileChannel.size();
      muxerOutput.write(getAxteBoxHeader(cacheFileSize));
      // Copy data from the cache file to muxer output.
      cacheFileChannel.transferTo(/* position= */ 0, cacheFileSize, muxerOutput);
    }
  }
}
