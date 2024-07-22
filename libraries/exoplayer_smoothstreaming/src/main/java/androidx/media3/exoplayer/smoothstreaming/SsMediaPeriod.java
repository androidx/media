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
package androidx.media3.exoplayer.smoothstreaming;

import static androidx.media3.common.util.Assertions.checkNotNull;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.StreamKey;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.util.NullableType;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.LoadingInfo;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.drm.DrmSessionEventListener;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.smoothstreaming.manifest.SsManifest;
import androidx.media3.exoplayer.source.CompositeSequenceableLoaderFactory;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.MediaSourceEventListener;
import androidx.media3.exoplayer.source.SampleStream;
import androidx.media3.exoplayer.source.SequenceableLoader;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.source.chunk.ChunkSampleStream;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.CmcdConfiguration;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.LoaderErrorThrower;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** A SmoothStreaming {@link MediaPeriod}. */
/* package */ final class SsMediaPeriod
    implements MediaPeriod, SequenceableLoader.Callback<ChunkSampleStream<SsChunkSource>> {

  private final SsChunkSource.Factory chunkSourceFactory;
  @Nullable private final TransferListener transferListener;
  private final LoaderErrorThrower manifestLoaderErrorThrower;
  private final DrmSessionManager drmSessionManager;
  @Nullable private final CmcdConfiguration cmcdConfiguration;
  private final DrmSessionEventListener.EventDispatcher drmEventDispatcher;
  private final LoadErrorHandlingPolicy loadErrorHandlingPolicy;
  private final MediaSourceEventListener.EventDispatcher mediaSourceEventDispatcher;
  private final Allocator allocator;
  private final TrackGroupArray trackGroups;
  private final CompositeSequenceableLoaderFactory compositeSequenceableLoaderFactory;

  @Nullable private Callback callback;
  private SsManifest manifest;
  private ChunkSampleStream<SsChunkSource>[] sampleStreams;
  private SequenceableLoader compositeSequenceableLoader;

  public SsMediaPeriod(
      SsManifest manifest,
      SsChunkSource.Factory chunkSourceFactory,
      @Nullable TransferListener transferListener,
      CompositeSequenceableLoaderFactory compositeSequenceableLoaderFactory,
      @Nullable CmcdConfiguration cmcdConfiguration,
      DrmSessionManager drmSessionManager,
      DrmSessionEventListener.EventDispatcher drmEventDispatcher,
      LoadErrorHandlingPolicy loadErrorHandlingPolicy,
      MediaSourceEventListener.EventDispatcher mediaSourceEventDispatcher,
      LoaderErrorThrower manifestLoaderErrorThrower,
      Allocator allocator) {
    this.manifest = manifest;
    this.chunkSourceFactory = chunkSourceFactory;
    this.transferListener = transferListener;
    this.manifestLoaderErrorThrower = manifestLoaderErrorThrower;
    this.cmcdConfiguration = cmcdConfiguration;
    this.drmSessionManager = drmSessionManager;
    this.drmEventDispatcher = drmEventDispatcher;
    this.loadErrorHandlingPolicy = loadErrorHandlingPolicy;
    this.mediaSourceEventDispatcher = mediaSourceEventDispatcher;
    this.allocator = allocator;
    this.compositeSequenceableLoaderFactory = compositeSequenceableLoaderFactory;
    trackGroups = buildTrackGroups(manifest, drmSessionManager, chunkSourceFactory);
    sampleStreams = newSampleStreamArray(0);
    compositeSequenceableLoader = compositeSequenceableLoaderFactory.empty();
  }

  public void updateManifest(SsManifest manifest) {
    this.manifest = manifest;
    for (ChunkSampleStream<SsChunkSource> sampleStream : sampleStreams) {
      sampleStream.getChunkSource().updateManifest(manifest);
    }
    checkNotNull(callback).onContinueLoadingRequested(this);
  }

  public void release() {
    for (ChunkSampleStream<SsChunkSource> sampleStream : sampleStreams) {
      sampleStream.release();
    }
    callback = null;
  }

  // MediaPeriod implementation.

  @Override
  public void prepare(Callback callback, long positionUs) {
    this.callback = callback;
    callback.onPrepared(this);
  }

  @Override
  public void maybeThrowPrepareError() throws IOException {
    manifestLoaderErrorThrower.maybeThrowError();
  }

  @Override
  public TrackGroupArray getTrackGroups() {
    return trackGroups;
  }

  @Override
  public long selectTracks(
      @NullableType ExoTrackSelection[] selections,
      boolean[] mayRetainStreamFlags,
      @NullableType SampleStream[] streams,
      boolean[] streamResetFlags,
      long positionUs) {
    ArrayList<ChunkSampleStream<SsChunkSource>> sampleStreamsList = new ArrayList<>();
    for (int i = 0; i < selections.length; i++) {
      if (streams[i] != null) {
        @SuppressWarnings("unchecked")
        ChunkSampleStream<SsChunkSource> stream = (ChunkSampleStream<SsChunkSource>) streams[i];
        if (selections[i] == null || !mayRetainStreamFlags[i]) {
          stream.release();
          streams[i] = null;
        } else {
          stream.getChunkSource().updateTrackSelection(checkNotNull(selections[i]));
          sampleStreamsList.add(stream);
        }
      }
      if (streams[i] == null && selections[i] != null) {
        ChunkSampleStream<SsChunkSource> stream = buildSampleStream(selections[i], positionUs);
        sampleStreamsList.add(stream);
        streams[i] = stream;
        streamResetFlags[i] = true;
      }
    }
    sampleStreams = newSampleStreamArray(sampleStreamsList.size());
    sampleStreamsList.toArray(sampleStreams);
    compositeSequenceableLoader =
        compositeSequenceableLoaderFactory.create(
            sampleStreamsList,
            Lists.transform(sampleStreamsList, s -> ImmutableList.of(s.primaryTrackType)));
    return positionUs;
  }

  @Override
  public List<StreamKey> getStreamKeys(List<ExoTrackSelection> trackSelections) {
    List<StreamKey> streamKeys = new ArrayList<>();
    for (int selectionIndex = 0; selectionIndex < trackSelections.size(); selectionIndex++) {
      ExoTrackSelection trackSelection = trackSelections.get(selectionIndex);
      int streamElementIndex = trackGroups.indexOf(trackSelection.getTrackGroup());
      for (int i = 0; i < trackSelection.length(); i++) {
        streamKeys.add(new StreamKey(streamElementIndex, trackSelection.getIndexInTrackGroup(i)));
      }
    }
    return streamKeys;
  }

  @Override
  public void discardBuffer(long positionUs, boolean toKeyframe) {
    for (ChunkSampleStream<SsChunkSource> sampleStream : sampleStreams) {
      sampleStream.discardBuffer(positionUs, toKeyframe);
    }
  }

  @Override
  public void reevaluateBuffer(long positionUs) {
    compositeSequenceableLoader.reevaluateBuffer(positionUs);
  }

  @Override
  public boolean continueLoading(LoadingInfo loadingInfo) {
    return compositeSequenceableLoader.continueLoading(loadingInfo);
  }

  @Override
  public boolean isLoading() {
    return compositeSequenceableLoader.isLoading();
  }

  @Override
  public long getNextLoadPositionUs() {
    return compositeSequenceableLoader.getNextLoadPositionUs();
  }

  @Override
  public long readDiscontinuity() {
    return C.TIME_UNSET;
  }

  @Override
  public long getBufferedPositionUs() {
    return compositeSequenceableLoader.getBufferedPositionUs();
  }

  @Override
  public long seekToUs(long positionUs) {
    for (ChunkSampleStream<SsChunkSource> sampleStream : sampleStreams) {
      sampleStream.seekToUs(positionUs);
    }
    return positionUs;
  }

  @Override
  public long getAdjustedSeekPositionUs(long positionUs, SeekParameters seekParameters) {
    for (ChunkSampleStream<SsChunkSource> sampleStream : sampleStreams) {
      if (sampleStream.primaryTrackType == C.TRACK_TYPE_VIDEO) {
        return sampleStream.getAdjustedSeekPositionUs(positionUs, seekParameters);
      }
    }
    return positionUs;
  }

  // SequenceableLoader.Callback implementation.

  @Override
  public void onContinueLoadingRequested(ChunkSampleStream<SsChunkSource> sampleStream) {
    checkNotNull(callback).onContinueLoadingRequested(this);
  }

  // Private methods.

  private ChunkSampleStream<SsChunkSource> buildSampleStream(
      ExoTrackSelection selection, long positionUs) {
    int streamElementIndex = trackGroups.indexOf(selection.getTrackGroup());
    SsChunkSource chunkSource =
        chunkSourceFactory.createChunkSource(
            manifestLoaderErrorThrower,
            manifest,
            streamElementIndex,
            selection,
            transferListener,
            cmcdConfiguration);
    return new ChunkSampleStream<>(
        manifest.streamElements[streamElementIndex].type,
        null,
        null,
        chunkSource,
        this,
        allocator,
        positionUs,
        drmSessionManager,
        drmEventDispatcher,
        loadErrorHandlingPolicy,
        mediaSourceEventDispatcher);
  }

  private static TrackGroupArray buildTrackGroups(
      SsManifest manifest,
      DrmSessionManager drmSessionManager,
      SsChunkSource.Factory chunkSourceFactory) {
    TrackGroup[] trackGroups = new TrackGroup[manifest.streamElements.length];
    for (int i = 0; i < manifest.streamElements.length; i++) {
      Format[] manifestFormats = manifest.streamElements[i].formats;
      Format[] exposedFormats = new Format[manifestFormats.length];
      for (int j = 0; j < manifestFormats.length; j++) {
        Format manifestFormat = manifestFormats[j];
        Format updatedFormatWithDrm =
            manifestFormat
                .buildUpon()
                .setCryptoType(drmSessionManager.getCryptoType(manifestFormat))
                .build();
        exposedFormats[j] = chunkSourceFactory.getOutputTextFormat(updatedFormatWithDrm);
      }
      trackGroups[i] = new TrackGroup(/* id= */ Integer.toString(i), exposedFormats);
    }
    return new TrackGroupArray(trackGroups);
  }

  // We won't assign the array to a variable that erases the generic type, and then write into it.
  @SuppressWarnings({"unchecked", "rawtypes"})
  private static ChunkSampleStream<SsChunkSource>[] newSampleStreamArray(int length) {
    return new ChunkSampleStream[length];
  }
}
