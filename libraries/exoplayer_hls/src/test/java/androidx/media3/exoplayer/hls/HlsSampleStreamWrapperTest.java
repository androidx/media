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
package androidx.media3.exoplayer.hls;

import static androidx.media3.common.util.Util.createHandlerForCurrentLooper;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.net.Uri;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.TrackGroup;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.LoadingInfo;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.drm.DrmSessionEventListener;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MediaSourceEventListener;
import androidx.media3.exoplayer.source.SampleStream;
import androidx.media3.exoplayer.source.chunk.Chunk;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.Loader;
import androidx.media3.exoplayer.util.ReleasableExecutor;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.test.utils.FakeDataSource;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link HlsSampleStreamWrapper}. */
@RunWith(AndroidJUnit4.class)
public final class HlsSampleStreamWrapperTest {

  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  @Mock private HlsExtractorFactory mockExtractorFactory;
  @Mock private HlsChunkSource mockChunkSource;
  @Mock private HlsMediaChunkExtractor mockExtractor;

  @Before
  public void setUp() throws Exception {
    when(mockExtractorFactory.createExtractor(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(mockExtractor);
    // Extractor returns false for read to signal mock end-of-stream and complete load successfully
    when(mockExtractor.read(any())).thenReturn(false);
  }

  @Test
  public void discardUpstream_allChunksDiscarded_keepLoadingFromStartPositionOfFirstDroppedChunk() {
    HlsSampleStreamWrapper sampleStreamWrapper =
        new HlsSampleStreamWrapper(
            /* uid= */ "uid",
            C.TRACK_TYPE_VIDEO,
            mock(HlsSampleStreamWrapper.Callback.class),
            mockChunkSource,
            /* overridingDrmInitData= */ ImmutableMap.of(),
            mock(Allocator.class),
            /* positionUs= */ 0L,
            /* muxedAudioFormat= */ null,
            mock(DrmSessionManager.class),
            new DrmSessionEventListener.EventDispatcher(),
            mock(LoadErrorHandlingPolicy.class),
            new MediaSourceEventListener.EventDispatcher()
                .withParameters(/* windowIndex= */ 0, new MediaSource.MediaPeriodId(new Object())),
            /* metadataType= */ HlsMediaSource.METADATA_TYPE_ID3,
            /* downloadExecutor= */ ReleasableExecutor.from(directExecutor(), e -> {}));
    sampleStreamWrapper.prepareWithMultivariantPlaylistInfo(
        new TrackGroup[] {new TrackGroup(new Format.Builder().build())},
        /* primaryTrackGroupIndex= */ 0);
    // Create a Part with start time at 1_000_000L and duration of 1_000_000L.
    HlsMediaPlaylist.Part part =
        new HlsMediaPlaylist.Part(
            /* url= */ "part1.ts",
            /* initializationSegment= */ null,
            /* durationUs= */ 1_000_000L,
            /* relativeDiscontinuitySequence= */ 0,
            /* relativeStartTimeUs= */ 1_000_000L,
            /* drmInitData= */ null,
            /* fullSegmentEncryptionKeyUri= */ null,
            /* encryptionIV= */ null,
            /* byteRangeOffset= */ 0,
            /* byteRangeLength= */ C.LENGTH_UNSET,
            /* hasGapTag= */ false,
            /* isIndependent= */ true,
            /* isPreload= */ false);
    HlsMediaPlaylist playlist =
        new HlsMediaPlaylist(
            HlsMediaPlaylist.PLAYLIST_TYPE_UNKNOWN,
            /* baseUri= */ "http://example.com/",
            /* tags= */ ImmutableList.of(),
            /* startOffsetUs= */ C.TIME_UNSET,
            /* preciseStart= */ false,
            /* startTimeUs= */ 0L,
            /* hasDiscontinuitySequence= */ false,
            /* discontinuitySequence= */ 0,
            /* mediaSequence= */ 0L,
            /* version= */ 7,
            /* targetDurationUs= */ 4_000_000L,
            /* partTargetDurationUs= */ C.TIME_UNSET,
            /* hasIndependentSegments= */ true,
            /* hasEndTag= */ false,
            /* hasProgramDateTime= */ false,
            /* protectionSchemes= */ null,
            /* segments= */ ImmutableList.of(),
            /* trailingParts= */ ImmutableList.of(part),
            new HlsMediaPlaylist.ServerControl(
                /* skipUntilUs= */ C.TIME_UNSET,
                /* canSkipDateRanges= */ false,
                /* holdBackUs= */ C.TIME_UNSET,
                /* partHoldBackUs= */ C.TIME_UNSET,
                /* canBlockReload= */ false),
            /* renditionReports= */ ImmutableMap.of(),
            /* interstitials= */ ImmutableList.of(),
            /* lastSeenInitSegment= */ null);
    FakeDataSource fakeDataSource = new FakeDataSource();
    fakeDataSource.getDataSet().newDefaultData().appendReadData(10).endData();
    HlsMediaChunk chunk =
        HlsMediaChunk.createInstance(
            mockExtractorFactory,
            fakeDataSource,
            new Format.Builder().build(),
            /* startOfPlaylistInPeriodUs= */ 0L,
            playlist,
            new HlsChunkSource.SegmentBaseHolder(
                playlist.trailingParts.get(0), /* mediaSequence= */ 0, /* partIndex= */ 0),
            Uri.parse("http://example.com/part1.ts"),
            /* steeredPathwayId= */ null,
            /* muxedCaptionFormats= */ null,
            C.SELECTION_REASON_INITIAL,
            /* trackSelectionData= */ null,
            /* isPrimaryTimestampSource= */ true,
            new TimestampAdjusterProvider(),
            /* timestampAdjusterInitializationTimeoutMs= */ 0,
            /* previousChunk= */ null,
            /* mediaSegmentKey= */ null,
            /* initSegmentKey= */ null,
            /* shouldSpliceIn= */ false,
            /* isIndependent= */ true,
            PlayerId.UNSET,
            /* cmcdDataFactory= */ null);
    doAnswer(
            invocation -> {
              HlsChunkSource.HlsChunkHolder chunkHolder = invocation.getArgument(5);
              chunkHolder.chunk = chunk;
              return null;
            })
        .when(mockChunkSource)
        .getNextChunk(any(), anyLong(), anyLong(), any(), anyBoolean(), any());
    // Call continueLoading to add the chunk to mediaChunks queue
    sampleStreamWrapper.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build());
    shadowOf(Looper.getMainLooper()).idle();
    // Next load position should be 2_000_000L
    assertThat(sampleStreamWrapper.getNextLoadPositionUs()).isEqualTo(2_000_000L);
    // Stub mockChunkSource to mark the chunk as removed during reevaluation
    when(mockChunkSource.shouldCancelLoad(anyLong(), any(), any())).thenReturn(false);
    when(mockChunkSource.getChunkPublicationState(chunk))
        .thenReturn(HlsChunkSource.CHUNK_PUBLICATION_STATE_REMOVED);

    // Call reevaluateBuffer to trigger discardUpstream, assuming the playback position is 500ms.
    sampleStreamWrapper.reevaluateBuffer(/* positionUs= */ 500_000L);

    // Since the chunk was discarded and the chunk queue is empty, the next load position should
    // be reset to the start position of the first chunk dropped.
    assertThat(sampleStreamWrapper.getNextLoadPositionUs()).isEqualTo(1_000_000L);
  }

  @Test
  public void getStreamFlags_beforeChunksLoaded_returnsFlagMaybeHasPreroll() {
    HlsSampleStreamWrapper sampleStreamWrapper =
        createSampleStreamWrapper(/* positionUs= */ 1_500_000L);
    assertThat(sampleStreamWrapper.getStreamFlags(/* trackGroupIndex= */ 0))
        .isEqualTo(SampleStream.FLAG_MAYBE_HAS_PREROLL);
  }

  @Test
  public void getStreamFlags_beforeChunksLoaded_syncAudioTrack_returnsZero() {
    HlsSampleStreamWrapper sampleStreamWrapper =
        createSampleStreamWrapper(
            /* positionUs= */ 1_500_000L,
            new Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_AAC)
                .setCodecs("mp4a.40.2")
                .build());
    assertThat(sampleStreamWrapper.getStreamFlags(/* trackGroupIndex= */ 0)).isEqualTo(0);
  }

  @Test
  public void getStreamFlags_segmentAlignedResumption_returnsZero() {
    HlsSampleStreamWrapper sampleStreamWrapper =
        createSampleStreamWrapper(/* positionUs= */ 1_000_000L);
    HlsMediaChunk chunk =
        createMediaChunk(/* startTimeUs= */ 1_000_000L, /* durationUs= */ 1_000_000L);
    doAnswer(
            invocation -> {
              HlsChunkSource.HlsChunkHolder chunkHolder = invocation.getArgument(5);
              chunkHolder.chunk = chunk;
              return null;
            })
        .when(mockChunkSource)
        .getNextChunk(any(), anyLong(), anyLong(), any(), anyBoolean(), any());

    sampleStreamWrapper.continueLoading(
        new LoadingInfo.Builder().setPlaybackPositionUs(1_000_000L).build());
    shadowOf(Looper.getMainLooper()).idle();

    assertThat(sampleStreamWrapper.getStreamFlags(/* trackGroupIndex= */ 0)).isEqualTo(0);
  }

  @Test
  public void getStreamFlags_midSegmentResumption_returnsFlagHasPreroll() {
    HlsSampleStreamWrapper sampleStreamWrapper =
        createSampleStreamWrapper(
            /* positionUs= */ 1_500_000L,
            new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build());
    Format sampleFormat = new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build();
    doAnswer(
            invocation -> {
              ExtractorOutput extractorOutput = invocation.getArgument(0);
              extractorOutput.track(/* id= */ 0, C.TRACK_TYPE_VIDEO).format(sampleFormat);
              extractorOutput.endTracks();
              return null;
            })
        .when(mockExtractor)
        .init(any());
    HlsMediaChunk chunk =
        createMediaChunk(/* startTimeUs= */ 1_000_000L, /* durationUs= */ 1_000_000L);
    doAnswer(
            invocation -> {
              HlsChunkSource.HlsChunkHolder chunkHolder = invocation.getArgument(5);
              chunkHolder.chunk = chunk;
              return null;
            })
        .when(mockChunkSource)
        .getNextChunk(any(), anyLong(), anyLong(), any(), anyBoolean(), any());

    sampleStreamWrapper.continueLoading(
        new LoadingInfo.Builder().setPlaybackPositionUs(1_500_000L).build());
    shadowOf(Looper.getMainLooper()).idle();

    assertThat(sampleStreamWrapper.getStreamFlags(/* trackGroupIndex= */ 0))
        .isEqualTo(SampleStream.FLAG_HAS_PREROLL);
  }

  @Test
  public void getStreamFlags_midSegmentResumption_upstreamFormatNull_returnsFlagMaybeHasPreroll() {
    HlsSampleStreamWrapper sampleStreamWrapper =
        createSampleStreamWrapper(
            /* positionUs= */ 1_500_000L,
            new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build());
    AtomicReference<TrackOutput> trackOutput = new AtomicReference<>();
    doAnswer(
            invocation -> {
              ExtractorOutput extractorOutput = invocation.getArgument(0);
              trackOutput.set(extractorOutput.track(/* id= */ 0, C.TRACK_TYPE_VIDEO));
              extractorOutput.endTracks();
              return null;
            })
        .when(mockExtractor)
        .init(any());
    HlsMediaChunk chunk =
        createMediaChunk(/* startTimeUs= */ 1_000_000L, /* durationUs= */ 1_000_000L);
    doAnswer(
            invocation -> {
              HlsChunkSource.HlsChunkHolder chunkHolder = invocation.getArgument(5);
              chunkHolder.chunk = chunk;
              return null;
            })
        .when(mockChunkSource)
        .getNextChunk(any(), anyLong(), anyLong(), any(), anyBoolean(), any());

    sampleStreamWrapper.continueLoading(
        new LoadingInfo.Builder().setPlaybackPositionUs(1_500_000L).build());
    shadowOf(Looper.getMainLooper()).idle();

    // The chunk is loaded, but headers have not been parsed yet (upstreamFormat == null).
    assertThat(sampleStreamWrapper.getStreamFlags(/* trackGroupIndex= */ 0))
        .isEqualTo(SampleStream.FLAG_MAYBE_HAS_PREROLL);

    // Headers are parsed and upstreamFormat is populated.
    trackOutput.get().format(new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build());
    shadowOf(Looper.getMainLooper()).idle();

    // Video tracks require preroll frames to be decoded; preroll flag must be reported.
    assertThat(sampleStreamWrapper.getStreamFlags(/* trackGroupIndex= */ 0))
        .isEqualTo(SampleStream.FLAG_HAS_PREROLL);
  }

  @Test
  public void getStreamFlags_syncAudioTrack_reportsZeroForMidSegmentResumption() {
    HlsSampleStreamWrapper sampleStreamWrapper =
        createSampleStreamWrapper(
            /* positionUs= */ 1_500_000L,
            new Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_AAC)
                .setCodecs("mp4a.40.2")
                .build());
    HlsMediaChunk chunk =
        createMediaChunk(/* startTimeUs= */ 1_000_000L, /* durationUs= */ 1_000_000L);
    doAnswer(
            invocation -> {
              HlsChunkSource.HlsChunkHolder chunkHolder = invocation.getArgument(5);
              chunkHolder.chunk = chunk;
              return null;
            })
        .when(mockChunkSource)
        .getNextChunk(any(), anyLong(), anyLong(), any(), anyBoolean(), any());

    sampleStreamWrapper.continueLoading(
        new LoadingInfo.Builder().setPlaybackPositionUs(1_500_000L).build());
    shadowOf(Looper.getMainLooper()).idle();

    // Sync audio discards samples up to start time; no preroll should be reported.
    assertThat(sampleStreamWrapper.getStreamFlags(/* trackGroupIndex= */ 0)).isEqualTo(0);
  }

  @Test
  public void getStreamFlags_beforeChunksLoaded_videoApv_returnsMaybeHasPreroll() {
    HlsSampleStreamWrapper sampleStreamWrapper =
        createSampleStreamWrapper(
            /* positionUs= */ 1_500_000L,
            new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_APV).build());
    assertThat(sampleStreamWrapper.getStreamFlags(/* trackGroupIndex= */ 0))
        .isEqualTo(SampleStream.FLAG_MAYBE_HAS_PREROLL);
  }

  @Test
  public void getStreamFlags_midSegmentResumption_videoApv_returnsFlagHasPreroll() {
    HlsSampleStreamWrapper sampleStreamWrapper =
        createSampleStreamWrapper(
            /* positionUs= */ 1_500_000L,
            new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_APV).build());
    Format sampleFormat = new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_APV).build();
    doAnswer(
            invocation -> {
              ExtractorOutput extractorOutput = invocation.getArgument(0);
              extractorOutput.track(/* id= */ 0, C.TRACK_TYPE_VIDEO).format(sampleFormat);
              extractorOutput.endTracks();
              return null;
            })
        .when(mockExtractor)
        .init(any());
    HlsMediaChunk chunk =
        createMediaChunk(/* startTimeUs= */ 1_000_000L, /* durationUs= */ 1_000_000L);
    doAnswer(
            invocation -> {
              HlsChunkSource.HlsChunkHolder chunkHolder = invocation.getArgument(5);
              chunkHolder.chunk = chunk;
              return null;
            })
        .when(mockChunkSource)
        .getNextChunk(any(), anyLong(), anyLong(), any(), anyBoolean(), any());

    sampleStreamWrapper.continueLoading(
        new LoadingInfo.Builder().setPlaybackPositionUs(1_500_000L).build());
    shadowOf(Looper.getMainLooper()).idle();

    // Even though APV only contains sync frames, it is a video format and SampleQueue does not
    // discard samples to start time; preroll flag must be reported.
    assertThat(sampleStreamWrapper.getStreamFlags(/* trackGroupIndex= */ 0))
        .isEqualTo(SampleStream.FLAG_HAS_PREROLL);
  }

  @Test
  public void getStreamFlags_midSegmentResumption_nonSyncAudio_returnsFlagHasPreroll() {
    HlsSampleStreamWrapper sampleStreamWrapper =
        createSampleStreamWrapper(
            /* positionUs= */ 1_500_000L,
            new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_VORBIS).build());
    Format sampleFormat = new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_VORBIS).build();
    doAnswer(
            invocation -> {
              ExtractorOutput extractorOutput = invocation.getArgument(0);
              extractorOutput.track(/* id= */ 0, C.TRACK_TYPE_AUDIO).format(sampleFormat);
              extractorOutput.endTracks();
              return null;
            })
        .when(mockExtractor)
        .init(any());
    HlsMediaChunk chunk =
        createMediaChunk(/* startTimeUs= */ 1_000_000L, /* durationUs= */ 1_000_000L);
    doAnswer(
            invocation -> {
              HlsChunkSource.HlsChunkHolder chunkHolder = invocation.getArgument(5);
              chunkHolder.chunk = chunk;
              return null;
            })
        .when(mockChunkSource)
        .getNextChunk(any(), anyLong(), anyLong(), any(), anyBoolean(), any());

    sampleStreamWrapper.continueLoading(
        new LoadingInfo.Builder().setPlaybackPositionUs(1_500_000L).build());
    shadowOf(Looper.getMainLooper()).idle();

    // Non-sync audio does not discard samples up to start time; preroll flag must be reported.
    assertThat(sampleStreamWrapper.getStreamFlags(/* trackGroupIndex= */ 0))
        .isEqualTo(SampleStream.FLAG_HAS_PREROLL);
  }

  @Test
  public void getStreamFlags_loadingFinishedWithoutMediaChunks_returnsZero() {
    HlsSampleStreamWrapper sampleStreamWrapper =
        createSampleStreamWrapper(/* positionUs= */ 1_500_000L);
    doAnswer(
            invocation -> {
              HlsChunkSource.HlsChunkHolder chunkHolder = invocation.getArgument(5);
              chunkHolder.endOfStream = true;
              return null;
            })
        .when(mockChunkSource)
        .getNextChunk(any(), anyLong(), anyLong(), any(), anyBoolean(), any());

    sampleStreamWrapper.continueLoading(
        new LoadingInfo.Builder().setPlaybackPositionUs(1_500_000L).build());
    shadowOf(Looper.getMainLooper()).idle();

    // No chunk will ever be loaded, so the stream must not stay stuck on FLAG_MAYBE_HAS_PREROLL.
    assertThat(sampleStreamWrapper.getStreamFlags(/* trackGroupIndex= */ 0)).isEqualTo(0);
  }

  @Test
  public void getStreamFlags_unmappedTrackGroup_returnsZero() {
    Format videoFormat = new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build();
    Format optionalFormat =
        new Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).build();
    HlsSampleStreamWrapper sampleStreamWrapper =
        new HlsSampleStreamWrapper(
            /* uid= */ "uid",
            C.TRACK_TYPE_VIDEO,
            mock(HlsSampleStreamWrapper.Callback.class),
            mockChunkSource,
            /* overridingDrmInitData= */ ImmutableMap.of(),
            mock(Allocator.class),
            /* positionUs= */ 1_500_000L,
            /* muxedAudioFormat= */ null,
            mock(DrmSessionManager.class),
            new DrmSessionEventListener.EventDispatcher(),
            mock(LoadErrorHandlingPolicy.class),
            new MediaSourceEventListener.EventDispatcher(),
            /* metadataType= */ HlsMediaSource.METADATA_TYPE_ID3,
            /* downloadExecutor= */ ReleasableExecutor.from(directExecutor(), e -> {}));
    sampleStreamWrapper.prepareWithMultivariantPlaylistInfo(
        new TrackGroup[] {new TrackGroup(videoFormat), new TrackGroup(optionalFormat)},
        /* primaryTrackGroupIndex= */ 0,
        /* optionalTrackGroupsIndices...= */ 1);
    doAnswer(
            invocation -> {
              ExtractorOutput extractorOutput = invocation.getArgument(0);
              extractorOutput.track(/* id= */ 0, C.TRACK_TYPE_VIDEO).format(videoFormat);
              extractorOutput.endTracks();
              return null;
            })
        .when(mockExtractor)
        .init(any());
    HlsMediaChunk chunk =
        createMediaChunk(/* startTimeUs= */ 1_000_000L, /* durationUs= */ 1_000_000L);
    doAnswer(
            invocation -> {
              HlsChunkSource.HlsChunkHolder chunkHolder = invocation.getArgument(5);
              chunkHolder.chunk = chunk;
              return null;
            })
        .when(mockChunkSource)
        .getNextChunk(any(), anyLong(), anyLong(), any(), anyBoolean(), any());

    sampleStreamWrapper.continueLoading(
        new LoadingInfo.Builder().setPlaybackPositionUs(1_500_000L).build());
    shadowOf(Looper.getMainLooper()).idle();

    // Primary track has preroll.
    assertThat(sampleStreamWrapper.getStreamFlags(/* trackGroupIndex= */ 0))
        .isEqualTo(SampleStream.FLAG_HAS_PREROLL);
    // Unmapped optional track returns 0.
    assertThat(sampleStreamWrapper.getStreamFlags(/* trackGroupIndex= */ 1)).isEqualTo(0);
  }

  @Test
  public void getStreamFlags_syncAudioWithoutCodecsInMultivariantPlaylist_returnsZero() {
    // The multivariant playlist rendition has no CODECS attribute, but the extractor reports the
    // codecs of the actual samples.
    HlsSampleStreamWrapper sampleStreamWrapper =
        createSampleStreamWrapper(
            /* positionUs= */ 1_500_000L,
            new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build());
    Format sampleFormat =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setCodecs("mp4a.40.2").build();
    AtomicReference<TrackOutput> trackOutput = new AtomicReference<>();
    doAnswer(
            invocation -> {
              ExtractorOutput extractorOutput = invocation.getArgument(0);
              trackOutput.set(extractorOutput.track(/* id= */ 0, C.TRACK_TYPE_AUDIO));
              extractorOutput.endTracks();
              return null;
            })
        .when(mockExtractor)
        .init(any());
    HlsMediaChunk chunk =
        createMediaChunk(/* startTimeUs= */ 1_000_000L, /* durationUs= */ 1_000_000L);
    doAnswer(
            invocation -> {
              HlsChunkSource.HlsChunkHolder chunkHolder = invocation.getArgument(5);
              chunkHolder.chunk = chunk;
              return null;
            })
        .when(mockChunkSource)
        .getNextChunk(any(), anyLong(), anyLong(), any(), anyBoolean(), any());

    sampleStreamWrapper.continueLoading(
        new LoadingInfo.Builder().setPlaybackPositionUs(1_500_000L).build());
    shadowOf(Looper.getMainLooper()).idle();

    // The chunk is loaded, but headers have not been parsed yet (upstreamFormat == null).
    assertThat(sampleStreamWrapper.getStreamFlags(/* trackGroupIndex= */ 0))
        .isEqualTo(SampleStream.FLAG_MAYBE_HAS_PREROLL);

    // Headers are parsed and upstreamFormat is populated.
    trackOutput.get().format(sampleFormat);
    shadowOf(Looper.getMainLooper()).idle();

    // Sync audio discards all samples before the start time, so there is no preroll even though
    // the resumption is mid-segment.
    assertThat(sampleStreamWrapper.getStreamFlags(/* trackGroupIndex= */ 0)).isEqualTo(0);
  }

  private HlsSampleStreamWrapper createSampleStreamWrapper(long positionUs) {
    return createSampleStreamWrapper(positionUs, new Format.Builder().build());
  }

  private HlsSampleStreamWrapper createSampleStreamWrapper(long positionUs, Format format) {
    HlsSampleStreamWrapper sampleStreamWrapper =
        new HlsSampleStreamWrapper(
            /* uid= */ "uid",
            C.TRACK_TYPE_VIDEO,
            mock(HlsSampleStreamWrapper.Callback.class),
            mockChunkSource,
            /* overridingDrmInitData= */ ImmutableMap.of(),
            mock(Allocator.class),
            positionUs,
            /* muxedAudioFormat= */ null,
            mock(DrmSessionManager.class),
            new DrmSessionEventListener.EventDispatcher(),
            mock(LoadErrorHandlingPolicy.class),
            new MediaSourceEventListener.EventDispatcher(),
            /* metadataType= */ HlsMediaSource.METADATA_TYPE_ID3,
            /* downloadExecutor= */ ReleasableExecutor.from(directExecutor(), e -> {}));
    sampleStreamWrapper.prepareWithMultivariantPlaylistInfo(
        new TrackGroup[] {new TrackGroup(format)}, /* primaryTrackGroupIndex= */ 0);
    return sampleStreamWrapper;
  }

  private HlsMediaChunk createMediaChunk(long startTimeUs, long durationUs) {
    HlsMediaPlaylist.Part part =
        new HlsMediaPlaylist.Part(
            /* url= */ "part1.ts",
            /* initializationSegment= */ null,
            durationUs,
            /* relativeDiscontinuitySequence= */ 0,
            startTimeUs,
            /* drmInitData= */ null,
            /* fullSegmentEncryptionKeyUri= */ null,
            /* encryptionIV= */ null,
            /* byteRangeOffset= */ 0,
            /* byteRangeLength= */ C.LENGTH_UNSET,
            /* hasGapTag= */ false,
            /* isIndependent= */ true,
            /* isPreload= */ false);
    HlsMediaPlaylist playlist =
        new HlsMediaPlaylist(
            HlsMediaPlaylist.PLAYLIST_TYPE_UNKNOWN,
            /* baseUri= */ "http://example.com/",
            /* tags= */ ImmutableList.of(),
            /* startOffsetUs= */ C.TIME_UNSET,
            /* preciseStart= */ false,
            /* startTimeUs= */ 0L,
            /* hasDiscontinuitySequence= */ false,
            /* discontinuitySequence= */ 0,
            /* mediaSequence= */ 0L,
            /* version= */ 7,
            /* targetDurationUs= */ 4_000_000L,
            /* partTargetDurationUs= */ C.TIME_UNSET,
            /* hasIndependentSegments= */ true,
            /* hasEndTag= */ false,
            /* hasProgramDateTime= */ false,
            /* protectionSchemes= */ null,
            /* segments= */ ImmutableList.of(),
            /* trailingParts= */ ImmutableList.of(part),
            new HlsMediaPlaylist.ServerControl(
                /* skipUntilUs= */ C.TIME_UNSET,
                /* canSkipDateRanges= */ false,
                /* holdBackUs= */ C.TIME_UNSET,
                /* partHoldBackUs= */ C.TIME_UNSET,
                /* canBlockReload= */ false),
            /* renditionReports= */ ImmutableMap.of(),
            /* interstitials= */ ImmutableList.of(),
            /* lastSeenInitSegment= */ null);
    FakeDataSource fakeDataSource = new FakeDataSource();
    fakeDataSource.getDataSet().newDefaultData().appendReadData(10).endData();
    return HlsMediaChunk.createInstance(
        mockExtractorFactory,
        fakeDataSource,
        new Format.Builder().build(),
        /* startOfPlaylistInPeriodUs= */ 0L,
        playlist,
        new HlsChunkSource.SegmentBaseHolder(
            playlist.trailingParts.get(0), /* mediaSequence= */ 0, /* partIndex= */ 0),
        Uri.parse("http://example.com/part1.ts"),
        /* steeredPathwayId= */ null,
        /* muxedCaptionFormats= */ null,
        C.SELECTION_REASON_INITIAL,
        /* trackSelectionData= */ null,
        /* isPrimaryTimestampSource= */ true,
        new TimestampAdjusterProvider(),
        /* timestampAdjusterInitializationTimeoutMs= */ 0,
        /* previousChunk= */ null,
        /* mediaSegmentKey= */ null,
        /* initSegmentKey= */ null,
        /* shouldSpliceIn= */ false,
        /* isIndependent= */ true,
        PlayerId.UNSET,
        /* cmcdDataFactory= */ null);
  }

  @Test
  public void onLoadError_whenRetriesExhausted_returnsDontRetryFatalAndReportsCanceled() {
    MediaSourceEventListener.EventDispatcher eventDispatcher =
        new MediaSourceEventListener.EventDispatcher();
    List<Boolean> wasCanceledEvents = new ArrayList<>();
    eventDispatcher.addEventListener(
        createHandlerForCurrentLooper(),
        new MediaSourceEventListener() {
          @Override
          public void onLoadError(
              int windowIndex,
              @Nullable MediaSource.MediaPeriodId mediaPeriodId,
              LoadEventInfo loadEventInfo,
              MediaLoadData mediaLoadData,
              IOException error,
              boolean wasCanceled) {
            wasCanceledEvents.add(wasCanceled);
          }
        });
    HlsSampleStreamWrapper sampleStreamWrapper =
        new HlsSampleStreamWrapper(
            /* uid= */ "uid",
            C.TRACK_TYPE_VIDEO,
            mock(HlsSampleStreamWrapper.Callback.class),
            mockChunkSource,
            /* overridingDrmInitData= */ ImmutableMap.of(),
            mock(Allocator.class),
            /* positionUs= */ 0L,
            /* muxedAudioFormat= */ null,
            mock(DrmSessionManager.class),
            new DrmSessionEventListener.EventDispatcher(),
            new DefaultLoadErrorHandlingPolicy(),
            eventDispatcher,
            /* metadataType= */ HlsMediaSource.METADATA_TYPE_ID3,
            /* downloadExecutor= */ ReleasableExecutor.from(directExecutor(), e -> {}));
    Chunk chunk =
        new Chunk(
            new FakeDataSource(),
            new DataSpec(Uri.parse("http://example.com/chunk.ts")),
            C.DATA_TYPE_MEDIA,
            new Format.Builder().build(),
            C.SELECTION_REASON_UNKNOWN,
            /* trackSelectionData= */ null,
            /* startTimeUs= */ 0,
            /* endTimeUs= */ 1_000_000,
            /* steeredPathwayId= */ null) {
          @Override
          public void cancelLoad() {}

          @Override
          public void load() {}
        };
    when(mockChunkSource.onChunkError(any(), any())).thenReturn(false);

    Loader.LoadErrorAction actionWithinLimit =
        sampleStreamWrapper.onLoadError(
            chunk,
            /* elapsedRealtimeMs= */ 0,
            /* loadDurationMs= */ 0,
            new IOException("Transient error"),
            /* errorCount= */ 3);
    Loader.LoadErrorAction actionExhausted =
        sampleStreamWrapper.onLoadError(
            chunk,
            /* elapsedRealtimeMs= */ 0,
            /* loadDurationMs= */ 0,
            new IOException("Transient error"),
            /* errorCount= */ 4);

    assertThat(actionWithinLimit.isRetry()).isTrue();
    assertThat(actionExhausted).isEqualTo(Loader.DONT_RETRY_FATAL);
    assertThat(wasCanceledEvents).containsExactly(false, true).inOrder();
  }

  @Test
  public void onLoadError_unpublishedPreloadPartHttp404_stopsRetryingWhenRetriesExhausted() {
    MediaSourceEventListener.EventDispatcher eventDispatcher =
        new MediaSourceEventListener.EventDispatcher();
    List<Boolean> wasCanceledEvents = new ArrayList<>();
    eventDispatcher.addEventListener(
        createHandlerForCurrentLooper(),
        new MediaSourceEventListener() {
          @Override
          public void onLoadError(
              int windowIndex,
              @Nullable MediaSource.MediaPeriodId mediaPeriodId,
              LoadEventInfo loadEventInfo,
              MediaLoadData mediaLoadData,
              IOException error,
              boolean wasCanceled) {
            wasCanceledEvents.add(wasCanceled);
          }
        });
    HlsSampleStreamWrapper sampleStreamWrapper =
        new HlsSampleStreamWrapper(
            /* uid= */ "uid",
            C.TRACK_TYPE_VIDEO,
            mock(HlsSampleStreamWrapper.Callback.class),
            mockChunkSource,
            /* overridingDrmInitData= */ ImmutableMap.of(),
            mock(Allocator.class),
            /* positionUs= */ 0L,
            /* muxedAudioFormat= */ null,
            mock(DrmSessionManager.class),
            new DrmSessionEventListener.EventDispatcher(),
            new DefaultLoadErrorHandlingPolicy(),
            eventDispatcher,
            /* metadataType= */ HlsMediaSource.METADATA_TYPE_ID3,
            /* downloadExecutor= */ ReleasableExecutor.from(directExecutor(), e -> {}));
    HlsMediaPlaylist.Part preloadPart =
        new HlsMediaPlaylist.Part(
            /* url= */ "preload.ts",
            /* initializationSegment= */ null,
            /* durationUs= */ 1_000_000L,
            /* relativeDiscontinuitySequence= */ 0,
            /* relativeStartTimeUs= */ 0L,
            /* drmInitData= */ null,
            /* fullSegmentEncryptionKeyUri= */ null,
            /* encryptionIV= */ null,
            /* byteRangeOffset= */ 0,
            /* byteRangeLength= */ C.LENGTH_UNSET,
            /* hasGapTag= */ false,
            /* isIndependent= */ true,
            /* isPreload= */ true);
    HlsMediaPlaylist playlist =
        new HlsMediaPlaylist(
            HlsMediaPlaylist.PLAYLIST_TYPE_UNKNOWN,
            /* baseUri= */ "http://example.com/",
            /* tags= */ ImmutableList.of(),
            /* startOffsetUs= */ C.TIME_UNSET,
            /* preciseStart= */ false,
            /* startTimeUs= */ 0L,
            /* hasDiscontinuitySequence= */ false,
            /* discontinuitySequence= */ 0,
            /* mediaSequence= */ 0L,
            /* version= */ 7,
            /* targetDurationUs= */ 4_000_000L,
            /* partTargetDurationUs= */ C.TIME_UNSET,
            /* hasIndependentSegments= */ true,
            /* hasEndTag= */ false,
            /* hasProgramDateTime= */ false,
            /* protectionSchemes= */ null,
            /* segments= */ ImmutableList.of(),
            /* trailingParts= */ ImmutableList.of(preloadPart),
            new HlsMediaPlaylist.ServerControl(
                /* skipUntilUs= */ C.TIME_UNSET,
                /* canSkipDateRanges= */ false,
                /* holdBackUs= */ C.TIME_UNSET,
                /* partHoldBackUs= */ C.TIME_UNSET,
                /* canBlockReload= */ false),
            /* renditionReports= */ ImmutableMap.of(),
            /* interstitials= */ ImmutableList.of(),
            /* lastSeenInitSegment= */ null);
    HlsMediaChunk preloadChunk =
        HlsMediaChunk.createInstance(
            mockExtractorFactory,
            new FakeDataSource(),
            new Format.Builder().build(),
            /* startOfPlaylistInPeriodUs= */ 0L,
            playlist,
            new HlsChunkSource.SegmentBaseHolder(
                playlist.trailingParts.get(0), /* mediaSequence= */ 0, /* partIndex= */ 0),
            Uri.parse("http://example.com/preload.ts"),
            /* steeredPathwayId= */ null,
            /* muxedCaptionFormats= */ null,
            C.SELECTION_REASON_INITIAL,
            /* trackSelectionData= */ null,
            /* isPrimaryTimestampSource= */ true,
            new TimestampAdjusterProvider(),
            /* timestampAdjusterInitializationTimeoutMs= */ 0,
            /* previousChunk= */ null,
            /* mediaSegmentKey= */ null,
            /* initSegmentKey= */ null,
            /* shouldSpliceIn= */ false,
            /* isIndependent= */ true,
            PlayerId.UNSET,
            /* cmcdDataFactory= */ null);
    when(mockChunkSource.onChunkError(any(), any())).thenReturn(false);
    when(mockChunkSource.createFallbackOptions(any(Chunk.class)))
        .thenReturn(
            new LoadErrorHandlingPolicy.FallbackOptions(
                /* numberOfLocations= */ 1,
                /* numberOfExcludedLocations= */ 0,
                /* numberOfTracks= */ 1,
                /* numberOfExcludedTracks= */ 0,
                /* locationSteeringActive= */ false));
    HttpDataSource.InvalidResponseCodeException http404Exception =
        new HttpDataSource.InvalidResponseCodeException(
            /* responseCode= */ 404,
            /* responseMessage= */ "Not Found",
            /* cause= */ null,
            ImmutableMap.of(),
            new DataSpec(Uri.parse("http://example.com/preload.ts")),
            new byte[0]);

    Loader.LoadErrorAction actionWithinLimit =
        sampleStreamWrapper.onLoadError(
            preloadChunk,
            /* elapsedRealtimeMs= */ 0,
            /* loadDurationMs= */ 0,
            http404Exception,
            /* errorCount= */ 3);
    Loader.LoadErrorAction actionExhausted =
        sampleStreamWrapper.onLoadError(
            preloadChunk,
            /* elapsedRealtimeMs= */ 0,
            /* loadDurationMs= */ 0,
            http404Exception,
            /* errorCount= */ 4);

    assertThat(preloadChunk.isPublished()).isFalse();
    assertThat(actionWithinLimit).isEqualTo(Loader.RETRY);
    assertThat(actionExhausted).isEqualTo(Loader.DONT_RETRY_FATAL);
    assertThat(wasCanceledEvents).containsExactly(true);
  }
}
