/*
 * Copyright 2024 The Android Open Source Project
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

package androidx.media3.ui.compose.utils

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.VideoSize
import androidx.media3.common.util.Assertions.checkState
import androidx.media3.common.util.Util.msToUs
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * A fake [Player] that uses [SimpleBasePlayer]'s minimal number of default methods implementations
 * to build upon to simulate realistic playback scenarios for testing.
 */
internal class TestPlayer(
  playbackState: @Player.State Int = STATE_READY,
  playWhenReady: Boolean = false,
  playlist: List<MediaItemData> =
    listOf(
      MediaItemData.Builder(/* uid= */ "First").setDurationUs(1_000_000L).build(),
      MediaItemData.Builder(/* uid= */ "Second").setDurationUs(2_000_000L).build(),
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

  var videoOutput: Any? = null
    private set

  override fun getState(): State {
    return state
  }

  override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
    state =
      state
        .buildUpon()
        .setPlayWhenReady(playWhenReady, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        .build()
    return Futures.immediateVoidFuture()
  }

  override fun handlePrepare(): ListenableFuture<*> {
    state =
      state
        .buildUpon()
        .setPlayerError(null)
        .setPlaybackState(if (state.timeline.isEmpty) STATE_ENDED else STATE_BUFFERING)
        .build()
    return Futures.immediateVoidFuture()
  }

  override fun handleSeek(
    mediaItemIndex: Int,
    positionMs: Long,
    seekCommand: @Player.Command Int,
  ): ListenableFuture<*> {
    state =
      state
        .buildUpon()
        .setPlaybackState(STATE_BUFFERING)
        .setCurrentMediaItemIndex(mediaItemIndex)
        .setContentPositionMs(positionMs)
        .build()
    if (mediaItemIndex == state.playlist.size - 1) {
      removeCommands(COMMAND_SEEK_TO_NEXT)
    }
    return Futures.immediateVoidFuture()
  }

  override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
    state = state.buildUpon().setShuffleModeEnabled(shuffleModeEnabled).build()
    return Futures.immediateVoidFuture()
  }

  override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
    state = state.buildUpon().setRepeatMode(repeatMode).build()
    return Futures.immediateVoidFuture()
  }

  override fun handleSetPlaybackParameters(
    playbackParameters: PlaybackParameters
  ): ListenableFuture<*> {
    state = state.buildUpon().setPlaybackParameters(playbackParameters).build()
    return Futures.immediateVoidFuture()
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

  override fun handleSetVolume(
    volume: Float,
    volumeOperationType: @C.VolumeOperationType Int,
  ): ListenableFuture<*> {
    state = state.buildUpon().setVolume(volume).build()
    return Futures.immediateVoidFuture()
  }

  fun setPlaybackState(playbackState: @Player.State Int) {
    state = state.buildUpon().setPlaybackState(playbackState).build()
    invalidateState()
  }

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

  fun setBufferedPositionMs(bufferedPositionMs: Long) {
    state = state.buildUpon().setContentBufferedPositionMs { bufferedPositionMs }.build()
    invalidateState()
  }

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

  fun setVideoSize(videoSize: VideoSize) {
    state = state.buildUpon().setVideoSize(videoSize).build()
    invalidateState()
  }

  fun setSeekBackIncrementMs(seekBackIncrementMs: Long) {
    state = state.buildUpon().setSeekBackIncrementMs(seekBackIncrementMs).build()
    invalidateState()
  }

  fun setSeekForwardIncrementMs(seekForwardIncrementMs: Long) {
    state = state.buildUpon().setSeekForwardIncrementMs(seekForwardIncrementMs).build()
    invalidateState()
  }

  fun renderFirstFrame(newlyRenderedFirstFrame: Boolean) {
    state = state.buildUpon().setNewlyRenderedFirstFrame(newlyRenderedFirstFrame).build()
    invalidateState() // flushes EVENT_RENDERED_FIRST_FRAME
    state = state.buildUpon().setNewlyRenderedFirstFrame(false).build()
  }

  fun removeCommands(vararg commands: @Player.Command Int) {
    // It doesn't seem possible to propagate the @IntDef annotation through Kotlin's spread operator
    // in a way that lint understands.
    @SuppressWarnings("WrongConstant")
    state =
      state
        .buildUpon()
        .setAvailableCommands(
          Player.Commands.Builder().addAllCommands().removeAll(*commands).build()
        )
        .build()
    invalidateState()
  }

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
}
