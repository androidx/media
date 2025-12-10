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

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test executing methods in {@link ExoPlayerModuleProguard}. */
@RunWith(AndroidJUnit4.class)
public final class ExoPlayerModuleProguardTest {

  /**
   * This test does not cover any specific ExoPlayer proguard rules. It's a defensive test, which
   * can potentially help to spot (a) cases where we should be adding a proguard rule but have
   * forgotten to do so, and (b) cases where proguarding bugs are affecting ExoPlayer. See [Internal
   * ref: b/200606479] for an example. Currently, the test only covers ExoPlayer instantiation and
   * release.
   */
  @Test
  public void createAndReleaseExoPlayer_succeeds() {
    getInstrumentation()
        .runOnMainSync(
            () -> ExoPlayerModuleProguard.createAndReleaseExoPlayer(getApplicationContext()));
  }

  @Test
  public void defaultRenderersFactory_createLibvpxVideoRenderer_succeeds() {
    ExoPlayerModuleProguard.createLibvpxVideoRendererWithDefaultRenderersFactory(
        ApplicationProvider.getApplicationContext());
  }

  @Test
  public void defaultRenderersFactory_createLibdav1dVideoRenderer_succeeds() {
    ExoPlayerModuleProguard.createLibdav1dVideoRendererWithDefaultRenderersFactory(
        ApplicationProvider.getApplicationContext());
  }

  @Test
  public void defaultRenderersFactory_createFfmpegVideoRenderer_succeeds() {
    ExoPlayerModuleProguard.createFfmpegVideoRendererWithDefaultRenderersFactory(
        ApplicationProvider.getApplicationContext());
  }

  @Test
  public void defaultRenderersFactory_createLibopusAudioRenderer_succeeds() {
    ExoPlayerModuleProguard.createLibopusAudioRendererWithDefaultRenderersFactory(
        ApplicationProvider.getApplicationContext());
  }

  @Test
  public void defaultRenderersFactory_createLibflacAudioRenderer_succeeds() {
    ExoPlayerModuleProguard.createLibflacAudioRendererWithDefaultRenderersFactory(
        ApplicationProvider.getApplicationContext());
  }

  @Test
  public void defaultRenderersFactory_createFfmpegAudioRenderer_succeeds() {
    ExoPlayerModuleProguard.createFfmpegAudioRendererWithDefaultRenderersFactory(
        ApplicationProvider.getApplicationContext());
  }

  @Test
  public void defaultRenderersFactory_createMidiRenderer_succeeds() {
    ExoPlayerModuleProguard.createMidiRendererWithDefaultRenderersFactory(
        ApplicationProvider.getApplicationContext());
  }

  @Test
  public void defaultDownloaderFactory_createDashDownloader_succeeds() {
    ExoPlayerModuleProguard.createDashDownloaderWithDefaultDownloaderFactory(
        ApplicationProvider.getApplicationContext());
  }

  @Test
  public void defaultDownloaderFactory_createHlsDownloader_succeeds() {
    ExoPlayerModuleProguard.createHlsDownloaderWithDefaultDownloaderFactory(
        ApplicationProvider.getApplicationContext());
  }

  @Test
  public void defaultDownloaderFactory_createSsDownloader_succeeds() {
    ExoPlayerModuleProguard.createSsDownloaderWithDefaultDownloaderFactory(
        ApplicationProvider.getApplicationContext());
  }

  @Test
  public void defaultMediaSourceFactory_createDashMediaSource_succeeds() {
    ExoPlayerModuleProguard.createDashMediaSourceWithDefaultMediaSourceFactory(
        ApplicationProvider.getApplicationContext());
  }

  @Test
  public void defaultMediaSourceFactory_createHlsMediaSource_succeeds() {
    ExoPlayerModuleProguard.createHlsMediaSourceWithDefaultMediaSourceFactory(
        ApplicationProvider.getApplicationContext());
  }

  @Test
  public void defaultMediaSourceFactory_createSsMediaSource_succeeds() {
    ExoPlayerModuleProguard.createSsMediaSourceWithDefaultMediaSourceFactory(
        ApplicationProvider.getApplicationContext());
  }

  @Test
  public void defaultMediaSourceFactory_createRtspMediaSource_succeeds() {
    ExoPlayerModuleProguard.createRtspMediaSourceWithDefaultMediaSourceFactory(
        ApplicationProvider.getApplicationContext());
  }

  @Test
  public void compositingVideoSinkProvider_createSingleInputVideoGraph_succeeds() {
    getInstrumentation()
        .runOnMainSync(
            () -> {
              try {
                ExoPlayerModuleProguard.createSingleInputVideoGraphWithCompositingVideoSinkProvider(
                    getApplicationContext());
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
  }
}
