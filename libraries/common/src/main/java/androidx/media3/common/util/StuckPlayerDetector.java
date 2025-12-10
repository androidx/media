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
package androidx.media3.common.util;

import static java.lang.Math.max;

import android.os.Message;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import java.util.Objects;

/**
 * A utility class to detect whether a {@link Player} is stuck.
 *
 * <p>The class must be used on the thread associated with the {@link
 * Player#getApplicationLooper()}.
 */
@UnstableApi
public final class StuckPlayerDetector {

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
  private static final int MSG_STUCK_PLAYING_TIMEOUT = 2;
  private static final int MSG_STUCK_PLAYING_NOT_ENDING_TIMEOUT = 3;
  private static final int MSG_STUCK_SUPPRESSED_TIMEOUT = 4;

  private final Player player;
  private final Player.Listener playerListener;
  private final Callback callback;
  private final Clock clock;
  private final Timeline.Period period;
  private final HandlerWrapper handler;
  private final StuckBufferingDetector stuckBufferingDetector;
  private final StuckPlayingDetector stuckPlayingDetector;
  private final StuckPlayingNotEndingDetector stuckPlayingNotEndingDetector;
  private final StuckSuppressedDetector stuckSuppressedDetector;

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
   * @param stuckPlayingTimeoutMs The timeout after which the player is assumed stuck playing if
   *     it's playing and no position progress is made, in milliseconds.
   * @param stuckPlayingNotEndingTimeoutMs The timeout after which the player is assumed stuck
   *     playing if it's playing and it should have ended, in milliseconds.
   * @param stuckSuppressedTimeoutMs The timeout after which the player is assumed stuck in a
   *     suppression state, in milliseconds.
   */
  // Using instance methods as listeners in the constructor
  @SuppressWarnings({"methodref.receiver.bound.invalid", "method.invocation.invalid"})
  public StuckPlayerDetector(
      Player player,
      Callback callback,
      Clock clock,
      int stuckBufferingTimeoutMs,
      int stuckPlayingTimeoutMs,
      int stuckPlayingNotEndingTimeoutMs,
      int stuckSuppressedTimeoutMs) {
    this.player = player;
    this.callback = callback;
    this.clock = clock;
    this.period = new Timeline.Period();
    this.handler =
        clock.createHandler(player.getApplicationLooper(), /* callback= */ this::handleMessage);
    this.stuckBufferingDetector = new StuckBufferingDetector(stuckBufferingTimeoutMs);
    this.stuckPlayingDetector = new StuckPlayingDetector(stuckPlayingTimeoutMs);
    this.stuckPlayingNotEndingDetector =
        new StuckPlayingNotEndingDetector(stuckPlayingNotEndingTimeoutMs);
    this.stuckSuppressedDetector = new StuckSuppressedDetector(stuckSuppressedTimeoutMs);
    this.playerListener =
        new Player.Listener() {
          @Override
          public void onEvents(Player player, Player.Events events) {
            onPlayerEvents();
          }
        };
    player.addListener(playerListener);
  }

  /** Releases the stuck player detector. */
  public void release() {
    handler.removeCallbacksAndMessages(/* token= */ null);
    player.removeListener(playerListener);
  }

  private void onPlayerEvents() {
    stuckBufferingDetector.update();
    stuckPlayingDetector.update();
    stuckPlayingNotEndingDetector.update();
    stuckSuppressedDetector.update();
  }

  private boolean handleMessage(Message message) {
    switch (message.what) {
      case MSG_STUCK_BUFFERING_TIMEOUT:
        stuckBufferingDetector.update();
        return true;
      case MSG_STUCK_PLAYING_TIMEOUT:
        stuckPlayingDetector.update();
        return true;
      case MSG_STUCK_PLAYING_NOT_ENDING_TIMEOUT:
        stuckPlayingNotEndingDetector.update();
        return true;
      case MSG_STUCK_SUPPRESSED_TIMEOUT:
        stuckSuppressedDetector.update();
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
              new StuckPlayerException(
                  StuckPlayerException.STUCK_BUFFERING_NO_PROGRESS, stuckBufferingTimeoutMs));
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

  private final class StuckPlayingDetector {

    private final int stuckPlayingTimeoutMs;

    @Nullable private Object periodUid;
    private int adGroupIndex;
    private int adIndexInAdGroup;
    private long currentPositionInPeriodMs;
    private boolean isPlaying;
    private long startRealtimeMs;

    public StuckPlayingDetector(int stuckPlayingTimeoutMs) {
      this.stuckPlayingTimeoutMs = stuckPlayingTimeoutMs;
    }

    public void update() {
      if (!player.isPlaying()) {
        // Preconditions for stuck playing not met. Clear any pending timeout and ignore.
        if (isPlaying) {
          handler.removeMessages(MSG_STUCK_PLAYING_TIMEOUT);
        }
        isPlaying = false;
        return;
      }
      Timeline timeline = player.getCurrentTimeline();
      @Nullable
      Object periodUid =
          timeline.isEmpty() ? null : timeline.getUidOfPeriod(player.getCurrentPeriodIndex());
      int adGroupIndex = player.getCurrentAdGroupIndex();
      int adIndexInAdGroup = player.getCurrentAdIndexInAdGroup();
      long currentPositionInPeriodMs = player.getCurrentPosition();
      if (periodUid != null && adGroupIndex == C.INDEX_UNSET) {
        currentPositionInPeriodMs -=
            timeline.getPeriodByUid(periodUid, period).getPositionInWindowMs();
      }
      long nowRealtimeMs = clock.elapsedRealtime();
      if (isPlaying
          && Objects.equals(periodUid, this.periodUid)
          && adGroupIndex == this.adGroupIndex
          && adIndexInAdGroup == this.adIndexInAdGroup
          && currentPositionInPeriodMs == this.currentPositionInPeriodMs) {
        // Still the same state, keep current timeout.
        if (nowRealtimeMs - startRealtimeMs >= stuckPlayingTimeoutMs) {
          callback.onStuckPlayerDetected(
              new StuckPlayerException(
                  StuckPlayerException.STUCK_PLAYING_NO_PROGRESS, stuckPlayingTimeoutMs));
        }
      } else {
        // Restart the timeout from the current time.
        isPlaying = true;
        startRealtimeMs = nowRealtimeMs;
        this.periodUid = periodUid;
        this.adGroupIndex = adGroupIndex;
        this.adIndexInAdGroup = adIndexInAdGroup;
        this.currentPositionInPeriodMs = currentPositionInPeriodMs;
        handler.removeMessages(MSG_STUCK_PLAYING_TIMEOUT);
        handler.sendEmptyMessageDelayed(MSG_STUCK_PLAYING_TIMEOUT, stuckPlayingTimeoutMs);
      }
    }
  }

  private final class StuckPlayingNotEndingDetector {

    private final int stuckPlayingNotEndingTimeoutMs;

    @Nullable private Object periodUid;
    private int adGroupIndex;
    private int adIndexInAdGroup;
    private boolean isPlayingAndReachedDuration;
    private long startRealtimeMs;

    public StuckPlayingNotEndingDetector(int stuckPlayingNotEndingTimeoutMs) {
      this.stuckPlayingNotEndingTimeoutMs = stuckPlayingNotEndingTimeoutMs;
    }

    public void update() {
      Timeline timeline = player.getCurrentTimeline();
      @Nullable
      Object periodUid =
          timeline.isEmpty() ? null : timeline.getUidOfPeriod(player.getCurrentPeriodIndex());
      int adGroupIndex = player.getCurrentAdGroupIndex();
      int adIndexInAdGroup = player.getCurrentAdIndexInAdGroup();
      long currentPositionInPeriodOrAdMs = player.getCurrentPosition();
      long durationOfPeriodOrAdMs = C.TIME_UNSET;
      if (periodUid != null && adGroupIndex == C.INDEX_UNSET) {
        timeline.getPeriodByUid(periodUid, period);
        currentPositionInPeriodOrAdMs -= period.getPositionInWindowMs();
        durationOfPeriodOrAdMs = period.getDurationMs();
      } else if (adGroupIndex != C.INDEX_UNSET) {
        durationOfPeriodOrAdMs = player.getDuration();
      }
      boolean isPlaying = player.isPlaying();
      if (!isPlaying
          || durationOfPeriodOrAdMs == C.TIME_UNSET
          || currentPositionInPeriodOrAdMs < durationOfPeriodOrAdMs) {
        // Preconditions for stuck playing not ending not met.
        // Clear any pending previous update or timeout.
        handler.removeMessages(MSG_STUCK_PLAYING_NOT_ENDING_TIMEOUT);
        if (isPlaying && durationOfPeriodOrAdMs != C.TIME_UNSET) {
          // Reschedule update for when the duration is likely reached.
          float realtimeUntilDurationReachedMs =
              (durationOfPeriodOrAdMs - currentPositionInPeriodOrAdMs)
                  / player.getPlaybackParameters().speed;
          handler.sendEmptyMessageDelayed(
              MSG_STUCK_PLAYING_NOT_ENDING_TIMEOUT,
              (int) Math.ceil(realtimeUntilDurationReachedMs));
        }
        this.isPlayingAndReachedDuration = false;
        return;
      }
      long nowRealtimeMs = clock.elapsedRealtime();
      if (this.isPlayingAndReachedDuration
          && Objects.equals(periodUid, this.periodUid)
          && adGroupIndex == this.adGroupIndex
          && adIndexInAdGroup == this.adIndexInAdGroup) {
        // Still the same state, keep current timeout.
        if (nowRealtimeMs - startRealtimeMs >= stuckPlayingNotEndingTimeoutMs) {
          callback.onStuckPlayerDetected(
              new StuckPlayerException(
                  StuckPlayerException.STUCK_PLAYING_NOT_ENDING, stuckPlayingNotEndingTimeoutMs));
        }
      } else {
        // Restart the timeout from the current time.
        isPlayingAndReachedDuration = true;
        startRealtimeMs = nowRealtimeMs;
        this.periodUid = periodUid;
        this.adGroupIndex = adGroupIndex;
        this.adIndexInAdGroup = adIndexInAdGroup;
        handler.removeMessages(MSG_STUCK_PLAYING_NOT_ENDING_TIMEOUT);
        handler.sendEmptyMessageDelayed(
            MSG_STUCK_PLAYING_NOT_ENDING_TIMEOUT, stuckPlayingNotEndingTimeoutMs);
      }
    }
  }

  private final class StuckSuppressedDetector {

    private final int stuckSuppressedTimeoutMs;

    private @Player.PlaybackSuppressionReason int suppressionReason;
    private boolean isSuppressed;
    private long startRealtimeMs;

    public StuckSuppressedDetector(int stuckSuppressedTimeoutMs) {
      this.stuckSuppressedTimeoutMs = stuckSuppressedTimeoutMs;
    }

    public void update() {
      @Player.PlaybackSuppressionReason
      int suppressionReason = player.getPlaybackSuppressionReason();
      if (!player.getPlayWhenReady()
          || player.getPlaybackState() == Player.STATE_IDLE
          || player.getPlaybackState() == Player.STATE_ENDED
          || suppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
          || suppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS) {
        // Preconditions for stuck suppressed not met. Clear any pending timeout and ignore.
        if (isSuppressed) {
          handler.removeMessages(MSG_STUCK_SUPPRESSED_TIMEOUT);
        }
        isSuppressed = false;
        return;
      }
      long nowRealtimeMs = clock.elapsedRealtime();
      if (isSuppressed && this.suppressionReason == suppressionReason) {
        // Still the same state, keep current timeout.
        if (nowRealtimeMs - startRealtimeMs >= stuckSuppressedTimeoutMs) {
          callback.onStuckPlayerDetected(
              new StuckPlayerException(
                  StuckPlayerException.STUCK_SUPPRESSED, stuckSuppressedTimeoutMs));
        }
      } else {
        // Restart the timeout from the current time.
        isSuppressed = true;
        startRealtimeMs = nowRealtimeMs;
        this.suppressionReason = suppressionReason;
        handler.removeMessages(MSG_STUCK_SUPPRESSED_TIMEOUT);
        handler.sendEmptyMessageDelayed(MSG_STUCK_SUPPRESSED_TIMEOUT, stuckSuppressedTimeoutMs);
      }
    }
  }
}
