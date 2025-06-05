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

import android.view.SurfaceView
import android.view.TextureView
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.ui.compose.utils.TestPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify

/** Unit test for [PlayerSurface]. */
@RunWith(AndroidJUnit4::class)
class PlayerSurfaceTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun playerSurface_withSurfaceViewType_setsSurfaceViewOnPlayer() {
    val player = TestPlayer()

    composeTestRule.setContent {
      PlayerSurface(player = player, surfaceType = SURFACE_TYPE_SURFACE_VIEW)
    }
    composeTestRule.waitForIdle()

    assertThat(player.videoOutput).isInstanceOf(SurfaceView::class.java)
  }

  @Test
  fun playerSurface_withTextureViewType_setsTextureViewOnPlayer() {
    val player = TestPlayer()

    composeTestRule.setContent {
      PlayerSurface(player = player, surfaceType = SURFACE_TYPE_TEXTURE_VIEW)
    }
    composeTestRule.waitForIdle()

    assertThat(player.videoOutput).isInstanceOf(TextureView::class.java)
  }

  @Test
  fun playerSurface_withoutSupportedCommand_doesNotSetSurfaceOnPlayer() {
    val player = TestPlayer()
    player.removeCommands(Player.COMMAND_SET_VIDEO_SURFACE)

    composeTestRule.setContent {
      PlayerSurface(player = player, surfaceType = SURFACE_TYPE_TEXTURE_VIEW)
    }
    composeTestRule.waitForIdle()

    assertThat(player.videoOutput).isNull()
  }

  @Test
  fun playerSurface_withUpdateSurfaceType_setsNewSurfaceOnPlayer() {
    val player = TestPlayer()

    lateinit var surfaceType: MutableIntState
    composeTestRule.setContent {
      surfaceType = remember { mutableIntStateOf(SURFACE_TYPE_TEXTURE_VIEW) }
      PlayerSurface(player = player, surfaceType = surfaceType.intValue)
    }
    composeTestRule.waitForIdle()
    surfaceType.intValue = SURFACE_TYPE_SURFACE_VIEW
    composeTestRule.waitForIdle()

    assertThat(player.videoOutput).isInstanceOf(SurfaceView::class.java)
  }

  @Test
  fun playerSurface_withNewPlayer_unsetsSurfaceOnOldPlayerFirst() {
    val player0 = TestPlayer()
    val player1 = TestPlayer()
    val spyPlayer0 = spy(ForwardingPlayer(player0))
    val spyPlayer1 = spy(ForwardingPlayer(player1))

    lateinit var playerIndex: MutableIntState
    composeTestRule.setContent {
      playerIndex = remember { mutableIntStateOf(0) }
      PlayerSurface(
        player = if (playerIndex.intValue == 0) spyPlayer0 else spyPlayer1,
        surfaceType = SURFACE_TYPE_SURFACE_VIEW,
      )
    }
    composeTestRule.waitForIdle()
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
    val nonNullPlayer = TestPlayer()
    val spyPlayer = spy(ForwardingPlayer(nonNullPlayer))

    lateinit var playerIndex: MutableIntState
    composeTestRule.setContent {
      playerIndex = remember { mutableIntStateOf(0) }
      PlayerSurface(
        player = if (playerIndex.intValue == 0) spyPlayer else null,
        surfaceType = SURFACE_TYPE_SURFACE_VIEW,
      )
    }
    composeTestRule.waitForIdle()
    playerIndex.intValue = 1
    composeTestRule.waitForIdle()

    assertThat(nonNullPlayer.videoOutput).isNull()
    verify(spyPlayer).clearVideoSurfaceView(any())
  }

  @Test
  fun twoPlayerSurfaces_exchangePlayers_onlyOnePlayerClearsTheSurfaceNotBoth() {
    val player0 = TestPlayer()
    val player1 = TestPlayer()
    val spyPlayer0 = spy(ForwardingPlayer(player0))
    val spyPlayer1 = spy(ForwardingPlayer(player1))
    val argCaptor = ArgumentCaptor.forClass(SurfaceView::class.java)

    lateinit var playerIndex: MutableIntState
    composeTestRule.setContent {
      playerIndex = remember { mutableIntStateOf(0) }
      PlayerSurface(player = if (playerIndex.intValue == 0) spyPlayer0 else spyPlayer1)
      PlayerSurface(player = if (playerIndex.intValue == 0) spyPlayer1 else spyPlayer0)
    }
    composeTestRule.waitForIdle()
    playerIndex.intValue = 1
    composeTestRule.waitForIdle()

    assertThat(player0.videoOutput).isNotNull()
    assertThat(player1.videoOutput).isNotNull()

    val inOrder = inOrder(spyPlayer0, spyPlayer1)
    // Original setup
    inOrder.verify(spyPlayer0).setVideoSurfaceView(argCaptor.capture())
    val surfaceView0 = argCaptor.value
    inOrder.verify(spyPlayer1).setVideoSurfaceView(argCaptor.capture())
    val surfaceView1 = argCaptor.value

    // Stages of exchanging players
    inOrder.verify(spyPlayer0).clearVideoSurfaceView(surfaceView0)
    inOrder.verify(spyPlayer1).setVideoSurfaceView(surfaceView0)
    inOrder.verify(spyPlayer1).clearVideoSurfaceView(surfaceView1) // no-op, not the current surface
    inOrder.verify(spyPlayer0).setVideoSurfaceView(surfaceView1)
    inOrder.verifyNoMoreInteractions()
  }

  @Test
  fun twoPlayerSurfaces_passPlayerFromOneToAnotherAndBack_avoidsIntermediateUnnecessaryClearing() {
    val player = TestPlayer()
    val spyPlayer = spy(ForwardingPlayer(player))
    val argCaptor = ArgumentCaptor.forClass(SurfaceView::class.java)

    lateinit var playerIndex: MutableIntState
    composeTestRule.setContent {
      playerIndex = remember { mutableIntStateOf(0) }
      PlayerSurface(player = if (playerIndex.intValue == 0) spyPlayer else null)
      PlayerSurface(player = if (playerIndex.intValue == 0) null else spyPlayer)
    }
    composeTestRule.waitForIdle()
    val inOrder = inOrder(spyPlayer)
    inOrder.verify(spyPlayer).setVideoSurfaceView(argCaptor.capture())
    val originalSurface = argCaptor.value

    playerIndex.intValue = 1
    composeTestRule.waitForIdle()

    assertThat(player.videoOutput).isNotNull()
    inOrder.verify(spyPlayer).setVideoSurfaceView(argCaptor.capture())
    val newSurface1 = argCaptor.value
    assertThat(originalSurface).isNotEqualTo(newSurface1)
    inOrder.verify(spyPlayer).clearVideoSurfaceView(originalSurface) // no-op, wrong surface
    inOrder.verifyNoMoreInteractions()

    playerIndex.intValue = 0
    composeTestRule.waitForIdle()

    assertThat(player.videoOutput).isNotNull()
    inOrder.verify(spyPlayer).setVideoSurfaceView(argCaptor.capture())
    val newSurface0 = argCaptor.value
    assertThat(originalSurface).isEqualTo(newSurface0)
    inOrder.verify(spyPlayer).clearVideoSurfaceView(newSurface1) // no-op, wrong surface
    inOrder.verifyNoMoreInteractions()
  }
}
