package androidx.media3.decoder.mpegh;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.DecoderException;

/**
 * Thrown when an MPEG-H decoder error occurs.
 */
@UnstableApi
public class MpeghException extends DecoderException {

  public MpeghException(String message) {
    super(message, new Throwable());
  }

  public MpeghException(String message, Throwable cause) {
    super(message, cause);
  }
}
