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
import androidx.media3.common.MediaLibraryInfo;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

/**
 * A muxer for creating an Ogg container file.
 *
 * <p>This muxer supports muxing of {@link MimeTypes#AUDIO_OPUS Opus} audio codec.
 */
@UnstableApi
public final class OggMuxer implements Muxer {

  /** Builder for {@link OggMuxer} instances. */
  public static final class Builder {
    private final WritableByteChannel outputChannel;
    private String vendorString = MediaLibraryInfo.VERSION_SLASHY;

    /**
     * Creates a {@link Builder}.
     *
     * @param outputChannel The {@link WritableByteChannel} where the output will be written. The
     *     {@code outputChannel} will be closed when {@link #close()} is called on the muxer.
     */
    public Builder(WritableByteChannel outputChannel) {
      this.outputChannel = outputChannel;
    }

    /**
     * Sets the vendor string written to the Opus comment header. The default value is {@link
     * MediaLibraryInfo#VERSION_SLASHY}.
     *
     * @param vendorString The vendor string to use.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setVendorString(String vendorString) {
      this.vendorString = vendorString;
      return this;
    }

    /** Builds an {@link OggMuxer}. */
    public OggMuxer build() {
      return new OggMuxer(outputChannel, vendorString);
    }
  }

  private final OggWriter writer;

  private boolean isTrackAdded;

  private OggMuxer(WritableByteChannel outputChannel, String vendorString) {
    writer = new OggWriter(outputChannel, vendorString);
  }

  @Override
  public int addTrack(Format format) throws MuxerException {
    checkArgument(Objects.equals(format.sampleMimeType, MimeTypes.AUDIO_OPUS));
    // Only one track is supported.
    checkState(!isTrackAdded);
    try {
      writer.setFormat(format);
    } catch (IOException e) {
      throw new MuxerException("Failed to add track to the OggMuxer", e);
    }

    isTrackAdded = true;
    return 0; // There is only one track supported.
  }

  @Override
  public void writeSampleData(int trackId, ByteBuffer byteBuffer, BufferInfo bufferInfo)
      throws MuxerException {
    try {
      checkState(isTrackAdded);
      // Only one track is supported.
      checkArgument(trackId == 0);
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
   * <p>This operation is not supported for Ogg muxing and will always throw an {@link
   * UnsupportedOperationException}.
   */
  @Override
  public void addMetadataEntry(Metadata.Entry metadataEntry) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void close() throws MuxerException {
    try {
      writer.close();
    } catch (IOException e) {
      throw new MuxerException("Failed to close the OggMuxer", e);
    }
  }
}
