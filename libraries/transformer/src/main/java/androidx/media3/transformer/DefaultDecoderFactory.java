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

import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.MediaFormatUtil.createMediaFormatFromFormat;
import static androidx.media3.common.util.Util.SDK_INT;
import static java.lang.Math.max;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Pair;
import android.view.Surface;
import androidx.annotation.IntRange;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.MediaFormatUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayList;
import java.util.List;

/**
 * Default implementation of {@link Codec.DecoderFactory} that uses {@link MediaCodec} for decoding.
 */
@UnstableApi
public final class DefaultDecoderFactory implements Codec.DecoderFactory {

  private static final String TAG = "DefaultDecoderFactory";

  /** Listener for decoder factory events. */
  public interface Listener {
    /**
     * Reports that a codec was initialized.
     *
     * <p>Called on the thread that is using the associated factory.
     *
     * @param codecName The {@linkplain MediaCodec#getName() name of the codec} that was
     *     initialized.
     * @param codecInitializationExceptions The list of non-fatal errors that occurred before the
     *     codec was successfully initialized, which is empty if no errors occurred.
     */
    void onCodecInitialized(String codecName, List<ExportException> codecInitializationExceptions);
  }

  /** A builder for {@link DefaultDecoderFactory} instances. */
  public static final class Builder {
    private final Context context;
    private Listener listener;
    private boolean enableDecoderFallback;
    private @C.Priority int codecPriority;
    private MediaCodecSelector mediaCodecSelector;

    /** Creates a new {@link Builder}. */
    public Builder(Context context) {
      this.context = context.getApplicationContext();
      listener = (codecName, codecInitializationExceptions) -> {};
      codecPriority = C.PRIORITY_PROCESSING_FOREGROUND;
      mediaCodecSelector = MediaCodecSelector.DEFAULT;
    }

    /** Sets the {@link Listener}. */
    @CanIgnoreReturnValue
    public Builder setListener(Listener listener) {
      this.listener = listener;
      return this;
    }

    /**
     * Sets whether the decoder can fallback.
     *
     * <p>This decides whether to enable fallback to lower-priority decoders if decoder
     * initialization fails. This may result in using a decoder that is less efficient or slower
     * than the primary decoder.
     *
     * <p>The default value is {@code false}.
     */
    @CanIgnoreReturnValue
    public Builder setEnableDecoderFallback(boolean enableDecoderFallback) {
      this.enableDecoderFallback = enableDecoderFallback;
      return this;
    }

    /**
     * Sets the codec priority.
     *
     * <p>Specifying codec priority allows the resource manager in the platform to reclaim less
     * important codecs before more important codecs.
     *
     * <p>It is recommended to use predefined {@linkplain C.Priority priorities} like {@link
     * C#PRIORITY_PROCESSING_FOREGROUND}, {@link C#PRIORITY_PROCESSING_BACKGROUND} or priority
     * values defined relative to those defaults.
     *
     * <p>This method is a no-op on API versions before 35.
     *
     * <p>The default value is {@link C#PRIORITY_PROCESSING_FOREGROUND}.
     *
     * @param codecPriority The {@link C.Priority} for the codec. Should be at most {@link
     *     C#PRIORITY_MAX}.
     */
    @CanIgnoreReturnValue
    public Builder setCodecPriority(@IntRange(to = C.PRIORITY_MAX) @C.Priority int codecPriority) {
      this.codecPriority = codecPriority;
      return this;
    }

    /**
     * Sets the {@link MediaCodecSelector} used when selecting a decoder.
     *
     * <p>The default value is {@link MediaCodecSelector#DEFAULT}
     */
    @CanIgnoreReturnValue
    public Builder setMediaCodecSelector(MediaCodecSelector mediaCodecSelector) {
      this.mediaCodecSelector = mediaCodecSelector;
      return this;
    }

    /** Creates an instance of {@link DefaultDecoderFactory}, using defaults if values are unset. */
    public DefaultDecoderFactory build() {
      return new DefaultDecoderFactory(this);
    }
  }

  private final Context context;
  private final boolean enableDecoderFallback;
  private final Listener listener;
  private final @C.Priority int codecPriority;
  private final MediaCodecSelector mediaCodecSelector;

  /**
   * @deprecated Use {@link Builder} instead.
   */
  @Deprecated
  public DefaultDecoderFactory(Context context) {
    this(new Builder(context));
  }

  /**
   * Creates a new factory that selects the most preferred decoder, optionally falling back to less
   * preferred decoders if initialization fails.
   *
   * @param context The context.
   * @param enableDecoderFallback Whether to enable fallback to lower-priority decoders if decoder
   *     initialization fails. This may result in using a decoder that is less efficient or slower
   *     than the primary decoder.
   * @param listener Listener for codec initialization errors.
   * @deprecated Use {@link Builder} instead.
   */
  @Deprecated
  public DefaultDecoderFactory(Context context, boolean enableDecoderFallback, Listener listener) {
    this(
        new Builder(context).setEnableDecoderFallback(enableDecoderFallback).setListener(listener));
  }

  private DefaultDecoderFactory(Builder builder) {
    this.context = builder.context;
    this.enableDecoderFallback = builder.enableDecoderFallback;
    this.listener = builder.listener;
    this.codecPriority = builder.codecPriority;
    this.mediaCodecSelector = builder.mediaCodecSelector;
  }

  @Override
  public DefaultCodec createForAudioDecoding(Format format) throws ExportException {
    MediaFormat mediaFormat = createMediaFormatFromFormat(format);
    return createCodecForMediaFormat(
        mediaFormat, format, /* outputSurface= */ null, /* devicePrefersSoftwareDecoder= */ false);
  }

  @SuppressLint("InlinedApi")
  @Override
  public DefaultCodec createForVideoDecoding(
      Format format, Surface outputSurface, boolean requestSdrToneMapping) throws ExportException {
    if (ColorInfo.isTransferHdr(format.colorInfo)) {
      if (requestSdrToneMapping
          && (SDK_INT < 31
              || deviceNeedsDisableToneMappingWorkaround(
                  checkNotNull(format.colorInfo).colorTransfer))) {
        throw createExportException(
            format, /* reason= */ "Tone-mapping HDR is not supported on this device.");
      }
      if (SDK_INT < 29) {
        // TODO(b/266837571, b/267171669): Remove API version restriction after fixing linked bugs.
        throw createExportException(
            format, /* reason= */ "Decoding HDR is not supported on this device.");
      }
    }
    if (deviceNeedsDisable8kWorkaround(format)) {
      throw createExportException(
          format, /* reason= */ "Decoding 8k is not supported on this device.");
    }
    if (deviceNeedsNoFrameRateWorkaround()) {
      format = format.buildUpon().setFrameRate(Format.NO_VALUE).build();
    }

    MediaFormat mediaFormat = createMediaFormatFromFormat(format);
    if (decoderSupportsKeyAllowFrameDrop(context)) {
      // This key ensures no frame dropping when the decoder's output surface is full. This allows
      // transformer to decode as many frames as possible in one render cycle.
      mediaFormat.setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, 0);
    }
    if (SDK_INT >= 31 && requestSdrToneMapping) {
      mediaFormat.setInteger(
          MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
    }

    @Nullable
    Pair<Integer, Integer> codecProfileAndLevel = MediaCodecUtil.getCodecProfileAndLevel(format);
    if (codecProfileAndLevel != null) {
      MediaFormatUtil.maybeSetInteger(
          mediaFormat, MediaFormat.KEY_PROFILE, codecProfileAndLevel.first);
      MediaFormatUtil.maybeSetInteger(
          mediaFormat, MediaFormat.KEY_LEVEL, codecProfileAndLevel.second);
    }

    if (SDK_INT >= 35) {
      // TODO: b/333552477 - Redefinition of MediaFormat.KEY_IMPORTANCE, remove after API35 is
      //  released.
      mediaFormat.setInteger("importance", max(0, -codecPriority));
    }

    return createCodecForMediaFormat(
        mediaFormat, format, outputSurface, devicePrefersSoftwareDecoder(format));
  }

  private DefaultCodec createCodecForMediaFormat(
      MediaFormat mediaFormat,
      Format format,
      @Nullable Surface outputSurface,
      boolean devicePrefersSoftwareDecoder)
      throws ExportException {
    List<MediaCodecInfo> decoderInfos = ImmutableList.of();
    checkNotNull(format.sampleMimeType);
    try {
      decoderInfos =
          MediaCodecUtil.getDecoderInfosSortedByFormatSupport(
              MediaCodecUtil.getDecoderInfosSoftMatch(
                  mediaCodecSelector,
                  format,
                  /* requiresSecureDecoder= */ false,
                  /* requiresTunnelingDecoder= */ false),
              format);
    } catch (MediaCodecUtil.DecoderQueryException e) {
      Log.e(TAG, "Error querying decoders", e);
      throw createExportException(format, /* reason= */ "Querying codecs failed");
    }
    if (decoderInfos.isEmpty()) {
      throw createExportException(format, /* reason= */ "No decoders for format");
    }
    if (devicePrefersSoftwareDecoder) {
      List<MediaCodecInfo> softwareDecoderInfos = new ArrayList<>();
      for (int i = 0; i < decoderInfos.size(); ++i) {
        MediaCodecInfo mediaCodecInfo = decoderInfos.get(i);
        if (!mediaCodecInfo.hardwareAccelerated) {
          softwareDecoderInfos.add(mediaCodecInfo);
        }
      }
      if (!softwareDecoderInfos.isEmpty()) {
        decoderInfos = softwareDecoderInfos;
      }
    }

    List<ExportException> codecInitExceptions = new ArrayList<>();
    DefaultCodec codec =
        createCodecFromDecoderInfos(
            context,
            enableDecoderFallback ? decoderInfos : decoderInfos.subList(0, 1),
            format,
            mediaFormat,
            outputSurface,
            codecInitExceptions);
    listener.onCodecInitialized(codec.getName(), codecInitExceptions);
    return codec;
  }

  private static DefaultCodec createCodecFromDecoderInfos(
      Context context,
      List<MediaCodecInfo> decoderInfos,
      Format format,
      MediaFormat mediaFormat,
      @Nullable Surface outputSurface,
      List<ExportException> codecInitExceptions)
      throws ExportException {
    for (MediaCodecInfo decoderInfo : decoderInfos) {
      String codecMimeType = decoderInfo.codecMimeType;
      // Does not alter format.sampleMimeType to keep the original MimeType.
      // The MIME type of the selected decoder may differ from Format.sampleMimeType, for example,
      // video/hevc is used instead of video/dolby-vision for some specific DolbyVision videos.
      mediaFormat.setString(MediaFormat.KEY_MIME, codecMimeType);
      try {
        return new DefaultCodec(
            context, format, mediaFormat, decoderInfo.name, /* isDecoder= */ true, outputSurface);
      } catch (ExportException e) {
        codecInitExceptions.add(e);
      }
    }

    // All codecs failed to be initialized, throw the first codec init error out.
    throw codecInitExceptions.get(0);
  }

  private static boolean deviceNeedsDisable8kWorkaround(Format format) {
    // Fixed on API 31+. See http://b/278234847#comment40 for more information.
    return SDK_INT < 31
        && format.width >= 7680
        && format.height >= 4320
        && format.sampleMimeType != null
        && format.sampleMimeType.equals(MimeTypes.VIDEO_H265)
        && (Util.MODEL.equals("SM-F711U1") || Util.MODEL.equals("SM-F926U1"));
  }

  private static boolean deviceNeedsDisableToneMappingWorkaround(
      @C.ColorTransfer int colorTransfer) {
    if (Util.MANUFACTURER.equals("Google") && Build.ID.startsWith("TP1A")) {
      // Some Pixel 6 builds report support for tone mapping but the feature doesn't work
      // (see b/249297370#comment8).
      return true;
    }
    if (colorTransfer == C.COLOR_TRANSFER_HLG
        && (Util.MODEL.startsWith("SM-F936")
            || Util.MODEL.startsWith("SM-F916")
            || Util.MODEL.startsWith("SM-F721")
            || Util.MODEL.equals("SM-X900"))) {
      // Some Samsung Galaxy Z Fold devices report support for HLG tone mapping but the feature only
      // works on PQ (see b/282791751#comment7).
      return true;
    }
    if (SDK_INT < 34
        && colorTransfer == C.COLOR_TRANSFER_ST2084
        && Util.MODEL.startsWith("SM-F936")) {
      // The Samsung Fold 4 HDR10 codec plugin for tonemapping sets incorrect crop values, so block
      // using it (see b/290725189).
      return true;
    }
    return false;
  }

  private static boolean deviceNeedsNoFrameRateWorkaround() {
    // Redmi Note 9 Pro fails if KEY_FRAME_RATE is set too high (see b/278076311).
    return SDK_INT < 30 && Util.DEVICE.equals("joyeuse");
  }

  private static boolean decoderSupportsKeyAllowFrameDrop(Context context) {
    return SDK_INT >= 29 && context.getApplicationInfo().targetSdkVersion >= 29;
  }

  private static boolean devicePrefersSoftwareDecoder(Format format) {
    // TODO: b/255953153 - Capture this corner case with refactored fallback API.
    // Some devices fail to configure a 1080p hardware encoder when a 1080p hardware decoder
    // was created. Fall back to using a software decoder (see b/283768701).
    // During a 1080p -> 180p export, using the hardware decoder would be faster than software
    // decoder (68 fps vs 45 fps).
    // When transcoding 1080p to 1080p, software decoder + hardware encoder (33 fps) outperforms
    // hardware decoder + software encoder (17 fps).
    // Due to b/267740292 using hardware to software encoder fallback is risky.
    return format.width * format.height >= 1920 * 1080
        && (Ascii.equalsIgnoreCase(Util.MODEL, "vivo 1906")
            || Ascii.equalsIgnoreCase(Util.MODEL, "redmi 8"));
  }

  private static ExportException createExportException(Format format, String reason) {
    return ExportException.createForCodec(
        new IllegalArgumentException(reason),
        ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        new ExportException.CodecInfo(
            format.toString(),
            MimeTypes.isVideo(checkNotNull(format.sampleMimeType)),
            /* isDecoder= */ true,
            /* name= */ null));
  }
}
