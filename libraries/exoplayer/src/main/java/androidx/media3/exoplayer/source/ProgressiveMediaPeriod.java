/*
 * Copyright (C) 2016 The Android Open Source Project
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
package androidx.media3.exoplayer.source;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.max;
import static java.lang.Math.min;

import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.C;
import androidx.media3.common.C.DataType;
import androidx.media3.common.DataReader;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.util.CodecSpecificDataUtil;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.NullableType;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSourceUtil;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.StatsDataSource;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.LoadingInfo;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.drm.DrmSessionEventListener;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.source.SampleQueue.UpstreamFormatChangedListener;
import androidx.media3.exoplayer.source.SampleStream.ReadFlags;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo;
import androidx.media3.exoplayer.upstream.Loader;
import androidx.media3.exoplayer.upstream.Loader.LoadErrorAction;
import androidx.media3.exoplayer.upstream.Loader.Loadable;
import androidx.media3.exoplayer.util.ReleasableExecutor;
import androidx.media3.extractor.DiscardingTrackOutput;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ForwardingSeekMap;
import androidx.media3.extractor.ForwardingTrackOutput;
import androidx.media3.extractor.IndexSeekMap;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.SeekMap.SeekPoints;
import androidx.media3.extractor.SeekMap.Unseekable;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.metadata.icy.IcyHeaders;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.HttpHeaders;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** A {@link MediaPeriod} that extracts data using an {@link Extractor}. */
/* package */ final class ProgressiveMediaPeriod
    implements MediaPeriod,
        ExtractorOutput,
        Loader.Callback<ProgressiveMediaPeriod.ExtractingLoadable>,
        Loader.ReleaseCallback,
        UpstreamFormatChangedListener {

  /** Listener for information about the period. */
  interface Listener {

    /**
     * Called when the duration, the {@link SeekMap} of the period, or the categorization as live
     * stream changes.
     *
     * @param durationUs The duration of the period, or {@link C#TIME_UNSET}.
     * @param seekMap The {@link SeekMap}.
     * @param isLive Whether the period is live.
     */
    void onSourceInfoRefreshed(long durationUs, SeekMap seekMap, boolean isLive);
  }

  private static final String TAG = "ProgressiveMediaPeriod";

  /**
   * When the source's duration is unknown, it is calculated by adding this value to the largest
   * sample timestamp seen when buffering completes.
   */
  private static final long DEFAULT_LAST_SAMPLE_DURATION_US = 10_000;

  private static final Map<String, String> ICY_METADATA_HEADERS = createIcyMetadataHeaders();

  private static final Format ICY_FORMAT =
      new Format.Builder().setId("icy").setSampleMimeType(MimeTypes.APPLICATION_ICY).build();

  private final Uri uri;
  private final DataSource dataSource;
  private final DrmSessionManager drmSessionManager;
  private final LoadErrorHandlingPolicy loadErrorHandlingPolicy;
  private final MediaSourceEventListener.EventDispatcher mediaSourceEventDispatcher;
  private final DrmSessionEventListener.EventDispatcher drmEventDispatcher;
  private final Listener listener;
  private final Allocator allocator;
  @Nullable private final String customCacheKey;
  private final long continueLoadingCheckIntervalBytes;
  private final boolean loadOnlySelectedTracks;
  private final int singleTrackId;
  @Nullable private final Format singleTrackFormat;
  private final long singleSampleDurationUs;
  private final Loader loader;
  private final ProgressiveMediaExtractor progressiveMediaExtractor;
  private final ConditionVariable loadCondition;
  private final Runnable maybeFinishPrepareRunnable;
  private final Runnable onContinueLoadingRequestedRunnable;
  private final Handler handler;
  private final List<MergingMetadataSampleStream> mergingSampleStreams;
  private final LoadingStateMachine loadingStateMachine;

  @Nullable private Callback callback;
  @Nullable private IcyHeaders icyHeaders;
  // TODO(b/457738425) - Consider merging controledTrackOutputs, sampleQueues together, since they
  // are essentially the same objects.
  private ControlledTrackOutput[] controlledTrackOutputs;
  private SampleQueue[] sampleQueues;
  private TrackId[] sampleQueueTrackIds;
  private boolean sampleQueuesBuilt;

  private boolean prepared;
  private boolean haveAudioVideoTracks;
  private boolean isSingleSample;
  private @MonotonicNonNull TrackState trackState;
  private @MonotonicNonNull SeekMap seekMap;
  private long durationUs;
  private boolean isLive;
  private @DataType int dataType;
  private long endPositionUs;
  private int enabledTrackCount;
  private boolean isLengthKnown;
  private boolean released;

  /**
   * @param uri The {@link Uri} of the media stream.
   * @param dataSource The data source to read the media.
   * @param progressiveMediaExtractor The {@link ProgressiveMediaExtractor} to use to read the data
   *     source.
   * @param drmSessionManager A {@link DrmSessionManager} to allow DRM interactions.
   * @param drmEventDispatcher A dispatcher to notify of {@link DrmSessionEventListener} events.
   * @param loadErrorHandlingPolicy The {@link LoadErrorHandlingPolicy}.
   * @param mediaSourceEventDispatcher A dispatcher to notify of {@link MediaSourceEventListener}
   *     events.
   * @param listener A listener to notify when information about the period changes.
   * @param allocator An {@link Allocator} from which to obtain media buffer allocations.
   * @param customCacheKey A custom key that uniquely identifies the original stream. Used for cache
   *     indexing. May be null.
   * @param continueLoadingCheckIntervalBytes The number of bytes that should be loaded between each
   *     invocation of {@link Callback#onContinueLoadingRequested(SequenceableLoader)}.
   * @param loadOnlySelectedTracks Whether to load only the video and image tracks selected by the
   *     track selection policy. Audio, text, and metadata tracks are always loaded.
   * @param singleTrackId The ID of the track configured by {@code singleTrackFormat}. Ignored if
   *     {@code singleTrackFormat} is null.
   * @param singleTrackFormat The format of the single track this period is known to emit, allowing
   *     preparation to complete without reading any data. Otherwise null.
   * @param singleSampleDurationUs The duration of media with a single sample in microseconds.
   * @param downloadExecutor An optional externally provided {@link ReleasableExecutor} for loading
   *     and extracting media.
   */
  // maybeFinishPrepare is not posted to the handler until initialization completes.
  @SuppressWarnings({"nullness:argument", "nullness:methodref.receiver.bound"})
  public ProgressiveMediaPeriod(
      Uri uri,
      DataSource dataSource,
      ProgressiveMediaExtractor progressiveMediaExtractor,
      DrmSessionManager drmSessionManager,
      DrmSessionEventListener.EventDispatcher drmEventDispatcher,
      LoadErrorHandlingPolicy loadErrorHandlingPolicy,
      MediaSourceEventListener.EventDispatcher mediaSourceEventDispatcher,
      Listener listener,
      Allocator allocator,
      @Nullable String customCacheKey,
      int continueLoadingCheckIntervalBytes,
      boolean loadOnlySelectedTracks,
      int singleTrackId,
      @Nullable Format singleTrackFormat,
      long singleSampleDurationUs,
      @Nullable ReleasableExecutor downloadExecutor) {
    this.uri = uri;
    this.dataSource = dataSource;
    this.drmSessionManager = drmSessionManager;
    this.drmEventDispatcher = drmEventDispatcher;
    this.loadErrorHandlingPolicy = loadErrorHandlingPolicy;
    this.mediaSourceEventDispatcher = mediaSourceEventDispatcher;
    this.listener = listener;
    this.allocator = allocator;
    this.customCacheKey = customCacheKey;
    this.continueLoadingCheckIntervalBytes = continueLoadingCheckIntervalBytes;
    this.loadOnlySelectedTracks = loadOnlySelectedTracks;
    this.singleTrackId = singleTrackId;
    this.singleTrackFormat = singleTrackFormat;
    this.endPositionUs = C.TIME_END_OF_SOURCE;
    loader =
        downloadExecutor != null
            ? new Loader(downloadExecutor)
            : new Loader("ProgressiveMediaPeriod");
    this.progressiveMediaExtractor = progressiveMediaExtractor;
    this.singleSampleDurationUs = singleSampleDurationUs;
    loadCondition = new ConditionVariable();
    maybeFinishPrepareRunnable = this::maybeFinishPrepare;
    onContinueLoadingRequestedRunnable =
        () -> {
          if (!released) {
            checkNotNull(callback).onContinueLoadingRequested(ProgressiveMediaPeriod.this);
          }
        };
    handler = Util.createHandlerForCurrentLooper();
    sampleQueueTrackIds = new TrackId[0];
    sampleQueues = new SampleQueue[0];
    controlledTrackOutputs = new ControlledTrackOutput[0];
    mergingSampleStreams = new ArrayList<>();
    loadingStateMachine = new LoadingStateMachine();
    dataType = C.DATA_TYPE_MEDIA;
  }

  public void release() {
    if (prepared) {
      // Discard as much as we can synchronously. We only do this if we're prepared, since otherwise
      // sampleQueues may still be being modified by the loading thread.
      for (SampleQueue sampleQueue : sampleQueues) {
        sampleQueue.preRelease();
      }
    }
    loader.release(/* callback= */ this);
    handler.removeCallbacksAndMessages(null);
    callback = null;
    released = true;
  }

  @Override
  public void onLoaderReleased() {
    for (SampleQueue sampleQueue : sampleQueues) {
      sampleQueue.release();
    }
    progressiveMediaExtractor.release();
  }

  @Override
  public void prepare(Callback callback, long positionUs) {
    this.callback = callback;
    loadingStateMachine.onPrepared(/* isSingleTrack= */ singleTrackFormat != null, positionUs);
    if (singleTrackFormat != null) {
      // track() and endTracks() are meant to be called on the loading thread, which doesn't exist
      // yet (we're on the playback thread here). Starting the loading thread will provide a memory
      // barrier to ensure any changes done here are visible on the loading thread after it starts.
      TrackOutput track = track(singleTrackId, C.TRACK_TYPE_TEXT);
      track.format(singleTrackFormat);
      setSeekMap(
          new IndexSeekMap(
              /* positions= */ new long[] {0},
              /* timesUs= */ new long[] {0},
              /* durationUs= */ C.TIME_UNSET));
      endTracks();
    } else {
      loadCondition.open();
      startLoading();
    }
  }

  @Override
  public void maybeThrowPrepareError() throws IOException {
    maybeThrowError();
    if (loadingStateMachine.isFinished() && !prepared) {
      throw ParserException.createForMalformedContainer(
          "Loading finished before preparation is complete.", /* cause= */ null);
    }
  }

  @Override
  public TrackGroupArray getTrackGroups() {
    assertPrepared();
    return trackState.tracks;
  }

  @Override
  public long selectTracks(
      @NullableType ExoTrackSelection[] selections,
      boolean[] mayRetainStreamFlags,
      @NullableType SampleStream[] streams,
      boolean[] streamResetFlags,
      long positionUs) {
    assertPrepared();
    TrackGroupArray tracks = trackState.tracks;
    boolean[] trackEnabledStates = trackState.trackEnabledStates;
    int oldEnabledTrackCount = enabledTrackCount;

    boolean isT35TrackExplicitlySelected = false;
    for (int i = 0; i < selections.length; i++) {
      if (selections[i] != null) {
        int track = tracks.indexOf(selections[i].getTrackGroup());
        if (Objects.equals(
            tracks.get(track).getFormat(0).sampleMimeType, MimeTypes.APPLICATION_ITUT_T35)) {
          isT35TrackExplicitlySelected = true;
          break;
        }
      }
    }

    // Deselect old tracks.
    for (int i = 0; i < selections.length; i++) {
      if (streams[i] != null) {
        boolean shouldDeselect =
            selections[i] == null
                || !mayRetainStreamFlags[i]
                || (isT35TrackExplicitlySelected
                    && streams[i] instanceof MergingMetadataSampleStream);
        if (shouldDeselect) {
          if (streams[i] instanceof MergingMetadataSampleStream) {
            MergingMetadataSampleStream mergingStream = (MergingMetadataSampleStream) streams[i];
            mergingSampleStreams.remove(mergingStream);
            int primaryTrack = ((SampleStreamImpl) mergingStream.getPrimaryStream()).track;
            checkState(trackEnabledStates[primaryTrack]);
            enabledTrackCount--;
            trackEnabledStates[primaryTrack] = false;

            int metadataTrack = ((SampleStreamImpl) mergingStream.getMetadataStream()).track;
            checkState(trackEnabledStates[metadataTrack]);
            enabledTrackCount--;
            trackEnabledStates[metadataTrack] = false;
          } else {
            int track = ((SampleStreamImpl) streams[i]).track;
            checkState(trackEnabledStates[track]);
            enabledTrackCount--;
            trackEnabledStates[track] = false;
          }
          streams[i] = null;
        }
      }
    }
    // We'll always need to seek if this is a first selection to a non-zero position (except for
    // when we have a single sample only), or if we're making a selection having previously
    // disabled all tracks.
    boolean seekRequired =
        loadingStateMachine.hasSeenFirstTrackSelection()
            ? oldEnabledTrackCount == 0
            : positionUs != 0 && !isSingleSample;
    boolean hasPreroll = false;
    // Select new tracks.
    for (int i = 0; i < selections.length; i++) {
      if (streams[i] == null && selections[i] != null) {
        ExoTrackSelection selection = selections[i];
        checkState(selection.length() == 1);
        checkState(selection.getIndexInTrackGroup(0) == 0);
        int track = tracks.indexOf(selection.getTrackGroup());
        checkState(!trackEnabledStates[track]);
        enabledTrackCount++;
        trackEnabledStates[track] = true;
        hasPreroll |= selection.getSelectedFormat().hasPrerollSamples;
        SampleStream stream =
            new SampleStreamImpl(track, selection.getSelectedFormat().hasPrerollSamples);
        if (Build.VERSION.SDK_INT >= 37
            && !isT35TrackExplicitlySelected
            && MimeTypes.isVideo(selection.getSelectedFormat().sampleMimeType)) {
          // TODO: b/388762778 - The MP4 container has extra information (e.g. cdsc box) specifying
          // which HAGC track describes which video track. We should use this information to create
          // the correct mergingSampleStreams when multiple video/HAGC tracks exist.
          for (int j = 0; j < tracks.length; j++) {
            Format format = tracks.get(j).getFormat(0);
            if (CodecSpecificDataUtil.isHagcTrack(format)) {
              checkState(!trackEnabledStates[j]);
              enabledTrackCount++;
              trackEnabledStates[j] = true;
              // HAGC it35 metadata samples are standalone and do not depend on
              // previous samples, hence hasPreroll is not relevant and always false.
              stream =
                  new MergingMetadataSampleStream(
                      stream,
                      new SampleStreamImpl(j, /* hasPreroll= */ false),
                      selection.getSelectedFormat());
              mergingSampleStreams.add((MergingMetadataSampleStream) stream);
              break;
            }
          }
        }
        streams[i] = stream;
        streamResetFlags[i] = true;

        if (loadOnlySelectedTracks && !controlledTrackOutputs[track].isSelected()) {
          // If we reenable a track that was not previously loaded, it needs to be seeked.
          seekRequired |= loadingStateMachine.hasSeenFirstTrackSelection();
          continue;
        }

        // If there's still a chance of avoiding a seek, try and seek within the sample queue.
        if (!seekRequired) {
          SampleQueue sampleQueue = sampleQueues[track];
          // A seek can be avoided if we haven't read any samples yet (e.g. for the first track
          // selection) or we are able to seek to the current playback position in the sample queue.
          // In all other cases a seek is required.
          seekRequired =
              sampleQueue.getReadIndex() != 0
                  && !sampleQueue.seekTo(positionUs, /* allowTimeBeyondBuffer= */ true);
        }
      }
    }

    // TODO: b/474538573 - Use internal state instead of loader.isLoading() once loads are correctly
    // canceled when the end position is reached.
    loadingStateMachine.onTrackSelection(hasPreroll, enabledTrackCount, loader.isLoading());

    if (loadOnlySelectedTracks) {
      boolean[] tracksSelectedForLoading = getTracksSelectedForLoading(trackEnabledStates, tracks);
      for (int i = 0; i < controlledTrackOutputs.length; i++) {
        controlledTrackOutputs[i].updateSelectionState(tracksSelectedForLoading[i]);
      }
    }

    if (enabledTrackCount == 0) {
      if (loadingStateMachine.isCanceling()) {
        // Discard as much as we can synchronously.
        for (SampleQueue sampleQueue : sampleQueues) {
          sampleQueue.discardToEnd();
        }
        loader.cancelLoading();
      } else {
        loader.clearFatalError();
        for (SampleQueue sampleQueue : sampleQueues) {
          sampleQueue.reset();
        }
      }
    } else if (seekRequired) {
      positionUs = seekToUs(positionUs);
      // We'll need to reset renderers consuming from all streams due to the seek.
      for (int i = 0; i < streams.length; i++) {
        if (streams[i] != null) {
          streamResetFlags[i] = true;
          if (streams[i] instanceof MergingMetadataSampleStream) {
            ((MergingMetadataSampleStream) streams[i]).reset();
          }
        }
      }
    }
    return positionUs;
  }

  @Override
  public void discardBuffer(long positionUs, boolean toKeyframe) {
    if (isSingleSample) {
      // Optimize by not discarding buffers.
      return;
    }
    assertPrepared();
    if (loadingStateMachine.isPendingReset()) {
      return;
    }
    boolean[] trackEnabledStates = trackState.trackEnabledStates;
    int trackCount = sampleQueues.length;
    for (int i = 0; i < trackCount; i++) {
      sampleQueues[i].discardTo(positionUs, toKeyframe, trackEnabledStates[i]);
    }
  }

  @Override
  public void reevaluateBuffer(long positionUs) {
    loadingStateMachine.onReevaluateBuffer(
        /* hasEnabledTracks= */ enabledTrackCount > 0,
        /* haveSampleQueuesReachedEndTimeUs= */ haveSampleQueuesReachedEndTimeUs());
    // TODO: b/474538573 - Actually cancel ongoing load and restart later if required.
  }

  @Override
  public boolean continueLoading(LoadingInfo loadingInfo) {
    if (!loadingStateMachine.canContinueLoading(
        /* isPreparedOrSingleTrack= */ prepared || singleTrackFormat != null, enabledTrackCount)) {
      return false;
    }
    boolean continuedLoading = loadCondition.open();
    if (!loader.isLoading()) {
      startLoading();
      continuedLoading = true;
    }
    return continuedLoading;
  }

  @Override
  public boolean isLoading() {
    return !loadingStateMachine.isFinished() && loader.isLoading() && loadCondition.isOpen();
  }

  @Override
  public long getNextLoadPositionUs() {
    return getBufferedPositionUs();
  }

  @Override
  public void setUsesStreamPrerollFlags() {
    loadingStateMachine.setUsesStreamPrerollFlags();
  }

  @Override
  public long readDiscontinuity() {
    return loadingStateMachine.readDiscontinuity(getExtractedSamplesCount());
  }

  @Override
  public long getBufferedPositionUs() {
    assertPrepared();
    if (loadingStateMachine.isFinished() || enabledTrackCount == 0) {
      return C.TIME_END_OF_SOURCE;
    } else if (loadingStateMachine.isPendingReset()) {
      return loadingStateMachine.getPendingResetPositionUs();
    }
    long largestQueuedTimestampUs = Long.MAX_VALUE;
    if (haveAudioVideoTracks) {
      // Ignore non-AV tracks, which may be sparse or poorly interleaved.
      int trackCount = sampleQueues.length;
      for (int i = 0; i < trackCount; i++) {
        if (trackState.trackIsAudioVideoFlags[i]
            && trackState.trackEnabledStates[i]
            && !sampleQueues[i].isLastSampleQueued()) {
          largestQueuedTimestampUs =
              min(largestQueuedTimestampUs, sampleQueues[i].getLargestQueuedTimestampUs());
        }
      }
    }
    if (largestQueuedTimestampUs == Long.MAX_VALUE) {
      largestQueuedTimestampUs = getLargestQueuedTimestampUs(/* includeDisabledTracks= */ false);
    }
    return largestQueuedTimestampUs == Long.MIN_VALUE
        ? loadingStateMachine.getLastSeekPositionUs()
        : largestQueuedTimestampUs;
  }

  @Override
  public long seekToUs(long positionUs) {
    for (MergingMetadataSampleStream stream : mergingSampleStreams) {
      stream.reset();
    }
    assertPrepared();
    boolean[] trackIsAudioVideoFlags = trackState.trackIsAudioVideoFlags;
    // Treat all seeks into non-seekable media as being to t=0.
    positionUs = seekMap.isSeekable() ? positionUs : 0;

    boolean wasPendingReset = loadingStateMachine.isPendingReset();
    boolean isSameAsLastSeekPosition = loadingStateMachine.isLastSeekPosition(positionUs);
    boolean canSeekInsideBuffer =
        !wasPendingReset
            && dataType != C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE
            && (loadingStateMachine.isFinished() || loader.isLoading())
            && seekInsideBufferUs(trackIsAudioVideoFlags, positionUs, isSameAsLastSeekPosition);

    // TODO: b/474538573 - Use internal state instead of loader.isLoading() once loads are correctly
    // canceled when the end position is reached.
    loadingStateMachine.onSeek(positionUs, canSeekInsideBuffer, loader.isLoading());

    if (canSeekInsideBuffer || wasPendingReset) {
      return positionUs;
    }

    // We can't seek inside the buffer, and so need to reset.
    if (loadingStateMachine.isCanceling()) {
      // Discard as much as we can synchronously.
      for (SampleQueue sampleQueue : sampleQueues) {
        sampleQueue.discardToEnd();
      }
      loader.cancelLoading();
    } else {
      loader.clearFatalError();
      for (SampleQueue sampleQueue : sampleQueues) {
        sampleQueue.reset();
      }
    }
    return positionUs;
  }

  @Override
  public long getAdjustedSeekPositionUs(long positionUs, SeekParameters seekParameters) {
    assertPrepared();
    if (!seekMap.isSeekable()) {
      // Treat all seeks into non-seekable media as being to t=0.
      return 0;
    }
    SeekPoints seekPoints = seekMap.getSeekPoints(positionUs);
    return seekParameters.resolveSeekPositionUs(
        positionUs, seekPoints.first.timeUs, seekPoints.second.timeUs);
  }

  @Override
  public long setEndPositionUs(long endPositionUs) {
    this.endPositionUs = endPositionUs;
    for (SampleQueue sampleQueue : sampleQueues) {
      sampleQueue.setReadEndTimeUs(endPositionUs);
    }
    return endPositionUs;
  }

  // SampleStream methods.

  /* package */ boolean isReady(int track) {
    return !loadingStateMachine.suppressRead()
        && sampleQueues[track].isReady(loadingStateMachine.isFinished());
  }

  /* package */ void maybeThrowError(int sampleQueueIndex) throws IOException {
    sampleQueues[sampleQueueIndex].maybeThrowError();
    maybeThrowError();
  }

  /* package */ void maybeThrowError() throws IOException {
    loader.maybeThrowError(loadErrorHandlingPolicy.getMinimumLoadableRetryCount(dataType));
  }

  /* package */ int readData(
      int sampleQueueIndex,
      FormatHolder formatHolder,
      DecoderInputBuffer buffer,
      @ReadFlags int readFlags) {
    if (loadingStateMachine.suppressRead()) {
      return C.RESULT_NOTHING_READ;
    }
    maybeNotifyDownstreamFormat(sampleQueueIndex);
    int result =
        sampleQueues[sampleQueueIndex].read(
            formatHolder, buffer, readFlags, loadingStateMachine.isFinished());
    if (result == C.RESULT_NOTHING_READ) {
      maybeStartDeferredRetry(sampleQueueIndex);
    }
    return result;
  }

  /* package */ int skipData(int track, long positionUs) {
    if (loadingStateMachine.suppressRead()) {
      return 0;
    }
    maybeNotifyDownstreamFormat(track);
    SampleQueue sampleQueue = sampleQueues[track];
    int skipCount = sampleQueue.getSkipCount(positionUs, loadingStateMachine.isFinished());
    sampleQueue.skip(skipCount);
    if (skipCount == 0) {
      maybeStartDeferredRetry(track);
    }
    return skipCount;
  }

  private boolean haveSampleQueuesReachedEndTimeUs() {
    if (endPositionUs == C.TIME_END_OF_SOURCE) {
      return false;
    }
    assertPrepared();
    boolean endPositionReached = true;
    for (int i = 0; i < sampleQueues.length; i++) {
      // Ignore non-AV tracks, which may be sparse or poorly interleaved.
      if (trackState.trackEnabledStates[i]
          && (trackState.trackIsAudioVideoFlags[i] || !haveAudioVideoTracks)) {
        endPositionReached &= sampleQueues[i].hasQueuedTimestampsUpToReadEndTimeUs();
      }
    }
    return endPositionReached;
  }

  private void maybeNotifyDownstreamFormat(int track) {
    assertPrepared();
    boolean[] trackNotifiedDownstreamFormats = trackState.trackNotifiedDownstreamFormats;
    if (!trackNotifiedDownstreamFormats[track]) {
      Format trackFormat = trackState.tracks.get(track).getFormat(/* index= */ 0);
      mediaSourceEventDispatcher.downstreamFormatChanged(
          MimeTypes.getTrackType(trackFormat.sampleMimeType),
          trackFormat,
          C.SELECTION_REASON_UNKNOWN,
          /* trackSelectionData= */ null,
          loadingStateMachine.getLastSeekPositionUs());
      trackNotifiedDownstreamFormats[track] = true;
    }
  }

  private void maybeStartDeferredRetry(int track) {
    assertPrepared();
    if (!loadingStateMachine.isDeferredRetryPending()
        || (haveAudioVideoTracks && !trackState.trackIsAudioVideoFlags[track])
        || sampleQueues[track].isReady(/* loadingFinished= */ false)) {
      return;
    }
    loadingStateMachine.onDeferredRetryStarted();
    for (SampleQueue sampleQueue : sampleQueues) {
      sampleQueue.reset();
    }
    checkNotNull(callback).onContinueLoadingRequested(this);
  }

  // Loader.Callback implementation.

  @Override
  public void onLoadStarted(
      ExtractingLoadable loadable, long elapsedRealtimeMs, long loadDurationMs, int retryCount) {
    StatsDataSource dataSource = loadable.dataSource;
    LoadEventInfo.Builder loadEventInfo =
        new LoadEventInfo.Builder(loadable.loadTaskId, loadable.dataSpec, elapsedRealtimeMs);
    if (retryCount != 0) {
      loadEventInfo
          .setUri(dataSource.getLastOpenedUri())
          .setResponseHeaders(dataSource.getLastResponseHeaders())
          .setLoadDurationMs(loadDurationMs)
          .setBytesLoaded(dataSource.getBytesRead());
    }
    mediaSourceEventDispatcher.loadStarted(
        loadEventInfo.build(),
        C.DATA_TYPE_MEDIA,
        C.TRACK_TYPE_UNKNOWN,
        /* trackFormat= */ null,
        C.SELECTION_REASON_UNKNOWN,
        /* trackSelectionData= */ null,
        /* mediaStartTimeUs= */ loadable.seekTimeUs,
        durationUs,
        retryCount);
  }

  @Override
  public void onLoadCompleted(
      ExtractingLoadable loadable, long elapsedRealtimeMs, long loadDurationMs) {
    if (durationUs == C.TIME_UNSET && seekMap != null) {
      long largestQueuedTimestampUs =
          getLargestQueuedTimestampUs(/* includeDisabledTracks= */ true);
      durationUs =
          largestQueuedTimestampUs == Long.MIN_VALUE
              ? 0
              : largestQueuedTimestampUs + DEFAULT_LAST_SAMPLE_DURATION_US;
      listener.onSourceInfoRefreshed(durationUs, seekMap, isLive);
    }
    StatsDataSource dataSource = loadable.dataSource;
    LoadEventInfo loadEventInfo =
        new LoadEventInfo.Builder(loadable.loadTaskId, loadable.dataSpec, elapsedRealtimeMs)
            .setUri(dataSource.getLastOpenedUri())
            .setResponseHeaders(dataSource.getLastResponseHeaders())
            .setLoadDurationMs(loadDurationMs)
            .setBytesLoaded(dataSource.getBytesRead())
            .build();
    loadErrorHandlingPolicy.onLoadTaskConcluded(loadable.loadTaskId);
    mediaSourceEventDispatcher.loadCompleted(
        loadEventInfo,
        C.DATA_TYPE_MEDIA,
        C.TRACK_TYPE_UNKNOWN,
        /* trackFormat= */ null,
        C.SELECTION_REASON_UNKNOWN,
        /* trackSelectionData= */ null,
        /* mediaStartTimeUs= */ loadable.seekTimeUs,
        durationUs);
    loadingStateMachine.onLoadCompleted();
    checkNotNull(callback).onContinueLoadingRequested(this);
  }

  @Override
  public void onLoadCanceled(
      ExtractingLoadable loadable, long elapsedRealtimeMs, long loadDurationMs, boolean released) {
    StatsDataSource dataSource = loadable.dataSource;
    LoadEventInfo loadEventInfo =
        new LoadEventInfo.Builder(loadable.loadTaskId, loadable.dataSpec, elapsedRealtimeMs)
            .setUri(dataSource.getLastOpenedUri())
            .setResponseHeaders(dataSource.getLastResponseHeaders())
            .setLoadDurationMs(loadDurationMs)
            .setBytesLoaded(dataSource.getBytesRead())
            .build();
    loadErrorHandlingPolicy.onLoadTaskConcluded(loadable.loadTaskId);
    mediaSourceEventDispatcher.loadCanceled(
        loadEventInfo,
        C.DATA_TYPE_MEDIA,
        C.TRACK_TYPE_UNKNOWN,
        /* trackFormat= */ null,
        C.SELECTION_REASON_UNKNOWN,
        /* trackSelectionData= */ null,
        /* mediaStartTimeUs= */ loadable.seekTimeUs,
        durationUs);
    loadingStateMachine.onLoadCanceled(released);
    if (!released) {
      for (SampleQueue sampleQueue : sampleQueues) {
        sampleQueue.reset();
      }
      if (enabledTrackCount > 0) {
        checkNotNull(callback).onContinueLoadingRequested(this);
      }
    }
  }

  @Override
  public LoadErrorAction onLoadError(
      ExtractingLoadable loadable,
      long elapsedRealtimeMs,
      long loadDurationMs,
      IOException error,
      int errorCount) {
    StatsDataSource dataSource = loadable.dataSource;
    LoadEventInfo loadEventInfo =
        new LoadEventInfo.Builder(loadable.loadTaskId, loadable.dataSpec, elapsedRealtimeMs)
            .setUri(dataSource.getLastOpenedUri())
            .setResponseHeaders(dataSource.getLastResponseHeaders())
            .setLoadDurationMs(loadDurationMs)
            .setBytesLoaded(dataSource.getBytesRead())
            .build();
    MediaLoadData mediaLoadData =
        new MediaLoadData(
            C.DATA_TYPE_MEDIA,
            C.TRACK_TYPE_UNKNOWN,
            /* trackFormat= */ null,
            C.SELECTION_REASON_UNKNOWN,
            /* trackSelectionData= */ null,
            /* mediaStartTimeMs= */ Util.usToMs(loadable.seekTimeUs),
            Util.usToMs(durationUs));
    LoadErrorAction loadErrorAction;
    long retryDelayMs =
        loadErrorHandlingPolicy.getRetryDelayMsFor(
            new LoadErrorInfo(loadEventInfo, mediaLoadData, error, errorCount));
    if (retryDelayMs == C.TIME_UNSET) {
      loadErrorAction = Loader.DONT_RETRY_FATAL;
      loadingStateMachine.onFatalLoadError();
    } else /* the load should be retried */ {
      int extractedSamplesCount = getExtractedSamplesCount();
      boolean madeProgress =
          loadingStateMachine.hasExtractedProgressSinceLoadStart(extractedSamplesCount);
      loadErrorAction =
          configureRetry(loadable, extractedSamplesCount)
              ? Loader.createRetryAction(/* resetErrorCount= */ madeProgress, retryDelayMs)
              : Loader.DONT_RETRY;
    }

    boolean wasCanceled = !loadErrorAction.isRetry();
    mediaSourceEventDispatcher.loadError(
        loadEventInfo,
        C.DATA_TYPE_MEDIA,
        C.TRACK_TYPE_UNKNOWN,
        /* trackFormat= */ null,
        C.SELECTION_REASON_UNKNOWN,
        /* trackSelectionData= */ null,
        /* mediaStartTimeUs= */ loadable.seekTimeUs,
        durationUs,
        error,
        wasCanceled);
    if (wasCanceled) {
      loadErrorHandlingPolicy.onLoadTaskConcluded(loadable.loadTaskId);
    }
    return loadErrorAction;
  }

  // ExtractorOutput implementation. Called by the loading thread.

  @Override
  public TrackOutput track(int id, int type) {
    return prepareTrackOutput(new TrackId(id, /* isIcyTrack= */ false));
  }

  @Override
  public void endTracks() {
    sampleQueuesBuilt = true;
    handler.post(maybeFinishPrepareRunnable);
  }

  @Override
  public void seekMap(SeekMap seekMap) {
    handler.post(() -> setSeekMap(seekMap));
  }

  // Icy metadata. Called by the loading thread.

  /* package */ TrackOutput icyTrack() {
    return prepareTrackOutput(new TrackId(0, /* isIcyTrack= */ true));
  }

  // UpstreamFormatChangedListener implementation. Called by the loading thread.

  @Override
  public void onUpstreamFormatChanged(Format format) {
    handler.post(maybeFinishPrepareRunnable);
  }

  // Internal methods.

  private void onLengthKnown() {
    handler.post(() -> isLengthKnown = true);
  }

  private TrackOutput prepareTrackOutput(TrackId id) {
    int trackCount = sampleQueues.length;
    for (int i = 0; i < trackCount; i++) {
      if (id.equals(sampleQueueTrackIds[i])) {
        return sampleQueues[i];
      }
    }
    if (sampleQueuesBuilt) {
      Log.w(TAG, "Extractor added new track (id=" + id.id + ") after finishing tracks.");
      return new DiscardingTrackOutput();
    }
    SampleQueue sampleQueue =
        SampleQueue.createWithDrm(allocator, drmSessionManager, drmEventDispatcher);
    ControlledTrackOutput trackOutput = new ControlledTrackOutput(sampleQueue);
    sampleQueue.setUpstreamFormatChangeListener(this);
    @NullableType
    TrackId[] sampleQueueTrackIds = Arrays.copyOf(this.sampleQueueTrackIds, trackCount + 1);
    sampleQueueTrackIds[trackCount] = id;
    this.sampleQueueTrackIds = Util.castNonNullTypeArray(sampleQueueTrackIds);

    @NullableType SampleQueue[] sampleQueues = Arrays.copyOf(this.sampleQueues, trackCount + 1);
    sampleQueues[trackCount] = sampleQueue;
    this.sampleQueues = Util.castNonNullTypeArray(sampleQueues);

    @NullableType
    ControlledTrackOutput[] controlledTrackOutputs =
        Arrays.copyOf(this.controlledTrackOutputs, trackCount + 1);
    controlledTrackOutputs[trackCount] = trackOutput;
    this.controlledTrackOutputs = Util.castNonNullTypeArray(controlledTrackOutputs);
    return trackOutput;
  }

  private void setSeekMap(SeekMap seekMap) {
    this.seekMap = icyHeaders == null ? seekMap : new Unseekable(/* durationUs= */ C.TIME_UNSET);
    durationUs = seekMap.getDurationUs();
    isLive = !isLengthKnown && seekMap.getDurationUs() == C.TIME_UNSET;
    dataType = isLive ? C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE : C.DATA_TYPE_MEDIA;
    if (prepared) {
      listener.onSourceInfoRefreshed(durationUs, seekMap, isLive);
    } else {
      maybeFinishPrepare();
    }
  }

  private void maybeFinishPrepare() {
    if (released || prepared || !sampleQueuesBuilt || seekMap == null) {
      return;
    }
    for (SampleQueue sampleQueue : sampleQueues) {
      if (sampleQueue.getUpstreamFormat() == null) {
        return;
      }
    }
    loadCondition.close();
    int trackCount = sampleQueues.length;
    int primaryTrackIndex = 0;
    @C.TrackType int primaryTrackIndexType = C.TRACK_TYPE_UNKNOWN;
    for (int i = 0; i < trackCount; i++) {
      @C.TrackType
      int trackType =
          MimeTypes.getTrackType(checkNotNull(sampleQueues[i].getUpstreamFormat()).sampleMimeType);
      if (getTrackTypePriority(trackType) > getTrackTypePriority(primaryTrackIndexType)) {
        primaryTrackIndex = i;
        primaryTrackIndexType = trackType;
      }
    }
    TrackGroup[] trackArray = new TrackGroup[trackCount];
    boolean[] trackIsAudioVideoFlags = new boolean[trackCount];
    for (int i = 0; i < trackCount; i++) {
      Format trackFormat = checkNotNull(sampleQueues[i].getUpstreamFormat());
      @Nullable String mimeType = trackFormat.sampleMimeType;
      boolean isAudio = MimeTypes.isAudio(mimeType);
      boolean isAudioVideo = isAudio || MimeTypes.isVideo(mimeType);
      trackIsAudioVideoFlags[i] = isAudioVideo;
      haveAudioVideoTracks |= isAudioVideo;
      boolean isImage = MimeTypes.isImage(mimeType);
      isSingleSample = singleSampleDurationUs != C.TIME_UNSET && trackCount == 1 && isImage;
      @Nullable IcyHeaders icyHeaders = this.icyHeaders;
      if (icyHeaders != null) {
        if (isAudio || sampleQueueTrackIds[i].isIcyTrack) {
          @Nullable Metadata metadata = trackFormat.metadata;
          if (metadata == null) {
            metadata = new Metadata(icyHeaders);
          } else {
            metadata = metadata.copyWithAppendedEntries(icyHeaders);
          }
          trackFormat = trackFormat.buildUpon().setMetadata(metadata).build();
        }
        // Update the track format with the bitrate from the ICY header only if it declares neither
        // an average or peak bitrate of its own.
        if (isAudio
            && trackFormat.averageBitrate == Format.NO_VALUE
            && trackFormat.peakBitrate == Format.NO_VALUE
            && icyHeaders.bitrate != Format.NO_VALUE) {
          trackFormat = trackFormat.buildUpon().setAverageBitrate(icyHeaders.bitrate).build();
        }
      }
      trackFormat = trackFormat.copyWithCryptoType(drmSessionManager.getCryptoType(trackFormat));
      if (i != primaryTrackIndex) {
        trackFormat =
            trackFormat
                .buildUpon()
                .setPrimaryTrackGroupId(Integer.toString(primaryTrackIndex))
                .build();
      }
      trackArray[i] = new TrackGroup(/* id= */ Integer.toString(i), trackFormat);
      sampleQueues[i].setReadEndTimeUs(endPositionUs);
    }
    trackState = new TrackState(new TrackGroupArray(trackArray), trackIsAudioVideoFlags);
    if (isSingleSample && durationUs == C.TIME_UNSET) {
      durationUs = singleSampleDurationUs;
      seekMap =
          new ForwardingSeekMap(seekMap) {
            @Override
            public long getDurationUs() {
              return durationUs;
            }
          };
    }
    listener.onSourceInfoRefreshed(durationUs, seekMap, isLive);
    prepared = true;
    checkNotNull(callback).onPrepared(this);
  }

  private void startLoading() {
    ExtractingLoadable loadable =
        new ExtractingLoadable(
            uri, dataSource, progressiveMediaExtractor, /* extractorOutput= */ this, loadCondition);
    if (prepared) {
      checkState(loadingStateMachine.isPendingReset());
      long pendingResetPositionUs = loadingStateMachine.getPendingResetPositionUs();
      long maxLoadPositionUs = endPositionUs != C.TIME_END_OF_SOURCE ? endPositionUs : durationUs;
      if (maxLoadPositionUs != C.TIME_UNSET && pendingResetPositionUs > maxLoadPositionUs) {
        loadingStateMachine.onLoadCompleted();
        return;
      }
      loadable.setLoadPosition(
          checkNotNull(seekMap).getSeekPoints(pendingResetPositionUs).first.position,
          pendingResetPositionUs);
      for (SampleQueue sampleQueue : sampleQueues) {
        sampleQueue.setStartTimeUs(pendingResetPositionUs);
      }
    }
    loadingStateMachine.onStartLoading(getExtractedSamplesCount());
    loader.startLoading(
        loadable, this, loadErrorHandlingPolicy.getMinimumLoadableRetryCount(dataType));
  }

  /**
   * Called to configure a retry when a load error occurs.
   *
   * @param loadable The current loadable for which the error was encountered.
   * @param currentExtractedSamplesCount The current number of samples that have been extracted into
   *     the sample queues.
   * @return Whether the loader should retry with the current loadable. False indicates a deferred
   *     retry.
   */
  private boolean configureRetry(ExtractingLoadable loadable, int currentExtractedSamplesCount) {
    boolean isLengthKnownOrHasDuration =
        isLengthKnown || (seekMap != null && seekMap.getDurationUs() != C.TIME_UNSET);
    loadingStateMachine.onLoadError(
        isLengthKnownOrHasDuration, prepared, currentExtractedSamplesCount);
    boolean retry = !loadingStateMachine.isDeferredRetryPending();
    if (retry && !isLengthKnownOrHasDuration) {
      for (SampleQueue sampleQueue : sampleQueues) {
        sampleQueue.reset();
      }
      loadable.setLoadPosition(0, 0);
    }
    return retry;
  }

  /**
   * Attempts to seek to the specified position within the sample queues.
   *
   * @param trackIsAudioVideoFlags Whether each track is audio/video.
   * @param positionUs The seek position in microseconds.
   * @param isSameAsLastSeekPosition Whether new seek position is same as that last called with
   *     {@link #seekToUs}.
   * @return Whether the in-buffer seek was successful.
   */
  private boolean seekInsideBufferUs(
      boolean[] trackIsAudioVideoFlags, long positionUs, boolean isSameAsLastSeekPosition) {
    int trackCount = sampleQueues.length;
    for (int i = 0; i < trackCount; i++) {
      SampleQueue sampleQueue = sampleQueues[i];
      // For the tracks that do not get buffered, we don't need to seek.
      if (!controlledTrackOutputs[i].isSelected()) {
        continue;
      }

      if (sampleQueue.getReadIndex() == 0 && isSameAsLastSeekPosition) {
        continue;
      }
      boolean seekInsideQueue =
          isSingleSample
              ? sampleQueue.seekTo(sampleQueue.getFirstIndex())
              : sampleQueue.seekTo(
                  positionUs, /* allowTimeBeyondBuffer= */ loadingStateMachine.isFinished());
      // If we have AV tracks then an in-buffer seek is successful if the seek into every AV queue
      // is successful. We ignore whether seeks within non-AV queues are successful in this case, as
      // they may be sparse or poorly interleaved. If we only have non-AV tracks then a seek is
      // successful only if the seek into every queue succeeds.
      if (!seekInsideQueue && (trackIsAudioVideoFlags[i] || !haveAudioVideoTracks)) {
        return false;
      }
    }
    return true;
  }

  private int getExtractedSamplesCount() {
    int extractedSamplesCount = 0;
    for (SampleQueue sampleQueue : sampleQueues) {
      extractedSamplesCount += sampleQueue.getWriteIndex();
    }
    return extractedSamplesCount;
  }

  private long getLargestQueuedTimestampUs(boolean includeDisabledTracks) {
    long largestQueuedTimestampUs = Long.MIN_VALUE;
    for (int i = 0; i < sampleQueues.length; i++) {
      if (includeDisabledTracks || checkNotNull(trackState).trackEnabledStates[i]) {
        largestQueuedTimestampUs =
            max(largestQueuedTimestampUs, sampleQueues[i].getLargestQueuedTimestampUs());
      }
    }
    return largestQueuedTimestampUs;
  }

  @EnsuresNonNull({"trackState", "seekMap"})
  private void assertPrepared() {
    checkState(prepared);
    checkNotNull(trackState);
    checkNotNull(seekMap);
  }

  private static boolean[] getTracksSelectedForLoading(
      boolean[] trackEnabledStates, TrackGroupArray tracks) {
    boolean[] selectedForLoading = new boolean[trackEnabledStates.length];
    for (int i = 0; i < selectedForLoading.length; i++) {
      int type = tracks.get(i).type;
      selectedForLoading[i] =
          (type != C.TRACK_TYPE_VIDEO && type != C.TRACK_TYPE_IMAGE) || trackEnabledStates[i];
    }
    return selectedForLoading;
  }

  private final class SampleStreamImpl implements SampleStream {

    private final int track;
    private final boolean hasPreroll;

    private SampleStreamImpl(int track, boolean hasPreroll) {
      this.track = track;
      this.hasPreroll = hasPreroll;
    }

    @Override
    public boolean isReady() {
      return ProgressiveMediaPeriod.this.isReady(track);
    }

    @Override
    public void maybeThrowError() throws IOException {
      ProgressiveMediaPeriod.this.maybeThrowError(track);
    }

    @Override
    public int readData(
        FormatHolder formatHolder, DecoderInputBuffer buffer, @ReadFlags int readFlags) {
      return ProgressiveMediaPeriod.this.readData(track, formatHolder, buffer, readFlags);
    }

    @Override
    public int skipData(long positionUs) {
      return ProgressiveMediaPeriod.this.skipData(track, positionUs);
    }

    @Override
    public @Flags int getFlags() {
      return hasPreroll ? FLAG_HAS_PREROLL : 0;
    }
  }

  /** Loads the media stream and extracts sample data from it. */
  /* package */ final class ExtractingLoadable implements Loadable, IcyDataSource.Listener {

    private final long loadTaskId;
    private final Uri uri;
    private final StatsDataSource dataSource;
    private final ProgressiveMediaExtractor progressiveMediaExtractor;
    private final ExtractorOutput extractorOutput;
    private final ConditionVariable loadCondition;
    private final PositionHolder positionHolder;

    private volatile boolean loadCanceled;

    private boolean pendingExtractorSeek;
    private long seekTimeUs;
    private DataSpec dataSpec;
    @Nullable private TrackOutput icyTrackOutput;
    private boolean seenIcyMetadata;

    @SuppressWarnings("nullness:method.invocation")
    public ExtractingLoadable(
        Uri uri,
        DataSource dataSource,
        ProgressiveMediaExtractor progressiveMediaExtractor,
        ExtractorOutput extractorOutput,
        ConditionVariable loadCondition) {
      this.uri = uri;
      this.dataSource = new StatsDataSource(dataSource);
      this.progressiveMediaExtractor = progressiveMediaExtractor;
      this.extractorOutput = extractorOutput;
      this.loadCondition = loadCondition;
      this.positionHolder = new PositionHolder();
      this.pendingExtractorSeek = true;
      loadTaskId = LoadEventInfo.getNewId();
      dataSpec = buildDataSpec(/* position= */ 0, /* etag= */ null);
    }

    // Loadable implementation.

    @Override
    public void cancelLoad() {
      loadCanceled = true;
    }

    @Override
    public void load() throws IOException {
      int result = Extractor.RESULT_CONTINUE;
      String etag = null;
      while (result == Extractor.RESULT_CONTINUE && !loadCanceled) {
        try {
          long position = positionHolder.position;
          dataSpec = buildDataSpec(position, etag);
          long length = dataSource.open(dataSpec);
          if (loadCanceled) {
            break;
          }
          @Nullable List<String> etags = dataSource.getResponseHeaders().get(HttpHeaders.ETAG);
          etag = etags != null && !etags.isEmpty() ? etags.get(0) : null;
          if (length != C.LENGTH_UNSET) {
            length += position;
            onLengthKnown();
          }
          icyHeaders = IcyHeaders.parse(dataSource.getResponseHeaders());
          DataSource extractorDataSource = dataSource;
          if (icyHeaders != null && icyHeaders.metadataInterval != C.LENGTH_UNSET) {
            extractorDataSource = new IcyDataSource(dataSource, icyHeaders.metadataInterval, this);
            icyTrackOutput = icyTrack();
            icyTrackOutput.format(ICY_FORMAT);
          }
          progressiveMediaExtractor.init(
              extractorDataSource,
              uri,
              dataSource.getResponseHeaders(),
              position,
              length,
              extractorOutput);

          if (icyHeaders != null) {
            progressiveMediaExtractor.disableSeekingOnMp3Streams();
          }

          if (pendingExtractorSeek) {
            progressiveMediaExtractor.seek(position, seekTimeUs);
            pendingExtractorSeek = false;
          }
          while (result == Extractor.RESULT_CONTINUE && !loadCanceled) {
            try {
              loadCondition.block();
            } catch (InterruptedException e) {
              throw new InterruptedIOException();
            }
            result = progressiveMediaExtractor.read(positionHolder);
            long currentInputPosition = progressiveMediaExtractor.getCurrentInputPosition();
            if (currentInputPosition > position + continueLoadingCheckIntervalBytes) {
              position = currentInputPosition;
              loadCondition.close();
              handler.post(onContinueLoadingRequestedRunnable);
            }
          }
        } finally {
          if (result == Extractor.RESULT_SEEK) {
            result = Extractor.RESULT_CONTINUE;
          } else if (progressiveMediaExtractor.getCurrentInputPosition() != C.INDEX_UNSET) {
            positionHolder.position = progressiveMediaExtractor.getCurrentInputPosition();
          }
          DataSourceUtil.closeQuietly(dataSource);
        }
      }
    }

    // IcyDataSource.Listener

    @Override
    public void onIcyMetadata(ParsableByteArray metadata) {
      // Always output the first ICY metadata at the start time. This helps minimize any delay
      // between the start of playback and the first ICY metadata event.
      long timeUs =
          !seenIcyMetadata
              ? seekTimeUs
              : max(getLargestQueuedTimestampUs(/* includeDisabledTracks= */ true), seekTimeUs);
      int length = metadata.bytesLeft();
      TrackOutput icyTrackOutput = checkNotNull(this.icyTrackOutput);
      icyTrackOutput.sampleData(metadata, length);
      icyTrackOutput.sampleMetadata(
          timeUs, C.BUFFER_FLAG_KEY_FRAME, length, /* offset= */ 0, /* cryptoData= */ null);
      seenIcyMetadata = true;
    }

    // Internal methods.

    private DataSpec buildDataSpec(long position, @Nullable String etag) {
      Map<String, String> requestHeaders = ICY_METADATA_HEADERS;
      if (etag != null && !etag.startsWith("W/")) {
        requestHeaders =
            ImmutableMap.<String, String>builder()
                .putAll(requestHeaders)
                .put(HttpHeaders.IF_RANGE, etag)
                .buildKeepingLast();
      }
      return new DataSpec.Builder()
          .setUri(uri)
          .setPosition(position)
          .setKey(customCacheKey)
          // Disable caching if the content length cannot be resolved, since this is indicative of a
          // progressive live stream.
          .setFlags(
              DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN | DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION)
          .setHttpRequestHeaders(requestHeaders)
          .build();
    }

    private void setLoadPosition(long position, long timeUs) {
      positionHolder.position = position;
      seekTimeUs = timeUs;
      pendingExtractorSeek = true;
      seenIcyMetadata = false;
    }
  }

  /** Stores track state. */
  private static final class TrackState {

    public final TrackGroupArray tracks;
    public final boolean[] trackIsAudioVideoFlags;
    public final boolean[] trackEnabledStates;
    public final boolean[] trackNotifiedDownstreamFormats;

    public TrackState(TrackGroupArray tracks, boolean[] trackIsAudioVideoFlags) {
      this.tracks = tracks;
      this.trackIsAudioVideoFlags = trackIsAudioVideoFlags;
      this.trackEnabledStates = new boolean[tracks.length];
      this.trackNotifiedDownstreamFormats = new boolean[tracks.length];
    }
  }

  /** Identifies a track. */
  private static final class TrackId {

    public final int id;
    public final boolean isIcyTrack;

    public TrackId(int id, boolean isIcyTrack) {
      this.id = id;
      this.isIcyTrack = isIcyTrack;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      if (this == obj) {
        return true;
      }
      if (obj == null || getClass() != obj.getClass()) {
        return false;
      }
      TrackId other = (TrackId) obj;
      return id == other.id && isIcyTrack == other.isIcyTrack;
    }

    @Override
    public int hashCode() {
      return 31 * id + (isIcyTrack ? 1 : 0);
    }
  }

  private static Map<String, String> createIcyMetadataHeaders() {
    Map<String, String> headers = new HashMap<>();
    headers.put(
        IcyHeaders.REQUEST_HEADER_ENABLE_METADATA_NAME,
        IcyHeaders.REQUEST_HEADER_ENABLE_METADATA_VALUE);
    return Collections.unmodifiableMap(headers);
  }

  private static int getTrackTypePriority(@C.TrackType int trackType) {
    switch (trackType) {
      case C.TRACK_TYPE_VIDEO:
        return 4;
      case C.TRACK_TYPE_AUDIO:
        return 3;
      case C.TRACK_TYPE_IMAGE:
        return 2;
      case C.TRACK_TYPE_TEXT:
        return 1;
      default:
        return 0;
    }
  }

  /** A track output that can discard samples when not selected. */
  private static class ControlledTrackOutput extends ForwardingTrackOutput {
    private final SampleQueue sampleQueue;
    private final DiscardingTrackOutput discardingTrackOutput;
    private final AtomicReference<OutputMode> outputMode;

    /** The mode of operation for the controlled track output. */
    static enum OutputMode {
      /** Pass samples through to the downstream track output. */
      PASS_THROUGH,
      /**
       * Pass samples through to the downstream track output, but discard after the next sample
       * metadata is received.
       */
      DISCARD_AFTER_NEXT_SAMPLE_METADATA,
      /** Discards all samples. */
      DISCARDING,
    };

    ControlledTrackOutput(SampleQueue sampleQueue) {
      super(sampleQueue);
      this.sampleQueue = sampleQueue;
      this.discardingTrackOutput = new DiscardingTrackOutput();
      this.outputMode = new AtomicReference<>(OutputMode.PASS_THROUGH);
    }

    @Override
    public int sampleData(DataReader input, int length, boolean allowEndOfInput)
        throws IOException {
      return getCurrentOutput().sampleData(input, length, allowEndOfInput);
    }

    @Override
    public int sampleData(
        DataReader input, int length, boolean allowEndOfInput, @SampleDataPart int sampleDataPart)
        throws IOException {
      return getCurrentOutput().sampleData(input, length, allowEndOfInput, sampleDataPart);
    }

    @Override
    public void sampleData(ParsableByteArray data, int length) {
      getCurrentOutput().sampleData(data, length);
    }

    @Override
    public void sampleData(ParsableByteArray data, int length, @SampleDataPart int sampleDataPart) {
      getCurrentOutput().sampleData(data, length, sampleDataPart);
    }

    @Override
    public void sampleMetadata(
        long timeUs,
        @C.BufferFlags int flags,
        int size,
        int offset,
        @Nullable CryptoData cryptoData) {
      getCurrentOutput().sampleMetadata(timeUs, flags, size, offset, cryptoData);
      if (outputMode.get() == OutputMode.DISCARD_AFTER_NEXT_SAMPLE_METADATA) {
        sampleQueue.reset();
        outputMode.set(OutputMode.DISCARDING);
      }
    }

    /**
     * Updates the selection state of the track.
     *
     * @param selected Whether the track is selected.
     */
    void updateSelectionState(boolean selected) {
      // Since there could still be some samples within the internal SampleDataQueue, we will be
      // confident about releasing them all after the next sample metadata is received.
      outputMode.set(
          selected ? OutputMode.PASS_THROUGH : OutputMode.DISCARD_AFTER_NEXT_SAMPLE_METADATA);
      // In case the existing samples are taking too much memory, preventing further load, release
      // them optimistically.
      if (!selected) {
        sampleQueue.discardToEnd();
      }
    }

    /** Returns whether the track is selected. */
    boolean isSelected() {
      return outputMode.get() == OutputMode.PASS_THROUGH;
    }

    private TrackOutput getCurrentOutput() {
      return outputMode.get() == OutputMode.DISCARDING ? discardingTrackOutput : sampleQueue;
    }
  }

  /** State machine managing the loading state of a {@link ProgressiveMediaPeriod}. */
  @VisibleForTesting
  /* package */ static final class LoadingStateMachine {

    /** State of the loading state machine. */
    @Documented
    @Target(ElementType.TYPE_USE)
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
      STATE_IDLE,
      STATE_LOADING,
      STATE_CANCELING,
      STATE_FINISHED,
      STATE_DEFERRED_RETRY_PENDING,
      STATE_ERROR,
    })
    public @interface State {}

    /**
     * The loader is idle (not currently extracting data), and no load error is pending deferred
     * retry.
     */
    public static final int STATE_IDLE = 1;

    /** An {@link ExtractingLoadable} is currently being loaded by the {@link Loader}. */
    public static final int STATE_LOADING = 2;

    /**
     * A load is being canceled (e.g. due to an out-of-buffer seek or track deselection). Once the
     * load is canceled, state will return to {@link #STATE_IDLE}.
     */
    public static final int STATE_CANCELING = 3;

    /** Loading has finished because end-of-file (EOF) or the duration/end position was reached. */
    public static final int STATE_FINISHED = 4;

    /**
     * A load error occurred on a live / unknown-length stream while buffered data remains.
     * Re-loading is deferred until the buffered data is consumed.
     */
    public static final int STATE_DEFERRED_RETRY_PENDING = 5;

    /** A fatal load error occurred that cannot be retried. */
    public static final int STATE_ERROR = 6;

    private @State int state;

    /**
     * The last seek position in microseconds, or 0 if reset by a live-stream or deferred retry.
     * Returned as the discontinuity position by {@link #readDiscontinuity(int)}.
     */
    private long lastSeekPositionUs;

    private long pendingResetPositionUs;
    private int extractedSamplesCountAtStartOfLoad;
    private boolean notifyDiscontinuity;
    private boolean pendingInitialDiscontinuity;
    private boolean seenFirstTrackSelection;
    private boolean usesStreamPrerollFlags;

    /** Creates an instance. */
    public LoadingStateMachine() {
      state = STATE_IDLE;
      pendingResetPositionUs = C.TIME_UNSET;
    }

    /** Returns the current {@link State}. */
    @VisibleForTesting
    public @State int getState() {
      return state;
    }

    /** Returns whether loading has finished (e.g. EOF or end position reached). */
    public boolean isFinished() {
      return state == STATE_FINISHED;
    }

    /** Returns whether a load error is pending a deferred retry. */
    public boolean isDeferredRetryPending() {
      return state == STATE_DEFERRED_RETRY_PENDING;
    }

    /** Returns whether the given seek position matches the last requested seek position. */
    public boolean isLastSeekPosition(long positionUs) {
      return lastSeekPositionUs == positionUs;
    }

    /**
     * Returns whether progress (more extracted samples) has been made since the current load task
     * started.
     *
     * @param currentExtractedSamplesCount Current total sample count across all queues.
     */
    public boolean hasExtractedProgressSinceLoadStart(int currentExtractedSamplesCount) {
      return currentExtractedSamplesCount > extractedSamplesCountAtStartOfLoad;
    }

    /**
     * Returns whether a load reset (to {@link #getPendingResetPositionUs()}) is currently pending.
     *
     * <p>A reset is only pending while in {@link #STATE_IDLE} or {@link #STATE_CANCELING}, awaiting
     * a new load task to start.
     */
    public boolean isPendingReset() {
      return pendingResetPositionUs != C.TIME_UNSET;
    }

    /** Returns the pending reset position in microseconds, or {@link C#TIME_UNSET} if none. */
    public long getPendingResetPositionUs() {
      return pendingResetPositionUs;
    }

    /**
     * Returns the last seek position in microseconds (or {@code 0} if reset by a live-stream or
     * deferred retry).
     */
    public long getLastSeekPositionUs() {
      return lastSeekPositionUs;
    }

    /** Returns the number of extracted samples recorded when the current load started. */
    public int getExtractedSamplesCountAtStartOfLoad() {
      return extractedSamplesCountAtStartOfLoad;
    }

    /** Configures whether sample streams manage their own preroll flags. */
    public void setUsesStreamPrerollFlags() {
      this.usesStreamPrerollFlags = true;
    }

    /** Returns whether track selection has occurred at least once for this period. */
    public boolean hasSeenFirstTrackSelection() {
      return seenFirstTrackSelection;
    }

    /**
     * Returns whether sample reading should be suppressed due to pending reset or discontinuity.
     */
    public boolean suppressRead() {
      return notifyDiscontinuity || isPendingReset();
    }

    /** Returns whether an active load is currently being canceled. */
    public boolean isCanceling() {
      return state == STATE_CANCELING;
    }

    /** Returns whether loading can be started or continued by the caller. */
    public boolean canContinueLoading(boolean isPreparedOrSingleTrack, int enabledTrackCount) {
      return (state == STATE_IDLE || state == STATE_LOADING)
          && (!isPreparedOrSingleTrack || enabledTrackCount > 0);
    }

    /** Evaluates and returns a pending discontinuity position, or {@link C#TIME_UNSET} if none. */
    public long readDiscontinuity(int currentExtractedSamplesCount) {
      if (!usesStreamPrerollFlags && pendingInitialDiscontinuity) {
        pendingInitialDiscontinuity = false;
        return lastSeekPositionUs;
      }
      if (notifyDiscontinuity
          && (state == STATE_FINISHED
              || currentExtractedSamplesCount > extractedSamplesCountAtStartOfLoad)) {
        notifyDiscontinuity = false;
        return lastSeekPositionUs;
      }
      return C.TIME_UNSET;
    }

    // --- State Transitions & Events ---

    /**
     * Called when track selections are updated.
     *
     * @param hasPreroll Whether any selected track has preroll samples.
     * @param enabledTrackCount Number of currently enabled tracks.
     */
    public void onTrackSelection(
        boolean hasPreroll, int enabledTrackCount, boolean loaderIsLoading) {
      if (enabledTrackCount == 0) {
        notifyDiscontinuity = false;
        pendingInitialDiscontinuity = false;
        if (loaderIsLoading) {
          state = STATE_CANCELING;
        } else {
          state = STATE_IDLE;
        }
      } else if (pendingInitialDiscontinuity || !seenFirstTrackSelection) {
        pendingInitialDiscontinuity = hasPreroll;
      }
      seenFirstTrackSelection = true;
    }

    /** Configures initial reset position upon single-track period preparation. */
    public void onPrepared(boolean isSingleTrack, long positionUs) {
      if (isSingleTrack) {
        pendingResetPositionUs = positionUs;
      }
    }

    /**
     * Called when a load is started by the {@link Loader}.
     *
     * @param currentExtractedSamplesCount Current total sample count across all queues.
     */
    public void onStartLoading(int currentExtractedSamplesCount) {
      extractedSamplesCountAtStartOfLoad = currentExtractedSamplesCount;
      pendingResetPositionUs = C.TIME_UNSET;
      state = STATE_LOADING;
    }

    /** Called when a load completes naturally (e.g. reaches EOF). */
    public void onLoadCompleted() {
      pendingResetPositionUs = C.TIME_UNSET;
      state = STATE_FINISHED;
    }

    /** Called when an active load is canceled by the {@link Loader}. */
    public void onLoadCanceled(boolean released) {
      if (!released && (state == STATE_CANCELING || state == STATE_LOADING)) {
        state = STATE_IDLE;
      }
    }

    /** Called when a fatal load error occurs and loading will not be retried. */
    public void onFatalLoadError() {
      state = STATE_ERROR;
    }

    /** Evaluates state transition upon load error. */
    public void onLoadError(
        boolean isLengthKnownOrHasDuration, boolean isPrepared, int currentExtractedSamplesCount) {
      if (isLengthKnownOrHasDuration) {
        // We're playing an on-demand stream. Resume the current loadable, which will
        // request data starting from the point it left off.
        extractedSamplesCountAtStartOfLoad = currentExtractedSamplesCount;
        state = STATE_LOADING;
      } else if (isPrepared && !suppressRead()) {
        // We're playing a stream of unknown length and duration. Assume it's live, and therefore
        // that the chance of the loadable managing to recover when retried immediately is low.
        // Defer retrying until the buffer has been depleted.
        state = STATE_DEFERRED_RETRY_PENDING;
      } else {
        // We're playing a stream of unknown length and duration, and the buffer is empty.
        // Assume it's live, and that starting a new loadable (which will start from the
        // beginning of the live stream) is the best option.
        notifyDiscontinuity = isPrepared;
        lastSeekPositionUs = 0;
        extractedSamplesCountAtStartOfLoad = 0;
        state = STATE_LOADING;
      }
    }

    /** Called when a deferred retry is initiated upon buffer exhaustion. */
    public void onDeferredRetryStarted() {
      pendingResetPositionUs = 0;
      notifyDiscontinuity = true;
      lastSeekPositionUs = 0;
      extractedSamplesCountAtStartOfLoad = 0;
      state = STATE_IDLE;
    }

    /**
     * Called when a seek is requested.
     *
     * @param positionUs The seek target position in microseconds.
     * @param canSeekInsideBuffer Whether in-buffer seek succeeded on sample queues.
     * @param loaderIsLoading Whether the loader is actively running a load task.
     */
    public void onSeek(long positionUs, boolean canSeekInsideBuffer, boolean loaderIsLoading) {
      notifyDiscontinuity = false;
      lastSeekPositionUs = positionUs;

      if (isPendingReset()) {
        pendingResetPositionUs = positionUs;
        return;
      }

      if (canSeekInsideBuffer) {
        return;
      }

      pendingResetPositionUs = positionUs;
      pendingInitialDiscontinuity = false;
      if (loaderIsLoading) {
        state = STATE_CANCELING;
      } else {
        state = STATE_IDLE;
      }
    }

    /** Evaluates state transition during buffer re-evaluation. */
    public void onReevaluateBuffer(
        boolean hasEnabledTracks, boolean haveSampleQueuesReachedEndTimeUs) {
      if (hasEnabledTracks && !isPendingReset() && haveSampleQueuesReachedEndTimeUs) {
        state = STATE_FINISHED;
      }
    }
  }
}
