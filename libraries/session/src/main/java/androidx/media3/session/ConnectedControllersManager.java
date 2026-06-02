/*
 * Copyright 2019 The Android Open Source Project
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
package androidx.media3.session;

import static androidx.media3.common.util.Util.postOrRun;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.collection.ArrayMap;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.Tracks;
import androidx.media3.session.MediaSession.ControllerInfo;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicReference;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Manager that holds {@link ControllerInfo} of connected {@link MediaController controllers}.
 *
 * <p>The generic {@code T} denotes a key of connected {@link MediaController controllers}, and it
 * can be either {@link android.os.IBinder} or {@link
 * androidx.media3.session.legacy.MediaSessionManager.RemoteUserInfo}.
 *
 * <p>All methods must be called on the application thread associated with the player.
 */
/* package */ final class ConnectedControllersManager<T extends @NonNull Object> {

  /** An asynchronous controller command function. */
  public interface AsyncCommand {

    /**
     * Runs the asynchronous command.
     *
     * @return A {@link ListenableFuture} to listen for the command completion.
     */
    ListenableFuture<Void> run();
  }

  private final ArrayMap<T, ControllerInfo> controllerInfoMap = new ArrayMap<>();

  private final ArrayMap<ControllerInfo, ConnectedControllerRecord<T>> controllerRecords =
      new ArrayMap<>();

  private final WeakReference<MediaSessionImpl> sessionImpl;

  public ConnectedControllersManager(MediaSessionImpl session) {
    // Initialize members with params.
    sessionImpl = new WeakReference<>(session);
  }

  public void addController(
      T controllerKey,
      ControllerInfo controllerInfo,
      SessionCommands sessionCommands,
      Player.Commands playerCommands) {
    verifyApplicationThread();
    @Nullable ControllerInfo savedInfo = getController(controllerKey);
    if (savedInfo == null) {
      controllerInfoMap.put(controllerKey, controllerInfo);
      Timeline timeline = Timeline.EMPTY;
      Tracks tracks = Tracks.EMPTY;
      @Nullable MediaSessionImpl session = sessionImpl.get();
      if (session != null) {
        PlayerWrapper playerWrapper = session.getPlayerWrapper();
        timeline = playerWrapper.getCurrentTimelineWithCommandCheck();
        tracks = playerWrapper.getCurrentTracksWithCommandCheck();
      }
      ConnectedControllerRecord<T> record =
          new ConnectedControllerRecord<>(
              controllerKey,
              new SequencedFutureManager(),
              sessionCommands,
              playerCommands,
              timeline,
              tracks);
      controllerRecords.put(controllerInfo, record);
    } else {
      // already exist. Only update allowed commands.
      ConnectedControllerRecord<T> record = checkNotNull(controllerRecords.get(savedInfo));
      record.sessionCommands = sessionCommands;
      record.playerCommands = playerCommands;
    }
  }

  public void updateCommandsFromSession(
      ControllerInfo controllerInfo,
      SessionCommands sessionCommands,
      Player.Commands playerCommands) {
    verifyApplicationThread();
    @Nullable ConnectedControllerRecord<T> record = controllerRecords.get(controllerInfo);
    if (record != null) {
      record.sessionCommands = sessionCommands;
      if (record.playerCommandsBeforePlaybackException != null) {
        record.playerCommandsBeforePlaybackException = playerCommands;
      } else {
        record.playerCommands = playerCommands;
      }
    }
  }

  @Nullable
  public Player.Commands getAvailablePlayerCommands(ControllerInfo controllerInfo) {
    verifyApplicationThread();
    @Nullable ConnectedControllerRecord<T> record = controllerRecords.get(controllerInfo);
    if (record != null) {
      return record.playerCommands;
    }
    return null;
  }

  @Nullable
  public SessionCommands getAvailableSessionCommands(ControllerInfo controllerInfo) {
    verifyApplicationThread();
    @Nullable ConnectedControllerRecord<T> record = controllerRecords.get(controllerInfo);
    if (record != null) {
      return record.sessionCommands;
    }
    return null;
  }

  public void setPlaybackException(
      ControllerInfo controllerInfo,
      PlaybackException playbackException,
      Player.Commands playerCommandsBeforePlaybackException) {
    verifyApplicationThread();
    @Nullable ConnectedControllerRecord<T> record = controllerRecords.get(controllerInfo);
    if (record != null) {
      record.playbackException = playbackException;
      record.playerCommandsBeforePlaybackException = playerCommandsBeforePlaybackException;
      record.playerInfoForPlaybackException = null;
    }
  }

  public void resetPlaybackException(ControllerInfo controllerInfo) {
    verifyApplicationThread();
    @Nullable ConnectedControllerRecord<T> record = controllerRecords.get(controllerInfo);
    if (record != null) {
      record.playbackException = null;
      record.playerCommandsBeforePlaybackException = null;
      record.playerInfoForPlaybackException = null;
    }
  }

  /**
   * Sets the player info for the playback exception.
   *
   * <p>To reset the player info to null call {@link #resetPlaybackException(ControllerInfo)}.
   *
   * @param controllerInfo The controller info .
   * @param playerInfo The player info.
   * @throws IllegalStateException if {@link ConnectedControllerRecord#playbackException} is null.
   */
  public void setPlayerInfoForPlaybackException(
      ControllerInfo controllerInfo, PlayerInfo playerInfo) {
    verifyApplicationThread();
    @Nullable ConnectedControllerRecord<T> record = controllerRecords.get(controllerInfo);
    if (record != null) {
      checkNotNull(record.playbackException);
      record.playerInfoForPlaybackException = playerInfo;
    }
  }

  @Nullable
  public PlaybackException getPlaybackException(ControllerInfo controllerInfo) {
    verifyApplicationThread();
    @Nullable ConnectedControllerRecord<T> record = controllerRecords.get(controllerInfo);
    if (record != null) {
      return record.playbackException;
    }
    return null;
  }

  @Nullable
  public PlayerInfo getPlayerInfoForPlaybackException(ControllerInfo controllerInfo) {
    verifyApplicationThread();
    @Nullable ConnectedControllerRecord<T> record = controllerRecords.get(controllerInfo);
    if (record != null) {
      return record.playerInfoForPlaybackException;
    }
    return null;
  }

  @Nullable
  public Player.Commands getPlayerCommandsBeforePlaybackException(ControllerInfo controllerInfo) {
    verifyApplicationThread();
    @Nullable ConnectedControllerRecord<T> record = controllerRecords.get(controllerInfo);
    if (record != null) {
      return record.playerCommandsBeforePlaybackException;
    }
    return null;
  }

  public void removeController(T controllerKey) {
    verifyApplicationThread();
    @Nullable ControllerInfo controllerInfo = getController(controllerKey);
    if (controllerInfo != null) {
      removeController(controllerInfo);
    }
  }

  public void removeController(ControllerInfo controllerInfo) {
    verifyApplicationThread();
    @Nullable ConnectedControllerRecord<T> record = controllerRecords.remove(controllerInfo);
    if (record == null) {
      return;
    }
    controllerInfoMap.remove(record.controllerKey);

    record.sequencedFutureManager.release();
    @Nullable MediaSessionImpl sessionImpl = this.sessionImpl.get();
    if (sessionImpl == null || sessionImpl.isReleased()) {
      return;
    }
    postOrRun(
        sessionImpl.getApplicationHandler(),
        () -> {
          if (sessionImpl.isReleased()) {
            return;
          }
          sessionImpl.onDisconnectedOnHandler(controllerInfo);
        });
  }

  public void release() {
    sessionImpl.clear();
  }

  public ImmutableList<ControllerInfo> getConnectedControllers() {
    verifyApplicationThread();
    return ImmutableList.copyOf(controllerInfoMap.values());
  }

  public boolean isConnected(ControllerInfo controllerInfo) {
    verifyApplicationThread();
    return controllerRecords.get(controllerInfo) != null;
  }

  /**
   * Gets the sequenced future manager.
   *
   * @param controllerInfo controller info
   * @return sequenced future manager. Can be {@code null} if the controller was null or
   *     disconnected.
   */
  @Nullable
  public SequencedFutureManager getSequencedFutureManager(ControllerInfo controllerInfo) {
    verifyApplicationThread();
    @Nullable ConnectedControllerRecord<T> info = controllerRecords.get(controllerInfo);
    return info != null ? info.sequencedFutureManager : null;
  }

  /**
   * Gets the sequenced future manager.
   *
   * @param controllerKey key
   * @return sequenced future manager. Can be {@code null} if the controller was null or
   *     disconnected.
   */
  @Nullable
  public SequencedFutureManager getSequencedFutureManager(T controllerKey) {
    verifyApplicationThread();
    @Nullable ControllerInfo controllerInfo = getController(controllerKey);
    @Nullable
    ConnectedControllerRecord<T> info =
        controllerInfo != null ? controllerRecords.get(controllerInfo) : null;
    return info != null ? info.sequencedFutureManager : null;
  }

  public boolean isSessionCommandAvailable(ControllerInfo controllerInfo, SessionCommand command) {
    verifyApplicationThread();
    @Nullable ConnectedControllerRecord<T> info = controllerRecords.get(controllerInfo);
    return info != null
        && (info.sessionCommands.contains(command)
            || CommandButton.isPredefinedCustomCommandButtonCode(command.customAction));
  }

  public boolean isSessionCommandAvailable(
      ControllerInfo controllerInfo, @SessionCommand.CommandCode int commandCode) {
    verifyApplicationThread();
    @Nullable ConnectedControllerRecord<T> info = controllerRecords.get(controllerInfo);
    return info != null && info.sessionCommands.contains(commandCode);
  }

  public boolean isPlayerCommandAvailable(
      ControllerInfo controllerInfo, @Player.Command int commandCode) {
    verifyApplicationThread();
    @Nullable ConnectedControllerRecord<T> info = controllerRecords.get(controllerInfo);
    @Nullable MediaSessionImpl sessionImpl = this.sessionImpl.get();
    return info != null
        && info.playerCommands.contains(commandCode)
        && sessionImpl != null
        && sessionImpl.getPlayerWrapper().getAvailableCommands().contains(commandCode);
  }

  @Nullable
  public ControllerInfo getController(T controllerKey) {
    verifyApplicationThread();
    return controllerInfoMap.get(controllerKey);
  }

  public Timeline getLastSentTimeline(ControllerInfo controllerInfo) {
    verifyApplicationThread();
    ConnectedControllerRecord<T> record = checkNotNull(controllerRecords.get(controllerInfo));
    return record.lastSentTimeline;
  }

  public Tracks getLastSentTracks(ControllerInfo controllerInfo) {
    verifyApplicationThread();
    ConnectedControllerRecord<T> record = checkNotNull(controllerRecords.get(controllerInfo));
    return record.lastSentTracks;
  }

  public void updateLastSentTimelineAndTracks(
      ControllerInfo controllerInfo, Timeline timeline, Tracks tracks) {
    verifyApplicationThread();
    ConnectedControllerRecord<T> record = checkNotNull(controllerRecords.get(controllerInfo));
    record.lastSentTimeline = timeline;
    record.lastSentTracks = tracks;
  }

  public void addToCommandQueue(
      ControllerInfo controllerInfo, @Player.Command int command, AsyncCommand asyncCommand) {
    verifyApplicationThread();
    @Nullable ConnectedControllerRecord<T> info = controllerRecords.get(controllerInfo);
    if (info != null) {
      info.commandQueuePlayerCommands =
          info.commandQueuePlayerCommands.buildUpon().add(command).build();
      info.commandQueue.add(asyncCommand);
    }
  }

  public void flushCommandQueue(ControllerInfo controllerInfo) {
    verifyApplicationThread();
    @Nullable ConnectedControllerRecord<T> info = controllerRecords.get(controllerInfo);
    if (info == null) {
      return;
    }
    Player.Commands commandQueuePlayerCommands = info.commandQueuePlayerCommands;
    info.commandQueuePlayerCommands = Player.Commands.EMPTY;
    info.commandQueue.add(
        () -> {
          @Nullable MediaSessionImpl sessionImpl = this.sessionImpl.get();
          if (sessionImpl != null) {
            sessionImpl.onPlayerInteractionFinishedOnHandler(
                controllerInfo, commandQueuePlayerCommands);
          }
          return Futures.immediateVoidFuture();
        });
    if (info.commandQueueIsFlushing) {
      return;
    }
    info.commandQueueIsFlushing = true;
    flushCommandQueue(info);
  }

  private void flushCommandQueue(ConnectedControllerRecord<T> info) {
    @Nullable MediaSessionImpl sessionImpl = this.sessionImpl.get();
    if (sessionImpl == null) {
      return;
    }
    verifyApplicationThread();
    while (true) {
      @Nullable AsyncCommand asyncCommand = info.commandQueue.poll();
      if (asyncCommand == null) {
        info.commandQueueIsFlushing = false;
        return;
      }
      AtomicReference<ListenableFuture<Void>> futureHolder = new AtomicReference<>();
      sessionImpl
          .callWithControllerForCurrentRequestSet(
              getController(info.controllerKey), () -> futureHolder.set(asyncCommand.run()))
          .run();
      ListenableFuture<Void> future = futureHolder.get();
      if (!future.isDone()) {
        future.addListener(
            () -> postOrRun(sessionImpl.getApplicationHandler(), () -> flushCommandQueue(info)),
            directExecutor());
        return;
      }
    }
  }

  private static final class ConnectedControllerRecord<T> {

    private final T controllerKey;
    private final SequencedFutureManager sequencedFutureManager;
    private final Deque<AsyncCommand> commandQueue;

    private SessionCommands sessionCommands;
    private Player.Commands playerCommands;
    @Nullable private Player.Commands playerCommandsBeforePlaybackException;
    private boolean commandQueueIsFlushing;
    private Player.Commands commandQueuePlayerCommands;
    @Nullable private PlaybackException playbackException;
    @Nullable private PlayerInfo playerInfoForPlaybackException;
    private Timeline lastSentTimeline;
    private Tracks lastSentTracks;

    public ConnectedControllerRecord(
        T controllerKey,
        SequencedFutureManager sequencedFutureManager,
        SessionCommands sessionCommands,
        Player.Commands playerCommands,
        Timeline lastSentTimeline,
        Tracks lastSentTracks) {
      this.controllerKey = controllerKey;
      this.sequencedFutureManager = sequencedFutureManager;
      this.sessionCommands = sessionCommands;
      this.playerCommands = playerCommands;
      this.lastSentTimeline = lastSentTimeline;
      this.lastSentTracks = lastSentTracks;
      this.commandQueue = new ArrayDeque<>();
      this.commandQueuePlayerCommands = Player.Commands.EMPTY;
    }
  }

  private void verifyApplicationThread() {
    @Nullable MediaSessionImpl session = sessionImpl.get();
    if (session != null) {
      checkState(Looper.myLooper() == session.getApplicationHandler().getLooper());
    }
  }
}
