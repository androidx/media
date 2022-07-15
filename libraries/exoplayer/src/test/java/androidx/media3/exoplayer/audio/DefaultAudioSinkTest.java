/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static androidx.media3.exoplayer.audio.AudioSink.CURRENT_POSITION_NOT_SET;
import static androidx.media3.exoplayer.audio.AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY;
import static androidx.media3.exoplayer.audio.AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING;
import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.exoplayer.audio.DefaultAudioSink.DefaultAudioProcessorChain;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.TimeoutException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

/** Unit tests for {@link DefaultAudioSink}. */
@RunWith(AndroidJUnit4.class)
public final class DefaultAudioSinkTest {

  private static final long TIMEOUT_MS = 10_000;

  private static final int CHANNEL_COUNT_MONO = 1;
  private static final int CHANNEL_COUNT_STEREO = 2;
  private static final int BYTES_PER_FRAME_16_BIT = 2;
  private static final int SAMPLE_RATE_44_1 = 44100;
  private static final int TRIM_100_MS_FRAME_COUNT = 4410;
  private static final int TRIM_10_MS_FRAME_COUNT = 441;
  private static final Format STEREO_44_1_FORMAT =
      new Format.Builder()
          .setChannelCount(CHANNEL_COUNT_STEREO)
          .setSampleRate(SAMPLE_RATE_44_1)
          .build();

  private DefaultAudioSink defaultAudioSink;
  private ArrayAudioBufferSink arrayAudioBufferSink;

  @Before
  public void setUp() {
    // For capturing output.
    arrayAudioBufferSink = new ArrayAudioBufferSink();
    TeeAudioProcessor teeAudioProcessor = new TeeAudioProcessor(arrayAudioBufferSink);
    defaultAudioSink =
        new DefaultAudioSink.Builder()
            .setAudioProcessorChain(new DefaultAudioProcessorChain(teeAudioProcessor))
            .setOffloadMode(DefaultAudioSink.OFFLOAD_MODE_DISABLED)
            .build();
  }

  @Test
  public void handlesSpecializedAudioProcessorArray() {
    defaultAudioSink =
        new DefaultAudioSink.Builder().setAudioProcessors(new TeeAudioProcessor[0]).build();
  }

  @Test
  public void handlesBufferAfterReset() throws Exception {
    configureDefaultAudioSink(CHANNEL_COUNT_STEREO);
    assertThat(
            defaultAudioSink.handleBuffer(
                createDefaultSilenceBuffer(),
                /* presentationTimeUs= */ 0,
                /* encodedAccessUnitCount= */ 1))
        .isTrue();

    // After reset and re-configure we can successfully queue more input.
    defaultAudioSink.reset();
    configureDefaultAudioSink(CHANNEL_COUNT_STEREO);
    retryUntilTrue(
        () ->
            defaultAudioSink.handleBuffer(
                createDefaultSilenceBuffer(),
                /* presentationTimeUs= */ 0,
                /* encodedAccessUnitCount= */ 1));
  }

  @Test
  public void handlesBufferAfterReset_withPlaybackSpeed() throws Exception {
    defaultAudioSink.setPlaybackParameters(new PlaybackParameters(/* speed= */ 1.5f));
    configureDefaultAudioSink(CHANNEL_COUNT_STEREO);
    assertThat(
            defaultAudioSink.handleBuffer(
                createDefaultSilenceBuffer(),
                /* presentationTimeUs= */ 0,
                /* encodedAccessUnitCount= */ 1))
        .isTrue();

    // After reset and re-configure we can successfully queue more input.
    defaultAudioSink.reset();
    configureDefaultAudioSink(CHANNEL_COUNT_STEREO);
    retryUntilTrue(
        () ->
            defaultAudioSink.handleBuffer(
                createDefaultSilenceBuffer(),
                /* presentationTimeUs= */ 0,
                /* encodedAccessUnitCount= */ 1));
    assertThat(defaultAudioSink.getPlaybackParameters())
        .isEqualTo(new PlaybackParameters(/* speed= */ 1.5f));
  }

  @Test
  public void handlesBufferAfterReset_withFormatChange() throws Exception {
    configureDefaultAudioSink(CHANNEL_COUNT_STEREO);
    assertThat(
            defaultAudioSink.handleBuffer(
                createDefaultSilenceBuffer(),
                /* presentationTimeUs= */ 0,
                /* encodedAccessUnitCount= */ 1))
        .isTrue();

    // After reset and re-configure we can successfully queue more input.
    defaultAudioSink.reset();
    configureDefaultAudioSink(CHANNEL_COUNT_MONO);
    retryUntilTrue(
        () ->
            defaultAudioSink.handleBuffer(
                createDefaultSilenceBuffer(),
                /* presentationTimeUs= */ 0,
                /* encodedAccessUnitCount= */ 1));
  }

  @Test
  public void handlesBufferAfterReset_withFormatChangeAndPlaybackSpeed() throws Exception {
    defaultAudioSink.setPlaybackParameters(new PlaybackParameters(/* speed= */ 1.5f));
    configureDefaultAudioSink(CHANNEL_COUNT_STEREO);
    assertThat(
            defaultAudioSink.handleBuffer(
                createDefaultSilenceBuffer(),
                /* presentationTimeUs= */ 0,
                /* encodedAccessUnitCount= */ 1))
        .isTrue();

    // After reset and re-configure we can successfully queue more input.
    defaultAudioSink.reset();
    configureDefaultAudioSink(CHANNEL_COUNT_MONO);
    retryUntilTrue(
        () ->
            defaultAudioSink.handleBuffer(
                createDefaultSilenceBuffer(),
                /* presentationTimeUs= */ 0,
                /* encodedAccessUnitCount= */ 1));
    assertThat(defaultAudioSink.getPlaybackParameters())
        .isEqualTo(new PlaybackParameters(/* speed= */ 1.5f));
  }

  @Test
  public void trimsStartFrames() throws Exception {
    configureDefaultAudioSink(
        CHANNEL_COUNT_STEREO,
        /* trimStartFrames= */ TRIM_100_MS_FRAME_COUNT,
        /* trimEndFrames= */ 0);
    assertThat(
            defaultAudioSink.handleBuffer(
                createDefaultSilenceBuffer(),
                /* presentationTimeUs= */ 0,
                /* encodedAccessUnitCount= */ 1))
        .isTrue();

    assertThat(arrayAudioBufferSink.output)
        .hasLength(
            (BYTES_PER_FRAME_16_BIT
                * CHANNEL_COUNT_STEREO
                * (SAMPLE_RATE_44_1 - TRIM_100_MS_FRAME_COUNT)));
  }

  @Test
  public void trimsEndFrames() throws Exception {
    configureDefaultAudioSink(
        CHANNEL_COUNT_STEREO,
        /* trimStartFrames= */ 0,
        /* trimEndFrames= */ TRIM_10_MS_FRAME_COUNT);
    assertThat(
            defaultAudioSink.handleBuffer(
                createDefaultSilenceBuffer(),
                /* presentationTimeUs= */ 0,
                /* encodedAccessUnitCount= */ 1))
        .isTrue();

    assertThat(arrayAudioBufferSink.output)
        .hasLength(
            (BYTES_PER_FRAME_16_BIT
                * CHANNEL_COUNT_STEREO
                * (SAMPLE_RATE_44_1 - TRIM_10_MS_FRAME_COUNT)));
  }

  @Test
  public void trimsStartAndEndFrames() throws Exception {
    configureDefaultAudioSink(
        CHANNEL_COUNT_STEREO,
        /* trimStartFrames= */ TRIM_100_MS_FRAME_COUNT,
        /* trimEndFrames= */ TRIM_10_MS_FRAME_COUNT);
    assertThat(
            defaultAudioSink.handleBuffer(
                createDefaultSilenceBuffer(),
                /* presentationTimeUs= */ 0,
                /* encodedAccessUnitCount= */ 1))
        .isTrue();

    assertThat(arrayAudioBufferSink.output)
        .hasLength(
            (BYTES_PER_FRAME_16_BIT
                * CHANNEL_COUNT_STEREO
                * (SAMPLE_RATE_44_1 - TRIM_100_MS_FRAME_COUNT - TRIM_10_MS_FRAME_COUNT)));
  }

  @Test
  public void getCurrentPosition_returnsPositionFromFirstBuffer() throws Exception {
    configureDefaultAudioSink(CHANNEL_COUNT_STEREO);
    assertThat(
            defaultAudioSink.handleBuffer(
                createDefaultSilenceBuffer(),
                /* presentationTimeUs= */ 5 * C.MICROS_PER_SECOND,
                /* encodedAccessUnitCount= */ 1))
        .isTrue();
    assertThat(defaultAudioSink.getCurrentPositionUs(/* sourceEnded= */ false))
        .isEqualTo(5 * C.MICROS_PER_SECOND);

    defaultAudioSink.reset();
    configureDefaultAudioSink(CHANNEL_COUNT_STEREO);
    retryUntilTrue(
        () ->
            defaultAudioSink.handleBuffer(
                createDefaultSilenceBuffer(),
                /* presentationTimeUs= */ 8 * C.MICROS_PER_SECOND,
                /* encodedAccessUnitCount= */ 1));
    assertThat(defaultAudioSink.getCurrentPositionUs(/* sourceEnded= */ false))
        .isEqualTo(8 * C.MICROS_PER_SECOND);
  }

  @Test
  public void floatPcmNeedsTranscodingIfFloatOutputDisabled() {
    defaultAudioSink = new DefaultAudioSink.Builder().build();
    Format floatFormat =
        STEREO_44_1_FORMAT
            .buildUpon()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_FLOAT)
            .build();
    assertThat(defaultAudioSink.getFormatSupport(floatFormat))
        .isEqualTo(SINK_FORMAT_SUPPORTED_WITH_TRANSCODING);
  }

  @Config(maxSdk = 20)
  @Test
  public void floatPcmNeedsTranscodingIfFloatOutputEnabledBeforeApi21() {
    defaultAudioSink = new DefaultAudioSink.Builder().setEnableFloatOutput(true).build();
    Format floatFormat =
        STEREO_44_1_FORMAT
            .buildUpon()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_FLOAT)
            .build();
    assertThat(defaultAudioSink.getFormatSupport(floatFormat))
        .isEqualTo(SINK_FORMAT_SUPPORTED_WITH_TRANSCODING);
  }

  @Config(minSdk = 21)
  @Test
  public void floatOutputSupportedIfFloatOutputEnabledFromApi21() {
    defaultAudioSink = new DefaultAudioSink.Builder().setEnableFloatOutput(true).build();
    Format floatFormat =
        STEREO_44_1_FORMAT
            .buildUpon()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_FLOAT)
            .build();
    assertThat(defaultAudioSink.getFormatSupport(floatFormat))
        .isEqualTo(SINK_FORMAT_SUPPORTED_DIRECTLY);
  }

  @Test
  public void supportsFloatPcm() {
    Format floatFormat =
        STEREO_44_1_FORMAT
            .buildUpon()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_FLOAT)
            .build();
    assertThat(defaultAudioSink.supportsFormat(floatFormat)).isTrue();
  }

  @Test
  public void audioSinkWithAacAudioCapabilitiesWithoutOffload_doesNotSupportAac() {
    DefaultAudioSink defaultAudioSink =
        new DefaultAudioSink.Builder()
            .setAudioCapabilities(new AudioCapabilities(new int[] {C.ENCODING_AAC_LC}, 2))
            .build();
    Format aacLcFormat =
        STEREO_44_1_FORMAT
            .buildUpon()
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setPcmEncoding(C.ENCODING_AAC_LC)
            .build();
    assertThat(defaultAudioSink.supportsFormat(aacLcFormat)).isFalse();
  }

  @Test
  public void handlesBufferAfterExperimentalFlush() throws Exception {
    // This is demonstrating that no Exceptions are thrown as a result of handling a buffer after an
    // experimental flush.
    configureDefaultAudioSink(CHANNEL_COUNT_STEREO);
    assertThat(
            defaultAudioSink.handleBuffer(
                createDefaultSilenceBuffer(),
                /* presentationTimeUs= */ 0,
                /* encodedAccessUnitCount= */ 1))
        .isTrue();

    // After the experimental flush we can successfully queue more input.
    defaultAudioSink.experimentalFlushWithoutAudioTrackRelease();
    assertThat(
            defaultAudioSink.handleBuffer(
                createDefaultSilenceBuffer(),
                /* presentationTimeUs= */ 5_000,
                /* encodedAccessUnitCount= */ 1))
        .isTrue();
  }

  @Test
  public void getCurrentPosition_returnsUnset_afterExperimentalFlush() throws Exception {
    configureDefaultAudioSink(CHANNEL_COUNT_STEREO);
    assertThat(
            defaultAudioSink.handleBuffer(
                createDefaultSilenceBuffer(),
                /* presentationTimeUs= */ 5 * C.MICROS_PER_SECOND,
                /* encodedAccessUnitCount= */ 1))
        .isTrue();
    defaultAudioSink.experimentalFlushWithoutAudioTrackRelease();
    assertThat(defaultAudioSink.getCurrentPositionUs(/* sourceEnded= */ false))
        .isEqualTo(CURRENT_POSITION_NOT_SET);
  }

  @Test
  public void configure_throwsConfigurationException_withInvalidInput() {
    Format format = new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build();
    AudioSink.ConfigurationException thrown =
        Assert.assertThrows(
            AudioSink.ConfigurationException.class,
            () ->
                defaultAudioSink.configure(
                    format, /* specifiedBufferSize= */ 0, /* outputChannels= */ null));
    assertThat(thrown.format).isEqualTo(format);
  }

  @Test
  public void setPlaybackParameters_doesNothingWhenTunnelingIsEnabled() throws Exception {
    defaultAudioSink.setAudioSessionId(1);
    defaultAudioSink.enableTunnelingV21();
    defaultAudioSink.setPlaybackParameters(new PlaybackParameters(2));
    configureDefaultAudioSink(/* channelCount= */ 2);
    assertThat(
            defaultAudioSink.handleBuffer(
                createDefaultSilenceBuffer(),
                /* presentationTimeUs= */ 5 * C.MICROS_PER_SECOND,
                /* encodedAccessUnitCount= */ 1))
        .isTrue();

    assertThat(defaultAudioSink.getPlaybackParameters().speed).isEqualTo(1);
  }

  private void configureDefaultAudioSink(int channelCount) throws AudioSink.ConfigurationException {
    configureDefaultAudioSink(channelCount, /* trimStartFrames= */ 0, /* trimEndFrames= */ 0);
  }

  private void configureDefaultAudioSink(int channelCount, int trimStartFrames, int trimEndFrames)
      throws AudioSink.ConfigurationException {
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(channelCount)
            .setSampleRate(SAMPLE_RATE_44_1)
            .setEncoderDelay(trimStartFrames)
            .setEncoderPadding(trimEndFrames)
            .build();
    defaultAudioSink.configure(format, /* specifiedBufferSize= */ 0, /* outputChannels= */ null);
  }

  /** Creates a one second silence buffer for 44.1 kHz stereo 16-bit audio. */
  private static ByteBuffer createDefaultSilenceBuffer() {
    return ByteBuffer.allocateDirect(
            SAMPLE_RATE_44_1 * CHANNEL_COUNT_STEREO * BYTES_PER_FRAME_16_BIT)
        .order(ByteOrder.nativeOrder());
  }

  private interface ThrowingBooleanMethod {
    boolean run() throws Exception;
  }

  private static void retryUntilTrue(ThrowingBooleanMethod booleanMethod) throws Exception {
    long timeoutTimeMs = System.currentTimeMillis() + TIMEOUT_MS;
    while (!booleanMethod.run()) {
      if (System.currentTimeMillis() >= timeoutTimeMs) {
        throw new TimeoutException();
      }
    }
  }

  private static final class ArrayAudioBufferSink implements TeeAudioProcessor.AudioBufferSink {

    private byte[] output;

    public ArrayAudioBufferSink() {
      output = new byte[0];
    }

    @Override
    public void flush(int sampleRateHz, int channelCount, int encoding) {
      output = new byte[0];
    }

    @Override
    public void handleBuffer(ByteBuffer buffer) {
      int position = buffer.position();
      int remaining = buffer.remaining();
      output = Arrays.copyOf(output, output.length + remaining);
      buffer.get(output, 0, remaining);
      buffer.position(position);
    }
  }
}
