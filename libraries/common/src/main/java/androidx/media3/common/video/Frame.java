/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.common.video;

import static java.lang.annotation.ElementType.TYPE_USE;

import android.hardware.HardwareBuffer;
import androidx.annotation.LongDef;
import androidx.media3.common.Format;
import androidx.media3.common.util.ExperimentalApi;
import com.google.common.collect.ImmutableMap;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Holds a frame of video data.
 *
 * <p>Pixel data is accessible via subtypes.
 */
@ExperimentalApi // TODO: b/498176910 Remove once Frame is production ready.
public interface Frame {

  /**
   * Flags that define how {@linkplain Frame frames} are used. One of {@link
   * #USAGE_CPU_READ_RARELY}, {@link #USAGE_CPU_READ_OFTEN}, {@link #USAGE_CPU_WRITE_RARELY}, {@link
   * #USAGE_CPU_WRITE_OFTEN}, {@link #USAGE_GPU_SAMPLED_IMAGE}, {@link #USAGE_GPU_COLOR_OUTPUT},
   * {@link #USAGE_COMPOSER_OVERLAY} or {@link #USAGE_VIDEO_ENCODE}.
   */
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @LongDef(
      flag = true,
      value = {
        USAGE_CPU_READ_RARELY,
        USAGE_CPU_READ_OFTEN,
        USAGE_CPU_WRITE_RARELY,
        USAGE_CPU_WRITE_OFTEN,
        USAGE_GPU_SAMPLED_IMAGE,
        USAGE_GPU_COLOR_OUTPUT,
        USAGE_COMPOSER_OVERLAY,
        USAGE_VIDEO_ENCODE
      })
  @interface Usage {}

  /** The frame will sometimes be read by the CPU. */
  @SuppressWarnings("InlinedApi")
  long USAGE_CPU_READ_RARELY = HardwareBuffer.USAGE_CPU_READ_RARELY;

  /** The frame will often be read by the CPU. */
  @SuppressWarnings("InlinedApi")
  long USAGE_CPU_READ_OFTEN = HardwareBuffer.USAGE_CPU_READ_OFTEN;

  /** The frame will sometimes be written to by the CPU. */
  @SuppressWarnings("InlinedApi")
  long USAGE_CPU_WRITE_RARELY = HardwareBuffer.USAGE_CPU_WRITE_RARELY;

  /** The frame will often be written to by the CPU. */
  @SuppressWarnings("InlinedApi")
  long USAGE_CPU_WRITE_OFTEN = HardwareBuffer.USAGE_CPU_WRITE_OFTEN;

  /** The frame will be read from by the GPU. */
  @SuppressWarnings("InlinedApi")
  long USAGE_GPU_SAMPLED_IMAGE = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE;

  /** The frame will be written to by the GPU. */
  @SuppressWarnings("InlinedApi")
  long USAGE_GPU_COLOR_OUTPUT = HardwareBuffer.USAGE_GPU_COLOR_OUTPUT;

  /**
   * The frame will be used as a hardware composer overlay layer. That is, it will be displayed
   * using the system compositor via {@link android.view.SurfaceControl}.
   */
  @SuppressWarnings("InlinedApi")
  long USAGE_COMPOSER_OVERLAY = HardwareBuffer.USAGE_COMPOSER_OVERLAY;

  /** The frame will be read by a hardware video encoder. */
  @SuppressWarnings("InlinedApi")
  long USAGE_VIDEO_ENCODE = HardwareBuffer.USAGE_VIDEO_ENCODE;

  /** The frame format. */
  Format getFormat();

  /** The frame metadata. */
  ImmutableMap<String, Object> getMetadata();

  /**
   * The time of the frame in the context of the input media in microseconds, or {@link
   * androidx.media3.common.C#TIME_UNSET} if it is not set.
   */
  long getContentTimeUs();
}
