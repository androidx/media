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
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** An implementation of {@link SeekableMuxerOutput} that writes to a {@link FileOutputStream}. */
@UnstableApi
public final class FileOutputStreamSeekableMuxerOutput implements SeekableMuxerOutput {
  private final FileOutputStream fileOutputStream;
  private final FileChannel fileChannel;

  /**
   * Creates an instance.
   *
   * @param fileOutputStream The {@link FileOutputStream} to write output to.
   */
  public FileOutputStreamSeekableMuxerOutput(FileOutputStream fileOutputStream) {
    this.fileOutputStream = fileOutputStream;
    fileChannel = fileOutputStream.getChannel();
  }

  @Override
  public long getPosition() throws IOException {
    return fileChannel.position();
  }

  @Override
  public void setPosition(long position) throws IOException {
    fileChannel.position(position);
  }

  @Override
  public long getSize() throws IOException {
    return fileChannel.size();
  }

  @Override
  public void truncate(long size) throws IOException {
    fileChannel.truncate(size);
  }

  @Override
  public int write(ByteBuffer src) throws IOException {
    return fileChannel.write(src);
  }

  @Override
  public boolean isOpen() {
    return fileChannel.isOpen();
  }

  @Override
  public void close() throws IOException {
    fileOutputStream.close();
  }
}
