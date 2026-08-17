/*
 * Copyright (C) 2018 The Android Open Source Project
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
package androidx.media3.cast;

import androidx.annotation.IntDef;
import androidx.media3.common.util.UnstableApi;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Listener of changes in the cast session availability. */
@UnstableApi
public interface SessionAvailabilityListener {

  /** Reasons for the Cast session becoming unavailable. */
  @IntDef({
    SESSION_UNAVAILABLE_REASON_ROUTE_CHANGED,
    SESSION_UNAVAILABLE_REASON_STOPPED,
    SESSION_UNAVAILABLE_REASON_APP_STOPPED,
    SESSION_UNAVAILABLE_REASON_DISCONNECTED
  })
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(ElementType.TYPE_USE)
  @interface SessionUnavailableReason {}

  /** Playback is transferring to the local device because the route was changed. */
  int SESSION_UNAVAILABLE_REASON_ROUTE_CHANGED = 1;

  /** The user explicitly stopped casting. */
  int SESSION_UNAVAILABLE_REASON_STOPPED = 2;

  /** The Cast receiver application was stopped. */
  int SESSION_UNAVAILABLE_REASON_APP_STOPPED = 3;

  /** The Cast session was disconnected. */
  int SESSION_UNAVAILABLE_REASON_DISCONNECTED = 4;

  /** Called when a cast session becomes available to the player. */
  void onCastSessionAvailable();

  /**
   * Called when the cast session becomes unavailable. This only gets called if
   * onCastSessionUnavailable(@CastPlayer.CastSessionChangeReason int reason) is not implemented
   */
  void onCastSessionUnavailable();

  /**
   * Called when the cast session becomes unavailable with the specified reason. The reason can
   * affect the player state transfer. For example, a "Stop Casting" button press or a disconnection
   * typically require the playback to pause.
   */
  default void onCastSessionUnavailable(@SessionUnavailableReason int reason) {
    onCastSessionUnavailable();
  }
}
