/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.media3.test.utils

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util.getAvailableCommands
import androidx.media3.common.util.Util.msToUs
import com.google.common.base.Preconditions.checkState
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * A fake [Player] that uses [SimpleBasePlayer]'s minimal number of default methods implementations
 * to build upon to simulate realistic playback scenarios for testing.
 *
 * @param playbackState The initial playback state of the player.
 * @param playWhenReady Whether playback should start automatically when ready.
 * @param playlist The initial list of media items to play. Default is 2 items (1s and 2s duration).
 * @param playbackSpeed The initial playback speed.
 * @hide
 */
@UnstableApi
open class TestSimpleBasePlayer(
  playbackState: @Player.State Int = STATE_READY,
  playWhenReady: Boolean = false,
  playlist: List<MediaItemData> =
    listOf(
      MediaItemData.Builder(/* uid= */ "First")
        .setDurationUs(1_000_000L)
        .setIsSeekable(true)
        .build(),
      MediaItemData.Builder(/* uid= */ "Second")
        .setDurationUs(2_000_000L)
        .setIsSeekable(true)
        .build(),
    ),
  playbackSpeed: Float = 1f,
) : SimpleBasePlayer(Looper.myLooper()!!) {
  private var state =
    State.Builder()
      .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
      .setPlaybackState(playbackState)
      .setPlayWhenReady(playWhenReady, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
      .setPlaylist(playlist)
      .setPlaybackParameters(PlaybackParameters(playbackSpeed))
      .build()

  /** Current video output */
  var videoOutput: Any? = null
    private set

  init {
    updateAvailableSeekCommands()
  }

  /**
   * Allow subclasses to have more control over the state to make use of this player's
   * implementation of handle* methods, but add customization
   *
   * @param block The block to be applied to the local [state].
   */
  protected fun updateState(block: State.Builder.() -> Unit) {
    state = state.buildUpon().apply(block).build()
    invalidateState() // propagate the change from `block` to SimpleBasePlayer.state
    updateAvailableSeekCommands() // operates on Player (SimpleBasePlayer.state), not local state
  }

  override fun getState(): State = state

  override fun handleSetPlayWhenReady(playWhenReady: Boolean) = handleStateUpdate {
    setPlayWhenReady(playWhenReady, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
  }

  override fun handlePrepare() = handleStateUpdate {
    setPlayerError(null)
      .setPlaybackState(if (state.timeline.isEmpty) STATE_ENDED else STATE_BUFFERING)
  }

  override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: @Player.Command Int) =
    handleStateUpdate {
      setPlaybackState(STATE_BUFFERING)
        .setCurrentMediaItemIndex(mediaItemIndex)
        .setContentPositionMs(positionMs)
    }

  override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean) = handleStateUpdate {
    setShuffleModeEnabled(shuffleModeEnabled)
  }

  override fun handleSetRepeatMode(repeatMode: Int) = handleStateUpdate {
    setRepeatMode(repeatMode)
  }

  override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters) =
    handleStateUpdate {
      setPlaybackParameters(playbackParameters)
    }

  override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
    this.videoOutput = videoOutput
    return Futures.immediateVoidFuture()
  }

  override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
    if (videoOutput == null || videoOutput == this.videoOutput) {
      this.videoOutput = null
    }
    return Futures.immediateVoidFuture()
  }

  override fun handleSetVolume(volume: Float, volumeOperationType: @C.VolumeOperationType Int) =
    handleStateUpdate {
      setVolume(volume)
    }

  /**
   * Sets the {@linkplain Player.State state} of the player.
   *
   * If the playlist is empty, the state must be either [Player.STATE_IDLE] or [Player.STATE_ENDED].
   *
   * @param playbackState The [Player.State] of the player.
   */
  fun setPlaybackState(playbackState: @Player.State Int) {
    state = state.buildUpon().setPlaybackState(playbackState).build()
    invalidateState()
  }

  /**
   * Sets the current content playback position in milliseconds.
   *
   * @param positionMs The current content playback position in milliseconds, or [C.TIME_UNSET] to
   *   indicate the default start position.
   */
  fun setPosition(positionMs: Long) {
    state = state.buildUpon().setContentPositionMs(positionMs).build()
    invalidateState()
  }

  /**
   * Sets the [PositionSupplier] for the current content playback position using extrapolation.
   * Start and elapsed time are measured using the [TestCoroutineScheduler], and playback speed is
   * taken into account.
   *
   * Note, that this approach only works
   * * for a single media item (does not consider current item's duration and playlist length)
   * * assuming the Player is playing (does not react to pauses and discontinuities)
   * * assuming the playback speed does not change after the supplier is set
   */
  fun setPositionSupplierDrivenBy(testScheduler: TestCoroutineScheduler) {
    checkState(state.playlist.size == 1, "Playlist must contain exactly one item")
    checkState(isPlaying, "Player must be playing")
    val startTime = testScheduler.timeSource.markNow()
    val currentPositionMs = currentPosition
    val positionSupplier = {
      val elapsedTime = testScheduler.timeSource.markNow().minus(startTime)
      currentPositionMs + (elapsedTime * playbackParameters.speed.toDouble()).inWholeMilliseconds
    }
    state = state.buildUpon().setContentPositionMs(positionSupplier).build()
    invalidateState()
  }

  /**
   * Sets the buffered content playback position in milliseconds.
   *
   * @param bufferedPositionMs The buffered content playback position in milliseconds.
   */
  fun setBufferedPositionMs(bufferedPositionMs: Long) {
    state = state.buildUpon().setContentBufferedPositionMs { bufferedPositionMs }.build()
    invalidateState()
  }

  /**
   * Sets the duration of the media item in the playlist, in milliseconds.
   *
   * @param uid Unique id of the media item whose duration is being set.
   * @param durationMs The duration of the media item, in milliseconds, or {@link C#TIME_UNSET} if
   *   unknown.
   */
  fun setDuration(uid: String, durationMs: Long) {
    val index = state.playlist.indexOfFirst { it.uid == uid }
    if (index == -1) {
      throw IllegalArgumentException("Playlist does not contain item with uid: $uid")
    }
    val modifiedPlaylist = buildList {
      addAll(state.playlist)
      set(index, state.playlist[index].buildUpon().setDurationUs(msToUs(durationMs)).build())
    }
    state = state.buildUpon().setPlaylist(modifiedPlaylist).build()
    invalidateState()
  }

  /**
   * Sets the current video size.
   *
   * @param videoSize The current video size.
   */
  fun setVideoSize(videoSize: VideoSize) {
    state = state.buildUpon().setVideoSize(videoSize).build()
    invalidateState()
  }

  /**
   * Sets the [Player.seekBack] increment in milliseconds.
   *
   * @param seekBackIncrementMs The [Player.seekBack] increment in milliseconds.
   */
  fun setSeekBackIncrementMs(seekBackIncrementMs: Long) {
    state = state.buildUpon().setSeekBackIncrementMs(seekBackIncrementMs).build()
    invalidateState()
  }

  /**
   * Sets the [Player.seekForward] increment in milliseconds.
   *
   * @param seekForwardIncrementMs The [Player.seekForward] increment in milliseconds.
   */
  fun setSeekForwardIncrementMs(seekForwardIncrementMs: Long) {
    state = state.buildUpon().setSeekForwardIncrementMs(seekForwardIncrementMs).build()
    invalidateState()
  }

  /**
   * Sets whether a frame has been rendered for the first time since setting the surface, a
   * rendering reset, or since the stream being rendered was changed.
   */
  fun renderFirstFrame(newlyRenderedFirstFrame: Boolean) {
    state = state.buildUpon().setNewlyRenderedFirstFrame(newlyRenderedFirstFrame).build()
    invalidateState() // flushes EVENT_RENDERED_FIRST_FRAME
    state = state.buildUpon().setNewlyRenderedFirstFrame(false).build()
  }

  /** Remove commands from those already available to the player. */
  fun removeCommands(vararg commands: @Player.Command Int) {
    // It doesn't seem possible to propagate the @IntDef annotation through Kotlin's spread operator
    // in a way that lint understands.
    @SuppressWarnings("WrongConstant")
    state =
      state
        .buildUpon()
        .setAvailableCommands(state.availableCommands.buildUpon().removeAll(*commands).build())
        .build()
    invalidateState()
  }

  /** Add commands to those already available to the player. */
  fun addCommands(vararg commands: @Player.Command Int) {
    // It doesn't seem possible to propagate the @IntDef annotation through Kotlin's spread operator
    // in a way that lint understands.
    @SuppressWarnings("WrongConstant")
    state =
      state
        .buildUpon()
        .setAvailableCommands(state.availableCommands.buildUpon().addAll(*commands).build())
        .build()
    invalidateState()
  }

  private fun handleStateUpdate(stateUpdate: State.Builder.() -> Unit): ListenableFuture<*> {
    updateState(stateUpdate)
    return Futures.immediateVoidFuture()
  }

  /**
   * Potentially remove/add COMMAND_SEEK_* due to the nature of the current media or the location in
   * the playlist by delegating to `Util's getAvailableCommands`. Respect the existing non-seek
   * commands and don't rely on a set of always available commands to let the TestPlayer consumer
   * have more control when removing commands for good.
   */
  private fun updateAvailableSeekCommands() {
    state =
      state
        .buildUpon()
        .setAvailableCommands(
          state.availableCommands
            .buildUpon()
            .removeAll(*ALL_SEEK_COMMANDS)
            .addAll(
              getAvailableCommands(
                /* player= */ this,
                /* permanentAvailableCommands = */ Player.Commands.Builder().build(),
              )
            )
            .build()
        )
        .build()
  }
}

private val ALL_SEEK_COMMANDS =
  intArrayOf(
    Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
    Player.COMMAND_SEEK_TO_PREVIOUS,
    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
    Player.COMMAND_SEEK_TO_NEXT,
    Player.COMMAND_SEEK_TO_MEDIA_ITEM,
    Player.COMMAND_SEEK_BACK,
    Player.COMMAND_SEEK_FORWARD,
  )
