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

import androidx.media3.common.ForwardingSimpleBasePlayer;
import androidx.media3.common.Player;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

/**
 * Contains the implementation details of {@link CastPlayer}, when created using {@link
 * CastPlayer.Builder}.
 *
 * <p>When created using the constructors, {@link CastPlayer} behaves as a thin wrapper of {@link
 * RemoteCastPlayer}, for API backwards compatibility. When created using {@link
 * CastPlayer.Builder}, {@link CastPlayer} will wrap this class instead, making it support both
 * local and remote playback, unlike {@link RemoteCastPlayer}, which supports only remote playback.
 */
/* package */ final class CastPlayerImpl extends ForwardingSimpleBasePlayer {

  private final Player localPlayer;
  private final RemoteCastPlayer remotePlayer;
  private final CastPlayer.TransferCallback transferCallback;
  private final SessionAvailabilityListener sessionAvailabilityListener;

  public CastPlayerImpl(
      Player localPlayer,
      RemoteCastPlayer remotePlayer,
      Player initialActivePlayer,
      CastPlayer.TransferCallback transferCallback) {
    super(initialActivePlayer);
    this.localPlayer = localPlayer;
    this.remotePlayer = remotePlayer;
    this.transferCallback = transferCallback;
    sessionAvailabilityListener = new SessionAvailabilityListenerImpl();
    remotePlayer.setInternalSessionAvailabilityListener(sessionAvailabilityListener);
  }

  private void updateActivePlayer() {
    Player previousPlayer = getPlayer();
    Player newPlayer = remotePlayer.isCastSessionAvailable() ? remotePlayer : localPlayer;
    if (previousPlayer == newPlayer) {
      return;
    }
    transferCallback.transferState(previousPlayer, newPlayer);
    if (previousPlayer.getPlaybackState() != STATE_IDLE) {
      newPlayer.prepare();
    }
    previousPlayer.stop();
    setPlayer(newPlayer);
  }

  // SimpleBasePlayer implementation.

  @Override
  protected State getState() {
    State currentState = super.getState();
    Commands availableCommands = currentState.availableCommands;
    if (!availableCommands.contains(COMMAND_RELEASE)) {
      // This player implementation supports release regardless of the forwarded Player, but we only
      // recreate the state when necessary. So as to avoid unnecessarily recreating the state object
      // every time.
      Commands newCommands = availableCommands.buildUpon().add(COMMAND_RELEASE).build();
      currentState = currentState.buildUpon().setAvailableCommands(newCommands).build();
    }
    return currentState;
  }

  @Override
  protected ListenableFuture<?> handleRelease() {
    remotePlayer.release();
    remotePlayer.setInternalSessionAvailabilityListener(null);
    if (localPlayer.isCommandAvailable(COMMAND_RELEASE)) {
      localPlayer.release();
    }
    return Futures.immediateVoidFuture();
  }

  // Internal classes.

  private class SessionAvailabilityListenerImpl implements SessionAvailabilityListener {

    @Override
    public void onCastSessionAvailable() {
      updateActivePlayer();
    }

    @Override
    public void onCastSessionUnavailable() {
      updateActivePlayer();
    }
  }
}
