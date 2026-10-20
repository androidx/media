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
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
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
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.ImageLoader
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.size.Size
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Unit tests for [Artwork]. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class ArtworkTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  // The painters and bitmaps have distinct sizes, so the size of the displayed image identifies
  // which one is shown.
  private val placeholder = BitmapPainter(ImageBitmap(PLACEHOLDER_SIZE_PX, PLACEHOLDER_SIZE_PX))
  private val error = BitmapPainter(ImageBitmap(ERROR_SIZE_PX, ERROR_SIZE_PX))
  private val fallback = BitmapPainter(ImageBitmap(FALLBACK_SIZE_PX, FALLBACK_SIZE_PX))

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
        modifier = Modifier.testTag(TAG),
        loadingContext = UnconfinedTestDispatcher(),
      )
    }

    onNodeWithTag(TAG).assertIsDisplayed().assertContentDescriptionEquals("Test Artwork")
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
        modifier = Modifier.testTag(TAG),
        loadingContext = UnconfinedTestDispatcher(),
      )
    }

    onNodeWithTag(TAG).assertIsDisplayed().assertContentDescriptionEquals("Test Artwork")
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

    val imageLoader =
      ImageLoader.Builder(ApplicationProvider.getApplicationContext())
        .dispatcher(UnconfinedTestDispatcher())
        .build()

    setContent {
      Artwork(
        player = player,
        contentDescription = "Test Artwork",
        modifier = Modifier.wrapContentSize().testTag(TAG),
      ) {
        rememberAsyncImagePainter(
          // Without an explicit size, Coil waits for the image to be drawn to resolve the size to
          // load at, which doesn't happen in Robolectric.
          model =
            ImageRequest.Builder(LocalContext.current)
              .data(mediaMetadata.artworkUri ?: mediaMetadata.artworkData)
              .size(Size.ORIGINAL)
              .build(),
          imageLoader = imageLoader,
        )
      }
    }

    assertDisplayedSizePx(ARTWORK_SIZE_PX)
    onNodeWithTag(TAG).assertContentDescriptionEquals("Test Artwork")
  }

  @Test
  fun artwork_customBitmapLoaderForArtworkUri_usesLoadBitmap() = runComposeUiTest {
    val mediaItem =
      MediaItem.Builder()
        .setMediaMetadata(
          MediaMetadata.Builder().setArtworkUri("https://example.com/image.png".toUri()).build()
        )
        .build()
    val player: Player =
      FakePlayer(
        playlist = listOf(MediaItemData.Builder(mediaItem.mediaId).setMediaItem(mediaItem).build())
      )
    val customLoader =
      object : BitmapLoader {
        override fun supportsMimeType(mimeType: String): Boolean = true

        override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
          throw UnsupportedOperationException()

        override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
          Futures.immediateFuture(createBitmap(ARTWORK_SIZE_PX, ARTWORK_SIZE_PX))
      }

    setContent {
      Artwork(
        player = player,
        bitmapLoader = customLoader,
        placeholder = placeholder,
        error = error,
        fallback = fallback,
        modifier = Modifier.wrapContentSize().testTag(TAG),
      )
    }

    assertDisplayedSizePx(ARTWORK_SIZE_PX)
  }

  @Test
  fun artwork_customBitmapLoaderFails_showsErrorPainter() = runComposeUiTest {
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
    val failingLoader =
      object : BitmapLoader {
        override fun supportsMimeType(mimeType: String): Boolean = true

        override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
          Futures.immediateFailedFuture(RuntimeException("Failed to load"))

        override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
          throw UnsupportedOperationException()
      }

    setContent {
      Artwork(
        player = player,
        bitmapLoader = failingLoader,
        placeholder = placeholder,
        error = error,
        fallback = fallback,
        modifier = Modifier.wrapContentSize().testTag(TAG),
      )
    }

    assertDisplayedSizePx(ERROR_SIZE_PX)
  }

  @Test
  fun artwork_withArtworkDataAndUri_loadsArtworkData() = runComposeUiTest {
    val mediaMetadata =
      MediaMetadata.Builder()
        .setArtworkData(byteArrayOf(1, 2, 3), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        .setArtworkUri("https://example.com/image.png".toUri())
        .build()
    val customLoader =
      object : BitmapLoader {
        override fun supportsMimeType(mimeType: String): Boolean = true

        override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
          Futures.immediateFuture(createBitmap(ARTWORK_SIZE_PX, ARTWORK_SIZE_PX))

        override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
          Futures.immediateFuture(createBitmap(OTHER_ARTWORK_SIZE_PX, OTHER_ARTWORK_SIZE_PX))
      }

    setContent {
      Artwork(
        metadata = mediaMetadata,
        bitmapLoader = customLoader,
        placeholder = placeholder,
        error = error,
        fallback = fallback,
        modifier = Modifier.wrapContentSize().testTag(TAG),
      )
    }

    assertDisplayedSizePx(ARTWORK_SIZE_PX)
  }

  @Test
  fun artwork_artworkChanges_keepsPreviousArtworkForOneSecondThenShowsPlaceholder() =
    runComposeUiTest {
      val firstArtworkData = byteArrayOf(1)
      val secondArtwork = SettableFuture.create<Bitmap>()
      val customLoader =
        object : BitmapLoader {
          override fun supportsMimeType(mimeType: String): Boolean = true

          override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
            if (data.contentEquals(firstArtworkData)) {
              Futures.immediateFuture(createBitmap(ARTWORK_SIZE_PX, ARTWORK_SIZE_PX))
            } else {
              secondArtwork
            }

          override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
            throw UnsupportedOperationException()
        }
      var mediaMetadata by
        mutableStateOf(
          MediaMetadata.Builder()
            .setArtworkData(firstArtworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .build()
        )
      setContent {
        Artwork(
          metadata = mediaMetadata,
          bitmapLoader = customLoader,
          placeholder = placeholder,
          error = error,
          fallback = fallback,
          modifier = Modifier.wrapContentSize().testTag(TAG),
        )
      }
      assertDisplayedSizePx(ARTWORK_SIZE_PX)

      mediaMetadata =
        MediaMetadata.Builder()
          .setArtworkData(byteArrayOf(2), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
          .build()
      waitForIdle()
      mainClock.advanceTimeBy(800)
      val sizeBeforeOneSecond = displayedSizePx()
      mainClock.advanceTimeBy(300)
      val sizeAfterOneSecond = displayedSizePx()
      secondArtwork.set(createBitmap(OTHER_ARTWORK_SIZE_PX, OTHER_ARTWORK_SIZE_PX))

      assertThat(sizeBeforeOneSecond).isEqualTo(ARTWORK_SIZE_PX)
      assertThat(sizeAfterOneSecond).isEqualTo(PLACEHOLDER_SIZE_PX)
      assertDisplayedSizePx(OTHER_ARTWORK_SIZE_PX)
    }

  @Test
  fun artwork_withUnsupportedUriSchemeAndDefaultLoader_showsErrorPainter() = runComposeUiTest {
    val mediaMetadata =
      MediaMetadata.Builder().setArtworkUri("https://example.com/image.png".toUri()).build()

    setContent {
      Artwork(
        metadata = mediaMetadata,
        placeholder = placeholder,
        error = error,
        fallback = fallback,
        modifier = Modifier.wrapContentSize().testTag(TAG),
        loadingContext = UnconfinedTestDispatcher(),
      )
    }

    assertDisplayedSizePx(ERROR_SIZE_PX)
  }

  @Test
  fun artwork_withoutArtwork_showsFallbackPainter() = runComposeUiTest {
    setContent {
      Artwork(
        metadata = MediaMetadata.EMPTY,
        placeholder = placeholder,
        error = error,
        fallback = fallback,
        modifier = Modifier.wrapContentSize().testTag(TAG),
      )
    }

    assertDisplayedSizePx(FALLBACK_SIZE_PX)
  }

  @Test
  fun artwork_withFileUriScheme_loadsBitmap() = runComposeUiTest {
    val artworkData = createTestBitmapBytes()
    val file = temporaryFolder.newFile("artwork.png").apply { writeBytes(artworkData) }
    val mediaMetadata = MediaMetadata.Builder().setArtworkUri(Uri.fromFile(file)).build()

    setContent {
      Artwork(
        metadata = mediaMetadata,
        contentDescription = "Test Artwork",
        modifier = Modifier.testTag(TAG),
        loadingContext = UnconfinedTestDispatcher(),
      )
    }

    onNodeWithTag(TAG).assertIsDisplayed().assertContentDescriptionEquals("Test Artwork")
  }

  private fun createTestBitmapBytes(): ByteArray {
    val bitmap = createBitmap(ARTWORK_SIZE_PX, ARTWORK_SIZE_PX)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.RED)
    val stream = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
    return stream.toByteArray()
  }

  private fun ComposeUiTest.displayedSizePx(): Int =
    onNodeWithTag(TAG).fetchSemanticsNode().size.width

  private fun ComposeUiTest.assertDisplayedSizePx(sizePx: Int) {
    with(density) {
      onNodeWithTag(TAG).assertWidthIsEqualTo(sizePx.toDp()).assertHeightIsEqualTo(sizePx.toDp())
    }
  }

  private companion object {
    const val TAG = "ArtworkTag"
    const val ARTWORK_SIZE_PX = 44
    const val OTHER_ARTWORK_SIZE_PX = 55
    const val PLACEHOLDER_SIZE_PX = 11
    const val ERROR_SIZE_PX = 22
    const val FALLBACK_SIZE_PX = 33
  }
}
