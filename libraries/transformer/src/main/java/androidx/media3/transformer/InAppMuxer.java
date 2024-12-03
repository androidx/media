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

import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.muxer.Mp4Muxer.LAST_SAMPLE_DURATION_BEHAVIOR_SET_FROM_END_OF_STREAM_BUFFER_OR_DUPLICATE_PREVIOUS;

import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.container.Mp4OrientationData;
import androidx.media3.muxer.FragmentedMp4Muxer;
import androidx.media3.muxer.Mp4Muxer;
import androidx.media3.muxer.Muxer;
import androidx.media3.muxer.MuxerUtil;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

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
      @Nullable private MetadataProvider metadataProvider;
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
        ImmutableList.of(
            MimeTypes.VIDEO_AV1,
            MimeTypes.VIDEO_H263,
            MimeTypes.VIDEO_H264,
            MimeTypes.VIDEO_H265,
            MimeTypes.VIDEO_MP4V);

    /** A list of supported audio sample MIME types. */
    private static final ImmutableList<String> SUPPORTED_AUDIO_SAMPLE_MIME_TYPES =
        ImmutableList.of(
            MimeTypes.AUDIO_AAC,
            MimeTypes.AUDIO_AMR_NB,
            MimeTypes.AUDIO_AMR_WB,
            MimeTypes.AUDIO_OPUS,
            MimeTypes.AUDIO_VORBIS);

    @Nullable private final MetadataProvider metadataProvider;
    private final boolean outputFragmentedMp4;
    private final long fragmentDurationMs;

    private long videoDurationUs;

    private Factory(
        @Nullable MetadataProvider metadataProvider,
        boolean outputFragmentedMp4,
        long fragmentDurationMs) {
      this.metadataProvider = metadataProvider;
      this.outputFragmentedMp4 = outputFragmentedMp4;
      this.fragmentDurationMs = fragmentDurationMs;
      videoDurationUs = C.TIME_UNSET;
    }

    /**
     * Sets the duration of the video track (in microseconds) in the output.
     *
     * <p>Only the duration of the last sample is adjusted to achieve the given duration. Duration
     * of the other samples remains unchanged.
     *
     * <p>The default is {@link C#TIME_UNSET} to not set any duration in the output. In this case
     * the video track duration is determined by the samples written to it and the duration of the
     * last sample will be same as that of the sample before that.
     *
     * @param videoDurationUs The duration of the video track (in microseconds) in the output, or
     *     {@link C#TIME_UNSET} to not set any duration. Only applicable when a video track is
     *     {@linkplain #addTrack(Format) added}.
     * @return This factory.
     */
    @CanIgnoreReturnValue
    public Factory setVideoDurationUs(long videoDurationUs) {
      this.videoDurationUs = videoDurationUs;
      return this;
    }

    @Override
    public InAppMuxer create(String path) throws MuxerException {
      FileOutputStream outputStream;
      try {
        outputStream = new FileOutputStream(path);
      } catch (FileNotFoundException e) {
        throw new MuxerException("Error creating file output stream", e);
      }

      Muxer muxer = null;
      if (outputFragmentedMp4) {
        FragmentedMp4Muxer.Builder builder = new FragmentedMp4Muxer.Builder(outputStream);
        if (fragmentDurationMs != C.TIME_UNSET) {
          builder.setFragmentDurationMs(fragmentDurationMs);
        }
        muxer = builder.build();
      } else {
        Mp4Muxer.Builder builder = new Mp4Muxer.Builder(outputStream);
        if (videoDurationUs != C.TIME_UNSET) {
          builder.setLastSampleDurationBehavior(
              LAST_SAMPLE_DURATION_BEHAVIOR_SET_FROM_END_OF_STREAM_BUFFER_OR_DUPLICATE_PREVIOUS);
        }
        muxer = builder.build();
      }

      return new InAppMuxer(muxer, metadataProvider, videoDurationUs);
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

  private static final String TAG = "InAppMuxer";

  private final Muxer muxer;
  @Nullable private final MetadataProvider metadataProvider;
  private final long videoDurationUs;
  private final Set<Metadata.Entry> metadataEntries;

  @Nullable private TrackToken videoTrackToken;

  private InAppMuxer(
      Muxer muxer, @Nullable MetadataProvider metadataProvider, long videoDurationUs) {
    this.muxer = muxer;
    this.metadataProvider = metadataProvider;
    this.videoDurationUs = videoDurationUs;
    metadataEntries = new LinkedHashSet<>();
  }

  @Override
  public TrackToken addTrack(Format format) throws MuxerException {
    TrackToken trackToken = muxer.addTrack(format);
    if (MimeTypes.isVideo(format.sampleMimeType)) {
      muxer.addMetadataEntry(new Mp4OrientationData(format.rotationDegrees));
      videoTrackToken = trackToken;
    }
    return trackToken;
  }

  @Override
  public void writeSampleData(TrackToken trackToken, ByteBuffer byteBuffer, BufferInfo bufferInfo)
      throws MuxerException {
    if (videoDurationUs != C.TIME_UNSET
        && trackToken == videoTrackToken
        && bufferInfo.presentationTimeUs > videoDurationUs) {
      Log.w(
          TAG,
          String.format(
              Locale.US,
              "Skipped sample with presentation time (%d) > video duration (%d)",
              bufferInfo.presentationTimeUs,
              videoDurationUs));
      return;
    }
    muxer.writeSampleData(trackToken, byteBuffer, bufferInfo);
  }

  @Override
  public void addMetadataEntry(Metadata.Entry metadataEntry) {
    if (MuxerUtil.isMetadataSupported(metadataEntry)) {
      metadataEntries.add(metadataEntry);
    }
  }

  @Override
  public void close() throws MuxerException {
    if (videoDurationUs != C.TIME_UNSET && videoTrackToken != null) {
      BufferInfo bufferInfo = new BufferInfo();
      bufferInfo.set(
          /* newOffset= */ 0,
          /* newSize= */ 0,
          videoDurationUs,
          MediaCodec.BUFFER_FLAG_END_OF_STREAM);
      writeSampleData(checkNotNull(videoTrackToken), ByteBuffer.allocateDirect(0), bufferInfo);
    }
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
