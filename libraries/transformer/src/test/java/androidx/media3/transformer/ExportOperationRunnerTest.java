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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.robolectric.Shadows.shadowOf;

import android.os.HandlerThread;
import android.os.Looper;
import androidx.media3.common.util.SystemClock;
import androidx.media3.transformer.ExportResult.ProcessedInput;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Uninterruptibles;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link ExportOperationRunner}. */
@RunWith(AndroidJUnit4.class)
public final class ExportOperationRunnerTest {

  private static final long TIMEOUT_MS = 10_000;

  private CountDownLatch applicationThreadBlockedLatch;
  private RecordingListener listener;
  private ExportOperationRunner runner;

  @Before
  public void setUp() {
    applicationThreadBlockedLatch = new CountDownLatch(1);
    SystemClock clock =
        new SystemClock() {
          @Override
          public void onThreadBlocked() {
            applicationThreadBlockedLatch.countDown();
          }
        };
    listener = new RecordingListener();
    runner =
        new ExportOperationRunner(
            "ExportOperationRunnerTest",
            clock,
            clock.createHandler(Looper.getMainLooper(), /* callback= */ null),
            listener);
  }

  @After
  public void tearDown() {
    runner.cancel();
  }

  @Test
  public void start_stageThrowsRuntimeException_reportsUnexpectedErrorToListener()
      throws Exception {
    IllegalStateException stageException = new IllegalStateException();

    runner.start(() -> {
      throw stageException;
    });
    waitForInternalThreadToQuit();

    ExportException exportException = checkNotNull(listener.stageError.get());
    assertThat(exportException.errorCode)
        .isEqualTo(ExportException.ERROR_CODE_FAILED_RUNTIME_CHECK);
    assertThat(exportException).hasCauseThat().isSameInstanceAs(stageException);
  }

  @Test
  public void reportCompleted_notCancelled_runsCallbackOnApplicationThread() throws Exception {
    AtomicReference<@Nullable Looper> callbackLooper = new AtomicReference<>();

    runner.start(() -> runner.reportCompleted(() -> callbackLooper.set(Looper.myLooper())));
    waitForInternalThreadToQuit();
    shadowOf(Looper.getMainLooper()).idle();

    assertThat(callbackLooper.get()).isEqualTo(Looper.getMainLooper());
  }

  @Test
  public void reportCompleted_cancelledBeforeCallbackRuns_skipsCallbackWithoutDiscardingOutput()
      throws Exception {
    AtomicBoolean callbackRan = new AtomicBoolean();
    runner.start(() -> runner.reportCompleted(() -> callbackRan.set(true)));
    waitForInternalThreadToQuit();

    runner.cancel();
    shadowOf(Looper.getMainLooper()).idle();

    assertThat(callbackRan.get()).isFalse();
    assertThat(listener.discardOutputCount).isEqualTo(0);
  }

  @Test
  public void reportCompleted_resultAlreadyReported_doesNotRunSecondCallback() throws Exception {
    List<String> callbacks = new ArrayList<>();

    runner.start(() -> {
      runner.reportCompleted(() -> callbacks.add("first"));
      runner.reportCompleted(() -> callbacks.add("second"));
    });
    waitForInternalThreadToQuit();
    shadowOf(Looper.getMainLooper()).idle();

    assertThat(callbacks).containsExactly("first");
  }

  @Test
  public void reportCompleted_notCancelled_releasesWithoutDiscardingOutput() throws Exception {
    runner.start(() -> runner.reportCompleted(() -> {}));

    waitForInternalThreadToQuit();

    assertThat(listener.releaseCount).isEqualTo(1);
    assertThat(listener.discardOutputCount).isEqualTo(0);
  }

  @Test
  public void reportError_notCancelled_releasesAndDiscardsOutput() throws Exception {
    runner.start(() -> runner.reportError(() -> {}));

    waitForInternalThreadToQuit();

    assertThat(listener.releaseCount).isEqualTo(1);
    assertThat(listener.discardOutputCount).isEqualTo(1);
  }

  @Test
  public void cancel_whileStageRuns_releasesAndDiscardsOutputBeforeReturning() throws Exception {
    CountDownLatch stageStartedLatch = new CountDownLatch(1);
    runner.start(() -> {
      stageStartedLatch.countDown();
      // Keep the stage running until cancel() blocks.
      Uninterruptibles.awaitUninterruptibly(applicationThreadBlockedLatch);
    });
    checkState(stageStartedLatch.await(TIMEOUT_MS, MILLISECONDS));

    runner.cancel();

    assertThat(listener.releaseCount).isEqualTo(1);
    assertThat(listener.discardOutputCount).isEqualTo(1);
  }

  @Test
  public void endWithException_noTransformerInternal_reportsErrorToListener() throws Exception {
    ExportException exportException =
        ExportException.createForUnexpected(new IllegalStateException());

    runner.endWithException(exportException);
    waitForInternalThreadToQuit();

    assertThat(listener.stageError.get()).isSameInstanceAs(exportException);
  }

  private void waitForInternalThreadToQuit() throws InterruptedException {
    HandlerThread internalHandlerThread = runner.getInternalHandlerThread();
    internalHandlerThread.join(TIMEOUT_MS);
    checkState(!internalHandlerThread.isAlive());
  }

  /**
   * An {@link ExportOperationRunner.Listener} that records events and fails on {@link
   * TransformerInternal} events.
   */
  private final class RecordingListener implements ExportOperationRunner.Listener {

    private final AtomicReference<@Nullable ExportException> stageError;

    // Only accessed on the internal thread, or after the export has been released.
    private int releaseCount;
    private int discardOutputCount;

    private RecordingListener() {
      stageError = new AtomicReference<>();
    }

    @Override
    public void onTransformerInternalCompleted(
        ImmutableList<ProcessedInput> processedInputs,
        @Nullable String audioEncoderName,
        @Nullable String videoEncoderName) {
      throw new AssertionError("Unexpected TransformerInternal completion");
    }

    @Override
    public void onTransformerInternalError(
        ImmutableList<ProcessedInput> processedInputs,
        @Nullable String audioEncoderName,
        @Nullable String videoEncoderName,
        ExportException exportException) {
      throw new AssertionError(exportException);
    }

    @Override
    public void onStageError(ExportException exportException) {
      stageError.set(exportException);
      runner.reportError(() -> {});
    }

    @Override
    public void onReleased(boolean discardOutput) {
      releaseCount++;
      if (discardOutput) {
        discardOutputCount++;
      }
    }
  }
}
