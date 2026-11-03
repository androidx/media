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
package androidx.media3.transformer.codecdb;

import static androidx.media3.transformer.codecdb.CodecDbUtil.validateDimensions;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import androidx.media3.common.Format;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.transformer.VideoEncoderSettings;
import com.google.common.base.MoreObjects;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The complete configuration of a Video Encoder, including which {@link android.media.MediaCodec}
 * is used, the settings to apply, and the bitrate to use.
 */
@ExperimentalApi // TODO(b/579130160): Promote to @UnstableApi or remove.
public final class VideoEncoderConfiguration {

  /**
   * The {@link Format} the codec described by this configuration produces.
   *
   * <p>{@link Format#sampleMimeType} is always set. {@link Format#width}, {@link Format#height},
   * {@link Format#frameRate} and {@link Format#averageBitrate} are {@link Format#NO_VALUE} if not
   * configured.
   */
  public final Format format;

  /**
   * The {@link VideoEncoderSettings} used to configure the codec described by this configuration.
   */
  public final VideoEncoderSettings videoEncoderSettings;

  /** Whether this is a fallback configuration. */
  public final boolean isFallback;

  /**
   * Creates an instance.
   *
   * <p>{@code format} must have a non-null {@link Format#sampleMimeType}. It may optionally specify
   * the resolution ({@link Format#width}, {@link Format#height}, and {@link Format#frameRate}) and
   * bitrate ({@link Format#averageBitrate}); unset fields must be left at {@link Format#NO_VALUE},
   * and any set resolution or bitrate fields must be strictly positive (with all three resolution
   * fields set together).
   *
   * @param videoEncoderSettings The {@link VideoEncoderSettings} used to configure the encoder.
   * @param format The {@link Format} of the encoded video stream.
   * @param isFallback Whether this is a fallback configuration.
   * @throws IllegalArgumentException If {@link Format#sampleMimeType} is {@code null}, if only a
   *     subset of resolution fields ({@link Format#width}, {@link Format#height}, {@link
   *     Format#frameRate}) are set, or if any set resolution or bitrate field is not strictly
   *     positive.
   */
  public VideoEncoderConfiguration(
      VideoEncoderSettings videoEncoderSettings, Format format, boolean isFallback) {
    checkNotNull(videoEncoderSettings, "videoEncoderSettings must not be null");
    checkNotNull(format, "format must not be null");
    checkArgument(format.sampleMimeType != null, "format.sampleMimeType must not be null");
    if (format.width != Format.NO_VALUE
        || format.height != Format.NO_VALUE
        || format.frameRate != Format.NO_VALUE) {
      validateDimensions(format.width, format.height, format.frameRate);
    }
    checkArgument(
        format.averageBitrate == Format.NO_VALUE || format.averageBitrate > 0,
        "Bitrate must be Format.NO_VALUE or strictly greater than 0: %s",
        format.averageBitrate);
    this.format = format;
    this.videoEncoderSettings = videoEncoderSettings;
    this.isFallback = isFallback;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("mimeType", format.sampleMimeType)
        .add("videoEncoderSettings", videoEncoderSettings)
        .add("width", format.width)
        .add("height", format.height)
        .add("frameRate", format.frameRate)
        .add("bitrate", format.averageBitrate)
        .add("isFallback", isFallback)
        .toString();
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof VideoEncoderConfiguration)) {
      return false;
    }
    VideoEncoderConfiguration that = (VideoEncoderConfiguration) o;
    return format.equals(that.format)
        && Objects.equals(videoEncoderSettings, that.videoEncoderSettings)
        && isFallback == that.isFallback;
  }

  @Override
  public int hashCode() {
    return Objects.hash(format, videoEncoderSettings, isFallback);
  }
}
