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

import android.media.MediaCodec.BufferInfo;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.container.Mp4OrientationData;
import androidx.media3.muxer.FragmentedMp4Muxer;
import androidx.media3.muxer.Mp4Muxer;
import androidx.media3.muxer.Mp4Utils;
import androidx.media3.muxer.Muxer;
import androidx.media3.muxer.Muxer.TrackToken;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.LinkedHashSet;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.Nullable;

/** {@link Muxer} implementation that uses an {@link Mp4Muxer} or {@link FragmentedMp4Muxer}. */
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
     * <p>For the list of supported metadata refer to {@link
     * Mp4Muxer#addMetadataEntry(Metadata.Entry)}.
     */
    void updateMetadataEntries(Set<Metadata.Entry> metadataEntries);
  }

  /** {@link Muxer.Factory} for {@link InAppMuxer}. */
  public static final class Factory implements Muxer.Factory {

    /** A builder for {@link Factory} instances. */
    public static final class Builder {
      private @Nullable MetadataProvider metadataProvider;
      private boolean outputFragmentedMp4;
      private long fragmentDurationMs;

      /** Creates a {@link Builder} instance with default values. */
      public Builder() {
        fragmentDurationMs = C.TIME_UNSET;
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

      /** Sets whether to output a fragmented MP4. */
      @CanIgnoreReturnValue
      public Builder setOutputFragmentedMp4(boolean outputFragmentedMp4) {
        this.outputFragmentedMp4 = outputFragmentedMp4;
        return this;
      }

      /**
       * Sets the fragment duration (in milliseconds) if the output file is {@link
       * #setOutputFragmentedMp4(boolean) fragmented}.
       */
      @CanIgnoreReturnValue
      public Builder setFragmentDurationMs(long fragmentDurationMs) {
        this.fragmentDurationMs = fragmentDurationMs;
        return this;
      }

      /** Builds a {@link Factory} instance. */
      public Factory build() {
        return new Factory(metadataProvider, outputFragmentedMp4, fragmentDurationMs);
      }
    }

    /** A list of supported video sample MIME types. */
    private static final ImmutableList<String> SUPPORTED_VIDEO_SAMPLE_MIME_TYPES =
        ImmutableList.of(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265, MimeTypes.VIDEO_AV1);

    /** A list of supported audio sample MIME types. */
    private static final ImmutableList<String> SUPPORTED_AUDIO_SAMPLE_MIME_TYPES =
        ImmutableList.of(MimeTypes.AUDIO_AAC);

    private final @Nullable MetadataProvider metadataProvider;
    private final boolean outputFragmentedMp4;
    private final long fragmentDurationMs;

    private Factory(
        @Nullable MetadataProvider metadataProvider,
        boolean outputFragmentedMp4,
        long fragmentDurationMs) {
      this.metadataProvider = metadataProvider;
      this.outputFragmentedMp4 = outputFragmentedMp4;
      this.fragmentDurationMs = fragmentDurationMs;
    }

    @Override
    public InAppMuxer create(String path) throws MuxerException {
      FileOutputStream outputStream;
      try {
        outputStream = new FileOutputStream(path);
      } catch (FileNotFoundException e) {
        throw new MuxerException("Error creating file output stream", e);
      }

      androidx.media3.muxer.Muxer muxer =
          outputFragmentedMp4
              ? fragmentDurationMs != C.TIME_UNSET
                  ? new FragmentedMp4Muxer.Builder(outputStream)
                      .setFragmentDurationMs(fragmentDurationMs)
                      .build()
                  : new FragmentedMp4Muxer.Builder(outputStream).build()
              : new Mp4Muxer.Builder(outputStream).build();
      return new InAppMuxer(muxer, metadataProvider);
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

  private final androidx.media3.muxer.Muxer muxer;
  private final @Nullable MetadataProvider metadataProvider;
  private final Set<Metadata.Entry> metadataEntries;

  private InAppMuxer(
      androidx.media3.muxer.Muxer muxer, @Nullable MetadataProvider metadataProvider) {
    this.muxer = muxer;
    this.metadataProvider = metadataProvider;
    metadataEntries = new LinkedHashSet<>();
  }

  @Override
  public TrackToken addTrack(Format format) throws MuxerException {
    TrackToken trackToken = muxer.addTrack(format);
    if (MimeTypes.isVideo(format.sampleMimeType)) {
      muxer.addMetadataEntry(new Mp4OrientationData(format.rotationDegrees));
    }
    return trackToken;
  }

  @Override
  public void writeSampleData(TrackToken trackToken, ByteBuffer byteBuffer, BufferInfo bufferInfo)
      throws MuxerException {
    muxer.writeSampleData(trackToken, byteBuffer, bufferInfo);
  }

  @Override
  public void addMetadataEntry(Metadata.Entry metadataEntry) {
    if (Mp4Utils.isMetadataSupported(metadataEntry)) {
      metadataEntries.add(metadataEntry);
    }
  }

  @Override
  public void close() throws MuxerException {
    writeMetadata();
    muxer.close();
  }

  private void writeMetadata() {
    if (metadataProvider != null) {
      Set<Metadata.Entry> metadataEntriesCopy = new LinkedHashSet<>(metadataEntries);
      metadataProvider.updateMetadataEntries(metadataEntriesCopy);
      metadataEntries.clear();
      metadataEntries.addAll(metadataEntriesCopy);
    }

    for (Metadata.Entry entry : metadataEntries) {
      muxer.addMetadataEntry(entry);
    }
  }
}
