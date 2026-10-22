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
package androidx.media3.effect;

import static androidx.media3.effect.DefaultGlFrameProcessor.KEY_COMPOSITION_SEQUENCE_INDEX;
import static androidx.media3.effect.DefaultGlFrameProcessor.KEY_COMPOSITOR_SETTINGS;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static org.junit.Assert.assertThrows;

import androidx.media3.common.C;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoCompositorSettings;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link DefaultCompositorGlProgram}. */
@RunWith(AndroidJUnit4.class)
public final class DefaultCompositorGlProgramTest {

  private static final GlTextureInfo OUTPUT_TEXTURE =
      new GlTextureInfo(
          /* texId= */ 1,
          /* fboId= */ 1,
          /* rboId= */ C.INDEX_UNSET,
          /* width= */ 100,
          /* height= */ 100);

  @Test
  public void drawFrame_emptyFrames_throwsIllegalArgumentException() {
    DefaultCompositorGlProgram program = new DefaultCompositorGlProgram(getApplicationContext());

    assertThrows(
        IllegalArgumentException.class,
        () -> program.drawFrame(ImmutableList.of(), OUTPUT_TEXTURE));
  }

  @Test
  public void drawFrame_missingCompositorSettingsMetadata_throwsNullPointerException() {
    DefaultCompositorGlProgram program = new DefaultCompositorGlProgram(getApplicationContext());
    GlTextureFrame frame =
        new GlTextureFrame.Builder(
                OUTPUT_TEXTURE, directExecutor(), /* releaseTextureCallback= */ unused -> {})
            .setMetadata(ImmutableMap.of(KEY_COMPOSITION_SEQUENCE_INDEX, 0))
            .build();

    assertThrows(
        NullPointerException.class,
        () -> program.drawFrame(ImmutableList.of(frame), OUTPUT_TEXTURE));
  }

  @Test
  public void drawFrame_missingCompositionSequenceIndexMetadata_throwsNullPointerException() {
    DefaultCompositorGlProgram program = new DefaultCompositorGlProgram(getApplicationContext());
    GlTextureFrame frame =
        new GlTextureFrame.Builder(
                OUTPUT_TEXTURE, directExecutor(), /* releaseTextureCallback= */ unused -> {})
            .setMetadata(ImmutableMap.of(KEY_COMPOSITOR_SETTINGS, VideoCompositorSettings.DEFAULT))
            .build();

    assertThrows(
        NullPointerException.class,
        () -> program.drawFrame(ImmutableList.of(frame), OUTPUT_TEXTURE));
  }
}
