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

import static com.google.common.base.Preconditions.checkState;

import android.os.HandlerThread;
import android.os.Looper;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.transformer.ExportResult.ProcessedInput;
import com.google.common.collect.ImmutableList;
import org.jspecify.annotations.Nullable;

/**
 * Runs the stages of an {@link ExportOperation} and their {@link TransformerInternal} instances on
 * a single internal thread, and delivers the result on the application thread.
 *
 * <p>No more stages run once the export has ended or {@link #cancel()} has been called. When {@link
 * #cancel()} returns, the export has been released and no result is delivered afterwards.
 */
/* package */ final class ExportOperationRunner {

  /** A unit of export work that runs on the internal thread. */
  public interface Stage {

    /** Runs the stage. */
    void run() throws ExportException;
  }

  /** A listener for {@link ExportOperationRunner} events, called on the internal thread. */
  public interface Listener {

    /**
     * Called when the {@link TransformerInternal} completes, unless the export has ended or {@link
     * ExportOperationRunner#cancel()} has been called.
     *
     * <p>An exception thrown by this method is reported to {@link #onStageError}.
     */
    void onTransformerInternalCompleted(
        ImmutableList<ProcessedInput> processedInputs,
        @Nullable String audioEncoderName,
        @Nullable String videoEncoderName)
        throws ExportException;

    /**
     * Called when the {@link TransformerInternal} fails, unless the export has ended or {@link
     * ExportOperationRunner#cancel()} has been called.
     *
     * <p>An exception thrown by this method is reported to {@link #onStageError}.
     */
    void onTransformerInternalError(
        ImmutableList<ProcessedInput> processedInputs,
        @Nullable String audioEncoderName,
        @Nullable String videoEncoderName,
        ExportException exportException)
        throws ExportException;

    /**
     * Called when a {@link Stage} throws, or when {@link ExportOperationRunner#endWithException} is
     * called while no {@link TransformerInternal} is running, unless the export has ended or {@link
     * ExportOperationRunner#cancel()} has been called.
     */
    void onStageError(ExportException exportException);

    /**
     * Called once when the export ends, to release the resources held between stages.
     *
     * @param discardOutput Whether to delete the output, because the export was cancelled or
     *     failed.
     */
    void onReleased(boolean discardOutput);
  }

  private final Clock clock;
  private final HandlerWrapper applicationHandler;
  private final Listener listener;
  private final HandlerThread internalHandlerThread;
  private final HandlerWrapper internalHandler;
  private final TransformerInternalListener transformerInternalListener;
  private final ConditionVariable releasedConditionVariable;

  // Written on the application thread, read on multiple threads.
  private volatile boolean isCancelled;

  // Written on the internal thread, read on multiple threads.
  private volatile @Nullable TransformerInternal transformerInternal;

  // Only accessed on the internal thread.
  private boolean isEnded;

  // Written on the internal thread before releasedConditionVariable is opened, read on the
  // application thread after it is opened.
  private @Nullable RuntimeException cancelException;

  /**
   * Creates an instance and starts its internal thread.
   *
   * @param threadName The name of the internal thread.
   * @param clock The {@link Clock} used to create the handler of the internal thread.
   * @param applicationHandler The {@link HandlerWrapper} of the application thread.
   * @param listener The {@link Listener}.
   */
  public ExportOperationRunner(
      String threadName, Clock clock, HandlerWrapper applicationHandler, Listener listener) {
    this.clock = clock;
    this.applicationHandler = applicationHandler;
    this.listener = listener;
    transformerInternalListener = new TransformerInternalListener();
    releasedConditionVariable = new ConditionVariable(clock);
    internalHandlerThread = new HandlerThread(threadName);
    internalHandlerThread.start();
    internalHandler = clock.createHandler(internalHandlerThread.getLooper(), /* callback= */ null);
  }

  /** Returns the internal thread, which must be passed to each {@link TransformerInternal}. */
  public HandlerThread getInternalHandlerThread() {
    return internalHandlerThread;
  }

  /** Returns the listener that must be passed to each {@link TransformerInternal}. */
  public TransformerInternal.Listener getTransformerInternalListener() {
    return transformerInternalListener;
  }

  /**
   * Returns the last {@link TransformerInternal} passed to {@link #startTransformerInternal}, or
   * {@code null} if there is none.
   */
  public @Nullable TransformerInternal getTransformerInternal() {
    return transformerInternal;
  }

  /**
   * Returns whether {@link #cancel()} has been called.
   *
   * <p>Can be called from any thread. A long-running {@link Stage} can use this to return early.
   */
  public boolean isCancelled() {
    return isCancelled;
  }

  /** Posts the first {@link Stage} to the internal thread. */
  public void start(Stage firstStage) {
    checkState(internalHandler.post(() -> runStage(firstStage)));
  }

  /**
   * Starts the {@link TransformerInternal}.
   *
   * <p>Must be called on the internal thread. If {@link #cancel()} has been called, the queued
   * cancellation releases the {@link TransformerInternal} before it handles the start.
   */
  public void startTransformerInternal(TransformerInternal transformerInternal) {
    verifyInternalThread();
    transformerInternal.start();
    // Set after starting, so that getProgress() doesn't report that the export hasn't started.
    this.transformerInternal = transformerInternal;
  }

  /**
   * Ends the export and posts {@code applicationThreadCallback}, unless the export has already
   * ended or {@link #cancel()} has been called.
   *
   * <p>Must be called on the internal thread. The callback is skipped if {@link #cancel()} is
   * called before it runs.
   */
  public void reportCompleted(Runnable applicationThreadCallback) {
    reportResult(applicationThreadCallback, /* completed= */ true);
  }

  /**
   * Ends the export with an error and posts {@code applicationThreadCallback}, unless the export
   * has already ended or {@link #cancel()} has been called.
   *
   * <p>Must be called on the internal thread. The callback is skipped if {@link #cancel()} is
   * called before it runs.
   */
  public void reportError(Runnable applicationThreadCallback) {
    reportResult(applicationThreadCallback, /* completed= */ false);
  }

  /**
   * Cancels the export.
   *
   * <p>Must be called on the application thread. Blocks until the export has been released, which
   * happens once the stage that is running, if any, has returned. If the export has already
   * completed, its result is dropped but its output isn't deleted.
   */
  public void cancel() {
    verifyApplicationThread();
    if (isCancelled) {
      return;
    }
    isCancelled = true;
    // If the internal thread has quit, the post fails, but the export has already been released.
    boolean unused = internalHandler.post(this::cancelInternal);
    clock.onThreadBlocked();
    releasedConditionVariable.blockUninterruptible();
    @Nullable RuntimeException cancelException = this.cancelException;
    if (cancelException != null) {
      throw cancelException;
    }
  }

  /**
   * Ends the export with an {@link ExportException}.
   *
   * <p>Can be called from any thread. If no {@link TransformerInternal} is running, the exception
   * is reported to {@link Listener#onStageError}.
   */
  public void endWithException(ExportException exportException) {
    // If the post fails, the internal thread has quit, so the export has already ended.
    boolean unused =
        internalHandler.post(
            () -> {
              if (isEnded || isCancelled) {
                return;
              }
              @Nullable TransformerInternal transformerInternal = this.transformerInternal;
              if (transformerInternal != null) {
                transformerInternal.endWithException(exportException);
              } else {
                listener.onStageError(exportException);
              }
            });
  }

  private void runStage(Stage stage) {
    if (isEnded || isCancelled) {
      return;
    }
    try {
      stage.run();
    } catch (ExportException e) {
      listener.onStageError(e);
    } catch (RuntimeException e) {
      listener.onStageError(ExportException.createForUnexpected(e));
    }
  }

  private void reportResult(Runnable applicationThreadCallback, boolean completed) {
    verifyInternalThread();
    if (isEnded || isCancelled) {
      return;
    }
    isEnded = true;
    release(/* discardOutput= */ !completed);
    checkState(
        applicationHandler.post(
            () -> {
              if (!isCancelled) {
                applicationThreadCallback.run();
              }
            }));
  }

  private void cancelInternal() {
    if (isEnded) {
      return;
    }
    isEnded = true;
    @Nullable TransformerInternal transformerInternal = this.transformerInternal;
    try {
      if (transformerInternal != null) {
        transformerInternal.cancel();
      }
    } catch (RuntimeException e) {
      cancelException = e;
    } finally {
      release(/* discardOutput= */ true);
    }
  }

  private void release(boolean discardOutput) {
    try {
      listener.onReleased(discardOutput);
    } finally {
      // Quit lazily so that events triggered while releasing are still delivered.
      boolean unused = internalHandler.post(internalHandlerThread::quitSafely);
      releasedConditionVariable.open();
    }
  }

  private void verifyApplicationThread() {
    checkState(Looper.myLooper() == applicationHandler.getLooper());
  }

  private void verifyInternalThread() {
    checkState(Looper.myLooper() == internalHandlerThread.getLooper());
  }

  private final class TransformerInternalListener implements TransformerInternal.Listener {

    @Override
    public void onCompleted(
        ImmutableList<ProcessedInput> processedInputs,
        @Nullable String audioEncoderName,
        @Nullable String videoEncoderName) {
      runStage(
          () ->
              listener.onTransformerInternalCompleted(
                  processedInputs, audioEncoderName, videoEncoderName));
    }

    @Override
    public void onError(
        ImmutableList<ProcessedInput> processedInputs,
        @Nullable String audioEncoderName,
        @Nullable String videoEncoderName,
        ExportException exportException) {
      runStage(
          () ->
              listener.onTransformerInternalError(
                  processedInputs, audioEncoderName, videoEncoderName, exportException));
    }
  }
}
