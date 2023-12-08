package androidx.media3.common;

import java.util.ArrayList;

/**
 * A codec parameter change listener provides the ability to be notified about state/parameter
 * changes in a codec instance.
 */
public interface CodecParametersChangeListener {

  /**
   * Inform about changes to the output buffer format.
   *
   * @param codecParameters A set of codec parameters.
   */
  void onCodecParametersChanged(CodecParameters codecParameters);

  /**
   * Get a list of key values which should be returned by {@link #onCodecParametersChanged(CodecParameters)}
   *
   * @returns An ArrayList of the requested keys.
   */
  ArrayList<String> getFilterKeys();
}
