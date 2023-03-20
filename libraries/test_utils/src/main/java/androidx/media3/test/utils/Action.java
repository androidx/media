/*
 * Copyright (C) 2016 The Android Open Source Project
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
package androidx.media3.test.utils;

import static androidx.media3.test.utils.TestUtil.timelinesAreSame;

import android.os.Looper;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.annotation.Size;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.IllegalSeekPositionException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.PlayerMessage;
import androidx.media3.exoplayer.PlayerMessage.Target;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ShuffleOrder;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector.Parameters;
import androidx.media3.test.utils.ActionSchedule.ActionNode;
import androidx.media3.test.utils.ActionSchedule.PlayerRunnable;
import androidx.media3.test.utils.ActionSchedule.PlayerTarget;
import java.util.Arrays;
import java.util.List;

/** Base class for actions to perform during playback tests. */
@UnstableApi
public abstract class Action {

  @Size(max = 23)
  private final String tag;

  @Nullable private final String description;

  /**
   * @param tag A tag to use for logging.
   * @param description A description to be logged when the action is executed, or null if no
   *     logging is required.
   */
  public Action(@Size(max = 23) String tag, @Nullable String description) {
    this.tag = tag;
    this.description = description;
  }

  /**
   * Called by {@link #doActionAndScheduleNextImpl(ExoPlayer, DefaultTrackSelector, Surface,
   * HandlerWrapper, ActionNode)} to perform the action.
   *
   * @param player The player to which the action should be applied.
   * @param trackSelector The track selector to which the action should be applied.
   * @param surface The surface to use when applying actions, or {@code null} if no surface is
   *     needed.
   */
  protected abstract void doActionImpl(
      ExoPlayer player, DefaultTrackSelector trackSelector, @Nullable Surface surface);

  /**
   * Executes the action and schedules the next.
   *
   * @param player The player to which the action should be applied.
   * @param trackSelector The track selector to which the action should be applied.
   * @param surface The surface to use when applying actions, or {@code null} if no surface is
   *     needed.
   * @param handler The handler to use to pass to the next action.
   * @param nextAction The next action to schedule immediately after this action finished, or {@code
   *     null} if there's no next action.
   */
  /* package */ final void doActionAndScheduleNext(
      ExoPlayer player,
      DefaultTrackSelector trackSelector,
      @Nullable Surface surface,
      HandlerWrapper handler,
      @Nullable ActionNode nextAction) {
    if (description != null) {
      Log.i(tag, description);
    }
    doActionAndScheduleNextImpl(player, trackSelector, surface, handler, nextAction);
  }

  /**
   * Called by {@link #doActionAndScheduleNext(ExoPlayer, DefaultTrackSelector, Surface,
   * HandlerWrapper, ActionNode)} to perform the action and to schedule the next action node.
   *
   * @param player The player to which the action should be applied.
   * @param trackSelector The track selector to which the action should be applied.
   * @param surface The surface to use when applying actions, or {@code null} if no surface is
   *     needed.
   * @param handler The handler to use to pass to the next action.
   * @param nextAction The next action to schedule immediately after this action finished, or {@code
   *     null} if there's no next action.
   */
  /* package */ void doActionAndScheduleNextImpl(
      ExoPlayer player,
      DefaultTrackSelector trackSelector,
      @Nullable Surface surface,
      HandlerWrapper handler,
      @Nullable ActionNode nextAction) {
    doActionImpl(player, trackSelector, surface);
    if (nextAction != null) {
      nextAction.schedule(player, trackSelector, surface, handler);
    }
  }

  /** Calls {@link Player#seekTo(long)} or {@link Player#seekTo(int, long)}. */
  public static final class Seek extends Action {

    @Nullable private final Integer mediaItemIndex;
    private final long positionMs;
    private final boolean catchIllegalSeekException;

    /**
     * Action calls {@link Player#seekTo(long)}.
     *
     * @param tag A tag to use for logging.
     * @param positionMs The seek position.
     */
    public Seek(@Size(max = 23) String tag, long positionMs) {
      super(tag, "Seek:" + positionMs);
      this.mediaItemIndex = null;
      this.positionMs = positionMs;
      catchIllegalSeekException = false;
    }

    /**
     * Action calls {@link Player#seekTo(int, long)}.
     *
     * @param tag A tag to use for logging.
     * @param mediaItemIndex The media item to seek to.
     * @param positionMs The seek position.
     * @param catchIllegalSeekException Whether {@link IllegalSeekPositionException} should be
     *     silently caught or not.
     */
    public Seek(
        String tag, int mediaItemIndex, long positionMs, boolean catchIllegalSeekException) {
      super(tag, "Seek:" + positionMs);
      this.mediaItemIndex = mediaItemIndex;
      this.positionMs = positionMs;
      this.catchIllegalSeekException = catchIllegalSeekException;
    }

    @Override
    protected void doActionImpl(
        ExoPlayer player, DefaultTrackSelector trackSelector, @Nullable Surface surface) {
      try {
        if (mediaItemIndex == null) {
          player.seekTo(positionMs);
        } else {
          player.seekTo(mediaItemIndex, positionMs);
        }
      } catch (IllegalSeekPositionException e) {
        if (!catchIllegalSeekException) {
          throw e;
        }
      }
    }
  }

  /** Calls {@link ExoPlayer#setMediaSources(List, int, long)}. */
  public static final class SetMediaItems extends Action {

    private final int mediaItemIndex;
    private final long positionMs;
    private final MediaSource[] mediaSources;

    /**
     * @param tag A tag to use for logging.
     * @param mediaItemIndex The media item index to start playback from.
     * @param positionMs The position in milliseconds to start playback from.
     * @param mediaSources The media sources to populate the playlist with.
     */
    public SetMediaItems(
        String tag, int mediaItemIndex, long positionMs, MediaSource... mediaSources) {
      super(tag, "SetMediaItems");
      this.mediaItemIndex = mediaItemIndex;
      this.positionMs = positionMs;
      this.mediaSources = mediaSources;
    }

    @Override
    protected void doActionImpl(
        ExoPlayer player, DefaultTrackSelector trackSelector, @Nullable Surface surface) {
      player.setMediaSources(Arrays.asList(mediaSources), mediaItemIndex, positionMs);
    }
  }

  /** Calls {@link ExoPlayer#addMediaSources(List)}. */
  public static final class AddMediaItems extends Action {

    private final MediaSource[] mediaSources;

    /**
     * @param tag A tag to use for logging.
     * @param mediaSources The media sources to be added to the playlist.
     */
    public AddMediaItems(@Size(max = 23) String tag, MediaSource... mediaSources) {
      super(tag, /* description= */ "AddMediaItems");
      this.mediaSources = mediaSources;
    }

    @Override
    protected void doActionImpl(
        ExoPlayer player, DefaultTrackSelector trackSelector, @Nullable Surface surface) {
      player.addMediaSources(Arrays.asList(mediaSources));
    }
  }

  /** Calls {@link ExoPlayer#setMediaSources(List, boolean)}. */
  public static final class SetMediaItemsResetPosition extends Action {

    private final boolean resetPosition;
    private final MediaSource[] mediaSources;

    /**
     * @param tag A tag to use for logging.
     * @param resetPosition Whether the position should be reset.
     * @param mediaSources The media sources to populate the playlist with.
     */
    public SetMediaItemsResetPosition(
        String tag, boolean resetPosition, MediaSource... mediaSources) {
      super(tag, "SetMediaItems");
      this.resetPosition = resetPosition;
      this.mediaSources = mediaSources;
    }

    @Override
    protected void doActionImpl(
        ExoPlayer player, DefaultTrackSelector trackSelector, @Nullable Surface surface) {
      player.setMediaSources(Arrays.asList(mediaSources), resetPosition);
    }
  }

  /** Calls {@link ExoPlayer#moveMediaItem(int, int)}. */
  public static class MoveMediaItem extends Action {

    private final int currentIndex;
    private final int newIndex;

    /**
     * @param tag A tag to use for logging.
     * @param currentIndex The current index of the media item.
     * @param newIndex The new index of the media item.
     */
    public MoveMediaItem(@Size(max = 23) String tag, int currentIndex, int newIndex) {
      super(tag, "MoveMediaItem");
      this.currentIndex = currentIndex;
      this.newIndex = newIndex;
    }

    @Override
    protected void doActionImpl(
        ExoPlayer player, DefaultTrackSelector trackSelector, @Nullable Surface surface) {
      player.moveMediaItem(currentIndex, newIndex);
    }
  }

  /** Calls {@link ExoPlayer#removeMediaItem(int)}. */
  public static class RemoveMediaItem extends Action {

    private final int index;

    /**
     * @param tag A tag to use for logging.
     * @param index The index of the item to remove.
     */
    public RemoveMediaItem(@Size(max = 23) String tag, int index) {
      super(tag, "RemoveMediaItem");
      this.index = index;
    }

    @Override
    protected void doActionImpl(
        ExoPlayer player, DefaultTrackSelector trackSelector, @Nullable Surface surface) {
      player.removeMediaItem(index);
    }
  }

  /** Calls {@link ExoPlayer#removeMediaItems(int, int)}. */
  public static class RemoveMediaItems extends Action {

    private final int fromIndex;
    private final int toIndex;

    /**
     * @param tag A tag to use for logging.
     * @param fromIndex The start if the range of media items to remove.
     * @param toIndex The end of the range of media items to remove (exclusive).
     */
    public RemoveMediaItems(@Size(max = 23) String tag, int fromIndex, int toIndex) {
      super(tag, "RemoveMediaItem");
      this.fromIndex = fromIndex;
      this.toIndex = toIndex;
    }

    @Override
    protected void doActionImpl(
        ExoPlayer player, DefaultTrackSelector trackSelector, @Nullable Surface surface) {
      player.removeMediaItems(fromIndex, toIndex);
    }
  }

  /** Calls {@link ExoPlayer#clearMediaItems()}}. */
  public static class ClearMediaItems extends Action {

    /**
     * @param tag A tag to use for logging.
     */
    public ClearMediaItems(String tag) {
      super(tag, "ClearMediaItems");
    }

    @Override
    protected void doActionImpl(
        ExoPlayer player, DefaultTrackSelector trackSelector, @Nullable Surface surface) {
      player.clearMediaItems();
    }
  }

  /** Calls {@link Player#stop()} or {@link Player#stop(boolean)}. */
  public static final class Stop extends Action {

    private static final String STOP_ACTION_TAG = "Stop";

    @Nullable private final Boolean reset;

    /**
     * Action will call {@link Player#stop()}.
     *
     * @param tag A tag to use for logging.
     */
    public Stop(String tag) {
      super(tag, STOP_ACTION_TAG);
      this.reset = null;
    }

    /**
     * Action will call {@link Player#stop(boolean)}.
     *
     * @param tag A tag to use for logging.
     * @param reset The value to pass to {@link Player#stop(boolean)}.
     */
    public Stop(@Size(max = 23) String tag, boolean reset) {
      super(tag, STOP_ACTION_TAG);
      this.reset = reset;
    }

    @Override
    protected void doActionImpl(
        ExoPlayer player, DefaultTrackSelector trackSelector, @Nullable Surface surface) {
      if (reset == null) {
        player.stop();
      } else {
        player.stop(reset);
      }
    }
  }

  /** Calls {@link Player#setPlayWhenReady(boolean)}. */
  public static final class SetPlayWhenReady extends Action {

    private final boolean playWhenReady;

    /**
     * @param tag A tag to use for logging.
     * @param playWhenReady The value to pass.
     */
    public SetPlayWhenReady(@Size(max = 23) String tag, boolean playWhenReady) {
      super(tag, playWhenReady ? "Play" : "Pause");
      this.playWhenReady = playWhenReady;
    }

    @Override
    protected void doActionImpl(
        ExoPlayer player, DefaultTrackSelector trackSelector, @Nullable Surface surface) {
      player.setPlayWhenReady(playWhenReady);
    }
  }

  /**
   * Updates the {@link Parameters} of a {@link DefaultTrackSelector} to specify whether the
   * renderer at a given index should be disabled.
   */
  public static final class SetRendererDisabled extends Action {

    private final int rendererIndex;
    private final boolean disabled;

    /**
     * @param tag A tag to use for logging.
     * @param rendererIndex The index of the renderer.
     * @param disabled Whether the renderer should be disabled.
     */
    public SetRendererDisabled(@Size(max = 23) String tag, int rendererIndex, boolean disabled) {
      super(tag, "SetRendererDisabled:" + rendererIndex + ":" + disabled);
      this.rendererIndex = rendererIndex;
      this.disabled = disabled;
    }

    @Override
    protected void doActionImpl(
        ExoPlayer player, DefaultTrackSelector trackSelector, @Nullable Surface surface) {
      trackSelector.setParameters(
          trackSelector.buildUponParameters().setRendererDisabled(rendererIndex, disabled));
    }
  }

  /** Calls {@link ExoPlayer#clearVideoSurface()}. */
  public static final class ClearVideoSurface extends Action {

    /**
     * @param tag A tag to use for logging.
     */
    public ClearVideoSurface(String tag) {
      super(tag, "ClearVideoSurface");
    }

    @Override
    protected void doActionImpl(
        ExoPlayer player, DefaultTrackSelector trackSelector, @Nullable Surface surface) {
      player.clearVideoSurface();
    }
  }

  /** Calls {@link ExoPlayer#setVideoSurface(Surface)}. */
  public static final class SetVideoSurface extends Action {

    /**
     * @param tag A tag to use for logging.
     */
    public SetVideoSurface(String tag) {
      super(tag, "SetVideoSurface");
    }

    @Override
    protected void doActionImpl(
        ExoPlayer player, DefaultTrackSelector trackSelector, @Nullable Surface surface) {
      player.setVideoSurface(Assertions.checkNotNull(surface));
    }
  }

  /** Calls {@link ExoPlayer#setAudioAttributes(AudioAttributes, boolean)}. */
  public static final class SetAudioAttributes extends Action {

    private final AudioAttributes audioAttributes;
    private final boolean handleAudioFocus;

    /**
     * @param tag A tag to use for logging.
     * @param audioAttributes The attributes to use for audio playback.
     * @param handleAudioFocus True if the player should handle audio focus, false otherwise.
     */
    public SetAudioAttributes(
        String tag, AudioAttributes audioAttributes, boolean handleAudioFocus) {
      super(tag, "SetAudioAttributes");
      this.audioAttributes = audioAttributes;
      this.handleAudioFocus = handleAudioFocus;
    }

    @Override
    protected void doActionImpl(
        ExoPlayer player, DefaultTrackSelector trackSelector, @Nullable Surface surface) {
      player.setAudioAttributes(audioAttributes, handleAudioFocus);
    }
  }

  /** Calls {@link ExoPlayer#prepare()}. */
  public static final class Prepare extends Action {
    /**
     * @param tag A tag to use for logging.
     */
    public Prepare(String tag) {
      super(tag, "Prepare");
    }

    @Override
    protected void doActionImpl(
        ExoPlayer player, DefaultTrackSelector trackSelector, @Nullable Surface surface) {
      player.prepare();
    }
  }

  /** Calls {@link Player#setRepeatMode(int)}. */
  public static final class SetRepeatMode extends Action {

    private final @Player.RepeatMode int repeatMode;

    /**
     * @param tag A tag to use for logging.
     * @param repeatMode The repeat mode.
     */
    public SetRepeatMode(@Size(max = 23) String tag, @Player.RepeatMode int repeatMode) {
      super(tag, "SetRepeatMode:" + repeatMode);
      this.repeatMode = repeatMode;
    }

    @Override
    protected void doActionImpl(
        ExoPlayer player, DefaultTrackSelector trackSelector, @Nullable Surface surface) {
      player.setRepeatMode(repeatMode);
    }
  }

  /** Calls {@link ExoPlayer#setShuffleOrder(ShuffleOrder)} . */
  public static final class SetShuffleOrder extends Action {

    private final ShuffleOrder shuffleOrder;

    /**
     * @param tag A tag to use for logging.
     * @param shuffleOrder The shuffle order.
     */
    public SetShuffleOrder(@Size(max = 23) String tag, ShuffleOrder shuffleOrder) {
      super(tag, "SetShufflerOrder");
      this.shuffleOrder = shuffleOrder;
    }

    @Override
    protected void doActionImpl(
        ExoPlayer player, DefaultTrackSelector trackSelector, @Nullable Surface surface) {
      player.setShuffleOrder(shuffleOrder);
    }
  }

  /** Calls {@link Player#setShuffleModeEnabled(boolean)}. */
  public static final class SetShuffleModeEnabled extends Action {

    private final boolean shuffleModeEnabled;

    /**
     * @param tag A tag to use for logging.
     * @param shuffleModeEnabled Whether shuffling is enabled.
     */
    public SetShuffleModeEnabled(@Size(max = 23) String tag, boolean shuffleModeEnabled) {
      super(tag, "SetShuffleModeEnabled:" + shuffleModeEnabled);
      this.shuffleModeEnabled = shuffleModeEnabled;
    }

    @Override
    protected void doActionImpl(
        ExoPlayer player, DefaultTrackSelector trackSelector, @Nullable Surface surface) {
      player.setShuffleModeEnabled(shuffleModeEnabled);
    }
  }

  /** Calls {@link ExoPlayer#createMessage(Target)} and {@link PlayerMessage#send()}. */
  public static final class SendMessages extends Action {

    private final Target target;
    private final int mediaItemIndex;
    private final long positionMs;
    private final boolean deleteAfterDelivery;

    /**
     * @param tag A tag to use for logging.
     * @param target A message target.
     * @param positionMs The position at which the message should be sent, in milliseconds.
     */
    public SendMessages(@Size(max = 23) String tag, Target target, long positionMs) {
      this(
          tag,
          target,
          /* mediaItemIndex= */ C.INDEX_UNSET,
          positionMs,
          /* deleteAfterDelivery= */ true);
    }

    /**
     * @param tag A tag to use for logging.
     * @param target A message target.
     * @param mediaItemIndex The media item index at which the message should be sent, or {@link
     *     C#INDEX_UNSET} for the current media item.
     * @param positionMs The position at which the message should be sent, in milliseconds.
     * @param deleteAfterDelivery Whether the message will be deleted after delivery.
     */
    public SendMessages(
        String tag,
        Target target,
        int mediaItemIndex,
        long positionMs,
        boolean deleteAfterDelivery) {
      super(tag, "SendMessages");
      this.target = target;
      this.mediaItemIndex = mediaItemIndex;
      this.positionMs = positionMs;
      this.deleteAfterDelivery = deleteAfterDelivery;
    }

    @Override
    protected void doActionImpl(
        final ExoPlayer player, DefaultTrackSelector trackSelector, @Nullable Surface surface) {
      if (target instanceof PlayerTarget) {
        ((PlayerTarget) target).setPlayer(player);
      }
      PlayerMessage message = player.createMessage(target);
      if (mediaItemIndex != C.INDEX_UNSET) {
        message.setPosition(mediaItemIndex, positionMs);
      } else {
        message.setPosition(positionMs);
      }
      message.setLooper(Util.getCurrentOrMainLooper());
      message.setDeleteAfterDelivery(deleteAfterDelivery);
      message.send();
    }
  }

  /** Calls {@link Player#setPlaybackParameters(PlaybackParameters)}. */
  public static final class SetPlaybackParameters extends Action {

    private final PlaybackParameters playbackParameters;

    /**
     * Creates a set playback parameters action instance.
     *
     * @param tag A tag to use for logging.
     * @param playbackParameters The playback parameters.
     */
    public SetPlaybackParameters(
        @Size(max = 23) String tag, PlaybackParameters playbackParameters) {
      super(tag, "SetPlaybackParameters:" + playbackParameters);
      this.playbackParameters = playbackParameters;
    }

    @Override
    protected void doActionImpl(
        ExoPlayer player, DefaultTrackSelector trackSelector, @Nullable Surface surface) {
      player.setPlaybackParameters(playbackParameters);
    }
  }

  /** Throws a playback exception on the playback thread. */
  public static final class ThrowPlaybackException extends Action {

    private final ExoPlaybackException exception;

    /**
     * @param tag A tag to use for logging.
     * @param exception The exception to throw.
     */
    public ThrowPlaybackException(@Size(max = 23) String tag, ExoPlaybackException exception) {
      super(tag, "ThrowPlaybackException:" + exception);
      this.exception = exception;
    }

    @Override
    protected void doActionImpl(
        ExoPlayer player, DefaultTrackSelector trackSelector, @Nullable Surface surface) {
      player
          .createMessage(
              (messageType, payload) -> {
                throw exception;
              })
          .send();
    }
  }

  /**
   * Schedules a play action to be executed, waits until the player reaches the specified position,
   * and pauses the player again.
   */
  public static final class PlayUntilPosition extends Action {

    private final int mediaItemIndex;
    private final long positionMs;

    /**
     * @param tag A tag to use for logging.
     * @param mediaItemIndex The media item index at which the player should be paused again.
     * @param positionMs The position in that media item at which the player should be paused again.
     */
    public PlayUntilPosition(@Size(max = 23) String tag, int mediaItemIndex, long positionMs) {
      super(tag, "PlayUntilPosition:" + mediaItemIndex + ":" + positionMs);
      this.mediaItemIndex = mediaItemIndex;
      this.positionMs = positionMs;
    }

    @Override
    protected void doActionImpl(
        ExoPlayer player, DefaultTrackSelector trackSelector, @Nullable Surface surface) {
      // Not triggered.
    }

    @Override
    /* package */ void doActionAndScheduleNextImpl(
        ExoPlayer player,
        DefaultTrackSelector trackSelector,
        @Nullable Surface surface,
        HandlerWrapper handler,
        @Nullable ActionNode nextAction) {
      // Schedule a message on the playback thread to ensure the player is paused immediately.
      Looper applicationLooper = Util.getCurrentOrMainLooper();
      player
          .createMessage(
              (messageType, payload) -> {
                // Block playback thread until pause command has been sent from test thread.
                ConditionVariable blockPlaybackThreadCondition = new ConditionVariable();
                player
                    .getClock()
                    .createHandler(applicationLooper, /* callback= */ null)
                    .post(
                        () -> {
                          player.pause();
                          blockPlaybackThreadCondition.open();
                        });
                try {
                  player.getClock().onThreadBlocked();
                  blockPlaybackThreadCondition.block();
                } catch (InterruptedException e) {
                  // Ignore.
                }
              })
          .setPosition(mediaItemIndex, positionMs)
          .send();
      if (nextAction != null) {
        // Schedule another message on this test thread to continue action schedule.
        player
            .createMessage(
                (messageType, payload) ->
                    nextAction.schedule(player, trackSelector, surface, handler))
            .setPosition(mediaItemIndex, positionMs)
            .setLooper(applicationLooper)
            .send();
      }
      player.play();
    }
  }

  /** Waits for {@link Player.Listener#onTimelineChanged(Timeline, int)}. */
  public static final class WaitForTimelineChanged extends Action {

    @Nullable private final Timeline expectedTimeline;
    private final boolean ignoreExpectedReason;
    private final @Player.TimelineChangeReason int expectedReason;

    /**
     * Creates action waiting for a timeline change for a given reason.
     *
     * @param tag A tag to use for logging.
     * @param expectedTimeline The expected timeline or {@code null} if any timeline change is
     *     relevant.
     * @param expectedReason The expected timeline change reason.
     */
    public WaitForTimelineChanged(
        String tag,
        @Nullable Timeline expectedTimeline,
        @Player.TimelineChangeReason int expectedReason) {
      super(tag, "WaitForTimelineChanged");
      this.expectedTimeline = expectedTimeline;
      this.ignoreExpectedReason = false;
      this.expectedReason = expectedReason;
    }

    /**
     * Creates action waiting for any timeline change for any reason.
     *
     * @param tag A tag to use for logging.
     */
    public WaitForTimelineChanged(String tag) {
      super(tag, "WaitForTimelineChanged");
      this.expectedTimeline = null;
      this.ignoreExpectedReason = true;
      this.expectedReason = Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED;
    }

    @Override
    protected void doActionImpl(
        ExoPlayer player, DefaultTrackSelector trackSelector, @Nullable Surface surface) {
      // Not triggered.
    }

    @Override
    /* package */ void doActionAndScheduleNextImpl(
        ExoPlayer player,
        DefaultTrackSelector trackSelector,
        @Nullable Surface surface,
        HandlerWrapper handler,
        @Nullable ActionNode nextAction) {
      if (nextAction == null) {
        return;
      }
      Player.Listener listener =
          new Player.Listener() {
            @Override
            public void onTimelineChanged(
                Timeline timeline, @Player.TimelineChangeReason int reason) {
              if ((expectedTimeline == null || timelinesAreSame(timeline, expectedTimeline))
                  && (ignoreExpectedReason || expectedReason == reason)) {
                player.removeListener(this);
                nextAction.schedule(player, trackSelector, surface, handler);
              }
            }
          };
      player.addListener(listener);
      if (expectedTimeline != null
          && timelinesAreSame(player.getCurrentTimeline(), expectedTimeline)) {
        player.removeListener(listener);
        nextAction.schedule(player, trackSelector, surface, handler);
      }
    }
  }

  /**
   * Waits for {@link Player.Listener#onPositionDiscontinuity(Player.PositionInfo,
   * Player.PositionInfo, int)}.
   */
  public static final class WaitForPositionDiscontinuity extends Action {

    /**
     * @param tag A tag to use for logging.
     */
    public WaitForPositionDiscontinuity(String tag) {
      super(tag, "WaitForPositionDiscontinuity");
    }

    @Override
    protected void doActionImpl(
        ExoPlayer player, DefaultTrackSelector trackSelector, @Nullable Surface surface) {
      // Not triggered.
    }

    @Override
    /* package */ void doActionAndScheduleNextImpl(
        ExoPlayer player,
        DefaultTrackSelector trackSelector,
        @Nullable Surface surface,
        HandlerWrapper handler,
        @Nullable ActionNode nextAction) {
      if (nextAction == null) {
        return;
      }
      player.addListener(
          new Player.Listener() {
            @Override
            public void onPositionDiscontinuity(
                Player.PositionInfo oldPosition,
                Player.PositionInfo newPosition,
                @Player.DiscontinuityReason int reason) {
              player.removeListener(this);
              nextAction.schedule(player, trackSelector, surface, handler);
            }
          });
    }
  }

  /**
   * Waits for a specified playWhenReady value, returning either immediately or after a call to
   * {@link Player.Listener#onPlayWhenReadyChanged(boolean, int)}.
   */
  public static final class WaitForPlayWhenReady extends Action {

    private final boolean targetPlayWhenReady;

    /**
     * @param tag A tag to use for logging.
     * @param playWhenReady The playWhenReady value to wait for.
     */
    public WaitForPlayWhenReady(@Size(max = 23) String tag, boolean playWhenReady) {
      super(tag, "WaitForPlayWhenReady");
      targetPlayWhenReady = playWhenReady;
    }

    @Override
    protected void doActionImpl(
        ExoPlayer player, DefaultTrackSelector trackSelector, @Nullable Surface surface) {
      // Not triggered.
    }

    @Override
    /* package */ void doActionAndScheduleNextImpl(
        ExoPlayer player,
        DefaultTrackSelector trackSelector,
        @Nullable Surface surface,
        HandlerWrapper handler,
        @Nullable ActionNode nextAction) {
      if (nextAction == null) {
        return;
      }
      if (targetPlayWhenReady == player.getPlayWhenReady()) {
        nextAction.schedule(player, trackSelector, surface, handler);
      } else {
        player.addListener(
            new Player.Listener() {
              @Override
              public void onPlayWhenReadyChanged(
                  boolean playWhenReady, @Player.PlayWhenReadyChangeReason int reason) {
                if (targetPlayWhenReady == playWhenReady) {
                  player.removeListener(this);
                  nextAction.schedule(player, trackSelector, surface, handler);
                }
              }
            });
      }
    }
  }

  /**
   * Waits for a specified playback state, returning either immediately or after a call to {@link
   * Player.Listener#onPlaybackStateChanged(int)}.
   */
  public static final class WaitForPlaybackState extends Action {

    private final @Player.State int targetPlaybackState;

    /**
     * @param tag A tag to use for logging.
     * @param targetPlaybackState The playback state to wait for.
     */
    public WaitForPlaybackState(@Size(max = 23) String tag, @Player.State int targetPlaybackState) {
      super(tag, "WaitForPlaybackState");
      this.targetPlaybackState = targetPlaybackState;
    }

    @Override
    protected void doActionImpl(
        ExoPlayer player, DefaultTrackSelector trackSelector, @Nullable Surface surface) {
      // Not triggered.
    }

    @Override
    /* package */ void doActionAndScheduleNextImpl(
        ExoPlayer player,
        DefaultTrackSelector trackSelector,
        @Nullable Surface surface,
        HandlerWrapper handler,
        @Nullable ActionNode nextAction) {
      if (nextAction == null) {
        return;
      }
      if (targetPlaybackState == player.getPlaybackState()) {
        nextAction.schedule(player, trackSelector, surface, handler);
      } else {
        player.addListener(
            new Player.Listener() {
              @Override
              public void onPlaybackStateChanged(@Player.State int playbackState) {
                if (targetPlaybackState == playbackState) {
                  player.removeListener(this);
                  nextAction.schedule(player, trackSelector, surface, handler);
                }
              }
            });
      }
    }
  }

  /**
   * Waits for a player message to arrive. If the target already received a message, the action
   * returns immediately.
   */
  public static final class WaitForMessage extends Action {

    private final PlayerTarget playerTarget;

    /**
     * @param tag A tag to use for logging.
     * @param playerTarget The target to observe.
     */
    public WaitForMessage(@Size(max = 23) String tag, PlayerTarget playerTarget) {
      super(tag, "WaitForMessage");
      this.playerTarget = playerTarget;
    }

    @Override
    protected void doActionImpl(
        ExoPlayer player, DefaultTrackSelector trackSelector, @Nullable Surface surface) {
      // Not triggered.
    }

    @Override
    /* package */ void doActionAndScheduleNextImpl(
        ExoPlayer player,
        DefaultTrackSelector trackSelector,
        @Nullable Surface surface,
        HandlerWrapper handler,
        @Nullable ActionNode nextAction) {
      if (nextAction == null) {
        return;
      }
      PlayerTarget.Callback callback =
          () -> nextAction.schedule(player, trackSelector, surface, handler);

      playerTarget.setCallback(callback);
    }
  }

  /**
   * Waits for a specified loading state, returning either immediately or after a call to {@link
   * Player.Listener#onIsLoadingChanged(boolean)}.
   */
  public static final class WaitForIsLoading extends Action {

    private final boolean targetIsLoading;

    /**
     * @param tag A tag to use for logging.
     * @param targetIsLoading The loading state to wait for.
     */
    public WaitForIsLoading(@Size(max = 23) String tag, boolean targetIsLoading) {
      super(tag, "WaitForIsLoading");
      this.targetIsLoading = targetIsLoading;
    }

    @Override
    protected void doActionImpl(
        ExoPlayer player, DefaultTrackSelector trackSelector, @Nullable Surface surface) {
      // Not triggered.
    }

    @Override
    /* package */ void doActionAndScheduleNextImpl(
        ExoPlayer player,
        DefaultTrackSelector trackSelector,
        @Nullable Surface surface,
        HandlerWrapper handler,
        @Nullable ActionNode nextAction) {
      if (nextAction == null) {
        return;
      }
      if (targetIsLoading == player.isLoading()) {
        nextAction.schedule(player, trackSelector, surface, handler);
      } else {
        player.addListener(
            new Player.Listener() {
              @Override
              public void onIsLoadingChanged(boolean isLoading) {
                if (targetIsLoading == isLoading) {
                  player.removeListener(this);
                  nextAction.schedule(player, trackSelector, surface, handler);
                }
              }
            });
      }
    }
  }

  /** Waits until the player acknowledged all pending player commands. */
  public static final class WaitForPendingPlayerCommands extends Action {

    /**
     * @param tag A tag to use for logging.
     */
    public WaitForPendingPlayerCommands(String tag) {
      super(tag, "WaitForPendingPlayerCommands");
    }

    @Override
    protected void doActionImpl(
        ExoPlayer player, DefaultTrackSelector trackSelector, @Nullable Surface surface) {
      // Not triggered.
    }

    @Override
    /* package */ void doActionAndScheduleNextImpl(
        ExoPlayer player,
        DefaultTrackSelector trackSelector,
        @Nullable Surface surface,
        HandlerWrapper handler,
        @Nullable ActionNode nextAction) {
      if (nextAction == null) {
        return;
      }
      // Send message to player that will arrive after all other pending commands. Thus, the message
      // execution on the app thread will also happen after all other pending command
      // acknowledgements have arrived back on the app thread.
      player
          .createMessage(
              (type, data) -> nextAction.schedule(player, trackSelector, surface, handler))
          .setLooper(Util.getCurrentOrMainLooper())
          .send();
    }
  }

  /** Calls {@code Runnable.run()}. */
  public static final class ExecuteRunnable extends Action {

    private final Runnable runnable;

    /**
     * Constructs an instance.
     *
     * @param tag A tag to use for logging.
     * @param runnable The runnable to run.
     */
    public ExecuteRunnable(@Size(max = 23) String tag, Runnable runnable) {
      super(tag, "ExecuteRunnable");
      this.runnable = runnable;
    }

    @Override
    protected void doActionImpl(
        ExoPlayer player, DefaultTrackSelector trackSelector, @Nullable Surface surface) {
      if (runnable instanceof PlayerRunnable) {
        ((PlayerRunnable) runnable).setPlayer(player);
      }
      runnable.run();
    }
  }
}
