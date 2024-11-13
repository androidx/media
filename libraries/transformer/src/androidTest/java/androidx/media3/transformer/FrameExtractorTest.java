/*
 * Copyright 2024 The Android Open Source Project
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

import static androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND;
import static androidx.media3.test.utils.BitmapPixelTestUtil.maybeSaveTestBitmap;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.common.util.NullableType;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.transformer.ExperimentalFrameExtractor.Frame;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/** End-to-end instrumentation test for {@link ExperimentalFrameExtractor}. */
@RunWith(AndroidJUnit4.class)
public class FrameExtractorTest {
  private static final String FILE_PATH =
      "asset:///media/mp4/sample_with_increasing_timestamps_360p.mp4";
  private static final long TIMEOUT_SECONDS = 10;

  @Rule public final TestName testName = new TestName();

  private final Context context = ApplicationProvider.getApplicationContext();

  private String testId;
  private @MonotonicNonNull ExperimentalFrameExtractor frameExtractor;

  @Before
  public void setUpTestId() {
    testId = testName.getMethodName();
  }

  @After
  public void tearDown() {
    if (frameExtractor != null) {
      frameExtractor.release();
    }
  }

  @Test
  public void extractFrame_oneFrame_returnsNearest() throws Exception {
    frameExtractor = new ExperimentalFrameExtractor(context, MediaItem.fromUri(FILE_PATH));

    ListenableFuture<Frame> frameFuture = frameExtractor.getFrame(/* positionMs= */ 8_500);
    Bitmap bitmap = frameFuture.get(TIMEOUT_SECONDS, SECONDS).bitmap;

    maybeSaveTestBitmap(testId, /* bitmapLabel= */ "actual", bitmap, /* path= */ null);
    assertThat(frameFuture.get(TIMEOUT_SECONDS, SECONDS).presentationTimeMs).isEqualTo(8_531);
    // TODO: b/350498258 - Actually check Bitmap contents. Due to bugs in hardware decoders,
    //   such a test would require a too high tolerance.
    assertThat(bitmap.getWidth()).isEqualTo(640);
    assertThat(bitmap.getHeight()).isEqualTo(360);
    assertThat(bitmap.getConfig()).isEqualTo(Bitmap.Config.ARGB_8888);
  }

  @Test
  public void extractFrame_pastDuration_returnsLastFrame() throws Exception {
    frameExtractor = new ExperimentalFrameExtractor(context, MediaItem.fromUri(FILE_PATH));

    ListenableFuture<Frame> frameFuture = frameExtractor.getFrame(/* positionMs= */ 200_000);
    int lastVideoFramePresentationTimeMs = 17_029;

    assertThat(frameFuture.get(TIMEOUT_SECONDS, SECONDS).presentationTimeMs)
        .isEqualTo(lastVideoFramePresentationTimeMs);
  }

  @Test
  public void extractFrame_repeatedPositionMs_returnsTheSameFrame() throws Exception {
    frameExtractor = new ExperimentalFrameExtractor(context, MediaItem.fromUri(FILE_PATH));

    ListenableFuture<Frame> frame0 = frameExtractor.getFrame(/* positionMs= */ 0);
    ListenableFuture<Frame> frame0Again = frameExtractor.getFrame(/* positionMs= */ 0);
    ListenableFuture<Frame> frame33 = frameExtractor.getFrame(/* positionMs= */ 33);
    ListenableFuture<Frame> frame34 = frameExtractor.getFrame(/* positionMs= */ 34);
    ListenableFuture<Frame> frame34Again = frameExtractor.getFrame(/* positionMs= */ 34);

    assertThat(frame0.get(TIMEOUT_SECONDS, SECONDS).presentationTimeMs).isEqualTo(0);
    assertThat(frame0Again.get(TIMEOUT_SECONDS, SECONDS).presentationTimeMs).isEqualTo(0);
    assertThat(frame33.get(TIMEOUT_SECONDS, SECONDS).presentationTimeMs).isEqualTo(33);
    assertThat(frame34.get(TIMEOUT_SECONDS, SECONDS).presentationTimeMs).isEqualTo(66);
    assertThat(frame34Again.get(TIMEOUT_SECONDS, SECONDS).presentationTimeMs).isEqualTo(66);
  }

  @Test
  public void extractFrame_randomAccess_returnsCorrectFrames() throws Exception {
    frameExtractor = new ExperimentalFrameExtractor(context, MediaItem.fromUri(FILE_PATH));

    ListenableFuture<Frame> frame5 = frameExtractor.getFrame(/* positionMs= */ 5_000);
    ListenableFuture<Frame> frame3 = frameExtractor.getFrame(/* positionMs= */ 3_000);
    ListenableFuture<Frame> frame7 = frameExtractor.getFrame(/* positionMs= */ 7_000);
    ListenableFuture<Frame> frame2 = frameExtractor.getFrame(/* positionMs= */ 2_000);
    ListenableFuture<Frame> frame8 = frameExtractor.getFrame(/* positionMs= */ 8_000);

    assertThat(frame5.get(TIMEOUT_SECONDS, SECONDS).presentationTimeMs).isEqualTo(5_032);
    assertThat(frame3.get(TIMEOUT_SECONDS, SECONDS).presentationTimeMs).isEqualTo(3_032);
    assertThat(frame7.get(TIMEOUT_SECONDS, SECONDS).presentationTimeMs).isEqualTo(7_031);
    assertThat(frame2.get(TIMEOUT_SECONDS, SECONDS).presentationTimeMs).isEqualTo(2_032);
    assertThat(frame8.get(TIMEOUT_SECONDS, SECONDS).presentationTimeMs).isEqualTo(8_031);
  }

  @Test
  public void extractFrame_invalidInput_reportsErrorViaFuture() {
    String filePath = "asset:///nonexistent";
    frameExtractor = new ExperimentalFrameExtractor(context, MediaItem.fromUri(filePath));

    ListenableFuture<Frame> frame0 = frameExtractor.getFrame(/* positionMs= */ 0);

    ExecutionException thrown =
        assertThrows(ExecutionException.class, () -> frame0.get(TIMEOUT_SECONDS, SECONDS));
    assertThat(thrown).hasCauseThat().isInstanceOf(ExoPlaybackException.class);
    assertThat(((ExoPlaybackException) thrown.getCause()).errorCode)
        .isEqualTo(ERROR_CODE_IO_FILE_NOT_FOUND);
  }

  @Test
  public void extractFrame_oneFrame_completesViaCallback() throws Exception {
    frameExtractor = new ExperimentalFrameExtractor(context, MediaItem.fromUri(FILE_PATH));
    AtomicReference<@NullableType Frame> frameAtomicReference = new AtomicReference<>();
    AtomicReference<@NullableType Throwable> throwableAtomicReference = new AtomicReference<>();
    ConditionVariable frameReady = new ConditionVariable();

    ListenableFuture<Frame> frameFuture = frameExtractor.getFrame(/* positionMs= */ 0);
    Futures.addCallback(
        frameFuture,
        new FutureCallback<Frame>() {
          @Override
          public void onSuccess(Frame result) {
            frameAtomicReference.set(result);
            frameReady.open();
          }

          @Override
          public void onFailure(Throwable t) {
            throwableAtomicReference.set(t);
            frameReady.open();
          }
        },
        directExecutor());
    frameReady.block(/* timeoutMs= */ TIMEOUT_SECONDS * 1000);

    assertThat(throwableAtomicReference.get()).isNull();
    assertThat(frameAtomicReference.get().presentationTimeMs).isEqualTo(0);
  }

  @Test
  public void frameExtractor_releaseOnPlayerLooper_returns() throws Exception {
    frameExtractor = new ExperimentalFrameExtractor(context, MediaItem.fromUri(FILE_PATH));

    Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    instrumentation.runOnMainSync(frameExtractor::release);
    frameExtractor = null;
  }
}
