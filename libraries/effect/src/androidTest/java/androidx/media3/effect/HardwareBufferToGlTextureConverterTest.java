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

import static androidx.media3.common.util.Util.isRunningOnEmulator;
import static androidx.media3.effect.FrameProcessorUtils.releaseOpenGl;
import static androidx.media3.effect.FrameProcessorUtils.setupOpenGl;
import static androidx.media3.effect.FrameProcessorUtils.shutdownGlExecutorService;
import static androidx.media3.test.utils.BitmapPixelTestUtil.createArgb8888BitmapFromFocusedGlFramebuffer;
import static androidx.media3.test.utils.BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceArgb8888;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaFormat;
import androidx.annotation.Nullable;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.GlUtil.GlException;
import androidx.media3.common.util.MediaFormatUtil;
import androidx.media3.common.video.DefaultHardwareBufferFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.FrameProcessor;
import androidx.media3.common.video.HardwareBufferFrame;
import androidx.media3.common.video.SyncFenceWrapper;
import androidx.media3.effect.ndk.HardwareBufferJni;
import androidx.media3.test.utils.AssetInfo;
import androidx.media3.test.utils.BitmapPixelTestUtil;
import androidx.media3.test.utils.DecodeOneFrameUtil;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/** Instrumentation tests for {@link HardwareBufferToGlTextureConverter}. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 29)
public final class HardwareBufferToGlTextureConverterTest {

  @Rule public final TestName testName = new TestName();
  private static final AssetInfo TEST_VIDEO_ASSET = AssetInfo.MP4_ASSET_WITH_INCREASING_TIMESTAMPS;
  private static final float MAX_PIXEL_DIFFERENCE = 10.f;
  private static final long TEST_TIMEOUT_MS = isRunningOnEmulator() ? 20_000L : 10_000L;
  private static final long FENCE_TIMEOUT_MS = 1_000L;

  private final Context context = getApplicationContext();

  private @MonotonicNonNull ListeningExecutorService glExecutorService;
  private @MonotonicNonNull GlObjectsProvider glObjectsProvider;
  private @MonotonicNonNull HardwareBufferToGlTextureConverter converter;

  @Before
  public void setUp() {
    glExecutorService = listeningDecorator(Executors.newSingleThreadExecutor());
    glObjectsProvider = new DefaultGlObjectsProvider();
    converter =
        new HardwareBufferToGlTextureConverter(context, HardwareBufferJni.INSTANCE, e -> {});
  }

  @After
  public void tearDown() throws Exception {
    if (glExecutorService != null) {
      glExecutorService.submit(this::tearDownInternal).get();
      shutdownGlExecutorService(glExecutorService);
    }
  }

  private void tearDownInternal() {
    try {
      if (converter != null) {
        converter.close();
      }
      if (glObjectsProvider != null) {
        releaseOpenGl(glObjectsProvider);
      }
    } catch (Exception e) {
      // Ignore release failures.
    }
  }

  @SdkSuppress(minSdkVersion = 31)
  @Test
  public void convert_withARGB8888HardwareBuffer_outputsCorrectGlTexture() throws Exception {
    Bitmap expectedBitmap = BitmapPixelTestUtil.readBitmap("media/png/first_frame_1920x1080.png");
    Bitmap hardwareBitmap = expectedBitmap.copy(Bitmap.Config.HARDWARE, /* isMutable= */ false);
    HardwareBuffer hardwareBuffer = hardwareBitmap.getHardwareBuffer();

    AtomicReference<Frame> completedFrame = new AtomicReference<>();
    FrameProcessor.Listener listener =
        new FrameProcessor.Listener() {
          @Override
          public void onWakeup() {}

          @Override
          public void onError(VideoFrameProcessingException exception) {}

          @Override
          public void onFrameProcessed(Frame frame, @Nullable SyncFenceWrapper releaseFence) {
            if (releaseFence != null) {
              if (!releaseFence.awaitMs(FENCE_TIMEOUT_MS)) {
                throw new IllegalStateException("Release fence timed out.");
              }
              releaseFence.close();
            }
            hardwareBuffer.close();
            completedFrame.set(frame);
          }
        };

    int width = expectedBitmap.getWidth();
    int height = expectedBitmap.getHeight();
    HardwareBufferFrame hardwareBufferFrame =
        new DefaultHardwareBufferFrame.Builder(hardwareBuffer)
            .setFormat(
                new Format.Builder()
                    .setWidth(width)
                    .setHeight(height)
                    .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
                    .build())
            .build();

    Bitmap actualBitmap = convertAndCaptureBitmap(hardwareBufferFrame, listener);

    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                expectedBitmap, flip(actualBitmap), testName.getMethodName()))
        .isLessThan(MAX_PIXEL_DIFFERENCE);
    assertThat(completedFrame.get()).isSameInstanceAs(hardwareBufferFrame);
  }

  @SdkSuppress(minSdkVersion = 29)
  @Test
  public void convert_withYuv420HardwareBuffer_outputsCorrectGlTexture() throws Exception {
    int width = TEST_VIDEO_ASSET.videoFormat.width;
    int height = TEST_VIDEO_ASSET.videoFormat.height;

    ImageReader inputImageReader =
        ImageReader.newInstance(
            width,
            height,
            ImageFormat.YUV_420_888,
            /* maxImages= */ 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);

    AtomicReference<MediaFormat> inputMediaFormat = new AtomicReference<>();
    DecodeOneFrameUtil.decodeOneMediaItemFrame(
        MediaItem.fromUri(AssetInfo.MP4_ASSET_WITH_INCREASING_TIMESTAMPS.uri),
        new DecodeOneFrameUtil.Listener() {
          @Override
          public void onContainerExtracted(MediaFormat mediaFormat) {}

          @Override
          public void onFrameDecoded(MediaFormat mediaFormat) {
            inputMediaFormat.set(mediaFormat);
          }
        },
        inputImageReader.getSurface());

    Image inputImage = checkNotNull(inputImageReader.acquireLatestImage());
    HardwareBuffer inputHardwareBuffer = checkNotNull(inputImage.getHardwareBuffer());

    // Override the input format to force it to be 1920x1080 rather than 1920x1088.
    Format inputFormat =
        MediaFormatUtil.createFormatFromMediaFormat(inputMediaFormat.get())
            .buildUpon()
            .setWidth(width)
            .setHeight(height)
            .build();

    AtomicReference<Frame> completedFrame = new AtomicReference<>();
    FrameProcessor.Listener listener =
        new FrameProcessor.Listener() {
          @Override
          public void onWakeup() {}

          @Override
          public void onError(VideoFrameProcessingException exception) {}

          @Override
          public void onFrameProcessed(Frame frame, @Nullable SyncFenceWrapper releaseFence) {
            if (releaseFence != null) {
              if (!releaseFence.awaitMs(FENCE_TIMEOUT_MS)) {
                throw new IllegalStateException("Release fence timed out.");
              }
              releaseFence.close();
            }
            inputHardwareBuffer.close();
            inputImage.close();
            completedFrame.set(frame);
          }
        };

    HardwareBufferFrame inputHardwareBufferFrame =
        new DefaultHardwareBufferFrame.Builder(inputHardwareBuffer).setFormat(inputFormat).build();
    Bitmap expectedBitmap = BitmapPixelTestUtil.readBitmap("media/png/first_frame_1920x1080.png");

    Bitmap actualBitmap = convertAndCaptureBitmap(inputHardwareBufferFrame, listener);

    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                expectedBitmap, flip(actualBitmap), testName.getMethodName()))
        .isLessThan(MAX_PIXEL_DIFFERENCE);
    assertThat(completedFrame.get()).isSameInstanceAs(inputHardwareBufferFrame);
    inputImageReader.close();
  }

  private Bitmap convertAndCaptureBitmap(
      HardwareBufferFrame hardwareBufferFrame, FrameProcessor.Listener listener) throws Exception {
    AtomicReference<Bitmap> actualBitmap = new AtomicReference<>();
    CountDownLatch bitmapCaptured = new CountDownLatch(1);
    glExecutorService
        .submit(
            () -> {
              try {
                setupOpenGl(checkNotNull(glObjectsProvider));
                GlTextureFrame glTextureFrame =
                    converter.convert(
                        /* hardwareBufferFrame= */ hardwareBufferFrame,
                        glExecutorService,
                        /* listenerExecutor= */ directExecutor(),
                        listener);

                int textureWidth = glTextureFrame.glTextureInfo.width;
                int textureHeight = glTextureFrame.glTextureInfo.height;
                int fboId = GlUtil.createFboForTexture(glTextureFrame.glTextureInfo.texId);
                GlUtil.focusFramebufferUsingCurrentContext(fboId, textureWidth, textureHeight);
                actualBitmap.set(
                    createArgb8888BitmapFromFocusedGlFramebuffer(textureWidth, textureHeight));
                glTextureFrame.release(/* releaseFence= */ null);
                GlUtil.deleteFbo(fboId);
                // createArgb8888BitmapFromFocusedGlFramebuffer calls glReadPixels, which syncs
                // OpenGL
                bitmapCaptured.countDown();
              } catch (GlException | VideoFrameProcessingException e) {
                throw new IllegalStateException(e);
              }
            })
        .get();

    assertThat(bitmapCaptured.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();
    return actualBitmap.get();
  }

  @SdkSuppress(minSdkVersion = 31)
  @Test
  public void convert_releaseAfterReleasingGlResources_doesNotThrow() throws Exception {
    Bitmap expectedBitmap = BitmapPixelTestUtil.readBitmap("media/png/first_frame_1920x1080.png");
    Bitmap hardwareBitmap = expectedBitmap.copy(Bitmap.Config.HARDWARE, /* isMutable= */ false);
    HardwareBuffer hardwareBuffer = hardwareBitmap.getHardwareBuffer();

    HardwareBufferFrame hardwareBufferFrame =
        new DefaultHardwareBufferFrame.Builder(hardwareBuffer)
            .setFormat(new Format.Builder().setWidth(100).setHeight(100).build())
            .build();

    glExecutorService
        .submit(
            () -> {
              try {
                setupOpenGl(checkNotNull(glObjectsProvider));
                GlTextureFrame glTextureFrame =
                    converter.convert(
                        hardwareBufferFrame,
                        glExecutorService,
                        directExecutor(),
                        new FrameProcessor.Listener() {
                          @Override
                          public void onWakeup() {}

                          @Override
                          public void onError(VideoFrameProcessingException exception) {}

                          @Override
                          public void onFrameProcessed(
                              Frame frame, @Nullable SyncFenceWrapper releaseFence) {}
                        });

                converter.releaseGlResources(hardwareBufferFrame);
                // This should be no-op as GL resources is already released.
                glTextureFrame.release(/* releaseFence= */ null);
              } catch (GlException | VideoFrameProcessingException e) {
                throw new IllegalStateException(e);
              }
            })
        .get();

    hardwareBuffer.close();
  }

  private static Bitmap flip(Bitmap bitmap) {
    // Flip the actual bitmap vertically to match the original coordinate system
    Matrix matrix = new Matrix();
    matrix.postScale(1, -1, bitmap.getWidth() / 2f, bitmap.getHeight() / 2f);
    return Bitmap.createBitmap(
        bitmap,
        /* x= */ 0,
        /* y= */ 0,
        bitmap.getWidth(),
        bitmap.getHeight(),
        matrix,
        /* filter= */ true);
  }
}
