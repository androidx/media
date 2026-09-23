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
package androidx.media3.effect;

import static androidx.media3.effect.DefaultGlFrameProcessor.BT2020_HLG;
import static androidx.media3.effect.DefaultGlFrameProcessor.BT709_SRGB;
import static androidx.media3.effect.DefaultGlFrameProcessor.KEY_COMPOSITION_SEQUENCE_INDEX;
import static androidx.media3.effect.DefaultGlFrameProcessor.KEY_COMPOSITOR_SETTINGS;
import static androidx.media3.effect.EffectsTestUtil.createFocusedEglContextWithFallback;
import static androidx.media3.test.utils.BitmapPixelTestUtil.createFp16BitmapFromFocusedGlFramebuffer;
import static androidx.media3.test.utils.BitmapPixelTestUtil.createGlTextureFromBitmap;
import static androidx.media3.test.utils.BitmapPixelTestUtil.createUnpremultipliedArgb8888BitmapFromFocusedGlFramebuffer;
import static androidx.media3.test.utils.BitmapPixelTestUtil.maybeSaveTestBitmap;
import static androidx.media3.test.utils.BitmapPixelTestUtil.readBitmapUnpremultipliedAlpha;
import static androidx.media3.test.utils.TestUtil.PSNR_THRESHOLD;
import static androidx.media3.test.utils.TestUtil.assertBitmapsAreSimilar;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.util.Pair;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.OverlaySettings;
import androidx.media3.common.VideoCompositorSettings;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/** Pixel tests for {@link DefaultGlTextureFrameCompositor}. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 26)
public final class DefaultGlTextureFrameCompositorPixelTest {

  private static final String ORIGINAL_HLG10_PNG_ASSET_PATH =
      "test-generated-goldens/sample_mp4_first_frame/electrical_colors/original_hlg10.png";
  private static final String ORIGINAL_SDR_PNG_ASSET_PATH =
      "test-generated-goldens/sample_mp4_first_frame/electrical_colors/original.png";

  @Rule public final TestName testName = new TestName();

  private final Context context = getApplicationContext();
  private final List<Integer> texturesToDelete = new ArrayList<>();
  private final List<Integer> frameBuffersToDelete = new ArrayList<>();

  private String testId;
  private @MonotonicNonNull EGLDisplay eglDisplay;
  private @MonotonicNonNull EGLContext eglContext;
  private @MonotonicNonNull EGLSurface placeholderEglSurface;
  private @MonotonicNonNull GlObjectsProvider glObjectsProvider;

  @Before
  public void setUp() throws Exception {
    testId = testName.getMethodName();
    eglDisplay = GlUtil.getDefaultEglDisplay();
    glObjectsProvider = new DefaultGlObjectsProvider();
    Pair<EGLContext, EGLSurface> eglContextAndSurface =
        createFocusedEglContextWithFallback(
            checkNotNull(eglDisplay), GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888);
    eglContext = eglContextAndSurface.first;
    placeholderEglSurface = eglContextAndSurface.second;
  }

  @After
  public void tearDown() throws Exception {
    for (int texId : texturesToDelete) {
      GlUtil.deleteTexture(texId);
    }
    for (int frameBuffer : frameBuffersToDelete) {
      GlUtil.deleteFbo(frameBuffer);
    }
    if (eglDisplay != null && placeholderEglSurface != null) {
      GlUtil.destroyEglSurface(eglDisplay, placeholderEglSurface);
    }
    if (eglDisplay != null && eglContext != null) {
      GlUtil.destroyEglContext(eglDisplay, eglContext);
    }
  }

  @Test
  @SdkSuppress(minSdkVersion = 33)
  public void composite_singleHdrSequence_passesThroughHdrFrame() throws Exception {
    Format hlgFormat = new Format.Builder().setColorInfo(BT2020_HLG).build();
    assumeTrue("Device does not support OpenGL ES 3.0", GlUtil.getContextMajorVersion() >= 3);

    Bitmap hlgBitmap = readBitmapUnpremultipliedAlpha(ORIGINAL_HLG10_PNG_ASSET_PATH);
    int width = hlgBitmap.getWidth();
    int height = hlgBitmap.getHeight();
    int texId = createGlTextureFromBitmap(hlgBitmap);
    texturesToDelete.add(texId);
    GlTextureInfo textureInfo =
        new GlTextureInfo(
            texId, /* fboId= */ C.INDEX_UNSET, /* rboId= */ C.INDEX_UNSET, width, height);

    GlTextureFrame inputFrame =
        new GlTextureFrame.Builder(
                textureInfo, directExecutor(), /* releaseTextureCallback= */ unused -> {})
            .setPresentationTimeUs(0)
            .setFormat(hlgFormat)
            .setMetadata(
                ImmutableMap.of(
                    KEY_COMPOSITION_SEQUENCE_INDEX,
                    0,
                    KEY_COMPOSITOR_SETTINGS,
                    VideoCompositorSettings.DEFAULT))
            .build();

    AtomicReference<GlTextureFrame> outputFrameRef = new AtomicReference<>();
    GlTextureFrameConsumer downstreamConsumer =
        new GlTextureFrameConsumer() {
          @Override
          public boolean queue(
              GlTextureFrame frame, Executor listenerExecutor, Runnable wakeupListener) {
            outputFrameRef.set(frame);
            return true;
          }

          @Override
          public void signalEndOfStream() {}

          @Override
          public void close() {}
        };

    DefaultGlTextureFrameCompositor.Factory factory =
        new DefaultGlTextureFrameCompositor.Factory(
            new DefaultCompositorGlProgram.Factory(context));
    GlTextureFrameCompositor compositor =
        factory.create(
            glObjectsProvider,
            BT2020_HLG,
            /* errorConsumer= */ exception -> {
              throw new AssertionError(exception);
            },
            directExecutor(),
            downstreamConsumer);

    assertThat(
            compositor.queue(
                ImmutableList.of(inputFrame), directExecutor(), /* wakeupListener= */ () -> {}))
        .isTrue();

    GlTextureFrame outputFrame = checkNotNull(outputFrameRef.get());
    assertThat(outputFrame.format.colorInfo).isEqualTo(BT2020_HLG);
    assertThat(outputFrame.glTextureInfo.texId).isEqualTo(texId);

    outputFrame.release(/* releaseFence= */ null);
    compositor.close();
  }

  @Test
  @SdkSuppress(minSdkVersion = 33)
  public void composite_twoHdrSequences_withOpaqueOverlay_outputsCorrectTexture() throws Exception {
    Format hlgFormat = new Format.Builder().setColorInfo(BT2020_HLG).build();
    assumeTrue("Device does not support OpenGL ES 3.0", GlUtil.getContextMajorVersion() >= 3);

    Bitmap hlgBitmap = readBitmapUnpremultipliedAlpha(ORIGINAL_HLG10_PNG_ASSET_PATH);
    int width = hlgBitmap.getWidth();
    int height = hlgBitmap.getHeight();
    int texId0 = createGlTextureFromBitmap(hlgBitmap);
    int texId1 = createGlTextureFromBitmap(hlgBitmap);
    texturesToDelete.add(texId0);
    texturesToDelete.add(texId1);

    VideoCompositorSettings compositorSettings =
        new VideoCompositorSettings() {
          @Override
          public Size getOutputSize(List<Size> inputSizes) {
            return inputSizes.get(0);
          }

          @Override
          public OverlaySettings getOverlaySettings(int inputId, long presentationTimeUs) {
            return new StaticOverlaySettings.Builder().build();
          }
        };

    GlTextureFrame frame0 =
        new GlTextureFrame.Builder(
                new GlTextureInfo(
                    texId0, /* fboId= */ C.INDEX_UNSET, /* rboId= */ C.INDEX_UNSET, width, height),
                directExecutor(),
                /* releaseTextureCallback= */ unused -> {})
            .setPresentationTimeUs(0)
            .setFormat(hlgFormat)
            .setMetadata(
                ImmutableMap.of(
                    KEY_COMPOSITION_SEQUENCE_INDEX, 0, KEY_COMPOSITOR_SETTINGS, compositorSettings))
            .build();

    GlTextureFrame frame1 =
        new GlTextureFrame.Builder(
                new GlTextureInfo(
                    texId1, /* fboId= */ C.INDEX_UNSET, /* rboId= */ C.INDEX_UNSET, width, height),
                directExecutor(),
                /* releaseTextureCallback= */ unused -> {})
            .setPresentationTimeUs(0)
            .setFormat(hlgFormat)
            .setMetadata(
                ImmutableMap.of(
                    KEY_COMPOSITION_SEQUENCE_INDEX, 1, KEY_COMPOSITOR_SETTINGS, compositorSettings))
            .build();

    AtomicReference<GlTextureFrame> outputFrameRef = new AtomicReference<>();
    GlTextureFrameConsumer downstreamConsumer =
        new GlTextureFrameConsumer() {
          @Override
          public boolean queue(
              GlTextureFrame frame, Executor listenerExecutor, Runnable wakeupListener) {
            outputFrameRef.set(frame);
            return true;
          }

          @Override
          public void signalEndOfStream() {}

          @Override
          public void close() {}
        };

    DefaultGlTextureFrameCompositor.Factory factory =
        new DefaultGlTextureFrameCompositor.Factory(
            new DefaultCompositorGlProgram.Factory(context));
    GlTextureFrameCompositor compositor =
        factory.create(
            glObjectsProvider,
            BT2020_HLG,
            /* errorConsumer= */ exception -> {
              throw new AssertionError(exception);
            },
            directExecutor(),
            downstreamConsumer);

    assertThat(
            compositor.queue(
                ImmutableList.of(frame0, frame1), directExecutor(), /* wakeupListener= */ () -> {}))
        .isTrue();

    GlTextureFrame outputFrame = checkNotNull(outputFrameRef.get());
    assertThat(outputFrame.format.colorInfo).isEqualTo(BT2020_HLG);

    int outputFbo = GlUtil.createFboForTexture(outputFrame.glTextureInfo.texId);
    frameBuffersToDelete.add(outputFbo);
    GlUtil.focusFramebuffer(
        checkNotNull(eglDisplay),
        checkNotNull(eglContext),
        checkNotNull(placeholderEglSurface),
        outputFbo,
        width,
        height);

    Bitmap actualBitmap = createFp16BitmapFromFocusedGlFramebuffer(width, height);
    actualBitmap.setColorSpace(checkNotNull(hlgBitmap.getColorSpace()));

    maybeSaveTestBitmap(testId, /* bitmapLabel= */ "actual", actualBitmap, /* path= */ null);
    assertBitmapsAreSimilar(hlgBitmap, actualBitmap, PSNR_THRESHOLD);

    outputFrame.release(/* releaseFence= */ null);
    compositor.close();
  }

  @Test
  @SdkSuppress(minSdkVersion = 26)
  public void composite_twoSdrSequences_withOpaqueOverlay_outputsCorrectTexture() throws Exception {
    Format sdrFormat = new Format.Builder().setColorInfo(BT709_SRGB).build();

    Bitmap sdrBitmap = readBitmapUnpremultipliedAlpha(ORIGINAL_SDR_PNG_ASSET_PATH);
    int width = sdrBitmap.getWidth();
    int height = sdrBitmap.getHeight();
    int texId0 = createGlTextureFromBitmap(sdrBitmap);
    int texId1 = createGlTextureFromBitmap(sdrBitmap);
    texturesToDelete.add(texId0);
    texturesToDelete.add(texId1);

    VideoCompositorSettings compositorSettings =
        new VideoCompositorSettings() {
          @Override
          public Size getOutputSize(List<Size> inputSizes) {
            return inputSizes.get(0);
          }

          @Override
          public OverlaySettings getOverlaySettings(int inputId, long presentationTimeUs) {
            return new StaticOverlaySettings.Builder().build();
          }
        };

    GlTextureFrame frame0 =
        new GlTextureFrame.Builder(
                new GlTextureInfo(
                    texId0, /* fboId= */ C.INDEX_UNSET, /* rboId= */ C.INDEX_UNSET, width, height),
                directExecutor(),
                /* releaseTextureCallback= */ unused -> {})
            .setPresentationTimeUs(0)
            .setFormat(sdrFormat)
            .setMetadata(
                ImmutableMap.of(
                    KEY_COMPOSITION_SEQUENCE_INDEX, 0, KEY_COMPOSITOR_SETTINGS, compositorSettings))
            .build();

    GlTextureFrame frame1 =
        new GlTextureFrame.Builder(
                new GlTextureInfo(
                    texId1, /* fboId= */ C.INDEX_UNSET, /* rboId= */ C.INDEX_UNSET, width, height),
                directExecutor(),
                /* releaseTextureCallback= */ unused -> {})
            .setPresentationTimeUs(0)
            .setFormat(sdrFormat)
            .setMetadata(
                ImmutableMap.of(
                    KEY_COMPOSITION_SEQUENCE_INDEX, 1, KEY_COMPOSITOR_SETTINGS, compositorSettings))
            .build();

    AtomicReference<GlTextureFrame> outputFrameRef = new AtomicReference<>();
    GlTextureFrameConsumer downstreamConsumer =
        new GlTextureFrameConsumer() {
          @Override
          public boolean queue(
              GlTextureFrame frame, Executor listenerExecutor, Runnable wakeupListener) {
            outputFrameRef.set(frame);
            return true;
          }

          @Override
          public void signalEndOfStream() {}

          @Override
          public void close() {}
        };

    DefaultGlTextureFrameCompositor.Factory factory =
        new DefaultGlTextureFrameCompositor.Factory(
            new DefaultCompositorGlProgram.Factory(context));
    GlTextureFrameCompositor compositor =
        factory.create(
            glObjectsProvider,
            BT709_SRGB,
            /* errorConsumer= */ exception -> {
              throw new AssertionError(exception);
            },
            directExecutor(),
            downstreamConsumer);

    assertThat(
            compositor.queue(
                ImmutableList.of(frame0, frame1), directExecutor(), /* wakeupListener= */ () -> {}))
        .isTrue();

    GlTextureFrame outputFrame = checkNotNull(outputFrameRef.get());
    assertThat(outputFrame.format.colorInfo).isEqualTo(BT709_SRGB);

    int outputFbo = GlUtil.createFboForTexture(outputFrame.glTextureInfo.texId);
    frameBuffersToDelete.add(outputFbo);
    GlUtil.focusFramebuffer(
        checkNotNull(eglDisplay),
        checkNotNull(eglContext),
        checkNotNull(placeholderEglSurface),
        outputFbo,
        width,
        height);

    Bitmap actualBitmap =
        createUnpremultipliedArgb8888BitmapFromFocusedGlFramebuffer(width, height);

    maybeSaveTestBitmap(testId, /* bitmapLabel= */ "actual", actualBitmap, /* path= */ null);
    assertBitmapsAreSimilar(sdrBitmap, actualBitmap, PSNR_THRESHOLD);

    outputFrame.release(/* releaseFence= */ null);
    compositor.close();
  }
}
