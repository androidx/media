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

import static androidx.media3.common.util.Assertions.checkArgument;
import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.muxer.Boxes.LARGE_SIZE_BOX_HEADER_SIZE;
import static androidx.media3.muxer.Boxes.getEdvdBoxHeader;
import static androidx.media3.muxer.MuxerUtil.getEditableTracksLengthMetadata;
import static androidx.media3.muxer.MuxerUtil.getEditableTracksOffsetMetadata;
import static androidx.media3.muxer.MuxerUtil.isEditableVideoTrack;
import static androidx.media3.muxer.MuxerUtil.isMetadataSupported;
import static androidx.media3.muxer.MuxerUtil.populateEditableVideoTracksMetadata;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.container.MdtaMetadataEntry;
import androidx.media3.container.Mp4LocationData;
import androidx.media3.container.Mp4OrientationData;
import androidx.media3.container.Mp4TimestampData;
import androidx.media3.container.XmpData;
import com.google.common.io.ByteStreams;
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
 *       </ul>
 *   <li>Audio Codecs:
 *       <ul>
 *         <li>AAC
 *         <li>AMR-NB (Narrowband AMR)
 *         <li>AMR-WB (Wideband AMR)
 *         <li>Opus
 *         <li>Vorbis
 *       </ul>
 *   <li>Metadata
 * </ul>
 *
 * <p>All the operations are performed on the caller thread.
 *
 * <p>To create an MP4 container file, the caller must:
 *
 * <ul>
 *   <li>Add tracks using {@link #addTrack(int, Format)} which will return a {@link TrackToken}.
 *   <li>Use the associated {@link TrackToken} when {@linkplain #writeSampleData(TrackToken,
 *       ByteBuffer, BufferInfo) writing samples} for that track.
 *   <li>{@link #close} the muxer when all data has been written.
 * </ul>
 *
 * <p>Some key points:
 *
 * <ul>
 *   <li>Tracks can be added at any point, even after writing some samples to other tracks.
 *   <li>The caller is responsible for ensuring that samples of different track types are well
 *       interleaved by calling {@link #writeSampleData(TrackToken, ByteBuffer, BufferInfo)} in an
 *       order that interleaves samples from different tracks.
 *   <li>When writing a file, if an error occurs and the muxer is not closed, then the output MP4
 *       file may still have some partial data.
 * </ul>
 */
@UnstableApi
public final class Mp4Muxer implements Muxer {
  /** Parameters for {@link #FILE_FORMAT_EDITABLE_VIDEO}. */
  public static final class EditableVideoParameters {
    /** Provides temporary cache files to be used by the muxer. */
    public interface CacheFileProvider {

      /**
       * Returns a cache file path.
       *
       * <p>Every call to this method should return a new cache file.
       *
       * <p>The app is responsible for deleting the cache file after {@linkplain Mp4Muxer#close()
       * closing} the muxer.
       */
      String getCacheFilePath();
    }

    public final boolean shouldInterleaveSamples;
    @Nullable public final CacheFileProvider cacheFileProvider;

    /**
     * Creates an instance.
     *
     * @param shouldInterleaveSamples Whether to interleave editable video track samples with
     *     primary track samples.
     * @param cacheFileProvider A {@link CacheFileProvider}. Required only when {@code
     *     shouldInterleaveSamples} is set to {@code false}, can be {@code null} otherwise.
     */
    public EditableVideoParameters(
        boolean shouldInterleaveSamples, @Nullable CacheFileProvider cacheFileProvider) {
      checkArgument(shouldInterleaveSamples || cacheFileProvider != null);
      this.shouldInterleaveSamples = shouldInterleaveSamples;
      this.cacheFileProvider = cacheFileProvider;
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
   * Use the {@link MediaCodec#BUFFER_FLAG_END_OF_STREAM end of stream sample} to set the duration
   * of the last sample.
   *
   * <p>After {@linkplain #writeSampleData writing} all the samples for a track, the app must
   * {@linkplain #writeSampleData write} an empty sample with flag {@link
   * MediaCodec#BUFFER_FLAG_END_OF_STREAM}. The timestamp of this sample should be equal to the
   * desired track duration.
   *
   * <p>Once a sample with flag {@link MediaCodec#BUFFER_FLAG_END_OF_STREAM} is {@linkplain
   * #writeSampleData written}, no more samples can be written for that track.
   *
   * <p>If no explicit {@link MediaCodec#BUFFER_FLAG_END_OF_STREAM} sample is passed, then the
   * duration of the last sample will be same as that of the sample before that.
   */
  public static final int
      LAST_SAMPLE_DURATION_BEHAVIOR_SET_FROM_END_OF_STREAM_BUFFER_OR_DUPLICATE_PREVIOUS = 1;

  /** The specific MP4 file format. */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({FILE_FORMAT_DEFAULT, FILE_FORMAT_EDITABLE_VIDEO})
  public @interface FileFormat {}

  /** The default MP4 format. */
  public static final int FILE_FORMAT_DEFAULT = 0;

  // TODO: b/345219017 - Add spec details.
  /**
   * The editable video file format. In this file format all the tracks with {@linkplain
   * Format#auxiliaryTrackType} set to {@link C#AUXILIARY_TRACK_TYPE_ORIGINAL}, {@link
   * C#AUXILIARY_TRACK_TYPE_DEPTH_LINEAR}, {@link C#AUXILIARY_TRACK_TYPE_DEPTH_INVERSE}, or {@link
   * C#AUXILIARY_TRACK_TYPE_DEPTH_METADATA} are written in the MP4 edit data (edvd box). The rest of
   * the tracks are written as usual.
   */
  public static final int FILE_FORMAT_EDITABLE_VIDEO = 1;

  /** A builder for {@link Mp4Muxer} instances. */
  public static final class Builder {
    private final FileOutputStream outputStream;

    private @LastSampleDurationBehavior int lastSampleDurationBehavior;
    @Nullable private AnnexBToAvccConverter annexBToAvccConverter;
    private boolean sampleCopyEnabled;
    private boolean attemptStreamableOutputEnabled;
    private @FileFormat int outputFileFormat;
    @Nullable private EditableVideoParameters editableVideoParameters;

    /**
     * Creates a {@link Builder} instance with default values.
     *
     * @param outputStream The {@link FileOutputStream} to write the media data to.
     */
    public Builder(FileOutputStream outputStream) {
      this.outputStream = outputStream;
      lastSampleDurationBehavior =
          LAST_SAMPLE_DURATION_BEHAVIOR_SET_FROM_END_OF_STREAM_BUFFER_OR_DUPLICATE_PREVIOUS;
      sampleCopyEnabled = true;
      attemptStreamableOutputEnabled = true;
      outputFileFormat = FILE_FORMAT_DEFAULT;
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
     * <p>If the sample copy is enabled, {@link #writeSampleData(TrackToken, ByteBuffer,
     * BufferInfo)} copies the input {@link ByteBuffer} and {@link BufferInfo} before it returns, so
     * it is safe to reuse them immediately. Otherwise, the muxer takes ownership of the {@link
     * ByteBuffer} and the {@link BufferInfo} and the caller must not modify them.
     *
     * <p>The default value is {@code true}.
     */
    @CanIgnoreReturnValue
    public Mp4Muxer.Builder setSampleCopyEnabled(boolean enabled) {
      this.sampleCopyEnabled = enabled;
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
     * <p>For {@link #FILE_FORMAT_EDITABLE_VIDEO}, {@link EditableVideoParameters} must also be
     * {@linkplain #setEditableVideoParameters(EditableVideoParameters)} set}.
     */
    @CanIgnoreReturnValue
    public Mp4Muxer.Builder setOutputFileFormat(@FileFormat int fileFormat) {
      this.outputFileFormat = fileFormat;
      return this;
    }

    /** Sets the {@link EditableVideoParameters}. */
    @CanIgnoreReturnValue
    public Mp4Muxer.Builder setEditableVideoParameters(
        EditableVideoParameters editableVideoParameters) {
      this.editableVideoParameters = editableVideoParameters;
      return this;
    }

    /** Builds an {@link Mp4Muxer} instance. */
    public Mp4Muxer build() {
      checkArgument(
          outputFileFormat == FILE_FORMAT_EDITABLE_VIDEO
              ? editableVideoParameters != null
              : editableVideoParameters == null,
          "EditablevideoParameters must be set for FILE_FORMAT_EDITABLE_VIDEO");
      return new Mp4Muxer(
          outputStream,
          lastSampleDurationBehavior,
          annexBToAvccConverter == null ? AnnexBToAvccConverter.DEFAULT : annexBToAvccConverter,
          sampleCopyEnabled,
          attemptStreamableOutputEnabled,
          outputFileFormat,
          editableVideoParameters);
    }
  }

  private static final String TAG = "Mp4Muxer";

  private final FileOutputStream outputStream;
  private final FileChannel outputChannel;
  private final @LastSampleDurationBehavior int lastSampleDurationBehavior;
  private final AnnexBToAvccConverter annexBToAvccConverter;
  private final boolean sampleCopyEnabled;
  private final boolean attemptStreamableOutputEnabled;
  private final @FileFormat int outputFileFormat;
  @Nullable private final EditableVideoParameters editableVideoParameters;
  private final MetadataCollector metadataCollector;
  private final Mp4Writer mp4Writer;
  private final List<Track> editableVideoTracks;

  @Nullable private String cacheFilePath;
  @Nullable private FileOutputStream cacheFileOutputStream;
  @Nullable private MetadataCollector editableVideoMetadataCollector;
  @Nullable private Mp4Writer editableVideoMp4Writer;

  private Mp4Muxer(
      FileOutputStream outputStream,
      @LastSampleDurationBehavior int lastFrameDurationBehavior,
      AnnexBToAvccConverter annexBToAvccConverter,
      boolean sampleCopyEnabled,
      boolean attemptStreamableOutputEnabled,
      @FileFormat int outputFileFormat,
      @Nullable EditableVideoParameters editableVideoParameters) {
    this.outputStream = outputStream;
    outputChannel = outputStream.getChannel();
    this.lastSampleDurationBehavior = lastFrameDurationBehavior;
    this.annexBToAvccConverter = annexBToAvccConverter;
    this.sampleCopyEnabled = sampleCopyEnabled;
    this.attemptStreamableOutputEnabled = attemptStreamableOutputEnabled;
    this.outputFileFormat = outputFileFormat;
    this.editableVideoParameters = editableVideoParameters;
    metadataCollector = new MetadataCollector();
    mp4Writer =
        new Mp4Writer(
            outputChannel,
            metadataCollector,
            annexBToAvccConverter,
            lastFrameDurationBehavior,
            sampleCopyEnabled,
            attemptStreamableOutputEnabled);
    editableVideoTracks = new ArrayList<>();
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
   * @return A unique {@link TrackToken}. It should be used in {@link #writeSampleData}.
   * @throws MuxerException If an error occurs while adding track.
   */
  @Override
  public TrackToken addTrack(Format format) throws MuxerException {
    return addTrack(/* sortKey= */ 1, format);
  }

  /**
   * Adds a track of the given media format.
   *
   * <p>Tracks can be added at any point before the muxer is closed, even after writing samples to
   * other tracks.
   *
   * <p>The final order of tracks is determined by the provided sort key. Tracks with a lower sort
   * key will always have a lower track id than tracks with a higher sort key. Ordering between
   * tracks with the same sort key is not specified.
   *
   * @param sortKey The key used for sorting the track list.
   * @param format The {@link Format} for the track.
   * @return A unique {@link TrackToken}. It should be used in {@link #writeSampleData}.
   * @throws MuxerException If an error occurs while adding track.
   */
  public TrackToken addTrack(int sortKey, Format format) throws MuxerException {
    if (outputFileFormat == FILE_FORMAT_EDITABLE_VIDEO && isEditableVideoTrack(format)) {
      if (checkNotNull(editableVideoParameters).shouldInterleaveSamples) {
        // Editable video tracks are handled by the primary Mp4Writer.
        return mp4Writer.addEditableVideoTrack(sortKey, format);
      }
      try {
        ensureSetupForEditableVideoTracks();
      } catch (FileNotFoundException e) {
        throw new MuxerException("Cache file not found", e);
      }
      Track track = editableVideoMp4Writer.addTrack(sortKey, format);
      editableVideoTracks.add(track);
      return track;
    }
    return mp4Writer.addTrack(sortKey, format);
  }

  /**
   * {@inheritDoc}
   *
   * <p>Samples are written to the file in batches. If {@link Builder#setSampleCopyEnabled(boolean)
   * sample copying} is disabled, the {@code byteBuffer} and the {@code bufferInfo} must not be
   * modified after calling this method. Otherwise, they are copied and it is safe to modify them
   * after this method returns.
   *
   * @param trackToken The {@link TrackToken} for which this sample is being written.
   * @param byteBuffer The encoded sample. The muxer takes ownership of the buffer if {@link
   *     Builder#setSampleCopyEnabled(boolean) sample copying} is disabled. Otherwise, the position
   *     of the buffer is updated but the caller retains ownership.
   * @param bufferInfo The {@link BufferInfo} related to this sample.
   * @throws MuxerException If an error occurs while writing data to the output file.
   */
  @Override
  public void writeSampleData(TrackToken trackToken, ByteBuffer byteBuffer, BufferInfo bufferInfo)
      throws MuxerException {
    checkState(trackToken instanceof Track);
    Track track = (Track) trackToken;
    try {
      if (editableVideoTracks.contains(trackToken)) {
        checkNotNull(editableVideoMp4Writer).writeSampleData(track, byteBuffer, bufferInfo);
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
      finishWritingEditableVideoTracks();
      finishWritingPrimaryVideoTracks();
      appendEditableVideoTracksDataToTheOutputFile();
    } catch (IOException e) {
      exception = new MuxerException("Failed to finish writing data", e);
    }
    try {
      outputStream.close();
    } catch (IOException e) {
      if (exception == null) {
        exception = new MuxerException("Failed to close output stream", e);
      } else {
        Log.e(TAG, "Failed to close output stream", e);
      }
    }
    if (cacheFileOutputStream != null) {
      try {
        cacheFileOutputStream.close();
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

  @EnsuresNonNull({"editableVideoMp4Writer"})
  private void ensureSetupForEditableVideoTracks() throws FileNotFoundException {
    if (editableVideoMp4Writer == null) {
      cacheFilePath =
          checkNotNull(checkNotNull(editableVideoParameters).cacheFileProvider).getCacheFilePath();
      cacheFileOutputStream = new FileOutputStream(cacheFilePath);
      editableVideoMetadataCollector = new MetadataCollector();
      editableVideoMp4Writer =
          new Mp4Writer(
              cacheFileOutputStream.getChannel(),
              checkNotNull(editableVideoMetadataCollector),
              annexBToAvccConverter,
              lastSampleDurationBehavior,
              sampleCopyEnabled,
              attemptStreamableOutputEnabled);
    }
  }

  private void finishWritingEditableVideoTracks() throws IOException {
    if (editableVideoMp4Writer == null) {
      // Editable video tracks were not added.
      return;
    }
    populateEditableVideoTracksMetadata(
        checkNotNull(editableVideoMetadataCollector),
        metadataCollector.timestampData,
        /* samplesInterleaved= */ false,
        editableVideoTracks);
    checkNotNull(editableVideoMp4Writer).finishWritingSamplesAndFinalizeMoovBox();
  }

  private void finishWritingPrimaryVideoTracks() throws IOException {
    // The exact offset is known after writing all the data in mp4Writer.
    MdtaMetadataEntry placeholderEditableTrackOffset = getEditableTracksOffsetMetadata(0L);
    if (editableVideoMp4Writer != null) {
      long editableVideoDataSize = checkNotNull(cacheFileOutputStream).getChannel().size();
      long edvdBoxSize = LARGE_SIZE_BOX_HEADER_SIZE + editableVideoDataSize;
      metadataCollector.addMetadata(getEditableTracksLengthMetadata(edvdBoxSize));
      metadataCollector.addMetadata(placeholderEditableTrackOffset);
    }
    mp4Writer.finishWritingSamplesAndFinalizeMoovBox();
    if (editableVideoMp4Writer != null) {
      long primaryVideoDataSize = outputChannel.size();
      metadataCollector.removeMdtaMetadataEntry(placeholderEditableTrackOffset);
      metadataCollector.addMetadata(getEditableTracksOffsetMetadata(primaryVideoDataSize));
      mp4Writer.finalizeMoovBox();
      checkState(
          outputChannel.size() == primaryVideoDataSize,
          "The editable tracks offset should remain the same");
    }
  }

  private void appendEditableVideoTracksDataToTheOutputFile() throws IOException {
    if (editableVideoMp4Writer == null) {
      // Editable video tracks were not added.
      return;
    }
    outputChannel.position(outputChannel.size());
    FileInputStream inputStream = new FileInputStream(checkNotNull(cacheFilePath));
    outputChannel.write(getEdvdBoxHeader(inputStream.getChannel().size()));
    ByteStreams.copy(inputStream, outputStream);
    inputStream.close();
  }
}
