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

import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance;
import static org.robolectric.annotation.GraphicsMode.Mode.NATIVE;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.Clock;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.test.utils.DumpFileAsserts;
import androidx.media3.test.utils.FakeClock;
import androidx.media3.test.utils.robolectric.CapturingRenderersFactory;
import androidx.media3.test.utils.robolectric.PlaybackOutput;
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig;
import androidx.test.core.app.ApplicationProvider;
import com.google.common.collect.ImmutableList;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameter;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters;
import org.robolectric.annotation.GraphicsMode;

/** End-to-end tests using MP4 samples. */
@GraphicsMode(NATIVE)
@RunWith(ParameterizedRobolectricTestRunner.class)
public class Mp4PlaybackTest {

  @Parameters(name = "{0}")
  public static ImmutableList<Sample> mediaSamples() {
    return ImmutableList.of(
        Sample.forFile("midroll-5s.mp4"),
        Sample.forFile("postroll-5s.mp4"),
        Sample.forFile("preroll-5s.mp4"),
        Sample.forFile("pixel-motion-photo-2-hevc-tracks.mp4"),
        Sample.forFile("sample_ac3_fragmented.mp4"),
        Sample.forFile("sample_ac3.mp4"),
        Sample.forFile("sample_ac4_fragmented.mp4"),
        Sample.forFile("sample_ac4.mp4"),
        Sample.forFile("sample_android_slow_motion.mp4"),
        Sample.forFile("sample_eac3_fragmented.mp4"),
        Sample.forFile("sample_eac3.mp4"),
        Sample.forFile("sample_eac3joc_fragmented.mp4"),
        Sample.forFile("sample_eac3joc.mp4"),
        Sample.forFile("sample_fragmented.mp4"),
        Sample.forFile("sample_fragmented_seekable.mp4"),
        Sample.forFile("sample_fragmented_large_bitrates.mp4"),
        Sample.forFile("sample_fragmented_sei.mp4"),
        Sample.forFile("sample_mdat_too_long.mp4"),
        Sample.forFile("sample.mp4"),
        Sample.forFile("sample_with_metadata.mp4"),
        Sample.forFile("sample_with_numeric_genre.mp4"),
        Sample.forFile("sample_opus_fragmented.mp4"),
        Sample.forFile("sample_opus.mp4"),
        Sample.forFile("sample_alac.mp4"),
        Sample.forFile("sample_partially_fragmented.mp4"),
        Sample.withSubtitles("sample_with_vobsub.mp4", "eng"),
        Sample.forFile("testvid_1022ms.mp4"),
        Sample.forFile("sample_edit_list.mp4"),
        Sample.forFile("sample_edit_list_no_sync_frame_before_edit.mp4"));
  }

  @Parameter public Sample sample;

  @Rule
  public ShadowMediaCodecConfig mediaCodecConfig =
      ShadowMediaCodecConfig.withAllDefaultSupportedCodecs();

  @Test
  public void test() throws Exception {
    Context applicationContext = ApplicationProvider.getApplicationContext();
    Clock clock = new FakeClock(/* isAutoAdvancing= */ true);
    CapturingRenderersFactory renderersFactory =
        new CapturingRenderersFactory(applicationContext, clock);
    ExoPlayer player =
        new ExoPlayer.Builder(applicationContext, renderersFactory).setClock(clock).build();
    if (sample.subtitleLanguageToSelect != null) {
      player.setTrackSelectionParameters(
          player
              .getTrackSelectionParameters()
              .buildUpon()
              .setPreferredTextLanguage(sample.subtitleLanguageToSelect)
              .build());
    }
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player.setVideoSurface(surface);

    PlaybackOutput playbackOutput = PlaybackOutput.register(player, renderersFactory);

    player.setMediaItem(MediaItem.fromUri("asset:///media/mp4/" + sample.filename));
    player.prepare();
    advance(player).untilState(Player.STATE_READY);
    player.play();
    advance(player).untilState(Player.STATE_ENDED);
    player.release();
    surface.release();

    DumpFileAsserts.assertOutput(
        applicationContext, playbackOutput, "playbackdumps/mp4/" + sample.filename + ".dump");
  }

  private static final class Sample {
    public final String filename;
    @Nullable public final String subtitleLanguageToSelect;

    private Sample(String filename, @Nullable String subtitleLanguageToSelect) {
      this.filename = filename;
      this.subtitleLanguageToSelect = subtitleLanguageToSelect;
    }

    public static Sample forFile(String filename) {
      return new Sample(filename, /* subtitleLanguageToSelect= */ null);
    }

    public static Sample withSubtitles(String filename, String subtitleLanguageToSelect) {
      return new Sample(filename, /* enableSubtitles= */ subtitleLanguageToSelect);
    }

    @Override
    public String toString() {
      return filename;
    }
  }
}
