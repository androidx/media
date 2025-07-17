/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.muxer;

import androidx.media3.common.util.UnstableApi;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;

/** A seekable target for {@link Muxer} output. */
@UnstableApi
public interface SeekableMuxerOutput extends WritableByteChannel {
  /** Creates a {@link SeekableMuxerOutput} from the given {@link FileOutputStream}. */
  static SeekableMuxerOutput of(FileOutputStream fileOutputStream) {
    return new FileOutputStreamSeekableMuxerOutput(fileOutputStream);
  }

  /** Creates a {@link SeekableMuxerOutput} from the given file path. */
  static SeekableMuxerOutput of(String filePath) throws FileNotFoundException {
    return new FileOutputStreamSeekableMuxerOutput(new FileOutputStream(filePath));
  }

  /**
   * Returns the current position in the output.
   *
   * <p>This refers to the byte offset at which bytes will be written on the next {@link
   * #write(ByteBuffer)} call.
   *
   * @return The current position in bytes from the beginning of the output.
   * @throws IOException If an error occurs while getting the position.
   */
  long getPosition() throws IOException;

  /**
   * Sets the current position in the output for subsequent {@link #write(ByteBuffer)} operations.
   *
   * @param position The new position in bytes from the beginning of the output.
   * @throws IOException If an error occurs while setting the position.
   */
  void setPosition(long position) throws IOException;

  /**
   * Returns the total size of the output.
   *
   * @return The total size of the output in bytes.
   * @throws IOException If an error occurs while getting the size.
   */
  long getSize() throws IOException;

  /**
   * Truncates the output to the specified size.
   *
   * <p>If the current position (for writing) is beyond the new size, it is set to the new size.
   *
   * @param size The new size of the output in bytes.
   * @throws IOException If an error occurs while truncating the output.
   */
  void truncate(long size) throws IOException;
}
