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

package androidx.media3.transformer;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem.ClippingConfiguration;
import androidx.media3.common.StreamKey;
import androidx.media3.common.Timeline;
import androidx.media3.common.audio.SpeedProvider;
import androidx.media3.common.util.NullableType;
import androidx.media3.common.util.SpeedProviderUtil.SpeedProviderMapper;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.LoadingInfo;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.source.ForwardingTimeline;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.SampleStream;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.source.WrappingMediaSource;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.upstream.Allocator;
import java.io.IOException;
import java.util.List;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** A {@link MediaSource} that applies a {@link SpeedProvider} to all timestamps. */
/* package */ final class SpeedChangingMediaSource extends WrappingMediaSource {

  private final SpeedProviderMapper speedProviderMapper;
  private final long clipStartUs;

  public SpeedChangingMediaSource(
      MediaSource mediaSource,
      SpeedProvider speedProvider,
      ClippingConfiguration clippingConfiguration) {
    super(mediaSource);
    this.clipStartUs = clippingConfiguration.startPositionUs;
    this.speedProviderMapper = new SpeedProviderMapper(speedProvider);
  }

  @Override
  public MediaPeriod createPeriod(MediaPeriodId id, Allocator allocator, long startPositionUs) {
    return new SpeedProviderMediaPeriod(
        super.createPeriod(id, allocator, startPositionUs), speedProviderMapper, clipStartUs);
  }

  @Override
  public void releasePeriod(MediaPeriod mediaPeriod) {
    MediaPeriod wrappedPeriod = ((SpeedProviderMediaPeriod) mediaPeriod).getWrappedMediaPeriod();
    super.releasePeriod(wrappedPeriod);
  }

  @Override
  protected void onChildSourceInfoRefreshed(Timeline newTimeline) {
    Timeline timeline =
        new ForwardingTimeline(newTimeline) {
          @Override
          public Window getWindow(
              int windowIndex, Window window, long defaultPositionProjectionUs) {
            Window wrappedWindow =
                newTimeline.getWindow(windowIndex, window, defaultPositionProjectionUs);
            checkState(
                wrappedWindow.firstPeriodIndex == wrappedWindow.lastPeriodIndex,
                "SpeedChangingMediaSource does not support multiple Period instances per Window.");
            // If the MediaItem is clipped, durationUs is the clipped duration.
            long unadjustedWindowDurationUs = wrappedWindow.durationUs;
            if (unadjustedWindowDurationUs != C.TIME_UNSET) {
              // The window's duration represents the speed-adjusted duration of the MediaItem over
              // the clipped duration (if clipped).
              wrappedWindow.durationUs =
                  speedProviderMapper.getAdjustedTimeUs(unadjustedWindowDurationUs);
            }
            return wrappedWindow;
          }

          @Override
          public Period getPeriod(int periodIndex, Period period, boolean setIds) {
            Period wrappedPeriod = newTimeline.getPeriod(periodIndex, period, setIds);
            checkState(
                wrappedPeriod.positionInWindowUs <= 0,
                "SpeedChangingMediaSource does not support Period instances starting after their"
                    + " Window.");
            if (wrappedPeriod.durationUs != C.TIME_UNSET) {
              // Clip start provided to SpeedChangingMediaSource should match the actual period's
              // clip start.
              checkState(clipStartUs == -wrappedPeriod.positionInWindowUs);
              wrappedPeriod.durationUs =
                  getAdjustedPeriodTimeUs(
                      wrappedPeriod.durationUs, speedProviderMapper, clipStartUs);
            }
            return wrappedPeriod;
          }
        };
    super.onChildSourceInfoRefreshed(timeline);
  }

  /**
   * Returns the speed-adjusted period position in microseconds.
   *
   * <p>This is the inverse operation of {@link #getOriginalPeriodTimeUs}.
   *
   * @param originalPeriodPositionUs The original period position in microseconds.
   */
  private static long getAdjustedPeriodTimeUs(
      long originalPeriodPositionUs, SpeedProviderMapper mapper, long clipStartUs) {
    if (originalPeriodPositionUs == C.TIME_UNSET
        || originalPeriodPositionUs == C.TIME_END_OF_SOURCE) {
      return originalPeriodPositionUs;
    }

    // Do not speed-adjust negative timestamps.
    if (originalPeriodPositionUs - clipStartUs < 0) {
      return originalPeriodPositionUs;
    }

    return mapper.getAdjustedTimeUs(originalPeriodPositionUs - clipStartUs) + clipStartUs;
  }

  /**
   * Returns the original period position in microseconds.
   *
   * <p>This is the inverse operation of {@link #getAdjustedPeriodTimeUs}.
   *
   * @param adjustedPeriodPositionUs The speed-adjusted period position in microseconds.
   */
  private static long getOriginalPeriodTimeUs(
      long adjustedPeriodPositionUs, SpeedProviderMapper mapper, long clipStartUs) {
    if (adjustedPeriodPositionUs == C.TIME_UNSET
        || adjustedPeriodPositionUs == C.TIME_END_OF_SOURCE) {
      return adjustedPeriodPositionUs;
    }

    // Do not speed-adjust negative timestamps.
    if (adjustedPeriodPositionUs - clipStartUs < 0) {
      return adjustedPeriodPositionUs;
    }

    return mapper.getOriginalTimeUs(adjustedPeriodPositionUs - clipStartUs) + clipStartUs;
  }

  /** A {@link MediaPeriod} that adjusts the timestamps as specified by the speed provider. */
  /* package */ static final class SpeedProviderMediaPeriod
      implements MediaPeriod, MediaPeriod.Callback {

    public final MediaPeriod mediaPeriod;
    private final SpeedProviderMapper speedProviderMapper;
    private final long clipStartUs;
    private @MonotonicNonNull Callback callback;

    /**
     * Create an instance.
     *
     * @param mediaPeriod The wrapped {@link MediaPeriod}.
     * @param speedProviderMapper The {@link SpeedProviderMapper} to scale the original media times.
     * @param clipStartUs The start position of the clip in microseconds.
     */
    public SpeedProviderMediaPeriod(
        MediaPeriod mediaPeriod, SpeedProviderMapper speedProviderMapper, long clipStartUs) {
      this.mediaPeriod = mediaPeriod;
      this.speedProviderMapper = speedProviderMapper;
      this.clipStartUs = clipStartUs;
    }

    /** Returns the wrapped {@link MediaPeriod}. */
    public MediaPeriod getWrappedMediaPeriod() {
      return mediaPeriod;
    }

    @Override
    public void prepare(Callback callback, long positionUs) {
      this.callback = callback;
      mediaPeriod.prepare(
          /* callback= */ this,
          getOriginalPeriodTimeUs(positionUs, speedProviderMapper, clipStartUs));
    }

    @Override
    public void maybeThrowPrepareError() throws IOException {
      mediaPeriod.maybeThrowPrepareError();
    }

    @Override
    public TrackGroupArray getTrackGroups() {
      return mediaPeriod.getTrackGroups();
    }

    @Override
    public List<StreamKey> getStreamKeys(List<ExoTrackSelection> trackSelections) {
      return mediaPeriod.getStreamKeys(trackSelections);
    }

    @Override
    public long selectTracks(
        @NullableType ExoTrackSelection[] selections,
        boolean[] mayRetainStreamFlags,
        @NullableType SampleStream[] streams,
        boolean[] streamResetFlags,
        long positionUs) {
      @NullableType SampleStream[] childStreams = new SampleStream[streams.length];
      for (int i = 0; i < streams.length; i++) {
        SpeedProviderMapperSampleStream sampleStream = (SpeedProviderMapperSampleStream) streams[i];
        childStreams[i] = sampleStream != null ? sampleStream.getChildStream() : null;
      }
      long startPositionUs =
          mediaPeriod.selectTracks(
              selections,
              mayRetainStreamFlags,
              childStreams,
              streamResetFlags,
              getOriginalPeriodTimeUs(positionUs, speedProviderMapper, clipStartUs));
      for (int i = 0; i < streams.length; i++) {
        @Nullable SampleStream childStream = childStreams[i];
        if (childStream == null) {
          streams[i] = null;
        } else if (streams[i] == null
            || ((SpeedProviderMapperSampleStream) streams[i]).getChildStream() != childStream) {
          streams[i] =
              new SpeedProviderMapperSampleStream(childStream, speedProviderMapper, clipStartUs);
        }
      }
      return getAdjustedPeriodTimeUs(startPositionUs, speedProviderMapper, clipStartUs);
    }

    @Override
    public void discardBuffer(long positionUs, boolean toKeyframe) {
      mediaPeriod.discardBuffer(
          getOriginalPeriodTimeUs(positionUs, speedProviderMapper, clipStartUs), toKeyframe);
    }

    @Override
    public long readDiscontinuity() {
      return getAdjustedPeriodTimeUs(
          mediaPeriod.readDiscontinuity(), speedProviderMapper, clipStartUs);
    }

    @Override
    public long seekToUs(long positionUs) {
      return getAdjustedPeriodTimeUs(
          mediaPeriod.seekToUs(
              getOriginalPeriodTimeUs(positionUs, speedProviderMapper, clipStartUs)),
          speedProviderMapper,
          clipStartUs);
    }

    @Override
    public long getAdjustedSeekPositionUs(long positionUs, SeekParameters seekParameters) {
      long originalPositionUs =
          getOriginalPeriodTimeUs(positionUs, speedProviderMapper, clipStartUs);
      long adjustedSeekPosition =
          mediaPeriod.getAdjustedSeekPositionUs(originalPositionUs, seekParameters);
      return getAdjustedPeriodTimeUs(adjustedSeekPosition, speedProviderMapper, clipStartUs);
    }

    @Override
    public long getBufferedPositionUs() {
      return getAdjustedPeriodTimeUs(
          mediaPeriod.getBufferedPositionUs(), speedProviderMapper, clipStartUs);
    }

    @Override
    public long getNextLoadPositionUs() {
      return getAdjustedPeriodTimeUs(
          mediaPeriod.getNextLoadPositionUs(), speedProviderMapper, clipStartUs);
    }

    @Override
    public boolean continueLoading(LoadingInfo loadingInfo) {
      return mediaPeriod.continueLoading(
          loadingInfo
              .buildUpon()
              .setPlaybackPositionUs(
                  getOriginalPeriodTimeUs(
                      loadingInfo.playbackPositionUs, speedProviderMapper, clipStartUs))
              .build());
    }

    @Override
    public boolean isLoading() {
      return mediaPeriod.isLoading();
    }

    @Override
    public void reevaluateBuffer(long positionUs) {
      mediaPeriod.reevaluateBuffer(
          getOriginalPeriodTimeUs(positionUs, speedProviderMapper, clipStartUs));
    }

    @Override
    public void onPrepared(MediaPeriod mediaPeriod) {
      checkNotNull(callback).onPrepared(/* mediaPeriod= */ this);
    }

    @Override
    public void onContinueLoadingRequested(MediaPeriod source) {
      checkNotNull(callback).onContinueLoadingRequested(/* source= */ this);
    }

    private static final class SpeedProviderMapperSampleStream implements SampleStream {

      private final SampleStream sampleStream;
      private final SpeedProviderMapper speedProviderMapper;
      private final long clipStartUs;

      public SpeedProviderMapperSampleStream(
          SampleStream sampleStream, SpeedProviderMapper speedProviderMapper, long clipStartUs) {
        this.sampleStream = sampleStream;
        this.speedProviderMapper = speedProviderMapper;
        this.clipStartUs = clipStartUs;
      }

      public SampleStream getChildStream() {
        return sampleStream;
      }

      @Override
      public boolean isReady() {
        return sampleStream.isReady();
      }

      @Override
      public void maybeThrowError() throws IOException {
        sampleStream.maybeThrowError();
      }

      @Override
      public int readData(
          FormatHolder formatHolder, DecoderInputBuffer buffer, @ReadFlags int readFlags) {
        int readResult = sampleStream.readData(formatHolder, buffer, readFlags);
        if (readResult == C.RESULT_BUFFER_READ && !buffer.isEndOfStream()) {
          buffer.timeUs = getAdjustedPeriodTimeUs(buffer.timeUs, speedProviderMapper, clipStartUs);
        }
        return readResult;
      }

      @Override
      public int skipData(long positionUs) {
        return sampleStream.skipData(
            getOriginalPeriodTimeUs(positionUs, speedProviderMapper, clipStartUs));
      }
    }
  }
}
