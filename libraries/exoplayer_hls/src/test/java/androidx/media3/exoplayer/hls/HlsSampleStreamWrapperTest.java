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
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.net.Uri;
import android.os.Looper;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.datasource.DataSource;
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
import androidx.media3.extractor.metadata.emsg.EventMessage;
import androidx.media3.extractor.metadata.emsg.EventMessageEncoder;
import androidx.media3.test.utils.FakeDataSource;
import androidx.media3.test.utils.FakeExtractorInput;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.Bytes;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
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

  @Test
  public void seekToUs_toStartOfSplicedInChunk_seeksInsideBufferWithoutError() {
    Format videoFormat = new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build();
    HlsSampleStreamWrapper sampleStreamWrapper =
        createSampleStreamWrapper(/* positionUs= */ 0L, videoFormat);
    when(mockChunkSource.hasIndependentSegments()).thenReturn(true);
    doAnswer(
            invocation -> {
              ExtractorOutput extractorOutput = invocation.getArgument(0);
              TrackOutput trackOutput = extractorOutput.track(/* id= */ 0, C.TRACK_TYPE_VIDEO);
              trackOutput.format(videoFormat);
              extractorOutput.endTracks();
              trackOutput.sampleMetadata(
                  /* timeUs= */ 500_000L,
                  C.BUFFER_FLAG_KEY_FRAME,
                  /* size= */ 0,
                  /* offset= */ 0,
                  /* cryptoData= */ null);
              return null;
            })
        .when(mockExtractor)
        .init(any());
    HlsMediaChunk chunk =
        createMediaChunk(
            /* startTimeUs= */ 500_000L, /* durationUs= */ 500_000L, /* shouldSpliceIn= */ true);
    doAnswer(
            invocation -> {
              HlsChunkSource.HlsChunkHolder chunkHolder = invocation.getArgument(5);
              chunkHolder.chunk = chunk;
              return null;
            })
        .when(mockChunkSource)
        .getNextChunk(any(), anyLong(), anyLong(), any(), anyBoolean(), any());
    sampleStreamWrapper.continueLoading(
        new LoadingInfo.Builder().setPlaybackPositionUs(0L).build());
    shadowOf(Looper.getMainLooper()).idle();

    boolean resetRequired =
        sampleStreamWrapper.seekToUs(/* positionUs= */ 500_000L, /* forceReset= */ false);

    assertThat(resetRequired).isFalse();
  }

  @Test
  public void seekToUs_toChunkOverwrittenBySubsequentSplicedInChunk_seeksToKeyframe() {
    Format videoFormat = new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build();
    HlsSampleStreamWrapper sampleStreamWrapper =
        createSampleStreamWrapper(/* positionUs= */ 0L, videoFormat);
    when(mockChunkSource.hasIndependentSegments()).thenReturn(true);
    HlsMediaChunk chunk0 =
        createMediaChunk(
            /* startTimeUs= */ 0L, /* durationUs= */ 500_000L, /* shouldSpliceIn= */ false);
    HlsMediaChunk chunk1 =
        createMediaChunk(
            /* startTimeUs= */ 500_000L, /* durationUs= */ 500_000L, /* shouldSpliceIn= */ false);
    HlsMediaChunk chunk2 =
        createMediaChunk(
            /* startTimeUs= */ 250_000L, /* durationUs= */ 750_000L, /* shouldSpliceIn= */ true);
    doAnswer(
            invocation -> {
              ExtractorOutput extractorOutput = invocation.getArgument(0);
              TrackOutput trackOutput = extractorOutput.track(/* id= */ 0, C.TRACK_TYPE_VIDEO);
              trackOutput.format(videoFormat);
              extractorOutput.endTracks();
              trackOutput.sampleMetadata(
                  /* timeUs= */ 0L,
                  C.BUFFER_FLAG_KEY_FRAME,
                  /* size= */ 0,
                  /* offset= */ 0,
                  /* cryptoData= */ null);
              trackOutput.sampleMetadata(
                  /* timeUs= */ 250_000L,
                  C.BUFFER_FLAG_KEY_FRAME,
                  /* size= */ 0,
                  /* offset= */ 0,
                  /* cryptoData= */ null);
              return null;
            })
        .doAnswer(invocation -> {
          ExtractorOutput extractorOutput = invocation.getArgument(0);
          TrackOutput trackOutput = extractorOutput.track(/* id= */ 0, C.TRACK_TYPE_VIDEO);
          trackOutput.format(videoFormat);
          trackOutput.sampleMetadata(
              /* timeUs= */ 500_000L,
              C.BUFFER_FLAG_KEY_FRAME,
              /* size= */ 0,
              /* offset= */ 0,
              /* cryptoData= */ null);
          return null;
        })
        .doAnswer(invocation -> {
          ExtractorOutput extractorOutput = invocation.getArgument(0);
          TrackOutput trackOutput = extractorOutput.track(/* id= */ 0, C.TRACK_TYPE_VIDEO);
          trackOutput.format(videoFormat);
          trackOutput.sampleMetadata(
              /* timeUs= */ 250_000L,
              C.BUFFER_FLAG_KEY_FRAME,
              /* size= */ 0,
              /* offset= */ 0,
              /* cryptoData= */ null);
          trackOutput.sampleMetadata(
              /* timeUs= */ 400_000L,
              /* flags= */ 0,
              /* size= */ 0,
              /* offset= */ 0,
              /* cryptoData= */ null);
          trackOutput.sampleMetadata(
              /* timeUs= */ 500_000L,
              C.BUFFER_FLAG_KEY_FRAME,
              /* size= */ 0,
              /* offset= */ 0,
              /* cryptoData= */ null);
          return null;
        })
        .when(mockExtractor)
        .init(any());
    doAnswer(
            invocation -> {
              HlsChunkSource.HlsChunkHolder chunkHolder = invocation.getArgument(5);
              chunkHolder.chunk = chunk0;
              return null;
            })
        .doAnswer(invocation -> {
          HlsChunkSource.HlsChunkHolder chunkHolder = invocation.getArgument(5);
          chunkHolder.chunk = chunk1;
          return null;
        })
        .doAnswer(invocation -> {
          HlsChunkSource.HlsChunkHolder chunkHolder = invocation.getArgument(5);
          chunkHolder.chunk = chunk2;
          return null;
        })
        .when(mockChunkSource)
        .getNextChunk(any(), anyLong(), anyLong(), any(), anyBoolean(), any());
    sampleStreamWrapper.continueLoading(
        new LoadingInfo.Builder().setPlaybackPositionUs(0L).build());
    shadowOf(Looper.getMainLooper()).idle();
    sampleStreamWrapper.continueLoading(
        new LoadingInfo.Builder().setPlaybackPositionUs(0L).build());
    shadowOf(Looper.getMainLooper()).idle();
    sampleStreamWrapper.continueLoading(
        new LoadingInfo.Builder().setPlaybackPositionUs(0L).build());
    shadowOf(Looper.getMainLooper()).idle();
    int sampleQueueIndex =
        sampleStreamWrapper.bindSampleQueueToSampleStream(/* trackGroupIndex= */ 0);

    boolean resetRequired =
        sampleStreamWrapper.seekToUs(/* positionUs= */ 500_000L, /* forceReset= */ false);

    assertThat(resetRequired).isFalse();
    assertThat(sampleStreamWrapper.skipData(sampleQueueIndex, /* positionUs= */ 500_000L))
        .isEqualTo(0);
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
            new MediaSourceEventListener.EventDispatcher()
                .withParameters(/* windowIndex= */ 0, new MediaSource.MediaPeriodId(new Object())),
            /* metadataType= */ HlsMediaSource.METADATA_TYPE_ID3,
            /* downloadExecutor= */ ReleasableExecutor.from(directExecutor(), e -> {}));
    sampleStreamWrapper.prepareWithMultivariantPlaylistInfo(
        new TrackGroup[] {new TrackGroup(format)}, /* primaryTrackGroupIndex= */ 0);
    return sampleStreamWrapper;
  }

  private HlsMediaChunk createMediaChunk(long startTimeUs, long durationUs) {
    return createMediaChunk(startTimeUs, durationUs, /* shouldSpliceIn= */ false);
  }

  private HlsMediaChunk createMediaChunk(
      long startTimeUs, long durationUs, boolean shouldSpliceIn) {
    FakeDataSource fakeDataSource = new FakeDataSource();
    fakeDataSource.getDataSet().newDefaultData().appendReadData(10).endData();
    return createMediaChunk(startTimeUs, durationUs, fakeDataSource, shouldSpliceIn);
  }

  private HlsMediaChunk createMediaChunk(long startTimeUs, long durationUs, DataSource dataSource) {
    return createMediaChunk(startTimeUs, durationUs, dataSource, /* shouldSpliceIn= */ false);
  }

  private HlsMediaChunk createMediaChunk(
      long startTimeUs, long durationUs, DataSource dataSource, boolean shouldSpliceIn) {
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
    return HlsMediaChunk.createInstance(
        mockExtractorFactory,
        dataSource,
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
        shouldSpliceIn,
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
              MediaSource.@Nullable MediaPeriodId mediaPeriodId,
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
              MediaSource.@Nullable MediaPeriodId mediaPeriodId,
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

  @Test
  public void
      onLoadError_excludedMediaChunkWithZeroBytesLoadedRemoved_resetsPositionToRemovedChunkStart() {
    HlsSampleStreamWrapper sampleStreamWrapper = createSampleStreamWrapper(/* positionUs= */ 0L);
    HlsMediaChunk chunk =
        createMediaChunk(
            /* startTimeUs= */ 1_000_000L, /* durationUs= */ 1_000_000L, new FakeDataSource());
    doAnswer(
            invocation -> {
              HlsChunkSource.HlsChunkHolder chunkHolder = invocation.getArgument(5);
              chunkHolder.chunk = chunk;
              return null;
            })
        .when(mockChunkSource)
        .getNextChunk(any(), anyLong(), anyLong(), any(), anyBoolean(), any());
    when(mockChunkSource.onChunkError(any(), any())).thenReturn(true);

    sampleStreamWrapper.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build());
    shadowOf(Looper.getMainLooper()).idle();

    assertThat(chunk.bytesLoaded()).isEqualTo(0);
    assertThat(sampleStreamWrapper.getNextLoadPositionUs()).isEqualTo(1_000_000L);
  }

  @Test
  public void
      continueLoading_reloadingPartiallyLoadedFailedChunk_discardsFailedChunkAndClearsSplice()
          throws Exception {
    HlsSampleStreamWrapper sampleStreamWrapper = createSampleStreamWrapper(/* positionUs= */ 0L);
    HlsMediaChunk failedChunk =
        createMediaChunk(/* startTimeUs= */ 1_000_000L, /* durationUs= */ 1_000_000L);
    when(mockExtractor.read(any()))
        .thenThrow(new IOException("Exception after partial read"))
        .thenReturn(false);
    doAnswer(
            invocation -> {
              HlsChunkSource.HlsChunkHolder chunkHolder = invocation.getArgument(5);
              chunkHolder.chunk = failedChunk;
              return null;
            })
        .when(mockChunkSource)
        .getNextChunk(any(), anyLong(), anyLong(), any(), anyBoolean(), any());
    when(mockChunkSource.onChunkError(any(), any())).thenReturn(true);

    // First load partially reads 10 bytes and then fails.
    sampleStreamWrapper.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build());
    shadowOf(Looper.getMainLooper()).idle();

    assertThat(failedChunk.bytesLoaded()).isGreaterThan(0);
    assertThat(failedChunk.isLoadCompleted()).isFalse();

    // Create replacement chunk at the same start time (1_000_000L) on a fallback playlist.
    HlsMediaPlaylist.Part replacementPart =
        new HlsMediaPlaylist.Part(
            /* url= */ "part1_low.ts",
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
    HlsMediaPlaylist fallbackPlaylist =
        new HlsMediaPlaylist(
            HlsMediaPlaylist.PLAYLIST_TYPE_UNKNOWN,
            /* baseUri= */ "http://example.com/low/",
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
            /* trailingParts= */ ImmutableList.of(replacementPart),
            new HlsMediaPlaylist.ServerControl(
                /* skipUntilUs= */ C.TIME_UNSET,
                /* canSkipDateRanges= */ false,
                /* holdBackUs= */ C.TIME_UNSET,
                /* partHoldBackUs= */ C.TIME_UNSET,
                /* canBlockReload= */ false),
            /* renditionReports= */ ImmutableMap.of(),
            /* interstitials= */ ImmutableList.of(),
            /* lastSeenInitSegment= */ null);
    HlsChunkSource.SegmentBaseHolder segmentBaseHolder =
        new HlsChunkSource.SegmentBaseHolder(
            fallbackPlaylist.trailingParts.get(0), /* mediaSequence= */ 0, /* partIndex= */ 0);
    Uri fallbackPlaylistUri = Uri.parse("http://example.com/low/playlist.m3u8");
    boolean shouldSpliceIn =
        HlsMediaChunk.shouldSpliceIn(
            failedChunk,
            fallbackPlaylistUri,
            /* isIndependent= */ true,
            segmentBaseHolder,
            /* startOfPlaylistInPeriodUs= */ 0L);
    assertThat(shouldSpliceIn).isTrue();

    FakeDataSource fakeDataSource = new FakeDataSource();
    fakeDataSource.getDataSet().newDefaultData().appendReadData(10).endData();
    HlsMediaChunk replacementChunk =
        HlsMediaChunk.createInstance(
            mockExtractorFactory,
            fakeDataSource,
            new Format.Builder().build(),
            /* startOfPlaylistInPeriodUs= */ 0L,
            fallbackPlaylist,
            segmentBaseHolder,
            fallbackPlaylistUri,
            /* steeredPathwayId= */ null,
            /* muxedCaptionFormats= */ null,
            C.SELECTION_REASON_ADAPTIVE,
            /* trackSelectionData= */ null,
            /* isPrimaryTimestampSource= */ true,
            new TimestampAdjusterProvider(),
            /* timestampAdjusterInitializationTimeoutMs= */ 0,
            /* previousChunk= */ failedChunk,
            /* mediaSegmentKey= */ null,
            /* initSegmentKey= */ null,
            shouldSpliceIn,
            /* isIndependent= */ true,
            PlayerId.UNSET,
            /* cmcdDataFactory= */ null);
    AtomicReference<List<HlsMediaChunk>> mediaChunks = new AtomicReference<>();
    doAnswer(
            invocation -> {
              mediaChunks.set(invocation.getArgument(3));
              HlsChunkSource.HlsChunkHolder chunkHolder = invocation.getArgument(5);
              chunkHolder.chunk = replacementChunk;
              return null;
            })
        .when(mockChunkSource)
        .getNextChunk(any(), anyLong(), anyLong(), any(), anyBoolean(), any());

    sampleStreamWrapper.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build());
    shadowOf(Looper.getMainLooper()).idle();

    // The unread failed chunk at the same start time is discarded and shouldSpliceIn is cleared.
    assertThat(mediaChunks.get()).containsExactly(replacementChunk);
    assertThat(replacementChunk.shouldSpliceIn()).isFalse();
    assertThat(replacementChunk.isLoadCompleted()).isTrue();
  }

  @Test
  public void sampleMetadata_straightThroughFormat_passesSampleWithoutModification() {
    FakeTrackOutput fakeTrackOutput =
        new FakeTrackOutput(C.TRACK_TYPE_METADATA, /* deduplicateConsecutiveFormats= */ false);
    HlsSampleStreamWrapper.EmsgUnwrappingTrackOutput trackOutput =
        new HlsSampleStreamWrapper.EmsgUnwrappingTrackOutput(
            fakeTrackOutput, HlsMediaSource.METADATA_TYPE_ID3);
    Format id3Format = new Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_ID3).build();
    byte[] sampleData = new byte[] {1, 2, 3, 4};

    trackOutput.format(id3Format);
    trackOutput.sampleData(
        new ParsableByteArray(sampleData), sampleData.length, TrackOutput.SAMPLE_DATA_PART_MAIN);
    trackOutput.sampleMetadata(
        /* timeUs= */ 1000,
        C.BUFFER_FLAG_KEY_FRAME,
        sampleData.length,
        /* offset= */ 0,
        /* cryptoData= */ null);

    assertThat(trackOutput.buffer).isEmpty();
    fakeTrackOutput.assertSampleCount(1);
    fakeTrackOutput.assertSample(
        /* index= */ 0,
        sampleData,
        /* timeUs= */ 1000,
        C.BUFFER_FLAG_KEY_FRAME,
        /* cryptoData= */ null);
  }

  @Test
  public void sampleMetadata_straightThroughFormatWithDataReader_passesSampleWithoutModification()
      throws Exception {
    FakeTrackOutput fakeTrackOutput =
        new FakeTrackOutput(C.TRACK_TYPE_METADATA, /* deduplicateConsecutiveFormats= */ false);
    HlsSampleStreamWrapper.EmsgUnwrappingTrackOutput trackOutput =
        new HlsSampleStreamWrapper.EmsgUnwrappingTrackOutput(
            fakeTrackOutput, HlsMediaSource.METADATA_TYPE_ID3);
    Format id3Format = new Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_ID3).build();
    byte[] sampleData = new byte[] {1, 2, 3, 4};

    trackOutput.format(id3Format);
    int bytesWritten =
        trackOutput.sampleData(
            new FakeExtractorInput.Builder().setData(sampleData).build(),
            sampleData.length,
            /* allowEndOfInput= */ true,
            TrackOutput.SAMPLE_DATA_PART_MAIN);
    trackOutput.sampleMetadata(
        /* timeUs= */ 1000,
        C.BUFFER_FLAG_KEY_FRAME,
        sampleData.length,
        /* offset= */ 0,
        /* cryptoData= */ null);

    assertThat(bytesWritten).isEqualTo(sampleData.length);
    assertThat(trackOutput.buffer).isEmpty();
    fakeTrackOutput.assertSampleCount(1);
    fakeTrackOutput.assertSample(
        /* index= */ 0,
        sampleData,
        /* timeUs= */ 1000,
        C.BUFFER_FLAG_KEY_FRAME,
        /* cryptoData= */ null);
  }

  @Test
  public void format_switchingToStraightThroughFormat_releasesBuffer() {
    FakeTrackOutput fakeTrackOutput =
        new FakeTrackOutput(C.TRACK_TYPE_METADATA, /* deduplicateConsecutiveFormats= */ true);
    HlsSampleStreamWrapper.EmsgUnwrappingTrackOutput trackOutput =
        new HlsSampleStreamWrapper.EmsgUnwrappingTrackOutput(
            fakeTrackOutput, HlsMediaSource.METADATA_TYPE_ID3);
    Format emsgFormat = new Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_EMSG).build();
    Format id3Format = new Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_ID3).build();
    byte[] bufferedBytes = new byte[] {1, 2, 3, 4};
    byte[] id3Sample = new byte[] {5, 6, 7, 8};

    trackOutput.format(emsgFormat);
    trackOutput.sampleData(
        new ParsableByteArray(bufferedBytes),
        bufferedBytes.length,
        TrackOutput.SAMPLE_DATA_PART_MAIN);
    trackOutput.format(id3Format);
    trackOutput.sampleData(
        new ParsableByteArray(id3Sample), id3Sample.length, TrackOutput.SAMPLE_DATA_PART_MAIN);
    trackOutput.sampleMetadata(
        /* timeUs= */ 1000,
        C.BUFFER_FLAG_KEY_FRAME,
        id3Sample.length,
        /* offset= */ 0,
        /* cryptoData= */ null);

    assertThat(trackOutput.buffer).isEmpty();
    fakeTrackOutput.assertSampleCount(1);
    fakeTrackOutput.assertSample(
        /* index= */ 0,
        id3Sample,
        /* timeUs= */ 1000,
        C.BUFFER_FLAG_KEY_FRAME,
        /* cryptoData= */ null);
  }

  @Test
  public void sampleData_beforeInitialStraightThroughFormat_forwardsSample() {
    FakeTrackOutput fakeTrackOutput =
        new FakeTrackOutput(C.TRACK_TYPE_METADATA, /* deduplicateConsecutiveFormats= */ false);
    HlsSampleStreamWrapper.EmsgUnwrappingTrackOutput trackOutput =
        new HlsSampleStreamWrapper.EmsgUnwrappingTrackOutput(
            fakeTrackOutput, HlsMediaSource.METADATA_TYPE_ID3);
    Format id3Format = new Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_ID3).build();
    byte[] sampleData = new byte[] {1, 2, 3, 4};

    trackOutput.sampleData(
        new ParsableByteArray(sampleData), sampleData.length, TrackOutput.SAMPLE_DATA_PART_MAIN);
    trackOutput.format(id3Format);
    trackOutput.sampleMetadata(
        /* timeUs= */ 1000,
        C.BUFFER_FLAG_KEY_FRAME,
        sampleData.length,
        /* offset= */ 0,
        /* cryptoData= */ null);

    assertThat(trackOutput.buffer).isEmpty();
    fakeTrackOutput.assertSampleCount(1);
    fakeTrackOutput.assertSample(
        /* index= */ 0,
        sampleData,
        /* timeUs= */ 1000,
        C.BUFFER_FLAG_KEY_FRAME,
        /* cryptoData= */ null);
  }

  @Test
  public void reset_afterBufferingData_releasesBuffer() {
    FakeTrackOutput fakeTrackOutput =
        new FakeTrackOutput(C.TRACK_TYPE_METADATA, /* deduplicateConsecutiveFormats= */ false);
    HlsSampleStreamWrapper.EmsgUnwrappingTrackOutput trackOutput =
        new HlsSampleStreamWrapper.EmsgUnwrappingTrackOutput(
            fakeTrackOutput, HlsMediaSource.METADATA_TYPE_ID3);
    Format emsgFormat = new Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_EMSG).build();
    byte[] bufferedBytes = new byte[20000];

    trackOutput.format(emsgFormat);
    trackOutput.sampleData(
        new ParsableByteArray(bufferedBytes),
        bufferedBytes.length,
        TrackOutput.SAMPLE_DATA_PART_MAIN);
    trackOutput.reset();

    assertThat(trackOutput.buffer).isEmpty();
  }

  @Test
  public void reset_afterStraightThroughFormat_buffersSampleBeforeEmsgFormat() {
    FakeTrackOutput fakeTrackOutput =
        new FakeTrackOutput(C.TRACK_TYPE_METADATA, /* deduplicateConsecutiveFormats= */ true);
    HlsSampleStreamWrapper.EmsgUnwrappingTrackOutput trackOutput =
        new HlsSampleStreamWrapper.EmsgUnwrappingTrackOutput(
            fakeTrackOutput, HlsMediaSource.METADATA_TYPE_ID3);
    Format id3Format = new Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_ID3).build();
    Format emsgFormat = new Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_EMSG).build();
    byte[] unwrappedData = new byte[] {5, 6, 7, 8};
    byte[] emsgData =
        new EventMessageEncoder()
            .encode(
                new EventMessage(
                    EventMessage.ID3_SCHEME_ID_AOM,
                    /* value= */ "",
                    /* durationMs= */ 1000,
                    /* id= */ 1,
                    unwrappedData));

    trackOutput.format(id3Format);
    trackOutput.reset();
    trackOutput.sampleData(
        new ParsableByteArray(emsgData), emsgData.length, TrackOutput.SAMPLE_DATA_PART_MAIN);
    trackOutput.format(emsgFormat);
    trackOutput.sampleMetadata(
        /* timeUs= */ 1000,
        C.BUFFER_FLAG_KEY_FRAME,
        emsgData.length,
        /* offset= */ 0,
        /* cryptoData= */ null);

    fakeTrackOutput.assertSampleCount(1);
    fakeTrackOutput.assertSample(
        /* index= */ 0,
        unwrappedData,
        /* timeUs= */ 1000,
        C.BUFFER_FLAG_KEY_FRAME,
        /* cryptoData= */ null);
  }

  @Test
  public void reset_afterEmsgFormat_flushesSampleBeforeStraightThroughFormat() {
    FakeTrackOutput fakeTrackOutput =
        new FakeTrackOutput(C.TRACK_TYPE_METADATA, /* deduplicateConsecutiveFormats= */ true);
    HlsSampleStreamWrapper.EmsgUnwrappingTrackOutput trackOutput =
        new HlsSampleStreamWrapper.EmsgUnwrappingTrackOutput(
            fakeTrackOutput, HlsMediaSource.METADATA_TYPE_ID3);
    Format emsgFormat = new Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_EMSG).build();
    Format id3Format = new Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_ID3).build();
    byte[] id3Data = new byte[] {1, 2, 3, 4};

    trackOutput.format(emsgFormat);
    trackOutput.reset();
    trackOutput.sampleData(
        new ParsableByteArray(id3Data), id3Data.length, TrackOutput.SAMPLE_DATA_PART_MAIN);
    trackOutput.format(id3Format);
    trackOutput.sampleMetadata(
        /* timeUs= */ 1000,
        C.BUFFER_FLAG_KEY_FRAME,
        id3Data.length,
        /* offset= */ 0,
        /* cryptoData= */ null);

    assertThat(trackOutput.buffer).isEmpty();
    fakeTrackOutput.assertSampleCount(1);
    fakeTrackOutput.assertSample(
        /* index= */ 0,
        id3Data,
        /* timeUs= */ 1000,
        C.BUFFER_FLAG_KEY_FRAME,
        /* cryptoData= */ null);
  }

  @Test
  public void sampleMetadata_emsgWrappedFormat_unwrapsAndPassesSample() {
    FakeTrackOutput fakeTrackOutput =
        new FakeTrackOutput(C.TRACK_TYPE_METADATA, /* deduplicateConsecutiveFormats= */ false);
    HlsSampleStreamWrapper.EmsgUnwrappingTrackOutput trackOutput =
        new HlsSampleStreamWrapper.EmsgUnwrappingTrackOutput(
            fakeTrackOutput, HlsMediaSource.METADATA_TYPE_ID3);
    Format emsgFormat = new Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_EMSG).build();
    byte[] unwrappedData = new byte[] {5, 6, 7, 8};
    EventMessage emsg =
        new EventMessage(
            "https://developer.apple.com/streaming/emsg-id3",
            /* value= */ "",
            /* durationMs= */ 1000,
            /* id= */ 1,
            unwrappedData);
    byte[] emsgData = new EventMessageEncoder().encode(emsg);

    trackOutput.format(emsgFormat);
    trackOutput.sampleData(
        new ParsableByteArray(emsgData), emsgData.length, TrackOutput.SAMPLE_DATA_PART_MAIN);
    trackOutput.sampleMetadata(
        /* timeUs= */ 1000,
        C.BUFFER_FLAG_KEY_FRAME,
        emsgData.length,
        /* offset= */ 0,
        /* cryptoData= */ null);

    fakeTrackOutput.assertSampleCount(1);
    fakeTrackOutput.assertSample(
        /* index= */ 0,
        unwrappedData,
        /* timeUs= */ 1000,
        C.BUFFER_FLAG_KEY_FRAME,
        /* cryptoData= */ null);
  }

  @Test
  public void sampleMetadata_withLargeBuffer_shrinksRetainedBuffer() {
    FakeTrackOutput fakeTrackOutput =
        new FakeTrackOutput(C.TRACK_TYPE_METADATA, /* deduplicateConsecutiveFormats= */ false);
    HlsSampleStreamWrapper.EmsgUnwrappingTrackOutput trackOutput =
        new HlsSampleStreamWrapper.EmsgUnwrappingTrackOutput(
            fakeTrackOutput, HlsMediaSource.METADATA_TYPE_ID3);
    Format emsgFormat = new Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_EMSG).build();
    // Create a large EMSG sample to force buffer growth beyond 16KB
    byte[] unwrappedData = new byte[20000];
    EventMessage emsg =
        new EventMessage(
            "https://developer.apple.com/streaming/emsg-id3",
            /* value= */ "",
            /* durationMs= */ 1000,
            /* id= */ 1,
            unwrappedData);
    byte[] emsgData = new EventMessageEncoder().encode(emsg);

    trackOutput.format(emsgFormat);
    trackOutput.sampleData(
        new ParsableByteArray(emsgData), emsgData.length, TrackOutput.SAMPLE_DATA_PART_MAIN);
    trackOutput.sampleMetadata(
        /* timeUs= */ 1000,
        C.BUFFER_FLAG_KEY_FRAME,
        emsgData.length,
        /* offset= */ 0,
        /* cryptoData= */ null);

    fakeTrackOutput.assertSampleCount(1);
    assertThat(trackOutput.buffer.length).isAtMost(16384);
  }

  @Test
  public void sampleMetadata_withOffset_shiftsDataCorrectly() {
    FakeTrackOutput fakeTrackOutput =
        new FakeTrackOutput(C.TRACK_TYPE_METADATA, /* deduplicateConsecutiveFormats= */ false);
    HlsSampleStreamWrapper.EmsgUnwrappingTrackOutput trackOutput =
        new HlsSampleStreamWrapper.EmsgUnwrappingTrackOutput(
            fakeTrackOutput, HlsMediaSource.METADATA_TYPE_ID3);
    Format emsgFormat = new Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_EMSG).build();
    byte[] unwrappedData1 = new byte[] {1, 2, 3, 4};
    byte[] unwrappedData2 = new byte[] {5, 6, 7, 8};
    EventMessage emsg1 =
        new EventMessage(
            "https://developer.apple.com/streaming/emsg-id3",
            /* value= */ "",
            /* durationMs= */ 1000,
            /* id= */ 1,
            unwrappedData1);
    EventMessage emsg2 =
        new EventMessage(
            "https://developer.apple.com/streaming/emsg-id3",
            /* value= */ "",
            /* durationMs= */ 1000,
            /* id= */ 2,
            unwrappedData2);
    byte[] sampleData1 = new EventMessageEncoder().encode(emsg1);
    byte[] sampleData2 = new EventMessageEncoder().encode(emsg2);
    byte[] combinedData = Bytes.concat(sampleData1, sampleData2);

    trackOutput.format(emsgFormat);
    trackOutput.sampleData(
        new ParsableByteArray(combinedData),
        combinedData.length,
        TrackOutput.SAMPLE_DATA_PART_MAIN);
    // Commit first sample, offset is the size of the second sample
    trackOutput.sampleMetadata(
        /* timeUs= */ 1000,
        C.BUFFER_FLAG_KEY_FRAME,
        /* size= */ sampleData1.length,
        /* offset= */ sampleData2.length,
        /* cryptoData= */ null);
    // Commit second sample, offset is 0
    trackOutput.sampleMetadata(
        /* timeUs= */ 2000,
        C.BUFFER_FLAG_KEY_FRAME,
        /* size= */ sampleData2.length,
        /* offset= */ 0,
        /* cryptoData= */ null);

    fakeTrackOutput.assertSampleCount(2);
    fakeTrackOutput.assertSample(
        /* index= */ 0,
        unwrappedData1,
        /* timeUs= */ 1000,
        C.BUFFER_FLAG_KEY_FRAME,
        /* cryptoData= */ null);
    fakeTrackOutput.assertSample(
        /* index= */ 1,
        unwrappedData2,
        /* timeUs= */ 2000,
        C.BUFFER_FLAG_KEY_FRAME,
        /* cryptoData= */ null);
  }

  @Test
  public void sampleMetadata_withUncommittedPrefix_decodesSample() {
    FakeTrackOutput fakeTrackOutput =
        new FakeTrackOutput(C.TRACK_TYPE_METADATA, /* deduplicateConsecutiveFormats= */ false);
    HlsSampleStreamWrapper.EmsgUnwrappingTrackOutput trackOutput =
        new HlsSampleStreamWrapper.EmsgUnwrappingTrackOutput(
            fakeTrackOutput, HlsMediaSource.METADATA_TYPE_ID3);
    Format emsgFormat = new Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_EMSG).build();
    byte[] prefix = new byte[] {9, 9, 9};
    byte[] unwrappedData = new byte[] {1, 2, 3, 4};
    EventMessage emsg =
        new EventMessage(
            "https://developer.apple.com/streaming/emsg-id3",
            /* value= */ "",
            /* durationMs= */ 1000,
            /* id= */ 1,
            unwrappedData);
    byte[] emsgData = new EventMessageEncoder().encode(emsg);
    byte[] combinedData = Bytes.concat(prefix, emsgData);

    trackOutput.format(emsgFormat);
    trackOutput.sampleData(
        new ParsableByteArray(combinedData),
        combinedData.length,
        TrackOutput.SAMPLE_DATA_PART_MAIN);
    trackOutput.sampleMetadata(
        /* timeUs= */ 1000,
        C.BUFFER_FLAG_KEY_FRAME,
        emsgData.length,
        /* offset= */ 0,
        /* cryptoData= */ null);

    fakeTrackOutput.assertSampleCount(1);
    fakeTrackOutput.assertSample(
        /* index= */ 0,
        unwrappedData,
        /* timeUs= */ 1000,
        C.BUFFER_FLAG_KEY_FRAME,
        /* cryptoData= */ null);
  }

  @Test
  public void sampleMetadata_withMalformedEmsg_recoversForNextSample() {
    FakeTrackOutput fakeTrackOutput =
        new FakeTrackOutput(C.TRACK_TYPE_METADATA, /* deduplicateConsecutiveFormats= */ false);
    HlsSampleStreamWrapper.EmsgUnwrappingTrackOutput trackOutput =
        new HlsSampleStreamWrapper.EmsgUnwrappingTrackOutput(
            fakeTrackOutput, HlsMediaSource.METADATA_TYPE_ID3);
    Format emsgFormat = new Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_EMSG).build();
    byte[] malformedData = new byte[] {0, 1, 2, 3};
    byte[] unwrappedData = new byte[] {5, 6, 7, 8};
    EventMessage emsg =
        new EventMessage(
            "https://developer.apple.com/streaming/emsg-id3",
            /* value= */ "",
            /* durationMs= */ 1000,
            /* id= */ 1,
            unwrappedData);
    byte[] emsgData = new EventMessageEncoder().encode(emsg);
    byte[] combinedData = Bytes.concat(malformedData, emsgData);

    trackOutput.format(emsgFormat);
    trackOutput.sampleData(
        new ParsableByteArray(combinedData),
        combinedData.length,
        TrackOutput.SAMPLE_DATA_PART_MAIN);
    assertThrows(
        RuntimeException.class,
        () ->
            trackOutput.sampleMetadata(
                /* timeUs= */ 1000,
                C.BUFFER_FLAG_KEY_FRAME,
                malformedData.length,
                /* offset= */ emsgData.length,
                /* cryptoData= */ null));
    trackOutput.sampleMetadata(
        /* timeUs= */ 2000,
        C.BUFFER_FLAG_KEY_FRAME,
        emsgData.length,
        /* offset= */ 0,
        /* cryptoData= */ null);

    fakeTrackOutput.assertSampleCount(1);
    fakeTrackOutput.assertSample(
        /* index= */ 0,
        unwrappedData,
        /* timeUs= */ 2000,
        C.BUFFER_FLAG_KEY_FRAME,
        /* cryptoData= */ null);
  }

  @Test
  public void sampleMetadata_largeOffset_preservesTailThenShrinksBuffer() {
    FakeTrackOutput fakeTrackOutput =
        new FakeTrackOutput(C.TRACK_TYPE_METADATA, /* deduplicateConsecutiveFormats= */ false);
    HlsSampleStreamWrapper.EmsgUnwrappingTrackOutput trackOutput =
        new HlsSampleStreamWrapper.EmsgUnwrappingTrackOutput(
            fakeTrackOutput, HlsMediaSource.METADATA_TYPE_ID3);
    Format emsgFormat = new Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_EMSG).build();
    byte[] unwrappedData1 = new byte[] {1, 2, 3, 4};
    byte[] unwrappedData2 = new byte[20000];
    unwrappedData2[19999] = 42;
    byte[] sampleData1 =
        new EventMessageEncoder()
            .encode(
                new EventMessage(
                    EventMessage.ID3_SCHEME_ID_AOM,
                    /* value= */ "",
                    /* durationMs= */ 1000,
                    /* id= */ 1,
                    unwrappedData1));
    byte[] sampleData2 =
        new EventMessageEncoder()
            .encode(
                new EventMessage(
                    EventMessage.ID3_SCHEME_ID_AOM,
                    /* value= */ "",
                    /* durationMs= */ 1000,
                    /* id= */ 2,
                    unwrappedData2));
    byte[] combinedData = Bytes.concat(sampleData1, sampleData2);

    trackOutput.format(emsgFormat);
    trackOutput.sampleData(
        new ParsableByteArray(combinedData),
        combinedData.length,
        TrackOutput.SAMPLE_DATA_PART_MAIN);
    trackOutput.sampleMetadata(
        /* timeUs= */ 1000,
        C.BUFFER_FLAG_KEY_FRAME,
        /* size= */ sampleData1.length,
        /* offset= */ sampleData2.length,
        /* cryptoData= */ null);
    trackOutput.sampleMetadata(
        /* timeUs= */ 2000,
        C.BUFFER_FLAG_KEY_FRAME,
        /* size= */ sampleData2.length,
        /* offset= */ 0,
        /* cryptoData= */ null);

    assertThat(trackOutput.buffer.length).isAtMost(16384);
    fakeTrackOutput.assertSampleCount(2);
    fakeTrackOutput.assertSample(
        /* index= */ 0,
        unwrappedData1,
        /* timeUs= */ 1000,
        C.BUFFER_FLAG_KEY_FRAME,
        /* cryptoData= */ null);
    fakeTrackOutput.assertSample(
        /* index= */ 1,
        unwrappedData2,
        /* timeUs= */ 2000,
        C.BUFFER_FLAG_KEY_FRAME,
        /* cryptoData= */ null);
  }

  @Test
  public void sampleMetadata_unexpectedWrappedEmsg_discardsAndShrinksBuffer() {
    FakeTrackOutput fakeTrackOutput =
        new FakeTrackOutput(C.TRACK_TYPE_METADATA, /* deduplicateConsecutiveFormats= */ false);
    HlsSampleStreamWrapper.EmsgUnwrappingTrackOutput trackOutput =
        new HlsSampleStreamWrapper.EmsgUnwrappingTrackOutput(
            fakeTrackOutput, HlsMediaSource.METADATA_TYPE_ID3);
    Format emsgFormat = new Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_EMSG).build();
    byte[] scte35EmsgData =
        new EventMessageEncoder()
            .encode(
                new EventMessage(
                    EventMessage.SCTE35_SCHEME_ID,
                    /* value= */ "",
                    /* durationMs= */ 1000,
                    /* id= */ 1,
                    new byte[20000]));

    trackOutput.format(emsgFormat);
    trackOutput.sampleData(
        new ParsableByteArray(scte35EmsgData),
        scte35EmsgData.length,
        TrackOutput.SAMPLE_DATA_PART_MAIN);
    trackOutput.sampleMetadata(
        /* timeUs= */ 1000,
        C.BUFFER_FLAG_KEY_FRAME,
        scte35EmsgData.length,
        /* offset= */ 0,
        /* cryptoData= */ null);

    assertThat(trackOutput.buffer.length).isAtMost(16384);
    fakeTrackOutput.assertSampleCount(0);
  }

  @Test
  public void sampleMetadata_withUnsupportedFormat_discardsSampleAndTrimsBuffer() {
    FakeTrackOutput fakeTrackOutput =
        new FakeTrackOutput(C.TRACK_TYPE_METADATA, /* deduplicateConsecutiveFormats= */ false);
    HlsSampleStreamWrapper.EmsgUnwrappingTrackOutput trackOutput =
        new HlsSampleStreamWrapper.EmsgUnwrappingTrackOutput(
            fakeTrackOutput, HlsMediaSource.METADATA_TYPE_ID3);
    Format unsupportedFormat =
        new Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_SCTE35).build();
    byte[] unwrappedData = new byte[20000];
    EventMessage emsg =
        new EventMessage(
            "https://developer.apple.com/streaming/emsg-id3",
            /* value= */ "",
            /* durationMs= */ 1000,
            /* id= */ 1,
            unwrappedData);
    byte[] unsupportedData = new EventMessageEncoder().encode(emsg);

    trackOutput.format(unsupportedFormat);
    trackOutput.sampleData(
        new ParsableByteArray(unsupportedData),
        unsupportedData.length,
        TrackOutput.SAMPLE_DATA_PART_MAIN);
    trackOutput.sampleMetadata(
        /* timeUs= */ 1000,
        C.BUFFER_FLAG_KEY_FRAME,
        unsupportedData.length,
        /* offset= */ 0,
        /* cryptoData= */ null);

    assertThat(trackOutput.buffer.length).isAtMost(16384);
    fakeTrackOutput.assertSampleCount(0);
  }
}
