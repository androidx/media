/*
 * Copyright 2023 The Android Open Source Project
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
package androidx.media3.transformer;

import static androidx.media3.muxer.Mp4Muxer.SUPPORTED_AUDIO_SAMPLE_MIME_TYPES;
import static androidx.media3.muxer.Mp4Muxer.SUPPORTED_VIDEO_SAMPLE_MIME_TYPES;

import android.media.MediaCodec.BufferInfo;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.container.MdtaMetadataEntry;
import androidx.media3.container.Mp4LocationData;
import androidx.media3.container.Mp4TimestampData;
import androidx.media3.container.Mp4Util;
import androidx.media3.container.XmpData;
import androidx.media3.muxer.Mp4Muxer;
import androidx.media3.muxer.Mp4Muxer.TrackToken;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.Nullable;

/** {@link Muxer} implementation that uses a {@link Mp4Muxer}. */
@UnstableApi
public final class InAppMuxer implements Muxer {

  /** Provides {@linkplain Metadata.Entry metadata} to add in the output MP4 file. */
  public interface MetadataProvider {

    /**
     * Updates the list of {@linkplain Metadata.Entry metadata entries}.
     *
     * <p>A {@link Metadata.Entry} can be added or removed. To modify an existing {@link
     * Metadata.Entry}, first remove it and then add a new one.
     *
     * <p>List of supported {@linkplain Metadata.Entry metadata entries}:
     *
     * <ul>
     *   <li>{@link Mp4LocationData}
     *   <li>{@link XmpData}
     *   <li>{@link Mp4TimestampData}
     *   <li>{@link MdtaMetadataEntry}
     * </ul>
     */
    void updateMetadataEntries(Set<Metadata.Entry> metadataEntries);
  }

  /** {@link Muxer.Factory} for {@link InAppMuxer}. */
  public static final class Factory implements Muxer.Factory {

    /** A builder for {@link Factory} instances. */
    public static final class Builder {
      private long maxDelayBetweenSamplesMs;
      private @Nullable MetadataProvider metadataProvider;
      private boolean fragmentedMp4Enabled;
      private int fragmentDurationUs;

      /** Creates a {@link Builder} instance with default values. */
      public Builder() {
        maxDelayBetweenSamplesMs = DefaultMuxer.Factory.DEFAULT_MAX_DELAY_BETWEEN_SAMPLES_MS;
        fragmentDurationUs = Mp4Muxer.DEFAULT_FRAGMENT_DURATION_US;
      }

      /** See {@link Muxer#getMaxDelayBetweenSamplesMs()}. */
      @CanIgnoreReturnValue
      public Builder setMaxDelayBetweenSamplesMs(long maxDelayBetweenSamplesMs) {
        this.maxDelayBetweenSamplesMs = maxDelayBetweenSamplesMs;
        return this;
      }

      /**
       * Sets an implementation of {@link MetadataProvider}.
       *
       * <p>The default value is {@code null}.
       *
       * <p>If the value is not set then the {@linkplain Metadata.Entry metadata} from the input
       * file is set as it is in the output file.
       */
      @CanIgnoreReturnValue
      public Builder setMetadataProvider(MetadataProvider metadataProvider) {
        this.metadataProvider = metadataProvider;
        return this;
      }

      /** See {@link Mp4Muxer.Builder#setFragmentedMp4Enabled(boolean)}. */
      @CanIgnoreReturnValue
      public Builder setFragmentedMp4Enabled(boolean fragmentedMp4Enabled) {
        this.fragmentedMp4Enabled = fragmentedMp4Enabled;
        return this;
      }

      /** See {@link Mp4Muxer.Builder#setFragmentDurationUs(int)}. */
      @CanIgnoreReturnValue
      public Builder setFragmentDurationUs(int fragmentDurationUs) {
        this.fragmentDurationUs = fragmentDurationUs;
        return this;
      }

      /** Builds a {@link Factory} instance. */
      public Factory build() {
        return new Factory(
            maxDelayBetweenSamplesMs, metadataProvider, fragmentedMp4Enabled, fragmentDurationUs);
      }
    }

    private final long maxDelayBetweenSamplesMs;
    private final @Nullable MetadataProvider metadataProvider;
    private final boolean fragmentedMp4Enabled;
    private final int fragmentDurationUs;

    private Factory(
        long maxDelayBetweenSamplesMs,
        @Nullable MetadataProvider metadataProvider,
        boolean fragmentedMp4Enabled,
        int fragmentDurationUs) {
      this.maxDelayBetweenSamplesMs = maxDelayBetweenSamplesMs;
      this.metadataProvider = metadataProvider;
      this.fragmentedMp4Enabled = fragmentedMp4Enabled;
      this.fragmentDurationUs = fragmentDurationUs;
    }

    @Override
    public InAppMuxer create(String path) throws MuxerException {
      FileOutputStream outputStream;
      try {
        outputStream = new FileOutputStream(path);
      } catch (FileNotFoundException e) {
        throw new MuxerException("Error creating file output stream", e);
      }

      Mp4Muxer mp4Muxer =
          new Mp4Muxer.Builder(outputStream)
              .setFragmentedMp4Enabled(fragmentedMp4Enabled)
              .setFragmentDurationUs(fragmentDurationUs)
              .build();
      return new InAppMuxer(mp4Muxer, maxDelayBetweenSamplesMs, metadataProvider);
    }

    @Override
    public ImmutableList<String> getSupportedSampleMimeTypes(@C.TrackType int trackType) {
      if (trackType == C.TRACK_TYPE_VIDEO) {
        return SUPPORTED_VIDEO_SAMPLE_MIME_TYPES;
      } else if (trackType == C.TRACK_TYPE_AUDIO) {
        return SUPPORTED_AUDIO_SAMPLE_MIME_TYPES;
      }
      return ImmutableList.of();
    }
  }

  private final Mp4Muxer mp4Muxer;
  private final long maxDelayBetweenSamplesMs;
  private final @Nullable MetadataProvider metadataProvider;
  private final List<TrackToken> trackTokenList;
  private final BufferInfo bufferInfo;
  private final Set<Metadata.Entry> metadataEntries;

  private InAppMuxer(
      Mp4Muxer mp4Muxer,
      long maxDelayBetweenSamplesMs,
      @Nullable MetadataProvider metadataProvider) {
    this.mp4Muxer = mp4Muxer;
    this.maxDelayBetweenSamplesMs = maxDelayBetweenSamplesMs;
    this.metadataProvider = metadataProvider;
    trackTokenList = new ArrayList<>();
    bufferInfo = new BufferInfo();
    metadataEntries = new LinkedHashSet<>();
  }

  @Override
  public int addTrack(Format format) {
    // Keep same sort key as no specific sort order is required.
    TrackToken trackToken = mp4Muxer.addTrack(/* sortKey= */ 0, format);
    trackTokenList.add(trackToken);

    if (MimeTypes.isVideo(format.sampleMimeType)) {
      mp4Muxer.setOrientation(format.rotationDegrees);
    }

    return trackTokenList.size() - 1;
  }

  @Override
  public void writeSampleData(
      int trackIndex, ByteBuffer data, long presentationTimeUs, @C.BufferFlags int flags)
      throws MuxerException {

    int size = data.remaining();
    bufferInfo.set(
        data.position(), size, presentationTimeUs, TransformerUtil.getMediaCodecFlags(flags));

    try {
      // Copy sample data and release the original buffer.
      ByteBuffer byteBufferCopy = ByteBuffer.allocateDirect(data.remaining());
      byteBufferCopy.put(data);
      byteBufferCopy.rewind();

      BufferInfo bufferInfoCopy = new BufferInfo();
      bufferInfoCopy.set(
          /* newOffset= */ byteBufferCopy.position(),
          /* newSize= */ byteBufferCopy.remaining(),
          bufferInfo.presentationTimeUs,
          bufferInfo.flags);

      mp4Muxer.writeSampleData(trackTokenList.get(trackIndex), byteBufferCopy, bufferInfoCopy);
    } catch (IOException e) {
      throw new MuxerException(
          "Failed to write sample for trackIndex="
              + trackIndex
              + ", presentationTimeUs="
              + presentationTimeUs
              + ", size="
              + size,
          e);
    }
  }

  @Override
  public void addMetadata(Metadata metadata) {
    for (int i = 0; i < metadata.length(); i++) {
      Metadata.Entry entry = metadata.get(i);
      // Keep only supported metadata.
      // LINT.IfChange(added_metadata)
      if (entry instanceof Mp4LocationData
          || entry instanceof XmpData
          || entry instanceof Mp4TimestampData
          || (entry instanceof MdtaMetadataEntry
              && (((MdtaMetadataEntry) entry).key.equals(MdtaMetadataEntry.KEY_ANDROID_CAPTURE_FPS)
                  || ((MdtaMetadataEntry) entry).typeIndicator
                      == MdtaMetadataEntry.TYPE_INDICATOR_STRING
                  || ((MdtaMetadataEntry) entry).typeIndicator
                      == MdtaMetadataEntry.TYPE_INDICATOR_FLOAT32))) {
        metadataEntries.add(entry);
      }
    }
  }

  @Override
  public void release(boolean forCancellation) throws MuxerException {
    writeMetadata();

    try {
      mp4Muxer.close();
    } catch (IOException e) {
      throw new MuxerException("Error closing muxer", e);
    }
  }

  @Override
  public long getMaxDelayBetweenSamplesMs() {
    return maxDelayBetweenSamplesMs;
  }

  private void writeMetadata() {
    if (metadataProvider != null) {
      Set<Metadata.Entry> metadataEntriesCopy = new LinkedHashSet<>(metadataEntries);
      metadataProvider.updateMetadataEntries(metadataEntriesCopy);
      metadataEntries.clear();
      metadataEntries.addAll(metadataEntriesCopy);
    }

    for (Metadata.Entry entry : metadataEntries) {
      // LINT.IfChange(written_metadata)
      if (entry instanceof Mp4LocationData) {
        mp4Muxer.setLocation(
            ((Mp4LocationData) entry).latitude, ((Mp4LocationData) entry).longitude);
      } else if (entry instanceof XmpData) {
        mp4Muxer.addXmp(ByteBuffer.wrap(((XmpData) entry).data));
      } else if (entry instanceof Mp4TimestampData) {
        // TODO: b/285281716 - Use creation time specific API.
        mp4Muxer.setModificationTime(
            Mp4Util.mp4TimeToUnixTimeMs(((Mp4TimestampData) entry).creationTimestampSeconds));
      } else if (entry instanceof MdtaMetadataEntry) {
        MdtaMetadataEntry mdtaMetadataEntry = (MdtaMetadataEntry) entry;
        if (mdtaMetadataEntry.key.equals(MdtaMetadataEntry.KEY_ANDROID_CAPTURE_FPS)) {
          byte[] captureFps = mdtaMetadataEntry.value;
          mp4Muxer.setCaptureFps(ByteBuffer.wrap(captureFps).getFloat());
        } else if (mdtaMetadataEntry.typeIndicator == MdtaMetadataEntry.TYPE_INDICATOR_STRING) {
          mp4Muxer.addMetadata(mdtaMetadataEntry.key, Util.fromUtf8Bytes(mdtaMetadataEntry.value));
        } else if (mdtaMetadataEntry.typeIndicator == MdtaMetadataEntry.TYPE_INDICATOR_FLOAT32) {
          mp4Muxer.addMetadata(mdtaMetadataEntry.key, Util.toFloat(mdtaMetadataEntry.value));
        } else {
          throw new IllegalStateException("Unsupported MdtaMetadataEntry " + mdtaMetadataEntry.key);
        }
      } else {
        throw new IllegalStateException("Unsupported Metadata.Entry " + entry.getClass().getName());
      }
    }
  }
}
