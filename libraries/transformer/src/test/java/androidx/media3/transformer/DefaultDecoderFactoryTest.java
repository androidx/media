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
package androidx.media3.transformer;

import static androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig.CODEC_INFO_AVC;
import static androidx.media3.transformer.ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.media.MediaCodecInfo.CodecCapabilities;
import android.media.MediaFormat;
import android.view.Surface;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link DefaultDecoderFactory}. */
@RunWith(AndroidJUnit4.class)
public final class DefaultDecoderFactoryTest {

  @Rule
  public final ShadowMediaCodecConfig shadowMediaCodecConfig =
      ShadowMediaCodecConfig.withCodecs(
          /* decoders= */ ImmutableList.of(CODEC_INFO_AVC), /* encoders= */ ImmutableList.of());

  private final Context context = getApplicationContext();
  private SurfaceTexture surfaceTexture;
  private Surface surface;
  private @Nullable DefaultCodec codec;

  @Before
  public void setUp() {
    surfaceTexture = new SurfaceTexture(/* texName= */ 0);
    surface = new Surface(surfaceTexture);
  }

  @After
  public void tearDown() {
    if (codec != null) {
      codec.release();
    }
    surface.release();
    surfaceTexture.release();
  }

  @Test
  public void createForVideoDecoding_withDolbyVisionProfile20_throws() {
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dvh1.20.01")
            .setWidth(1920)
            .setHeight(1080)
            .build();
    DefaultDecoderFactory decoderFactory = new DefaultDecoderFactory.Builder(context).build();

    ExportException exception =
        assertThrows(
            ExportException.class,
            () ->
                decoderFactory.createForVideoDecoding(
                    format, surface, /* requestSdrToneMapping= */ false, /* logSessionId= */ null));
    assertThat(exception.errorCode).isEqualTo(ERROR_CODE_DECODING_FORMAT_UNSUPPORTED);
  }

  @Test
  public void createForVideoDecoding_withDolbyVisionNullCodecs_throws() {
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setWidth(1920)
            .setHeight(1080)
            .build();
    DefaultDecoderFactory decoderFactory = new DefaultDecoderFactory.Builder(context).build();

    ExportException exception =
        assertThrows(
            ExportException.class,
            () ->
                decoderFactory.createForVideoDecoding(
                    format, surface, /* requestSdrToneMapping= */ false, /* logSessionId= */ null));
    assertThat(exception.errorCode).isEqualTo(ERROR_CODE_DECODING_FORMAT_UNSUPPORTED);
  }

  @Test
  public void createForVideoDecoding_yuv420888_setsFlexibleYuvColorFormat() throws Exception {
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setWidth(1920)
            .setHeight(1080)
            .setPixelFormat(ImageFormat.YUV_420_888)
            .build();
    DefaultDecoderFactory decoderFactory = new DefaultDecoderFactory.Builder(context).build();

    codec =
        decoderFactory.createForVideoDecoding(
            format, surface, /* requestSdrToneMapping= */ false, /* logSessionId= */ null);

    assertThat(codec.getConfigurationMediaFormat().getInteger(MediaFormat.KEY_COLOR_FORMAT))
        .isEqualTo(CodecCapabilities.COLOR_FormatYUV420Flexible);
  }

  @Test
  public void createForVideoDecoding_noPixelFormat_omitsColorFormat() throws Exception {
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setWidth(1920)
            .setHeight(1080)
            .build();
    DefaultDecoderFactory decoderFactory = new DefaultDecoderFactory.Builder(context).build();

    codec =
        decoderFactory.createForVideoDecoding(
            format, surface, /* requestSdrToneMapping= */ false, /* logSessionId= */ null);

    assertThat(codec.getConfigurationMediaFormat().containsKey(MediaFormat.KEY_COLOR_FORMAT))
        .isFalse();
  }
}
