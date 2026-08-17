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
package androidx.media3.test.proguard;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test executing methods in {@link UiModuleProguard}. */
@RunWith(AndroidJUnit4.class)
public final class UiModuleProguardTest {

  @Test
  public void playerView_inflateSphericalGLSurfaceView_succeeds() {
    getInstrumentation()
        .runOnMainSync(
            () ->
                UiModuleProguard.inflatePlayerViewWithSphericalGLSurfaceView(
                    getApplicationContext()));
  }

  @Test
  public void playerView_inflateVideoDecoderGLSurfaceView_succeeds() {
    getInstrumentation()
        .runOnMainSync(
            () ->
                UiModuleProguard.inflatePlayerViewWithVideoDecoderGLSurfaceView(
                    getApplicationContext()));
  }

  @Test
  public void playerControlView_scrubbingWithExoPlayer_succeeds() throws Exception {
    UiModuleProguard.scrubOnTimeBarWithExoPlayerAndCheckThatSuppressionReasonChangesAndSeeksHappen(
        InstrumentationRegistry.getInstrumentation().getContext(), getApplicationContext());
  }

  @Test
  public void playerControlView_scrubbingWithCompositionPlayer_succeeds() throws Exception {
    UiModuleProguard
        .scrubOnTimeBarWithCompositionPlayerAndCheckThatSuppressionReasonChangesAndSeeksHappen(
            getApplicationContext());
  }

  @Test
  public void trackSelectionDialogBuilder_createAndroidXAlertDialog_succeeds() throws Exception {
    UiModuleProguard.createAndroidXDialogWithTrackSelectionDialogBuilder(getApplicationContext());
  }

  @Test
  public void playerView_setExoPlayer_registersImageOutput() throws Exception {
    UiModuleProguard.setPlayerOnPlayerViewAndCheckThatImageRendererReceivesOutput(
        getApplicationContext());
  }
}
