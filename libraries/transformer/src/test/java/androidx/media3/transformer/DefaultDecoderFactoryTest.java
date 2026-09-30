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

import static androidx.media3.transformer.ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link DefaultDecoderFactory}. */
@RunWith(AndroidJUnit4.class)
public final class DefaultDecoderFactoryTest {

  private final Context context = getApplicationContext();
  private SurfaceTexture surfaceTexture;
  private Surface surface;

  @Before
  public void setUp() {
    surfaceTexture = new SurfaceTexture(/* texName= */ 0);
    surface = new Surface(surfaceTexture);
  }

  @After
  public void tearDown() {
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
}
