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
package androidx.media3.test.utils.robolectric;

import static androidx.media3.exoplayer.mediacodec.MediaCodecUtil.createCodecProfileLevel;

import android.annotation.SuppressLint;
import android.media.MediaCodecInfo;
import android.media.MediaCodecInfo.CodecProfileLevel;
import android.media.MediaFormat;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.transformer.EncoderUtil;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Ints;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.rules.ExternalResource;
import org.robolectric.shadows.MediaCodecInfoBuilder;
import org.robolectric.shadows.ShadowMediaCodec;
import org.robolectric.shadows.ShadowMediaCodecList;

/**
 * A JUnit @Rule to configure Robolectric's {@link ShadowMediaCodec}.
 *
 * <p>Registers a {@link org.robolectric.shadows.ShadowMediaCodec.CodecConfig} for each audio/video
 * MIME type known by ExoPlayer.
 */
@UnstableApi
public final class ShadowMediaCodecConfig extends ExternalResource {

  /** Class that holds information about a {@link CodecImpl} configuration. */
  public static final class CodecInfo {
    public final String codecName;
    public final String mimeType;
    public final ImmutableList<CodecProfileLevel> profileLevels;
    public final ImmutableList<Integer> colorFormats;

    /**
     * Creates an instance.
     *
     * <p>This method is equivalent to {@code CodecInfo(codecName, mimeType, ImmutableList.of(),
     * ImmutableList.of()}.
     *
     * @param codecName The name of the codec.
     * @param mimeType The MIME type of the codec.
     */
    public CodecInfo(String codecName, String mimeType) {
      this(
          codecName,
          mimeType,
          /* profileLevels= */ ImmutableList.of(),
          /* colorFormats= */ ImmutableList.of());
    }

    /**
     * Creates an instance.
     *
     * @param codecName The name of the codec.
     * @param mimeType The MIME type of the codec.
     * @param profileLevels A list of profiles and levels supported by the codec.
     * @param colorFormats A list of color formats supported by the codec.
     */
    public CodecInfo(
        String codecName,
        String mimeType,
        ImmutableList<CodecProfileLevel> profileLevels,
        ImmutableList<Integer> colorFormats) {
      this.codecName = codecName;
      this.mimeType = mimeType;
      this.profileLevels = profileLevels;
      this.colorFormats = colorFormats;
    }
  }

  public static final CodecInfo CODEC_INFO_AVC =
      new CodecInfo(
          /* codecName= */ "media3.video.avc",
          MimeTypes.VIDEO_H264,
          /* profileLevels= */ ImmutableList.of(
              createCodecProfileLevel(
                  CodecProfileLevel.AVCProfileHigh, CodecProfileLevel.AVCLevel62)),
          /* colorFormats= */ ImmutableList.of(
              MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible));
  public static final CodecInfo CODEC_INFO_HEVC =
      new CodecInfo(
          /* codecName= */ "media3.video.hevc",
          MimeTypes.VIDEO_H265,
          /* profileLevels= */ ImmutableList.of(
              createCodecProfileLevel(
                  CodecProfileLevel.HEVCProfileMain, CodecProfileLevel.HEVCMainTierLevel61)),
          /* colorFormats= */ ImmutableList.of(
              MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible));
  public static final CodecInfo CODEC_INFO_MPEG2 =
      new CodecInfo(
          /* codecName= */ "media3.video.mpeg2",
          MimeTypes.VIDEO_MPEG2,
          /* profileLevels= */ ImmutableList.of(
              createCodecProfileLevel(
                  CodecProfileLevel.MPEG2ProfileMain, CodecProfileLevel.MPEG2LevelML)),
          /* colorFormats= */ ImmutableList.of(
              MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible));
  public static final CodecInfo CODEC_INFO_VP9 =
      new CodecInfo(
          /* codecName= */ "media3.video.vp9",
          MimeTypes.VIDEO_VP9,
          /* profileLevels= */ ImmutableList.of(),
          /* colorFormats= */ ImmutableList.of(
              MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible));
  public static final CodecInfo CODEC_INFO_AV1 =
      new CodecInfo(
          /* codecName= */ "media3.video.av1",
          MimeTypes.VIDEO_AV1,
          /* profileLevels= */ ImmutableList.of(),
          /* colorFormats= */ ImmutableList.of(
              MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible));
  public static final CodecInfo CODEC_INFO_AAC =
      new CodecInfo(/* codecName= */ "media3.audio.aac", MimeTypes.AUDIO_AAC);
  public static final CodecInfo CODEC_INFO_AC3 =
      new CodecInfo(/* codecName= */ "media3.audio.ac3", MimeTypes.AUDIO_AC3);
  public static final CodecInfo CODEC_INFO_AC4 =
      new CodecInfo(/* codecName= */ "media3.audio.ac4", MimeTypes.AUDIO_AC4);
  public static final CodecInfo CODEC_INFO_AMR_NB =
      new CodecInfo(/* codecName= */ "media3.audio.amrnb", MimeTypes.AUDIO_AMR_NB);
  public static final CodecInfo CODEC_INFO_E_AC3 =
      new CodecInfo(/* codecName= */ "media3.audio.eac3", MimeTypes.AUDIO_E_AC3);
  public static final CodecInfo CODEC_INFO_E_AC3_JOC =
      new CodecInfo(/* codecName= */ "media3.audio.eac3joc", MimeTypes.AUDIO_E_AC3_JOC);
  public static final CodecInfo CODEC_INFO_ALAC =
      new CodecInfo(/* codecName= */ "media3.audio.alac", MimeTypes.AUDIO_ALAC);
  public static final CodecInfo CODEC_INFO_FLAC =
      new CodecInfo(/* codecName= */ "media3.audio.flac", MimeTypes.AUDIO_FLAC);
  public static final CodecInfo CODEC_INFO_MPEG =
      new CodecInfo(/* codecName= */ "media3.audio.mpeg", MimeTypes.AUDIO_MPEG);
  public static final CodecInfo CODEC_INFO_MPEG_L2 =
      new CodecInfo(/* codecName= */ "media3.audio.mpegl2", MimeTypes.AUDIO_MPEG_L2);
  public static final CodecInfo CODEC_INFO_OPUS =
      new CodecInfo(/* codecName= */ "media3.audio.opus", MimeTypes.AUDIO_OPUS);
  public static final CodecInfo CODEC_INFO_VORBIS =
      new CodecInfo(/* codecName= */ "media3.audio.vorbis", MimeTypes.AUDIO_VORBIS);
  // In ExoPlayer, raw audio should use a bypass mode and never need this codec. However, to easily
  // assert failures of the bypass mode we want to detect when the raw audio is decoded by this
  public static final CodecInfo CODEC_INFO_RAW =
      new CodecInfo(/* codecName= */ "media3.audio.raw", MimeTypes.AUDIO_RAW);

  private static final ImmutableSet<CodecInfo> ALL_SUPPORTED_CODECS =
      ImmutableSet.of(
          CODEC_INFO_AVC,
          CODEC_INFO_HEVC,
          CODEC_INFO_MPEG2,
          CODEC_INFO_VP9,
          CODEC_INFO_AV1,
          CODEC_INFO_AAC,
          CODEC_INFO_AC3,
          CODEC_INFO_AC4,
          CODEC_INFO_E_AC3,
          CODEC_INFO_E_AC3_JOC,
          CODEC_INFO_ALAC,
          CODEC_INFO_FLAC,
          CODEC_INFO_MPEG,
          CODEC_INFO_MPEG_L2,
          CODEC_INFO_OPUS,
          CODEC_INFO_VORBIS,
          CODEC_INFO_RAW);

  /**
   * @deprecated Use {@link ShadowMediaCodecConfig#withAllDefaultSupportedCodecs()} instead.
   */
  @Deprecated
  public static ShadowMediaCodecConfig forAllSupportedMimeTypes() {
    return withAllDefaultSupportedCodecs();
  }

  /**
   * Returns a {@link ShadowMediaCodecConfig} instance populated with a default list of supported
   * decoders using a default codec configuration.
   *
   * <p>The default codec configuration drops all samples on audio decoders and works as passthrough
   * on video decoders.
   */
  public static ShadowMediaCodecConfig withAllDefaultSupportedCodecs() {
    return new ShadowMediaCodecConfig(
        createDecoders(ALL_SUPPORTED_CODECS.asList(), /* forcePassthrough= */ false));
  }

  /**
   * @deprecated Use {@link ShadowMediaCodecConfig#withNoDefaultSupportedCodecs()} instead.
   */
  @Deprecated
  public static ShadowMediaCodecConfig withNoDefaultSupportedMimeTypes() {
    return withNoDefaultSupportedCodecs();
  }

  /** Returns a {@link ShadowMediaCodecConfig} instance populated with no shadow codecs. */
  public static ShadowMediaCodecConfig withNoDefaultSupportedCodecs() {
    return new ShadowMediaCodecConfig(ImmutableSet.of());
  }

  /**
   * Returns a {@link ShadowMediaCodecConfig} instance configured with the provided {@code decoders}
   * and {@code encoders}.
   *
   * <p>All codecs will work as passthrough, regardless of type.
   */
  public static ShadowMediaCodecConfig withCodecs(
      List<CodecInfo> decoders, List<CodecInfo> encoders) {
    ImmutableSet.Builder<CodecImpl> codecs = new ImmutableSet.Builder<>();
    codecs.addAll(createDecoders(decoders, /* forcePassthrough= */ true));
    codecs.addAll(createEncoders(encoders));
    return new ShadowMediaCodecConfig(codecs.build());
  }

  private final ImmutableSet<CodecImpl> defaultCodecs;

  private ShadowMediaCodecConfig(ImmutableSet<CodecImpl> defaultCodecs) {
    this.defaultCodecs = defaultCodecs;
  }

  /**
   * Configures a shadow MediaCodec.
   *
   * @param codecName The name of the codec.
   * @param mimeType The MIME type of the codec.
   * @param isEncoder Whether the codec is an encoder or a decoder.
   * @param profileLevels A list of profiles and levels supported by the codec.
   * @param colorFormats A list of color formats supported by the codec.
   * @param codecConfig The {@link ShadowMediaCodec.CodecConfig} for the codec, specifying its
   *     behavior.
   */
  // TODO(b/452541218): Remove this suppression once Robolectric is updated to a version that
  //  includes the @RequiresApi(Q) annotation from ShadowMediaCodecList.addCodec().
  @SuppressLint("NewApi") // The upstream annotation causing this warning was removed.
  public static void configureShadowMediaCodec(
      String codecName,
      String mimeType,
      boolean isEncoder,
      ImmutableList<CodecProfileLevel> profileLevels,
      ImmutableList<Integer> colorFormats,
      ShadowMediaCodec.CodecConfig codecConfig) {
    MediaFormat mediaFormat = new MediaFormat();
    mediaFormat.setString(MediaFormat.KEY_MIME, mimeType);
    MediaCodecInfoBuilder.CodecCapabilitiesBuilder capabilities =
        MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(mediaFormat)
            .setIsEncoder(isEncoder);
    if (!profileLevels.isEmpty()) {
      capabilities.setProfileLevels(profileLevels.toArray(new CodecProfileLevel[0]));
    }
    if (!colorFormats.isEmpty()) {
      capabilities.setColorFormats(Ints.toArray(colorFormats));
    }
    ShadowMediaCodecList.addCodec(
        MediaCodecInfoBuilder.newBuilder()
            .setName(codecName)
            .setIsEncoder(isEncoder)
            .setCapabilities(capabilities.build())
            .build());
    if (isEncoder) {
      ShadowMediaCodec.addEncoder(codecName, codecConfig);
    } else {
      ShadowMediaCodec.addDecoder(codecName, codecConfig);
    }
  }

  /**
   * Configures and publishes {@linkplain ShadowMediaCodec shadow decoders} based on {@code
   * decoders}.
   *
   * <p>This method configures frame-dropping decoders.
   */
  public void addDecoders(CodecInfo... decoders) {
    for (CodecInfo decoderInfo : decoders) {
      CodecImpl decoder = CodecImpl.createFrameDroppingDecoder(decoderInfo);
      decoder.configure();
    }
  }

  /**
   * Configures and publishes {@linkplain ShadowMediaCodec shadow encoders} based on {@code
   * encoders}.
   *
   * <p>This method configures pass-through encoders.
   */
  public void addEncoders(CodecInfo... encoders) {
    for (CodecInfo encoderInfo : encoders) {
      CodecImpl encoder = CodecImpl.createEncoder(encoderInfo);
      encoder.configure();
    }
  }

  /**
   * Configures and publishes a {@link ShadowMediaCodec} codec.
   *
   * <p>Input buffers are handled according to the {@link ShadowMediaCodec.CodecConfig} provided.
   *
   * @param codecInfo Basic codec information.
   * @param isEncoder Whether the codecs registered are encoders or decoders.
   * @param codecConfig Codec configuration implementation of the shadow.
   */
  public void addCodec(
      CodecInfo codecInfo, boolean isEncoder, ShadowMediaCodec.CodecConfig codecConfig) {
    configureShadowMediaCodec(
        codecInfo.codecName,
        codecInfo.mimeType,
        isEncoder,
        codecInfo.profileLevels,
        codecInfo.colorFormats,
        codecConfig);
  }

  @Override
  protected void before() throws Throwable {
    for (CodecImpl codec : this.defaultCodecs) {
      codec.configure();
    }
  }

  @Override
  protected void after() {
    MediaCodecUtil.clearDecoderInfoCache();
    EncoderUtil.clearCachedEncoders();
    ShadowMediaCodecList.reset();
    ShadowMediaCodec.clearCodecs();
  }

  private static ImmutableSet<CodecImpl> createDecoders(
      List<CodecInfo> decoderInfos, boolean forcePassthrough) {
    ImmutableSet.Builder<CodecImpl> builder = new ImmutableSet.Builder<>();
    for (CodecInfo info : decoderInfos) {
      if (!forcePassthrough && MimeTypes.isAudio(info.mimeType)) {
        builder.add(CodecImpl.createFrameDroppingDecoder(info));
      } else {
        builder.add(CodecImpl.createPassthroughDecoder(info));
      }
    }
    return builder.build();
  }

  private static ImmutableSet<CodecImpl> createEncoders(List<CodecInfo> encoderInfos) {
    ImmutableSet.Builder<CodecImpl> builder = new ImmutableSet.Builder<>();
    for (CodecInfo info : encoderInfos) {
      builder.add(CodecImpl.createEncoder(info));
    }
    return builder.build();
  }

  /**
   * A {@link ShadowMediaCodec.CodecConfig.Codec} that provides pass-through or frame dropping
   * encoders and decoders.
   */
  private static final class CodecImpl implements ShadowMediaCodec.CodecConfig.Codec {

    private final CodecInfo codecInfo;
    private final boolean isPassthrough;
    private final boolean isEncoder;

    public static CodecImpl createFrameDroppingDecoder(CodecInfo codecInfo) {
      return new CodecImpl(codecInfo, /* isPassthrough= */ false, /* isEncoder= */ false);
    }

    public static CodecImpl createPassthroughDecoder(CodecInfo codecInfo) {
      return new CodecImpl(codecInfo, /* isPassthrough= */ true, /* isEncoder= */ false);
    }

    public static CodecImpl createEncoder(CodecInfo codecInfo) {
      return new CodecImpl(codecInfo, /* isPassthrough= */ true, /* isEncoder= */ true);
    }

    /**
     * Creates an instance.
     *
     * @param codecInfo The {@link CodecInfo} that holds the codec information.
     * @param isPassthrough If {@code true}, the codec acts as a pass-through codec, directly
     *     copying input data to the output. If {@code false}, the codec drops frames.
     * @param isEncoder If {@code true}, the codec is an encoder. If {@code false}, the codec is a
     *     decoder.
     */
    private CodecImpl(CodecInfo codecInfo, boolean isPassthrough, boolean isEncoder) {
      this.codecInfo = codecInfo;
      this.isPassthrough = isPassthrough;
      this.isEncoder = isEncoder;
    }

    public void configure() {
      // TODO: Update ShadowMediaCodec to consider the MediaFormat.KEY_MAX_INPUT_SIZE value passed
      // to configure() so we don't have to specify large buffers here.
      int bufferSize = MimeTypes.isVideo(codecInfo.mimeType) ? 250_000 : 20_000;
      configureShadowMediaCodec(
          codecInfo.codecName,
          codecInfo.mimeType,
          isEncoder,
          codecInfo.profileLevels,
          codecInfo.colorFormats,
          new ShadowMediaCodec.CodecConfig(
              /* inputBufferSize= */ bufferSize,
              /* outputBufferSize= */ bufferSize,
              /* codec= */ this));
    }

    @Override
    public void process(ByteBuffer in, ByteBuffer out) {
      // TODO(internal b/174737370): Output audio bytes as well.
      if (isPassthrough) {
        out.put(in);
      } else {
        byte[] bytes = new byte[in.remaining()];
        in.get(bytes);
      }
    }
  }
}
