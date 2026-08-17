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
package androidx.media3.muxer;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A muxer for creating a WAV container file.
 *
 * <p>This muxer supports muxing of {@link MimeTypes#AUDIO_RAW} with PCM encodings.
 */
@UnstableApi
public final class WavMuxer implements Muxer {
  private final WavWriter wavWriter;

  private boolean trackAdded;

  /**
   * Creates an instance.
   *
   * @param seekableMuxerOutput The {@link SeekableMuxerOutput} to write the media data to. It will
   *     be automatically {@linkplain SeekableMuxerOutput#close() closed} by the muxer when {@link
   *     WavMuxer#close()} is called.
   */
  public WavMuxer(SeekableMuxerOutput seekableMuxerOutput) {
    wavWriter = new WavWriter(seekableMuxerOutput);
  }

  /**
   * {@inheritDoc}
   *
   * <p>Only a single audio track is supported.
   */
  @Override
  public int addTrack(Format format) {
    checkState(!trackAdded);
    wavWriter.setFormat(format);
    trackAdded = true;
    return 0; // trackId is always 0 for WAV.
  }

  /**
   * {@inheritDoc}
   *
   * <p>The {@code bufferInfo} is ignored.
   */
  @Override
  public void writeSampleData(int trackId, ByteBuffer byteBuffer, BufferInfo bufferInfo)
      throws MuxerException {
    try {
      checkArgument(trackId == 0);
      checkState(trackAdded);
      wavWriter.writeSampleData(byteBuffer);
    } catch (IOException e) {
      throw new MuxerException("Failed to write sample", e);
    }
  }

  @Override
  public void addMetadataEntry(Metadata.Entry metadataEntry) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void close() throws MuxerException {
    try {
      wavWriter.close();
    } catch (IOException e) {
      throw new MuxerException("Failed to close the output.", e);
    }
  }
}
