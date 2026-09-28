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
package androidx.media3.test.proguard.minimal;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.res.Resources;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.InputStream;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Instrumentation test verifying that unused classes and resources are stripped in a minimal
 * ExoPlayer build.
 *
 * <ul>
 *   <li>Build-time validation: R8 enforces the {@code -checkdiscard} rules in {@code
 *       proguard-checkdiscard-rules.pgcfg} (failing the build if {@code
 *       SingleInputVideoGraph$Factory} or {@code DefaultVideoFrameProcessor$Factory$Builder} is
 *       retained).
 *   <li>Runtime validation: Verifies basic player execution, unused class stripping, and resource
 *       stripping.
 * </ul>
 */
@RunWith(AndroidJUnit4.class)
public final class ExoPlayerMinimalModuleProguardTest {

  @Test
  public void createAndReleaseExoPlayer_succeeds() {
    getInstrumentation()
        .runOnMainSync(
            () ->
                ExoPlayerMinimalModuleProguard.createAndReleaseExoPlayer(getApplicationContext()));
  }

  @Test
  @SuppressWarnings({"UnnecessaryStringBuilder", "RedundantStringBuilderAppend"})
  public void effectClasses_areStripped() {
    // LINT.IfChange
    // b/463697143: Obfuscate class name to bypass R8/AppReduce static analysis.
    assertThrows(
        ClassNotFoundException.class,
        () ->
            Class.forName(
                new StringBuilder("androidx.media3.effect.")
                    .append("SingleInputVideoGraph$Factory")
                    .toString()));
    assertThrows(
        ClassNotFoundException.class,
        () ->
            Class.forName(
                new StringBuilder("androidx.media3.effect.")
                    .append("DefaultVideoFrameProcessor$Factory$Builder")
                    .toString()));
    // LINT.ThenChange(../../../../../../../../../exoplayer/src/main/java/androidx/media3/exoplayer/video/PlaybackVideoGraphWrapper.java)
  }

  @Test
  public void unusedResource_isStrippedOrEmpty() throws Exception {
    Resources resources = getApplicationContext().getResources();
    String packageName = getApplicationContext().getPackageName();

    assertTrue(getRawResourceAvailableBytes(resources, "unused_raw_resource", packageName) <= 1);
  }

  @Test
  public void effectShaderResource_isStrippedOrEmpty() throws Exception {
    Resources resources = getApplicationContext().getResources();
    String packageName = getApplicationContext().getPackageName();

    assertTrue(
        getRawResourceAvailableBytes(resources, "fragment_shader_copy_es2", packageName) <= 1);
  }

  private static int getRawResourceAvailableBytes(
      Resources resources, String resourceName, String packageName) throws Exception {
    int resId = resources.getIdentifier(resourceName, "raw", packageName);
    if (resId == 0) {
      return 0;
    }
    try (InputStream inputStream = resources.openRawResource(resId)) {
      // Shrunk resources are stubbed to empty/dummy bytes (0 or 1 byte) or removed by resource
      // shrinker.
      return inputStream.available();
    }
  }
}
