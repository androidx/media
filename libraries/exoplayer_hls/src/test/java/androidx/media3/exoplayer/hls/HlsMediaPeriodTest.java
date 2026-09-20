/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static androidx.media3.common.MimeTypes.APPLICATION_ID3;
import static androidx.media3.common.MimeTypes.AUDIO_AAC;
import static androidx.media3.common.MimeTypes.VIDEO_H264;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.net.Uri;
import android.os.SystemClock;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.TrackGroup;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.drm.DrmSessionEventListener;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist.Rendition;
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist.Variant;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker;
import androidx.media3.exoplayer.hls.playlist.HlsRedundantGroup;
import androidx.media3.exoplayer.source.CompositeSequenceableLoaderFactory;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.exoplayer.source.MediaSourceEventListener;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.test.utils.MediaPeriodAsserts;
import androidx.media3.test.utils.MediaPeriodAsserts.FilterableManifestMediaPeriodFactory;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

/** Unit test for {@link HlsMediaPeriod}. */
@RunWith(AndroidJUnit4.class)
public final class HlsMediaPeriodTest {

  @Test
  public void getSteamKeys_isCompatibleWithHlsMultivariantPlaylistFilter() {
    FilterableManifestMediaPeriodFactory<HlsPlaylist> mediaPeriodFactory =
        (playlist, periodIndex) -> {
          HlsExtractorFactory mockHlsExtractorFactory = mock(HlsExtractorFactory.class);
          when(mockHlsExtractorFactory.getOutputTextFormat(any()))
              .then(invocation -> invocation.getArguments()[0]);
          HlsDataSourceFactory mockDataSourceFactory = mock(HlsDataSourceFactory.class);
          when(mockDataSourceFactory.createDataSource(anyInt())).thenReturn(mock(DataSource.class));
          HlsPlaylistTracker mockPlaylistTracker = mock(HlsPlaylistTracker.class);
          setupPlaylistTracker(mockPlaylistTracker, (HlsMultivariantPlaylist) playlist);
          MediaPeriodId mediaPeriodId = new MediaPeriodId(/* periodUid= */ new Object());
          return new HlsMediaPeriod(
              mockHlsExtractorFactory,
              mockPlaylistTracker,
              mockDataSourceFactory,
              mock(TransferListener.class),
              /* cmcdConfiguration= */ null,
              mock(DrmSessionManager.class),
              new DrmSessionEventListener.EventDispatcher()
                  .withParameters(/* windowIndex= */ 0, mediaPeriodId),
              mock(LoadErrorHandlingPolicy.class),
              new MediaSourceEventListener.EventDispatcher()
                  .withParameters(/* windowIndex= */ 0, mediaPeriodId),
              mock(Allocator.class),
              mock(CompositeSequenceableLoaderFactory.class),
              /* allowChunklessPreparation= */ true,
              HlsMediaSource.METADATA_TYPE_ID3,
              /* useSessionKeys= */ false,
              PlayerId.UNSET,
              /* timestampAdjusterInitializationTimeoutMs= */ 0,
              /* downloadExecutorSupplier= */ null);
        };

    HlsMultivariantPlaylist testMultivariantPlaylist =
        createMultivariantPlaylist(
            /* variants= */ Arrays.asList(
                createAudioOnlyVariant(Uri.parse("https://variant1"), /* peakBitrate= */ 10000),
                createMuxedVideoAudioVariant(
                    Uri.parse("https://variant2"), /* peakBitrate= */ 200000),
                createMuxedVideoAudioVariant(
                    Uri.parse("https://backup/variant2"), /* peakBitrate= */ 200000),
                createAudioOnlyVariant(Uri.parse("https://variant3"), /* peakBitrate= */ 300000),
                createMuxedVideoAudioVariant(
                    Uri.parse("https://variant4"), /* peakBitrate= */ 400000),
                createMuxedVideoAudioVariant(
                    Uri.parse("https://variant5"), /* peakBitrate= */ 600000)),
            /* audios= */ Arrays.asList(
                createAudioRendition(Uri.parse("https://audio1"), /* language= */ "spa"),
                createAudioRendition(Uri.parse("https://backup/audio1"), /* language= */ "spa"),
                createAudioRendition(Uri.parse("https://audio2"), /* language= */ "ger"),
                createAudioRendition(Uri.parse("https://audio3"), /* language= */ "tur")),
            /* subtitles= */ Arrays.asList(
                createSubtitleRendition(Uri.parse("https://subtitle1"), /* language= */ "spa"),
                createSubtitleRendition(
                    Uri.parse("https://backup/subtitle1"), /* language= */ "spa"),
                createSubtitleRendition(Uri.parse("https://subtitle2"), /* language= */ "ger"),
                createSubtitleRendition(Uri.parse("https://subtitle3"), /* language= */ "tur")),
            /* muxedAudioFormat= */ createAudioFormat("eng"),
            /* muxedCaptionFormats= */ Arrays.asList(
                createSubtitleFormat("eng"), createSubtitleFormat("gsw")));
    MediaPeriodAsserts.assertGetStreamKeysAndManifestFilterIntegration(
        mediaPeriodFactory,
        testMultivariantPlaylist,
        /* periodIndex= */ 0,
        /* ignoredMimeType= */ APPLICATION_ID3);

    HlsMultivariantPlaylist testMultivariantPlaylistWithoutSubtitles =
        createMultivariantPlaylist(
            /* variants= */ Arrays.asList(
                createAudioOnlyVariant(Uri.parse("https://variant1"), /* peakBitrate= */ 10000),
                createMuxedVideoAudioVariant(
                    Uri.parse("https://variant2"), /* peakBitrate= */ 200000),
                createAudioOnlyVariant(Uri.parse("https://variant3"), /* peakBitrate= */ 300000),
                createMuxedVideoAudioVariant(
                    Uri.parse("https://variant4"), /* peakBitrate= */ 400000)),
            /* audios= */ Arrays.asList(
                createAudioRendition(Uri.parse("https://audio1"), /* language= */ "spa"),
                createAudioRendition(Uri.parse("https://audio2"), /* language= */ "ger")),
            /* subtitles= */ ImmutableList.of(),
            /* muxedAudioFormat= */ createAudioFormat("eng"),
            /* muxedCaptionFormats= */ Arrays.asList(
                createSubtitleFormat("eng"), createSubtitleFormat("gsw")));
    MediaPeriodAsserts.assertGetStreamKeysAndManifestFilterIntegration(
        mediaPeriodFactory,
        testMultivariantPlaylistWithoutSubtitles,
        /* periodIndex= */ 0,
        /* ignoredMimeType= */ APPLICATION_ID3);
  }

  @Test
  public void onPlaylistError_returnsCorrectResult() {
    HlsMultivariantPlaylist testMultivariantPlaylist =
        createMultivariantPlaylist(
            /* variants= */ Arrays.asList(
                createMuxedVideoAudioVariant(
                    Uri.parse("https://variant1"), /* peakBitrate= */ 400000),
                createMuxedVideoAudioVariant(
                    Uri.parse("https://variant2"), /* peakBitrate= */ 600000)),
            /* audios= */ ImmutableList.of(),
            /* subtitles= */ ImmutableList.of(),
            /* muxedAudioFormat= */ createAudioFormat("eng"),
            /* muxedCaptionFormats= */ ImmutableList.of());
    HlsExtractorFactory mockHlsExtractorFactory = mock(HlsExtractorFactory.class);
    when(mockHlsExtractorFactory.getOutputTextFormat(any()))
        .then(invocation -> invocation.getArguments()[0]);
    HlsDataSourceFactory mockDataSourceFactory = mock(HlsDataSourceFactory.class);
    when(mockDataSourceFactory.createDataSource(anyInt())).thenReturn(mock(DataSource.class));
    HlsPlaylistTracker mockPlaylistTracker = mock(HlsPlaylistTracker.class);
    setupPlaylistTracker(mockPlaylistTracker, testMultivariantPlaylist);
    when(mockPlaylistTracker.excludeMediaPlaylist(any(), anyLong())).thenReturn(true);
    LoadErrorHandlingPolicy mockLoadErrorHandlingPolicy = mock(LoadErrorHandlingPolicy.class);
    MediaPeriodId mediaPeriodId = new MediaPeriodId(/* periodUid= */ new Object());
    HlsMediaPeriod mediaPeriod =
        new HlsMediaPeriod(
            mockHlsExtractorFactory,
            mockPlaylistTracker,
            mockDataSourceFactory,
            mock(TransferListener.class),
            /* cmcdConfiguration= */ null,
            mock(DrmSessionManager.class),
            new DrmSessionEventListener.EventDispatcher()
                .withParameters(/* windowIndex= */ 0, mediaPeriodId),
            mockLoadErrorHandlingPolicy,
            new MediaSourceEventListener.EventDispatcher()
                .withParameters(/* windowIndex= */ 0, mediaPeriodId),
            mock(Allocator.class),
            mock(CompositeSequenceableLoaderFactory.class),
            /* allowChunklessPreparation= */ true,
            HlsMediaSource.METADATA_TYPE_ID3,
            /* useSessionKeys= */ false,
            PlayerId.UNSET,
            /* timestampAdjusterInitializationTimeoutMs= */ 0,
            /* downloadExecutorSupplier= */ null);
    TrackGroupArray expectedGroups =
        new TrackGroupArray(
            new TrackGroup(
                "main",
                new Format.Builder()
                    .setContainerMimeType(MimeTypes.APPLICATION_M3U8)
                    .setCodecs("avc1.100.41")
                    .setSampleMimeType(VIDEO_H264)
                    .setPeakBitrate(400000)
                    .build(),
                new Format.Builder()
                    .setContainerMimeType(MimeTypes.APPLICATION_M3U8)
                    .setCodecs("avc1.100.41")
                    .setSampleMimeType(VIDEO_H264)
                    .setPeakBitrate(600000)
                    .build()),
            new TrackGroup(
                "main:audio",
                new Format.Builder()
                    .setContainerMimeType(MimeTypes.APPLICATION_M3U8)
                    .setCodecs("mp4a.40.2")
                    .setSampleMimeType(AUDIO_AAC)
                    .setLanguage("en")
                    .setPrimaryTrackGroupId("main")
                    .build()),
            new TrackGroup(
                "main:id3",
                new Format.Builder()
                    .setId("ID3")
                    .setSampleMimeType(APPLICATION_ID3)
                    .setPrimaryTrackGroupId("main")
                    .build()));
    MediaPeriodAsserts.assertTrackGroups(mediaPeriod, expectedGroups);

    Uri failedPlaylistUrl = Uri.parse("https://variant1");
    when(mockLoadErrorHandlingPolicy.getFallbackSelectionFor(any(), any()))
        .thenReturn(
            new LoadErrorHandlingPolicy.FallbackSelection(
                LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK, /* exclusionDurationMs= */ 10_000));
    assertThat(
            mediaPeriod.onPlaylistError(
                failedPlaylistUrl,
                createFakeLoadErrorInfo(
                    new DataSpec(failedPlaylistUrl),
                    /* httpResponseCode= */ 404,
                    /* errorCount= */ 1),
                /* forceRetry= */ false))
        .isTrue();

    // Pass a playlistUrl which has no corresponding chunk source.
    failedPlaylistUrl = Uri.parse("https://variant3");
    when(mockLoadErrorHandlingPolicy.getFallbackSelectionFor(any(), any()))
        .thenReturn(
            new LoadErrorHandlingPolicy.FallbackSelection(
                LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK, /* exclusionDurationMs= */ 10_000));
    assertThat(
            mediaPeriod.onPlaylistError(
                failedPlaylistUrl,
                createFakeLoadErrorInfo(
                    new DataSpec(failedPlaylistUrl),
                    /* httpResponseCode= */ 404,
                    /* errorCount= */ 1),
                /* forceRetry= */ false))
        .isFalse();
  }

  @Test
  public void prepare_withVariantsAndRenditions_preservesFormatIdAndMetadata() {
    Metadata variantMetadata1 =
        new Metadata(
            new HlsTrackMetadataEntry(
                /* groupId= */ null, /* name= */ null, /* variantInfos= */ ImmutableList.of()));
    Metadata variantMetadata2 =
        new Metadata(
            new HlsTrackMetadataEntry(
                /* groupId= */ "backup", /* name= */ null, /* variantInfos= */ ImmutableList.of()));
    Metadata audioMetadata =
        new Metadata(
            new HlsTrackMetadataEntry(
                /* groupId= */ "audio",
                /* name= */ "English",
                /* variantInfos= */ ImmutableList.of()));
    Metadata subtitleMetadata =
        new Metadata(
            new HlsTrackMetadataEntry(
                /* groupId= */ "sub",
                /* name= */ "English",
                /* variantInfos= */ ImmutableList.of()));
    HlsMultivariantPlaylist multivariantPlaylist =
        createMultivariantPlaylist(
            /* variants= */ ImmutableList.of(
                createMuxedVideoAudioVariant(
                    Uri.parse("https://variant1"),
                    /* peakBitrate= */ 400000,
                    /* id= */ "0",
                    variantMetadata1),
                createMuxedVideoAudioVariant(
                    Uri.parse("https://backup/variant1"),
                    /* peakBitrate= */ 400000,
                    /* id= */ "1",
                    variantMetadata2)),
            /* audios= */ ImmutableList.of(
                createAudioRendition(
                    Uri.parse("https://audio1"),
                    /* language= */ "en",
                    /* id= */ "audio:English",
                    audioMetadata,
                    /* groupId= */ "audio",
                    /* name= */ "English")),
            /* subtitles= */ ImmutableList.of(
                createSubtitleRendition(
                    Uri.parse("https://subtitle1"),
                    /* language= */ "en",
                    /* id= */ "sub:English",
                    subtitleMetadata,
                    /* groupId= */ "sub",
                    /* name= */ "English")),
            /* muxedAudioFormat= */ null,
            /* muxedCaptionFormats= */ ImmutableList.of());

    HlsMediaPeriod mediaPeriod = createMediaPeriod(multivariantPlaylist);

    TrackGroupArray expectedGroups =
        new TrackGroupArray(
            new TrackGroup(
                "main",
                new Format.Builder()
                    .setId("0")
                    .setContainerMimeType(MimeTypes.APPLICATION_M3U8)
                    .setCodecs("avc1.100.41")
                    .setSampleMimeType(VIDEO_H264)
                    .setPeakBitrate(400000)
                    .setMetadata(variantMetadata1)
                    .build()),
            new TrackGroup(
                "main:id3",
                new Format.Builder()
                    .setId("ID3")
                    .setSampleMimeType(APPLICATION_ID3)
                    .setPrimaryTrackGroupId("main")
                    .build()),
            new TrackGroup(
                "audio:English",
                createAudioFormat("en")
                    .buildUpon()
                    .setId("audio:English")
                    .setMetadata(audioMetadata)
                    .build()),
            new TrackGroup(
                "audio:English:id3",
                new Format.Builder()
                    .setId("ID3")
                    .setSampleMimeType(APPLICATION_ID3)
                    .setPrimaryTrackGroupId("audio:English")
                    .build()),
            new TrackGroup(
                "subtitle:English",
                createSubtitleFormat("en")
                    .buildUpon()
                    .setId("sub:English")
                    .setMetadata(subtitleMetadata)
                    .build()));
    MediaPeriodAsserts.assertTrackGroups(mediaPeriod, expectedGroups);
  }

  private static HlsMediaPeriod createMediaPeriod(HlsMultivariantPlaylist multivariantPlaylist) {
    HlsExtractorFactory mockHlsExtractorFactory = mock(HlsExtractorFactory.class);
    when(mockHlsExtractorFactory.getOutputTextFormat(any()))
        .then(invocation -> invocation.getArguments()[0]);
    HlsDataSourceFactory mockDataSourceFactory = mock(HlsDataSourceFactory.class);
    when(mockDataSourceFactory.createDataSource(anyInt())).thenReturn(mock(DataSource.class));
    HlsPlaylistTracker mockPlaylistTracker = mock(HlsPlaylistTracker.class);
    setupPlaylistTracker(mockPlaylistTracker, multivariantPlaylist);
    MediaPeriodId mediaPeriodId = new MediaPeriodId(/* periodUid= */ new Object());
    return new HlsMediaPeriod(
        mockHlsExtractorFactory,
        mockPlaylistTracker,
        mockDataSourceFactory,
        mock(TransferListener.class),
        /* cmcdConfiguration= */ null,
        mock(DrmSessionManager.class),
        new DrmSessionEventListener.EventDispatcher()
            .withParameters(/* windowIndex= */ 0, mediaPeriodId),
        mock(LoadErrorHandlingPolicy.class),
        new MediaSourceEventListener.EventDispatcher()
            .withParameters(/* windowIndex= */ 0, mediaPeriodId),
        mock(Allocator.class),
        mock(CompositeSequenceableLoaderFactory.class),
        /* allowChunklessPreparation= */ true,
        HlsMediaSource.METADATA_TYPE_ID3,
        /* useSessionKeys= */ false,
        PlayerId.UNSET,
        /* timestampAdjusterInitializationTimeoutMs= */ 0,
        /* downloadExecutorSupplier= */ null);
  }

  private static HlsMultivariantPlaylist createMultivariantPlaylist(
      List<Variant> variants,
      List<Rendition> audios,
      List<Rendition> subtitles,
      Format muxedAudioFormat,
      List<Format> muxedCaptionFormats) {
    return new HlsMultivariantPlaylist(
        "http://baseUri",
        /* tags= */ Collections.emptyList(),
        variants,
        /* videos= */ Collections.emptyList(),
        audios,
        subtitles,
        /* closedCaptions= */ Collections.emptyList(),
        muxedAudioFormat,
        muxedCaptionFormats,
        /* hasIndependentSegments= */ true,
        /* variableDefinitions= */ Collections.emptyMap(),
        /* sessionKeyDrmInitData= */ Collections.emptyList(),
        /* contentSteeringInfo= */ null);
  }

  private static Variant createMuxedVideoAudioVariant(Uri url, int peakBitrate) {
    return createMuxedVideoAudioVariant(url, peakBitrate, /* id= */ null, /* metadata= */ null);
  }

  private static Variant createMuxedVideoAudioVariant(
      Uri url, int peakBitrate, @Nullable String id, @Nullable Metadata metadata) {
    return createVariant(
        url,
        new Format.Builder()
            .setId(id)
            .setContainerMimeType(MimeTypes.APPLICATION_M3U8)
            .setCodecs("avc1.100.41,mp4a.40.2")
            .setPeakBitrate(peakBitrate)
            .setMetadata(metadata)
            .build());
  }

  private static Variant createAudioOnlyVariant(Uri url, int peakBitrate) {
    return createVariant(
        url,
        new Format.Builder()
            .setContainerMimeType(MimeTypes.APPLICATION_M3U8)
            .setCodecs("mp4a.40.2")
            .setPeakBitrate(peakBitrate)
            .build());
  }

  private static Rendition createAudioRendition(Uri url, String language) {
    return createAudioRendition(
        url, language, /* id= */ null, /* metadata= */ null, /* groupId= */ "", /* name= */ "");
  }

  private static Rendition createAudioRendition(
      Uri url,
      String language,
      @Nullable String id,
      @Nullable Metadata metadata,
      String groupId,
      String name) {
    return createRendition(
        url,
        createAudioFormat(language).buildUpon().setId(id).setMetadata(metadata).build(),
        groupId,
        name);
  }

  private static Rendition createSubtitleRendition(Uri url, String language) {
    return createSubtitleRendition(
        url, language, /* id= */ null, /* metadata= */ null, /* groupId= */ "", /* name= */ "");
  }

  private static Rendition createSubtitleRendition(
      Uri url,
      String language,
      @Nullable String id,
      @Nullable Metadata metadata,
      String groupId,
      String name) {
    return createRendition(
        url,
        createSubtitleFormat(language).buildUpon().setId(id).setMetadata(metadata).build(),
        groupId,
        name);
  }

  private static Variant createVariant(Uri url, Format format) {
    return new Variant(
        url,
        format,
        /* videoGroupId= */ null,
        /* audioGroupId= */ null,
        /* subtitleGroupId= */ null,
        /* captionGroupId= */ null,
        /* pathwayId= */ null,
        /* stableVariantId= */ null);
  }

  private static Rendition createRendition(Uri url, Format format, String groupId, String name) {
    return new Rendition(url, format, groupId, name, /* stableRenditionId= */ null);
  }

  private static Format createAudioFormat(String language) {
    return new Format.Builder()
        .setContainerMimeType(MimeTypes.APPLICATION_M3U8)
        .setSampleMimeType(MimeTypes.getMediaMimeType("mp4a.40.2"))
        .setCodecs("mp4a.40.2")
        .setLanguage(language)
        .build();
  }

  private static Format createSubtitleFormat(String language) {
    return new Format.Builder()
        .setContainerMimeType(MimeTypes.APPLICATION_M3U8)
        .setSampleMimeType(MimeTypes.TEXT_VTT)
        .setLanguage(language)
        .build();
  }

  private LoadErrorHandlingPolicy.LoadErrorInfo createFakeLoadErrorInfo(
      DataSpec dataSpec, int httpResponseCode, int errorCount) {
    LoadEventInfo loadEventInfo =
        new LoadEventInfo.Builder(/* loadTaskId= */ 0, dataSpec, SystemClock.elapsedRealtime())
            .build();
    MediaLoadData mediaLoadData = new MediaLoadData(C.DATA_TYPE_MEDIA);
    HttpDataSource.InvalidResponseCodeException invalidResponseCodeException =
        new HttpDataSource.InvalidResponseCodeException(
            httpResponseCode,
            /* responseMessage= */ null,
            /* cause= */ null,
            ImmutableMap.of(),
            dataSpec,
            new byte[0]);
    return new LoadErrorHandlingPolicy.LoadErrorInfo(
        loadEventInfo, mediaLoadData, invalidResponseCodeException, errorCount);
  }

  private static void setupPlaylistTracker(
      @Mock HlsPlaylistTracker mockPlaylistTracker, HlsMultivariantPlaylist multivariantPlaylist) {
    when(mockPlaylistTracker.getMultivariantPlaylist()).thenReturn(multivariantPlaylist);
    HlsRedundantGroup.Factory redundantGroupFactory =
        new HlsRedundantGroup.Factory(multivariantPlaylist);
    try {
      ImmutableList<HlsRedundantGroup> variantRedundantGroups =
          redundantGroupFactory.createVariantRedundantGroupList();
      ImmutableList<HlsRedundantGroup> videoRedundantGroups =
          redundantGroupFactory.createRenditionRedundantGroupList(
              HlsRedundantGroup.VIDEO_RENDITION);
      ImmutableList<HlsRedundantGroup> audioRedundantGroups =
          redundantGroupFactory.createRenditionRedundantGroupList(
              HlsRedundantGroup.AUDIO_RENDITION);
      ImmutableList<HlsRedundantGroup> subtitleRedundantGroups =
          redundantGroupFactory.createRenditionRedundantGroupList(
              HlsRedundantGroup.SUBTITLE_RENDITION);
      for (HlsRedundantGroup redundantGroup : variantRedundantGroups) {
        for (Uri url : redundantGroup.getAllPlaylistUrls()) {
          when(mockPlaylistTracker.getRedundantGroup(url)).thenReturn(redundantGroup);
        }
      }
      for (HlsRedundantGroup redundantGroup : videoRedundantGroups) {
        for (Uri url : redundantGroup.getAllPlaylistUrls()) {
          when(mockPlaylistTracker.getRedundantGroup(url)).thenReturn(redundantGroup);
        }
      }
      for (HlsRedundantGroup redundantGroup : audioRedundantGroups) {
        for (Uri url : redundantGroup.getAllPlaylistUrls()) {
          when(mockPlaylistTracker.getRedundantGroup(url)).thenReturn(redundantGroup);
        }
      }
      for (HlsRedundantGroup redundantGroup : subtitleRedundantGroups) {
        for (Uri url : redundantGroup.getAllPlaylistUrls()) {
          when(mockPlaylistTracker.getRedundantGroup(url)).thenReturn(redundantGroup);
        }
      }
      when(mockPlaylistTracker.getRedundantGroups(HlsRedundantGroup.VARIANT))
          .thenReturn(variantRedundantGroups);
      when(mockPlaylistTracker.getRedundantGroups(HlsRedundantGroup.VIDEO_RENDITION))
          .thenReturn(videoRedundantGroups);
      when(mockPlaylistTracker.getRedundantGroups(HlsRedundantGroup.AUDIO_RENDITION))
          .thenReturn(audioRedundantGroups);
      when(mockPlaylistTracker.getRedundantGroups(HlsRedundantGroup.SUBTITLE_RENDITION))
          .thenReturn(subtitleRedundantGroups);
    } catch (Exception e) {
      throw new IllegalStateException("Error in creating redundant group list", e);
    }
  }
}
