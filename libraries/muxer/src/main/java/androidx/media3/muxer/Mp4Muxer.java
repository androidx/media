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
import static java.lang.annotation.ElementType.TYPE_USE;

import android.media.MediaCodec.BufferInfo;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.container.MdtaMetadataEntry;
import androidx.media3.container.Mp4LocationData;
import androidx.media3.container.Mp4OrientationData;
import androidx.media3.container.Mp4TimestampData;
import androidx.media3.container.XmpData;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.ByteBuffer;

/**
 * A muxer for creating an MP4 container file.
 *
 * <p>The muxer supports writing H264, H265 and AV1 video, AAC audio and metadata.
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

  /** Behavior for the last sample duration. */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({
    LAST_FRAME_DURATION_BEHAVIOR_DUPLICATE_PREV_DURATION,
    LAST_FRAME_DURATION_BEHAVIOR_INSERT_SHORT_FRAME
  })
  public @interface LastFrameDurationBehavior {}

  /** Insert a zero-length last sample. */
  public static final int LAST_FRAME_DURATION_BEHAVIOR_INSERT_SHORT_FRAME = 0;

  /**
   * Use the difference between the last timestamp and the one before that as the duration of the
   * last sample.
   */
  public static final int LAST_FRAME_DURATION_BEHAVIOR_DUPLICATE_PREV_DURATION = 1;

  /** A builder for {@link Mp4Muxer} instances. */
  public static final class Builder {
    private final FileOutputStream fileOutputStream;

    private @LastFrameDurationBehavior int lastFrameDurationBehavior;
    @Nullable private AnnexBToAvccConverter annexBToAvccConverter;
    private boolean sampleCopyEnabled;
    private boolean attemptStreamableOutputEnabled;

    /**
     * Creates a {@link Builder} instance with default values.
     *
     * @param fileOutputStream The {@link FileOutputStream} to write the media data to.
     */
    public Builder(FileOutputStream fileOutputStream) {
      this.fileOutputStream = checkNotNull(fileOutputStream);
      lastFrameDurationBehavior = LAST_FRAME_DURATION_BEHAVIOR_INSERT_SHORT_FRAME;
      sampleCopyEnabled = true;
      attemptStreamableOutputEnabled = true;
    }

    /**
     * Sets the {@link LastFrameDurationBehavior} for the video track.
     *
     * <p>The default value is {@link #LAST_FRAME_DURATION_BEHAVIOR_INSERT_SHORT_FRAME}.
     */
    @CanIgnoreReturnValue
    public Mp4Muxer.Builder setLastFrameDurationBehavior(
        @LastFrameDurationBehavior int lastFrameDurationBehavior) {
      this.lastFrameDurationBehavior = lastFrameDurationBehavior;
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

    /** Builds an {@link Mp4Muxer} instance. */
    public Mp4Muxer build() {
      MetadataCollector metadataCollector = new MetadataCollector();
      Mp4MoovStructure moovStructure =
          new Mp4MoovStructure(metadataCollector, lastFrameDurationBehavior);
      Mp4Writer mp4Writer =
          new Mp4Writer(
              fileOutputStream,
              moovStructure,
              annexBToAvccConverter == null ? AnnexBToAvccConverter.DEFAULT : annexBToAvccConverter,
              sampleCopyEnabled,
              attemptStreamableOutputEnabled);

      return new Mp4Muxer(mp4Writer, metadataCollector);
    }
  }

  private final Mp4Writer mp4Writer;
  private final MetadataCollector metadataCollector;

  private Mp4Muxer(Mp4Writer mp4Writer, MetadataCollector metadataCollector) {
    this.mp4Writer = mp4Writer;
    this.metadataCollector = metadataCollector;
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
   */
  @Override
  public TrackToken addTrack(Format format) {
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
   */
  public TrackToken addTrack(int sortKey, Format format) {
    return mp4Writer.addTrack(sortKey, format);
  }

  /**
   * {@inheritDoc}
   *
   * <p>Samples are written to the disk in batches. If {@link Builder#setSampleCopyEnabled(boolean)
   * sample copying} is disabled, the {@code byteBuffer} and the {@code bufferInfo} must not be
   * modified after calling this method. Otherwise, they are copied and it is safe to modify them
   * after this method returns.
   *
   * @param trackToken The {@link TrackToken} for which this sample is being written.
   * @param byteBuffer The encoded sample. The muxer takes ownership of the buffer if {@link
   *     Builder#setSampleCopyEnabled(boolean) sample copying} is disabled. Otherwise, the position
   *     of the buffer is updated but the caller retains ownership.
   * @param bufferInfo The {@link BufferInfo} related to this sample.
   * @throws MuxerException If there is any error while writing data to the disk.
   */
  @Override
  public void writeSampleData(TrackToken trackToken, ByteBuffer byteBuffer, BufferInfo bufferInfo)
      throws MuxerException {
    try {
      mp4Writer.writeSampleData(trackToken, byteBuffer, bufferInfo);
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
    checkArgument(Mp4Utils.isMetadataSupported(metadataEntry), "Unsupported metadata");
    metadataCollector.addMetadata(metadataEntry);
  }

  @Override
  public void close() throws MuxerException {
    try {
      mp4Writer.close();
    } catch (IOException e) {
      throw new MuxerException("Failed to close the muxer", e);
    }
  }
}
