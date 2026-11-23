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

package androidx.media3.ui.compose.motionphoto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.test.utils.FakeBitmapLoader
import androidx.media3.test.utils.FakePlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.SettableFuture
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Unit tests for [MotionPhoto]. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class MotionPhotoTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private val unresolvableMediaItem = MediaItem.fromUri("file:///non_existent_motion_photo.jpg")

  @Test
  fun motionPhoto_withFileUriAndDefaultBitmapLoader_rendersLoadedPhoto() = runComposeUiTest {
    val fileMediaItem = MediaItem.fromUri(createTestImageUri())

    setContent {
      val state = rememberMotionPhotoState(player = null, mediaItem = fileMediaItem)
      MotionPhoto(
        state = state,
        contentDescription = PHOTO_CONTENT_DESCRIPTION,
        modifier = Modifier.size(100.dp),
        placeholder = null,
        error = null,
      )
    }
    waitUntil {
      onAllNodesWithContentDescription(PHOTO_CONTENT_DESCRIPTION).fetchSemanticsNodes().isNotEmpty()
    }

    onNodeWithContentDescription(PHOTO_CONTENT_DESCRIPTION).assertIsDisplayed()
  }

  @Test
  fun motionPhoto_withoutPlayer_waitsForBitmapLoadBeforeRenderingPhoto() = runComposeUiTest {
    val bitmapFuture = SettableFuture.create<Bitmap>()
    lateinit var state: MotionPhotoState

    setContent {
      state = rememberMotionPhotoState(player = null, mediaItem = unresolvableMediaItem)
      MotionPhoto(
        state = state,
        contentDescription = PHOTO_CONTENT_DESCRIPTION,
        modifier = Modifier.size(100.dp),
        bitmapLoader = FakeBitmapLoader(bitmapFuture),
        placeholder = null,
        error = null,
      )
    }
    waitForIdle()

    assertThat(state.visualState.isPhotoVisible).isTrue()
    onNodeWithContentDescription(PHOTO_CONTENT_DESCRIPTION).assertDoesNotExist()

    bitmapFuture.set(createBitmap(2, 2))
    waitForIdle()

    onNodeWithContentDescription(PHOTO_CONTENT_DESCRIPTION).assertIsDisplayed()
  }

  @Test
  fun motionPhoto_whenDefaultBitmapLoaderFails_rendersErrorPainter() = runComposeUiTest {
    val errorPainter = TestPainter()
    lateinit var composeView: View

    setContent {
      composeView = LocalView.current
      val state = rememberMotionPhotoState(player = null, mediaItem = unresolvableMediaItem)
      MotionPhoto(
        state = state,
        contentDescription = PHOTO_CONTENT_DESCRIPTION,
        modifier = Modifier.size(100.dp),
        placeholder = null,
        error = errorPainter,
      )
    }
    waitUntil {
      onAllNodesWithContentDescription(PHOTO_CONTENT_DESCRIPTION).fetchSemanticsNodes().isNotEmpty()
    }
    composeView.draw(Canvas())

    onNodeWithContentDescription(PHOTO_CONTENT_DESCRIPTION).assertIsDisplayed()
    assertThat(errorPainter.isDrawn).isTrue()
  }

  @Test
  fun motionPhoto_whenBitmapLoadFails_rendersErrorPainter() = runComposeUiTest {
    val errorPainter = TestPainter()
    lateinit var composeView: View

    setContent {
      composeView = LocalView.current
      val state = rememberMotionPhotoState(player = null, mediaItem = unresolvableMediaItem)
      MotionPhoto(
        state = state,
        contentDescription = PHOTO_CONTENT_DESCRIPTION,
        modifier = Modifier.size(100.dp),
        bitmapLoader = FakeBitmapLoader(IOException("Load failure")),
        placeholder = null,
        error = errorPainter,
      )
    }
    waitForIdle()
    composeView.draw(Canvas())

    onNodeWithContentDescription(PHOTO_CONTENT_DESCRIPTION).assertIsDisplayed()
    assertThat(errorPainter.isDrawn).isTrue()
  }

  @Test
  fun motionPhoto_whenToggleCalled_keepsPhotoInCompositionAndUpdatesPlayer() = runComposeUiTest {
    val player = FakePlayer(bufferingDelayMs = 0)
    var rememberInvocations = 0
    lateinit var state: MotionPhotoState
    lateinit var composeView: View
    setContent {
      composeView = LocalView.current
      state = rememberMotionPhotoState(player = player, mediaItem = unresolvableMediaItem)
      MotionPhoto(
        state = state,
        modifier = Modifier.size(100.dp),
        contentScale = ContentScale.Crop,
        photo = {
          val unused = remember { rememberInvocations++ }
          Box(modifier = Modifier.size(100.dp).testTag(PHOTO_SLOT_TAG))
        },
      )
    }
    waitForIdle()
    val photoSemanticsId = onNodeWithTag(PHOTO_SLOT_TAG).fetchSemanticsNode().id
    assertThat(state.visualState.isPhotoVisible).isTrue()
    assertThat(rememberInvocations).isEqualTo(1)
    onNodeWithTag(PHOTO_SLOT_TAG).assertExists()
    assertThat(
        composeView.accessibilityNodeProvider
          .createAccessibilityNodeInfo(photoSemanticsId)!!
          .isVisibleToUser
      )
      .isTrue()

    state.toggle()
    waitForIdle()
    assertThat(state.visualState.isPhotoVisible).isTrue()
    assertThat(player.playWhenReady).isTrue()

    player.renderFirstFrame(true)
    waitForIdle()

    assertThat(state.visualState.isPhotoVisible).isFalse()
    onNodeWithTag(PHOTO_SLOT_TAG).assertExists()
    assertThat(
        composeView.accessibilityNodeProvider
          .createAccessibilityNodeInfo(photoSemanticsId)!!
          .isVisibleToUser
      )
      .isFalse()
    assertThat(rememberInvocations).isEqualTo(1)
    assertThat(player.playWhenReady).isTrue()

    player.setPosition(300L)
    state.toggle()
    waitForIdle()

    assertThat(state.visualState.isPhotoVisible).isTrue()
    onNodeWithTag(PHOTO_SLOT_TAG).assertExists()
    assertThat(
        composeView.accessibilityNodeProvider
          .createAccessibilityNodeInfo(photoSemanticsId)!!
          .isVisibleToUser
      )
      .isTrue()
    assertThat(rememberInvocations).isEqualTo(1)
    assertThat(player.playWhenReady).isFalse()
    assertThat(player.currentPosition).isEqualTo(0L)
  }

  @Test
  fun motionPhoto_whenPlayerAttachedFromNull_keepsPhotoOverlayUntilFirstFrameRendered() =
    runComposeUiTest {
      var currentPlayer by mutableStateOf<Player?>(null)
      val observedIsPhotoVisibleWhenPlayerAttached = mutableSetOf<Boolean>()
      lateinit var state: MotionPhotoState
      setContent {
        state =
          rememberMotionPhotoState(
            player = currentPlayer,
            mediaItem = unresolvableMediaItem,
            initialShowMotion = true,
          )
        if (currentPlayer != null) {
          observedIsPhotoVisibleWhenPlayerAttached.add(state.visualState.isPhotoVisible)
        }
        MotionPhoto(
          state = state,
          modifier = Modifier.size(100.dp),
          photo = { Box(modifier = Modifier.size(100.dp).testTag(PHOTO_SLOT_TAG)) },
        )
      }
      waitForIdle()
      onNodeWithTag(PHOTO_SLOT_TAG).assertIsDisplayed()

      val newPlayer = FakePlayer(bufferingDelayMs = 0)
      currentPlayer = newPlayer
      waitForIdle()

      // Before first frame renders, still photo overlay remains visible over new player.
      assertThat(observedIsPhotoVisibleWhenPlayerAttached).containsExactly(true)
      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(newPlayer.playWhenReady).isTrue()
      onNodeWithTag(PHOTO_SLOT_TAG).assertIsDisplayed()

      // Once first frame renders, photo overlay transitions away.
      newPlayer.renderFirstFrame(true)
      waitForIdle()

      assertThat(state.visualState.isPhotoVisible).isFalse()
      onNodeWithTag(PHOTO_SLOT_TAG).assertExists()
      assertThat(newPlayer.currentMediaItem).isEqualTo(unresolvableMediaItem)
      assertThat(newPlayer.playbackState).isEqualTo(Player.STATE_READY)
      assertThat(newPlayer.playWhenReady).isTrue()
    }

  @Test
  fun motionPhoto_whenPlayerSwappedAndDetached_updatesSurfaceAndShowsPhotoWhenNull() =
    runComposeUiTest {
      val player1 = FakePlayer(bufferingDelayMs = 0)
      val player2 = FakePlayer(bufferingDelayMs = 0)
      var currentPlayer by mutableStateOf<Player?>(player1)
      lateinit var state: MotionPhotoState
      setContent {
        state =
          rememberMotionPhotoState(
            player = currentPlayer,
            mediaItem = unresolvableMediaItem,
            initialShowMotion = true,
          )
        MotionPhoto(
          state = state,
          contentDescription = null,
          modifier = Modifier.size(100.dp),
          bitmapLoader = FakeBitmapLoader(createBitmap(4, 4)),
        )
      }
      waitForIdle()
      player1.renderFirstFrame(true)
      waitForIdle()
      assertThat(state.visualState.isPhotoVisible).isFalse()
      assertThat(player1.videoOutput).isInstanceOf(SurfaceView::class.java)
      assertThat(player2.videoOutput).isNull()

      currentPlayer = player2
      waitForIdle()
      assertThat(state.visualState.isPhotoVisible).isTrue()
      player2.renderFirstFrame(true)
      waitForIdle()

      assertThat(state.visualState.isPhotoVisible).isFalse()
      assertThat(player1.videoOutput).isNull()
      assertThat(player1.playWhenReady).isTrue()
      assertThat(player2.videoOutput).isInstanceOf(SurfaceView::class.java)
      assertThat(player2.currentMediaItem).isEqualTo(unresolvableMediaItem)
      assertThat(player2.playbackState).isEqualTo(Player.STATE_READY)
      assertThat(player2.playWhenReady).isTrue()

      currentPlayer = null
      waitForIdle()

      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player2.videoOutput).isNull()
      assertThat(player2.playbackState).isEqualTo(Player.STATE_READY)
      assertThat(player2.playWhenReady).isTrue()
    }

  @Test
  fun motionPhoto_whenPlayMotionCalledAfterFirstFrameRendered_crossfadesOutOverTransitionDuration() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 0)
      lateinit var state: MotionPhotoState
      lateinit var composeView: View
      setContent {
        composeView = LocalView.current
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = unresolvableMediaItem,
            initialShowMotion = false,
            initialConfig = MotionPhotoConfig(transitionDurationMs = 800L),
          )
        MotionPhoto(
          state = state,
          modifier = Modifier.size(100.dp),
          photo = { Box(modifier = Modifier.size(100.dp).testTag(PHOTO_SLOT_TAG)) },
        )
      }
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()
      val photoSemanticsId = onNodeWithTag(PHOTO_SLOT_TAG).fetchSemanticsNode().id
      mainClock.autoAdvance = false

      state.playMotion()
      mainClock.advanceTimeByFrame()
      waitForIdle()
      mainClock.advanceTimeBy(600L)
      waitForIdle()

      // At 600ms into the configured 800ms crossfade, the photo overlay is still partially visible.
      assertThat(
          composeView.accessibilityNodeProvider
            .createAccessibilityNodeInfo(photoSemanticsId)!!
            .isVisibleToUser
        )
        .isTrue()

      mainClock.advanceTimeBy(250L)
      waitForIdle()

      // Once the 800ms crossfade completes, alpha reaches 0f and the photo overlay is hidden.
      assertThat(
          composeView.accessibilityNodeProvider
            .createAccessibilityNodeInfo(photoSemanticsId)!!
            .isVisibleToUser
        )
        .isFalse()
    }

  @Test
  fun motionPhoto_withZeroTransitionDurationAndPlayMotionCalled_hidesPhotoImmediately() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 0)
      lateinit var state: MotionPhotoState
      lateinit var composeView: View
      setContent {
        composeView = LocalView.current
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = unresolvableMediaItem,
            initialShowMotion = false,
            initialConfig = MotionPhotoConfig(transitionDurationMs = 0L),
          )
        MotionPhoto(
          state = state,
          modifier = Modifier.size(100.dp),
          photo = { Box(modifier = Modifier.size(100.dp).testTag(PHOTO_SLOT_TAG)) },
        )
      }
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()
      val photoSemanticsId = onNodeWithTag(PHOTO_SLOT_TAG).fetchSemanticsNode().id
      mainClock.autoAdvance = false

      state.playMotion()
      mainClock.advanceTimeByFrame()
      waitForIdle()
      mainClock.advanceTimeByFrame()
      mainClock.advanceTimeByFrame()
      waitForIdle()

      assertThat(
          composeView.accessibilityNodeProvider
            .createAccessibilityNodeInfo(photoSemanticsId)!!
            .isVisibleToUser
        )
        .isFalse()
    }

  @Test
  fun motionPhoto_withNullPlayer_doesNotCreateSurfaceView() = runComposeUiTest {
    lateinit var composeView: View

    setContent {
      composeView = LocalView.current
      val state = rememberMotionPhotoState(player = null, mediaItem = unresolvableMediaItem)
      MotionPhoto(
        state = state,
        modifier = Modifier.size(100.dp),
        photo = { Box(modifier = Modifier.size(100.dp).testTag(PHOTO_SLOT_TAG)) },
      )
    }
    waitForIdle()

    assertThat(findViewWithType(composeView, SurfaceView::class.java)).isNull()
  }

  @Test
  fun motionPhoto_withNullPlayerUnderLooseConstraints_fillsBoundedParent() = runComposeUiTest {
    val fileMediaItem = MediaItem.fromUri(createTestImageUri())

    setContent {
      Box(modifier = Modifier.size(100.dp)) {
        val state = rememberMotionPhotoState(player = null, mediaItem = fileMediaItem)
        MotionPhoto(
          state = state,
          contentDescription = PHOTO_CONTENT_DESCRIPTION,
          placeholder = null,
          error = null,
        )
      }
    }
    waitUntil {
      onAllNodesWithContentDescription(PHOTO_CONTENT_DESCRIPTION).fetchSemanticsNodes().isNotEmpty()
    }

    onNodeWithContentDescription(PHOTO_CONTENT_DESCRIPTION)
      .assertIsDisplayed()
      .assertWidthIsEqualTo(100.dp)
      .assertHeightIsEqualTo(100.dp)
  }

  @Test
  fun motionPhoto_whenPlayerAttached_createsSurfaceViewBehindOpaquePhoto() = runComposeUiTest {
    val player = FakePlayer(bufferingDelayMs = 0)
    var currentPlayer by mutableStateOf<Player?>(null)
    lateinit var state: MotionPhotoState
    lateinit var composeView: View
    setContent {
      composeView = LocalView.current
      state = rememberMotionPhotoState(player = currentPlayer, mediaItem = unresolvableMediaItem)
      MotionPhoto(
        state = state,
        modifier = Modifier.size(100.dp),
        photo = { Box(modifier = Modifier.size(100.dp).testTag(PHOTO_SLOT_TAG)) },
      )
    }
    waitForIdle()

    currentPlayer = player
    waitForIdle()

    assertThat(state.visualState.isPhotoVisible).isTrue()
    assertThat(findViewWithType(composeView, SurfaceView::class.java)).isNotNull()
    assertThat(player.videoOutput).isInstanceOf(SurfaceView::class.java)
  }

  @Test
  fun motionPhoto_whenPlayerDetachedWhilePhotoOpaque_removesSurfaceViewAndClearsVideoOutput() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 0)
      var currentPlayer by mutableStateOf<Player?>(player)
      lateinit var composeView: View
      setContent {
        composeView = LocalView.current
        val state =
          rememberMotionPhotoState(
            player = currentPlayer,
            mediaItem = unresolvableMediaItem,
            initialShowMotion = false,
          )
        MotionPhoto(
          state = state,
          modifier = Modifier.size(100.dp),
          photo = { Box(modifier = Modifier.size(100.dp).testTag(PHOTO_SLOT_TAG)) },
        )
      }
      waitForIdle()
      val attachedSurfaceView = findViewWithType(composeView, SurfaceView::class.java)

      currentPlayer = null
      waitForIdle()

      assertThat(attachedSurfaceView).isNotNull()
      assertThat(findViewWithType(composeView, SurfaceView::class.java)).isNull()
      assertThat(player.videoOutput).isNull()
    }

  @Test
  fun motionPhoto_whenPlayerDetachedDuringPlayback_removesSurfaceViewAfterCrossfadeAndClearsVideoOutput() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 0)
      var currentPlayer by mutableStateOf<Player?>(player)
      lateinit var composeView: View
      setContent {
        composeView = LocalView.current
        val state =
          rememberMotionPhotoState(
            player = currentPlayer,
            mediaItem = unresolvableMediaItem,
            initialShowMotion = true,
            initialConfig = MotionPhotoConfig(transitionDurationMs = 200L),
          )
        MotionPhoto(
          state = state,
          modifier = Modifier.size(100.dp),
          photo = { Box(modifier = Modifier.size(100.dp).testTag(PHOTO_SLOT_TAG)) },
        )
      }
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()
      mainClock.autoAdvance = false

      currentPlayer = null
      mainClock.advanceTimeByFrame()
      waitForIdle()
      mainClock.advanceTimeBy(100L)
      waitForIdle()
      val surfaceViewMidCrossfade = findViewWithType(composeView, SurfaceView::class.java)
      val videoOutputMidCrossfade = player.videoOutput
      mainClock.advanceTimeBy(150L)
      mainClock.autoAdvance = true
      waitForIdle()

      assertThat(videoOutputMidCrossfade).isNull()
      assertThat(surfaceViewMidCrossfade).isNotNull()
      assertThat(findViewWithType(composeView, SurfaceView::class.java)).isNull()
      assertThat(player.videoOutput).isNull()
    }

  private fun <T : View> findViewWithType(root: View, clazz: Class<T>): T? {
    if (clazz.isInstance(root)) {
      return clazz.cast(root)
    }
    if (root is ViewGroup) {
      for (i in 0 until root.childCount) {
        findViewWithType(root.getChildAt(i), clazz)?.let {
          return it
        }
      }
    }
    return null
  }

  private fun createTestImageUri(): Uri {
    val bitmap = createBitmap(4, 4)
    Canvas(bitmap).drawColor(Color.RED)
    val stream = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
    val file = tempFolder.newFile("motion_photo.png")
    file.writeBytes(stream.toByteArray())
    return Uri.fromFile(file)
  }

  private class TestPainter : Painter() {
    var isDrawn = false
      private set

    override val intrinsicSize: Size = Size.Unspecified

    override fun DrawScope.onDraw() {
      isDrawn = true
    }
  }

  companion object {
    private const val PHOTO_SLOT_TAG = "photo_slot"
    private const val PHOTO_CONTENT_DESCRIPTION = "Motion photo image"
  }
}
