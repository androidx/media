/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.decoder.mpegh;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import java.nio.ByteBuffer;

/** JNI wrapper for the libmpegh MPEG-H UI manager. */
@UnstableApi
public final class MpeghUiManagerJni {

  private long uiManagerHandle; // Used by JNI only to hold the native context.

  public MpeghUiManagerJni() {}

  /**
   * Initializes the native MPEG-H UI manager.
   *
   * @param persistenceBuffer The {@link ByteBuffer} holding the persistence cache.
   * @param persistenceBufferLength The length of the {@code persistenceBuffer}.
   * @throws MpeghDecoderException If initialization fails.
   */
  public native void init(@Nullable ByteBuffer persistenceBuffer, int persistenceBufferLength)
      throws MpeghDecoderException;

  /**
   * Destroys the native MPEG-H UI manager.
   *
   * @param persistenceBuffer The {@link ByteBuffer} to write the persistence cache to.
   * @param persistenceBufferLength The capacity of the {@code persistenceBuffer}.
   * @return The number of bytes written to {@code persistenceBuffer}.
   */
  public native int destroy(@Nullable ByteBuffer persistenceBuffer, int persistenceBufferLength);

  /**
   * Sends an XML action command to the MPEG-H UI Manager.
   *
   * @param xmlAction The XML action command string.
   * @return Whether the command could be applied.
   */
  public native boolean command(String xmlAction);

  /**
   * Feeds data (access units) to the MPEG-H UI Manager.
   *
   * @param inData The {@link ByteBuffer} holding the access units.
   * @param inDataLength The length of the data in {@code inData}.
   * @return Whether feeding the data was successful.
   */
  public native boolean feed(ByteBuffer inData, int inDataLength);

  /**
   * Updates data (access units) by the MPEG-H UI Manager.
   *
   * @param inData The {@link ByteBuffer} holding the access units.
   * @param inDataLength The length of the data in {@code inData}.
   * @param forceUiUpdate Whether a forced UI update should be triggered.
   * @return The updated length of the access unit.
   */
  public native int update(ByteBuffer inData, int inDataLength, boolean forceUiUpdate);

  /**
   * Checks if a new OSD XML configuration is available.
   *
   * @return Whether a new OSD XML configuration is available.
   */
  public native boolean newOsdAvailable();

  /**
   * Gets the latest OSD XML configuration from the MPEG-H UI manager.
   *
   * @return The latest OSD XML configuration string.
   */
  public native String getOsd();
}
