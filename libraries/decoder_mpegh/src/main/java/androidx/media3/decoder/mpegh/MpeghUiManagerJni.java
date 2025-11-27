package androidx.media3.decoder.mpegh;

import androidx.annotation.Nullable;
import java.nio.ByteBuffer;

/** JNI wrapper for the libmpegh MPEG-H UI manager. */
public class MpeghUiManagerJni {

  private long uiManagerHandle; // used by JNI only to hold the native context

  public MpeghUiManagerJni() {}

  /**
   * Initializes the native MPEG-H UI manager.
   * @param persistenceBuffer bytebuffer holding the persistence cache
   * @param persistenceBufferLength length of the bytebuffer
   * @throws MpeghDecoderException in case initialization fails
   */
  public native void init(@Nullable ByteBuffer persistenceBuffer, int persistenceBufferLength)
      throws MpeghDecoderException;

  /**
   * Destroys the native MPEG-H UI manager.
   * @param persistenceBuffer bytebuffer to write the persistence cache to
   * @param persistenceBufferLength capacity of the bytebuffer
   * @return number of bytes written to persistenceBuffer
   */
  public native int destroy(@Nullable ByteBuffer persistenceBuffer, int persistenceBufferLength);

  /**
   * Sends an XML action command to the MPEG-H UI Manager.
   * @param xmlAction XML action command string
   * @return boolean value to signal if the command could be applied
   */
  public native boolean command(String xmlAction);

  /**
   * Processes data (access units) inside of the MPEG-H UI Manager.
   * @param inData bytebuffer holding the access units
   * @param inDataLength length of the bytebuffer
   * @param forceUiUpdate flag signalizing if a forced UI update should be triggered
   * @return updated length of the access unit
   */
  public native int process(ByteBuffer inData, int inDataLength, boolean forceUiUpdate);

  /**
   * Checks if a new OSD XML configuration is available.
   * @return boolean value to signal if a new OSD XML configuration is available
   */
  public native boolean newOsdAvailable();

  /**
   * Gets the latest OSD XML configuration from the MPEG-H UI manager.
   * @return latest OSD XML configuration string
   */
  public native String getOsd();
}
