/*
 * Copyright 2024 The Android Open Source Project
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
package androidx.media3.decoder.iamf;

import android.content.Context;
import android.media.AudioFormat;
import android.os.Handler;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.TraceUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.CryptoConfig;
import androidx.media3.decoder.DecoderException;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DecoderAudioRenderer;
import androidx.media3.exoplayer.audio.IamfUtil;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Objects;

/** Decodes and renders audio using the native IAMF decoder. */
@UnstableApi
public class IamfAudioRenderer extends DecoderAudioRenderer<IamfDecoder> {

  /** Builds {@link IamfAudioRenderer} instances. */
  public static final class Builder {
    private final Context context;
    private final AudioSink audioSink;
    @Nullable private Handler eventHandler;
    @Nullable private AudioRendererEventListener eventListener;
    private @IamfUtil.OutputLayout int requestedOutputLayout;
    private @IamfDecoder.OutputSampleType int outputSampleType;
    private @IamfDecoder.ChannelOrdering int channelOrdering;
    private boolean enableIntegratedBinaural;

    /**
     * Creates a builder.
     *
     * @param context The {@link Context}.
     * @param audioSink The sink to which audio will be output.
     */
    public Builder(Context context, AudioSink audioSink) {
      this.context = context;
      this.audioSink = audioSink;
      this.requestedOutputLayout = IamfUtil.OUTPUT_LAYOUT_UNSET;
      this.outputSampleType = IamfDecoder.OUTPUT_SAMPLE_TYPE_INT16_LITTLE_ENDIAN;
      this.channelOrdering = IamfDecoder.CHANNEL_ORDERING_ANDROID_ORDERING;
      this.enableIntegratedBinaural = true;
    }

    /** Sets an event handler and listener to use when delivering events. */
    @CanIgnoreReturnValue
    public Builder setEventHandlerAndListener(
        Handler eventHandler, AudioRendererEventListener eventListener) {
      this.eventListener = eventListener;
      this.eventHandler = eventHandler;
      return this;
    }

    /**
     * Sets the desired {@link IamfDecoder.OutputSampleType}. The default value is {@link
     * IamfDecoder#OUTPUT_SAMPLE_TYPE_INT16_LITTLE_ENDIAN}.
     */
    @CanIgnoreReturnValue
    public Builder setOutputSampleType(@IamfDecoder.OutputSampleType int outputSampleType) {
      this.outputSampleType = outputSampleType;
      return this;
    }

    /**
     * Sets the desired {@link IamfDecoder.ChannelOrdering}. The default value is {@link
     * IamfDecoder#CHANNEL_ORDERING_ANDROID_ORDERING}.
     */
    @CanIgnoreReturnValue
    public Builder setChannelOrdering(@IamfDecoder.ChannelOrdering int channelOrdering) {
      this.channelOrdering = channelOrdering;
      return this;
    }

    /**
     * Sets a requested {@link IamfUtil.OutputLayout} to target for the output audio.
     *
     * <p>If not set, the renderer will use its own logic to decide what layout to request. The
     * decoder might not be able to produce the layout requested, so the actual layout used may be
     * different. To get the actual layout used by the decoder, use {@link
     * IamfDecoder#getSelectedOutputLayout()}.
     */
    @CanIgnoreReturnValue
    public Builder setRequestedOutputLayout(@IamfUtil.OutputLayout int requestedOutputLayout) {
      this.requestedOutputLayout = requestedOutputLayout;
      return this;
    }

    /**
     * Enables or disables binaural rendering within the IAMF decoder. Default {@code true}.
     *
     * <p>This setting controls the behaviour when we believe the user is using headphones. If
     * {@code true}, the IAMF decoder can output pre-rendered binaural audio. If this is {@code
     * false}, the {@link IamfAudioRenderer} will instead produce output appropriate for the Android
     * {@link android.media.Spatializer}.
     *
     * <p>The IAMF binaural renderer may be higher fidelity because the audio content of the IAMF
     * stream can be rendered directly to binaural audio, without first producing an intermediate
     * format to pass to the {@link android.media.Spatializer}, which may require downmixing and
     * loss of height information.
     *
     * <p>If a specific decoder layout is requested using {@link #setRequestedOutputLayout}, then
     * this setting is irrelevant.
     */
    @CanIgnoreReturnValue
    public Builder setEnableIntegratedBinaural(boolean enableIntegratedBinaural) {
      this.enableIntegratedBinaural = enableIntegratedBinaural;
      return this;
    }

    /** Builds an {@link IamfAudioRenderer}. */
    public IamfAudioRenderer build() {
      return new IamfAudioRenderer(this);
    }
  }

  private static final String TAG = "IamfAudioRenderer";
  private final Context context;
  private final @IamfDecoder.OutputSampleType int outputSampleType;
  private final @IamfDecoder.ChannelOrdering int channelOrdering;
  private final @IamfUtil.OutputLayout int requestedOutputLayout;
  private final @IamfUtil.OutputLayout int currentOutputLayout;

  private IamfAudioRenderer(Builder builder) {
    super(builder.eventHandler, builder.eventListener, builder.audioSink);
    this.context = builder.context;
    this.outputSampleType = builder.outputSampleType;
    this.channelOrdering = builder.channelOrdering;
    this.requestedOutputLayout = builder.requestedOutputLayout;
    this.currentOutputLayout =
        determineOutputLayout(context, requestedOutputLayout, builder.enableIntegratedBinaural);
  }

  @Override
  protected int supportsFormatInternal(Format format) {
    return !IamfLibrary.isAvailable()
            || !Objects.equals(format.sampleMimeType, MimeTypes.AUDIO_IAMF)
        ? C.FORMAT_UNSUPPORTED_TYPE
        : C.FORMAT_HANDLED;
  }

  @Override
  protected IamfDecoder createDecoder(Format format, @Nullable CryptoConfig cryptoConfig)
      throws DecoderException {
    TraceUtil.beginSection("createIamfDecoder");
    IamfDecoder decoder =
        new IamfDecoder(
            format.initializationData,
            currentOutputLayout,
            IamfUtil.REQUESTED_MIX_PRESENTATION_ID_UNSET,
            outputSampleType,
            channelOrdering);
    TraceUtil.endSection();
    return decoder;
  }

  @Override
  protected Format getOutputFormat(IamfDecoder decoder) {
    try {
      @IamfDecoder.OutputSampleType int sampleType = decoder.getOutputSampleType();
      int channelCount = decoder.getNumberOfOutputChannels();
      int sampleRate = decoder.getSampleRate();
      int encoding = getEncodingForSampleType(sampleType);
      return Util.getPcmFormat(encoding, channelCount, sampleRate);
    } catch (IamfDecoderException e) {
      Log.e(TAG, "Failed to get output sample type from decoder", e);
      return new Format.Builder().build();
    }
  }

  @Override
  public String getName() {
    return "IamfAudioRenderer";
  }

  private static int getEncodingForSampleType(@IamfDecoder.OutputSampleType int sampleType) {
    switch (sampleType) {
      case IamfDecoder.OUTPUT_SAMPLE_TYPE_INT16_LITTLE_ENDIAN:
        return AudioFormat.ENCODING_PCM_16BIT;
      case IamfDecoder.OUTPUT_SAMPLE_TYPE_INT32_LITTLE_ENDIAN:
        return AudioFormat.ENCODING_PCM_FLOAT;
      default:
        throw new IllegalArgumentException("Unsupported sample type: " + sampleType);
    }
  }

  private static @IamfUtil.OutputLayout int determineOutputLayout(
      Context context,
      @IamfUtil.OutputLayout int requestedOutputLayout,
      boolean enableIntegratedBinaural) {
    // If the user has requested a specific output layout, use that.
    if (requestedOutputLayout != IamfUtil.OUTPUT_LAYOUT_UNSET) {
      return requestedOutputLayout;
    }
    return IamfUtil.getOutputLayoutForCurrentConfiguration(context, enableIntegratedBinaural);
  }
}
