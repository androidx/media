/*
 * Copyright 2026 The Android Open Source Project
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
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A muxer for creating a hybrid MP4 file.
 *
 * <p>Hybrid MP4 writes fragmented MP4 during recording for crash safety (each fragment is
 * independently playable), then converts to a standard non-fragmented MP4 on finalization for broad
 * compatibility.
 *
 * <p>Requires a {@link SeekableMuxerOutput} because finalization needs to seek back and overwrite
 * the placeholder at the start of the file.
 *
 * <p>All the operations are performed on the caller thread.
 *
 * <p>To create a hybrid MP4 file, the caller must:
 *
 * <ul>
 *   <li>Add tracks using {@link #addTrack(Format)} which will return a track id.
 *   <li>Use the associated track id when {@linkplain #writeSampleData(int, ByteBuffer, BufferInfo)
 *       writing samples} for that track.
 *   <li>{@link #close} the muxer when all data has been written.
 * </ul>
 */
@UnstableApi
public final class HybridMp4Muxer implements Muxer {
  /** The default fragment duration. */
  public static final long DEFAULT_FRAGMENT_DURATION_MS = 2_000;

  /** A builder for {@link HybridMp4Muxer} instances. */
  public static final class Builder {
    private final SeekableMuxerOutput seekableMuxerOutput;

    private long fragmentDurationMs;
    private boolean sampleCopyEnabled;

    /**
     * Creates a {@link Builder} instance with default values.
     *
     * @param seekableMuxerOutput The {@link SeekableMuxerOutput} to write the media data to. This
     *     output will be automatically closed by the muxer when {@link HybridMp4Muxer#close()} is
     *     called.
     */
    public Builder(SeekableMuxerOutput seekableMuxerOutput) {
      this.seekableMuxerOutput = seekableMuxerOutput;
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

    /** Builds a {@link HybridMp4Muxer} instance. */
    public HybridMp4Muxer build() {
      return new HybridMp4Muxer(seekableMuxerOutput, fragmentDurationMs, sampleCopyEnabled);
    }
  }

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

  private final HybridMp4Writer hybridMp4Writer;
  private final MetadataCollector metadataCollector;
  private final SparseArray<Track> trackIdToTrack;

  private HybridMp4Muxer(
      SeekableMuxerOutput seekableMuxerOutput,
      long fragmentDurationMs,
      boolean sampleCopyEnabled) {
    metadataCollector = new MetadataCollector();
    hybridMp4Writer =
        new HybridMp4Writer(
            seekableMuxerOutput,
            metadataCollector,
            AnnexBToAvccConverter.DEFAULT,
            fragmentDurationMs,
            sampleCopyEnabled);
    trackIdToTrack = new SparseArray<>();
  }

  @Override
  public int addTrack(Format format) {
    Track track = hybridMp4Writer.addTrack(/* sortKey= */ 1, format);
    trackIdToTrack.append(track.id, track);
    return track.id;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Samples are written to the disk in batches as fragments. If {@link
   * Builder#setSampleCopyingEnabled(boolean) sample copying} is disabled, the {@code byteBuffer}
   * and the {@code bufferInfo} must not be modified after calling this method. Otherwise, they are
   * copied and it is safe to modify them after this method returns.
   *
   * @param trackId The track id for which this sample is being written.
   * @param byteBuffer The encoded sample.
   * @param bufferInfo The {@link BufferInfo} related to this sample.
   * @throws MuxerException If there is any error while writing data to the disk.
   */
  @Override
  public void writeSampleData(int trackId, ByteBuffer byteBuffer, BufferInfo bufferInfo)
      throws MuxerException {
    try {
      hybridMp4Writer.writeSampleData(trackIdToTrack.get(trackId), byteBuffer, bufferInfo);
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
      hybridMp4Writer.close();
    } catch (IOException e) {
      throw new MuxerException("Failed to close the muxer", e);
    }
  }
}
