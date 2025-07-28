/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.exoplayer;

import static java.lang.Math.max;

import android.os.Handler;
import android.os.Message;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.HandlerWrapper;
import java.util.Objects;

/** Detection logic for stuck playbacks. */
/* package */ final class StuckPlayerDetector implements Player.Listener, Handler.Callback {

  /** Callback notified when the player appears stuck. */
  public interface Callback {

    /**
     * Called when the player appears to be stuck.
     *
     * @param exception The {@link StuckPlayerException}.
     */
    void onStuckPlayerDetected(StuckPlayerException exception);
  }

  private static final int MSG_STUCK_BUFFERING_TIMEOUT = 1;

  private final Player player;
  private final Callback callback;
  private final Clock clock;
  private final Timeline.Period period;
  private final HandlerWrapper handler;
  private final StuckBufferingDetector stuckBufferingDetector;

  /**
   * Creates the stuck player detector.
   *
   * <p>Must be called on the player's {@link Player#getApplicationLooper()}.
   *
   * @param player The {@link Player} to monitor.
   * @param callback The {@link Callback} to notify of stuck playbacks.
   * @param clock The {@link Clock}.
   * @param stuckBufferingTimeoutMs The timeout after which the player is assumed stuck buffering if
   *     it's buffering and no loading progress is made, in milliseconds.
   */
  public StuckPlayerDetector(
      Player player, Callback callback, Clock clock, int stuckBufferingTimeoutMs) {
    this.player = player;
    this.callback = callback;
    this.clock = clock;
    this.period = new Timeline.Period();
    this.handler = clock.createHandler(player.getApplicationLooper(), /* callback= */ this);
    this.stuckBufferingDetector = new StuckBufferingDetector(stuckBufferingTimeoutMs);
    player.addListener(this);
  }

  /** Releases the stuck player detector. */
  public void release() {
    handler.removeCallbacksAndMessages(/* token= */ null);
    player.removeListener(this);
  }

  @Override
  public void onEvents(Player player, Player.Events events) {
    stuckBufferingDetector.update();
  }

  @Override
  public boolean handleMessage(Message message) {
    switch (message.what) {
      case MSG_STUCK_BUFFERING_TIMEOUT:
        stuckBufferingDetector.update();
        return true;
      default:
        return false;
    }
  }

  private final class StuckBufferingDetector {

    private final int stuckBufferingTimeoutMs;

    @Nullable private Object periodUid;
    private int adGroupIndex;
    private int adIndexInAdGroup;
    private long bufferedPositionInPeriodMs;
    private long bufferedDurationInOtherPeriodsMs;
    private boolean isBuffering;
    private long startRealtimeMs;

    public StuckBufferingDetector(int stuckBufferingTimeoutMs) {
      this.stuckBufferingTimeoutMs = stuckBufferingTimeoutMs;
    }

    public void update() {
      if (player.getPlaybackState() != Player.STATE_BUFFERING
          || !player.getPlayWhenReady()
          || player.getPlaybackSuppressionReason() != Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
        // Preconditions for stuck buffering not met. Clear any pending timeout and ignore.
        if (isBuffering) {
          handler.removeMessages(MSG_STUCK_BUFFERING_TIMEOUT);
        }
        isBuffering = false;
        return;
      }
      Timeline timeline = player.getCurrentTimeline();
      @Nullable
      Object periodUid =
          timeline.isEmpty() ? null : timeline.getUidOfPeriod(player.getCurrentPeriodIndex());
      int adGroupIndex = player.getCurrentAdGroupIndex();
      int adIndexInAdGroup = player.getCurrentAdIndexInAdGroup();
      long bufferedPositionInPeriodMs = player.getBufferedPosition();
      long bufferedDurationInPeriodMs =
          max(0, bufferedPositionInPeriodMs - player.getCurrentPosition());
      long bufferedDurationInOtherPeriodsMs =
          max(0, player.getTotalBufferedDuration() - bufferedDurationInPeriodMs);
      if (periodUid != null && adGroupIndex == C.INDEX_UNSET) {
        bufferedPositionInPeriodMs -=
            timeline.getPeriodByUid(periodUid, period).getPositionInWindowMs();
      }
      long nowRealtimeMs = clock.elapsedRealtime();
      if (isBuffering
          && Objects.equals(periodUid, this.periodUid)
          && adGroupIndex == this.adGroupIndex
          && adIndexInAdGroup == this.adIndexInAdGroup
          && bufferedPositionInPeriodMs == this.bufferedPositionInPeriodMs
          && bufferedDurationInOtherPeriodsMs == this.bufferedDurationInOtherPeriodsMs) {
        // Still the same state, keep current timeout.
        if (nowRealtimeMs - startRealtimeMs >= stuckBufferingTimeoutMs) {
          callback.onStuckPlayerDetected(
              new StuckPlayerException(StuckPlayerException.STUCK_BUFFERING_NO_PROGRESS));
        }
      } else {
        // Restart the timeout from the current time.
        isBuffering = true;
        startRealtimeMs = nowRealtimeMs;
        this.periodUid = periodUid;
        this.adGroupIndex = adGroupIndex;
        this.adIndexInAdGroup = adIndexInAdGroup;
        this.bufferedPositionInPeriodMs = bufferedPositionInPeriodMs;
        this.bufferedDurationInOtherPeriodsMs = bufferedDurationInOtherPeriodsMs;
        handler.removeMessages(MSG_STUCK_BUFFERING_TIMEOUT);
        handler.sendEmptyMessageDelayed(MSG_STUCK_BUFFERING_TIMEOUT, stuckBufferingTimeoutMs);
      }
    }
  }
}
