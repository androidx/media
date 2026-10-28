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
package androidx.media3.effect;

import android.opengl.EGLDisplay;
import androidx.media3.common.util.GlUtil;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link DefaultGlObjectsProvider}. */
@RunWith(AndroidJUnit4.class)
public final class DefaultGlObjectsProviderTest {

  @Test
  public void release_afterProviderIsReused_doesNotThrow() throws Exception {
    DefaultGlObjectsProvider glObjectsProvider = new DefaultGlObjectsProvider();
    EGLDisplay eglDisplay = GlUtil.getDefaultEglDisplay();
    glObjectsProvider.createEglContext(
        eglDisplay, /* openGlVersion= */ 2, GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888);
    glObjectsProvider.release(eglDisplay);

    eglDisplay = GlUtil.getDefaultEglDisplay();
    glObjectsProvider.createEglContext(
        eglDisplay, /* openGlVersion= */ 2, GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888);
    // Throws if the context destroyed by the first release is destroyed again.
    glObjectsProvider.release(eglDisplay);
  }
}
