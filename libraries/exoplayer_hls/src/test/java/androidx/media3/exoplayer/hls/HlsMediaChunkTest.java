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
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.ParserException;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.drm.DrmSessionEventListener;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.source.MediaSourceEventListener;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.test.utils.FakeDataSource;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.EOFException;
import java.io.IOException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link HlsMediaChunk}. */
@RunWith(AndroidJUnit4.class)
public final class HlsMediaChunkTest {

  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  @Mock private HlsMediaChunkExtractor mockExtractor;
  @Mock private HlsExtractorFactory extractorFactory;

  @Before
  public void setUp() throws Exception {
    when(mockExtractor.isReusable()).thenReturn(true);
    when(extractorFactory.createExtractor(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(mockExtractor);
  }

  @Test
  public void load_extractorThrowsEOFExceptionAfterFullyLoadedChunk_throwsParserException()
      throws Exception {
    when(mockExtractor.read(any(ExtractorInput.class)))
        .thenAnswer(
            invocation -> {
              ExtractorInput input = invocation.getArgument(0);
              input.skipFully(100);
              throw new EOFException();
            });
    HlsMediaPlaylist.Segment segment =
        new HlsMediaPlaylist.Segment(
            "https://example.com/segment.ts",
            /* byteRangeOffset= */ 0,
            /* byteRangeLength= */ 100, // Explicit finite length
            /* fullSegmentEncryptionKeyUri= */ null,
            /* encryptionIV= */ null);
    HlsMediaChunk mediaChunk = createChunkWithSegment(extractorFactory, segment);

    ParserException exception = assertThrows(ParserException.class, mediaChunk::load);
    assertThat(exception).hasCauseThat().isInstanceOf(EOFException.class);
  }

  @Test
  public void load_extractorThrowsEOFExceptionBeforeFullyLoadedChunk_propagatesEOFException()
      throws Exception {
    when(mockExtractor.read(any(ExtractorInput.class)))
        .thenAnswer(
            invocation -> {
              ExtractorInput input = invocation.getArgument(0);
              input.skipFully(50); // Premature read (less than segment's 100 length)
              throw new EOFException();
            });
    HlsMediaPlaylist.Segment segment =
        new HlsMediaPlaylist.Segment(
            "https://example.com/segment.ts",
            /* byteRangeOffset= */ 0,
            /* byteRangeLength= */ 100,
            /* fullSegmentEncryptionKeyUri= */ null,
            /* encryptionIV= */ null);
    HlsMediaChunk mediaChunk = createChunkWithSegment(extractorFactory, segment);

    assertThrows(EOFException.class, mediaChunk::load);
  }

  @Test
  public void load_extractorThrowsEOFExceptionForUnsetLengthChunk_propagatesEOFException()
      throws Exception {
    when(mockExtractor.read(any(ExtractorInput.class)))
        .thenAnswer(
            invocation -> {
              throw new EOFException();
            });
    HlsMediaPlaylist.Segment segment =
        new HlsMediaPlaylist.Segment(
            "https://example.com/segment.ts",
            /* byteRangeOffset= */ 0,
            /* byteRangeLength= */ C.LENGTH_UNSET, // Unset length
            /* fullSegmentEncryptionKeyUri= */ null,
            /* encryptionIV= */ null);
    HlsMediaChunk mediaChunk = createChunkWithSegment(extractorFactory, segment);

    assertThrows(EOFException.class, mediaChunk::load);
  }

  private static HlsMediaChunk createChunkWithSegment(
      HlsExtractorFactory extractorFactory, HlsMediaPlaylist.Segment segment) throws IOException {
    HlsMediaPlaylist mediaPlaylist =
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
            /* segments= */ ImmutableList.of(segment),
            /* trailingParts= */ ImmutableList.of(),
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
    fakeDataSource.getDataSet().newDefaultData().appendReadData(100).endData();
    HlsMediaChunk mediaChunk =
        HlsMediaChunk.createInstance(
            extractorFactory,
            fakeDataSource,
            new Format.Builder().build(),
            /* startOfPlaylistInPeriodUs= */ 0,
            mediaPlaylist,
            new HlsChunkSource.SegmentBaseHolder(
                segment, /* mediaSequence= */ 1, /* partIndex= */ 0),
            Uri.parse("https://playlist.uri/"),
            /* steeredPathwayId= */ null,
            /* muxedCaptionFormats= */ null,
            C.SELECTION_REASON_UNKNOWN,
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
    HlsSampleStreamWrapper sampleStreamWrapper =
        new HlsSampleStreamWrapper(
            /* uid= */ "",
            C.TRACK_TYPE_VIDEO,
            mock(HlsSampleStreamWrapper.Callback.class),
            mock(HlsChunkSource.class),
            /* overridingDrmInitData= */ ImmutableMap.of(),
            mock(Allocator.class),
            /* positionUs= */ 0,
            /* muxedAudioFormat= */ null,
            mock(DrmSessionManager.class),
            mock(DrmSessionEventListener.EventDispatcher.class),
            mock(LoadErrorHandlingPolicy.class),
            mock(MediaSourceEventListener.EventDispatcher.class),
            /* metadataType= */ HlsMediaSource.METADATA_TYPE_ID3,
            /* downloadExecutor= */ null);
    mediaChunk.init(sampleStreamWrapper, ImmutableList.of());
    return mediaChunk;
  }
}
