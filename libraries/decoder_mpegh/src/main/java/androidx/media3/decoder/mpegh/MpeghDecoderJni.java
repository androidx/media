package androidx.media3.decoder.mpegh;

import java.nio.ByteBuffer;

public class MpeghDecoderJni {

  private long decoderHandle; // used by JNI only to hold the native context

  public MpeghDecoderJni() {}

  /**
   * Initializes the native MPEG-H decoder.
   * @param cicpindex the desired target layout CICP index
   * @param mhaconfig byte array holding the audio specific configuration for MHA content
   * @param mhaconfiglength length of audio specific configuration
   * @throws MpeghException in case the initialization fails
   */
  public native void init(int cicpindex, byte[] mhaconfig, int mhaconfiglength)
      throws MpeghException;

  /**
   *  Destroys the native MPEG-H decoder.
   */
  public native void destroy();

  /**
   * Processes data (access units) and corresponding PTS inside of the native MPEG-H decoder.
   * @param in direct bytebuffer holding the access unit
   * @param in_len length of the direct bytebuffer
   * @param timestamp presentation timestamp of the access unit
   * @throws MpeghException in case processing fails
   */
  public native void process(ByteBuffer in, int in_len, long timestamp) throws MpeghException;

  /**
   * Obtains decoded samples from the native MPEG-H decoder and writes them into buffer at position
   * writePos.
   * NOTE: The decoder returns the samples as 16bit values.
   * @param buffer direct bytebuffer to write the decoded samples to
   * @param writePos start position in the bytebuffer to write the decoded samples to
   * @return number of bytes written to buffer
   * @throws MpeghException in case getting samples fails
   */
  public native int getSamples(ByteBuffer buffer, int writePos) throws MpeghException;

  /**
   * Flushes the native MPEG-H decoder and writes available output samples into a sample queue.
   * @throws MpeghException if flushing fails
   */
  public native void flushAndGet() throws MpeghException;

  /**
   * Gets the number of output channels from the native MPEG-H decoder.
   * NOTE: This information belongs to the last audio frame obtained from getSamples() or
   * flushAndGetSamples().
   * @return number of output channels
   */
  public native int getNumChannels();

  /**
   * Gets the output sample rate from the native MPEG-H decoder.
   * NOTE: This information belongs to the last audio frame obtained from getSamples() or
   * flushAndGetSamples().
   * @return output sample rate
   */
  public native int getSamplerate();

  /**
   * Gets the PTS from the native MPEG-H decoder.
   * NOTE: This information belongs to the last audio frame obtained from getSamples() or
   * flushAndGetSamples().
   * @return output presentation time stamp
   */
  public native long getPts();

  /**
   * Flushes the native MPEG-H decoder.
   * @throws MpeghException in case flushing fails
   */
  public native void flush() throws MpeghException;
}
