package androidx.media3.decoder.mpegh;

import android.os.Handler;
import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.CryptoConfig;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DecoderAudioRenderer;

/**
 * Decodes and renders audio using the native MPEG-H decoder.
 */
@UnstableApi
public final class MpeghAudioRenderer extends DecoderAudioRenderer<MpeghDecoder> {

  private static final String TAG = "MpeghAudioRenderer";

  /** The number of input and output buffers. */
  private static final int NUM_BUFFERS = 16;

  public MpeghAudioRenderer() {
    this(null, null);
  }

  /**
   * @param eventHandler A handler to use when delivering events to {@code eventListener}. May be
   *     null if delivery of events is not required.
   * @param eventListener A listener of events. May be null if delivery of events is not required.
   * @param audioProcessors Optional {@link AudioProcessor}s that will process audio before output.
   */
  public MpeghAudioRenderer(
      Handler eventHandler, AudioRendererEventListener eventListener,
      AudioProcessor... audioProcessors) {
    super(eventHandler, eventListener, audioProcessors);
  }

  /**
   * @param eventHandler A handler to use when delivering events to {@code eventListener}. May be
   *     null if delivery of events is not required.
   * @param eventListener A listener of events. May be null if delivery of events is not required.
   * @param audioSink The sink to which audio will be output.
   */
  public MpeghAudioRenderer(
      Handler eventHandler, AudioRendererEventListener eventListener, AudioSink audioSink) {
    super(eventHandler, eventListener, audioSink);
  }

  @Override
  public String getName() {
    return TAG;
  }

  @Override
  protected int supportsFormatInternal(Format format) {
    // check if JNI library is available
    if (!MpeghLibrary.isAvailable()) {
      return C.FORMAT_UNSUPPORTED_TYPE;
    }

    // check if MIME type is supported
    if (!(MimeTypes.AUDIO_MPEGH_MHM1.equalsIgnoreCase(format.sampleMimeType)
        || MimeTypes.AUDIO_MPEGH_MHA1.equalsIgnoreCase(format.sampleMimeType))) {
      return C.FORMAT_UNSUPPORTED_TYPE;
    }
    return C.FORMAT_HANDLED;
  }

  @Override
  protected DecoderReuseEvaluation canReuseDecoder(
      @NonNull String decoderName, Format oldFormat, Format newFormat) {

    if (oldFormat.sampleMimeType.equals(newFormat.sampleMimeType)
        && oldFormat.sampleMimeType.equals(MimeTypes.AUDIO_MPEGH_MHM1)) {
      return new DecoderReuseEvaluation(
          decoderName, oldFormat, newFormat,
          DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION, 0);
    }
    return super.canReuseDecoder(decoderName, oldFormat, newFormat);
  }

  @Override
  protected MpeghDecoder createDecoder(@NonNull Format format, CryptoConfig cryptoConfig)
      throws MpeghException {

    // initialize the decoder
    return new MpeghDecoder(NUM_BUFFERS, NUM_BUFFERS, format);
  }

  @Override
  protected Format getOutputFormat(MpeghDecoder decoder) {
    Format.Builder formatBuilder = new Format.Builder();
    formatBuilder.setChannelCount(decoder.getChannelCount()).setSampleRate(decoder.getSampleRate());
    formatBuilder.setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT);
    return formatBuilder.build();
  }
}
