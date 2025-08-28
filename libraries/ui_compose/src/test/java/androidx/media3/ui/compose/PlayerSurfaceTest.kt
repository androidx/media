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
package androidx.media3.ui.compose

import android.os.Looper
import android.view.SurfaceView
import android.view.TextureView
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.test.utils.TestSimpleBasePlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.AdditionalAnswers.delegatesTo
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify

/** Unit test for [PlayerSurface]. */
@RunWith(AndroidJUnit4::class)
class PlayerSurfaceTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun playerSurface_withSurfaceViewType_setsSurfaceViewOnPlayer() {
    val player = TestSimpleBasePlayer()

    composeTestRule.setContent {
      PlayerSurface(player = player, surfaceType = SURFACE_TYPE_SURFACE_VIEW)
    }

    assertThat(player.videoOutput).isInstanceOf(SurfaceView::class.java)
  }

  @Test
  fun playerSurface_withTextureViewType_setsTextureViewOnPlayer() {
    val player = TestSimpleBasePlayer()

    composeTestRule.setContent {
      PlayerSurface(player = player, surfaceType = SURFACE_TYPE_TEXTURE_VIEW)
    }

    assertThat(player.videoOutput).isInstanceOf(TextureView::class.java)
  }

  @Test
  fun playerSurface_withoutSupportedCommand_doesNotSetSurfaceOnPlayer() {
    val player = TestSimpleBasePlayer()
    player.removeCommands(Player.COMMAND_SET_VIDEO_SURFACE)

    composeTestRule.setContent {
      PlayerSurface(player = player, surfaceType = SURFACE_TYPE_TEXTURE_VIEW)
    }

    assertThat(player.videoOutput).isNull()
  }

  @Test
  fun playerSurface_withUpdateSurfaceType_setsNewSurfaceOnPlayer() {
    val player = TestSimpleBasePlayer()

    lateinit var surfaceType: MutableIntState
    composeTestRule.setContent {
      surfaceType = remember { mutableIntStateOf(SURFACE_TYPE_TEXTURE_VIEW) }
      PlayerSurface(player = player, surfaceType = surfaceType.intValue)
    }

    surfaceType.intValue = SURFACE_TYPE_SURFACE_VIEW
    composeTestRule.waitForIdle()

    assertThat(player.videoOutput).isInstanceOf(SurfaceView::class.java)
  }

  @Test
  fun playerSurface_withNewPlayer_unsetsSurfaceOnOldPlayerFirst() {
    val player0 = TestSimpleBasePlayer()
    val player1 = TestSimpleBasePlayer()
    val spyPlayer0 = mock(Player::class.java, delegatesTo<Player>(player0))
    val spyPlayer1 = mock(Player::class.java, delegatesTo<Player>(player1))

    lateinit var playerIndex: MutableIntState
    composeTestRule.setContent {
      playerIndex = remember { mutableIntStateOf(0) }
      PlayerSurface(
        player = if (playerIndex.intValue == 0) spyPlayer0 else spyPlayer1,
        surfaceType = SURFACE_TYPE_SURFACE_VIEW,
      )
    }

    playerIndex.intValue = 1
    composeTestRule.waitForIdle()

    assertThat(player0.videoOutput).isNull()
    assertThat(player1.videoOutput).isNotNull()
    val inOrder = inOrder(spyPlayer0, spyPlayer1)
    inOrder.verify(spyPlayer0).clearVideoSurfaceView(any())
    inOrder.verify(spyPlayer1).setVideoSurfaceView(any())
  }

  @Test
  fun playerSurface_withNullPlayer_createsStandaloneAndroidSurface() {
    composeTestRule.setContent { PlayerSurface(player = null) }
    composeTestRule.waitForIdle()
  }

  @Test
  fun playerSurface_fromPlayerToNull_unsetsSurfaceOnOldPlayer() {
    val nonNullPlayer = TestSimpleBasePlayer()
    val spyPlayer = spy(ForwardingPlayer(nonNullPlayer))

    lateinit var playerIndex: MutableIntState
    composeTestRule.setContent {
      playerIndex = remember { mutableIntStateOf(0) }
      PlayerSurface(
        player = if (playerIndex.intValue == 0) spyPlayer else null,
        surfaceType = SURFACE_TYPE_SURFACE_VIEW,
      )
    }

    playerIndex.intValue = 1
    composeTestRule.waitForIdle()

    assertThat(nonNullPlayer.videoOutput).isNull()
    verify(spyPlayer).clearVideoSurfaceView(any())
  }

  @Test
  fun twoPlayerSurfaces_exchangePlayers_neverAssignsSurfaceSimultaneouslyAndAvoidsUnnecessaryRemoval() {
    val tracker = PlayerSurfaceTracker()
    val player0 = tracker.createPlayer()
    val player1 = tracker.createPlayer()
    lateinit var playerIndex: MutableIntState
    composeTestRule.setContent {
      playerIndex = remember { mutableIntStateOf(0) }
      PlayerSurface(player = if (playerIndex.intValue == 0) player0 else player1)
      PlayerSurface(player = if (playerIndex.intValue == 0) player1 else player0)
    }

    playerIndex.intValue = 1
    composeTestRule.waitForIdle()

    // Verify every player received both surfaces
    assertThat(tracker.getAssignedSurfaceCount(player0)).isEqualTo(2)
    assertThat(tracker.getAssignedSurfaceCount(player1)).isEqualTo(2)
    // Assert correctness and efficiency
    assertThat(tracker.isAnySurfaceUsedByTwoPlayersSimultaneously()).isFalse()
    assertThat(tracker.isAnySurfaceRemovedWithoutNeedingItForAnotherPlayer()).isFalse()
  }

  @Test
  fun twoPlayerSurfaces_passPlayerFromOneToAnotherAndBack_avoidsIntermediateUnnecessaryRemoval() {
    val tracker = PlayerSurfaceTracker()
    val player = tracker.createPlayer()
    lateinit var playerIndex: MutableIntState
    composeTestRule.setContent {
      playerIndex = remember { mutableIntStateOf(0) }
      PlayerSurface(player = if (playerIndex.intValue == 0) player else null)
      PlayerSurface(player = if (playerIndex.intValue == 0) null else player)
    }

    playerIndex.intValue = 1
    composeTestRule.waitForIdle()
    playerIndex.intValue = 0
    composeTestRule.waitForIdle()

    // Verify the player received all surface updates
    assertThat(tracker.getAssignedSurfaceCount(player)).isEqualTo(3)
    // Check no unnecessary placeholder surfaces were needed
    assertThat(tracker.isAnySurfaceRemovedWithoutNeedingItForAnotherPlayer()).isFalse()
  }

  @Test
  fun playerSurface_inReusableContainerWithDifferentPlayers_neverAssignsSurfacesSimultaneouslyAndAvoidsUnnecessaryRemoval() {
    val tracker = PlayerSurfaceTracker()
    val players =
      listOf(
        tracker.createPlayer(),
        tracker.createPlayer(),
        tracker.createPlayer(),
        tracker.createPlayer(),
      )

    composeTestRule.setContent {
      LazyColumn(modifier = Modifier.testTag("lazyColumn")) {
        items(count = 4) { index ->
          // Use very large height to ensure only a single item is shown.
          PlayerSurface(
            modifier = Modifier.defaultMinSize(minHeight = 10000.dp),
            player = players[index],
            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
          )
        }
      }
    }
    // Show every element twice to verify reuse within and across items
    composeTestRule.onNodeWithTag("lazyColumn").performScrollToIndex(1)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("lazyColumn").performScrollToIndex(2)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("lazyColumn").performScrollToIndex(3)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("lazyColumn").performScrollToIndex(0)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("lazyColumn").performScrollToIndex(1)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("lazyColumn").performScrollToIndex(2)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("lazyColumn").performScrollToIndex(3)
    composeTestRule.waitForIdle()

    // Verify test setup actually re-uses surfaces
    assertThat(tracker.getTotalSurfaceCount()).isLessThan(4)
    // Verify every player received a surface twice (=each time it became visible)
    assertThat(tracker.getAssignedSurfaceCount(players[0])).isEqualTo(2)
    assertThat(tracker.getAssignedSurfaceCount(players[1])).isEqualTo(2)
    assertThat(tracker.getAssignedSurfaceCount(players[2])).isEqualTo(2)
    assertThat(tracker.getAssignedSurfaceCount(players[3])).isEqualTo(2)
    // Assert correctness and efficiency
    assertThat(tracker.isAnySurfaceUsedByTwoPlayersSimultaneously()).isFalse()
    assertThat(tracker.isAnySurfaceRemovedWithoutNeedingItForAnotherPlayer()).isFalse()
  }

  @Test
  fun playerSurface_inReusableContainerWithDifferentPlayersOnlyOneAssigned_neverAssignsSurfacesSimultaneouslyAndAvoidsUnnecessaryRemoval() {
    val tracker = PlayerSurfaceTracker()
    val players =
      listOf(
        tracker.createPlayer(),
        tracker.createPlayer(),
        tracker.createPlayer(),
        tracker.createPlayer(),
      )

    composeTestRule.setContent {
      val listState = rememberLazyListState()
      val currentVisibleIndex by remember { derivedStateOf { listState.firstVisibleItemIndex } }
      LazyColumn(state = listState, modifier = Modifier.testTag("lazyColumn")) {
        items(count = 4) { index ->
          // Use very large height to ensure only a single item is shown.
          PlayerSurface(
            modifier = Modifier.defaultMinSize(minHeight = 10000.dp),
            player = if (index == currentVisibleIndex) players[index] else null,
            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
          )
        }
      }
    }
    // Show every element twice to verify reuse within and across items
    composeTestRule.onNodeWithTag("lazyColumn").performScrollToIndex(1)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("lazyColumn").performScrollToIndex(2)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("lazyColumn").performScrollToIndex(3)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("lazyColumn").performScrollToIndex(0)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("lazyColumn").performScrollToIndex(1)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("lazyColumn").performScrollToIndex(2)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("lazyColumn").performScrollToIndex(3)
    composeTestRule.waitForIdle()

    // Verify test setup actually re-uses surfaces
    assertThat(tracker.getTotalSurfaceCount()).isLessThan(4)
    // Verify every player received a surface twice (=each time it became visible)
    assertThat(tracker.getAssignedSurfaceCount(players[0])).isEqualTo(2)
    assertThat(tracker.getAssignedSurfaceCount(players[1])).isEqualTo(2)
    assertThat(tracker.getAssignedSurfaceCount(players[2])).isEqualTo(2)
    assertThat(tracker.getAssignedSurfaceCount(players[3])).isEqualTo(2)
    // Assert correctness and efficiency
    assertThat(tracker.isAnySurfaceUsedByTwoPlayersSimultaneously()).isFalse()
    assertThat(tracker.isAnySurfaceRemovedWithoutNeedingItForAnotherPlayer()).isFalse()
  }

  @Test
  fun playerSurface_inReusableContainerWithSamePlayer_updatesSurfacesOnPlayerWithoutRemoval() {
    val tracker = PlayerSurfaceTracker()
    val player = tracker.createPlayer()

    composeTestRule.setContent {
      LazyColumn(modifier = Modifier.testTag("lazyColumn")) {
        items(count = 4) { index ->
          // Use very large height to ensure only a single item is shown.
          PlayerSurface(
            modifier = Modifier.defaultMinSize(minHeight = 10000.dp),
            player = player,
            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
          )
        }
      }
    }
    // Show every element twice to verify reuse within and across items
    composeTestRule.onNodeWithTag("lazyColumn").performScrollToIndex(1)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("lazyColumn").performScrollToIndex(2)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("lazyColumn").performScrollToIndex(3)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("lazyColumn").performScrollToIndex(0)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("lazyColumn").performScrollToIndex(1)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("lazyColumn").performScrollToIndex(2)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("lazyColumn").performScrollToIndex(3)
    composeTestRule.waitForIdle()

    // Verify test setup actually re-uses surfaces
    assertThat(tracker.getTotalSurfaceCount()).isLessThan(4)
    // Verify the player the expected total number of surfaces
    assertThat(tracker.getAssignedSurfaceCount(player)).isEqualTo(8)
    // Assert the surface usage was efficient
    assertThat(tracker.isAnySurfaceRemovedWithoutNeedingItForAnotherPlayer()).isFalse()
  }

  @Test
  fun playerSurface_inReusableContainerWithSamePlayerOnlyOneAssigned_updatesSurfacesOnPlayerWithoutRemoval() {
    val tracker = PlayerSurfaceTracker()
    val player = tracker.createPlayer()

    composeTestRule.setContent {
      val listState = rememberLazyListState()
      val currentVisibleIndex by remember { derivedStateOf { listState.firstVisibleItemIndex } }
      LazyColumn(state = listState, modifier = Modifier.testTag("lazyColumn")) {
        items(count = 4) { index ->
          // Use very large height to ensure only a single item is shown.
          PlayerSurface(
            modifier = Modifier.defaultMinSize(minHeight = 10000.dp),
            player = if (index == currentVisibleIndex) player else null,
            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
          )
        }
      }
    }
    // Show every element twice to verify reuse within and across items
    composeTestRule.onNodeWithTag("lazyColumn").performScrollToIndex(1)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("lazyColumn").performScrollToIndex(2)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("lazyColumn").performScrollToIndex(3)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("lazyColumn").performScrollToIndex(0)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("lazyColumn").performScrollToIndex(1)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("lazyColumn").performScrollToIndex(2)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("lazyColumn").performScrollToIndex(3)
    composeTestRule.waitForIdle()

    // Verify test setup actually re-uses surfaces
    assertThat(tracker.getTotalSurfaceCount()).isLessThan(4)
    // Verify the player the expected total number of surfaces
    assertThat(tracker.getAssignedSurfaceCount(player)).isEqualTo(8)
    // Assert the surface usage was efficient
    assertThat(tracker.isAnySurfaceRemovedWithoutNeedingItForAnotherPlayer()).isFalse()
  }

  @Test
  fun playerSurface_inReusableContainerWithTwoPlayers_neverAssignsSurfacesSimultaneouslyAndAvoidsUnnecessaryRemoval() {
    // Using two players is meant to force a situation where the same re-used surface is assigned
    // to the same player again.
    val tracker = PlayerSurfaceTracker()
    val players = listOf(tracker.createPlayer(), tracker.createPlayer())

    composeTestRule.setContent {
      LazyColumn(modifier = Modifier.testTag("lazyColumn")) {
        items(count = 4) { index ->
          // Use very large height to ensure only a single item is shown.
          PlayerSurface(
            modifier = Modifier.defaultMinSize(minHeight = 10000.dp),
            player = players[index % 2],
            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
          )
        }
      }
    }
    // Show every element twice to verify reuse within and across items
    composeTestRule.onNodeWithTag("lazyColumn").performScrollToIndex(1)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("lazyColumn").performScrollToIndex(2)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("lazyColumn").performScrollToIndex(3)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("lazyColumn").performScrollToIndex(0)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("lazyColumn").performScrollToIndex(1)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("lazyColumn").performScrollToIndex(2)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("lazyColumn").performScrollToIndex(3)
    composeTestRule.waitForIdle()

    // Verify test setup actually re-uses surfaces and the same surface has been used consecutively
    // by the same player at least once
    assertThat(tracker.getTotalSurfaceCount()).isLessThan(4)
    assertThat(tracker.hasAnyPlayerTheSameSurfaceAssignedConsecutively()).isTrue()
    // Verify every player received a surface twice (=each time it became visible)
    assertThat(tracker.getAssignedSurfaceCount(players[0])).isEqualTo(4)
    assertThat(tracker.getAssignedSurfaceCount(players[1])).isEqualTo(4)
    // Assert correctness and efficiency
    assertThat(tracker.isAnySurfaceUsedByTwoPlayersSimultaneously()).isFalse()
    assertThat(tracker.isAnySurfaceRemovedWithoutNeedingItForAnotherPlayer()).isFalse()
  }

  @Test
  fun playerSurface_inReusableContainerWithTwoPlayersOnlyOneAssigned_neverAssignsSurfacesSimultaneouslyAndAvoidsUnnecessaryRemoval() {
    // Using two players is meant to force a situation where the same re-used surface is assigned
    // to the same player again.
    val tracker = PlayerSurfaceTracker()
    val players = listOf(tracker.createPlayer(), tracker.createPlayer())

    composeTestRule.setContent {
      val listState = rememberLazyListState()
      val currentVisibleIndex by remember { derivedStateOf { listState.firstVisibleItemIndex } }
      LazyColumn(state = listState, modifier = Modifier.testTag("lazyColumn")) {
        items(count = 4) { index ->
          // Use very large height to ensure only a single item is shown.
          PlayerSurface(
            modifier = Modifier.defaultMinSize(minHeight = 10000.dp),
            player = if (index == currentVisibleIndex) players[index % 2] else null,
            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
          )
        }
      }
    }
    // Show every element twice to verify reuse within and across items
    composeTestRule.onNodeWithTag("lazyColumn").performScrollToIndex(1)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("lazyColumn").performScrollToIndex(2)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("lazyColumn").performScrollToIndex(3)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("lazyColumn").performScrollToIndex(0)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("lazyColumn").performScrollToIndex(1)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("lazyColumn").performScrollToIndex(2)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("lazyColumn").performScrollToIndex(3)
    composeTestRule.waitForIdle()

    // Verify test setup actually re-uses surfaces and the same surface has been used consecutively
    // by the same player at least once
    assertThat(tracker.getTotalSurfaceCount()).isLessThan(4)
    assertThat(tracker.hasAnyPlayerTheSameSurfaceAssignedConsecutively()).isTrue()
    // Verify every player received a surface twice (=each time it became visible)
    assertThat(tracker.getAssignedSurfaceCount(players[0])).isEqualTo(4)
    assertThat(tracker.getAssignedSurfaceCount(players[1])).isEqualTo(4)
    // Assert correctness and efficiency
    assertThat(tracker.isAnySurfaceUsedByTwoPlayersSimultaneously()).isFalse()
    assertThat(tracker.isAnySurfaceRemovedWithoutNeedingItForAnotherPlayer()).isFalse()
  }

  private class PlayerSurfaceTracker {

    private enum class SurfaceChangeType {
      ADD,
      REPLACE,
      REMOVE,
    }

    private data class Interaction(
      val player: Player,
      val surface: SurfaceView,
      val type: SurfaceChangeType,
    )

    private val interactions: MutableList<Interaction> = mutableListOf()

    fun createPlayer(): Player {
      return object : SimpleBasePlayer(Looper.myLooper()!!) {
        var currentSurface: SurfaceView? = null

        override fun getState(): State {
          return State.Builder()
            .setAvailableCommands(Player.Commands.Builder().add(COMMAND_SET_VIDEO_SURFACE).build())
            .build()
        }

        override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
          currentSurface?.let {
            interactions.add(Interaction(player = this, surface = it, SurfaceChangeType.REPLACE))
          }
          currentSurface = videoOutput as SurfaceView
          interactions.add(Interaction(player = this, videoOutput, SurfaceChangeType.ADD))
          return Futures.immediateVoidFuture()
        }

        override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
          currentSurface?.let { current ->
            if (videoOutput == null || videoOutput == current) {
              interactions.add(Interaction(player = this, current, SurfaceChangeType.REMOVE))
              currentSurface = null
            }
          }
          return Futures.immediateVoidFuture()
        }
      }
    }

    fun getTotalSurfaceCount(): Int {
      return interactions.map { interaction -> interaction.surface }.distinct().count()
    }

    fun getAssignedSurfaceCount(player: Player): Int {
      return interactions.count { interaction ->
        interaction.player == player && interaction.type == SurfaceChangeType.ADD
      }
    }

    fun hasAnyPlayerTheSameSurfaceAssignedConsecutively(): Boolean {
      return interactions
        .groupBy { interaction -> interaction.player }
        .any { (_, surfaceInteractions) ->
          surfaceInteractions.zipWithNext().any { (first, second) ->
            (first.type == SurfaceChangeType.REPLACE || first.type == SurfaceChangeType.REMOVE) &&
              second.type == SurfaceChangeType.ADD &&
              first.surface == second.surface
          }
        }
    }

    fun isAnySurfaceUsedByTwoPlayersSimultaneously(): Boolean {
      // Check if any surface has two consecutive ADD interactions without REPLACE/REMOVE indicating
      // it's been assigned to two players simultaneously.
      return interactions
        .groupBy { interaction -> interaction.surface }
        .any { (_, surfaceInteractions) ->
          surfaceInteractions.zipWithNext().any { (first, second) ->
            first.type == SurfaceChangeType.ADD && second.type == SurfaceChangeType.ADD
          }
        }
    }

    fun isAnySurfaceRemovedWithoutNeedingItForAnotherPlayer(): Boolean {
      return interactions.withIndex().any { interaction ->
        interaction.value.type == SurfaceChangeType.REMOVE &&
          !isSurfaceRemovalRequiredForAnotherPlayer(
            removalInteraction = interaction.value,
            remainingInteractions = interactions.drop(interaction.index + 1),
          )
      }
    }

    private fun isSurfaceRemovalRequiredForAnotherPlayer(
      removalInteraction: Interaction,
      remainingInteractions: List<Interaction>,
    ): Boolean {
      // Removing a surface is inefficient as it requires a placeholder surface. Check if this only
      // happens if the surface needs to be re-assigned to another player before the previous player
      // gets a new surface.
      for (interaction in remainingInteractions) {
        if (
          interaction.surface == removalInteraction.surface &&
            interaction.type == SurfaceChangeType.ADD &&
            interaction.player != removalInteraction.player
        ) {
          // Same surface assigned to new player, removal was required.
          return true
        }
        if (
          interaction.type == SurfaceChangeType.ADD &&
            interaction.player == removalInteraction.player
        ) {
          // Previous player gets a new surface first, removal was unnecessary.
          return false
        }
      }
      // Removal wasn't needed in any of the remaining interactions
      return false
    }
  }
}
