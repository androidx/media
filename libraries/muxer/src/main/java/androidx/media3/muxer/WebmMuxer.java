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
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A muxer for creating a WebM container file.
 *
 * <p>Muxer supports muxing of:
 *
 * <ul>
 *   <li>Video Codecs:
 *       <ul>
 *         <li>VP8
 *         <li>VP9
 *       </ul>
 *   <li>Audio Codecs:
 *       <ul>
 *         <li>Opus
 *         <li>Vorbis
 *       </ul>
 * </ul>
 */
@UnstableApi
public final class WebmMuxer implements Muxer {
  /** A builder for {@link WebmMuxer} instances. */
  public static final class Builder {
    private final SeekableMuxerOutput seekableMuxerOutput;
    private boolean sampleCopyEnabled;

    /**
     * Creates an instance.
     *
     * @param seekableMuxerOutput The {@link SeekableMuxerOutput} to write the media data to. It
     *     will be automatically {@linkplain SeekableMuxerOutput#close() closed} by the muxer when
     *     {@link WebmMuxer#close()} is called.
     */
    public Builder(SeekableMuxerOutput seekableMuxerOutput) {
      this.seekableMuxerOutput = seekableMuxerOutput;
      this.sampleCopyEnabled = true;
    }

    /**
     * Sets whether to enable the sample copy.
     *
     * <p>If the sample copy is enabled, {@link #writeSampleData(int, ByteBuffer, BufferInfo)}
     * copies the input {@link ByteBuffer} and {@link BufferInfo} before it returns, so it is safe
     * to reuse them immediately. Otherwise, the muxer takes ownership of the {@link ByteBuffer} and
     * the {@link BufferInfo} and the caller must not modify them.
     *
     * <p>The default value is {@code true}.
     */
    @CanIgnoreReturnValue
    public WebmMuxer.Builder setSampleCopyEnabled(boolean enabled) {
      this.sampleCopyEnabled = enabled;
      return this;
    }

    /** Builds a {@link WebmMuxer} instance. */
    public WebmMuxer build() {
      return new WebmMuxer(seekableMuxerOutput, sampleCopyEnabled);
    }
  }

  private final SeekableMuxerOutput seekableMuxerOutput;
  private final WebmWriter writer;
  private final List<Track> trackIdToTrack;
  private int nextTrackId;

  private WebmMuxer(SeekableMuxerOutput seekableMuxerOutput, boolean sampleCopyEnabled) {
    this.seekableMuxerOutput = seekableMuxerOutput;
    trackIdToTrack = new ArrayList<>();
    writer = new WebmWriter(seekableMuxerOutput, sampleCopyEnabled);
  }

  @Override
  public int addTrack(Format format) {
    checkArgument(isMimeTypeSupported(format));
    Track track = writer.addTrack(nextTrackId++, format);
    trackIdToTrack.add(track);
    return track.id;
  }

  /** Returns whether the given {@link Format} is supported by the WebM container. */
  private boolean isMimeTypeSupported(Format format) {
    return Objects.equals(format.sampleMimeType, MimeTypes.VIDEO_VP9)
        || Objects.equals(format.sampleMimeType, MimeTypes.VIDEO_VP8)
        || Objects.equals(format.sampleMimeType, MimeTypes.AUDIO_OPUS)
        || Objects.equals(format.sampleMimeType, MimeTypes.AUDIO_VORBIS);
  }

  /**
   * {@inheritDoc}
   *
   * @param trackId The track id for which this sample is being written.
   * @param byteBuffer The encoded sample. The muxer takes ownership of the buffer if {@link
   *     Builder#setSampleCopyEnabled(boolean) sample copying} is disabled. Otherwise, the position
   *     of the buffer is updated but the caller retains ownership.
   * @param bufferInfo The {@link BufferInfo} related to this sample.
   * @throws MuxerException If an error occurs while writing data to the output file.
   */
  @Override
  public void writeSampleData(int trackId, ByteBuffer byteBuffer, BufferInfo bufferInfo)
      throws MuxerException {
    Track track = trackIdToTrack.get(trackId);
    try {
      writer.writeSampleData(track, byteBuffer, bufferInfo);
    } catch (IOException e) {
      throw new MuxerException(
          "Failed to write sample for presentationTimeUs="
              + bufferInfo.presentationTimeUs
              + ", size="
              + bufferInfo.size,
          e);
    }
  }

  @Override
  public void addMetadataEntry(Metadata.Entry metadataEntry) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void close() throws MuxerException {
    try {
      writer.close();
    } catch (IOException e) {
      throw new MuxerException("Failed to close the writer.", e);
    }
    try {
      seekableMuxerOutput.close();
    } catch (IOException e) {
      throw new MuxerException("Failed to close the output.", e);
    }
  }
}
