/*
 * Copyright 2024 The Android Open Source Project
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

import android.os.ParcelFileDescriptor;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.ByteBuffer;

/** A muxer for producing media container files. */
@UnstableApi
public interface Muxer extends AutoCloseable {
  /** Factory for muxers. */
  interface Factory {
    /**
     * Returns a new {@link Muxer}.
     *
     * @param path The path to the output file.
     * @throws MuxerException If an error occurs opening the output file for writing.
     */
    Muxer create(String path) throws MuxerException;

    /**
     * Returns a new {@link Muxer}.
     *
     * <p>The caller transfers ownership of the {@link ParcelFileDescriptor} to the {@link Factory}.
     * If this method returns successfully, the created {@link Muxer} is responsible for closing the
     * descriptor when {@link Muxer#close()} is called. If an error occurs and a {@link
     * MuxerException} is thrown, the descriptor is closed before the exception is thrown.
     *
     * <p>Whether the provided {@link ParcelFileDescriptor} must be seekable depends on the
     * container format and {@link Muxer} implementation. For example, standard MP4 muxers require
     * seekable descriptors (such as those opened with {@code "rwt"} or {@code "rw"}), whereas
     * streaming muxers (such as fragmented MP4 muxers) also accept non-seekable streams like pipes.
     *
     * @param pfd the {@link ParcelFileDescriptor} to write output to
     * @throws MuxerException if an error occurs opening the output for writing or if the descriptor
     *     does not satisfy the seekability requirements of the muxer
     */
    default Muxer create(ParcelFileDescriptor pfd) throws MuxerException {
      MuxerException exception =
          new MuxerException(
              "ParcelFileDescriptor output is not supported", new UnsupportedOperationException());
      if (pfd != null) {
        try {
          pfd.close();
        } catch (IOException e) {
          exception.addSuppressed(e);
        }
      }
      throw exception;
    }

    /**
     * Returns the supported sample {@linkplain MimeTypes MIME types} for the given {@link
     * C.TrackType}.
     */
    ImmutableList<String> getSupportedSampleMimeTypes(@C.TrackType int trackType);

    /**
     * Whether the muxer supports writing negative timestamps into an edit list to instruct players
     * to ignore these samples.
     */
    default boolean supportsWritingNegativeTimestampsInEditList() {
      return false;
    }
  }

  /**
   * Adds a track of the given media format.
   *
   * <p>All tracks must be added before any samples are written to any track.
   *
   * @param format The {@link Format} of the track.
   * @return A track id for this track, which should be passed to {@link #writeSampleData}.
   * @throws MuxerException If the muxer encounters a problem while adding the track.
   */
  int addTrack(Format format) throws MuxerException;

  /**
   * Writes encoded sample data.
   *
   * @param trackId The track id, previously returned by {@link #addTrack(Format)}.
   * @param byteBuffer A buffer containing the sample data to write to the container.
   * @param bufferInfo The {@link BufferInfo} of the sample.
   * @throws MuxerException If the muxer fails to write the sample.
   */
  void writeSampleData(int trackId, ByteBuffer byteBuffer, BufferInfo bufferInfo)
      throws MuxerException;

  /** Adds {@linkplain Metadata.Entry metadata} about the output file. */
  void addMetadataEntry(Metadata.Entry metadataEntry);

  /**
   * Closes the file.
   *
   * <p>The muxer cannot be used anymore once this method returns.
   *
   * @throws MuxerException If the muxer fails to finish writing the output.
   */
  @Override
  void close() throws MuxerException;
}
