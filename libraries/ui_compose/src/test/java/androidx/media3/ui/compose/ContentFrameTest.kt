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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.test.utils.FakePlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [ContentFrame]. */
@RunWith(AndroidJUnit4::class)
class ContentFrameTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun contentFrame_withSurfaceViewType_setsSurfaceViewOnPlayer() {
    val player = FakePlayer()

    composeTestRule.setContent {
      ContentFrame(player = player, surfaceType = SURFACE_TYPE_SURFACE_VIEW)
    }

    assertThat(player.videoOutput).isInstanceOf(SurfaceView::class.java)
  }

  @Test
  fun contentFrame_withTextureViewType_setsTextureViewOnPlayer() {
    val player = FakePlayer()

    composeTestRule.setContent {
      ContentFrame(player = player, surfaceType = SURFACE_TYPE_TEXTURE_VIEW)
    }

    assertThat(player.videoOutput).isInstanceOf(TextureView::class.java)
  }

  @Test
  fun contentFrame_withIdlePlayer_shutterShown() {
    val player = FakePlayer()

    composeTestRule.setContent {
      ContentFrame(player) { Box(Modifier.fillMaxSize().testTag("Shutter")) }
    }

    composeTestRule.onNodeWithTag("Shutter").assertIsDisplayed()
  }

  @Test
  fun contentFrame_withFirstFrameRendered_shutterOpens() {
    val player = FakePlayer()
    composeTestRule.setContent {
      ContentFrame(player) { Box(Modifier.fillMaxSize().testTag("Shutter")) }
    }
    composeTestRule.onNodeWithTag("Shutter").assertIsDisplayed()

    player.renderFirstFrame(true)
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("Shutter").assertDoesNotExist()
  }

  @Test
  fun contentFrame_withContentScaleCrop_contentSizeIncreasesAspectRatioMaintained() {
    val player = FakePlayer()
    player.videoSize = VideoSize(360, 480)
    val aspectRatio = player.videoSize.width.toFloat() / player.videoSize.height
    lateinit var contentScale: MutableState<ContentScale>
    composeTestRule.setContent {
      contentScale = remember { mutableStateOf(ContentScale.Fit) }
      ContentFrame(
        player,
        contentScale = contentScale.value,
        modifier = Modifier.testTag("ContentFrameOuter"),
        overlay = { Box(Modifier.fillMaxSize().testTag("ScaledVideoBounds")) },
      )
    }
    val initialBounds = composeTestRule.onNodeWithTag("ScaledVideoBounds").getBoundsInRoot()
    val initialAspectRatio = initialBounds.width / initialBounds.height

    contentScale.value = ContentScale.Crop
    composeTestRule.waitForIdle()

    val croppedBounds = composeTestRule.onNodeWithTag("ScaledVideoBounds").getBoundsInRoot()
    val croppedAspectRatio = croppedBounds.width / croppedBounds.height

    assertThat(croppedBounds).isNotEqualTo(initialBounds)
    assertThat(abs(initialAspectRatio - aspectRatio) / aspectRatio).isLessThan(0.01f)
    assertThat(abs(croppedAspectRatio - aspectRatio) / aspectRatio).isLessThan(0.01f)
  }

  @Test
  fun contentFrame_withAudioOnlyTrack_showsArtwork() {
    val audioTrack =
      Tracks.Group(
        TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build()),
        /* adaptiveSupported= */ true,
        /* trackSupport= */ intArrayOf(C.FORMAT_HANDLED),
        /* trackSelected= */ booleanArrayOf(true),
      )
    val player =
      FakePlayer(
        playlist =
          listOf(MediaItemData.Builder("First").setTracks(Tracks(listOf(audioTrack))).build())
      )

    composeTestRule.setContent {
      ContentFrame(player, artwork = { Box(Modifier.fillMaxSize().testTag("ArtworkTag")) })
    }

    composeTestRule.onNodeWithTag("ArtworkTag").assertIsDisplayed()
  }

  @Test
  fun contentFrame_withVideoTrack_hidesArtwork() {
    val videoTrack =
      Tracks.Group(
        TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()),
        /* adaptiveSupported= */ true,
        /* trackSupport= */ intArrayOf(C.FORMAT_HANDLED),
        /* trackSelected= */ booleanArrayOf(true),
      )
    val player =
      FakePlayer(
        playlist =
          listOf(MediaItemData.Builder("First").setTracks(Tracks(listOf(videoTrack))).build())
      )

    composeTestRule.setContent {
      ContentFrame(player, artwork = { Box(Modifier.fillMaxSize().testTag("ArtworkTag")) })
    }

    composeTestRule.onNodeWithTag("ArtworkTag").assertDoesNotExist()
  }

  @Test
  fun contentFrame_withUnselectedVideoTrack_showsArtwork() {
    val videoTrack =
      Tracks.Group(
        TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()),
        /* adaptiveSupported= */ true,
        /* trackSupport= */ intArrayOf(C.FORMAT_HANDLED),
        /* trackSelected= */ booleanArrayOf(false),
      )
    val player =
      FakePlayer(
        playlist =
          listOf(MediaItemData.Builder("First").setTracks(Tracks(listOf(videoTrack))).build())
      )

    composeTestRule.setContent {
      ContentFrame(player, artwork = { Box(Modifier.fillMaxSize().testTag("ArtworkTag")) })
    }

    composeTestRule.onNodeWithTag("ArtworkTag").assertIsDisplayed()
  }

  @Test
  fun contentFrame_withNullArtworkParam_doesNotDisplayArtwork() {
    val audioTrack =
      Tracks.Group(
        TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build()),
        /* adaptiveSupported= */ true,
        /* trackSupport= */ intArrayOf(C.FORMAT_HANDLED),
        /* trackSelected= */ booleanArrayOf(true),
      )
    val player =
      FakePlayer(
        playlist =
          listOf(MediaItemData.Builder("First").setTracks(Tracks(listOf(audioTrack))).build())
      )

    composeTestRule.setContent { ContentFrame(player) }

    composeTestRule.onNodeWithTag("ArtworkTag").assertDoesNotExist()
  }

  @Test
  fun contentFrame_withNoTracks_doesNotDisplayArtwork() {
    val audioTrack =
      Tracks.Group(
        TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build()),
        /* adaptiveSupported= */ true,
        /* trackSupport= */ intArrayOf(C.FORMAT_HANDLED),
        /* trackSelected= */ booleanArrayOf(true),
      )
    val player =
      FakePlayer(
        playlist =
          listOf(MediaItemData.Builder("First").setTracks(Tracks(listOf(audioTrack))).build())
      )

    composeTestRule.setContent {
      ContentFrame(player, artwork = { Box(Modifier.fillMaxSize().testTag("ArtworkTag")) })
    }

    composeTestRule.onNodeWithTag("ArtworkTag").assertIsDisplayed()

    player.setMediaItems(emptyList())
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("ArtworkTag").assertDoesNotExist()
  }
}
