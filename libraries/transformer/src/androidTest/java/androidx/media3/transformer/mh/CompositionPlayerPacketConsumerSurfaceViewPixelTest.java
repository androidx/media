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
package androidx.media3.transformer.mh;

import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET_COLOR_TEST_1080P_HLG10;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S;
import static androidx.media3.test.utils.BitmapPixelTestUtil.maybeSaveTestBitmap;
import static androidx.media3.test.utils.BitmapPixelTestUtil.readBitmap;
import static androidx.media3.test.utils.FormatSupportAssumptions.assumeFormatsSupported;
import static androidx.media3.test.utils.TestUtil.assertBitmapsAreSimilar;
import static androidx.test.internal.util.Checks.checkState;
import static com.google.common.truth.Truth.assertThat;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.DataSpace;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.effect.DefaultHardwareBufferEffectsPipeline;
import androidx.media3.effect.RenderingPacketConsumer;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.CompositionPlayer;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.PlayerTestListener;
import androidx.media3.transformer.SurfaceTestActivity;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/**
 * Pixel tests for {@link
 * CompositionPlayer.Builder#setHardwareBufferEffectsPipeline(RenderingPacketConsumer)} when
 * outputting to a {@link android.view.SurfaceView}.
 */
@RunWith(AndroidJUnit4.class)
public class CompositionPlayerPacketConsumerSurfaceViewPixelTest {

  private static final long TEST_TIMEOUT_MS = 10_000;
  private static final float PSNR_THRESHOLD = 23f;
  private static final String GOLDEN_ASSET_FOLDER_PATH =
      "test-generated-goldens/CompositionPlayerPacketConsumerSurfaceViewPixelTest/";

  @Rule
  public ActivityScenarioRule<SurfaceTestActivity> rule =
      new ActivityScenarioRule<>(SurfaceTestActivity.class);

  @Rule public final TestName testName = new TestName();

  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private final Context context = ApplicationProvider.getApplicationContext();

  private @MonotonicNonNull CompositionPlayer compositionPlayer;
  private @MonotonicNonNull SurfaceView surfaceView;
  private @MonotonicNonNull ImageReaderSurfaceHolder surfaceHolder;

  private String testId;

  @Before
  public void setUp() {
    testId = testName.getMethodName();
    rule.getScenario().onActivity(activity -> surfaceView = activity.getSurfaceView());
  }

  @After
  public void tearDown() {
    instrumentation.runOnMainSync(
        () -> {
          if (compositionPlayer != null) {
            compositionPlayer.release();
          }
        });
    rule.getScenario().close();
    if (surfaceHolder != null) {
      surfaceHolder.release();
    }
  }

  @Test
  @SdkSuppress(minSdkVersion = 34)
  public void compositionPlayer_withPacketConsumer_reportsVideoSizeChanged()
      throws InterruptedException {
    ConditionVariable videoSizeReported = new ConditionVariable();
    AtomicReference<VideoSize> videoSizeAtomicReference = new AtomicReference<>();

    instrumentation.runOnMainSync(
        () -> {
          DefaultHardwareBufferEffectsPipeline packetProcessor =
              new DefaultHardwareBufferEffectsPipeline();
          compositionPlayer =
              new CompositionPlayer.Builder(context)
                  .setHardwareBufferEffectsPipeline(packetProcessor)
                  .build();
          compositionPlayer.setVideoSurfaceView(surfaceView);
          compositionPlayer.addListener(
              new Player.Listener() {
                @Override
                public void onVideoSizeChanged(VideoSize videoSize) {
                  videoSizeAtomicReference.set(videoSize);
                  videoSizeReported.open();
                }
              });
          compositionPlayer.setComposition(
              new Composition.Builder(
                      EditedMediaItemSequence.withVideoFrom(
                          ImmutableList.of(
                              new EditedMediaItem.Builder(
                                      MediaItem.fromUri(
                                          MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S.uri))
                                  .setDurationUs(
                                      MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S
                                          .videoDurationUs)
                                  .build())))
                  .build());
          compositionPlayer.prepare();
          compositionPlayer.play();
        });
    videoSizeReported.block(TEST_TIMEOUT_MS);

    VideoSize videoSize = videoSizeAtomicReference.get();
    assertThat(videoSize.width)
        .isEqualTo(MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S.videoFormat.width);
    assertThat(videoSize.height)
        .isEqualTo(MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S.videoFormat.height);
  }

  @Test
  @SdkSuppress(minSdkVersion = 34)
  public void compositionPlayer_withPacketConsumer_rendersFirstFrameAndReturnsBitmap()
      throws Exception {
    PlayerTestListener listener = new PlayerTestListener(TEST_TIMEOUT_MS);

    instrumentation.runOnMainSync(
        () -> {
          DefaultHardwareBufferEffectsPipeline packetProcessor =
              new DefaultHardwareBufferEffectsPipeline();
          compositionPlayer =
              new CompositionPlayer.Builder(context)
                  .setHardwareBufferEffectsPipeline(packetProcessor)
                  .build();
          compositionPlayer.setVideoSurfaceView(surfaceView);
          compositionPlayer.addListener(listener);
          compositionPlayer.setComposition(
              new Composition.Builder(
                      EditedMediaItemSequence.withVideoFrom(
                          ImmutableList.of(
                              new EditedMediaItem.Builder(
                                      MediaItem.fromUri(
                                          MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S.uri))
                                  .setDurationUs(
                                      MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S
                                          .videoDurationUs)
                                  .build())))
                  .build());
          compositionPlayer.prepare();
          // TODO: b/483974846 - Make sure the first frame is rendered with setPlayWhenReady(false).
          compositionPlayer.setPlayWhenReady(true);
        });

    listener.waitUntilFirstFrameRendered();

    Bitmap bitmap = Bitmap.createBitmap(/* width= */ 240, /* height= */ 270, Config.ARGB_8888);
    ConditionVariable pixelCopyFinished = new ConditionVariable();

    instrumentation.runOnMainSync(
        () ->
            PixelCopy.request(
                surfaceView,
                bitmap,
                result -> {
                  if (result == PixelCopy.SUCCESS) {
                    pixelCopyFinished.open();
                  }
                },
                surfaceView.getHandler()));
    assertThat(pixelCopyFinished.block(TEST_TIMEOUT_MS)).isTrue();

    Bitmap expectedBitmap =
        readBitmap(
            /* assetString= */ GOLDEN_ASSET_FOLDER_PATH
                + "compositionPlayer_withPacketConsumer_rendersFirstFrameAndReturnsBitmap.png");

    maybeSaveTestBitmap(testId, "firstFrame", bitmap, /* path= */ null);
    assertBitmapsAreSimilar(expectedBitmap, bitmap, PSNR_THRESHOLD);
  }

  @Test
  @SdkSuppress(minSdkVersion = 34)
  public void compositionPlayer_withPacketConsumer_usesMetadataListener() throws Exception {
    PlayerTestListener listener = new PlayerTestListener(TEST_TIMEOUT_MS);
    Queue<Long> videoTimestamps = new ConcurrentLinkedQueue<>();
    AtomicReference<Format> formatAtomicReference = new AtomicReference<>();

    instrumentation.runOnMainSync(
        () -> {
          DefaultHardwareBufferEffectsPipeline packetProcessor =
              new DefaultHardwareBufferEffectsPipeline();
          compositionPlayer =
              new CompositionPlayer.Builder(context)
                  .setHardwareBufferEffectsPipeline(packetProcessor)
                  .experimentalSetLateThresholdToDropInputUs(C.TIME_UNSET)
                  .build();
          compositionPlayer.setVideoSurfaceView(surfaceView);
          compositionPlayer.setVideoFrameMetadataListener(
              (presentationTimeUs, releaseTimeNs, format, mediaFormat) -> {
                videoTimestamps.add(presentationTimeUs);
                if (formatAtomicReference.get() != null) {
                  assertThat(formatAtomicReference.get()).isEqualTo(format);
                } else {
                  formatAtomicReference.set(format);
                }
              });
          compositionPlayer.addListener(listener);
          compositionPlayer.setComposition(
              new Composition.Builder(
                      EditedMediaItemSequence.withVideoFrom(
                          ImmutableList.of(
                              new EditedMediaItem.Builder(
                                      new MediaItem.Builder()
                                          .setUri(
                                              MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S.uri)
                                          .setClippingConfiguration(
                                              new MediaItem.ClippingConfiguration.Builder()
                                                  .setEndPositionMs(500)
                                                  .build())
                                          .build())
                                  .setDurationUs(
                                      MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S
                                          .videoDurationUs)
                                  .build())))
                  .build());
          compositionPlayer.prepare();
          compositionPlayer.play();
        });
    listener.waitUntilPlayerEnded();

    assertThat(videoTimestamps)
        .containsExactly(
            0L, 16666L, 33333L, 50000L, 66666L, 83333L, 100000L, 116666L, 133333L, 150000L, 166666L,
            183333L, 200000L, 216666L, 233333L, 250000L, 266666L, 283333L, 300000L, 316666L,
            333333L, 350000L, 366666L, 383333L, 400000L, 416666L, 433333L, 450000L, 466666L,
            483333L)
        .inOrder();
    assertThat(formatAtomicReference.get().width).isEqualTo(320);
    assertThat(formatAtomicReference.get().height).isEqualTo(240);
    assertThat(formatAtomicReference.get().colorInfo)
        .isEqualTo(
            new ColorInfo.Builder()
                .setColorRange(C.COLOR_RANGE_FULL)
                .setColorSpace(C.COLOR_SPACE_BT601)
                .setColorTransfer(C.COLOR_TRANSFER_SDR)
                .setChromaBitdepth(8)
                .setLumaBitdepth(8)
                .build());
  }

  @Test
  @SdkSuppress(minSdkVersion = 34)
  public void compositionPlayer_withPacketConsumer_andSdrVideo_outputsCorrectDataSpace()
      throws Exception {
    PlayerTestListener listener = new PlayerTestListener(TEST_TIMEOUT_MS);
    surfaceHolder = new ImageReaderSurfaceHolder();

    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer =
              new CompositionPlayer.Builder(context)
                  .setHardwareBufferEffectsPipeline(new DefaultHardwareBufferEffectsPipeline())
                  .build();
          compositionPlayer.setVideoSurfaceHolder(surfaceHolder);
          compositionPlayer.addListener(listener);
          compositionPlayer.setComposition(
              new Composition.Builder(
                      EditedMediaItemSequence.withVideoFrom(
                          ImmutableList.of(
                              new EditedMediaItem.Builder(
                                      MediaItem.fromUri(
                                          MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S.uri))
                                  .setDurationUs(
                                      MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S
                                          .videoDurationUs)
                                  .build())))
                  .build());
          compositionPlayer.prepare();
          compositionPlayer.setPlayWhenReady(true);
        });
    listener.waitUntilFirstFrameRendered();

    int actualDataSpace = surfaceHolder.getLatestDataSpace();
    assertThat(DataSpace.getStandard(actualDataSpace)).isEqualTo(DataSpace.STANDARD_BT601_625);
    assertThat(DataSpace.getTransfer(actualDataSpace)).isEqualTo(DataSpace.TRANSFER_SMPTE_170M);
    assertThat(DataSpace.getRange(actualDataSpace)).isEqualTo(DataSpace.RANGE_FULL);
  }

  @Test
  @SdkSuppress(minSdkVersion = 34)
  public void compositionPlayer_withPacketConsumer_andHdrVideo_outputsCorrectDataSpace()
      throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET_COLOR_TEST_1080P_HLG10.videoFormat,
        /* outputFormat= */ null);
    PlayerTestListener listener = new PlayerTestListener(TEST_TIMEOUT_MS);
    surfaceHolder = new ImageReaderSurfaceHolder();

    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer =
              new CompositionPlayer.Builder(context)
                  .setHardwareBufferEffectsPipeline(new DefaultHardwareBufferEffectsPipeline())
                  .build();
          compositionPlayer.setVideoSurfaceHolder(surfaceHolder);
          compositionPlayer.addListener(listener);
          compositionPlayer.setComposition(
              new Composition.Builder(
                      EditedMediaItemSequence.withVideoFrom(
                          ImmutableList.of(
                              new EditedMediaItem.Builder(
                                      MediaItem.fromUri(MP4_ASSET_COLOR_TEST_1080P_HLG10.uri))
                                  .setDurationUs(MP4_ASSET_COLOR_TEST_1080P_HLG10.videoDurationUs)
                                  .build())))
                  .build());
          compositionPlayer.prepare();
          compositionPlayer.setPlayWhenReady(true);
        });
    listener.waitUntilFirstFrameRendered();

    int actualDataSpace = surfaceHolder.getLatestDataSpace();
    assertThat(DataSpace.getStandard(actualDataSpace)).isEqualTo(DataSpace.STANDARD_BT2020);
    assertThat(DataSpace.getTransfer(actualDataSpace)).isEqualTo(DataSpace.TRANSFER_HLG);
    assertThat(DataSpace.getRange(actualDataSpace)).isEqualTo(DataSpace.RANGE_LIMITED);
  }

  /** An implementation of {@link SurfaceHolder} which is backed by an {@link ImageReader}. */
  private static final class ImageReaderSurfaceHolder implements SurfaceHolder {
    private final List<Callback> callbacks = new CopyOnWriteArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int width;
    private int height;
    private int format;
    private @MonotonicNonNull ImageReader imageReader;

    @Override
    public void addCallback(Callback callback) {
      callbacks.add(callback);
    }

    @Override
    public void removeCallback(Callback callback) {
      callbacks.remove(callback);
    }

    @Override
    public boolean isCreating() {
      return false;
    }

    /**
     * {@inheritDoc}
     *
     * @deprecated implements a {@link SurfaceHolder} method in a test.
     */
    @Override
    @Deprecated
    public void setType(int type) {}

    @Override
    public void setFixedSize(int width, int height) {
      this.width = width;
      this.height = height;
      handler.post(this::triggerCallbacks);
    }

    @Override
    public void setSizeFromLayout() {}

    @Override
    public void setFormat(int format) {
      this.format = format;
      handler.post(this::triggerCallbacks);
    }

    @Override
    public void setKeepScreenOn(boolean screenOn) {}

    @Override
    @Nullable
    public Canvas lockCanvas() {
      return null;
    }

    @Override
    @Nullable
    public Canvas lockCanvas(Rect dirty) {
      return null;
    }

    @Override
    public void unlockCanvasAndPost(Canvas canvas) {}

    @Override
    public Rect getSurfaceFrame() {
      return new Rect(0, 0, width, height);
    }

    @Override
    public Surface getSurface() {
      if (imageReader == null) {
        if (format == PixelFormat.RGBA_8888) {
          // Old API versions, use an ImageReader constructor which supports fewer pixel formats.
          imageReader =
              ImageReader.newInstance(
                  width == 0 ? 1 : width,
                  height == 0 ? 1 : height,
                  PixelFormat.RGBA_8888,
                  /* maxImages= */ 2);
        } else {
          checkState(SDK_INT >= 33);
          // HDR is only supported on newer API versions, where a different constructor, with wider
          // range of supported pixel formats exists.
          imageReader =
              new ImageReader.Builder(width, height)
                  .setDefaultHardwareBufferFormat(format)
                  .setMaxImages(2)
                  .build();
        }
      }
      return imageReader.getSurface();
    }

    int getLatestDataSpace() {
      Image image = imageReader.acquireLatestImage();
      int dataSpace = image.getDataSpace();
      image.close();
      return dataSpace;
    }

    void release() {
      if (imageReader != null) {
        imageReader.close();
      }
    }

    private void triggerCallbacks() {
      if (imageReader != null
          && (imageReader.getWidth() != width || imageReader.getHeight() != height)) {
        imageReader.close();
        imageReader = null;
      }
      for (Callback callback : callbacks) {
        callback.surfaceChanged(/* holder= */ this, format, width, height);
      }
    }
  }
}
