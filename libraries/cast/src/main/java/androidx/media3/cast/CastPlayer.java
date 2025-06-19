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
package androidx.media3.cast;

import android.content.Context;
import androidx.annotation.IntRange;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.DeviceInfo;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import com.google.android.gms.cast.framework.CastContext;

/**
 * {@link Player} implementation that communicates with a Cast receiver app.
 *
 * <p>This class is a passthrough wrapper of {@link RemoteCastPlayer}.
 */
// TODO: b/419816002 - Deprecate constructors in this class and update javadocs once
// RemoteCastPlayer has a Builder, and this class is able to support local playback.
@UnstableApi
public final class CastPlayer extends ForwardingPlayer {

  /** Same as {@link RemoteCastPlayer#DEVICE_INFO_REMOTE_EMPTY}. */
  public static final DeviceInfo DEVICE_INFO_REMOTE_EMPTY =
      RemoteCastPlayer.DEVICE_INFO_REMOTE_EMPTY;

  private final RemoteCastPlayer remoteCastPlayer;

  /**
   * Creates a new cast player.
   *
   * <p>The returned player uses a {@link DefaultMediaItemConverter} and
   *
   * <p>{@code mediaItemConverter} is set to a {@link DefaultMediaItemConverter}, {@code
   * seekBackIncrementMs} is set to {@link C#DEFAULT_SEEK_BACK_INCREMENT_MS} and {@code
   * seekForwardIncrementMs} is set to {@link C#DEFAULT_SEEK_FORWARD_INCREMENT_MS}.
   *
   * @param castContext The context from which the cast session is obtained.
   */
  public CastPlayer(CastContext castContext) {
    this(castContext, new DefaultMediaItemConverter());
  }

  /**
   * Creates a new cast player.
   *
   * <p>{@code seekBackIncrementMs} is set to {@link C#DEFAULT_SEEK_BACK_INCREMENT_MS} and {@code
   * seekForwardIncrementMs} is set to {@link C#DEFAULT_SEEK_FORWARD_INCREMENT_MS}.
   *
   * @param castContext The context from which the cast session is obtained.
   * @param mediaItemConverter The {@link MediaItemConverter} to use.
   */
  public CastPlayer(CastContext castContext, MediaItemConverter mediaItemConverter) {
    this(
        castContext,
        mediaItemConverter,
        C.DEFAULT_SEEK_BACK_INCREMENT_MS,
        C.DEFAULT_SEEK_FORWARD_INCREMENT_MS);
  }

  /**
   * Creates a new cast player.
   *
   * @param castContext The context from which the cast session is obtained.
   * @param mediaItemConverter The {@link MediaItemConverter} to use.
   * @param seekBackIncrementMs The {@link #seekBack()} increment, in milliseconds.
   * @param seekForwardIncrementMs The {@link #seekForward()} increment, in milliseconds.
   * @throws IllegalArgumentException If {@code seekBackIncrementMs} or {@code
   *     seekForwardIncrementMs} is non-positive.
   */
  public CastPlayer(
      CastContext castContext,
      MediaItemConverter mediaItemConverter,
      @IntRange(from = 1) long seekBackIncrementMs,
      @IntRange(from = 1) long seekForwardIncrementMs) {
    this(
        /* context= */ null,
        castContext,
        mediaItemConverter,
        seekBackIncrementMs,
        seekForwardIncrementMs,
        C.DEFAULT_MAX_SEEK_TO_PREVIOUS_POSITION_MS);
  }

  /**
   * Creates a new cast player.
   *
   * @param context A {@link Context} used to populate {@link #getDeviceInfo()}. If null, {@link
   *     #getDeviceInfo()} will always return {@link #DEVICE_INFO_REMOTE_EMPTY}.
   * @param castContext The context from which the cast session is obtained.
   * @param mediaItemConverter The {@link MediaItemConverter} to use.
   * @param seekBackIncrementMs The {@link #seekBack()} increment, in milliseconds.
   * @param seekForwardIncrementMs The {@link #seekForward()} increment, in milliseconds.
   * @param maxSeekToPreviousPositionMs The maximum position for which {@link #seekToPrevious()}
   *     seeks to the previous {@link MediaItem}, in milliseconds.
   * @throws IllegalArgumentException If {@code seekBackIncrementMs} or {@code
   *     seekForwardIncrementMs} is non-positive, or if {@code maxSeekToPreviousPositionMs} is
   *     negative.
   */
  public CastPlayer(
      @Nullable Context context,
      CastContext castContext,
      MediaItemConverter mediaItemConverter,
      @IntRange(from = 1) long seekBackIncrementMs,
      @IntRange(from = 1) long seekForwardIncrementMs,
      @IntRange(from = 0) long maxSeekToPreviousPositionMs) {
    this(
        new RemoteCastPlayer(
            context,
            castContext,
            mediaItemConverter,
            seekBackIncrementMs,
            seekForwardIncrementMs,
            maxSeekToPreviousPositionMs));
  }

  private CastPlayer(RemoteCastPlayer remoteCastPlayer) {
    super(remoteCastPlayer);
    this.remoteCastPlayer = remoteCastPlayer;
  }

  /** Returns whether a cast session is available. */
  public boolean isCastSessionAvailable() {
    return remoteCastPlayer.isCastSessionAvailable();
  }

  /**
   * Sets a listener for updates on the cast session availability.
   *
   * @param listener The {@link SessionAvailabilityListener}, or null to clear the listener.
   */
  public void setSessionAvailabilityListener(@Nullable SessionAvailabilityListener listener) {
    remoteCastPlayer.setSessionAvailabilityListener(listener);
  }
}
