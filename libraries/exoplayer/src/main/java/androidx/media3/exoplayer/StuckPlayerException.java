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

import static java.lang.annotation.ElementType.TYPE_USE;

import androidx.annotation.IntDef;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
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
   * The type of stuck playback. One of:
   *
   * <ul>
   *   <li>{@link #STUCK_BUFFERING_NOT_LOADING}
   * </ul>
   */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({STUCK_BUFFERING_NOT_LOADING})
  public @interface StuckType {}

  /** The type of stuck playback. */
  public final @StuckType int stuckType;

  /**
   * Creates an instance.
   *
   * @param stuckType The {@linkplain StuckType type of stuck playback}.
   */
  public StuckPlayerException(@StuckType int stuckType) {
    super(getMessage(stuckType));
    this.stuckType = stuckType;
  }

  private static String getMessage(@StuckType int stuckType) {
    switch (stuckType) {
      case STUCK_BUFFERING_NOT_LOADING:
        return "Player stuck buffering and not loading";
      default:
        throw new IllegalStateException();
    }
  }
}
