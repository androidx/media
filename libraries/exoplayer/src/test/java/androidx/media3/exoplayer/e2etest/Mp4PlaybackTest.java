/*
 * Copyright (C) 2020 The Android Open Source Project
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
package androidx.media3.exoplayer.e2etest;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.test.utils.CapturingRenderersFactory;
import androidx.media3.test.utils.DumpFileAsserts;
import androidx.media3.test.utils.FakeClock;
import androidx.media3.test.utils.robolectric.PlaybackOutput;
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig;
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper;
import androidx.test.core.app.ApplicationProvider;
import com.google.common.collect.ImmutableList;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameter;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters;

/** End-to-end tests using MP4 samples. */
@RunWith(ParameterizedRobolectricTestRunner.class)
public class Mp4PlaybackTest {

  @Parameters(name = "{0}")
  public static ImmutableList<String> mediaSamples() {
    return ImmutableList.of(
        "midroll-5s.mp4",
        "postroll-5s.mp4",
        "preroll-5s.mp4",
        "sample_ac3_fragmented.mp4",
        "sample_ac3.mp4",
        "sample_ac4_fragmented.mp4",
        "sample_ac4.mp4",
        "sample_android_slow_motion.mp4",
        "sample_eac3_fragmented.mp4",
        "sample_eac3.mp4",
        "sample_eac3joc_fragmented.mp4",
        "sample_eac3joc.mp4",
        "sample_fragmented.mp4",
        "sample_fragmented_seekable.mp4",
        "sample_fragmented_large_bitrates.mp4",
        "sample_fragmented_sei.mp4",
        "sample_mdat_too_long.mp4",
        "sample.mp4",
        "sample_opus_fragmented.mp4",
        "sample_opus.mp4",
        "sample_partially_fragmented.mp4",
        "testvid_1022ms.mp4");
  }

  @Parameter public String inputFile;

  @Rule
  public ShadowMediaCodecConfig mediaCodecConfig =
      ShadowMediaCodecConfig.forAllSupportedMimeTypes();

  @Test
  public void test() throws Exception {
    Context applicationContext = ApplicationProvider.getApplicationContext();
    CapturingRenderersFactory renderersFactory = new CapturingRenderersFactory(applicationContext);
    ExoPlayer player =
        new ExoPlayer.Builder(applicationContext, renderersFactory)
            .setClock(new FakeClock(/* isAutoAdvancing= */ true))
            .build();
    player.setVideoSurface(new Surface(new SurfaceTexture(/* texName= */ 1)));

    PlaybackOutput playbackOutput = PlaybackOutput.register(player, renderersFactory);

    player.setMediaItem(MediaItem.fromUri("asset:///media/mp4/" + inputFile));
    player.prepare();
    player.play();
    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED);
    player.release();

    DumpFileAsserts.assertOutput(
        applicationContext, playbackOutput, "playbackdumps/mp4/" + inputFile + ".dump");
  }
}
