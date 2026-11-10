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

import android.app.Instrumentation
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.Rect
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.Util.isRunningOnEmulator
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.AssetInfo
import androidx.media3.test.utils.awaitFirstFrameRendered
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.graphics.HardwareRendererCompat
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Instrumentation tests for [PlayerSurface] and [ContentFrame]. */
@RunWith(AndroidJUnit4::class)
class PlayerSurfaceAndroidTest {

  private val enableHardwareDrawingRule =
    object : ExternalResource() {
      private var wasDrawingEnabled = true

      override fun before() {
        wasDrawingEnabled = HardwareRendererCompat.isDrawingEnabled()
        HardwareRendererCompat.setDrawingEnabled(true)
      }

      override fun after() {
        HardwareRendererCompat.setDrawingEnabled(wasDrawingEnabled)
      }
    }

  private val activityScenarioRule = ActivityScenarioRule(TestActivity::class.java)

  @get:Rule
  val ruleChain: RuleChain =
    RuleChain.outerRule(enableHardwareDrawingRule).around(activityScenarioRule)

  private var player: ExoPlayer? = null
  private var lastScreenshot: Bitmap? = null

  @After
  fun tearDown() {
    lastScreenshot?.recycle()
    lastScreenshot = null
    runBlocking(Dispatchers.Main) {
      player?.release()
      player = null
    }
  }

  @Test
  fun playerSurface_withSurfaceViewInsideMaterialSurface_preservesSurroundingBackgroundColor() =
    runBlocking {
      val videoBounds = AtomicReference(Rect())
      val backgroundProbeBounds = AtomicReference(Rect())
      val exoPlayer =
        setUpVideoScreen(backgroundProbeBounds) { attachedPlayer ->
          Box(
            modifier =
              Modifier.size(width = 120.dp, height = 90.dp).onGloballyPositioned { coordinates ->
                videoBounds.set(coordinates.screenBounds())
              }
          ) {
            PlayerSurface(
              player = attachedPlayer,
              modifier = Modifier.fillMaxSize(),
              surfaceType = SURFACE_TYPE_SURFACE_VIEW,
            )
          }
        }

      val screenshot = playSampleVideoAndCaptureScreenshot(exoPlayer, videoBounds::get)

      assertThat(hasCompositedVideoContent(screenshot, videoBounds.get())).isTrue()
      assertRegionCenterIsWhite(screenshot, backgroundProbeBounds.get())
    }

  // Before API 26 (where SurfaceView switched from a WindowManager sub-window to a child
  // SurfaceControl with setMatrix), SurfaceView does not scale its underlying surface buffer when
  // an ancestor RenderNode/graphicsLayer applies a scale matrix without changing layout bounds.
  @SdkSuppress(minSdkVersion = 26)
  @Test
  fun playerSurface_withZoomedGraphicsLayerInsideMaterialSurface_rendersZoomedVideoAndPreservesBackgroundColor() =
    runBlocking {
      val layoutBounds = AtomicReference(Rect())
      val zoomedVisualBounds = AtomicReference(Rect())
      val backgroundProbeBounds = AtomicReference(Rect())
      val exoPlayer =
        setUpVideoScreen(backgroundProbeBounds) { attachedPlayer ->
          Box(
            modifier =
              Modifier.size(width = 120.dp, height = 90.dp).onGloballyPositioned { coordinates ->
                zoomedVisualBounds.set(coordinates.screenBounds())
              }
          ) {
            Box(
              modifier =
                Modifier.size(width = 80.dp, height = 60.dp).onGloballyPositioned { coordinates ->
                  layoutBounds.set(coordinates.screenBounds())
                }
            ) {
              PlayerSurface(
                player = attachedPlayer,
                modifier =
                  Modifier.fillMaxSize().graphicsLayer {
                    scaleX = 1.5f
                    scaleY = 1.5f
                    transformOrigin = TransformOrigin(0f, 0f)
                  },
                surfaceType = SURFACE_TYPE_SURFACE_VIEW,
              )
            }
          }
        }
      val overflowBounds = {
        val layout = layoutBounds.get()
        val zoomed = zoomedVisualBounds.get()
        if (!layout.isEmpty && !zoomed.isEmpty) {
          Rect(layout.right, layout.bottom, zoomed.right, zoomed.bottom)
        } else {
          Rect()
        }
      }

      val screenshot = playSampleVideoAndCaptureScreenshot(exoPlayer, overflowBounds)

      assertThat(hasCompositedVideoContent(screenshot, overflowBounds())).isTrue()
      assertRegionCenterIsWhite(screenshot, backgroundProbeBounds.get())
    }

  @Test
  fun contentFrame_withLetterboxedSurfaceViewInsideMaterialSurface_preservesLetterboxAndSurroundingBackgroundColor() =
    runBlocking {
      val frameBounds = AtomicReference(Rect())
      val videoBounds = AtomicReference(Rect())
      val backgroundProbeBounds = AtomicReference(Rect())
      val exoPlayer =
        setUpVideoScreen(backgroundProbeBounds) { attachedPlayer ->
          ContentFrame(
            player = attachedPlayer,
            modifier =
              Modifier.size(width = 120.dp, height = 150.dp).onGloballyPositioned { coordinates ->
                frameBounds.set(coordinates.screenBounds())
              },
            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
            subtitles = {
              Box(
                Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
                  videoBounds.set(coordinates.screenBounds())
                }
              )
            },
          )
        }
      val letterboxedVideoBounds = {
        val outer = frameBounds.get()
        val inner = videoBounds.get()
        if (
          !outer.isEmpty && !inner.isEmpty && inner.top > outer.top && inner.bottom < outer.bottom
        ) {
          inner
        } else {
          Rect()
        }
      }

      val screenshot = playSampleVideoAndCaptureScreenshot(exoPlayer, letterboxedVideoBounds)

      val outerBounds = frameBounds.get()
      val innerVideoBounds = letterboxedVideoBounds()
      assertThat(hasCompositedVideoContent(screenshot, innerVideoBounds)).isTrue()
      assertRegionCenterIsWhite(
        screenshot,
        Rect(outerBounds.left, outerBounds.top, outerBounds.right, innerVideoBounds.top),
      )
      assertRegionCenterIsWhite(
        screenshot,
        Rect(outerBounds.left, innerVideoBounds.bottom, outerBounds.right, outerBounds.bottom),
      )
      assertRegionCenterIsWhite(screenshot, backgroundProbeBounds.get())
    }

  @Test
  fun contentFrame_withSurfaceViewAndCropScaleInsideMaterialSurface_clipsOverflowAndPreservesBackgroundColor() =
    runBlocking {
      val videoBounds = AtomicReference(Rect())
      val backgroundProbeBounds = AtomicReference(Rect())
      val exoPlayer =
        setUpVideoScreen(backgroundProbeBounds) { attachedPlayer ->
          ContentFrame(
            player = attachedPlayer,
            modifier =
              Modifier.size(width = 160.dp, height = 60.dp).onGloballyPositioned { coordinates ->
                videoBounds.set(coordinates.screenBounds())
              },
            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
            contentScale = ContentScale.Crop,
          )
        }

      val screenshot = playSampleVideoAndCaptureScreenshot(exoPlayer, videoBounds::get)

      assertThat(hasCompositedVideoContent(screenshot, videoBounds.get())).isTrue()
      assertRegionCenterIsWhite(screenshot, backgroundProbeBounds.get())
    }

  private suspend fun setUpVideoScreen(
    backgroundProbeBounds: AtomicReference<Rect>,
    videoContent: @Composable (Player) -> Unit,
  ): ExoPlayer {
    lateinit var exoPlayer: ExoPlayer
    activityScenarioRule.scenario.onActivity { activity ->
      exoPlayer = ExoPlayer.Builder(activity).build().apply { repeatMode = Player.REPEAT_MODE_ONE }
      player = exoPlayer
      activity.setContent {
        MaterialTheme {
          Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
            Box(
              modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
              contentAlignment = Alignment.Center,
            ) {
              Column(
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                videoContent(exoPlayer)
                Box(
                  modifier =
                    Modifier.size(width = 160.dp, height = 20.dp).onGloballyPositioned { coordinates
                      ->
                      backgroundProbeBounds.set(coordinates.screenBounds())
                    }
                )
              }
            }
          }
        }
      }
    }
    withContext(AndroidUiDispatcher.Main) { repeat(2) { withFrameNanos {} } }
    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    return exoPlayer
  }

  private suspend fun playSampleVideoAndCaptureScreenshot(
    exoPlayer: ExoPlayer,
    requiredVideoRegion: () -> Rect,
  ): Bitmap {
    try {
      withContext(AndroidUiDispatcher.Main) {
        val firstFrameDeferred =
          async(start = CoroutineStart.UNDISPATCHED) { exoPlayer.awaitFirstFrameRendered() }
        exoPlayer.setMediaItem(
          MediaItem.fromUri(AssetInfo.MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S.uri)
        )
        exoPlayer.playWhenReady = true
        exoPlayer.prepare()
        firstFrameDeferred.await()
      }
    } catch (e: TimeoutCancellationException) {
      assumeActivityHasWindowFocus()
      throw e
    }

    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val deadlineMs = SystemClock.elapsedRealtime() + SCREENSHOT_POLL_TIMEOUT_MS
    while (SystemClock.elapsedRealtime() < deadlineMs) {
      withContext(AndroidUiDispatcher.Main) { withFrameNanos {} }
      instrumentation.waitForIdleSync()
      val candidate = takeScreenshotOrNull(instrumentation) ?: continue
      lastScreenshot?.recycle()
      lastScreenshot = candidate
      if (hasCompositedVideoContent(candidate, requiredVideoRegion())) {
        return candidate
      }
    }
    assumeActivityHasWindowFocus()
    return checkNotNull(lastScreenshot) {
      "UiAutomation.takeScreenshot() returned null until timeout"
    }
  }

  private fun takeScreenshotOrNull(instrumentation: Instrumentation): Bitmap? =
    try {
      instrumentation.uiAutomation.takeScreenshot()
    } catch (e: NullPointerException) {
      // On API 34+, UiAutomation.takeScreenshot() throws NPE when SurfaceFlinger transiently
      // returns a null ScreenshotHardwareBuffer instead of returning null.
      null
    }

  private fun assumeActivityHasWindowFocus() {
    var hasWindowFocus = true
    activityScenarioRule.scenario.onActivity { activity ->
      hasWindowFocus = activity.hasWindowFocus()
    }
    assumeTrue("TestActivity lost window focus before screenshot capture", hasWindowFocus)
  }

  private fun assertRegionCenterIsWhite(bitmap: Bitmap, bounds: Rect) {
    assertThat(bounds.isEmpty).isFalse()
    assertThat(bitmap.getPixel(bounds.centerX(), bounds.centerY())).isEqualTo(AndroidColor.WHITE)
  }

  private fun hasCompositedVideoContent(bitmap: Bitmap, bounds: Rect): Boolean {
    if (bounds.width() <= 4 || bounds.height() <= 4) {
      return false
    }
    val insetX = max(2, bounds.width() / 6)
    val insetY = max(2, bounds.height() / 6)
    val left = (bounds.left + insetX).coerceIn(0, bitmap.width - 1)
    val right = (bounds.right - insetX).coerceIn(left, bitmap.width - 1)
    val top = (bounds.top + insetY).coerceIn(0, bitmap.height - 1)
    val bottom = (bounds.bottom - insetY).coerceIn(top, bitmap.height - 1)
    val distinctColorBuckets = mutableSetOf<Int>()
    var nonBlackNonWhiteCount = 0
    val gridSteps = 4
    for (row in 0..gridSteps) {
      val y = min(bitmap.height - 1, top + ((bottom - top) * row) / gridSteps)
      for (col in 0..gridSteps) {
        val x = min(bitmap.width - 1, left + ((right - left) * col) / gridSteps)
        val pixel = bitmap.getPixel(x, y)
        val red = AndroidColor.red(pixel)
        val green = AndroidColor.green(pixel)
        val blue = AndroidColor.blue(pixel)
        val isNearBlack = red < 24 && green < 24 && blue < 24
        val isNearWhite = red > 235 && green > 235 && blue > 235
        if (!isNearBlack && !isNearWhite) {
          nonBlackNonWhiteCount++
        }
        distinctColorBuckets.add(((red shr 4) shl 8) or ((green shr 4) shl 4) or (blue shr 4))
      }
    }
    return nonBlackNonWhiteCount >= 5 && distinctColorBuckets.size >= 2
  }

  private fun LayoutCoordinates.screenBounds(): Rect {
    val topLeft = positionOnScreen()
    return Rect(
      topLeft.x.roundToInt(),
      topLeft.y.roundToInt(),
      (topLeft.x + size.width).roundToInt(),
      (topLeft.y + size.height).roundToInt(),
    )
  }

  /** Host activity that wakes and unlocks the display so SurfaceFlinger composites video frames. */
  class TestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
      super.onCreate(savedInstanceState)
      if (SDK_INT >= 27) {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
      } else {
        @Suppress("DEPRECATION")
        window.addFlags(
          WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
      }
      window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
  }

  companion object {
    private val SCREENSHOT_POLL_TIMEOUT_MS = if (isRunningOnEmulator()) 20_000L else 10_000L
  }
}
