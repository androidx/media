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
package androidx.media3.exoplayer.e2etest;

import static androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig.CODEC_INFO_MPEG;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.truth.Truth.assertThat;
import static java.lang.Integer.max;

import android.media.AudioFormat;
import android.media.AudioTrack;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.test.utils.FakeClock;
import androidx.media3.test.utils.robolectric.RandomizedMp3Decoder;
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig;
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Bytes;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.shadows.ShadowAudioTrack;
import org.robolectric.shadows.ShadowMediaCodec;

/** End to end playback test for gapless audio playbacks. */
@RunWith(AndroidJUnit4.class)
public class EndToEndGaplessTest {
  private static final int CODEC_INPUT_BUFFER_SIZE = 5120;
  private static final int CODEC_OUTPUT_BUFFER_SIZE = 5120;

  private RandomizedMp3Decoder mp3Decoder;
  private AudioTrackListener audioTrackListener;

  @Rule
  public final ShadowMediaCodecConfig shadowMediaCodecConfig =
      ShadowMediaCodecConfig.withNoDefaultSupportedCodecs();

  @Before
  public void setUp() throws Exception {
    audioTrackListener = new AudioTrackListener();
    ShadowAudioTrack.addAudioDataListener(audioTrackListener);

    mp3Decoder = new RandomizedMp3Decoder();
    shadowMediaCodecConfig.addCodec(
        CODEC_INFO_MPEG,
        /* isEncoder= */ false,
        new ShadowMediaCodec.CodecConfig(
            CODEC_INPUT_BUFFER_SIZE, CODEC_OUTPUT_BUFFER_SIZE, mp3Decoder));
  }

  @Test
  public void testPlayback_twoIdenticalMp3Files() throws Exception {
    ExoPlayer player =
        new ExoPlayer.Builder(ApplicationProvider.getApplicationContext())
            .setClock(new FakeClock(/* isAutoAdvancing= */ true))
            .build();

    player.setMediaItems(
        ImmutableList.of(
            MediaItem.fromUri("asset:///media/mp3/test-cbr-info-header.mp3"),
            MediaItem.fromUri("asset:///media/mp3/test-cbr-info-header.mp3")));
    player.prepare();
    player.play();
    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED);

    Format playerAudioFormat = player.getAudioFormat();
    assertThat(playerAudioFormat).isNotNull();

    int bytesPerFrame = audioTrackListener.getAudioTrackOutputFormat().getFrameSizeInBytes();
    int paddingBytes = max(0, playerAudioFormat.encoderPadding) * bytesPerFrame;
    int delayBytes = max(0, playerAudioFormat.encoderDelay) * bytesPerFrame;
    assertThat(paddingBytes).isEqualTo(2808);
    assertThat(delayBytes).isEqualTo(1152);

    byte[] decoderOutputBytes = Bytes.concat(mp3Decoder.getAllOutputBytes().toArray(new byte[0][]));
    int bytesPerAudioFile = decoderOutputBytes.length / 2;
    assertThat(bytesPerAudioFile).isEqualTo(92160);

    byte[] expectedTrimmedByteContent =
        Bytes.concat(
            // Track one is trimmed at its beginning and its end.
            Arrays.copyOfRange(decoderOutputBytes, delayBytes, bytesPerAudioFile - paddingBytes),
            // Track two is only trimmed at its beginning, but not its end.
            Arrays.copyOfRange(
                decoderOutputBytes, bytesPerAudioFile + delayBytes, decoderOutputBytes.length));
    byte[] audioTrackReceivedBytes = audioTrackListener.getAllReceivedBytes();

    // The first few bytes can be modified to ramp up the volume. Exclude those from the comparison.
    Arrays.fill(expectedTrimmedByteContent, 0, 2000, (byte) 0);
    Arrays.fill(audioTrackReceivedBytes, 0, 2000, (byte) 0);

    assertThat(audioTrackReceivedBytes).isEqualTo(expectedTrimmedByteContent);
  }

  private static class AudioTrackListener implements ShadowAudioTrack.OnAudioDataWrittenListener {
    private final ByteArrayOutputStream audioTrackReceivedBytesStream = new ByteArrayOutputStream();
    // Output format from the audioTrack.
    private AudioFormat format;
    private AudioTrack audioTrack;

    @Override
    public synchronized void onAudioDataWritten(
        AudioTrack audioTrack, byte[] audioData, AudioFormat format) {
      if (this.audioTrack == null) {
        this.audioTrack = audioTrack;
      } else {
        checkArgument(audioTrack == this.audioTrack, "Data written from a different AudioTrack");
      }

      if (!format.equals(this.format)) {
        this.format = format;
      }
      audioTrackReceivedBytesStream.write(audioData, 0, audioData.length);
    }

    public byte[] getAllReceivedBytes() {
      return audioTrackReceivedBytesStream.toByteArray();
    }

    public AudioFormat getAudioTrackOutputFormat() {
      return format;
    }
  }
}
