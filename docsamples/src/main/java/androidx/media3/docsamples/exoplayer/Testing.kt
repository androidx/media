/*
 * Copyright 2026 The Android Open Source Project
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
@file:Suppress("unused_parameter", "unused_variable", "unused", "CheckReturnValue")

package androidx.media3.docsamples.exoplayer

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.FakeMediaSource
import androidx.media3.test.utils.FakePlayer
import androidx.media3.test.utils.FakeTimeline
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.awaitContentPositionAtLeast
import androidx.media3.test.utils.awaitFirstFrameRendered
import androidx.media3.test.utils.awaitIsPlaying
import androidx.media3.test.utils.awaitPlaybackState
import androidx.media3.test.utils.awaitSupervised
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper.play
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

// Snippets for Testing.

object TestingKt {

  // [START robolectric_playback_test]
  @RunWith(AndroidJUnit4::class)
  @OptIn(UnstableApi::class)
  class PlaybackRobolectricTest {

    private lateinit var player: ExoPlayer

    @After
    fun tearDown() {
      if (::player.isInitialized) {
        player.release()
      }
    }

    @Test
    fun play_sampleMediaItem_reachesEndedState() {
      val context = ApplicationProvider.getApplicationContext<Context>()
      player = TestExoPlayerBuilder(context).build()
      player.setMediaItem(MediaItem.fromUri("asset:///media/mp4/sample.mp4"))
      player.prepare()

      play(player).untilState(Player.STATE_ENDED)

      assertThat(player.playbackState).isEqualTo(Player.STATE_ENDED)
    }
  }

  // [END robolectric_playback_test]

  @OptIn(UnstableApi::class)
  fun robolectricFakeMediaSource(context: Context) {
    // [START robolectric_fake_media_source]
    val player = TestExoPlayerBuilder(context).build()
    val fakeMediaSource = FakeMediaSource(FakeTimeline(/* windowCount= */ 2))
    player.setMediaSource(fakeMediaSource)
    player.prepare()

    play(player).untilMediaItemIndex(/* mediaItemIndex= */ 1)

    assertThat(player.currentMediaItemIndex).isEqualTo(1)
    player.release()
    // [END robolectric_fake_media_source]
  }

  // [START instrumentation_playback_test]
  @RunWith(AndroidJUnit4::class)
  @OptIn(UnstableApi::class)
  class PlaybackInstrumentationTest {

    private lateinit var player: ExoPlayer

    @After
    fun tearDown() =
      runBlocking(Dispatchers.Main) {
        if (::player.isInitialized) {
          player.release()
        }
      }

    @Test
    fun play_sampleMediaItem_advancesAndEnds() =
      runBlocking(Dispatchers.Main) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        player = ExoPlayer.Builder(context).build()
        player.setMediaItem(MediaItem.fromUri("asset:///media/mp4/sample.mp4"))
        player.prepare()

        player.play()
        player.awaitIsPlaying(true)
        player.awaitContentPositionAtLeast(500)
        player.awaitPlaybackState(Player.STATE_ENDED)

        assertThat(player.playbackState).isEqualTo(Player.STATE_ENDED)
      }
  }

  // [END instrumentation_playback_test]

  @OptIn(UnstableApi::class)
  suspend fun instrumentationCustomSupervision(player: ExoPlayer, mediaItem: MediaItem) {
    // [START instrumentation_custom_supervision]
    // Start awaiting transient events (with a video surface attached) before prepare().
    coroutineScope {
      val firstFrameDeferred =
        async(start = CoroutineStart.UNDISPATCHED) { player.awaitFirstFrameRendered() }
      player.setMediaItem(mediaItem)
      player.prepare()
      player.play()
      firstFrameDeferred.await()
    }

    // Supervise a custom listener condition while still failing fast on player errors.
    val metadataTitle = player.awaitSupervised {
      player.mediaMetadata.title?.let {
        return@awaitSupervised it
      }
      val titleDeferred = CompletableDeferred<CharSequence>()
      val listener =
        object : Player.Listener {
          override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            mediaMetadata.title?.let { titleDeferred.complete(it) }
          }
        }
      player.addListener(listener)
      try {
        titleDeferred.await()
      } finally {
        player.removeListener(listener)
      }
    }
    // [END instrumentation_custom_supervision]
  }

  @OptIn(UnstableApi::class)
  fun fakePlayerTest() {
    // [START fake_player_test]
    val fakePlayer = FakePlayer(bufferingDelayMs = 0)
    fakePlayer.setMediaItem(MediaItem.fromUri("https://example.com/media.mp4"))
    fakePlayer.prepare()
    fakePlayer.play()

    assertThat(fakePlayer.isPlaying).isTrue()

    // Drive state transitions directly to test UI or session listeners.
    fakePlayer.setPosition(30_000)
    fakePlayer.setPlaybackState(Player.STATE_ENDED)
    fakePlayer.release()
    // [END fake_player_test]
  }
}
