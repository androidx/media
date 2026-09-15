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
package androidx.media3.exoplayer.audio;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.truth.Truth.assertThat;
import static org.robolectric.Shadows.shadowOf;

import android.app.UiModeManager;
import android.content.Context;
import android.content.res.Configuration;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.test.utils.FakeClock;
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAudioTrack;

/**
 * Plays Dolby TrueHD from a fragmented MP4 through a real {@link DefaultAudioSink} in passthrough
 * mode, and checks that the sink doesn't report a timestamp discontinuity.
 *
 * <p>See https://github.com/androidx/media/issues/1519.
 */
@RunWith(AndroidJUnit4.class)
@Config(minSdk = 29, maxSdk = 32)
public class TrueHdFragmentedMp4PlaybackTest {

  /**
   * The format {@code AudioCapabilities} probes with. Registering support for it makes the sink
   * pick TrueHD passthrough.
   */
  private static final AudioFormat TRUEHD_PROBE_FORMAT =
      new AudioFormat.Builder()
          .setSampleRate(48_000)
          .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
          .setEncoding(AudioFormat.ENCODING_DOLBY_TRUEHD)
          .build();

  private static final AudioAttributes AUDIO_ATTRIBUTES =
      new AudioAttributes.Builder()
          .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
          .setUsage(AudioAttributes.USAGE_MEDIA)
          .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL)
          .build();

  private final Context applicationContext = getApplicationContext();

  @Before
  public void setUp() {
    // Passthrough capabilities are only queried on TV and automotive devices.
    shadowOf((UiModeManager) applicationContext.getSystemService(Context.UI_MODE_SERVICE))
        .setCurrentModeType(Configuration.UI_MODE_TYPE_TELEVISION);
    ShadowAudioTrack.addDirectPlaybackSupport(TRUEHD_PROBE_FORMAT, AUDIO_ATTRIBUTES);
    ShadowAudioTrack.addAllowedNonPcmEncoding(AudioFormat.ENCODING_DOLBY_TRUEHD);
  }

  @Test
  public void fragmentedTrueHd_playsThroughSinkWithoutDiscontinuity() throws Exception {
    assertPlaysWithoutAudioSinkError("asset:///media/mp4/sample_dthd_fragmented.mp4");
  }

  @Test
  public void nonFragmentedTrueHd_playsThroughSinkWithoutDiscontinuity() throws Exception {
    assertPlaysWithoutAudioSinkError("asset:///media/mp4/sample_dthd.mp4");
  }

  private void assertPlaysWithoutAudioSinkError(String uri) throws Exception {
    List<Exception> audioSinkErrors = new ArrayList<>();
    List<Format> audioInputFormats = new ArrayList<>();
    ExoPlayer player =
        new ExoPlayer.Builder(applicationContext)
            .setClock(new FakeClock(/* isAutoAdvancing= */ true))
            .build();
    player.addAnalyticsListener(
        new AnalyticsListener() {
          @Override
          public void onAudioSinkError(EventTime eventTime, Exception audioSinkError) {
            audioSinkErrors.add(audioSinkError);
          }

          @Override
          public void onAudioInputFormatChanged(
              EventTime eventTime, Format format, @Nullable DecoderReuseEvaluation evaluation) {
            audioInputFormats.add(format);
          }
        });
    player.setMediaItem(MediaItem.fromUri(uri));
    player.prepare();
    player.play();
    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED);
    player.release();

    // Guard against the test passing because the audio track was never played at all.
    assertThat(audioInputFormats).isNotEmpty();
    assertThat(audioInputFormats.get(0).sampleMimeType).isEqualTo(MimeTypes.AUDIO_TRUEHD);
    assertThat(audioSinkErrors).isEmpty();
  }
}
