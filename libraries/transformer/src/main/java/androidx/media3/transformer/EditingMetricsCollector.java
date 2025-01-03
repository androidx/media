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

import static androidx.media3.common.util.Assertions.checkNotNull;

import android.content.Context;
import android.media.metrics.EditingEndedEvent;
import android.media.metrics.EditingSession;
import android.media.metrics.MediaMetricsManager;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * A metrics collector that collects editing events and forwards them to an {@link EditingSession}
 * created by {@link MediaMetricsManager}.
 */
@RequiresApi(35)
/* package */ final class EditingMetricsCollector {

  private @MonotonicNonNull EditingSession editingSession;

  /**
   * Creates an instance.
   *
   * <p>A new instance must be created before starting a new export.
   *
   * @param context The {@link Context}.
   */
  public EditingMetricsCollector(Context context) {
    @Nullable
    MediaMetricsManager mediaMetricsManager =
        (MediaMetricsManager) context.getSystemService(Context.MEDIA_METRICS_SERVICE);
    if (mediaMetricsManager != null) {
      editingSession = checkNotNull(mediaMetricsManager.createEditingSession());
    }
  }

  /** Called when export completes with success. */
  public void onExportSuccess() {
    if (editingSession == null) {
      return;
    }
    editingSession.reportEditingEndedEvent(
        new EditingEndedEvent.Builder(EditingEndedEvent.FINAL_STATE_SUCCEEDED).build());
    editingSession.close();
  }

  /** Called when export completes with an error. */
  public void onExportError() {
    if (editingSession == null) {
      return;
    }
    editingSession.reportEditingEndedEvent(
        new EditingEndedEvent.Builder(EditingEndedEvent.FINAL_STATE_ERROR).build());
    editingSession.close();
  }

  /** Called when export is cancelled. */
  public void onExportCancelled() {
    if (editingSession == null) {
      return;
    }
    editingSession.reportEditingEndedEvent(
        new EditingEndedEvent.Builder(EditingEndedEvent.FINAL_STATE_CANCELED).build());
    editingSession.close();
  }
}
