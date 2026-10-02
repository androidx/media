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
package androidx.media3.effect;

import static androidx.media3.effect.DefaultGlFrameProcessor.BT2020_LINEAR;
import static androidx.media3.effect.DefaultGlFrameProcessor.BT709_SRGB;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static org.junit.Assert.assertThrows;

import android.graphics.PixelFormat;
import androidx.media3.common.Format;
import androidx.media3.common.video.DefaultImagePlanesFrame;
import androidx.media3.common.video.DefaultImagePlanesFrame.DefaultPlane;
import androidx.media3.common.video.FrameProcessor;
import androidx.media3.common.video.ImagePlanesFrame;
import androidx.media3.common.video.ImagePlanesFrame.Plane;
import androidx.media3.test.utils.FakeFrameProcessor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Unit tests for {@link ImagePlanesToGlTextureConverter}.
 *
 * <p>Converting valid frames requires an OpenGL ES context, so happy paths are tested in the {@code
 * androidTest} instrumentation tests.
 */
@RunWith(AndroidJUnit4.class)
public final class ImagePlanesToGlTextureConverterTest {

  private static final Executor DIRECT_EXECUTOR = directExecutor();
  private static final FrameProcessor.Listener LISTENER = new FakeFrameProcessor.Listener();
  private static final int DEFAULT_WIDTH = 4;
  private static final int DEFAULT_HEIGHT = 4;
  private static final int RGBA_BYTES_PER_PIXEL = 4;
  private static final int DEFAULT_RGBA_ROW_STRIDE = DEFAULT_WIDTH * RGBA_BYTES_PER_PIXEL;
  private static final Plane DEFAULT_RGBA_PLANE =
      new DefaultPlane(
          ByteBuffer.allocateDirect(DEFAULT_RGBA_ROW_STRIDE * DEFAULT_HEIGHT),
          DEFAULT_RGBA_ROW_STRIDE,
          /* pixelStride= */ RGBA_BYTES_PER_PIXEL);

  private ImagePlanesToGlTextureConverter converter;

  @Before
  public void setUp() {
    converter =
        new ImagePlanesToGlTextureConverter(
            getApplicationContext(), BT709_SRGB, /* errorConsumer= */ error -> {});
  }

  @Test
  public void convert_whenClosed_returnsNull() throws Exception {
    converter.close();
    ImagePlanesFrame frame =
        new DefaultImagePlanesFrame.Builder(ImmutableList.of(DEFAULT_RGBA_PLANE))
            .setFormat(
                new Format.Builder().setWidth(DEFAULT_WIDTH).setHeight(DEFAULT_HEIGHT).build())
            .build();

    assertThat(
            converter.convert(
                frame,
                /* glExecutor= */ DIRECT_EXECUTOR,
                /* listenerExecutor= */ DIRECT_EXECUTOR,
                LISTENER))
        .isNull();
  }

  @Test
  public void convert_unsetFrameDimensions_throwsIllegalArgumentException() {
    ImagePlanesFrame frame =
        new DefaultImagePlanesFrame.Builder(ImmutableList.of(DEFAULT_RGBA_PLANE))
            .setFormat(new Format.Builder().setPixelFormat(PixelFormat.RGBA_8888).build())
            .build();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            converter.convert(
                frame,
                /* glExecutor= */ DIRECT_EXECUTOR,
                /* listenerExecutor= */ DIRECT_EXECUTOR,
                LISTENER));
  }

  @Test
  public void convert_unsetPixelFormat_throwsUnsupportedOperationException() {
    Format format = new Format.Builder().setWidth(DEFAULT_WIDTH).setHeight(DEFAULT_HEIGHT).build();
    ImagePlanesFrame frame =
        new DefaultImagePlanesFrame.Builder(ImmutableList.of(DEFAULT_RGBA_PLANE))
            .setFormat(format)
            .build();

    assertThrows(
        UnsupportedOperationException.class,
        () ->
            converter.convert(
                frame,
                /* glExecutor= */ DIRECT_EXECUTOR,
                /* listenerExecutor= */ DIRECT_EXECUTOR,
                LISTENER));
  }

  @Test
  public void convert_unsupportedPixelFormat_throwsUnsupportedOperationException() {
    Format format =
        new Format.Builder()
            .setWidth(DEFAULT_WIDTH)
            .setHeight(DEFAULT_HEIGHT)
            .setPixelFormat(PixelFormat.RGB_565)
            .build();
    ImagePlanesFrame frame =
        new DefaultImagePlanesFrame.Builder(ImmutableList.of(DEFAULT_RGBA_PLANE))
            .setFormat(format)
            .build();

    assertThrows(
        UnsupportedOperationException.class,
        () ->
            converter.convert(
                frame,
                /* glExecutor= */ DIRECT_EXECUTOR,
                /* listenerExecutor= */ DIRECT_EXECUTOR,
                LISTENER));
  }

  @Test
  public void convert_bt2020ColorSpace_throwsIllegalArgumentException() {
    Format format =
        new Format.Builder()
            .setWidth(DEFAULT_WIDTH)
            .setHeight(DEFAULT_HEIGHT)
            .setPixelFormat(PixelFormat.RGBA_8888)
            .setColorInfo(BT2020_LINEAR)
            .build();
    ImagePlanesFrame frame =
        new DefaultImagePlanesFrame.Builder(ImmutableList.of(DEFAULT_RGBA_PLANE))
            .setFormat(format)
            .build();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            converter.convert(
                frame,
                /* glExecutor= */ DIRECT_EXECUTOR,
                /* listenerExecutor= */ DIRECT_EXECUTOR,
                LISTENER));
  }

  @Test
  public void convert_multiplePlanesForRgba_throwsIllegalArgumentException() {
    Format format =
        new Format.Builder()
            .setWidth(DEFAULT_WIDTH)
            .setHeight(DEFAULT_HEIGHT)
            .setPixelFormat(PixelFormat.RGBA_8888)
            .build();
    ImagePlanesFrame frame =
        new DefaultImagePlanesFrame.Builder(
                ImmutableList.of(DEFAULT_RGBA_PLANE, DEFAULT_RGBA_PLANE))
            .setFormat(format)
            .build();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            converter.convert(
                frame,
                /* glExecutor= */ DIRECT_EXECUTOR,
                /* listenerExecutor= */ DIRECT_EXECUTOR,
                LISTENER));
  }

  @Test
  public void convert_invalidPixelStride_throwsIllegalArgumentException() {
    Format format =
        new Format.Builder()
            .setWidth(DEFAULT_WIDTH)
            .setHeight(DEFAULT_HEIGHT)
            .setPixelFormat(PixelFormat.RGBA_8888)
            .build();
    // RGBA_8888 requires tightly-packed 4-byte pixels (pixelStride == RGBA_BYTES_PER_PIXEL).
    int invalidPixelStride = RGBA_BYTES_PER_PIXEL * 2;
    int rowStride = DEFAULT_WIDTH * invalidPixelStride;
    Plane plane =
        new DefaultPlane(
            ByteBuffer.allocateDirect(rowStride * DEFAULT_HEIGHT), rowStride, invalidPixelStride);
    ImagePlanesFrame frame =
        new DefaultImagePlanesFrame.Builder(ImmutableList.of(plane)).setFormat(format).build();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            converter.convert(
                frame,
                /* glExecutor= */ DIRECT_EXECUTOR,
                /* listenerExecutor= */ DIRECT_EXECUTOR,
                LISTENER));
  }

  @Test
  public void convert_rowStrideSmallerThanExpectedRowBytes_throwsIllegalArgumentException() {
    Format format =
        new Format.Builder()
            .setWidth(DEFAULT_WIDTH)
            .setHeight(DEFAULT_HEIGHT)
            .setPixelFormat(PixelFormat.RGBA_8888)
            .build();
    // Subtract a full pixel so rowStride remains a multiple of RGBA_BYTES_PER_PIXEL.
    Plane plane =
        new DefaultPlane(
            ByteBuffer.allocateDirect(DEFAULT_RGBA_ROW_STRIDE * DEFAULT_HEIGHT),
            /* rowStride= */ DEFAULT_RGBA_ROW_STRIDE - RGBA_BYTES_PER_PIXEL,
            /* pixelStride= */ RGBA_BYTES_PER_PIXEL);
    ImagePlanesFrame frame =
        new DefaultImagePlanesFrame.Builder(ImmutableList.of(plane)).setFormat(format).build();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            converter.convert(
                frame,
                /* glExecutor= */ DIRECT_EXECUTOR,
                /* listenerExecutor= */ DIRECT_EXECUTOR,
                LISTENER));
  }

  @Test
  public void convert_rowStrideNotMultipleOfBytesPerPixel_throwsIllegalArgumentException() {
    Format format =
        new Format.Builder()
            .setWidth(DEFAULT_WIDTH)
            .setHeight(DEFAULT_HEIGHT)
            .setPixelFormat(PixelFormat.RGBA_8888)
            .build();
    int unalignedRowStride = DEFAULT_RGBA_ROW_STRIDE + 1;
    Plane plane =
        new DefaultPlane(
            ByteBuffer.allocateDirect(unalignedRowStride * DEFAULT_HEIGHT),
            unalignedRowStride,
            /* pixelStride= */ RGBA_BYTES_PER_PIXEL);
    ImagePlanesFrame frame =
        new DefaultImagePlanesFrame.Builder(ImmutableList.of(plane)).setFormat(format).build();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            converter.convert(
                frame,
                /* glExecutor= */ DIRECT_EXECUTOR,
                /* listenerExecutor= */ DIRECT_EXECUTOR,
                LISTENER));
  }

  @Test
  public void convert_undersizedPlaneBuffer_throwsIllegalArgumentException() {
    Format format =
        new Format.Builder()
            .setWidth(DEFAULT_WIDTH)
            .setHeight(DEFAULT_HEIGHT)
            .setPixelFormat(PixelFormat.RGBA_8888)
            .build();
    Plane plane =
        new DefaultPlane(
            ByteBuffer.allocateDirect(DEFAULT_RGBA_ROW_STRIDE * DEFAULT_HEIGHT - 1),
            DEFAULT_RGBA_ROW_STRIDE,
            /* pixelStride= */ RGBA_BYTES_PER_PIXEL);
    ImagePlanesFrame frame =
        new DefaultImagePlanesFrame.Builder(ImmutableList.of(plane)).setFormat(format).build();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            converter.convert(
                frame,
                /* glExecutor= */ DIRECT_EXECUTOR,
                /* listenerExecutor= */ DIRECT_EXECUTOR,
                LISTENER));
  }

  @Test
  public void convert_planeBufferEndingBeforeLastVisiblePixel_throwsIllegalArgumentException() {
    Format format =
        new Format.Builder()
            .setWidth(DEFAULT_WIDTH)
            .setHeight(DEFAULT_HEIGHT)
            .setPixelFormat(PixelFormat.RGBA_8888)
            .build();
    int rowBytes = DEFAULT_WIDTH * RGBA_BYTES_PER_PIXEL;
    int paddedRowStride = rowBytes + RGBA_BYTES_PER_PIXEL;
    // The final row only needs to extend to the last visible pixel, so this is one byte short.
    Plane plane =
        new DefaultPlane(
            ByteBuffer.allocateDirect(paddedRowStride * (DEFAULT_HEIGHT - 1) + rowBytes - 1),
            paddedRowStride,
            /* pixelStride= */ RGBA_BYTES_PER_PIXEL);
    ImagePlanesFrame frame =
        new DefaultImagePlanesFrame.Builder(ImmutableList.of(plane)).setFormat(format).build();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            converter.convert(
                frame,
                /* glExecutor= */ DIRECT_EXECUTOR,
                /* listenerExecutor= */ DIRECT_EXECUTOR,
                LISTENER));
  }
}
