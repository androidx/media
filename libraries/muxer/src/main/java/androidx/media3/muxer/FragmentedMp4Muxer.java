/*
 * Copyright 2024 The Android Open Source Project
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

import static com.google.common.base.Preconditions.checkArgument;

import android.util.SparseArray;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.container.MdtaMetadataEntry;
import androidx.media3.container.Mp4LocationData;
import androidx.media3.container.Mp4OrientationData;
import androidx.media3.container.Mp4TimestampData;
import androidx.media3.container.XmpData;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.InlineMe;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;

/**
 * A muxer for creating a fragmented MP4 file.
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
 * <p>To create a fragmented MP4 file, the caller must:
 *
 * <ul>
 *   <li>Add tracks using {@link #addTrack(Format)} which will return a track id.
 *   <li>Use the associated track id when {@linkplain #writeSampleData(int, ByteBuffer, BufferInfo)
 *       writing samples} for that track.
 *   <li>{@link #close} the muxer when all data has been written.
 * </ul>
 *
 * <p>Some key points:
 *
 * <ul>
 *   <li>All tracks must be added before writing any samples.
 *   <li>The caller is responsible for ensuring that samples of different track types are well
 *       interleaved by calling {@link #writeSampleData(int, ByteBuffer, BufferInfo)} in an order
 *       that interleaves samples from different tracks.
 * </ul>
 */
@UnstableApi
public final class FragmentedMp4Muxer implements Muxer {
  /** The default fragment duration. */
  public static final long DEFAULT_FRAGMENT_DURATION_MS = 2_000;

  /** A builder for {@link FragmentedMp4Muxer} instances. */
  public static final class Builder {
    private final WritableByteChannel outputChannel;

    private long fragmentDurationMs;
    private boolean sampleCopyEnabled;

    /**
     * @deprecated Use {@link FragmentedMp4Muxer.Builder#Builder(WritableByteChannel)} instead.
     */
    @InlineMe(
        replacement = "this(Channels.newChannel(outputStream))",
        imports = "java.nio.channels.Channels")
    @Deprecated
    public Builder(OutputStream outputStream) {
      this(Channels.newChannel(outputStream));
    }

    /**
     * Creates a {@link Builder} instance with default values.
     *
     * @param outputChannel The {@link WritableByteChannel} to write the media data to. This channel
     *     will be automatically closed by the muxer when {@link FragmentedMp4Muxer#close()} is
     *     called.
     */
    public Builder(WritableByteChannel outputChannel) {
      this.outputChannel = outputChannel;
      fragmentDurationMs = DEFAULT_FRAGMENT_DURATION_MS;
      sampleCopyEnabled = true;
    }

    /**
     * Sets the fragment duration (in milliseconds).
     *
     * <p>The muxer will attempt to create fragments of the given duration but the actual duration
     * might be greater depending upon the frequency of sync samples.
     *
     * <p>The default value is {@link #DEFAULT_FRAGMENT_DURATION_MS}.
     */
    @CanIgnoreReturnValue
    public Builder setFragmentDurationMs(long fragmentDurationMs) {
      this.fragmentDurationMs = fragmentDurationMs;
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
     * <p>The default value is {@code true}.
     */
    @CanIgnoreReturnValue
    public Builder setSampleCopyingEnabled(boolean enabled) {
      this.sampleCopyEnabled = enabled;
      return this;
    }

    /** Builds a {@link FragmentedMp4Muxer} instance. */
    public FragmentedMp4Muxer build() {
      return new FragmentedMp4Muxer(outputChannel, fragmentDurationMs, sampleCopyEnabled);
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

  private final FragmentedMp4Writer fragmentedMp4Writer;
  private final MetadataCollector metadataCollector;
  private final SparseArray<Track> trackIdToTrack;

  private FragmentedMp4Muxer(
      WritableByteChannel outputChannel, long fragmentDurationMs, boolean sampleCopyEnabled) {
    metadataCollector = new MetadataCollector();
    fragmentedMp4Writer =
        new FragmentedMp4Writer(
            outputChannel,
            metadataCollector,
            AnnexBToAvccConverter.DEFAULT,
            fragmentDurationMs,
            sampleCopyEnabled);
    trackIdToTrack = new SparseArray<>();
  }

  @Override
  public int addTrack(Format format) {
    Track track = fragmentedMp4Writer.addTrack(/* sortKey= */ 1, format);
    trackIdToTrack.append(track.id, track);
    return track.id;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Samples are written to the disk in batches. If {@link
   * Builder#setSampleCopyingEnabled(boolean) sample copying} is disabled, the {@code byteBuffer}
   * and the {@code bufferInfo} must not be modified after calling this method. Otherwise, they are
   * copied and it is safe to modify them after this method returns.
   *
   * <p>Note: Out of order B-frames are currently not supported.
   *
   * @param trackId The track id for which this sample is being written.
   * @param byteBuffer The encoded sample. The muxer takes ownership of the buffer if {@link
   *     Builder#setSampleCopyingEnabled(boolean) sample copying} is disabled. Otherwise, the
   *     position of the buffer is updated but the caller retains ownership.
   * @param bufferInfo The {@link BufferInfo} related to this sample.
   * @throws MuxerException If there is any error while writing data to the disk.
   */
  @Override
  public void writeSampleData(int trackId, ByteBuffer byteBuffer, BufferInfo bufferInfo)
      throws MuxerException {
    try {
      fragmentedMp4Writer.writeSampleData(trackIdToTrack.get(trackId), byteBuffer, bufferInfo);
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
    checkArgument(MuxerUtil.isMetadataSupported(metadataEntry), "Unsupported metadata");
    metadataCollector.addMetadata(metadataEntry);
  }

  @Override
  public void close() throws MuxerException {
    try {
      fragmentedMp4Writer.close();
    } catch (IOException e) {
      throw new MuxerException("Failed to close the muxer", e);
    }
  }
}
