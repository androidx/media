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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import android.content.Context;
import androidx.annotation.IntRange;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.DeviceInfo;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.PlayerTransferState;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import com.google.android.gms.cast.framework.CastContext;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * {@link Player} implementation that can control playback both on the local device, and on a remote
 * Cast device.
 *
 * <p>See {@link RemoteCastPlayer} for a {@link Player} that only supports playback on Cast
 * receivers.
 *
 * <p>This class works by delegating playback to a dedicated player depending on Cast session
 * availability. When a Cast session becomes available or unavailable, the following steps take
 * place:
 *
 * <ul>
 *   <li>The new active player is a {@link RemoteCastPlayer} if a Cast session is active, or the
 *       {@link Builder#setLocalPlayer local player} otherwise.
 *   <li>A customizable {@link TransferCallback} receives both players to transfer state across
 *       players.
 *   <li>The inactive player is {@link Player#stop() stopped}.
 * </ul>
 */
@UnstableApi
public final class CastPlayer extends ForwardingPlayer {

  /**
   * Callback for moving state across players when transferring playback upon Cast session
   * availability changes.
   */
  public interface TransferCallback {

    TransferCallback DEFAULT =
        (sourcePlayer, targetPlayer) ->
            PlayerTransferState.fromPlayer(sourcePlayer).setToPlayer(targetPlayer);

    /**
     * Called immediately before changing the active {@link Player}, with the intended use of
     * transferring playback state.
     *
     * @param sourcePlayer The {@link Player} from which playback is transferring, from which to
     *     fetch the state.
     * @param targetPlayer The {@link Player} to which playback is transferring, to populate with
     *     state.
     * @see PlayerTransferState
     */
    void transferState(Player sourcePlayer, Player targetPlayer);
  }

  /** Builder for {@link CastPlayer}. */
  public static final class Builder {

    private final Context context;
    private TransferCallback transferCallback;
    private @MonotonicNonNull Player localPlayer;
    private @MonotonicNonNull RemoteCastPlayer remotePlayer;
    private boolean buildCalled;

    /**
     * Creates a builder.
     *
     * <p>The builder uses the following default values:
     *
     * <ul>
     *   <li>{@link TransferCallback}: {@link TransferCallback#DEFAULT}.
     *   <li>{@link RemoteCastPlayer}: {@link RemoteCastPlayer new
     *       RemoteCastPlayer.Builder(context).build()}.
     *   <li>{@link #setLocalPlayer}: {@link ExoPlayer new ExoPlayer.Builder(context).build()}.
     * </ul>
     *
     * @param context A {@link Context}.
     */
    public Builder(Context context) {
      this.context = checkNotNull(context);
      transferCallback = TransferCallback.DEFAULT;
    }

    /**
     * Sets the {@link TransferCallback} to call when the active player changes.
     *
     * @param transferCallback A {@link TransferCallback}.
     * @return This builder.
     * @throws IllegalStateException If {@link #build()} has already been called on this builder
     *     instance.
     */
    @CanIgnoreReturnValue
    public Builder setTransferCallback(TransferCallback transferCallback) {
      checkState(!buildCalled);
      this.transferCallback = checkNotNull(transferCallback);
      return this;
    }

    /**
     * Sets the {@link Player} to use for local playback.
     *
     * @param localPlayer A {@link Player}.
     * @return This builder.
     * @throws IllegalStateException If {@link #build()} has already been called on this builder
     *     instance.
     */
    @CanIgnoreReturnValue
    public Builder setLocalPlayer(Player localPlayer) {
      checkState(!buildCalled);
      this.localPlayer = checkNotNull(localPlayer);
      return this;
    }

    /**
     * Sets the {@link RemoteCastPlayer} to use for remote playback.
     *
     * @param remotePlayer A {@link RemoteCastPlayer}.
     * @return This builder.
     * @throws IllegalStateException If {@link #build()} has already been called on this builder
     *     instance.
     */
    @CanIgnoreReturnValue
    public Builder setRemotePlayer(RemoteCastPlayer remotePlayer) {
      checkState(!buildCalled);
      this.remotePlayer = checkNotNull(remotePlayer);
      return this;
    }

    /**
     * Creates and returns the new {@link CastPlayerImpl} instance.
     *
     * @throws IllegalStateException If this method has already been called on this instance.
     */
    public CastPlayer build() {
      checkState(!buildCalled);
      buildCalled = true;
      if (localPlayer == null) {
        localPlayer = new ExoPlayer.Builder(context).build();
      }
      if (remotePlayer == null) {
        remotePlayer = new RemoteCastPlayer.Builder(context).build();
      }
      Player initialActivePlayer =
          remotePlayer.isCastSessionAvailable() ? remotePlayer : localPlayer;
      CastPlayerImpl castPlayerImpl =
          new CastPlayerImpl(localPlayer, remotePlayer, initialActivePlayer, transferCallback);
      return new CastPlayer(castPlayerImpl, remotePlayer);
    }
  }

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
   * @deprecated Use {@link RemoteCastPlayer.Builder} to create a {@link Player} for playback
   *     exclusively on Cast receivers, or {@link Builder} for a {@link Player} that works both on
   *     Cast receivers and locally.
   */
  @Deprecated
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
   * @deprecated Use {@link RemoteCastPlayer.Builder} to create a {@link Player} for playback
   *     exclusively on Cast receivers, or {@link Builder} for a {@link Player} that works both on
   *     Cast receivers and locally.
   */
  @Deprecated
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
   * @deprecated Use {@link RemoteCastPlayer.Builder} to create a {@link Player} for playback
   *     exclusively on Cast receivers, or {@link Builder} for a {@link Player} that works both on
   *     Cast receivers and locally.
   */
  @Deprecated
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
   * @deprecated Use {@link RemoteCastPlayer.Builder} to create a {@link Player} for playback
   *     exclusively on Cast receivers, or {@link Builder} for a {@link Player} that works both on
   *     Cast receivers and locally.
   */
  @Deprecated
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

  private CastPlayer(CastPlayerImpl castPlayerImpl, RemoteCastPlayer remoteCastPlayer) {
    super(castPlayerImpl);
    this.remoteCastPlayer = remoteCastPlayer;
  }

  /**
   * Returns whether a cast session is available.
   *
   * @deprecated Use {@link #getDeviceInfo()} instead, and check for {@link
   *     DeviceInfo#PLAYBACK_TYPE_REMOTE}.
   */
  @Deprecated
  public boolean isCastSessionAvailable() {
    return remoteCastPlayer.isCastSessionAvailable();
  }

  /**
   * Sets a listener for updates on the cast session availability.
   *
   * @param listener The {@link SessionAvailabilityListener}, or null to clear the listener.
   * @deprecated Use {@link androidx.media3.common.Player.Listener#onDeviceInfoChanged} instead, and
   *     check for {@link DeviceInfo#PLAYBACK_TYPE_REMOTE}.
   */
  @Deprecated
  public void setSessionAvailabilityListener(@Nullable SessionAvailabilityListener listener) {
    remoteCastPlayer.setSessionAvailabilityListener(listener);
  }
}
