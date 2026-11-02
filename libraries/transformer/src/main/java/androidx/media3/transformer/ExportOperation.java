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

/**
 * An abstraction for a media export task.
 *
 * <p>Implementations handle specific types of export operations, such as resumed exports or
 * trim-optimized export operations.
 *
 * <p>Unless otherwise specified, methods must be called on the application thread, which is the
 * thread of {@link Transformer#getApplicationLooper()}. Each implementation owns an internal thread
 * on which the export work is performed.
 */
/* package */ interface ExportOperation {

  /** A listener for {@link ExportOperation} events. */
  interface Listener {
    /**
     * Called when the export operation is successfully completed.
     *
     * <p>Called on the application thread. Not called after {@link #cancel()}.
     */
    void onCompleted(ExportResult exportResult);

    /**
     * Called when an error occurs during the export operation.
     *
     * <p>Called on the application thread. Not called after {@link #cancel()}.
     *
     * @param exportResult The {@link ExportResult} of the export, which may contain partial
     *     information.
     * @param exportException The {@link ExportException} describing the error.
     */
    void onError(ExportResult exportResult, ExportException exportException);

    /**
     * Called when a sample has been written or dropped by the muxer.
     *
     * <p>Called on the internal thread of the export operation. Implementations must be thread-safe
     * and cheap, as this method is called once per muxed sample.
     */
    void onSampleWrittenOrDropped();
  }

  /** Starts the export operation. */
  void start();

  /**
   * Returns the current {@link Transformer.ProgressState} and updates {@code progressHolder} with
   * the current progress if it is {@link Transformer#PROGRESS_STATE_AVAILABLE available}.
   *
   * @param progressHolder A {@link ProgressHolder}, updated to hold the percentage progress if
   *     {@link Transformer#PROGRESS_STATE_AVAILABLE available}.
   * @return The {@link Transformer.ProgressState}.
   */
  @Transformer.ProgressState
  int getProgress(ProgressHolder progressHolder);

  /**
   * Cancels the export operation.
   *
   * <p>This method blocks until the resources of the export operation have been released. No
   * terminal {@link Listener} callback is invoked after this method returns.
   */
  void cancel();

  /**
   * Ends the export operation with the specified exception.
   *
   * @param exportException The {@link ExportException} to report to the {@link Listener}.
   */
  void endWithException(ExportException exportException);
}
