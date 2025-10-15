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

import static java.lang.annotation.ElementType.TYPE_USE;

import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.Player;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Exception thrown when the player is stuck in {@link Player#STATE_READY} or {@link
 * Player#STATE_BUFFERING} while {@link Player#getPlayWhenReady()} is true without observable
 * progress and no means of unblocking itself.
 */
@UnstableApi
public final class StuckPlayerException extends IllegalStateException {

  /**
   * The player is stuck because it's in {@link Player#STATE_BUFFERING}, needs to load more data to
   * make progress, but is not loading.
   */
  public static final int STUCK_BUFFERING_NOT_LOADING = 0;

  /**
   * The player is stuck because it's in {@link Player#STATE_BUFFERING}, but no loading progress is
   * made and the player is also not able to become ready.
   */
  public static final int STUCK_BUFFERING_NO_PROGRESS = 1;

  /** The player is stuck because it's in {@link Player#STATE_READY}, but no progress is made. */
  public static final int STUCK_PLAYING_NO_PROGRESS = 2;

  /**
   * The player is stuck because it's in {@link Player#STATE_READY}, but it's not able to end
   * playback despite exceeding the declared duration.
   */
  public static final int STUCK_PLAYING_NOT_ENDING = 3;

  /**
   * The player is stuck with a suppression reason other than {@link
   * Player#PLAYBACK_SUPPRESSION_REASON_NONE} or {@link
   * Player#PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS}.
   */
  public static final int STUCK_SUPPRESSED = 4;

  /**
   * The type of stuck playback. One of:
   *
   * <ul>
   *   <li>{@link #STUCK_BUFFERING_NOT_LOADING}
   *   <li>{@link #STUCK_BUFFERING_NO_PROGRESS}
   *   <li>{@link #STUCK_PLAYING_NO_PROGRESS}
   *   <li>{@link #STUCK_PLAYING_NOT_ENDING}
   *   <li>{@link #STUCK_SUPPRESSED}
   * </ul>
   */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({
    STUCK_BUFFERING_NOT_LOADING,
    STUCK_BUFFERING_NO_PROGRESS,
    STUCK_PLAYING_NO_PROGRESS,
    STUCK_PLAYING_NOT_ENDING,
    STUCK_SUPPRESSED
  })
  public @interface StuckType {}

  /** The type of stuck playback. */
  public final @StuckType int stuckType;

  /** The timeout after which the exception was triggered, in milliseconds. */
  public final int timeoutMs;

  /**
   * Creates an instance.
   *
   * @param stuckType The {@linkplain StuckType type of stuck playback}.
   * @param timeoutMs The timeout after which the exception was triggered, in milliseconds.
   */
  public StuckPlayerException(@StuckType int stuckType, int timeoutMs) {
    super(getMessage(stuckType, timeoutMs));
    this.stuckType = stuckType;
    this.timeoutMs = timeoutMs;
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null || getClass() != obj.getClass()) {
      return false;
    }
    StuckPlayerException other = (StuckPlayerException) obj;
    return this.stuckType == other.stuckType && timeoutMs == other.timeoutMs;
  }

  @Override
  public int hashCode() {
    int hashCode = 17;
    hashCode = 31 * hashCode + stuckType;
    hashCode = 31 * hashCode + timeoutMs;
    return hashCode;
  }

  private static String getMessage(@StuckType int stuckType, int timeoutMs) {
    switch (stuckType) {
      case STUCK_BUFFERING_NOT_LOADING:
        return "Player stuck buffering and not loading for " + timeoutMs + " ms";
      case STUCK_BUFFERING_NO_PROGRESS:
        return "Player stuck buffering with no progress for " + timeoutMs + " ms";
      case STUCK_PLAYING_NO_PROGRESS:
        return "Player stuck playing with no progress for " + timeoutMs + " ms";
      case STUCK_PLAYING_NOT_ENDING:
        return "Player stuck playing without ending for " + timeoutMs + " ms";
      case STUCK_SUPPRESSED:
        return "Player stuck suppressed for " + timeoutMs + " ms";
      default:
        throw new IllegalStateException();
    }
  }
}
