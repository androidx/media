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
import static androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig.CODEC_INFO_OPUS;
import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.truth.Truth.assertThat;
import static java.lang.Integer.max;

import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCrypto;
import android.media.MediaFormat;
import android.view.Surface;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.util.MediaFormatUtil;
import androidx.media3.common.util.Util;
import androidx.media3.container.OpusUtil;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.test.utils.FakeClock;
import androidx.media3.test.utils.TestUtil;
import androidx.media3.test.utils.robolectric.RandomizedMp3Decoder;
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Bytes;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAudioTrack;
import org.robolectric.shadows.ShadowMediaCodec;

/** End to end playback test for gapless audio playbacks. */
@RunWith(AndroidJUnit4.class)
public class EndToEndGaplessTest {
  private static final int CODEC_INPUT_BUFFER_SIZE = 5120;
  private static final int CODEC_OUTPUT_BUFFER_SIZE = 5120;

  private RandomizedMp3Decoder mp3Decoder;
  private RandomizedOpusDecoder opusDecoder;
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
    opusDecoder = new RandomizedOpusDecoder();
    shadowMediaCodecConfig.addCodec(
        CODEC_INFO_OPUS,
        /* isEncoder= */ false,
        new ShadowMediaCodec.CodecConfig(
            CODEC_INPUT_BUFFER_SIZE, CODEC_OUTPUT_BUFFER_SIZE, opusDecoder));
  }

  @Test
  @Config(minSdk = 29) // AudioFormat.getFrameSizeInBytes is only available from API 29.
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
    advance(player).untilState(Player.STATE_READY);
    advance(player).untilLoadingIs(false);
    player.play();
    advance(player).untilState(Player.STATE_ENDED);

    Format playerAudioFormat = player.getAudioFormat();
    player.release();
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
            // Both tracks are trimmed at their beginning and their end.
            Arrays.copyOfRange(decoderOutputBytes, delayBytes, bytesPerAudioFile - paddingBytes),
            Arrays.copyOfRange(
                decoderOutputBytes,
                bytesPerAudioFile + delayBytes,
                decoderOutputBytes.length - paddingBytes));
    byte[] audioTrackReceivedBytes = audioTrackListener.getAllReceivedBytes();

    // The first few bytes can be modified to ramp up the volume. Exclude those from the comparison.
    Arrays.fill(expectedTrimmedByteContent, 0, 2000, (byte) 0);
    Arrays.fill(audioTrackReceivedBytes, 0, 2000, (byte) 0);

    assertThat(audioTrackReceivedBytes).isEqualTo(expectedTrimmedByteContent);
  }

  @Test
  @Config(minSdk = 29) // AudioFormat.getFrameSizeInBytes is only available from API 29.
  public void play_twoIdenticalOpusFiles_trimsEndPadding() throws Exception {
    ExoPlayer player =
        new ExoPlayer.Builder(ApplicationProvider.getApplicationContext())
            .setClock(new FakeClock(/* isAutoAdvancing= */ true))
            .build();
    player.setMediaItems(
        ImmutableList.of(
            MediaItem.fromUri("asset:///media/ogg/bear.opus"),
            MediaItem.fromUri("asset:///media/ogg/bear.opus")));
    player.prepare();
    advance(player).untilState(Player.STATE_READY);
    advance(player).untilLoadingIs(false);
    player.play();
    advance(player).untilState(Player.STATE_ENDED);
    player.release();

    int bytesPerFrame = audioTrackListener.getAudioTrackOutputFormat().getFrameSizeInBytes();
    // bear.opus has 20 packets of 480 samples (9,600 samples) on the EOS page and a page granule
    // delta of 131,880 - 122,448 = 9,432, resulting in 168 samples of end padding.
    int paddingBytes = 168 * bytesPerFrame;
    assertThat(paddingBytes).isEqualTo(672);

    byte[] decoderOutputBytes =
        Bytes.concat(opusDecoder.getAllOutputBytes().toArray(new byte[0][]));
    int bytesPerAudioFile = decoderOutputBytes.length / 2;
    assertThat(bytesPerAudioFile).isEqualTo(528000);

    byte[] expectedTrimmedByteContent =
        Bytes.concat(
            // Both tracks are trimmed at their end (start pre-skip is handled by the Opus codec via
            // csd-1).
            Arrays.copyOf(decoderOutputBytes, bytesPerAudioFile - paddingBytes),
            Arrays.copyOfRange(
                decoderOutputBytes, bytesPerAudioFile, decoderOutputBytes.length - paddingBytes));
    byte[] audioTrackReceivedBytes = audioTrackListener.getAllReceivedBytes();

    // The first 20ms (960 frames = 3840 bytes) can be modified to ramp up the volume. Exclude
    // those from the comparison.
    Arrays.fill(expectedTrimmedByteContent, 0, 3840, (byte) 0);
    Arrays.fill(audioTrackReceivedBytes, 0, 3840, (byte) 0);

    assertThat(audioTrackReceivedBytes).isEqualTo(expectedTrimmedByteContent);
  }

  @Test
  @Config(minSdk = 29) // AudioFormat.getFrameSizeInBytes is only available from API 29.
  public void play_twoIdenticalMatroskaOpusFiles_trimsEndPadding() throws Exception {
    ExoPlayer player =
        new ExoPlayer.Builder(ApplicationProvider.getApplicationContext())
            .setClock(new FakeClock(/* isAutoAdvancing= */ true))
            .build();
    player.setMediaItems(
        ImmutableList.of(
            MediaItem.fromUri("asset:///media/mkv/sample_with_opus_audio.mkv"),
            MediaItem.fromUri("asset:///media/mkv/sample_with_opus_audio.mkv")));
    player.prepare();
    advance(player).untilState(Player.STATE_READY);
    advance(player).untilLoadingIs(false);
    player.play();
    advance(player).untilState(Player.STATE_ENDED);
    player.release();

    int bytesPerFrame = audioTrackListener.getAudioTrackOutputFormat().getFrameSizeInBytes();
    // sample_with_opus_audio.mkv has 3 packets of 960 samples (2,880 samples) and a DiscardPadding
    // of 3,500,000 ns (168 samples at 48 kHz) on the final BlockGroup.
    int paddingBytes = 168 * bytesPerFrame;
    assertThat(paddingBytes).isEqualTo(336);

    byte[] decoderOutputBytes =
        Bytes.concat(opusDecoder.getAllOutputBytes().toArray(new byte[0][]));
    int bytesPerAudioFile = decoderOutputBytes.length / 2;
    assertThat(bytesPerAudioFile).isEqualTo(5760);

    byte[] expectedTrimmedByteContent =
        Bytes.concat(
            // Both tracks are trimmed at their end (start pre-skip is handled by the Opus codec via
            // csd-1).
            Arrays.copyOf(decoderOutputBytes, bytesPerAudioFile - paddingBytes),
            Arrays.copyOfRange(
                decoderOutputBytes, bytesPerAudioFile, decoderOutputBytes.length - paddingBytes));
    byte[] audioTrackReceivedBytes = audioTrackListener.getAllReceivedBytes();

    // The first 20ms (960 frames = 1920 bytes for mono 16-bit PCM) can be modified to ramp up the
    // volume. Exclude those from the comparison.
    Arrays.fill(expectedTrimmedByteContent, 0, 1920, (byte) 0);
    Arrays.fill(audioTrackReceivedBytes, 0, 1920, (byte) 0);

    assertThat(audioTrackReceivedBytes).isEqualTo(expectedTrimmedByteContent);
  }

  private static final class RandomizedOpusDecoder implements ShadowMediaCodec.CodecConfig.Codec {
    private final List<byte[]> decoderOutput = new ArrayList<>();
    private int frameSizeInBytes;

    @Override
    public void process(ByteBuffer in, ByteBuffer out) {
      if (in.remaining() == 0) {
        return;
      }
      byte[] packetBytes = new byte[in.remaining()];
      in.get(packetBytes);
      int frameCount =
          (int)
              ((OpusUtil.getPacketDurationUs(packetBytes) * OpusUtil.SAMPLE_RATE)
                  / C.MICROS_PER_SECOND);
      byte[] bytesToWrite = TestUtil.buildTestData(frameCount * frameSizeInBytes);
      out.put(bytesToWrite);
      decoderOutput.add(bytesToWrite);
    }

    @Override
    public void onConfigured(
        MediaFormat format, @Nullable Surface surface, @Nullable MediaCrypto crypto, int flags) {
      int pcmEncoding =
          MediaFormatUtil.getInteger(
              format,
              MediaFormat.KEY_PCM_ENCODING,
              /* defaultValue= */ AudioFormat.ENCODING_PCM_16BIT);
      int channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
      checkArgument(
          MediaFormatUtil.getString(format, MediaFormat.KEY_MIME, MimeTypes.AUDIO_OPUS)
              .equals(MimeTypes.AUDIO_OPUS));
      frameSizeInBytes = Util.getPcmFrameSize(pcmEncoding, channelCount);
    }

    private ImmutableList<byte[]> getAllOutputBytes() {
      return ImmutableList.copyOf(decoderOutput);
    }
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
