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
import android.os.Build
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.test.utils.FakeBitmapLoader
import androidx.media3.test.utils.UriOnlyBitmapLoader
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.SettableFuture
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowBitmapFactory

/** Unit tests for [AsyncImage]. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class AsyncImageTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  // The painters and bitmaps have distinct sizes, so the size of the displayed image identifies
  // which one is shown.
  private val placeholder = BitmapPainter(ImageBitmap(PLACEHOLDER_SIZE_PX, PLACEHOLDER_SIZE_PX))
  private val error = BitmapPainter(ImageBitmap(ERROR_SIZE_PX, ERROR_SIZE_PX))
  private val fallback = BitmapPainter(ImageBitmap(FALLBACK_SIZE_PX, FALLBACK_SIZE_PX))

  @Test
  fun asyncImage_withBytes_displaysDecodedImage() = runComposeUiTest {
    val imageBytes = createTestImageBytes()

    setContent {
      AsyncImage(
        source = ImageSource.Bytes(imageBytes),
        modifier = Modifier.wrapContentSize().testTag(TAG),
        placeholder = placeholder,
        error = error,
        fallback = fallback,
        loadingContext = UnconfinedTestDispatcher(),
      )
    }

    assertDisplayedSizePx(IMAGE_SIZE_PX)
  }

  @Test
  fun asyncImage_withFileUri_displaysDecodedImage() = runComposeUiTest {
    val imageBytes = createTestImageBytes()
    val file = temporaryFolder.newFile("image.png").apply { writeBytes(imageBytes) }

    setContent {
      AsyncImage(
        source = ImageSource.Uri(Uri.fromFile(file)),
        modifier = Modifier.wrapContentSize().testTag(TAG),
        placeholder = placeholder,
        error = error,
        fallback = fallback,
        loadingContext = UnconfinedTestDispatcher(),
      )
    }

    assertDisplayedSizePx(IMAGE_SIZE_PX)
  }

  @Test
  fun asyncImage_withFilePathWithoutScheme_displaysDecodedImage() = runComposeUiTest {
    val imageBytes = createTestImageBytes()
    val file = temporaryFolder.newFile("image.png").apply { writeBytes(imageBytes) }

    setContent {
      AsyncImage(
        source = ImageSource.Uri(file.absolutePath.toUri()),
        modifier = Modifier.wrapContentSize().testTag(TAG),
        placeholder = placeholder,
        error = error,
        fallback = fallback,
        loadingContext = UnconfinedTestDispatcher(),
      )
    }

    assertDisplayedSizePx(IMAGE_SIZE_PX)
  }

  @Test
  fun asyncImage_withContentUri_displaysDecodedImage() = runComposeUiTest {
    val imageBytes = createTestImageBytes()
    val uri = "content://androidx.media3.ui.compose.test/image.png".toUri()
    registerImage(uri, imageBytes)

    setContent {
      AsyncImage(
        source = ImageSource.Uri(uri),
        modifier = Modifier.wrapContentSize().testTag(TAG),
        placeholder = placeholder,
        error = error,
        fallback = fallback,
        loadingContext = UnconfinedTestDispatcher(),
      )
    }

    assertDisplayedSizePx(IMAGE_SIZE_PX)
  }

  @Test
  fun asyncImage_withAndroidResourceUri_displaysDecodedImage() = runComposeUiTest {
    val imageBytes = createTestImageBytes()
    val uri = "android.resource://androidx.media3.ui.compose.test/drawable/image".toUri()
    registerImage(uri, imageBytes)

    setContent {
      AsyncImage(
        source = ImageSource.Uri(uri),
        modifier = Modifier.wrapContentSize().testTag(TAG),
        placeholder = placeholder,
        error = error,
        fallback = fallback,
        loadingContext = UnconfinedTestDispatcher(),
      )
    }

    assertDisplayedSizePx(IMAGE_SIZE_PX)
  }

  @Test
  fun asyncImage_withNullSource_showsFallbackPainterOnFirstFrame() = runComposeUiTest {
    mainClock.autoAdvance = false

    setContent {
      AsyncImage(
        source = null,
        modifier = Modifier.wrapContentSize().testTag(TAG),
        placeholder = placeholder,
        error = error,
        fallback = fallback,
      )
    }

    assertDisplayedSizePx(FALLBACK_SIZE_PX)
  }

  @Test
  fun asyncImage_withUnsupportedUriSchemeAndDefaultLoader_showsErrorPainter() = runComposeUiTest {
    setContent {
      AsyncImage(
        source = ImageSource.Uri("custom://example.com/image.png".toUri()),
        modifier = Modifier.wrapContentSize().testTag(TAG),
        placeholder = placeholder,
        error = error,
        fallback = fallback,
        loadingContext = UnconfinedTestDispatcher(),
      )
    }

    assertDisplayedSizePx(ERROR_SIZE_PX)
  }

  @Test
  fun asyncImage_withMissingFileAndDefaultLoader_showsErrorPainter() = runComposeUiTest {
    val file = File(temporaryFolder.root, "missing.png")

    setContent {
      AsyncImage(
        source = ImageSource.Uri(Uri.fromFile(file)),
        modifier = Modifier.wrapContentSize().testTag(TAG),
        placeholder = placeholder,
        error = error,
        fallback = fallback,
        loadingContext = UnconfinedTestDispatcher(),
      )
    }

    assertDisplayedSizePx(ERROR_SIZE_PX)
  }

  @Test
  fun asyncImage_withUndecodableBytesAndDefaultLoader_showsErrorPainter() = runComposeUiTest {
    // By default on API < 26, Robolectric's BitmapFactory returns a placeholder bitmap for invalid
    // data. Make it return null like on a device.
    if (Build.VERSION.SDK_INT < 26) {
      ShadowBitmapFactory.setAllowInvalidImageData(false)
    }

    setContent {
      AsyncImage(
        source = ImageSource.Bytes(byteArrayOf(1, 2, 3)),
        modifier = Modifier.wrapContentSize().testTag(TAG),
        placeholder = placeholder,
        error = error,
        fallback = fallback,
        loadingContext = UnconfinedTestDispatcher(),
      )
    }

    assertDisplayedSizePx(ERROR_SIZE_PX)
  }

  @Test
  fun asyncImage_whileLoading_showsPlaceholderThenLoadedImage() = runComposeUiTest {
    val imageFuture = SettableFuture.create<Bitmap>()
    val loader = FakeBitmapLoader(imageFuture)

    setContent {
      AsyncImage(
        source = ImageSource.Uri(FIRST_URI),
        modifier = Modifier.wrapContentSize().testTag(TAG),
        bitmapLoader = loader,
        placeholder = placeholder,
        error = error,
        fallback = fallback,
      )
    }
    waitForIdle()
    val sizeWhileLoading = displayedSizePx()
    imageFuture.set(createBitmap(IMAGE_SIZE_PX, IMAGE_SIZE_PX))

    assertThat(sizeWhileLoading).isEqualTo(PLACEHOLDER_SIZE_PX)
    assertDisplayedSizePx(IMAGE_SIZE_PX)
  }

  @Test
  fun asyncImage_customBitmapLoaderForUri_usesCustomLoaderAndNotLoadingContext() =
    runComposeUiTest {
      val dispatcher = RecordingDispatcher()
      val loader = FakeBitmapLoader(createBitmap(IMAGE_SIZE_PX, IMAGE_SIZE_PX))

      setContent {
        AsyncImage(
          source = ImageSource.Uri(FIRST_URI),
          modifier = Modifier.wrapContentSize().testTag(TAG),
          bitmapLoader = loader,
          placeholder = placeholder,
          error = error,
          fallback = fallback,
          loadingContext = dispatcher,
        )
      }

      assertDisplayedSizePx(IMAGE_SIZE_PX)
      assertThat(dispatcher.dispatched).isFalse()
    }

  @Test
  fun asyncImage_customBitmapLoaderForBytes_usesCustomLoader() = runComposeUiTest {
    val loader = FakeBitmapLoader(createBitmap(IMAGE_SIZE_PX, IMAGE_SIZE_PX))

    setContent {
      AsyncImage(
        source = ImageSource.Bytes(byteArrayOf(1, 2, 3)),
        modifier = Modifier.wrapContentSize().testTag(TAG),
        bitmapLoader = loader,
        placeholder = placeholder,
        error = error,
        fallback = fallback,
      )
    }

    assertDisplayedSizePx(IMAGE_SIZE_PX)
  }

  @Test
  fun asyncImage_customBitmapLoaderFails_showsErrorPainter() = runComposeUiTest {
    val loader = FakeBitmapLoader(RuntimeException("Failed to load"))

    setContent {
      AsyncImage(
        source = ImageSource.Uri(FIRST_URI),
        modifier = Modifier.wrapContentSize().testTag(TAG),
        bitmapLoader = loader,
        placeholder = placeholder,
        error = error,
        fallback = fallback,
      )
    }

    assertDisplayedSizePx(ERROR_SIZE_PX)
  }

  @Test
  fun asyncImage_customBitmapLoaderReturnsCancelledFuture_showsErrorPainter() = runComposeUiTest {
    val imageFuture = SettableFuture.create<Bitmap>()
    val loader = FakeBitmapLoader(imageFuture)

    setContent {
      AsyncImage(
        source = ImageSource.Uri(FIRST_URI),
        modifier = Modifier.wrapContentSize().testTag(TAG),
        bitmapLoader = loader,
        placeholder = placeholder,
        error = error,
        fallback = fallback,
      )
    }
    waitForIdle()
    val sizeWhileLoading = displayedSizePx()
    imageFuture.cancel(/* mayInterruptIfRunning= */ false)

    assertThat(sizeWhileLoading).isEqualTo(PLACEHOLDER_SIZE_PX)
    assertDisplayedSizePx(ERROR_SIZE_PX)
  }

  @Test
  fun asyncImage_sourceChangesToEqualBytes_doesNotReload() = runComposeUiTest {
    val loader = FakeBitmapLoader(createBitmap(IMAGE_SIZE_PX, IMAGE_SIZE_PX))
    var bytes by mutableStateOf(byteArrayOf(1, 2, 3))
    setContent {
      AsyncImage(
        source = ImageSource.Bytes(bytes),
        bitmapLoader = loader,
        modifier = Modifier.testTag(TAG),
      )
    }
    waitForIdle()

    bytes = byteArrayOf(1, 2, 3)
    waitForIdle()
    val decodeCountAfterEqualBytes = loader.decodeBitmapCallCount
    bytes = byteArrayOf(4, 5, 6)
    waitForIdle()

    assertThat(decodeCountAfterEqualBytes).isEqualTo(1)
    assertThat(loader.decodeBitmapCallCount).isEqualTo(2)
  }

  @Test
  fun asyncImage_sourceChanges_showsPlaceholderWhileLoading() = runComposeUiTest {
    val loader = UriOnlyBitmapLoader { uri ->
      when (uri) {
        FIRST_URI -> Futures.immediateFuture(createBitmap(IMAGE_SIZE_PX, IMAGE_SIZE_PX))
        SECOND_URI -> SettableFuture.create()
        else -> Futures.immediateFailedFuture(IOException("Not found"))
      }
    }
    var source by mutableStateOf(ImageSource.Uri(FIRST_URI))
    setContent {
      AsyncImage(
        source = source,
        modifier = Modifier.wrapContentSize().testTag(TAG),
        bitmapLoader = loader,
        placeholder = placeholder,
        error = error,
        fallback = fallback,
      )
    }
    assertDisplayedSizePx(IMAGE_SIZE_PX)

    source = ImageSource.Uri(SECOND_URI)

    assertDisplayedSizePx(PLACEHOLDER_SIZE_PX)
  }

  @Test
  fun asyncImage_sourceChangesWithKeepPreviousWhileLoading_showsPreviousImageUntilLoaded() =
    runComposeUiTest {
      val secondImage = SettableFuture.create<Bitmap>()
      val loader = UriOnlyBitmapLoader { uri ->
        when (uri) {
          FIRST_URI -> Futures.immediateFuture(createBitmap(IMAGE_SIZE_PX, IMAGE_SIZE_PX))
          SECOND_URI -> secondImage
          else -> Futures.immediateFailedFuture(IOException("Not found"))
        }
      }
      var source by mutableStateOf(ImageSource.Uri(FIRST_URI))
      setContent {
        AsyncImage(
          source = source,
          modifier = Modifier.wrapContentSize().testTag(TAG),
          bitmapLoader = loader,
          placeholder = placeholder,
          error = error,
          fallback = fallback,
          keepPreviousWhileLoading = true,
        )
      }
      assertDisplayedSizePx(IMAGE_SIZE_PX)

      source = ImageSource.Uri(SECOND_URI)
      waitForIdle()
      val sizeWhileLoading = displayedSizePx()
      secondImage.set(createBitmap(OTHER_IMAGE_SIZE_PX, OTHER_IMAGE_SIZE_PX))

      assertThat(sizeWhileLoading).isEqualTo(IMAGE_SIZE_PX)
      assertDisplayedSizePx(OTHER_IMAGE_SIZE_PX)
    }

  @Test
  fun asyncImage_keepPreviousWhileLoadingChangesToFalseWhileLoading_showsPlaceholderUntilLoaded() =
    runComposeUiTest {
      val secondImage = SettableFuture.create<Bitmap>()
      val loader = UriOnlyBitmapLoader { uri ->
        when (uri) {
          FIRST_URI -> Futures.immediateFuture(createBitmap(IMAGE_SIZE_PX, IMAGE_SIZE_PX))
          SECOND_URI -> secondImage
          else -> Futures.immediateFailedFuture(IOException("Not found"))
        }
      }
      var source by mutableStateOf(ImageSource.Uri(FIRST_URI))
      var keepPreviousWhileLoading by mutableStateOf(true)
      setContent {
        AsyncImage(
          source = source,
          modifier = Modifier.wrapContentSize().testTag(TAG),
          bitmapLoader = loader,
          placeholder = placeholder,
          error = error,
          fallback = fallback,
          keepPreviousWhileLoading = keepPreviousWhileLoading,
        )
      }
      assertDisplayedSizePx(IMAGE_SIZE_PX)
      source = ImageSource.Uri(SECOND_URI)
      waitForIdle()
      val sizeWhileLoadingWithKeepPrevious = displayedSizePx()

      keepPreviousWhileLoading = false
      waitForIdle()
      val sizeWhileLoadingWithoutKeepPrevious = displayedSizePx()
      secondImage.set(createBitmap(OTHER_IMAGE_SIZE_PX, OTHER_IMAGE_SIZE_PX))

      assertThat(sizeWhileLoadingWithKeepPrevious).isEqualTo(IMAGE_SIZE_PX)
      assertThat(sizeWhileLoadingWithoutKeepPrevious).isEqualTo(PLACEHOLDER_SIZE_PX)
      assertDisplayedSizePx(OTHER_IMAGE_SIZE_PX)
    }

  @Test
  fun asyncImage_sourceChangesWithKeepPreviousWhileLoadingAfterError_keepsErrorUntilLoaded() =
    runComposeUiTest {
      val secondImage = SettableFuture.create<Bitmap>()
      val loader = UriOnlyBitmapLoader { uri ->
        when (uri) {
          FIRST_URI -> Futures.immediateFailedFuture(RuntimeException("Failed to load"))
          SECOND_URI -> secondImage
          else -> Futures.immediateFailedFuture(IOException("Not found"))
        }
      }
      var source by mutableStateOf(ImageSource.Uri(FIRST_URI))
      setContent {
        AsyncImage(
          source = source,
          modifier = Modifier.wrapContentSize().testTag(TAG),
          bitmapLoader = loader,
          placeholder = placeholder,
          error = error,
          fallback = fallback,
          keepPreviousWhileLoading = true,
        )
      }
      assertDisplayedSizePx(ERROR_SIZE_PX)

      source = ImageSource.Uri(SECOND_URI)
      waitForIdle()
      val sizeWhileLoading = displayedSizePx()
      secondImage.set(createBitmap(IMAGE_SIZE_PX, IMAGE_SIZE_PX))

      assertThat(sizeWhileLoading).isEqualTo(ERROR_SIZE_PX)
      assertDisplayedSizePx(IMAGE_SIZE_PX)
    }

  @Test
  fun asyncImage_sourceChangesWithKeepPreviousWhileLoadingAfterFallback_keepsFallbackUntilLoaded() =
    runComposeUiTest {
      val imageFuture = SettableFuture.create<Bitmap>()
      val loader = FakeBitmapLoader(imageFuture)
      var source by mutableStateOf<ImageSource?>(null)
      setContent {
        AsyncImage(
          source = source,
          modifier = Modifier.wrapContentSize().testTag(TAG),
          bitmapLoader = loader,
          placeholder = placeholder,
          error = error,
          fallback = fallback,
          keepPreviousWhileLoading = true,
        )
      }
      assertDisplayedSizePx(FALLBACK_SIZE_PX)

      source = ImageSource.Uri(FIRST_URI)
      waitForIdle()
      val sizeWhileLoading = displayedSizePx()
      imageFuture.set(createBitmap(IMAGE_SIZE_PX, IMAGE_SIZE_PX))

      assertThat(sizeWhileLoading).isEqualTo(FALLBACK_SIZE_PX)
      assertDisplayedSizePx(IMAGE_SIZE_PX)
    }

  @Test
  fun asyncImage_bitmapLoaderChangesToNonNull_showsPlaceholderThenLoadedImage() = runComposeUiTest {
    val imageFuture = SettableFuture.create<Bitmap>()
    val customLoader = FakeBitmapLoader(imageFuture)
    var bitmapLoader by mutableStateOf<BitmapLoader?>(null)
    setContent {
      AsyncImage(
        source = ImageSource.Uri(FIRST_URI),
        modifier = Modifier.wrapContentSize().testTag(TAG),
        bitmapLoader = bitmapLoader,
        placeholder = placeholder,
        error = error,
        fallback = fallback,
        loadingContext = UnconfinedTestDispatcher(),
      )
    }
    assertDisplayedSizePx(ERROR_SIZE_PX)

    bitmapLoader = customLoader
    waitForIdle()
    val sizeWhileLoading = displayedSizePx()
    imageFuture.set(createBitmap(IMAGE_SIZE_PX, IMAGE_SIZE_PX))

    assertThat(sizeWhileLoading).isEqualTo(PLACEHOLDER_SIZE_PX)
    assertDisplayedSizePx(IMAGE_SIZE_PX)
  }

  @Test
  fun asyncImage_bitmapLoaderChangesToNonNullWithKeepPrevious_keepsErrorUntilLoaded() =
    runComposeUiTest {
      val imageFuture = SettableFuture.create<Bitmap>()
      val customLoader = FakeBitmapLoader(imageFuture)
      var bitmapLoader by mutableStateOf<BitmapLoader?>(null)
      setContent {
        AsyncImage(
          source = ImageSource.Uri(FIRST_URI),
          modifier = Modifier.wrapContentSize().testTag(TAG),
          bitmapLoader = bitmapLoader,
          placeholder = placeholder,
          error = error,
          fallback = fallback,
          keepPreviousWhileLoading = true,
          loadingContext = UnconfinedTestDispatcher(),
        )
      }
      assertDisplayedSizePx(ERROR_SIZE_PX)

      bitmapLoader = customLoader
      waitForIdle()
      val sizeWhileLoading = displayedSizePx()
      imageFuture.set(createBitmap(IMAGE_SIZE_PX, IMAGE_SIZE_PX))

      assertThat(sizeWhileLoading).isEqualTo(ERROR_SIZE_PX)
      assertDisplayedSizePx(IMAGE_SIZE_PX)
    }

  @Test
  fun asyncImage_bitmapLoaderChangesToNull_reloadsWithDefaultLoader() = runComposeUiTest {
    val customLoader = FakeBitmapLoader(createBitmap(IMAGE_SIZE_PX, IMAGE_SIZE_PX))
    var bitmapLoader by mutableStateOf<BitmapLoader?>(customLoader)
    setContent {
      AsyncImage(
        source = ImageSource.Uri(FIRST_URI),
        modifier = Modifier.wrapContentSize().testTag(TAG),
        bitmapLoader = bitmapLoader,
        placeholder = placeholder,
        error = error,
        fallback = fallback,
        loadingContext = UnconfinedTestDispatcher(),
      )
    }
    assertDisplayedSizePx(IMAGE_SIZE_PX)

    bitmapLoader = null

    assertDisplayedSizePx(ERROR_SIZE_PX)
  }

  @Test
  fun asyncImage_loadingContextChanges_doesNotReload() = runComposeUiTest {
    val imageBytes = createTestImageBytes()
    val firstDispatcher = RecordingDispatcher()
    val secondDispatcher = RecordingDispatcher()
    var loadingContext: CoroutineContext by mutableStateOf(firstDispatcher)
    setContent {
      AsyncImage(
        source = ImageSource.Bytes(imageBytes),
        modifier = Modifier.wrapContentSize().testTag(TAG),
        placeholder = placeholder,
        error = error,
        fallback = fallback,
        loadingContext = loadingContext,
      )
    }
    assertDisplayedSizePx(IMAGE_SIZE_PX)

    loadingContext = secondDispatcher
    waitForIdle()

    assertThat(firstDispatcher.dispatched).isTrue()
    assertThat(secondDispatcher.dispatched).isFalse()
  }

  private fun ComposeUiTest.displayedSizePx(): Int =
    onNodeWithTag(TAG).fetchSemanticsNode().size.width

  private fun ComposeUiTest.assertDisplayedSizePx(sizePx: Int) {
    with(density) {
      onNodeWithTag(TAG).assertWidthIsEqualTo(sizePx.toDp()).assertHeightIsEqualTo(sizePx.toDp())
    }
  }

  private fun registerImage(uri: Uri, imageBytes: ByteArray) {
    shadowOf(ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver)
      .registerInputStreamSupplier(uri) { ByteArrayInputStream(imageBytes) }
  }

  private fun createTestImageBytes(): ByteArray {
    val bitmap = createBitmap(IMAGE_SIZE_PX, IMAGE_SIZE_PX)
    Canvas(bitmap).drawColor(Color.RED)
    val stream = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
    return stream.toByteArray()
  }

  private class RecordingDispatcher : CoroutineDispatcher() {
    var dispatched = false

    override fun dispatch(context: CoroutineContext, block: Runnable) {
      dispatched = true
      block.run()
    }
  }

  private companion object {
    const val TAG = "AsyncImageTag"
    const val IMAGE_SIZE_PX = 44
    const val OTHER_IMAGE_SIZE_PX = 55
    const val PLACEHOLDER_SIZE_PX = 11
    const val ERROR_SIZE_PX = 22
    const val FALLBACK_SIZE_PX = 33
    val FIRST_URI: Uri = "https://example.com/first.png".toUri()
    val SECOND_URI: Uri = "https://example.com/second.png".toUri()
  }
}
