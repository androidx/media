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
package androidx.media3.decoder.av1;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static org.junit.Assert.fail;

import android.content.Context;
import android.net.Uri;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.test.utils.ExoPlayerTestRunner;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Playback tests using {@link Libdav1dVideoRenderer}. */
@RunWith(AndroidJUnit4.class)
public class Av1PlaybackTest {

  private static final String SAMPLE_AV1_URI = "asset:///media/mp4/sample_av1.mp4";

  @Before
  public void setUp() {
    if (!Dav1dLibrary.isAvailable()) {
      fail("Dav1d library not available.");
    }
  }

  @Test
  public void basicPlayback() throws Exception {
    RenderersFactory renderersFactory =
        (eventHandler,
            videoRendererEventListener,
            audioRendererEventListener,
            textRendererOutput,
            metadataRendererOutput) ->
            new Renderer[] {
              new Libdav1dVideoRenderer(
                  /* allowedJoiningTimeMs= */ 0,
                  eventHandler,
                  videoRendererEventListener,
                  /* maxDroppedFramesToNotify= */ -1)
            };
    Context context = getApplicationContext();
    new ExoPlayerTestRunner.Builder(context)
        .setRenderersFactory(renderersFactory)
        .setMediaSources(
            new DefaultMediaSourceFactory(context)
                .createMediaSource(MediaItem.fromUri(Uri.parse(SAMPLE_AV1_URI))))
        .build()
        .start()
        .blockUntilEnded(/* timeoutMs= */ 10_000);
  }
}
