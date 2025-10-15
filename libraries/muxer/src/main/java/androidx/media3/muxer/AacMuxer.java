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

import static com.google.common.base.Preconditions.checkArgument;

import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * A muxer for creating an AAC (Advanced Audio Coding) container file.
 *
 * <p>This muxer supports muxing of {@link MimeTypes#AUDIO_AAC AAC} audio codec.
 */
@UnstableApi
public final class AacMuxer implements Muxer {

  private final FileOutputStream outputStream;
  private final AacWriter writer;

  private boolean isTrackAdded;

  /**
   * Creates an {@link AacMuxer} instance.
   *
   * @param outputStream The {@link FileOutputStream} where the output will be written.
   */
  public AacMuxer(FileOutputStream outputStream) {
    this.outputStream = outputStream;
    writer = new AacWriter(outputStream);
  }

  /**
   * Add an {@linkplain MimeTypes#AUDIO_AAC AAC audio} track.
   *
   * @param format The {@link Format} of the track.
   * @return A track id for this track, which should be passed to {@link #writeSampleData}.
   */
  @Override
  public int addTrack(Format format) {
    checkArgument(Objects.equals(format.sampleMimeType, MimeTypes.AUDIO_AAC));
    checkArgument(!isTrackAdded, "Only one track is supported.");
    writer.setFormat(format);
    isTrackAdded = true;
    return 0; // trackId is always 0 for AAC.
  }

  @Override
  public void writeSampleData(int trackId, ByteBuffer byteBuffer, BufferInfo bufferInfo)
      throws MuxerException {
    try {
      checkArgument(isTrackAdded, "Track must be added before writing samples.");
      checkArgument(trackId == 0, "This track has not been added to the muxer.");
      writer.writeSampleData(byteBuffer, bufferInfo);
    } catch (IOException e) {
      throw new MuxerException(
          "Failed to write sample for presentationTimeUs="
              + bufferInfo.presentationTimeUs
              + ", size="
              + bufferInfo.size,
          e);
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>This operation is not supported for AAC muxing and will always throw an {@link
   * UnsupportedOperationException}.
   */
  @Override
  public void addMetadataEntry(Metadata.Entry metadataEntry) {
    throw new UnsupportedOperationException("Writing metadata is not supported for AacMuxer.");
  }

  @Override
  public void close() throws MuxerException {
    try {
      outputStream.close();
    } catch (IOException e) {
      throw new MuxerException("Failed to close the muxer", e);
    }
  }
}
