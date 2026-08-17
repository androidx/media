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
package androidx.media3.common.util;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;

import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import androidx.media3.common.video.SyncFenceWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import com.google.common.collect.ImmutableList;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Instrumentation tests for {@link GlUtil}. */
@RunWith(AndroidJUnit4.class)
public final class GlUtilAndroidTest {

  private EGLDisplay eglDisplay;
  private EGLContext eglContext;
  private EGLSurface placeholderSurface;

  @Before
  public void setUp() throws Exception {
    eglDisplay = GlUtil.getDefaultEglDisplay();
    eglContext = GlUtil.createEglContext(eglDisplay);
    placeholderSurface = GlUtil.createFocusedPlaceholderEglSurface(eglContext, eglDisplay);
  }

  @After
  public void tearDown() throws Exception {
    GlUtil.destroyEglSurface(eglDisplay, placeholderSurface);
    GlUtil.destroyEglContext(eglDisplay, eglContext);
  }

  @Test
  public void createSyncFences_nonPositiveCount_throwsIllegalArgumentException() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> GlUtil.createSyncFences(/* count= */ 0));
    assertThrows(IllegalArgumentException.class, () -> GlUtil.createSyncFences(/* count= */ -1));
  }

  @Test
  @SdkSuppress(minSdkVersion = 33)
  public void createSyncFences_onApi33OrHigher_returnsFencesIfSupported() throws Exception {
    String extensions = EGL14.eglQueryString(eglDisplay, EGL14.EGL_EXTENSIONS);
    assumeTrue(extensions != null && extensions.contains("EGL_ANDROID_native_fence_sync"));

    ImmutableList<SyncFenceWrapper> syncFences = GlUtil.createSyncFences(/* count= */ 2);

    assertThat(syncFences).hasSize(2);
    for (SyncFenceWrapper syncFence : syncFences) {
      assertThat(syncFence.asSyncFence().isValid()).isTrue();
    }
  }
}
