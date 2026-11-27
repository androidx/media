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
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.TrackGroup;
import androidx.media3.exoplayer.LoadingInfo;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.drm.DrmSessionEventListener;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.source.MediaSourceEventListener;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.exoplayer.util.ReleasableExecutor;
import androidx.media3.test.utils.FakeDataSource;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
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

  @Before
  public void setUp() throws Exception {
    HlsMediaChunkExtractor mockExtractor = mock(HlsMediaChunkExtractor.class);
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
            mock(DrmSessionEventListener.EventDispatcher.class),
            mock(LoadErrorHandlingPolicy.class),
            mock(MediaSourceEventListener.EventDispatcher.class),
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
}
