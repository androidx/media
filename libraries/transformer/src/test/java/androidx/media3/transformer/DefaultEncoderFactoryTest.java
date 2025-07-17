/*
 * Copyright 2022 The Android Open Source Project
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

package androidx.media3.transformer;

import static android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel4;
import static android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh;
import static androidx.media3.exoplayer.mediacodec.MediaCodecUtil.createCodecProfileLevel;
import static androidx.media3.transformer.ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecInfo.CodecProfileLevel;
import android.media.MediaFormat;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.MediaCodecInfoBuilder;
import org.robolectric.shadows.ShadowBuild;
import org.robolectric.shadows.ShadowMediaCodec;
import org.robolectric.shadows.ShadowMediaCodecList;

/** Unit test for {@link DefaultEncoderFactory}. */
@RunWith(AndroidJUnit4.class)
public class DefaultEncoderFactoryTest {
  private final Context context = getApplicationContext();

  @Before
  public void setUp() {
    createShadowH264Encoder();
    createShadowAacEncoder();
  }

  @After
  public void tearDown() {
    ShadowMediaCodec.clearCodecs();
    ShadowMediaCodecList.reset();
    EncoderUtil.clearCachedEncoders();
  }

  private static void createShadowH264Encoder() {
    MediaFormat avcFormat = new MediaFormat();
    avcFormat.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_AVC);
    // Using Level4 gives us 8192 16x16 blocks. If using width 1920 uses 120 blocks, 8192 / 120 = 68
    // blocks will be left for encoding height 1088.
    CodecProfileLevel profileLevel = createCodecProfileLevel(AVCProfileHigh, AVCLevel4);

    createShadowVideoEncoder(avcFormat, profileLevel, "test.transformer.avc.encoder");
  }

  private static void createShadowAacEncoder() {
    MediaFormat format = new MediaFormat();
    format.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_AUDIO_AAC);
    MediaCodecInfo.CodecCapabilities capabilities =
        MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(format)
            .setIsEncoder(true)
            .build();
    createShadowEncoder("test.transformer.aac.encoder", capabilities);
  }

  private static void createShadowVideoEncoder(
      MediaFormat supportedFormat, CodecProfileLevel supportedProfileLevel, String name) {
    MediaCodecInfo.CodecCapabilities capabilities =
        MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(supportedFormat)
            .setIsEncoder(true)
            .setColorFormats(
                new int[] {MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible})
            .setProfileLevels(new CodecProfileLevel[] {supportedProfileLevel})
            .build();
    createShadowEncoder(name, capabilities);
  }

  private static void createShadowEncoder(
      String name, MediaCodecInfo.CodecCapabilities... capabilities) {
    // ShadowMediaCodecList is static. The added encoders will be visible for every test.
    ShadowMediaCodecList.addCodec(
        MediaCodecInfoBuilder.newBuilder()
            .setName(name)
            .setIsEncoder(true)
            .setCapabilities(capabilities)
            .build());
  }

  @Test
  public void createForVideoEncoding_withFallbackOnAndSupportedInputFormat_configuresEncoder()
      throws Exception {
    Format requestedVideoFormat = createVideoFormat(MimeTypes.VIDEO_H264, 1920, 1080, 30);
    Format actualVideoFormat =
        new DefaultEncoderFactory.Builder(context)
            .build()
            .createForVideoEncoding(requestedVideoFormat, /* logSessionId= */ null)
            .getConfigurationFormat();

    assertThat(actualVideoFormat.sampleMimeType).isEqualTo(MimeTypes.VIDEO_H264);
    assertThat(actualVideoFormat.width).isEqualTo(1920);
    assertThat(actualVideoFormat.height).isEqualTo(1080);
    // 1920 * 1080 * 30 * 0.07 * 2.
    assertThat(actualVideoFormat.averageBitrate).isEqualTo(8_709_120);
  }

  @Test
  public void createForVideoEncoding_withFallbackOnAndUnsupportedMimeType_throws() {
    Format requestedVideoFormat = createVideoFormat(MimeTypes.VIDEO_H265, 1920, 1080, 30);
    DefaultEncoderFactory encoderFactory = new DefaultEncoderFactory.Builder(context).build();

    ExportException exportException =
        assertThrows(
            ExportException.class,
            () ->
                encoderFactory.createForVideoEncoding(
                    requestedVideoFormat, /* logSessionId= */ null));
    assertThat(exportException.errorCode).isEqualTo(ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED);
  }

  @Test
  public void createForVideoEncoding_withFallbackOnAndUnsupportedResolution_configuresEncoder()
      throws Exception {
    Format requestedVideoFormat = createVideoFormat(MimeTypes.VIDEO_H264, 3840, 2160, 60);
    Format actualVideoFormat =
        new DefaultEncoderFactory.Builder(context)
            .build()
            .createForVideoEncoding(requestedVideoFormat, /* logSessionId= */ null)
            .getConfigurationFormat();

    assertThat(actualVideoFormat.width).isEqualTo(1920);
    assertThat(actualVideoFormat.height).isEqualTo(1080);
  }

  @Test
  public void
      createForVideoEncoding_setFormatAverageBitrateUnsetVideoEncoderSettings_configuresEncoderUsingFormatAverageBitrate()
          throws Exception {
    Format requestedVideoFormat = createVideoFormat(MimeTypes.VIDEO_H264, 1920, 1080, 30);
    requestedVideoFormat = requestedVideoFormat.buildUpon().setAverageBitrate(5_000_000).build();

    Format actualVideoFormat =
        new DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.DEFAULT)
            .build()
            .createForVideoEncoding(requestedVideoFormat, /* logSessionId= */ null)
            .getConfigurationFormat();

    assertThat(actualVideoFormat.sampleMimeType).isEqualTo(MimeTypes.VIDEO_H264);
    assertThat(actualVideoFormat.width).isEqualTo(1920);
    assertThat(actualVideoFormat.height).isEqualTo(1080);
    assertThat(actualVideoFormat.averageBitrate).isEqualTo(5_000_000);
  }

  @Test
  public void
      createForVideoEncoding_unsetFormatAverageBitrateAndUnsetVideoEncoderSettingsBitrate_configuresEncoderUsingDefaultBitrateMapping()
          throws Exception {
    Format requestedVideoFormat = createVideoFormat(MimeTypes.VIDEO_H264, 1920, 1080, 30);
    Format actualVideoFormat =
        new DefaultEncoderFactory.Builder(context)
            .build()
            .createForVideoEncoding(requestedVideoFormat, /* logSessionId= */ null)
            .getConfigurationFormat();

    assertThat(actualVideoFormat.sampleMimeType).isEqualTo(MimeTypes.VIDEO_H264);
    assertThat(actualVideoFormat.width).isEqualTo(1920);
    assertThat(actualVideoFormat.height).isEqualTo(1080);
    // The default behavior is to use DefaultEncoderFactory#getSuggestedBitrate.
    // 1920 * 1080 * 30 * 0.07 * 2.
    assertThat(actualVideoFormat.averageBitrate).isEqualTo(8_709_120);
  }

  @Test
  public void
      createForVideoEncoding_setFormatAverageBitrateAndVideoEncoderSettingsBitrate_configuresEncoderUsingVideoEncoderSettingsBitrate()
          throws Exception {
    Format requestedVideoFormat = createVideoFormat(MimeTypes.VIDEO_H264, 1920, 1080, 30);
    requestedVideoFormat = requestedVideoFormat.buildUpon().setAverageBitrate(5_000_000).build();

    Format actualVideoFormat =
        new DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                new VideoEncoderSettings.Builder().setBitrate(10_000_000).build())
            .build()
            .createForVideoEncoding(requestedVideoFormat, /* logSessionId= */ null)
            .getConfigurationFormat();

    assertThat(actualVideoFormat.sampleMimeType).isEqualTo(MimeTypes.VIDEO_H264);
    assertThat(actualVideoFormat.width).isEqualTo(1920);
    assertThat(actualVideoFormat.height).isEqualTo(1080);
    assertThat(actualVideoFormat.averageBitrate).isEqualTo(10_000_000);
  }

  @Config(sdk = 29)
  @Test
  public void
      createForVideoEncoding_withH264EncodingOnApi31_configuresEncoderWithCorrectPerformanceSettings()
          throws Exception {
    Format requestedVideoFormat = createVideoFormat(MimeTypes.VIDEO_H264, 1920, 1080, 30);
    Codec videoEncoder =
        new DefaultEncoderFactory.Builder(context)
            .build()
            .createForVideoEncoding(requestedVideoFormat, /* logSessionId= */ null);

    assertThat(videoEncoder).isInstanceOf(DefaultCodec.class);
    MediaFormat configurationMediaFormat =
        ((DefaultCodec) videoEncoder).getConfigurationMediaFormat();
    assertThat(configurationMediaFormat.containsKey(MediaFormat.KEY_PRIORITY)).isTrue();
    assertThat(configurationMediaFormat.getInteger(MediaFormat.KEY_PRIORITY)).isEqualTo(1);
    assertThat(configurationMediaFormat.containsKey(MediaFormat.KEY_OPERATING_RATE)).isTrue();
    assertThat(configurationMediaFormat.getInteger(MediaFormat.KEY_OPERATING_RATE))
        .isEqualTo(Integer.MAX_VALUE);
  }

  @Config(sdk = 31)
  @Test
  public void
      createForVideoEncoding_withH264EncodingOnApi29AndConservativeDefault_configuresEncoderWithCorrectPerformanceSettings()
          throws Exception {
    Format requestedVideoFormat = createVideoFormat(MimeTypes.VIDEO_H264, 1920, 1080, 30);
    Codec videoEncoder =
        new DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                new VideoEncoderSettings.Builder()
                    .setEncoderPerformanceParameters(/* operatingRate= */ -1, /* priority= */ 1)
                    .build())
            .build()
            .createForVideoEncoding(requestedVideoFormat, /* logSessionId= */ null);

    assertThat(videoEncoder).isInstanceOf(DefaultCodec.class);
    MediaFormat configurationMediaFormat =
        ((DefaultCodec) videoEncoder).getConfigurationMediaFormat();
    assertThat(configurationMediaFormat.containsKey(MediaFormat.KEY_PRIORITY)).isTrue();
    assertThat(configurationMediaFormat.getInteger(MediaFormat.KEY_PRIORITY)).isEqualTo(1);
    assertThat(configurationMediaFormat.containsKey(MediaFormat.KEY_OPERATING_RATE)).isTrue();
    assertThat(configurationMediaFormat.getInteger(MediaFormat.KEY_OPERATING_RATE)).isEqualTo(-1);
  }

  @Config(sdk = 31)
  @Test
  public void
      createForVideoEncoding_withOperatingRateUnset_configuresEncoderWithCorrectPerformanceSettings()
          throws Exception {
    Format requestedVideoFormat = createVideoFormat(MimeTypes.VIDEO_H264, 1920, 1080, 30);
    Codec videoEncoder =
        new DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                new VideoEncoderSettings.Builder()
                    .setEncoderPerformanceParameters(
                        /* operatingRate= */ VideoEncoderSettings.RATE_UNSET, /* priority= */ 1)
                    .build())
            .build()
            .createForVideoEncoding(requestedVideoFormat, /* logSessionId= */ null);

    assertThat(videoEncoder).isInstanceOf(DefaultCodec.class);
    MediaFormat configurationMediaFormat =
        ((DefaultCodec) videoEncoder).getConfigurationMediaFormat();
    assertThat(configurationMediaFormat.containsKey(MediaFormat.KEY_PRIORITY)).isTrue();
    assertThat(configurationMediaFormat.getInteger(MediaFormat.KEY_PRIORITY)).isEqualTo(1);
    assertThat(configurationMediaFormat.containsKey(MediaFormat.KEY_OPERATING_RATE)).isFalse();
  }

  @Config(sdk = 31)
  @Test
  public void
      createForVideoEncoding_withOperatingRatePriorityUnset_configuresEncoderWithCorrectPerformanceSettings()
          throws Exception {
    Format requestedVideoFormat = createVideoFormat(MimeTypes.VIDEO_H264, 1920, 1080, 30);
    Codec videoEncoder =
        new DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                new VideoEncoderSettings.Builder()
                    .setEncoderPerformanceParameters(
                        VideoEncoderSettings.RATE_UNSET, VideoEncoderSettings.RATE_UNSET)
                    .build())
            .build()
            .createForVideoEncoding(requestedVideoFormat, /* logSessionId= */ null);

    assertThat(videoEncoder).isInstanceOf(DefaultCodec.class);
    MediaFormat configurationMediaFormat =
        ((DefaultCodec) videoEncoder).getConfigurationMediaFormat();
    assertThat(configurationMediaFormat.containsKey(MediaFormat.KEY_PRIORITY)).isFalse();
    assertThat(configurationMediaFormat.containsKey(MediaFormat.KEY_OPERATING_RATE)).isFalse();
  }

  @Test
  public void
      createForVideoEncoding_withRepeatPreviousFrameIntervalUs_configuresEncoderWithRepeatPreviousFrameIntervalUs()
          throws Exception {
    Format requestedVideoFormat = createVideoFormat(MimeTypes.VIDEO_H264, 1920, 1080, 30);
    DefaultCodec videoEncoder =
        new DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                new VideoEncoderSettings.Builder().setRepeatPreviousFrameIntervalUs(33_333).build())
            .build()
            .createForVideoEncoding(requestedVideoFormat, /* logSessionId= */ null);

    assertThat(
            videoEncoder
                .getConfigurationMediaFormat()
                .getLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER))
        .isEqualTo(33_333);
  }

  @Test
  public void
      createForVideoEncoding_withDefaultEncoderSettings_doesNotConfigureRepeatPreviousFrameIntervalUs()
          throws Exception {
    Format requestedVideoFormat = createVideoFormat(MimeTypes.VIDEO_H264, 1920, 1080, 30);
    DefaultCodec videoEncoder =
        new DefaultEncoderFactory.Builder(context)
            .build()
            .createForVideoEncoding(requestedVideoFormat, /* logSessionId= */ null);

    assertThat(
            videoEncoder
                .getConfigurationMediaFormat()
                .containsKey(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER))
        .isFalse();
  }

  @Test
  @Config(sdk = 29)
  public void createForVideoEncoding_withMaxBFrames_configuresEncoderWithMaxBFrames()
      throws Exception {
    Format requestedVideoFormat = createVideoFormat(MimeTypes.VIDEO_H264, 1920, 1080, 30);

    DefaultCodec videoEncoder =
        new DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                new VideoEncoderSettings.Builder().setMaxBFrames(3).build())
            .build()
            .createForVideoEncoding(requestedVideoFormat, /* logSessionId= */ null);

    assertThat(videoEncoder.getConfigurationMediaFormat().getInteger(MediaFormat.KEY_MAX_B_FRAMES))
        .isEqualTo(3);
  }

  @Test
  @Config(sdk = 23)
  public void createForVideoEncoding_withMaxBFramesOnApi23_doesNotConfigureMaxBFrames()
      throws Exception {
    Format requestedVideoFormat = createVideoFormat(MimeTypes.VIDEO_H264, 1920, 1080, 30);

    DefaultCodec videoEncoder =
        new DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                new VideoEncoderSettings.Builder().setMaxBFrames(3).build())
            .build()
            .createForVideoEncoding(requestedVideoFormat, /* logSessionId= */ null);

    assertThat(videoEncoder.getConfigurationMediaFormat().containsKey(MediaFormat.KEY_MAX_B_FRAMES))
        .isFalse();
  }

  @Test
  @Config(sdk = 29)
  public void createForVideoEncoding_withDefaultEncoderSettings_doesNotConfigureMaxBFrames()
      throws Exception {
    Format requestedVideoFormat = createVideoFormat(MimeTypes.VIDEO_H264, 1920, 1080, 30);

    DefaultCodec videoEncoder =
        new DefaultEncoderFactory.Builder(context)
            .build()
            .createForVideoEncoding(requestedVideoFormat, /* logSessionId= */ null);

    assertThat(videoEncoder.getConfigurationMediaFormat().containsKey(MediaFormat.KEY_MAX_B_FRAMES))
        .isFalse();
  }

  @Config(sdk = 29)
  @Test
  public void
      createForVideoEncoding_withTemporalLayeringSchemaWithZeroLayers_configuresEncoderWithTemporalLayeringSchema()
          throws Exception {
    Format requestedVideoFormat = createVideoFormat(MimeTypes.VIDEO_H264, 1920, 1080, 30);

    DefaultCodec videoEncoder =
        new DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                new VideoEncoderSettings.Builder()
                    .setTemporalLayers(
                        /* numNonBidirectionalLayers= */ 0, /* numBidirectionalLayers= */ 0)
                    .build())
            .build()
            .createForVideoEncoding(requestedVideoFormat, /* logSessionId= */ null);

    assertThat(
            videoEncoder.getConfigurationMediaFormat().getString(MediaFormat.KEY_TEMPORAL_LAYERING))
        .isEqualTo("none");
  }

  @Config(sdk = 29)
  @Test
  public void
      createForVideoEncoding_withTemporalLayeringSchemaWithoutBidirectionalLayers_configuresEncoderWithTemporalLayeringSchema()
          throws Exception {
    Format requestedVideoFormat = createVideoFormat(MimeTypes.VIDEO_H264, 1920, 1080, 30);

    DefaultCodec videoEncoder =
        new DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                new VideoEncoderSettings.Builder()
                    .setTemporalLayers(
                        /* numNonBidirectionalLayers= */ 1, /* numBidirectionalLayers= */ 0)
                    .build())
            .build()
            .createForVideoEncoding(requestedVideoFormat, /* logSessionId= */ null);

    assertThat(
            videoEncoder.getConfigurationMediaFormat().getString(MediaFormat.KEY_TEMPORAL_LAYERING))
        .isEqualTo("android.generic.1");
  }

  @Config(sdk = 29)
  @Test
  public void
      createForVideoEncoding_withTemporalLayeringSchemaWithBidirectionalLayers_configuresEncoderWithTemporalLayeringSchema()
          throws Exception {
    Format requestedVideoFormat = createVideoFormat(MimeTypes.VIDEO_H264, 1920, 1080, 30);

    DefaultCodec videoEncoder =
        new DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                new VideoEncoderSettings.Builder()
                    .setTemporalLayers(
                        /* numNonBidirectionalLayers= */ 1, /* numBidirectionalLayers= */ 2)
                    .build())
            .build()
            .createForVideoEncoding(requestedVideoFormat, /* logSessionId= */ null);

    assertThat(
            videoEncoder.getConfigurationMediaFormat().getString(MediaFormat.KEY_TEMPORAL_LAYERING))
        .isEqualTo("android.generic.1+2");
  }

  @Config(sdk = 23)
  @Test
  public void
      createForVideoEncoding_withTemporalLayeringSchemaOnApi23_doesNotConfigureTemporalLayeringSchema()
          throws Exception {
    Format requestedVideoFormat = createVideoFormat(MimeTypes.VIDEO_H264, 1920, 1080, 30);

    DefaultCodec videoEncoder =
        new DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                new VideoEncoderSettings.Builder()
                    .setTemporalLayers(
                        /* numNonBidirectionalLayers= */ 1, /* numBidirectionalLayers= */ 2)
                    .build())
            .build()
            .createForVideoEncoding(requestedVideoFormat, /* logSessionId= */ null);

    assertThat(
            videoEncoder
                .getConfigurationMediaFormat()
                .containsKey(MediaFormat.KEY_TEMPORAL_LAYERING))
        .isFalse();
  }

  @Config(sdk = 29)
  @Test
  public void
      createForVideoEncoding_withDefaultEncoderSettings_doesNotConfigureTemporalLayeringSchema()
          throws Exception {
    Format requestedVideoFormat = createVideoFormat(MimeTypes.VIDEO_H264, 1920, 1080, 30);

    DefaultCodec videoEncoder =
        new DefaultEncoderFactory.Builder(context)
            .build()
            .createForVideoEncoding(requestedVideoFormat, /* logSessionId= */ null);

    assertThat(
            videoEncoder
                .getConfigurationMediaFormat()
                .containsKey(MediaFormat.KEY_TEMPORAL_LAYERING))
        .isFalse();
  }

  @Test
  public void createForVideoEncoding_withNoAvailableEncoderFromEncoderSelector_throws() {
    Format requestedVideoFormat = createVideoFormat(MimeTypes.VIDEO_H264, 1920, 1080, 30);
    assertThrows(
        ExportException.class,
        () ->
            new DefaultEncoderFactory.Builder(context)
                .setVideoEncoderSelector((mimeType) -> ImmutableList.of())
                .build()
                .createForVideoEncoding(requestedVideoFormat, /* logSessionId= */ null));
  }

  @Test
  @Config(sdk = 31)
  public void createForVideoEncoding_withCodecDbLiteEnabled_configuresEncoderWithCodecDbLite()
      throws Exception {
    ShadowBuild.setSystemOnChipManufacturer("QTI");
    ShadowBuild.setSystemOnChipModel("SM8550");

    Format requestedVideoFormat = createVideoFormat(MimeTypes.VIDEO_H264, 1920, 1080, 30);

    DefaultCodec videoEncoder =
        new DefaultEncoderFactory.Builder(context)
            .setEnableCodecDbLite(true)
            .build()
            .createForVideoEncoding(requestedVideoFormat, /* logSessionId= */ null);
    assertThat(videoEncoder.getConfigurationMediaFormat().getInteger(MediaFormat.KEY_MAX_B_FRAMES))
        .isEqualTo(1);
    assertThat(
            videoEncoder.getConfigurationMediaFormat().getString(MediaFormat.KEY_TEMPORAL_LAYERING))
        .isEqualTo("android.generic.1+2");
  }

  // TODO: b/419347899 - Update CodecDB Lite tests once hardware identification on pre-API 31
  // devices is supported for greater coverage across media3-supported devices.
  @Test
  @Config(sdk = 31)
  public void createForVideoEncoding_withCodecDbLiteEnabled_doesNotOverwriteUserRequestedSettings()
      throws Exception {
    ShadowBuild.setSystemOnChipManufacturer("QTI");
    ShadowBuild.setSystemOnChipModel("SM8550");

    Format requestedVideoFormat = createVideoFormat(MimeTypes.VIDEO_H264, 1920, 1080, 30);

    DefaultCodec videoEncoder =
        new DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                new VideoEncoderSettings.Builder()
                    .setMaxBFrames(0)
                    .setTemporalLayers(
                        /* numNonBidirectionalLayers= */ 0, /* numBidirectionalLayers= */ 0)
                    .build())
            .setEnableCodecDbLite(true)
            .build()
            .createForVideoEncoding(requestedVideoFormat, /* logSessionId= */ null);
    assertThat(videoEncoder.getConfigurationMediaFormat().getInteger(MediaFormat.KEY_MAX_B_FRAMES))
        .isEqualTo(0);
    assertThat(
            videoEncoder.getConfigurationMediaFormat().getString(MediaFormat.KEY_TEMPORAL_LAYERING))
        .isEqualTo("none");
  }

  @Test
  @Config(sdk = 31)
  public void createForVideoEncoding_withCodecDbLiteDisabled_doesNotUseCodecDbLite()
      throws Exception {
    ShadowBuild.setSystemOnChipManufacturer("QTI");
    ShadowBuild.setSystemOnChipModel("SM8550");

    Format requestedVideoFormat = createVideoFormat(MimeTypes.VIDEO_H264, 1920, 1080, 30);

    DefaultCodec videoEncoder =
        new DefaultEncoderFactory.Builder(context)
            .setEnableCodecDbLite(false)
            .build()
            .createForVideoEncoding(requestedVideoFormat, /* logSessionId= */ null);

    assertThat(videoEncoder.getConfigurationMediaFormat().containsKey(MediaFormat.KEY_MAX_B_FRAMES))
        .isFalse();
    assertThat(
            videoEncoder
                .getConfigurationMediaFormat()
                .containsKey(MediaFormat.KEY_TEMPORAL_LAYERING))
        .isFalse();
  }

  @Test
  public void createForAudioEncoding_unsupportedSampleRateWithFallback() throws Exception {
    int highestSupportedSampleRate = 96_000;
    int unsupportedSampleRate = 192_000;
    Format requestedAudioFormat = createAudioFormat(MimeTypes.AUDIO_AAC, unsupportedSampleRate);

    DefaultCodec codec =
        new DefaultEncoderFactory.Builder(context)
            .setEnableFallback(true)
            .build()
            .createForAudioEncoding(requestedAudioFormat, /* logSessionId= */ null);

    Format inputFormat = codec.getInputFormat();
    Format configurationFormat = codec.getConfigurationFormat();
    Format outputFormat = codec.getOutputFormat();
    assertThat(outputFormat).isNotNull();
    assertThat(inputFormat.sampleMimeType).isEqualTo(MimeTypes.AUDIO_AAC);
    assertThat(configurationFormat.sampleMimeType).isEqualTo(MimeTypes.AUDIO_AAC);
    assertThat(outputFormat.sampleMimeType).isEqualTo(MimeTypes.AUDIO_AAC);
    assertThat(inputFormat.sampleRate).isEqualTo(highestSupportedSampleRate);
    assertThat(configurationFormat.sampleRate).isEqualTo(highestSupportedSampleRate);
    assertThat(outputFormat.sampleRate).isEqualTo(highestSupportedSampleRate);
  }

  private static Format createVideoFormat(String mimeType, int width, int height, int frameRate) {
    return new Format.Builder()
        .setWidth(width)
        .setHeight(height)
        .setFrameRate(frameRate)
        .setRotationDegrees(0)
        .setSampleMimeType(mimeType)
        .build();
  }

  private static Format createAudioFormat(String mimeType, int sampleRate) {
    return new Format.Builder().setSampleRate(sampleRate).setSampleMimeType(mimeType).build();
  }
}
