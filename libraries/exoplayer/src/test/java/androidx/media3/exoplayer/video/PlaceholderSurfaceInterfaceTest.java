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
package androidx.media3.exoplayer.video;

import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.common.util.EGLSurfaceTexture.SECURE_MODE_NONE;
import static androidx.media3.common.util.EGLSurfaceTexture.SECURE_MODE_PROTECTED_PBUFFER;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import androidx.media3.common.Flags;
import androidx.media3.test.utils.BindFlag;
import androidx.media3.test.utils.Media3FlagsRule;
import androidx.test.core.app.ApplicationProvider;
import com.google.testing.junit.testparameterinjector.TestParameter;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestParameterInjector;
import org.robolectric.util.ReflectionHelpers;

/** Unit test for {@link PlaceholderSurfaceInterface}. */
@RunWith(RobolectricTestParameterInjector.class)
public final class PlaceholderSurfaceInterfaceTest {

  @Rule public final Media3FlagsRule flags = new Media3FlagsRule(this);

  @TestParameter
  @BindFlag(Flags.FLAG_IMAGE_READER_PLACEHOLDER_SURFACE)
  private boolean useImageReaderPlaceholderSurface;

  private final Context context = ApplicationProvider.getApplicationContext();

  @After
  public void tearDown() {
    // Reset PlaceholderSurface's static EGL secure mode cache so it doesn't leak across tests.
    ReflectionHelpers.setStaticField(PlaceholderSurface.class, "secureModeInitialized", false);
    ReflectionHelpers.setStaticField(PlaceholderSurface.class, "secureMode", SECURE_MODE_NONE);
  }

  @Test
  public void newInstance_returnsExpectedImplementation(@TestParameter boolean secure) {
    // Robolectric's ShadowEGL14 does not report EGL_EXT_protected_content, so override
    // PlaceholderSurface's cached static secureMode fields to simulate a device supporting secure
    // placeholder surfaces.
    ReflectionHelpers.setStaticField(PlaceholderSurface.class, "secureModeInitialized", true);
    ReflectionHelpers.setStaticField(
        PlaceholderSurface.class, "secureMode", SECURE_MODE_PROTECTED_PBUFFER);

    PlaceholderSurfaceInterface placeholderSurface =
        PlaceholderSurfaceInterface.newInstance(context, secure);

    try {
      Class<? extends PlaceholderSurfaceInterface> expectedClass =
          !secure && SDK_INT >= 29 && useImageReaderPlaceholderSurface
              ? ImageReaderPlaceholderSurface.class
              : PlaceholderSurface.class;
      assertThat(placeholderSurface).isInstanceOf(expectedClass);
      assertThat(placeholderSurface.isSecure()).isEqualTo(secure);
      assertThat(placeholderSurface.getSurface()).isNotNull();
    } finally {
      placeholderSurface.release();
    }
  }

  @Test
  public void newInstance_secureWhenNotSupported_throwsIllegalStateException() {
    // Override PlaceholderSurface's cached static secureMode fields to simulate a device that does
    // not support secure placeholder surfaces.
    ReflectionHelpers.setStaticField(PlaceholderSurface.class, "secureModeInitialized", true);
    ReflectionHelpers.setStaticField(PlaceholderSurface.class, "secureMode", SECURE_MODE_NONE);

    assertThrows(
        IllegalStateException.class,
        () -> PlaceholderSurfaceInterface.newInstance(context, /* secure= */ true));
  }
}
