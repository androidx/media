/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.cast

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.mediarouter.media.MediaRouteSelector
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.SessionManager
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when` as whenever
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

/** Unit test for [MediaRouteButton]. */
@RunWith(AndroidJUnit4::class)
class MediaRouteButtonTest {

  @get:Rule val mocks: MockitoRule = MockitoJUnit.rule()
  @get:Rule val composeTestRule = createComposeRule()

  @Mock private lateinit var mockCastContext: CastContext
  @Mock private lateinit var mockSessionManager: SessionManager
  @Mock private lateinit var mockCastContextInitializer: Cast.CastContextInitializer

  private lateinit var context: Context
  private lateinit var selector: MediaRouteSelector
  private lateinit var cast: Cast
  private lateinit var castContextTaskCompletionSource: TaskCompletionSource<CastContext>

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    selector = MediaRouteSelector.Builder().addControlCategory("category").build()
    castContextTaskCompletionSource = TaskCompletionSource<CastContext>()
    whenever(mockCastContextInitializer.init()).thenReturn(castContextTaskCompletionSource.task)
    whenever(mockCastContext.sessionManager).thenReturn(mockSessionManager)
    whenever(mockCastContext.mergedSelector).thenReturn(selector)
    cast = Cast.getSingletonInstance(context)
  }

  @After
  fun tearDown() {
    Cast.reset()
  }

  @Test
  fun initializeMediaRouteButton_buttonIsDisplayed() {
    cast = Cast.getSingletonInstance(mockCastContext)
    val buttonContentDescription = context.getString(R.string.media_route_button_disconnected)

    composeTestRule.setContent { MediaRouteButton() }

    composeTestRule.onNodeWithContentDescription(buttonContentDescription).assertIsDisplayed()
  }

  @Test
  fun initializeMediaRouteButton_notInitialized_notThrowsException() = runTest {
    cast.initialize(mockCastContextInitializer)
    val isContentComposed = AtomicBoolean(false)
    val content: @Composable MediaRouteButtonState.() -> Unit = { isContentComposed.set(true) }

    composeTestRule.setContent { MediaRouteButtonContainer(content) }
    advanceUntilIdle()

    assertThat(isContentComposed.get()).isFalse()
  }

  @Test
  fun initializeMediaRouteButton_alreadyInitialized_contentIsComposed() = runTest {
    cast = Cast.getSingletonInstance(mockCastContext)
    val isContentComposed = AtomicBoolean(false)
    val content: @Composable MediaRouteButtonState.() -> Unit = { isContentComposed.set(true) }

    composeTestRule.setContent { MediaRouteButtonContainer(content) }
    advanceUntilIdle()

    assertThat(isContentComposed.get()).isTrue()
  }

  @Test
  fun initializeMediaRouteButton_withSuccessfulInit_contentIsComposed() = runTest {
    cast.initialize(mockCastContextInitializer)
    castContextTaskCompletionSource.setResult(mockCastContext)
    val isContentComposed = AtomicBoolean(false)
    val content: @Composable MediaRouteButtonState.() -> Unit = { isContentComposed.set(true) }

    composeTestRule.setContent { MediaRouteButtonContainer(content) }
    advanceUntilIdle()

    assertThat(isContentComposed.get()).isTrue()
  }

  @Test
  fun initializeMediaRouteButton_withFailedInit_contentIsNotComposed() = runTest {
    cast.initialize(mockCastContextInitializer)
    val exception = RuntimeException("Failed to load")
    castContextTaskCompletionSource.setException(exception)
    val isContentComposed = AtomicBoolean(false)
    val content: @Composable MediaRouteButtonState.() -> Unit = { isContentComposed.set(true) }

    composeTestRule.setContent { MediaRouteButtonContainer(content) }
    advanceUntilIdle()

    assertThat(cast.castContextLoadFailure).isEqualTo(exception)
    assertThat(isContentComposed.get()).isFalse()
  }

  @Test
  fun initializeMediaRouteButton_onBackgroundThread_throwsException() = runTest {
    cast = Cast.getSingletonInstance(mockCastContext)
    val isContentComposed = AtomicBoolean(false)
    val content: @Composable MediaRouteButtonState.() -> Unit = { isContentComposed.set(true) }
    var caughtException: Throwable? = null

    val job =
      launch(Dispatchers.Default) {
        try {
          composeTestRule.setContent { MediaRouteButtonContainer(content) }
        } catch (t: Throwable) {
          caughtException = t
        }
      }
    job.join()

    assertThat(caughtException).isNotNull()
    assertThat(isContentComposed.get()).isFalse()
  }
}
