/*
 * Copyright 2021 The Android Open Source Project
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
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.common.util.Util.SDK_INT;
import static androidx.media3.common.util.Util.castNonNull;

import android.annotation.SuppressLint;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.MediaFormatUtil;
import androidx.media3.common.util.Util;
import androidx.media3.container.Mp4LocationData;
import androidx.media3.muxer.Muxer;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** {@link Muxer} implementation that uses a {@link MediaMuxer}. */
/* package */ final class FrameworkMuxer implements Muxer {
  /** {@link Muxer.Factory} for {@link FrameworkMuxer}. */
  public static final class Factory implements Muxer.Factory {
    private long videoDurationUs;

    public Factory() {
      this.videoDurationUs = C.TIME_UNSET;
    }

    /**
     * Sets the duration of the video track (in microseconds) in the output.
     *
     * <p>Only the duration of the last sample is adjusted to achieve the given duration. Duration
     * of the other samples remains unchanged.
     *
     * <p>The default is {@link C#TIME_UNSET} to not set any duration in the output. In this case
     * the video track duration is determined by the samples written to it and the duration of the
     * last sample would be the same as that of the sample before that.
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
    public FrameworkMuxer create(String path) throws MuxerException {
      MediaMuxer mediaMuxer;
      try {
        mediaMuxer = new MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
      } catch (IOException e) {
        throw new MuxerException("Error creating muxer", e);
      }
      return new FrameworkMuxer(mediaMuxer, videoDurationUs);
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

  public static final String MUXER_STOPPING_FAILED_ERROR_MESSAGE = "Failed to stop the MediaMuxer";

  // MediaMuxer supported sample formats are documented in MediaMuxer.addTrack(MediaFormat).
  private static final ImmutableList<String> SUPPORTED_VIDEO_SAMPLE_MIME_TYPES =
      getSupportedVideoSampleMimeTypes();
  private static final ImmutableList<String> SUPPORTED_AUDIO_SAMPLE_MIME_TYPES =
      ImmutableList.of(MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_AMR_NB, MimeTypes.AUDIO_AMR_WB);
  private static final String TAG = "FrameworkMuxer";

  private final MediaMuxer mediaMuxer;
  private final long videoDurationUs;
  private final Map<TrackToken, Long> trackTokenToLastPresentationTimeUs;
  private final Map<TrackToken, Long> trackTokenToPresentationTimeOffsetUs;

  @Nullable private TrackToken videoTrackToken;

  private boolean isStarted;
  private boolean isReleased;

  private FrameworkMuxer(MediaMuxer mediaMuxer, long videoDurationUs) {
    this.mediaMuxer = mediaMuxer;
    this.videoDurationUs = videoDurationUs;
    trackTokenToLastPresentationTimeUs = new HashMap<>();
    trackTokenToPresentationTimeOffsetUs = new HashMap<>();
  }

  @Override
  public TrackToken addTrack(Format format) throws MuxerException {
    String sampleMimeType = checkNotNull(format.sampleMimeType);
    MediaFormat mediaFormat;
    boolean isVideo = MimeTypes.isVideo(sampleMimeType);
    if (isVideo) {
      mediaFormat = MediaFormat.createVideoFormat(sampleMimeType, format.width, format.height);
      MediaFormatUtil.maybeSetColorInfo(mediaFormat, format.colorInfo);
      try {
        mediaMuxer.setOrientationHint(format.rotationDegrees);
      } catch (RuntimeException e) {
        throw new MuxerException(
            "Failed to set orientation hint with rotationDegrees=" + format.rotationDegrees, e);
      }
    } else {
      mediaFormat =
          MediaFormat.createAudioFormat(sampleMimeType, format.sampleRate, format.channelCount);
      MediaFormatUtil.maybeSetString(mediaFormat, MediaFormat.KEY_LANGUAGE, format.language);
    }
    MediaFormatUtil.setCsdBuffers(mediaFormat, format.initializationData);
    int trackIndex;
    try {
      trackIndex = mediaMuxer.addTrack(mediaFormat);
    } catch (RuntimeException e) {
      throw new MuxerException("Failed to add track with format=" + format, e);
    }

    TrackToken trackToken = new TrackTokenImpl(trackIndex);
    if (isVideo) {
      videoTrackToken = trackToken;
    }

    return trackToken;
  }

  @Override
  public void writeSampleData(TrackToken trackToken, ByteBuffer data, BufferInfo bufferInfo)
      throws MuxerException {
    long presentationTimeUs = bufferInfo.presentationTimeUs;
    if (videoDurationUs != C.TIME_UNSET
        && trackToken == videoTrackToken
        && presentationTimeUs > videoDurationUs) {
      Log.w(
          TAG,
          String.format(
              Locale.US,
              "Skipped sample with presentation time (%d) > video duration (%d)",
              presentationTimeUs,
              videoDurationUs));
      return;
    }
    if (!isStarted) {
      if (Util.SDK_INT < 30 && presentationTimeUs < 0) {
        trackTokenToPresentationTimeOffsetUs.put(trackToken, -presentationTimeUs);
      }
      startMuxer();
    }

    long presentationTimeOffsetUs =
        trackTokenToPresentationTimeOffsetUs.containsKey(trackToken)
            ? trackTokenToPresentationTimeOffsetUs.get(trackToken)
            : 0;
    presentationTimeUs += presentationTimeOffsetUs;

    long lastSamplePresentationTimeUs =
        trackTokenToLastPresentationTimeUs.containsKey(trackToken)
            ? trackTokenToLastPresentationTimeUs.get(trackToken)
            : 0;
    // writeSampleData blocks on old API versions, so check here to avoid calling the method.
    checkState(
        Util.SDK_INT > 24 || presentationTimeUs >= lastSamplePresentationTimeUs,
        "Samples not in presentation order ("
            + presentationTimeUs
            + " < "
            + lastSamplePresentationTimeUs
            + ") unsupported on this API version");
    trackTokenToLastPresentationTimeUs.put(trackToken, presentationTimeUs);

    checkState(
        presentationTimeOffsetUs == 0 || presentationTimeUs >= 0,
        String.format(
            Locale.US,
            "Sample presentation time (%d) < first sample presentation time (%d). Ensure the first"
                + " sample has the smallest timestamp when using the negative PTS workaround.",
            presentationTimeUs - presentationTimeOffsetUs,
            -presentationTimeOffsetUs));
    bufferInfo.set(bufferInfo.offset, bufferInfo.size, presentationTimeUs, bufferInfo.flags);

    try {
      checkState(trackToken instanceof TrackTokenImpl);
      mediaMuxer.writeSampleData(((TrackTokenImpl) trackToken).trackIndex, data, bufferInfo);
    } catch (RuntimeException e) {
      throw new MuxerException(
          "Failed to write sample for presentationTimeUs="
              + presentationTimeUs
              + ", size="
              + bufferInfo.size,
          e);
    }
  }

  @Override
  public void addMetadataEntry(Metadata.Entry metadataEntry) {
    if (metadataEntry instanceof Mp4LocationData) {
      mediaMuxer.setLocation(
          ((Mp4LocationData) metadataEntry).latitude, ((Mp4LocationData) metadataEntry).longitude);
    }
  }

  @Override
  public void close() throws MuxerException {
    if (isReleased) {
      return;
    }

    if (!isStarted) {
      // Start the muxer even if no samples have been written so that it throws instead of silently
      // writing nothing to the output file.
      startMuxer();
    }

    if (videoDurationUs != C.TIME_UNSET && videoTrackToken != null) {
      BufferInfo bufferInfo = new BufferInfo();
      bufferInfo.set(
          /* newOffset= */ 0,
          /* newSize= */ 0,
          videoDurationUs,
          TransformerUtil.getMediaCodecFlags(C.BUFFER_FLAG_END_OF_STREAM));
      writeSampleData(checkNotNull(videoTrackToken), ByteBuffer.allocateDirect(0), bufferInfo);
    }

    isStarted = false;
    try {
      stopMuxer(mediaMuxer);
    } catch (RuntimeException e) {
      throw new MuxerException(MUXER_STOPPING_FAILED_ERROR_MESSAGE, e);
    } finally {
      mediaMuxer.release();
      isReleased = true;
    }
  }

  private void startMuxer() throws MuxerException {
    try {
      mediaMuxer.start();
    } catch (RuntimeException e) {
      throw new MuxerException("Failed to start the muxer", e);
    }
    isStarted = true;
  }

  // Accesses MediaMuxer state via reflection to ensure that muxer resources can be released even
  // if stopping fails.
  @SuppressLint("PrivateApi")
  private static void stopMuxer(MediaMuxer mediaMuxer) {
    try {
      mediaMuxer.stop();
    } catch (RuntimeException e) {
      if (SDK_INT < 30) {
        // Set the muxer state to stopped even if mediaMuxer.stop() failed so that
        // mediaMuxer.release() doesn't attempt to stop the muxer and therefore doesn't throw the
        // same exception without releasing its resources. This is already implemented in MediaMuxer
        // from API level 30. See also b/80338884.
        try {
          Field muxerStoppedStateField = MediaMuxer.class.getDeclaredField("MUXER_STATE_STOPPED");
          muxerStoppedStateField.setAccessible(true);
          int muxerStoppedState = castNonNull((Integer) muxerStoppedStateField.get(mediaMuxer));
          Field muxerStateField = MediaMuxer.class.getDeclaredField("mState");
          muxerStateField.setAccessible(true);
          muxerStateField.set(mediaMuxer, muxerStoppedState);
        } catch (Exception reflectionException) {
          // Do nothing.
        }
      }
      // Rethrow the original error.
      throw e;
    }
  }

  private static ImmutableList<String> getSupportedVideoSampleMimeTypes() {
    ImmutableList.Builder<String> supportedMimeTypes =
        new ImmutableList.Builder<String>()
            .add(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H263, MimeTypes.VIDEO_MP4V);
    if (SDK_INT >= 24) {
      supportedMimeTypes.add(MimeTypes.VIDEO_H265);
    }
    if (SDK_INT >= 34) {
      supportedMimeTypes.add(MimeTypes.VIDEO_AV1);
    }
    return supportedMimeTypes.build();
  }

  private static class TrackTokenImpl implements TrackToken {
    public final int trackIndex;

    public TrackTokenImpl(int trackIndex) {
      this.trackIndex = trackIndex;
    }
  }
}
