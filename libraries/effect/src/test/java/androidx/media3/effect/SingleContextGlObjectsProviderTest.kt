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
package androidx.media3.effect

import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock

@RunWith(AndroidJUnit4::class)
class SingleContextGlObjectsProviderTest {

  private lateinit var fakeDelegate: FakeGlObjectsProvider
  private lateinit var provider: SingleContextGlObjectsProvider
  private lateinit var mockDisplay: EGLDisplay

  @Before
  fun setUp() {
    mockDisplay = mock(EGLDisplay::class.java)
    fakeDelegate = FakeGlObjectsProvider()
    provider = SingleContextGlObjectsProvider(fakeDelegate)
  }

  @Test
  fun createEglContext_calledTwice_createsOnceAndReturnsSameInstance() {
    val configAttributes1 = intArrayOf()
    val configAttributes2 = intArrayOf()
    val context1 = provider.createEglContext(mockDisplay, 2, configAttributes1)
    val context2 = provider.createEglContext(mockDisplay, 2, configAttributes2)

    assertThat(context1).isSameInstanceAs(context2)
    assertThat(fakeDelegate.createEglContextArgs).containsExactly(configAttributes1)
  }

  @Test
  fun release_clearsCachedContext_andReleasesDelegate() {
    val configAttributes1 = intArrayOf()
    val configAttributes2 = intArrayOf()
    val context1 = provider.createEglContext(mockDisplay, 2, configAttributes1)

    provider.release(mockDisplay)

    assertThat(fakeDelegate.releaseArgs).containsExactly(mockDisplay)

    val context2 = provider.createEglContext(mockDisplay, 2, configAttributes2)

    assertThat(context2).isNotSameInstanceAs(context1)
    assertThat(fakeDelegate.createEglContextArgs)
      .containsExactly(configAttributes1, configAttributes2)
      .inOrder()
  }

  @Test
  fun release_whenNoContextCreated_doesNotCallDelegateRelease() {
    provider.release(mockDisplay)

    assertThat(fakeDelegate.releaseArgs).isEmpty()
  }

  @Test
  fun delegation_forwardsOtherCalls() {
    val mockContext = mock(EGLContext::class.java)
    val unused = provider.createFocusedPlaceholderEglSurface(mockContext, mockDisplay)

    assertThat(fakeDelegate.createEglSurfaceArgs).containsExactly(mockContext to mockDisplay)

    val createdTexture = provider.createBuffersForTexture(1, 100, 100)
    assertThat(fakeDelegate.createBuffersForTextureArgs).containsExactly(1)
    assertThat(fakeDelegate.createdGlTextureInfos).containsExactly(createdTexture)
  }

  /** A Fake implementation that tracks method calls and returns distinct mock objects. */
  private class FakeGlObjectsProvider : GlObjectsProvider {

    val createEglContextArgs = mutableListOf<IntArray>()
    val releaseArgs = mutableListOf<EGLDisplay>()
    val createEglSurfaceArgs = mutableListOf<Pair<EGLContext, EGLDisplay>>()
    val createBuffersForTextureArgs = mutableListOf<Int>()
    val createdGlTextureInfos: MutableList<GlTextureInfo> = mutableListOf()

    override fun createEglContext(
      eglDisplay: EGLDisplay,
      openGlVersion: Int,
      configAttributes: IntArray,
    ): EGLContext {
      createEglContextArgs.add(configAttributes)
      return mock(EGLContext::class.java)
    }

    override fun release(eglDisplay: EGLDisplay) {
      releaseArgs.add(eglDisplay)
    }

    override fun createEglSurface(
      eglDisplay: EGLDisplay,
      surface: Any,
      colorTransfer: Int,
      isEncoderInputSurface: Boolean,
    ): EGLSurface {
      return mock(EGLSurface::class.java)
    }

    override fun createFocusedPlaceholderEglSurface(
      eglContext: EGLContext,
      eglDisplay: EGLDisplay,
    ): EGLSurface {
      createEglSurfaceArgs.add(eglContext to eglDisplay)
      return mock(EGLSurface::class.java)
    }

    override fun createBuffersForTexture(texId: Int, width: Int, height: Int): GlTextureInfo {
      createBuffersForTextureArgs.add(texId)
      val textureInfo = GlTextureInfo(texId, 0, 0, width, height)
      createdGlTextureInfos.add(textureInfo)
      return textureInfo
    }
  }
}
