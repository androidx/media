/*
 * Copyright 2026 The Android Open Source Project
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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.media3.common.util.BitmapLoader
import androidx.media3.test.utils.FakePlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.compose.rememberAsyncImagePainter
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Test
import org.junit.runner.RunWith

/** Unit tests for [Artwork]. */
@Suppress("RedundantNullableReturnType") // Wrong Lint for loadBitmapFromMetadata
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class ArtworkTest {

  @Test
  fun artwork_withArtworkData_displaysBitmapImage() = runComposeUiTest {
    val artworkData = createTestBitmapBytes()
    val mediaMetadata =
      MediaMetadata.Builder()
        .setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        .build()

    setContent {
      Artwork(
        metadata = mediaMetadata,
        contentDescription = "Test Artwork",
        modifier = Modifier.testTag("ArtworkTag"),
        coroutineDispatcher = UnconfinedTestDispatcher(),
      )
    }

    onNodeWithTag("ArtworkTag").assertIsDisplayed().assertContentDescriptionEquals("Test Artwork")
  }

  @Test
  fun artwork_withPlayerAndArtworkData_displaysBitmapImage() = runComposeUiTest {
    val artworkData = createTestBitmapBytes()
    val mediaItem =
      MediaItem.Builder()
        .setMediaMetadata(
          MediaMetadata.Builder()
            .setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .build()
        )
        .build()
    val player: Player =
      FakePlayer(
        playlist = listOf(MediaItemData.Builder(mediaItem.mediaId).setMediaItem(mediaItem).build())
      )

    setContent {
      Artwork(
        player = player,
        contentDescription = "Test Artwork",
        modifier = Modifier.testTag("ArtworkTag"),
        coroutineDispatcher = UnconfinedTestDispatcher(),
      )
    }

    onNodeWithTag("ArtworkTag").assertIsDisplayed().assertContentDescriptionEquals("Test Artwork")
  }

  @Test
  fun artwork_withCoil_displaysImage() = runComposeUiTest {
    val artworkData = createTestBitmapBytes()
    val mediaItem =
      MediaItem.Builder()
        .setMediaMetadata(
          MediaMetadata.Builder()
            .setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .build()
        )
        .build()
    val player: Player =
      FakePlayer(
        playlist = listOf(MediaItemData.Builder(mediaItem.mediaId).setMediaItem(mediaItem).build())
      )

    setContent {
      Artwork(
        player = player,
        contentDescription = "Test Artwork",
        modifier = Modifier.testTag("ArtworkTag"),
      ) {
        rememberAsyncImagePainter(model = mediaMetadata.artworkUri ?: mediaMetadata.artworkData)
      }
    }

    onNodeWithTag("ArtworkTag").assertIsDisplayed().assertContentDescriptionEquals("Test Artwork")
  }

  @Test
  fun artwork_customBitmapLoader_usesCustomLoader() = runComposeUiTest {
    val testBitmap = createBitmap(10, 10)
    val mediaItem = MediaItem.Builder().setMediaMetadata(MediaMetadata.EMPTY).build()
    val player: Player =
      FakePlayer(
        playlist = listOf(MediaItemData.Builder(mediaItem.mediaId).setMediaItem(mediaItem).build())
      )
    val customLoader =
      object : BitmapLoader {
        override fun supportsMimeType(mimeType: String): Boolean = true

        override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
          throw UnsupportedOperationException()

        override fun loadBitmap(uri: android.net.Uri): ListenableFuture<Bitmap> =
          throw UnsupportedOperationException()

        override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? =
          Futures.immediateFuture(testBitmap)
      }

    setContent {
      Artwork(
        player = player,
        contentDescription = "Test Artwork",
        bitmapLoader = customLoader,
        modifier = Modifier.testTag("ArtworkTag"),
        coroutineDispatcher = UnconfinedTestDispatcher(),
      )
    }

    onNodeWithTag("ArtworkTag").assertIsDisplayed().assertContentDescriptionEquals("Test Artwork")
  }

  @Test
  fun artwork_customBitmapLoaderThrows_showsErrorPainter() = runComposeUiTest {
    val artworkData = createTestBitmapBytes()
    val mediaItem =
      MediaItem.Builder()
        .setMediaMetadata(
          MediaMetadata.Builder()
            .setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .build()
        )
        .build()
    val player: Player =
      FakePlayer(
        playlist = listOf(MediaItemData.Builder(mediaItem.mediaId).setMediaItem(mediaItem).build())
      )
    val errorPainter = ColorPainter(androidx.compose.ui.graphics.Color.Red)
    val failingLoader =
      object : BitmapLoader {
        override fun supportsMimeType(mimeType: String): Boolean = true

        override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
          throw UnsupportedOperationException()

        override fun loadBitmap(uri: android.net.Uri): ListenableFuture<Bitmap> =
          throw UnsupportedOperationException()

        override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? =
          Futures.immediateFailedFuture(RuntimeException("Failed to load"))
      }

    setContent {
      Artwork(
        player = player,
        contentDescription = "Test Artwork",
        bitmapLoader = failingLoader,
        error = errorPainter,
        modifier = Modifier.testTag("ArtworkTag"),
        coroutineDispatcher = UnconfinedTestDispatcher(),
      )
    }

    onNodeWithTag("ArtworkTag").assertIsDisplayed().assertContentDescriptionEquals("Test Artwork")
  }

  @Test
  fun artwork_customBitmapLoaderReturnsNull_fallsBackToDefaultLoadArtwork() = runComposeUiTest {
    val artworkData = createTestBitmapBytes()
    val mediaItem =
      MediaItem.Builder()
        .setMediaMetadata(
          MediaMetadata.Builder()
            .setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .build()
        )
        .build()
    val player: Player =
      FakePlayer(
        playlist = listOf(MediaItemData.Builder(mediaItem.mediaId).setMediaItem(mediaItem).build())
      )
    val fallbackPainter = ColorPainter(androidx.compose.ui.graphics.Color.Red)
    val nullLoader =
      object : BitmapLoader {
        override fun supportsMimeType(mimeType: String): Boolean = true

        override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
          throw UnsupportedOperationException()

        override fun loadBitmap(uri: android.net.Uri): ListenableFuture<Bitmap> =
          throw UnsupportedOperationException()

        override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? =
          null
      }

    setContent {
      Artwork(
        player = player,
        contentDescription = "Test Artwork",
        bitmapLoader = nullLoader,
        fallback = fallbackPainter,
        modifier = Modifier.testTag("ArtworkTag"),
        coroutineDispatcher = UnconfinedTestDispatcher(),
      )
    }

    onNodeWithTag("ArtworkTag").assertIsDisplayed().assertContentDescriptionEquals("Test Artwork")
  }

  @Test
  fun artwork_withUnsupportedUriScheme_doesNotLoadBitmap() = runComposeUiTest {
    val mediaMetadata =
      MediaMetadata.Builder().setArtworkUri("https://example.com/image.png".toUri()).build()
    val fallbackPainter = ColorPainter(androidx.compose.ui.graphics.Color.Red)

    setContent {
      Artwork(
        metadata = mediaMetadata,
        fallback = fallbackPainter,
        contentDescription = "Test Artwork",
        modifier = Modifier.testTag("ArtworkTag"),
        coroutineDispatcher = UnconfinedTestDispatcher(),
      )
    }

    onNodeWithTag("ArtworkTag").assertIsDisplayed().assertContentDescriptionEquals("Test Artwork")
  }

  @Test
  fun artwork_withFileUriScheme_loadsBitmap() = runComposeUiTest {
    val artworkData = createTestBitmapBytes()
    val file = File.createTempFile("artwork", ".png")
    file.writeBytes(artworkData)
    val mediaMetadata = MediaMetadata.Builder().setArtworkUri(Uri.fromFile(file)).build()

    setContent {
      Artwork(
        metadata = mediaMetadata,
        contentDescription = "Test Artwork",
        modifier = Modifier.testTag("ArtworkTag"),
        coroutineDispatcher = UnconfinedTestDispatcher(),
      )
    }

    onNodeWithTag("ArtworkTag").assertIsDisplayed().assertContentDescriptionEquals("Test Artwork")
  }

  private fun createTestBitmapBytes(): ByteArray {
    val bitmap = createBitmap(10, 10)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.RED)
    val stream = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
    return stream.toByteArray()
  }

  @Test
  fun artwork_withUnsupportedUriScheme_isRejectedEarly() = runComposeUiTest {
    val uri = Uri.parse("custom://example.com/image.png")
    val mediaMetadata = MediaMetadata.Builder().setArtworkUri(uri).build()

    setContent {
      Artwork(
        metadata = mediaMetadata,
        contentDescription = "Test Artwork",
        modifier = Modifier.testTag("ArtworkTag"),
        coroutineDispatcher = UnconfinedTestDispatcher(),
      )
    }

    // Because the custom scheme is unsupported, it should be rejected early and return null.
    // With no fallback painter provided, this results in a Box without a content description.
    onNode(
        hasTestTag("ArtworkTag") and hasContentDescription("Test Artwork"),
        useUnmergedTree = true,
      )
      .assertDoesNotExist()

    onNodeWithTag("ArtworkTag", useUnmergedTree = true).assertExists()
  }
}
