/*
 * Copyright 2025 The Android Open Source Project
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

import static androidx.media3.test.utils.BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceArgb8888;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assert.assertThrows;

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Color;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import androidx.annotation.ColorInt;
import androidx.media3.common.C.ColorTransfer;
import androidx.media3.common.Effect;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.common.util.GlUtil.GlException;
import androidx.media3.common.util.Util;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Instrumentation tests for {@link ExperimentalBitmapProcessor}. */
@RunWith(AndroidJUnit4.class)
public final class ExperimentalBitmapProcessorTest {
  private static final int TEST_TIMEOUT_MS = 10_000;
  private static final int WIDTH = 20;
  private static final int HEIGHT = 10;
  private @MonotonicNonNull TestGlObjectsProvider glObjectsProvider;
  private @MonotonicNonNull ExperimentalBitmapProcessor experimentalBitmapProcessor;

  @Before
  public void setUp()
      throws VideoFrameProcessingException,
          ExecutionException,
          InterruptedException,
          TimeoutException {
    glObjectsProvider = new TestGlObjectsProvider();
    experimentalBitmapProcessor =
        new ExperimentalBitmapProcessor.Builder(getApplicationContext())
            .setGlObjectsProvider(glObjectsProvider)
            .build();
  }

  @After
  public void tearDown() throws Exception {
    if (experimentalBitmapProcessor != null) {
      experimentalBitmapProcessor.releaseAsync().get(TEST_TIMEOUT_MS, MILLISECONDS);
    }
  }

  @Test
  public void applyEffectsAsync_beforeSetEffectsAsync_throwsIllegalStateException()
      throws Exception {
    Bitmap inputBitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);

    ExecutionException exception =
        assertThrows(
            ExecutionException.class,
            () ->
                experimentalBitmapProcessor
                    .applyEffectsAsync(inputBitmap)
                    .get(TEST_TIMEOUT_MS, MILLISECONDS));

    assertThat(exception).hasCauseThat().isInstanceOf(IllegalStateException.class);
  }

  @Test
  public void applyEffectsAsync_withNoEffects_completesWithNoEffectsApplied() throws Exception {
    Bitmap inputBitmap = createBitmap();
    ListenableFuture<Void> unused = experimentalBitmapProcessor.setEffectsAsync(ImmutableList.of());

    Bitmap resultBitmap =
        experimentalBitmapProcessor
            .applyEffectsAsync(inputBitmap)
            .get(TEST_TIMEOUT_MS, MILLISECONDS);

    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                createBitmap(),
                resultBitmap,
                /* testId= */ null,
                /* differencesBitmapPath= */ null))
        .isEqualTo(0);
  }

  @Test
  public void applyEffectsAsync_withMultipleEffects_completesWithEffectsApplied() throws Exception {
    RgbFilter grayScaleFilter = RgbFilter.createGrayscaleFilter();
    SpannableString overlayText = new SpannableString(/* source= */ "Overlay text");
    overlayText.setSpan(
        new ForegroundColorSpan(Color.GRAY),
        /* start= */ 0,
        /* end= */ 4,
        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    OverlayEffect textOverlay =
        new OverlayEffect(ImmutableList.of(TextOverlay.createStaticTextOverlay(overlayText)));
    Presentation presentation = Presentation.createForHeight(HEIGHT * 2);
    ImmutableList<Effect> effects = ImmutableList.of(grayScaleFilter, textOverlay, presentation);
    Bitmap inputBitmap = createBitmap();
    ListenableFuture<Void> unused = experimentalBitmapProcessor.setEffectsAsync(effects);

    Bitmap resultBitmap =
        experimentalBitmapProcessor
            .applyEffectsAsync(inputBitmap)
            .get(TEST_TIMEOUT_MS, MILLISECONDS);

    assertThat(resultBitmap.getHeight()).isEqualTo(HEIGHT * 2);
    assertThat(resultBitmap.getWidth()).isEqualTo(WIDTH * 2);
    assertThat(resultBitmap.getConfig()).isEqualTo(inputBitmap.getConfig());
  }

  @Test
  public void applyEffectsAsync_multipleInputBitmaps_appliesEffects() throws Exception {
    ImmutableList<Effect> effects = ImmutableList.of(Presentation.createForHeight(HEIGHT / 2));
    Bitmap inputBitmap1 = createBitmap();
    Bitmap inputBitmap2 = createBitmap(WIDTH, HEIGHT, Color.BLUE);
    Bitmap inputBitmap3 = createBitmap(WIDTH, HEIGHT, Color.GREEN);
    ListenableFuture<Void> unused = experimentalBitmapProcessor.setEffectsAsync(effects);

    ListenableFuture<Bitmap> resultFuture1 =
        experimentalBitmapProcessor.applyEffectsAsync(inputBitmap1);
    ListenableFuture<Bitmap> resultFuture2 =
        experimentalBitmapProcessor.applyEffectsAsync(inputBitmap2);
    ListenableFuture<Bitmap> resultFuture3 =
        experimentalBitmapProcessor.applyEffectsAsync(inputBitmap3);

    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                createBitmap(WIDTH / 2, HEIGHT / 2, Color.RED),
                resultFuture1.get(TEST_TIMEOUT_MS, MILLISECONDS),
                /* testId= */ null,
                /* differencesBitmapPath= */ null))
        .isEqualTo(0);
    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                createBitmap(WIDTH / 2, HEIGHT / 2, Color.BLUE),
                resultFuture2.get(TEST_TIMEOUT_MS, MILLISECONDS),
                /* testId= */ null,
                /* differencesBitmapPath= */ null))
        .isEqualTo(0);
    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                createBitmap(WIDTH / 2, HEIGHT / 2, Color.GREEN),
                resultFuture3.get(TEST_TIMEOUT_MS, MILLISECONDS),
                /* testId= */ null,
                /* differencesBitmapPath= */ null))
        .isEqualTo(0);
  }

  // TODO: b/429347981 - Do not recycle the input bitmap.
  @Test
  public void applyEffectsAsync_recyclesInputBitmap() throws Exception {
    ImmutableList<Effect> effects =
        ImmutableList.of(RgbFilter.createGrayscaleFilter(), Presentation.createForHeight(200));
    Bitmap inputBitmap = createBitmap();
    ListenableFuture<Void> unused = experimentalBitmapProcessor.setEffectsAsync(effects);

    experimentalBitmapProcessor.applyEffectsAsync(inputBitmap).get(TEST_TIMEOUT_MS, MILLISECONDS);

    assertThat(inputBitmap.isRecycled()).isTrue();
  }

  @Test
  public void setEffectsAsync_reconfiguresEffects() throws Exception {
    ImmutableList<Effect> effects1 = ImmutableList.of(Presentation.createForHeight(HEIGHT * 2));
    ImmutableList<Effect> effects2 = ImmutableList.of(Presentation.createForHeight(HEIGHT / 2));
    Bitmap inputBitmap1 = createBitmap();
    Bitmap inputBitmap2 = createBitmap();

    ListenableFuture<Void> unused1 = experimentalBitmapProcessor.setEffectsAsync(effects1);
    ListenableFuture<Bitmap> resultFuture1 =
        experimentalBitmapProcessor.applyEffectsAsync(inputBitmap1);
    ListenableFuture<Void> unused2 = experimentalBitmapProcessor.setEffectsAsync(effects2);
    ListenableFuture<Bitmap> resultFuture2 =
        experimentalBitmapProcessor.applyEffectsAsync(inputBitmap2);

    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                createBitmap(WIDTH * 2, HEIGHT * 2, Color.RED),
                resultFuture1.get(TEST_TIMEOUT_MS, MILLISECONDS),
                /* testId= */ null,
                /* differencesBitmapPath= */ null))
        .isEqualTo(0);
    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                createBitmap(WIDTH / 2, HEIGHT / 2, Color.RED),
                resultFuture2.get(TEST_TIMEOUT_MS, MILLISECONDS),
                /* testId= */ null,
                /* differencesBitmapPath= */ null))
        .isEqualTo(0);
  }

  @Test
  public void setEffectsAsync_cancelledBeforeStarted_onlyCancelsSubsequentApplyEffectsAsync()
      throws Exception {
    ConditionVariable blockGlThread = new ConditionVariable();
    ConditionVariable hasBlocked = new ConditionVariable();
    ListenableFuture<Void> setEffectsFuture1 =
        experimentalBitmapProcessor.setEffectsAsync(
            ImmutableList.of(createBlockingGlEffect(blockGlThread, hasBlocked)));
    ListenableFuture<Void> setEffectsFuture2 =
        experimentalBitmapProcessor.setEffectsAsync(ImmutableList.of());
    ListenableFuture<Bitmap> resultFuture1 =
        experimentalBitmapProcessor.applyEffectsAsync(createBitmap());
    ListenableFuture<Void> setEffectsFuture3 =
        experimentalBitmapProcessor.setEffectsAsync(ImmutableList.of());

    hasBlocked.block(TEST_TIMEOUT_MS);
    setEffectsFuture2.cancel(true);
    blockGlThread.open();

    setEffectsFuture1.get(TEST_TIMEOUT_MS, MILLISECONDS);
    assertThrows(
        CancellationException.class, () -> setEffectsFuture2.get(TEST_TIMEOUT_MS, MILLISECONDS));
    assertThat(setEffectsFuture2.isCancelled()).isTrue();
    ExecutionException e =
        assertThrows(
            ExecutionException.class, () -> resultFuture1.get(TEST_TIMEOUT_MS, MILLISECONDS));
    assertThat(e).hasCauseThat().isInstanceOf(CancellationException.class);
    // Dependent futures are failed, not cancelled.
    assertThat(resultFuture1.isCancelled()).isFalse();
    // After the next setEffectsAsync call, the pipeline succeeds as expected.
    setEffectsFuture3.get(TEST_TIMEOUT_MS, MILLISECONDS);
  }

  @Test
  public void setEffectsAsync_cancelledAfterStarted_onlyCancelsSubsequentApplyEffectsAsync()
      throws Exception {
    ConditionVariable blockGlThread = new ConditionVariable();
    ConditionVariable hasBlocked = new ConditionVariable();
    ListenableFuture<Void> setEffectsFuture1 =
        experimentalBitmapProcessor.setEffectsAsync(
            ImmutableList.of(createBlockingGlEffect(blockGlThread, hasBlocked)));
    ListenableFuture<Bitmap> resultFuture1 =
        experimentalBitmapProcessor.applyEffectsAsync(createBitmap());
    ListenableFuture<Void> setEffectsFuture2 =
        experimentalBitmapProcessor.setEffectsAsync(
            ImmutableList.of(Presentation.createForHeight(HEIGHT / 2)));
    ListenableFuture<Bitmap> resultFuture2 =
        experimentalBitmapProcessor.applyEffectsAsync(createBitmap());

    hasBlocked.block(TEST_TIMEOUT_MS);
    setEffectsFuture1.cancel(true);
    blockGlThread.open();

    assertThrows(
        CancellationException.class, () -> setEffectsFuture1.get(TEST_TIMEOUT_MS, MILLISECONDS));
    assertThat(setEffectsFuture1.isCancelled()).isTrue();
    ExecutionException e =
        assertThrows(
            ExecutionException.class, () -> resultFuture1.get(TEST_TIMEOUT_MS, MILLISECONDS));
    assertThat(e).hasCauseThat().isInstanceOf(CancellationException.class);
    // Dependent futures are failed, not cancelled.
    assertThat(resultFuture1.isCancelled()).isFalse();
    // After the next setEffectsAsync call, the pipeline succeeds as expected.
    setEffectsFuture2.get(TEST_TIMEOUT_MS, MILLISECONDS);
    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                createBitmap(WIDTH / 2, HEIGHT / 2, Color.RED),
                resultFuture2.get(TEST_TIMEOUT_MS, MILLISECONDS),
                /* testId= */ null,
                /* differencesBitmapPath= */ null))
        .isEqualTo(0);
  }

  @Test
  public void applyEffectsAsync_cancelledBeforeStarted_doesNotPropagateCancellation()
      throws Exception {
    ConditionVariable blockGlThread = new ConditionVariable();
    ConditionVariable hasBlocked = new ConditionVariable();
    ListenableFuture<Void> setEffectsFuture1 =
        experimentalBitmapProcessor.setEffectsAsync(
            ImmutableList.of(createBlockingGlEffect(blockGlThread, hasBlocked)));
    ListenableFuture<Bitmap> resultFuture1 =
        experimentalBitmapProcessor.applyEffectsAsync(createBitmap());
    ListenableFuture<Bitmap> resultFuture2 =
        experimentalBitmapProcessor.applyEffectsAsync(createBitmap());
    ListenableFuture<Bitmap> resultFuture3 =
        experimentalBitmapProcessor.applyEffectsAsync(createBitmap());

    hasBlocked.block(TEST_TIMEOUT_MS);
    resultFuture2.cancel(true);
    blockGlThread.open();

    setEffectsFuture1.get(TEST_TIMEOUT_MS, MILLISECONDS);
    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                createBitmap(WIDTH, HEIGHT, Color.RED),
                resultFuture1.get(TEST_TIMEOUT_MS, MILLISECONDS),
                /* testId= */ null,
                /* differencesBitmapPath= */ null))
        .isEqualTo(0);
    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                createBitmap(WIDTH, HEIGHT, Color.RED),
                resultFuture3.get(TEST_TIMEOUT_MS, MILLISECONDS),
                /* testId= */ null,
                /* differencesBitmapPath= */ null))
        .isEqualTo(0);
    assertThat(resultFuture2.isCancelled()).isTrue();
  }

  @Test
  public void applyEffectsAsync_cancelledDuringProcessing_doesNotPropagateCancellation()
      throws Exception {
    ConditionVariable blockGlThread = new ConditionVariable();
    ConditionVariable hasBlocked = new ConditionVariable();
    // Create a GlShaderProgram that blocks the GL thread when it receives the second bitmap.
    ListenableFuture<Void> setEffectsFuture1 =
        experimentalBitmapProcessor.setEffectsAsync(
            ImmutableList.of(
                (GlEffect)
                    (context, useHdr) ->
                        createBlockingGlShaderProgram(
                            blockGlThread, hasBlocked, /* frameIndexToBlock= */ 1)));
    ListenableFuture<Bitmap> resultFuture1 =
        experimentalBitmapProcessor.applyEffectsAsync(createBitmap());
    ListenableFuture<Bitmap> resultFuture2 =
        experimentalBitmapProcessor.applyEffectsAsync(createBitmap());
    ListenableFuture<Bitmap> resultFuture3 =
        experimentalBitmapProcessor.applyEffectsAsync(createBitmap());

    hasBlocked.block(TEST_TIMEOUT_MS);
    resultFuture2.cancel(true);
    blockGlThread.open();

    setEffectsFuture1.get(TEST_TIMEOUT_MS, MILLISECONDS);
    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                createBitmap(WIDTH, HEIGHT, Color.RED),
                resultFuture1.get(TEST_TIMEOUT_MS, MILLISECONDS),
                /* testId= */ null,
                /* differencesBitmapPath= */ null))
        .isEqualTo(0);
    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                createBitmap(WIDTH, HEIGHT, Color.RED),
                resultFuture3.get(TEST_TIMEOUT_MS, MILLISECONDS),
                /* testId= */ null,
                /* differencesBitmapPath= */ null))
        .isEqualTo(0);
  }

  @Test
  public void applyEffectsAsync_whenFailed_failsSubsequentFutures() throws Exception {
    RuntimeException e = new RuntimeException("Test exception");
    ImmutableList<Effect> failingEffects =
        ImmutableList.of(createFailingGlShaderProgram(e, /* failingFrameIndex= */ 0));
    experimentalBitmapProcessor.setEffectsAsync(failingEffects).get(TEST_TIMEOUT_MS, MILLISECONDS);

    ListenableFuture<Bitmap> resultFuture1 =
        experimentalBitmapProcessor.applyEffectsAsync(createBitmap());
    ListenableFuture<Bitmap> resultFuture2 =
        experimentalBitmapProcessor.applyEffectsAsync(createBitmap());

    ExecutionException exception =
        assertThrows(
            ExecutionException.class, () -> resultFuture1.get(TEST_TIMEOUT_MS, MILLISECONDS));
    assertThat(exception).hasCauseThat().isInstanceOf(VideoFrameProcessingException.class);
    assertThat(exception).hasCauseThat().hasCauseThat().isEqualTo(e);
    exception =
        assertThrows(
            ExecutionException.class, () -> resultFuture2.get(TEST_TIMEOUT_MS, MILLISECONDS));
    assertThat(exception).hasCauseThat().isInstanceOf(VideoFrameProcessingException.class);
    assertThat(exception).hasCauseThat().hasCauseThat().isEqualTo(e);

    // applyEffectsAsync called after the pipeline fails throw an IllegalStateException.
    ListenableFuture<Bitmap> resultFuture3 =
        experimentalBitmapProcessor.applyEffectsAsync(createBitmap());
    exception =
        assertThrows(
            ExecutionException.class, () -> resultFuture3.get(TEST_TIMEOUT_MS, MILLISECONDS));
    assertThat(exception).hasCauseThat().isInstanceOf(IllegalStateException.class);
  }

  @Test
  public void applyEffectsAsync_whenFailed_doesNotFailNextSetEffectsAsync() throws Exception {
    RuntimeException e = new RuntimeException("Test exception");
    ImmutableList<Effect> failingEffects =
        ImmutableList.of(createFailingGlShaderProgram(e, /* failingFrameIndex= */ 0));

    ListenableFuture<Void> setEffectsFuture1 =
        experimentalBitmapProcessor.setEffectsAsync(failingEffects);
    ListenableFuture<Bitmap> resultFuture1 =
        experimentalBitmapProcessor.applyEffectsAsync(createBitmap());
    ListenableFuture<Void> setEffectsFuture2 =
        experimentalBitmapProcessor.setEffectsAsync(ImmutableList.of());
    ListenableFuture<Bitmap> resultFuture2 =
        experimentalBitmapProcessor.applyEffectsAsync(createBitmap());

    setEffectsFuture1.get(TEST_TIMEOUT_MS, MILLISECONDS);
    assertThrows(ExecutionException.class, () -> resultFuture1.get(TEST_TIMEOUT_MS, MILLISECONDS));
    setEffectsFuture2.get(TEST_TIMEOUT_MS, MILLISECONDS);
    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                createBitmap(),
                resultFuture2.get(TEST_TIMEOUT_MS, MILLISECONDS),
                /* testId= */ null,
                /* differencesBitmapPath= */ null))
        .isEqualTo(0);
  }

  @Test
  public void setEffectsAsync_whenFailed_subsequentApplyEffectsAsyncRethrowsException()
      throws Exception {
    RuntimeException e = new RuntimeException("Test exception");
    ImmutableList<Effect> failingEffects =
        ImmutableList.of(
            (GlEffect)
                (context, useHdr) -> {
                  throw e;
                });

    ListenableFuture<Void> setEffectsFuture =
        experimentalBitmapProcessor.setEffectsAsync(failingEffects);
    ListenableFuture<Bitmap> resultFuture1 =
        experimentalBitmapProcessor.applyEffectsAsync(createBitmap());
    ListenableFuture<Bitmap> resultFuture2 =
        experimentalBitmapProcessor.applyEffectsAsync(createBitmap());

    ExecutionException exception =
        assertThrows(
            ExecutionException.class, () -> setEffectsFuture.get(TEST_TIMEOUT_MS, MILLISECONDS));
    assertThat(exception).hasCauseThat().isEqualTo(e);
    exception =
        assertThrows(
            ExecutionException.class, () -> resultFuture1.get(TEST_TIMEOUT_MS, MILLISECONDS));
    assertThat(exception).hasCauseThat().isEqualTo(e);
    exception =
        assertThrows(
            ExecutionException.class, () -> resultFuture2.get(TEST_TIMEOUT_MS, MILLISECONDS));
    assertThat(exception).hasCauseThat().isEqualTo(e);
  }

  @Test
  public void setEffectsAsync_whenFailed_doesNotFailNextSetEffectsAsync() throws Exception {
    RuntimeException e = new RuntimeException("Test exception");
    ImmutableList<Effect> failingEffects =
        ImmutableList.of(
            (GlEffect)
                (context, useHdr) -> {
                  throw e;
                });

    ListenableFuture<Void> setEffectsFuture1 =
        experimentalBitmapProcessor.setEffectsAsync(failingEffects);
    ListenableFuture<Bitmap> resultFuture1 =
        experimentalBitmapProcessor.applyEffectsAsync(createBitmap());
    ListenableFuture<Void> setEffectsFuture2 =
        experimentalBitmapProcessor.setEffectsAsync(ImmutableList.of());
    ListenableFuture<Bitmap> resultFuture2 =
        experimentalBitmapProcessor.applyEffectsAsync(createBitmap());

    assertThrows(
        ExecutionException.class, () -> setEffectsFuture1.get(TEST_TIMEOUT_MS, MILLISECONDS));
    assertThrows(ExecutionException.class, () -> resultFuture1.get(TEST_TIMEOUT_MS, MILLISECONDS));
    setEffectsFuture2.get(TEST_TIMEOUT_MS, MILLISECONDS);
    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                createBitmap(),
                resultFuture2.get(TEST_TIMEOUT_MS, MILLISECONDS),
                /* testId= */ null,
                /* differencesBitmapPath= */ null))
        .isEqualTo(0);
  }

  @Test
  public void setEffectsAsync_afterReleaseAsyncStarted_throwsIllegalStateException()
      throws Exception {
    ListenableFuture<Void> releaseFuture = experimentalBitmapProcessor.releaseAsync();
    ListenableFuture<Void> setEffectsAsyncFuture =
        experimentalBitmapProcessor.setEffectsAsync(ImmutableList.of());

    ExecutionException exception =
        assertThrows(
            ExecutionException.class,
            () -> setEffectsAsyncFuture.get(TEST_TIMEOUT_MS, MILLISECONDS));
    assertThat(exception).hasCauseThat().isInstanceOf(IllegalStateException.class);
    // Ensure the release completes.
    releaseFuture.get(TEST_TIMEOUT_MS, MILLISECONDS);
  }

  @Test
  public void applyEffectsAsync_afterReleaseAsyncStarted_throwsIllegalStateException()
      throws Exception {
    Bitmap inputBitmap = createBitmap();
    experimentalBitmapProcessor
        .setEffectsAsync(ImmutableList.of())
        .get(TEST_TIMEOUT_MS, MILLISECONDS);

    ListenableFuture<Void> releaseFuture = experimentalBitmapProcessor.releaseAsync();
    ListenableFuture<Bitmap> applyFuture =
        experimentalBitmapProcessor.applyEffectsAsync(inputBitmap);

    ExecutionException exception =
        assertThrows(
            ExecutionException.class, () -> applyFuture.get(TEST_TIMEOUT_MS, MILLISECONDS));
    assertThat(exception).hasCauseThat().isInstanceOf(IllegalStateException.class);
    // Ensure the release completes.
    releaseFuture.get(TEST_TIMEOUT_MS, MILLISECONDS);
  }

  @Test
  public void releaseAsync_multipleTimes_completesSuccessfully() throws Exception {
    ListenableFuture<Void> releaseFuture1 = experimentalBitmapProcessor.releaseAsync();
    ListenableFuture<Void> releaseFuture2 = experimentalBitmapProcessor.releaseAsync();

    releaseFuture1.get(TEST_TIMEOUT_MS, MILLISECONDS);
    releaseFuture2.get(TEST_TIMEOUT_MS, MILLISECONDS);
  }

  @Test
  public void releaseAsync_duringSetEffectsAsync_cancelsPendingTasksAndReleasesGlContext()
      throws Exception {
    ConditionVariable blockGlThread = new ConditionVariable();
    ConditionVariable hasBlocked = new ConditionVariable();
    // Block when creating the shader program so release is called before processing starts.
    ListenableFuture<Void> setEffectsFuture1 =
        experimentalBitmapProcessor.setEffectsAsync(
            ImmutableList.of(createBlockingGlEffect(blockGlThread, hasBlocked)));
    ListenableFuture<Bitmap> resultFuture1 =
        experimentalBitmapProcessor.applyEffectsAsync(createBitmap());
    ListenableFuture<Void> setEffectsFuture2 =
        experimentalBitmapProcessor.setEffectsAsync(ImmutableList.of());
    ListenableFuture<Bitmap> resultFuture2 =
        experimentalBitmapProcessor.applyEffectsAsync(createBitmap());

    hasBlocked.block(TEST_TIMEOUT_MS);
    ListenableFuture<Void> releaseFuture = experimentalBitmapProcessor.releaseAsync();
    blockGlThread.open();

    assertThat(setEffectsFuture1.isCancelled()).isTrue();
    assertThat(resultFuture1.isCancelled()).isTrue();
    assertThat(setEffectsFuture2.isCancelled()).isTrue();
    assertThat(resultFuture2.isCancelled()).isTrue();
    releaseFuture.get(TEST_TIMEOUT_MS, MILLISECONDS);
    assertThat(glObjectsProvider.isReleased.get()).isTrue();
  }

  @Test
  public void releaseAsync_duringApplyEffectsAsync_cancelsPendingTasksAndReleasesGlContext()
      throws Exception {
    ConditionVariable blockGlThread = new ConditionVariable();
    ConditionVariable hasBlocked = new ConditionVariable();
    // Block when creating the shader program so release is called before processing starts.
    experimentalBitmapProcessor
        .setEffectsAsync(
            ImmutableList.of(
                (GlEffect)
                    (context, useHdr) ->
                        createBlockingGlShaderProgram(
                            blockGlThread, hasBlocked, /* frameIndexToBlock= */ 0)))
        .get(TEST_TIMEOUT_MS, MILLISECONDS);

    ListenableFuture<Bitmap> resultFuture1 =
        experimentalBitmapProcessor.applyEffectsAsync(createBitmap());
    ListenableFuture<Void> setEffectsFuture2 =
        experimentalBitmapProcessor.setEffectsAsync(ImmutableList.of());
    ListenableFuture<Bitmap> resultFuture2 =
        experimentalBitmapProcessor.applyEffectsAsync(createBitmap());

    hasBlocked.block(TEST_TIMEOUT_MS);
    ListenableFuture<Void> releaseFuture = experimentalBitmapProcessor.releaseAsync();
    blockGlThread.open();

    assertThrows(
        CancellationException.class, () -> resultFuture1.get(TEST_TIMEOUT_MS, MILLISECONDS));
    assertThrows(
        CancellationException.class, () -> setEffectsFuture2.get(TEST_TIMEOUT_MS, MILLISECONDS));
    assertThrows(
        CancellationException.class, () -> resultFuture2.get(TEST_TIMEOUT_MS, MILLISECONDS));
    releaseFuture.get(TEST_TIMEOUT_MS, MILLISECONDS);
    assertThat(glObjectsProvider.isReleased.get()).isTrue();
  }

  @Test
  public void setEffectsAsync_fromDifferentThread_throwsIllegalStateException() throws Exception {
    ExecutorService secondaryThread = Util.newSingleThreadExecutor("");

    ExecutionException exception =
        assertThrows(
            ExecutionException.class,
            () ->
                secondaryThread
                    .submit(
                        () ->
                            experimentalBitmapProcessor
                                .setEffectsAsync(ImmutableList.of())
                                .get(TEST_TIMEOUT_MS, MILLISECONDS))
                    .get(TEST_TIMEOUT_MS, MILLISECONDS));

    assertThat(exception).hasCauseThat().isInstanceOf(IllegalStateException.class);
    secondaryThread.shutdownNow();
  }

  @Test
  public void applyEffectsAsync_fromDifferentThread_throwsIllegalStateException() throws Exception {
    ExecutorService secondaryThread = Util.newSingleThreadExecutor("");

    ExecutionException exception =
        assertThrows(
            ExecutionException.class,
            () ->
                secondaryThread
                    .submit(
                        () ->
                            experimentalBitmapProcessor
                                .applyEffectsAsync(createBitmap())
                                .get(TEST_TIMEOUT_MS, MILLISECONDS))
                    .get(TEST_TIMEOUT_MS, MILLISECONDS));

    assertThat(exception).hasCauseThat().isInstanceOf(IllegalStateException.class);
    secondaryThread.shutdownNow();
  }

  @Test
  public void release_fromDifferentThread_throwsIllegalStateException() throws Exception {
    ExecutorService secondaryThread = Util.newSingleThreadExecutor("");

    ExecutionException exception =
        assertThrows(
            ExecutionException.class,
            () ->
                secondaryThread
                    .submit(
                        () ->
                            experimentalBitmapProcessor
                                .releaseAsync()
                                .get(TEST_TIMEOUT_MS, MILLISECONDS))
                    .get(TEST_TIMEOUT_MS, MILLISECONDS));

    assertThat(exception).hasCauseThat().isInstanceOf(IllegalStateException.class);
    secondaryThread.shutdownNow();
  }

  /**
   * Creates a {@link GlEffect} that returns a {@link PassthroughShaderProgram} that fails when a
   * frame is queued.
   */
  private static GlEffect createFailingGlShaderProgram(
      RuntimeException exception, int failingFrameIndex) {
    return (context, useHdr) ->
        new PassthroughShaderProgram() {

          int currentFrameIndex;

          @Override
          public void queueInputFrame(
              GlObjectsProvider glObjectsProvider,
              GlTextureInfo inputTexture,
              long presentationTimeUs) {
            getInputListener().onInputFrameProcessed(inputTexture);
            if (currentFrameIndex == failingFrameIndex) {
              throw exception;
            }
            currentFrameIndex++;
            super.queueInputFrame(glObjectsProvider, inputTexture, presentationTimeUs);
          }
        };
  }

  /**
   * Creates a {@link GlEffect} that blocks when creating a {@link GlShaderProgram} until the {@link
   * ConditionVariable} is opened.
   */
  private static GlEffect createBlockingGlEffect(
      ConditionVariable blockGlThread, ConditionVariable hasBlocked) {
    return (context, useHdr) -> {
      try {
        hasBlocked.open();
        assertThat(blockGlThread.block(2 * TEST_TIMEOUT_MS)).isTrue();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return new PassthroughShaderProgram();
    };
  }

  /**
   * Creates a {@link GlEffect} that returns a {@link PassthroughShaderProgram} that blocks until
   * the {@link ConditionVariable} is opened.
   */
  private static GlShaderProgram createBlockingGlShaderProgram(
      ConditionVariable blockGlThread, ConditionVariable hasBlocked, int frameIndexToBlock) {
    return new PassthroughShaderProgram() {
      private int currentFrameIndex;

      @Override
      public void queueInputFrame(
          GlObjectsProvider glObjectsProvider,
          GlTextureInfo inputTexture,
          long presentationTimeUs) {
        try {
          if (currentFrameIndex == frameIndexToBlock) {
            hasBlocked.open();
            assertThat(blockGlThread.block(2 * TEST_TIMEOUT_MS)).isTrue();
          }
          currentFrameIndex++;
          super.queueInputFrame(glObjectsProvider, inputTexture, presentationTimeUs);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    };
  }

  private static Bitmap createBitmap() {
    return createBitmap(WIDTH, HEIGHT, Color.RED);
  }

  private static Bitmap createBitmap(int width, int height, @ColorInt int color) {
    Bitmap bitmap = Bitmap.createBitmap(width, height, Config.ARGB_8888);
    bitmap.eraseColor(color);
    return bitmap;
  }

  /**
   * Convenience wrapper around a {@link androidx.media3.effect.DefaultGlObjectsProvider} that
   * allows the {@link #release} call to be tracked.
   */
  private static final class TestGlObjectsProvider implements GlObjectsProvider {

    public final AtomicBoolean isReleased;
    private final GlObjectsProvider glObjectsProvider;

    public TestGlObjectsProvider() {
      this.isReleased = new AtomicBoolean();
      this.glObjectsProvider = new DefaultGlObjectsProvider();
    }

    @Override
    public EGLContext createEglContext(
        EGLDisplay eglDisplay, int openGlVersion, int[] configAttributes) throws GlException {
      return glObjectsProvider.createEglContext(eglDisplay, openGlVersion, configAttributes);
    }

    @Override
    public EGLSurface createEglSurface(
        EGLDisplay eglDisplay,
        Object surface,
        @ColorTransfer int colorTransfer,
        boolean isEncoderInputSurface)
        throws GlException {
      return glObjectsProvider.createEglSurface(
          eglDisplay, surface, colorTransfer, isEncoderInputSurface);
    }

    @Override
    public EGLSurface createFocusedPlaceholderEglSurface(
        EGLContext eglContext, EGLDisplay eglDisplay) throws GlException {
      return glObjectsProvider.createFocusedPlaceholderEglSurface(eglContext, eglDisplay);
    }

    @Override
    public GlTextureInfo createBuffersForTexture(int texId, int width, int height)
        throws GlException {
      return glObjectsProvider.createBuffersForTexture(texId, width, height);
    }

    @Override
    public void release(EGLDisplay eglDisplay) throws GlException {
      isReleased.set(true);
      glObjectsProvider.release(eglDisplay);
    }
  }
}
