/*
 * Copyright 2025 The Android Open Source Project
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

import static androidx.media3.muxer.MuxerUtil.getMuxerBufferInfoFromMediaCodecBufferInfo;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.system.ErrnoException;
import android.system.Os;
import androidx.annotation.FloatRange;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.MediaFormatUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.container.MdtaMetadataEntry;
import androidx.media3.container.Mp4LocationData;
import androidx.media3.container.Mp4OrientationData;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.ByteBuffer;

/**
 * A drop-in replacement for {@link MediaMuxer} that provides similar functionality, based on the
 * {@code media3.muxer} logic.
 *
 * <p>Currently only MP4 file format is supported.
 *
 * <p>Supported codecs are:
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
 * </ul>
 *
 * <p>All the methods should be called from the same thread.
 *
 * <p>All the operations are performed on the caller thread.
 */
public final class MediaMuxerCompat {
  /** The output file format. */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({OUTPUT_FORMAT_MP4})
  @UnstableApi
  public @interface OutputFormat {}

  /** The MP4 file format. */
  public static final int OUTPUT_FORMAT_MP4 = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4;

  @Nullable private final FileDescriptor fileDescriptor;
  private final Muxer muxer;

  private boolean startedMuxer;
  private boolean closedMuxer;

  /**
   * Creates an instance.
   *
   * <p>It is the caller's responsibility to close the {@code fileDescriptor}, which is safe to do
   * so as soon as this call returns.
   *
   * @param fileDescriptor A {@link FileDescriptor} for the output media file. It must represent a
   *     local file that is open in read-write mode.
   * @param outputFormat The {@link OutputFormat}.
   * @throws IOException If an error occurs while performing an I/O operation.
   */
  public MediaMuxerCompat(FileDescriptor fileDescriptor, @OutputFormat int outputFormat)
      throws IOException {
    try {
      this.fileDescriptor = Os.dup(fileDescriptor);
    } catch (ErrnoException e) {
      throw new IOException("Failed to create a copy of FileDescriptor", e);
    }
    muxer = createMuxer(new FileOutputStream(this.fileDescriptor), outputFormat);
  }

  /**
   * Creates an instance.
   *
   * @param filePath The path of the output media file.
   * @param outputFormat The {@link OutputFormat}.
   * @throws IOException If an error occurs while performing an I/O operation.
   */
  public MediaMuxerCompat(String filePath, @OutputFormat int outputFormat) throws IOException {
    fileDescriptor = null;
    muxer = createMuxer(new FileOutputStream(filePath), outputFormat);
  }

  /**
   * Starts the muxer.
   *
   * <p>This must be called after {@link #addTrack(MediaFormat)} and before {@link
   * #writeSampleData(int, ByteBuffer, MediaCodec.BufferInfo)}.
   *
   * @see MediaMuxer#start()
   */
  public void start() {
    checkState(!startedMuxer);
    checkState(!closedMuxer);
    startedMuxer = true;
  }

  /**
   * Adds a track of the given media format.
   *
   * <p>All tracks must be added before any samples are written to any track.
   *
   * <p>{@link MediaFormat#KEY_CAPTURE_RATE} is used to write {@link
   * MdtaMetadataEntry#KEY_ANDROID_CAPTURE_FPS} metadata in the MP4 file.
   *
   * @see MediaMuxer#addTrack(MediaFormat)
   * @param format The {@link MediaFormat} of the track.
   * @return A track index for this track, which should be passed to {@link #writeSampleData(int,
   *     ByteBuffer, MediaCodec.BufferInfo)}.
   */
  public int addTrack(MediaFormat format) {
    checkState(!startedMuxer);
    try {
      float captureFps =
          MediaFormatUtil.getFloatFromIntOrFloat(
              format, MediaFormat.KEY_CAPTURE_RATE, C.RATE_UNSET);
      if (captureFps != C.RATE_UNSET) {
        MdtaMetadataEntry captureFpsMetadata =
            new MdtaMetadataEntry(
                MdtaMetadataEntry.KEY_ANDROID_CAPTURE_FPS,
                /* value= */ Util.toByteArray(captureFps),
                MdtaMetadataEntry.TYPE_INDICATOR_FLOAT32);
        muxer.addMetadataEntry(captureFpsMetadata);
      }
      return muxer.addTrack(MediaFormatUtil.createFormatFromMediaFormat(format));
    } catch (MuxerException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Writes encoded sample data.
   *
   * <p>To set the duration of the last sample in a track, an additional empty buffer ({@code
   * bufferInfo.size = 0}) with {@link MediaCodec#BUFFER_FLAG_END_OF_STREAM} flag and a suitable
   * presentation timestamp must be passed as the last sample of that track. This timestamp should
   * be the sum of the desired duration and the presentation timestamp of the original last sample.
   * If no explicit END_OF_STREAM sample is provided, the last sample's duration will be equal to
   * the previous sample's duration.
   *
   * @see MediaMuxer#writeSampleData(int, ByteBuffer, MediaCodec.BufferInfo)
   * @param trackIndex The track index, previously returned by {@link #addTrack(MediaFormat)}.
   * @param byteBuffer A buffer containing the sample data to write.
   * @param bufferInfo The {@link MediaCodec.BufferInfo} of the sample.
   */
  public void writeSampleData(
      int trackIndex, ByteBuffer byteBuffer, MediaCodec.BufferInfo bufferInfo) {
    checkState(startedMuxer);
    try {
      muxer.writeSampleData(
          trackIndex, byteBuffer, getMuxerBufferInfoFromMediaCodecBufferInfo(bufferInfo));
    } catch (MuxerException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Sets the location of the media file.
   *
   * <p>It must be called before {@link #start()}.
   *
   * @see MediaMuxer#setLocation(float, float)
   * @param latitude The latitude, in degrees. Its value must be in the range [-90, 90].
   * @param longitude The longitude, in degrees. Its value must be in the range [-180, 180].
   */
  public void setLocation(
      @FloatRange(from = -90.0, to = 90.0) float latitude,
      @FloatRange(from = -180.0, to = 180.0) float longitude) {
    checkState(!startedMuxer);
    muxer.addMetadataEntry(new Mp4LocationData(latitude, longitude));
  }

  /**
   * Sets the orientation hint for the media file.
   *
   * <p>It must be called before {@link #start()}.
   *
   * @see MediaMuxer#setOrientationHint(int)
   * @param degrees The orientation, in degrees. The supported values are 0, 90, 180 and 270
   *     (degrees).
   */
  public void setOrientationHint(int degrees) {
    checkState(!startedMuxer);
    muxer.addMetadataEntry(new Mp4OrientationData(degrees));
  }

  /**
   * Stops the muxer.
   *
   * <p>Once the muxer is stopped, it can not be restarted.
   *
   * @see MediaMuxer#stop()
   */
  public void stop() {
    checkState(startedMuxer);
    closeMuxer();
  }

  /**
   * Releases the underlying resources.
   *
   * <p>It should be called after {@link #stop()}.
   *
   * @see MediaMuxer#release()
   */
  public void release() {
    if (!closedMuxer) {
      closeMuxer();
    }
  }

  private void closeMuxer() {
    try {
      muxer.close();
      if (fileDescriptor != null) {
        Os.close(fileDescriptor);
      }
      closedMuxer = true;
      startedMuxer = false;
    } catch (MuxerException | ErrnoException e) {
      throw new RuntimeException(e);
    }
  }

  private static Muxer createMuxer(
      FileOutputStream fileOutputStream, @OutputFormat int outputFormat) {
    checkArgument(outputFormat == OUTPUT_FORMAT_MP4);
    return new Mp4Muxer.Builder(SeekableMuxerOutput.of(fileOutputStream)).build();
  }
}
