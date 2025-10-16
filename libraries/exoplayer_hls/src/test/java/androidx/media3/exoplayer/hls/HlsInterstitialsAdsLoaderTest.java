/*
 * Copyright 2024 The Android Open Source Project
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

import static androidx.media3.common.Player.DISCONTINUITY_REASON_AUTO_TRANSITION;
import static androidx.media3.common.Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED;
import static androidx.media3.common.util.Util.msToUs;
import static androidx.media3.exoplayer.hls.HlsInterstitialsAdsLoader.getClosestSegmentBoundaryUs;
import static androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNotNull;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.Uri;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.AdPlaybackState.SkipInfo;
import androidx.media3.common.AdViewProvider;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.ByteArrayDataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.PlayerMessage;
import androidx.media3.exoplayer.hls.HlsInterstitialsAdsLoader.AdsResumptionState;
import androidx.media3.exoplayer.hls.HlsInterstitialsAdsLoader.Asset;
import androidx.media3.exoplayer.hls.HlsInterstitialsAdsLoader.AssetList;
import androidx.media3.exoplayer.hls.HlsInterstitialsAdsLoader.Listener;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.ads.AdsLoader;
import androidx.media3.exoplayer.source.ads.AdsMediaSource;
import androidx.media3.test.utils.FakeMediaSource;
import androidx.media3.test.utils.FakeTimeline;
import androidx.media3.test.utils.FakeTimeline.TimelineWindowDefinition;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link HlsInterstitialsAdsLoaderTest}. */
@SuppressWarnings({"DataFlowIssue", "TextBlockMigration", "EnhancedSwitchMigration"})
@RunWith(AndroidJUnit4.class)
public class HlsInterstitialsAdsLoaderTest {

  private static final long TIMEOUT_MS = 1_000L;

  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  @Mock private HlsInterstitialsAdsLoader.Listener mockAdsLoaderListener;
  @Mock private AdsLoader.EventListener mockEventListener;
  @Mock private ExoPlayer mockPlayer;
  @Mock private AdViewProvider mockAdViewProvider;

  private HlsInterstitialsAdsLoader adsLoader;
  private AssetListLoadingListener assetListLoadingListener;
  private MediaItem.AdsConfiguration adsConfiguration;
  private MediaItem contentMediaItem;
  private TimelineWindowDefinition contentWindowDefinition;
  private DataSpec adTagDataSpec;
  private AdsMediaSource adsMediaSource;

  @Before
  public void setUp() {
    adsLoader =
        new HlsInterstitialsAdsLoader(
            /* dataSourceFactory= */ () ->
                new ByteArrayDataSource(
                    uri -> {
                      switch (uri.toString()) {
                        case "http://invalid":
                          return "]".getBytes(Charset.defaultCharset());
                        case "http://empty":
                          return getJsonAssetList(/* assetCount= */ 0, /* delayMs= */ 0);
                        case "http://three-assets":
                          return getJsonAssetList(/* assetCount= */ 3, /* delayMs= */ 0);
                        case "http://three-assets-skip-info":
                          return getJsonAssetListWithSkipInformation(
                              /* assetCount= */ 3,
                              /* skipInfoOffsetSeconds= */ 5.5f,
                              /* skipInfoDurationSeconds= */ 6.5f,
                              /* skipInfoLabelId= */ null);
                        case "http://three-assets-skip-info-label-only":
                          return getJsonAssetListWithSkipInformation(
                              /* assetCount= */ 3,
                              /* skipInfoOffsetSeconds= */ null,
                              /* skipInfoDurationSeconds= */ null,
                              /* skipInfoLabelId= */ "skip_label_from_json");
                        case "http://slow_loading":
                          return getJsonAssetList(/* assetCount= */ 1, /* delayMs= */ 150);
                        default:
                          return getJsonAssetList(/* assetCount= */ 1, /* delayMs= */ 0);
                      }
                    }));
    adsLoader.addListener(mockAdsLoaderListener);
    assetListLoadingListener = new AssetListLoadingListener();
    adsLoader.addListener(assetListLoadingListener);
    adsConfiguration = new MediaItem.AdsConfiguration.Builder(Uri.EMPTY).setAdsId("adsId").build();
    // The HLS media item to play.
    contentMediaItem =
        new MediaItem.Builder()
            .setUri("http://example.com/media.m3u8")
            .setAdsConfiguration(adsConfiguration)
            .build();
    // The content timeline with AdPlaybackState.NONE.
    contentWindowDefinition =
        new TimelineWindowDefinition.Builder()
            .setDurationUs(90_000_000L)
            .setWindowPositionInFirstPeriodUs(0L)
            .setMediaItem(contentMediaItem)
            .build();
    adTagDataSpec = new DataSpec(Uri.EMPTY);
    // The ads media source using the ads loader.
    adsMediaSource =
        (AdsMediaSource)
            new HlsInterstitialsAdsLoader.AdsMediaSourceFactory(
                    adsLoader,
                    mockAdViewProvider,
                    (Context) ApplicationProvider.getApplicationContext())
                .createMediaSource(contentMediaItem);
  }

  @Test
  public void setSupportedContentTypes_hlsNotSupported_throwsIllegalArgumentException() {
    assertThrows(
        IllegalArgumentException.class,
        () -> adsLoader.setSupportedContentTypes(C.CONTENT_TYPE_DASH));
  }

  @Test
  public void start_playerNotSet_throwsException() {
    assertThrows(
        NullPointerException.class,
        () ->
            adsLoader.start(
                adsMediaSource, adTagDataSpec, "adsId", mockAdViewProvider, mockEventListener));
  }

  @Test
  public void start_nonHlsMediaItem_emptyAdPlaybackState() {
    MediaItem mp4MediaItem =
        new MediaItem.Builder()
            .setUri("http:///example.com/media.mp4")
            .setAdsConfiguration(
                new MediaItem.AdsConfiguration.Builder(Uri.EMPTY).setAdsId("adsId").build())
            .build();
    DefaultMediaSourceFactory defaultMediaSourceFactory =
        new DefaultMediaSourceFactory((Context) ApplicationProvider.getApplicationContext());
    AdsMediaSource adsMediaSource =
        new AdsMediaSource(
            defaultMediaSourceFactory.createMediaSource(mp4MediaItem),
            new DataSpec(Uri.EMPTY),
            "adsId",
            defaultMediaSourceFactory,
            adsLoader,
            mockAdViewProvider);
    when(mockPlayer.getCurrentTimeline())
        .thenReturn(
            new FakeTimeline(
                new TimelineWindowDefinition.Builder()
                    .setDynamic(true)
                    .setDurationUs(C.TIME_UNSET)
                    .setMediaItem(mp4MediaItem)
                    .build()));
    adsLoader.setPlayer(mockPlayer);

    adsLoader.start(adsMediaSource, adTagDataSpec, "adsId", mockAdViewProvider, mockEventListener);

    verify(mockEventListener).onAdPlaybackState(new AdPlaybackState("adsId"));
  }

  @Test
  public void start_twiceWithIdenticalAdsId_throwIllegalStateException() {
    when(mockPlayer.getCurrentTimeline()).thenReturn(new FakeTimeline(contentWindowDefinition));
    adsLoader.setPlayer(mockPlayer);
    adsLoader.start(adsMediaSource, adTagDataSpec, "adsId", mockAdViewProvider, mockEventListener);
    verify(mockAdsLoaderListener)
        .onStart(contentMediaItem, adsMediaSource.getAdsId(), mockAdViewProvider);

    assertThrows(
        IllegalStateException.class,
        () ->
            adsLoader.start(
                adsMediaSource, adTagDataSpec, "adsId", mockAdViewProvider, mockEventListener));

    verifyNoMoreInteractions(mockAdsLoaderListener);
  }

  @Test
  public void start_twiceWithIdenticalUnsupportedAdsId_throwIllegalStateException() {
    MediaItem mp4MediaItem =
        new MediaItem.Builder()
            .setUri("http:///example.com/media.mp4")
            .setAdsConfiguration(
                new MediaItem.AdsConfiguration.Builder(Uri.EMPTY).setAdsId("adsId").build())
            .build();
    DefaultMediaSourceFactory defaultMediaSourceFactory =
        new DefaultMediaSourceFactory((Context) ApplicationProvider.getApplicationContext());
    AdsMediaSource adsMediaSource =
        new AdsMediaSource(
            defaultMediaSourceFactory.createMediaSource(mp4MediaItem),
            new DataSpec(Uri.EMPTY),
            "adsId",
            defaultMediaSourceFactory,
            adsLoader,
            mockAdViewProvider);
    when(mockPlayer.getCurrentTimeline())
        .thenReturn(
            new FakeTimeline(
                new TimelineWindowDefinition.Builder()
                    .setDynamic(true)
                    .setDurationUs(C.TIME_UNSET)
                    .setMediaItem(mp4MediaItem)
                    .build()));
    adsLoader.setPlayer(mockPlayer);
    adsLoader.start(adsMediaSource, adTagDataSpec, "adsId", mockAdViewProvider, mockEventListener);

    assertThrows(
        IllegalStateException.class,
        () ->
            adsLoader.start(
                adsMediaSource, adTagDataSpec, "adsId", mockAdViewProvider, mockEventListener));

    verifyNoMoreInteractions(mockAdsLoaderListener);
  }

  @Test
  public void start_resumptionStateAvailable_resumptionStateUsedAndEventListenerCalled() {
    AdPlaybackState adPlaybackState =
        new AdPlaybackState("adsId", /* adGroupTimesUs...= */ 0, 10L, C.TIME_END_OF_SOURCE)
            .withAdCount(/* adGroupIndex= */ 0, 1)
            .withAdCount(/* adGroupIndex= */ 1, 2)
            .withAdCount(/* adGroupIndex= */ 2, 3)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 0,
                MediaItem.fromUri("http://example.com"));
    adsLoader.addAdResumptionState(new AdsResumptionState("adsId", adPlaybackState));
    adsLoader.setPlayer(mockPlayer);

    adsLoader.start(adsMediaSource, adTagDataSpec, "adsId", mockAdViewProvider, mockEventListener);
    adsLoader.stop(adsMediaSource, mockEventListener);

    ArgumentCaptor<AdPlaybackState> adPlaybackStateArgumentCaptor =
        ArgumentCaptor.forClass(AdPlaybackState.class);
    verify(mockEventListener).onAdPlaybackState(adPlaybackStateArgumentCaptor.capture());
    verify(mockAdsLoaderListener)
        .onStop(any(), eq("adsId"), adPlaybackStateArgumentCaptor.capture());
    assertThat(adPlaybackStateArgumentCaptor.getAllValues())
        .containsExactly(adPlaybackState, adPlaybackState);
    verify(mockAdsLoaderListener).onStart(eq(contentMediaItem), eq("adsId"), isNotNull());
    assertThat(adsLoader.removeAdResumptionState("adsId")).isFalse();
  }

  @Test
  public void addAdResumptionState_whileAdsIdIsActive_ignored() throws IOException {
    callHandleContentTimelineChangedAndCaptureAdPlaybackState(
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXTINF:6,\n"
            + "main2.0.ts\n"
            + "#EXTINF:6,\n"
            + "main3.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "CUE=\"PRE\","
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
            + "\n",
        adsLoader,
        /* windowIndex= */ 0,
        /* windowPositionInPeriodUs= */ 0,
        /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE);

    adsLoader.addAdResumptionState(new AdsResumptionState("adsId", new AdPlaybackState("adsId")));

    assertThat(adsLoader.getAdsResumptionStates())
        .containsExactly(
            new AdsResumptionState(
                "adsId",
                new AdPlaybackState("adsId", 0L)
                    .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
                    .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, "ad0-0")
                    .withAvailableAdMediaItem(
                        /* adGroupIndex= */ 0,
                        /* adIndexInAdGroup= */ 0,
                        new MediaItem.Builder()
                            .setUri("http://example.com/media-0-0.m3u8")
                            .setMimeType("application/x-mpegURL")
                            .build())));
    assertThat(adsLoader.removeAdResumptionState("adsId")).isFalse();
  }

  @Test
  public void addAdResumptionState_withLivePostRollHolder_throwsIllegalArgumentException() {
    AdsResumptionState adsResumptionState =
        new AdsResumptionState(
            "adsId",
            new AdPlaybackState("adsId")
                .withLivePostrollPlaceholderAppended(/* isServerSideInserted= */ false));

    assertThrows(
        IllegalArgumentException.class, () -> adsLoader.addAdResumptionState(adsResumptionState));
  }

  @Test
  public void getAdsResumptionStates_withLivePostRollPlaceholder_ignored() throws IOException {
    List<AdPlaybackState> adPlaybackStates =
        callHandleContentTimelineChangedForLiveAndCaptureAdPlaybackStates(
            adsLoader,
            /* startAdsLoader= */ true,
            /* windowOffsetInFirstPeriodUs= */ 0L,
            "#EXTM3U\n"
                + "#EXT-X-TARGETDURATION:6\n"
                + "#EXT-X-MEDIA-SEQUENCE:0\n"
                + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:00:00.000Z\n"
                + "#EXTINF:6,\nmain0.0.ts\n"
                + "#EXTINF:6,\nmain1.0.ts\n"
                + "#EXTINF:6,\nmain2.0.ts\n"
                + "#EXTINF:6,\nmain3.0.ts\n"
                + "#EXTINF:6,\nmain4.0.ts\n"
                + "\n");

    // active ad playback state with live post roll is ignored.
    assertThat(adsLoader.getAdsResumptionStates()).isEmpty();

    // Stop to verify that there was an active ad playback state when calling getAdResumptionStates.
    adsLoader.stop(adsMediaSource, mockEventListener);
    ArgumentCaptor<AdPlaybackState> adPlaybackState =
        ArgumentCaptor.forClass(AdPlaybackState.class);
    verify(mockAdsLoaderListener).onStop(any(), eq("adsId"), adPlaybackState.capture());
    assertThat(adPlaybackState.getAllValues()).isEqualTo(adPlaybackStates);
  }

  @Test
  public void getAdsResumptionStates_returnsResumptionStateOfActiveAdsIds() throws IOException {
    String secondPlaylistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXTINF:6,\n"
            + "main2.0.ts\n"
            + "#EXTINF:6,\n"
            + "main3.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad1-1\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "CUE=\"POST\","
            + "X-ASSET-URI=\"http://example.com/media-1-1.m3u8\""
            + "\n";
    HlsMediaPlaylist secondMediaPlaylist =
        (HlsMediaPlaylist)
            new HlsPlaylistParser()
                .parse(
                    Uri.EMPTY, new ByteArrayInputStream(Util.getUtf8Bytes(secondPlaylistString)));
    HlsManifest secondHlsManifest =
        new HlsManifest(/* multivariantPlaylist= */ null, secondMediaPlaylist);
    TimelineWindowDefinition secondInitialTimelineWindowDefinition =
        new TimelineWindowDefinition.Builder()
            .setPlaceholder(true)
            .setDynamic(true)
            .setDurationUs(C.TIME_UNSET)
            .setWindowPositionInFirstPeriodUs(0)
            .setMediaItem(MediaItem.fromUri("http://example.com/2.m3u8"))
            .build();
    AdsMediaSource secondAdsMediaSource =
        new AdsMediaSource(
            new FakeMediaSource(new FakeTimeline(secondInitialTimelineWindowDefinition)),
            new DataSpec(secondInitialTimelineWindowDefinition.mediaItem.localConfiguration.uri),
            "adsId2",
            new DefaultMediaSourceFactory((Context) ApplicationProvider.getApplicationContext()),
            adsLoader,
            mockAdViewProvider);
    AdsResumptionState firstAdsResumptionState =
        new AdsResumptionState(
            "adsId",
            new AdPlaybackState("adsId", 0L)
                .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
                .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, "ad0-0")
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-0-0.m3u8")
                        .setMimeType("application/x-mpegURL")
                        .build()));
    AdsResumptionState secondAdsResumptionState =
        new AdsResumptionState(
            "adsId2",
            new AdPlaybackState("adsId2", C.TIME_END_OF_SOURCE)
                .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
                .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, "ad1-1")
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-1-1.m3u8")
                        .setMimeType("application/x-mpegURL")
                        .build()));

    // Start the first adsId with a pre roll.
    callHandleContentTimelineChangedAndCaptureAdPlaybackState(
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXTINF:6,\n"
            + "main2.0.ts\n"
            + "#EXTINF:6,\n"
            + "main3.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "CUE=\"PRE\","
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
            + "\n",
        adsLoader,
        /* windowIndex= */ 0,
        /* windowPositionInPeriodUs= */ 0,
        /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE);

    assertThat(adsLoader.getAdsResumptionStates()).containsExactly(firstAdsResumptionState);

    // Start a second adsId with a post roll.
    adsLoader.start(
        secondAdsMediaSource,
        new DataSpec(Uri.EMPTY),
        "adsId2",
        mockAdViewProvider,
        mockEventListener);
    adsLoader.handleContentTimelineChanged(
        secondAdsMediaSource,
        new FakeTimeline(
            new Object[] {secondHlsManifest},
            secondInitialTimelineWindowDefinition
                .buildUpon()
                .setDurationUs(secondMediaPlaylist.durationUs)
                .setDynamic(false)
                .setPlaceholder(false)
                .build()));

    assertThat(adsLoader.getAdsResumptionStates())
        .containsExactly(firstAdsResumptionState, secondAdsResumptionState);

    // Stop the first ads media source.
    adsLoader.stop(adsMediaSource, mockEventListener);

    assertThat(adsLoader.getAdsResumptionStates()).containsExactly(secondAdsResumptionState);

    // Stop the second ads media source.
    adsLoader.stop(secondAdsMediaSource, mockEventListener);

    assertThat(adsLoader.getAdsResumptionStates()).isEmpty();
  }

  @Test
  public void removeAdResumptionState_removesAvailableResumptionState() {
    AdsResumptionState adsResumptionState =
        new AdsResumptionState("adsId", new AdPlaybackState("adsId"));
    adsLoader.addAdResumptionState(adsResumptionState);

    assertThat(adsLoader.removeAdResumptionState("adsId")).isTrue();
    assertThat(adsLoader.removeAdResumptionState("adsId")).isFalse();
  }

  @Test
  public void clearAllAdResumptionStates_removesAvailableResumptionState() {
    adsLoader.addAdResumptionState(new AdsResumptionState("adsId", new AdPlaybackState("adsId")));
    adsLoader.addAdResumptionState("adsId2", new AdPlaybackState("adsId2"));

    adsLoader.clearAllAdResumptionStates();

    assertThat(adsLoader.removeAdResumptionState("adsId")).isFalse();
  }

  @Test
  public void handleContentTimelineChanged_preMidAndPostRolls_translatedToAdPlaybackState()
      throws IOException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXTINF:6,\n"
            + "main2.0.ts\n"
            + "#EXTINF:6,\n"
            + "main3.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "CUE=\"PRE\","
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad1-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:55.000Z\","
            + "X-ASSET-URI=\"http://example.com/media-1-0.m3u8\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad2-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "CUE=\"POST\","
            + "X-ASSET-URI=\"http://example.com/media-2-0.m3u8\"\n";

    AdPlaybackState actual =
        callHandleContentTimelineChangedAndCaptureAdPlaybackState(
            playlistString,
            adsLoader,
            /* windowIndex= */ 1,
            /* windowPositionInPeriodUs= */ 0,
            /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE);
    AdPlaybackState expected =
        new AdPlaybackState("adsId", 0L, 15_000_000L, C.TIME_END_OF_SOURCE)
            .withAdDurationsUs(/* adGroupIndex= */ 0, C.TIME_UNSET)
            .withAdDurationsUs(/* adGroupIndex= */ 1, C.TIME_UNSET)
            .withAdDurationsUs(/* adGroupIndex= */ 2, C.TIME_UNSET)
            .withAdCount(/* adGroupIndex= */ 0, 1)
            .withAdCount(/* adGroupIndex= */ 1, 1)
            .withAdCount(/* adGroupIndex= */ 2, 1)
            .withContentResumeOffsetUs(/* adGroupIndex= */ 0, 0L)
            .withContentResumeOffsetUs(/* adGroupIndex= */ 1, 0L)
            .withContentResumeOffsetUs(/* adGroupIndex= */ 2, 0L)
            .withAdId(0, 0, "ad0-0")
            .withAdId(1, 0, "ad1-0")
            .withAdId(2, 0, "ad2-0")
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 0,
                new MediaItem.Builder()
                    .setUri("http://example.com/media-0-0.m3u8")
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build())
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 1,
                /* adIndexInAdGroup= */ 0,
                new MediaItem.Builder()
                    .setUri("http://example.com/media-1-0.m3u8")
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build())
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 2,
                /* adIndexInAdGroup= */ 0,
                new MediaItem.Builder()
                    .setUri("http://example.com/media-2-0.m3u8")
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build());
    assertThat(actual).isEqualTo(expected);
  }

  @Test
  public void handleContentTimelineChanged_clippedWindow_translatedToClippedWindow()
      throws IOException {
    AdPlaybackState actual =
        callHandleContentTimelineChangedAndCaptureAdPlaybackState(
            "#EXTM3U\n"
                + "#EXT-X-TARGETDURATION:6\n"
                + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
                + "#EXTINF:6,\nmain1.0.ts\n"
                + "#EXTINF:6,\nmain2.0.ts\n"
                + "#EXTINF:6,\nmain3.0.ts\n"
                + "#EXTINF:6,\nmain4.0.ts\n" // ends at 24_000_000 -> 21:56:04.000Z
                + "#EXT-X-ENDLIST"
                + "\n"
                + "#EXT-X-DATERANGE:"
                + "ID=\"ad0-0\"," // pre roll
                + "CLASS=\"com.apple.hls.interstitial\","
                + "START-DATE=\"2020-01-02T21:55:44.000Z\","
                + "CUE=\"PRE\"," // Explicit pre roll. Aligned to clip start.
                + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
                + "\n"
                + "#EXT-X-DATERANGE:"
                + "ID=\"ad1-0\"," // ignored
                + "CLASS=\"com.apple.hls.interstitial\","
                + "START-DATE=\"2020-01-02T21:55:42.999Z\"," // non-pre roll behind clipped window
                + "X-ASSET-URI=\"http://example.com/media-1-0.m3u8\""
                + "\n"
                + "#EXT-X-DATERANGE:"
                + "ID=\"ad2-0\"," // ignored
                + "CLASS=\"com.apple.hls.interstitial\","
                + "START-DATE=\"2020-01-02T21:55:39.999Z\"," // snaps to the playlist window start
                + "X-SNAP=\"OUT\""
                + "X-ASSET-URI=\"http://example.com/media-2-0.m3u8\""
                + "\n"
                + "#EXT-X-DATERANGE:"
                + "ID=\"ad3-0\"," // mid roll at 15_000_000
                + "CLASS=\"com.apple.hls.interstitial\","
                + "START-DATE=\"2020-01-02T21:55:55.000Z\","
                + "X-ASSET-URI=\"http://example.com/media-3-0.m3u8\""
                + "\n"
                + "#EXT-X-DATERANGE:"
                + "ID=\"ad4-0\"," // post roll 0
                + "CLASS=\"com.apple.hls.interstitial\","
                + "START-DATE=\"2020-01-02T21:56:00.321Z\"," // exact match at end of clip
                + "X-ASSET-URI=\"http://example.com/media-4-0.m3u8\""
                + "\n"
                + "#EXT-X-DATERANGE:"
                + "ID=\"ad4-1\"," // ignored
                + "CLASS=\"com.apple.hls.interstitial\","
                + "START-DATE=\"2020-01-02T21:56:00.322Z\"," // after end of clip
                + "X-ASSET-URI=\"http://example.com/media-4-1.m3u8\""
                + "\n"
                + "#EXT-X-DATERANGE:"
                + "ID=\"ad4-2\"," // post roll 1
                + "CLASS=\"com.apple.hls.interstitial\","
                + "START-DATE=\"2050-01-02T21:55:08.000Z\","
                + "CUE=\"POST\"," // explicit post roll
                + "X-ASSET-URI=\"http://example.com/media-4-2.m3u8\"\n",
            adsLoader,
            /* windowIndex= */ 1,
            6_000_123L, // clipped to 6s after start of period
            20_321_000L); // clipped to 4s before end of period
    assertThat(actual)
        .isEqualTo(
            new AdPlaybackState("adsId", 6_000_123L, 15_000_000L, C.TIME_END_OF_SOURCE)
                .withAdCount(/* adGroupIndex= */ 0, 1)
                .withAdCount(/* adGroupIndex= */ 1, 1)
                .withAdCount(/* adGroupIndex= */ 2, 2)
                .withAdId(0, 0, "ad0-0")
                .withAdId(1, 0, "ad3-0")
                .withAdId(2, 0, "ad4-0")
                .withAdId(2, 1, "ad4-2")
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-0-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 1,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-3-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 2,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-4-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 2,
                    /* adIndexInAdGroup= */ 1,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-4-2.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build()));
  }

  @Test
  public void handleContentTimelineChanged_3preRolls_mergedIntoSinglePreRollAdGroup()
      throws IOException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:40.000Z\","
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-1\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:40.000Z\","
            + "X-ASSET-URI=\"http://example.com/media-0-1.m3u8\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-2\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "CUE=\"PRE\","
            + "X-ASSET-URI=\"http://example.com/media-0-2.m3u8\""
            + "\n";

    assertThat(
            callHandleContentTimelineChangedAndCaptureAdPlaybackState(
                playlistString,
                adsLoader,
                /* windowIndex= */ 3,
                /* windowPositionInPeriodUs= */ 0,
                /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE))
        .isEqualTo(
            new AdPlaybackState("adsId", 0L)
                .withAdDurationsUs(/* adGroupIndex= */ 0, C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET)
                .withAdCount(/* adGroupIndex= */ 0, 3)
                .withContentResumeOffsetUs(/* adGroupIndex= */ 0, 0L)
                .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, "ad0-0")
                .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1, "ad0-1")
                .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 2, "ad0-2")
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-0-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 1,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-0-1.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 2,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-0-2.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build()));
  }

  @Test
  public void handleContentTimelineChanged_3midRolls_mergedIntoSingleMidRollAdGroup()
      throws IOException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "END-DATE=\"2020-01-02T21:55:46.000Z\"," // adds to resume offset
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-1\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "DURATION=1.1," // adds to resume offset
            + "X-ASSET-URI=\"http://example.com/media-0-1.m3u8\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-2\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "PLANNED-DURATION=1.2," // adds to resume offset
            + "X-ASSET-URI=\"http://example.com/media-0-2.m3u8\""
            + "\n";

    assertThat(
            callHandleContentTimelineChangedAndCaptureAdPlaybackState(
                playlistString,
                adsLoader,
                /* windowIndex= */ 0,
                /* windowPositionInPeriodUs= */ 0,
                /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE))
        .isEqualTo(
            new AdPlaybackState("adsId", /* adGroupTimesUs...= */ 4_000_000L)
                .withAdDurationsUs(/* adGroupIndex= */ 0, 2_000_000L, 1_100_000L, 1_200_000L)
                .withAdCount(/* adGroupIndex= */ 0, 3)
                .withContentResumeOffsetUs(/* adGroupIndex= */ 0, 4_300_000L)
                .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, "ad0-0")
                .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1, "ad0-1")
                .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 2, "ad0-2")
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-0-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 1,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-0-1.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 2,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-0-2.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build()));
  }

  @Test
  public void handleContentTimelineChanged_3postRolls_mergedIntoSinglePostRollAdGroup()
      throws IOException {
    assertThat(
            callHandleContentTimelineChangedAndCaptureAdPlaybackState(
                "#EXTM3U\n"
                    + "#EXT-X-TARGETDURATION:6\n"
                    + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
                    + "#EXTINF:6,\n"
                    + "main1.0.ts\n"
                    + "#EXT-X-ENDLIST"
                    + "\n"
                    + "#EXT-X-DATERANGE:"
                    + "ID=\"ad0-0\","
                    + "CLASS=\"com.apple.hls.interstitial\","
                    + "START-DATE=\"2020-01-02T21:55:30.000Z\","
                    + "CUE=\"POST\"," // cued as post roll
                    + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
                    + "\n"
                    + "#EXT-X-DATERANGE:"
                    + "ID=\"ad0-1\","
                    + "CLASS=\"com.apple.hls.interstitial\","
                    + "START-DATE=\"2020-01-02T21:55:46.000Z\"," // exact match
                    + "X-ASSET-URI=\"http://example.com/media-0-1.m3u8\""
                    + "\n"
                    + "#EXT-X-DATERANGE:"
                    + "ID=\"ad0-2\","
                    + "CLASS=\"com.apple.hls.interstitial\","
                    + "START-DATE=\"2020-01-02T21:55:46.001Z\"," // late but snaps to post roll.
                    + "X-SNAP=\"OUT\""
                    + "X-ASSET-URI=\"http://example.com/media-0-2.m3u8\""
                    + "\n"
                    + "#EXT-X-DATERANGE:"
                    + "ID=\"ad0-3\","
                    + "CLASS=\"com.apple.hls.interstitial\","
                    + "START-DATE=\"2020-01-02T21:55:46.001Z\"," // late and hence ignored.
                    + "X-ASSET-URI=\"http://example.com/media-0-3.m3u8\""
                    + "\n",
                adsLoader,
                /* windowIndex= */ 0,
                /* windowPositionInPeriodUs= */ 0,
                /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE))
        .isEqualTo(
            new AdPlaybackState("adsId", /* adGroupTimesUs...= */ C.TIME_END_OF_SOURCE)
                .withAdDurationsUs(/* adGroupIndex= */ 0, C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET)
                .withAdCount(/* adGroupIndex= */ 0, 3)
                .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, "ad0-0")
                .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1, "ad0-1")
                .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 2, "ad0-2")
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-0-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 1,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-0-1.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 2,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-0-2.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build()));
  }

  @Test
  public void handleContentTimelineChanged_midRollAndPostRollNotInOrder_insertedCorrectly()
      throws IOException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad2-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:40.500Z\","
            + "CUE=\"POST\","
            + "DURATION=3,"
            + "X-ASSET-URI=\"http://example.com/media-2-0.m3u8\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad1-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:42.000Z\","
            + "DURATION=2.0,"
            + "X-ASSET-URI=\"http://example.com/media-1-0.m3u8\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:41.000Z\","
            + "DURATION=1,"
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
            + "\n";

    assertThat(
            callHandleContentTimelineChangedAndCaptureAdPlaybackState(
                playlistString,
                adsLoader,
                /* windowIndex= */ 0,
                /* windowPositionInPeriodUs= */ 0,
                /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE))
        .isEqualTo(
            new AdPlaybackState(
                    "adsId", /* adGroupTimesUs...= */ 1_000_000L, 2_000_000L, C.TIME_END_OF_SOURCE)
                .withAdDurationsUs(/* adGroupIndex= */ 0, 1_000_000L)
                .withAdDurationsUs(/* adGroupIndex= */ 1, 2_000_000L)
                .withAdDurationsUs(/* adGroupIndex= */ 2, 3_000_000L)
                .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
                .withAdCount(/* adGroupIndex= */ 1, /* adCount= */ 1)
                .withAdCount(/* adGroupIndex= */ 2, /* adCount= */ 1)
                .withContentResumeOffsetUs(/* adGroupIndex= */ 0, 1_000_000L)
                .withContentResumeOffsetUs(/* adGroupIndex= */ 1, 2_000_000L)
                .withContentResumeOffsetUs(/* adGroupIndex= */ 2, 3_000_000L)
                .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, "ad0-0")
                .withAdId(/* adGroupIndex= */ 1, /* adIndexInAdGroup= */ 0, "ad1-0")
                .withAdId(/* adGroupIndex= */ 2, /* adIndexInAdGroup= */ 0, "ad2-0")
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-0-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 1,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-1-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 2,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-2-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build()));
  }

  @Test
  public void handleContentTimelineChanged_resumeOffsetSetToZero_contentResumeOffsetUsIsZero()
      throws IOException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:41.123Z\","
            + "DURATION=1.0,"
            + "CUE=\"PRE\","
            + "X-RESUME-OFFSET=0.0,"
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-1\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:41.123Z\","
            + "DURATION=1.0,"
            + "CUE=\"PRE\","
            + "X-ASSET-URI=\"http://example.com/media-0-1.m3u8\""
            + "\n";

    assertThat(
            callHandleContentTimelineChangedAndCaptureAdPlaybackState(
                playlistString,
                adsLoader,
                /* windowIndex= */ 0,
                /* windowPositionInPeriodUs= */ 0,
                /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE))
        .isEqualTo(
            new AdPlaybackState("adsId", /* adGroupTimesUs...= */ 0L)
                .withAdDurationsUs(/* adGroupIndex= */ 0, 1_000_000L, 1_000_000L)
                .withAdCount(/* adGroupIndex= */ 0, 2)
                .withContentResumeOffsetUs(/* adGroupIndex= */ 0, 1_000_000L)
                .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, "ad0-0")
                .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1, "ad0-1")
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-0-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 1,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-0-1.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build()));
  }

  @Test
  public void handleContentTimelineChanged_unknownDuration_handledAsZeroForContentResumeOffsetUs()
      throws IOException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:41.123Z\","
            + "DURATION=1.0,"
            + "CUE=\"PRE\","
            + "X-RESUME-OFFSET=0.0,"
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-1\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:41.123Z\","
            + "CUE=\"PRE\","
            + "X-ASSET-URI=\"http://example.com/media-0-1.m3u8\""
            + "\n";

    assertThat(
            callHandleContentTimelineChangedAndCaptureAdPlaybackState(
                playlistString,
                adsLoader,
                /* windowIndex= */ 0,
                /* windowPositionInPeriodUs= */ 0,
                /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE))
        .isEqualTo(
            new AdPlaybackState("adsId", /* adGroupTimesUs...= */ 0L)
                .withAdDurationsUs(/* adGroupIndex= */ 0, 1_000_000L, C.TIME_UNSET)
                .withAdCount(/* adGroupIndex= */ 0, 2)
                .withContentResumeOffsetUs(/* adGroupIndex= */ 0, 0L)
                .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, "ad0-0")
                .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1, "ad0-1")
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-0-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 1,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-0-1.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build()));
  }

  @Test
  public void handleContentTimelineChanged_playoutLimitSet_durationSetCorrectly()
      throws IOException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:41.123Z\","
            + "END-DATE=\"2020-01-02T21:55:42.123Z\","
            + "DURATION=2.0,"
            + "PLANNED-DURATION=3.0,"
            + "X-PLAYOUT-LIMIT=4.0,"
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
            + "\n";

    assertThat(
            callHandleContentTimelineChangedAndCaptureAdPlaybackState(
                playlistString,
                adsLoader,
                /* windowIndex= */ 0,
                /* windowPositionInPeriodUs= */ 0,
                /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE))
        .isEqualTo(
            new AdPlaybackState("adsId", /* adGroupTimesUs...= */ 1_123_000L)
                .withAdDurationsUs(/* adGroupIndex= */ 0, 4_000_000L)
                .withAdCount(/* adGroupIndex= */ 0, 1)
                .withContentResumeOffsetUs(/* adGroupIndex= */ 0, 4_000_000L)
                .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, "ad0-0")
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-0-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build()));
  }

  @Test
  public void handleContentTimelineChanged_withDurationSet_durationSetCorrectly()
      throws IOException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:41.123Z\","
            + "END-DATE=\"2020-01-02T21:55:42.246Z\","
            + "PLANNED-DURATION=2.000,"
            + "DURATION=3.456,"
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
            + "\n";

    assertThat(
            callHandleContentTimelineChangedAndCaptureAdPlaybackState(
                playlistString,
                adsLoader,
                /* windowIndex= */ 0,
                /* windowPositionInPeriodUs= */ 0,
                /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE))
        .isEqualTo(
            new AdPlaybackState("adsId", /* adGroupTimesUs...= */ 1_123_000L)
                .withAdDurationsUs(/* adGroupIndex= */ 0, 3_456_000L)
                .withAdCount(/* adGroupIndex= */ 0, 1)
                .withContentResumeOffsetUs(/* adGroupIndex= */ 0, 3_456_000L)
                .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, "ad0-0")
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-0-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build()));
  }

  @Test
  public void handleContentTimelineChanged_endDateSet_durationSetCorrectly() throws IOException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:41.123Z\","
            + "END-DATE=\"2020-01-02T21:55:42.246Z\","
            + "PLANNED-DURATION=2.0,"
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
            + "\n";

    assertThat(
            callHandleContentTimelineChangedAndCaptureAdPlaybackState(
                playlistString,
                adsLoader,
                /* windowIndex= */ 0,
                /* windowPositionInPeriodUs= */ 0,
                /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE))
        .isEqualTo(
            new AdPlaybackState("adsId", /* adGroupTimesUs...= */ 1_123_000L)
                .withAdDurationsUs(/* adGroupIndex= */ 0, 1_123_000L)
                .withAdCount(/* adGroupIndex= */ 0, 1)
                .withContentResumeOffsetUs(/* adGroupIndex= */ 0, 1_123_000L)
                .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, "ad0-0")
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-0-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build()));
  }

  @Test
  public void handleContentTimelineChanged_withPlannedDurationSet_durationSetCorrectly()
      throws IOException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:41.123Z\","
            + "PLANNED-DURATION=2.234,"
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
            + "\n";

    assertThat(
            callHandleContentTimelineChangedAndCaptureAdPlaybackState(
                playlistString,
                adsLoader,
                /* windowIndex= */ 0,
                /* windowPositionInPeriodUs= */ 0,
                /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE))
        .isEqualTo(
            new AdPlaybackState("adsId", /* adGroupTimesUs...= */ 1_123_000L)
                .withAdDurationsUs(/* adGroupIndex= */ 0, 2_234_000L)
                .withAdCount(/* adGroupIndex= */ 0, 1)
                .withContentResumeOffsetUs(/* adGroupIndex= */ 0, 2_234_000L)
                .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, "ad0-0")
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-0-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build()));
  }

  @Test
  public void handleContentTimelineChanged_snapOut_snapToClosestSegmentBoundaryOfStartPosition()
      throws IOException {
    assertThat(
            callHandleContentTimelineChangedAndCaptureAdPlaybackState(
                "#EXTM3U\n"
                    + "#EXT-X-TARGETDURATION:6\n"
                    + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
                    + "#EXTINF:6.111,\nmain1.ts\n"
                    + "#EXTINF:6.111,\nmain2.ts\n"
                    + "#EXTINF:6.111,\nmain3.ts\n" // end of window: 18_333_000 -> 21:55:58.333
                    + "#EXT-X-ENDLIST"
                    + "\n"
                    + "#EXT-X-DATERANGE:"
                    + "ID=\"ad0-0\"," // post roll
                    + "CLASS=\"com.apple.hls.interstitial\","
                    + "START-DATE=\"2020-01-02T21:55:58.000Z\"," // snap to 21:55:58.333
                    + "X-SNAP=\"OUT\","
                    + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
                    + "\n"
                    + "#EXT-X-DATERANGE:"
                    + "ID=\"ad1-0\"," // mid roll
                    + "CLASS=\"com.apple.hls.interstitial\","
                    + "START-DATE=\"2020-01-02T21:55:46.000Z\"," // snap to 21:55:46.111
                    + "X-SNAP=\"OUT\","
                    + "X-ASSET-URI=\"http://example.com/media-1-0.m3u8\""
                    + "\n"
                    + "#EXT-X-DATERANGE:"
                    + "ID=\"ad3-0\"," // pre roll
                    + "CLASS=\"com.apple.hls.interstitial\","
                    + "START-DATE=\"2020-01-02T21:55:40.123Z\"," // snap to 21:55:40.000
                    + "X-SNAP=\"OUT\","
                    + "X-ASSET-URI=\"http://example.com/media-3-0.m3u8\""
                    + "\n",
                adsLoader,
                /* windowIndex= */ 0,
                /* windowPositionInPeriodUs= */ 0,
                /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE))
        .isEqualTo(
            new AdPlaybackState(
                    "adsId", /* adGroupTimesUs...= */ 0L, 6_111_000L, C.TIME_END_OF_SOURCE)
                .withAdCount(/* adGroupIndex= */ 0, 1)
                .withAdCount(/* adGroupIndex= */ 1, 1)
                .withAdCount(/* adGroupIndex= */ 2, 1)
                .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, "ad3-0")
                .withAdId(/* adGroupIndex= */ 1, /* adIndexInAdGroup= */ 0, "ad1-0")
                .withAdId(/* adGroupIndex= */ 2, /* adIndexInAdGroup= */ 0, "ad0-0")
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-3-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 1,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-1-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 2,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-0-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build()));
  }

  @Test
  public void handleContentTimelineChanged_snapOutLive_snapToClosestSegmentBoundaryOfStartPosition()
      throws IOException {
    assertThat(
            callHandleContentTimelineChangedForLiveAndCaptureAdPlaybackStates(
                adsLoader,
                /* startAdsLoader= */ true,
                /* windowOffsetInFirstPeriodUs= */ 2_000_123L, // window offset!
                "#EXTM3U\n"
                    + "#EXT-X-TARGETDURATION:6\n"
                    + "#EXT-X-MEDIA-SEQUENCE:0\n"
                    + "#EXT-X-DATERANGE:"
                    + "ID=\"ad0-0\"," // mid roll
                    + "CLASS=\"com.apple.hls.interstitial\","
                    + "START-DATE=\"2020-01-02T21:55:46.000\"," // snap to 6.111 + 2_000_123
                    + "X-SNAP=\"OUT\","
                    + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
                    + "\n"
                    + "#EXT-X-DATERANGE:"
                    + "ID=\"ad1-0\"," // mid roll
                    + "CLASS=\"com.apple.hls.interstitial\","
                    + "START-DATE=\"2020-01-02T21:55:52.999Z\"," // snap to 12.222 + 2_000_123
                    + "X-SNAP=\"OUT\","
                    + "X-ASSET-URI=\"http://example.com/media-1-0.m3u8\""
                    + "\n"
                    + "#EXT-X-DATERANGE:"
                    + "ID=\"ad2-0\"," // mid roll at end of window
                    + "CLASS=\"com.apple.hls.interstitial\","
                    + "START-DATE=\"2050-01-02T21:56:04.000Z\"," // snap to 18.333 + 2_000_123
                    + "X-SNAP=\"OUT\","
                    + "X-ASSET-URI=\"http://example.com/media-2-0.m3u8\""
                    + "\n"
                    + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n" // 2s offset in period
                    + "#EXTINF:6.111,\nmain1.0.ts\n"
                    + "#EXTINF:6.111,\nmain2.0.ts\n"
                    + "#EXTINF:6.111,\nmain3.0.ts\n")) // window end time at 18.333 -> 21:55:58.333
        .containsExactly(
            new AdPlaybackState(
                    "adsId", /* adGroupTimesUs...= */ 8_111_123L, 14_222_123L, 20_333_123L)
                .withLivePostrollPlaceholderAppended(/* isServerSideInserted= */ false)
                .withAdCount(/* adGroupIndex= */ 0, 1)
                .withAdCount(/* adGroupIndex= */ 1, 1)
                .withAdCount(/* adGroupIndex= */ 2, 1)
                .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, "ad0-0")
                .withAdId(/* adGroupIndex= */ 1, /* adIndexInAdGroup= */ 0, "ad1-0")
                .withAdId(/* adGroupIndex= */ 2, /* adIndexInAdGroup= */ 0, "ad2-0")
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-0-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 1,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-1-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 2,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-2-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build()));
  }

  @Test
  public void handleContentTimelineChanged_snapIn_snapToClosestSegmentBoundaryOfResumptionPosition()
      throws IOException {
    assertThat(
            callHandleContentTimelineChangedAndCaptureAdPlaybackState(
                "#EXTM3U\n"
                    + "#EXT-X-TARGETDURATION:6\n"
                    + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
                    + "#EXTINF:6.111,\nmain1.ts\n"
                    + "#EXTINF:6.111,\nmain2.ts\n" // segment start at 6.111
                    + "#EXTINF:6.111,\nmain3.ts\n" // end of window at 18.333
                    + "#EXT-X-ENDLIST"
                    + "\n"
                    + "#EXT-X-DATERANGE:"
                    + "ID=\"ad0-0\","
                    + "CLASS=\"com.apple.hls.interstitial\","
                    + "START-DATE=\"2020-01-02T21:55:39.900\"," // snap to 12.222 - 12.222 -> 0L
                    + "X-SNAP=\"IN\","
                    + "DURATION=12.222,"
                    + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
                    + "\n"
                    + "#EXT-X-DATERANGE:"
                    + "ID=\"ad1-0\","
                    + "CLASS=\"com.apple.hls.interstitial\","
                    + "START-DATE=\"2020-01-02T21:55:51.222\"," // aligned 12.222 - 3.222 -> 9_000L
                    + "X-SNAP=\"IN\","
                    + "DURATION=3.222,"
                    + "X-ASSET-URI=\"http://example.com/media-1-0.m3u8\""
                    + "\n"
                    + "#EXT-X-DATERANGE:"
                    + "ID=\"ad2-0\","
                    + "CLASS=\"com.apple.hls.interstitial\","
                    + "START-DATE=\"2020-01-02T21:55:54.678\"," // snap to end of window - 4.333
                    + "X-SNAP=\"IN\","
                    + "DURATION=4.333,"
                    + "X-ASSET-URI=\"http://example.com/media-2-0.m3u8\""
                    + "\n",
                adsLoader,
                /* windowIndex= */ 0,
                /* windowPositionInPeriodUs= */ 0,
                /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE))
        .isEqualTo(
            new AdPlaybackState("adsId", /* adGroupTimesUs...= */ 0L, 9_000_000L, 14_000_000L)
                .withAdCount(/* adGroupIndex= */ 0, 1)
                .withAdCount(/* adGroupIndex= */ 1, 1)
                .withAdCount(/* adGroupIndex= */ 2, 1)
                .withContentResumeOffsetUs(/* adGroupIndex= */ 0, 12_222_000L)
                .withContentResumeOffsetUs(/* adGroupIndex= */ 1, 3_222_000L)
                .withContentResumeOffsetUs(/* adGroupIndex= */ 2, 4_333_000L)
                .withAdDurationsUs(/* adGroupIndex= */ 0, 12_222_000L)
                .withAdDurationsUs(/* adGroupIndex= */ 1, 3_222_000L)
                .withAdDurationsUs(/* adGroupIndex= */ 2, 4_333_000L)
                .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, "ad0-0")
                .withAdId(/* adGroupIndex= */ 1, /* adIndexInAdGroup= */ 0, "ad1-0")
                .withAdId(/* adGroupIndex= */ 2, /* adIndexInAdGroup= */ 0, "ad2-0")
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-0-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 1,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-1-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 2,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-2-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build()));
  }

  @Test
  public void
      handleContentTimelineChanged_snapInLive_snapToClosestSegmentBoundaryOfResumptionPosition()
          throws IOException {
    assertThat(
            callHandleContentTimelineChangedForLiveAndCaptureAdPlaybackStates(
                adsLoader,
                /* startAdsLoader= */ true,
                /* windowOffsetInFirstPeriodUs= */ 123L,
                "#EXTM3U\n"
                    + "#EXT-X-TARGETDURATION:6\n"
                    + "#EXT-X-MEDIA-SEQUENCE:0\n"
                    + "#EXT-X-DATERANGE:"
                    + "ID=\"ad0-0\","
                    + "CLASS=\"com.apple.hls.interstitial\","
                    + "START-DATE=\"2020-01-02T21:55:39.900\"," // snap to (12.222 - 12.222) -> 0L
                    + "X-SNAP=\"IN\","
                    + "X-RESUME-OFFSET=12.222,"
                    + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
                    + "\n"
                    + "#EXT-X-DATERANGE:"
                    + "ID=\"ad1-0\","
                    + "CLASS=\"com.apple.hls.interstitial\","
                    + "START-DATE=\"2020-01-02T21:55:51.222\"," // snap to (12.222 - 3.22) -> 9_000L
                    + "X-SNAP=\"IN\","
                    + "X-RESUME-OFFSET=3.222,"
                    + "X-ASSET-URI=\"http://example.com/media-1-0.m3u8\""
                    + "\n"
                    + "#EXT-X-DATERANGE:"
                    + "ID=\"ad2-0\","
                    + "CLASS=\"com.apple.hls.interstitial\","
                    + "START-DATE=\"2020-01-02T21:55:54.678\"," // snap to (end of window - 4.333)
                    + "X-SNAP=\"IN\","
                    + "DURATION=4.333,"
                    + "X-ASSET-URI=\"http://example.com/media-2-0.m3u8\""
                    + "\n"
                    + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
                    + "#EXTINF:6.111,\nmain1.ts\n"
                    + "#EXTINF:6.111,\nmain2.ts\n" // segment start at 6.111
                    + "#EXTINF:6.111,\nmain3.ts\n")) // segment start at 12.222
        .containsExactly(
            new AdPlaybackState("adsId", /* adGroupTimesUs...= */ 123L, 9_000_123L, 14_000_123L)
                .withLivePostrollPlaceholderAppended(/* isServerSideInserted= */ false)
                .withAdCount(/* adGroupIndex= */ 0, 1)
                .withAdCount(/* adGroupIndex= */ 1, 1)
                .withAdCount(/* adGroupIndex= */ 2, 1)
                .withContentResumeOffsetUs(/* adGroupIndex= */ 0, 12_222_000L)
                .withContentResumeOffsetUs(/* adGroupIndex= */ 1, 3_222_000L)
                .withContentResumeOffsetUs(/* adGroupIndex= */ 2, 4_333_000L)
                .withAdDurationsUs(/* adGroupIndex= */ 2, 4_333_000L)
                .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, "ad0-0")
                .withAdId(/* adGroupIndex= */ 1, /* adIndexInAdGroup= */ 0, "ad1-0")
                .withAdId(/* adGroupIndex= */ 2, /* adIndexInAdGroup= */ 0, "ad2-0")
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-0-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 1,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-1-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 2,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-2-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build()));
  }

  @Test
  public void
      handleContentTimelineChanged_snapInFarBeforeOrAfterWindow_snapToStartOfWindowAndPostRoll()
          throws IOException {
    assertThat(
            callHandleContentTimelineChangedAndCaptureAdPlaybackState(
                "#EXTM3U\n"
                    + "#EXT-X-TARGETDURATION:6\n"
                    + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
                    + "#EXTINF:6.111,\nmain1.0.ts\n"
                    + "#EXT-X-ENDLIST"
                    + "\n"
                    + "#EXT-X-DATERANGE:"
                    + "ID=\"ad0-0\"," // pre roll
                    + "CLASS=\"com.apple.hls.interstitial\","
                    + "START-DATE=\"1990-01-02T00:00:00.000\"," // snap to start of window.
                    + "DURATION=1.0,"
                    + "X-RESUME-OFFSET=0.0," // with no offset SNAP_IN => SNAP_OUT
                    + "X-SNAP=\"IN\","
                    + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
                    + "\n"
                    + "#EXT-X-DATERANGE:"
                    + "ID=\"ad1-0\"," // ignored
                    + "CLASS=\"com.apple.hls.interstitial\","
                    + "START-DATE=\"1990-01-02T00:00:00.000\"," // snap end to start of window
                    + "DURATION=1.0," // translate start of ad back to 1s behind window
                    + "X-SNAP=\"IN\","
                    + "X-ASSET-URI=\"http://example.com/media-1-0.m3u8\""
                    + "\n"
                    + "#EXT-X-DATERANGE:"
                    + "ID=\"ad2-0\"," // post roll
                    + "CLASS=\"com.apple.hls.interstitial\","
                    + "START-DATE=\"2050-01-02T21:55:00.900\"," // snap to end of window
                    + "X-SNAP=\"IN\","
                    + "X-ASSET-URI=\"http://example.com/media-2-0.m3u8\""
                    + "\n",
                adsLoader,
                /* windowIndex= */ 0,
                /* windowPositionInPeriodUs= */ 0,
                /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE))
        .isEqualTo(
            new AdPlaybackState("adsId", /* adGroupTimesUs...= */ 0L, C.TIME_END_OF_SOURCE)
                .withAdCount(/* adGroupIndex= */ 0, 1)
                .withAdCount(/* adGroupIndex= */ 1, 1)
                .withAdDurationsUs(/* adGroupIndex= */ 0, 1_000_000L)
                .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, "ad0-0")
                .withAdId(/* adGroupIndex= */ 1, /* adIndexInAdGroup= */ 0, "ad2-0")
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-0-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 1,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-2-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build()));
  }

  @Test
  public void
      handleContentTimelineChanged_snapInLiveFarBeforeOrAfterWindow_snapToStartAndEndOfWindow()
          throws IOException {
    assertThat(
            callHandleContentTimelineChangedForLiveAndCaptureAdPlaybackStates(
                adsLoader,
                /* startAdsLoader= */ true,
                /* windowOffsetInFirstPeriodUs= */ 0,
                "#EXTM3U\n"
                    + "#EXT-X-TARGETDURATION:6\n"
                    + "#EXT-X-MEDIA-SEQUENCE:0\n"
                    + "#EXT-X-DATERANGE:"
                    + "ID=\"ad0-0\"," // pre roll
                    + "CLASS=\"com.apple.hls.interstitial\","
                    + "START-DATE=\"1990-01-02T00:00:00.000\"," // snap to start of window
                    + "DURATION=1.0,"
                    + "X-RESUME-OFFSET=0.0," // with no offset SNAP_IN => SNAP_OUT
                    + "X-SNAP=\"IN\","
                    + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
                    + "\n"
                    + "#EXT-X-DATERANGE:"
                    + "ID=\"ad1-0\"," // ignore
                    + "CLASS=\"com.apple.hls.interstitial\","
                    + "START-DATE=\"1990-01-02T00:00:00.000\"," // snap to start of window
                    + "DURATION=1.0," // translate start of ad back to 1s behind window
                    + "X-SNAP=\"IN\","
                    + "X-ASSET-URI=\"http://example.com/media-1-0.m3u8\""
                    + "\n"
                    + "#EXT-X-DATERANGE:"
                    + "ID=\"ad2-0\"," // mid roll at end of window (but not C.TIME_END_OF_SOURCE)
                    + "CLASS=\"com.apple.hls.interstitial\","
                    + "START-DATE=\"2050-01-02T21:55:00.900\"," // snap end of window: 12_222_000
                    + "X-SNAP=\"IN\"," // no duration or offset: SNAP_IN => SNAP_OUT
                    + "X-ASSET-URI=\"http://example.com/media-2-0.m3u8\""
                    + "\n"
                    + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
                    + "#EXTINF:6.111,\nmain1.0.ts\n"
                    + "#EXTINF:6.111,\nmain1.0.ts\n" // end of window: 12:222 - 21:55:52.222
                    + "\n"))
        .containsExactly(
            new AdPlaybackState("adsId", /* adGroupTimesUs...= */ 0L, 12_222_000L)
                .withLivePostrollPlaceholderAppended(/* isServerSideInserted= */ false)
                .withAdCount(/* adGroupIndex= */ 0, 1)
                .withAdCount(/* adGroupIndex= */ 1, 1)
                .withAdDurationsUs(/* adGroupIndex= */ 0, 1_000_000L)
                .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, "ad0-0")
                .withAdId(/* adGroupIndex= */ 1, /* adIndexInAdGroup= */ 0, "ad2-0")
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-0-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 1,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-2-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build()));
  }

  @Test
  public void
      handleContentTimelineChanged_snapInOut_snapToSameSegmentBoundaryMergedIntoSameAdGroup()
          throws IOException {
    assertThat(
            callHandleContentTimelineChangedAndCaptureAdPlaybackState(
                "#EXTM3U\n"
                    + "#EXT-X-TARGETDURATION:6\n"
                    + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
                    + "#EXTINF:6.111,\nmain1.0.ts\n"
                    + "#EXTINF:6.111,\nmain2.0.ts\n" // segment start at 6.111 -> 21:55:46.111
                    + "#EXTINF:6.111,\nmain3.0.ts\n" // segment start at 12.222 -> 21:55:52.222
                    + "#EXT-X-ENDLIST"
                    + "\n"
                    + "#EXT-X-DATERANGE:"
                    + "ID=\"ad0-0\"," // mid roll
                    + "CLASS=\"com.apple.hls.interstitial\","
                    + "START-DATE=\"2020-01-02T21:55:45.000\"," // snap tp 6_111_000
                    + "X-SNAP=\"OUT\","
                    + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
                    + "\n"
                    + "#EXT-X-DATERANGE:"
                    + "ID=\"ad0-1\"," // mid roll
                    + "CLASS=\"com.apple.hls.interstitial\","
                    + "START-DATE=\"2020-01-02T21:55:47.000\"," // snap to 6_111_000
                    + "X-SNAP=\"IN\","
                    + "DURATION=6.111," // ends at 21:55:53.111 -> 21:55:52.222 - 6.111
                    + "X-ASSET-URI=\"http://example.com/media-0-1.m3u8\""
                    + "\n",
                adsLoader,
                /* windowIndex= */ 0,
                /* windowPositionInPeriodUs= */ 0,
                /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE))
        .isEqualTo(
            new AdPlaybackState("adsId", /* adGroupTimesUs...= */ 6_111_000L)
                .withAdCount(/* adGroupIndex= */ 0, 2)
                .withContentResumeOffsetUs(/* adGroupIndex= */ 0, 6_111_000L)
                .withAdDurationsUs(/* adGroupIndex= */ 0, C.TIME_UNSET, 6_111_000L)
                .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, "ad0-0")
                .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1, "ad0-1")
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-0-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 1,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-0-1.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build()));
  }

  @Test
  public void
      handleContentTimelineChanged_snapInOutLive_snapToSameSegmentBoundaryMergedInSameAdGroup()
          throws IOException {
    assertThat(
            callHandleContentTimelineChangedForLiveAndCaptureAdPlaybackStates(
                adsLoader,
                /* startAdsLoader= */ true,
                /* windowOffsetInFirstPeriodUs= */ 0L,
                "#EXTM3U\n"
                    + "#EXT-X-TARGETDURATION:6\n"
                    + "#EXT-X-MEDIA-SEQUENCE:0\n"
                    + "#EXT-X-DATERANGE:"
                    + "ID=\"ad0-0\","
                    + "CLASS=\"com.apple.hls.interstitial\","
                    + "START-DATE=\"2020-01-02T21:55:45.000\"," // -> 6_111_000L
                    + "X-SNAP=\"OUT\","
                    + "DURATION=2.222,"
                    + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
                    + "\n"
                    + "#EXT-X-DATERANGE:"
                    + "ID=\"ad0-1\","
                    + "CLASS=\"com.apple.hls.interstitial\","
                    + "START-DATE=\"2020-01-02T21:55:47.000\"," // -> 6_111_000L
                    + "X-SNAP=\"IN\","
                    + "DURATION=6.111,"
                    + "X-ASSET-URI=\"http://example.com/media-0-1.m3u8\""
                    + "\n"
                    + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
                    + "#EXTINF:6.111,\nmain1.0.ts\n"
                    + "#EXTINF:6.111,\nmain2.0.ts\n" // segment start at 6.111 -> 21:55:46.111
                    + "#EXTINF:6.111,\nmain3.0.ts\n" // segment start at 12.222 -> 21:55:52.222
                    + "\n"))
        .containsExactly(
            new AdPlaybackState("adsId", /* adGroupTimesUs...= */ 6_111_000L)
                .withLivePostrollPlaceholderAppended(/* isServerSideInserted= */ false)
                .withAdCount(/* adGroupIndex= */ 0, 2)
                .withContentResumeOffsetUs(/* adGroupIndex= */ 0, 8_333_000L)
                .withAdDurationsUs(/* adGroupIndex= */ 0, 2_222_000L, 6_111_000L)
                .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, "ad0-0")
                .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1, "ad0-1")
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-0-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 1,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-0-1.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build()));
  }

  @Test
  public void handleContentTimelineChanged_snapOut_snapToExactTargetDurationBoundaryInWindow()
      throws IOException {
    assertThat(
            callHandleContentTimelineChangedAndCaptureAdPlaybackState(
                "#EXTM3U\n"
                    + "#EXT-X-TARGETDURATION:6\n"
                    + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
                    + "#EXTINF:6.111,\nmain0.ts\n"
                    + "#EXTINF:6.111,\nmain1.ts\n"
                    + "#EXTINF:6.111,\nmain2.ts\n"
                    + "#EXT-X-ENDLIST"
                    + "\n"
                    + "#EXT-X-DATERANGE:"
                    + "ID=\"ad0-0\","
                    + "X-SNAP=\"OUT\","
                    + "CLASS=\"com.apple.hls.interstitial\","
                    + "START-DATE=\"2020-01-02T21:55:46.000Z\","
                    + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
                    + "\n"
                    + "#EXT-X-DATERANGE:"
                    + "ID=\"ad1-0\","
                    + "CLASS=\"com.apple.hls.interstitial\","
                    + "START-DATE=\"2020-01-02T21:55:58.000Z\","
                    + "X-SNAP=\"OUT\","
                    + "X-ASSET-URI=\"http://example.com/media-1-0.m3u8\""
                    + "\n",
                adsLoader,
                /* windowIndex= */ 0,
                /* windowPositionInPeriodUs= */ 0,
                /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE))
        .isEqualTo(
            new AdPlaybackState("adsId", /* adGroupTimesUs...= */ 6_111_000L, C.TIME_END_OF_SOURCE)
                .withAdCount(/* adGroupIndex= */ 0, 1)
                .withAdCount(/* adGroupIndex= */ 1, 1)
                .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, "ad0-0")
                .withAdId(/* adGroupIndex= */ 1, /* adIndexInAdGroup= */ 0, "ad1-0")
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-0-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 1,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-1-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build()));
  }

  @Test
  public void handleContentTimelineChanged_noDurationSet_durationTimeUnset() throws IOException {
    assertThat(
            callHandleContentTimelineChangedAndCaptureAdPlaybackState(
                "#EXTM3U\n"
                    + "#EXT-X-TARGETDURATION:6\n"
                    + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
                    + "#EXTINF:6,\n"
                    + "main1.0.ts\n"
                    + "#EXT-X-ENDLIST"
                    + "\n"
                    + "#EXT-X-DATERANGE:"
                    + "ID=\"ad0-0\","
                    + "CLASS=\"com.apple.hls.interstitial\","
                    + "START-DATE=\"2020-01-02T21:55:41.123Z\","
                    + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
                    + "\n",
                adsLoader,
                /* windowIndex= */ 0,
                /* windowPositionInPeriodUs= */ 0,
                /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE))
        .isEqualTo(
            new AdPlaybackState("adsId", /* adGroupTimesUs...= */ 1_123_000L)
                .withAdDurationsUs(/* adGroupIndex= */ 0, C.TIME_UNSET)
                .withAdCount(/* adGroupIndex= */ 0, 1)
                .withContentResumeOffsetUs(/* adGroupIndex= */ 0, 0L)
                .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, "ad0-0")
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-0-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build()));
  }

  @Test
  public void handleContentTimelineChanged_livePlaylistWithoutInterstitials_hasLivePlaceholder()
      throws IOException {
    assertThat(
            callHandleContentTimelineChangedForLiveAndCaptureAdPlaybackStates(
                adsLoader,
                /* startAdsLoader= */ true,
                /* windowOffsetInFirstPeriodUs= */ 0L,
                "#EXTM3U\n"
                    + "#EXT-X-TARGETDURATION:6\n"
                    + "#EXT-X-MEDIA-SEQUENCE:0\n"
                    + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:00:00.000Z\n"
                    + "#EXTINF:6,\nmain0.0.ts\n"
                    + "#EXTINF:6,\nmain1.0.ts\n"
                    + "#EXTINF:6,\nmain2.0.ts\n"
                    + "#EXTINF:6,\nmain3.0.ts\n"
                    + "#EXTINF:6,\nmain4.0.ts\n"
                    + "\n",
                "#EXTM3U\n"
                    + "#EXT-X-TARGETDURATION:6\n"
                    + "#EXT-X-MEDIA-SEQUENCE:1\n"
                    + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:00:06.000Z\n"
                    + "#EXTINF:6,\nmain1.0.ts\n"
                    + "#EXTINF:6,\nmain2.0.ts\n"
                    + "#EXTINF:6,\nmain3.0.ts\n"
                    + "#EXTINF:6,\nmain4.0.ts\n"
                    + "#EXTINF:6,\nmain5.0.ts\n"
                    + "\n"))
        .containsExactly(
            new AdPlaybackState("adsId")
                .withLivePostrollPlaceholderAppended(/* isServerSideInserted= */ false));
  }

  @Test
  public void
      handleContentTimelineChanged_threeLivePlaylistUpdatesUnplayed_correctAdPlaybackStateUpdates()
          throws IOException {
    assertThat(
            callHandleContentTimelineChangedForLiveAndCaptureAdPlaybackStates(
                adsLoader,
                /* startAdsLoader= */ true,
                /* windowOffsetInFirstPeriodUs= */ 0L,
                "#EXTM3U\n"
                    + "#EXT-X-TARGETDURATION:6\n"
                    + "#EXT-X-MEDIA-SEQUENCE:0\n"
                    + "#EXT-X-DATERANGE:"
                    + "ID=\"ad0-0\","
                    + "CLASS=\"com.apple.hls.interstitial\","
                    + "START-DATE=\"2020-01-02T21:00:06.000Z\","
                    + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
                    + "\n"
                    + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:00:00.000Z\n"
                    + "#EXTINF:6,\nmain0.0.ts\n"
                    + "#EXTINF:6,\nmain1.0.ts\n" // ad0-0 cue point: 21:00:06
                    + "#EXTINF:6,\nmain2.0.ts\n"
                    + "#EXTINF:6,\nmain3.0.ts\n"
                    + "#EXTINF:6,\nmain4.0.ts\n"
                    + "\n",
                "#EXTM3U\n"
                    + "#EXT-X-TARGETDURATION:6\n"
                    + "#EXT-X-MEDIA-SEQUENCE:1\n"
                    + "#EXT-X-DATERANGE:"
                    + "ID=\"ad0-0\","
                    + "CLASS=\"com.apple.hls.interstitial\","
                    + "START-DATE=\"2020-01-02T21:00:06.000Z\","
                    + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
                    + "\n"
                    + "#EXT-X-DATERANGE:"
                    + "ID=\"ad1-0\","
                    + "CLASS=\"com.apple.hls.interstitial\","
                    + "START-DATE=\"2020-01-02T21:00:18.000Z\","
                    + "X-ASSET-URI=\"http://example.com/media-1-0.m3u8\"\n"
                    + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:00:06.000Z\n"
                    + "#EXTINF:6,\nmain1.0.ts\n" // ad0-0 cue point: 21:00:06
                    + "#EXTINF:6,\nmain2.0.ts\n"
                    + "#EXTINF:6,\nmain3.0.ts\n" // ad1-0 cue point: 21:00:18
                    + "#EXTINF:6,\nmain4.0.ts\n"
                    + "#EXTINF:6,\nmain5.0.ts\n"
                    + "\n",
                "#EXTM3U\n"
                    + "#EXT-X-TARGETDURATION:6\n"
                    + "#EXT-X-MEDIA-SEQUENCE:2\n"
                    + "#EXT-X-DATERANGE:"
                    + "ID=\"ad1-0\","
                    + "CLASS=\"com.apple.hls.interstitial\","
                    + "START-DATE=\"2020-01-02T21:00:18.000Z\","
                    + "X-ASSET-URI=\"http://example.com/media-1-0.m3u8\"\n"
                    + "\n"
                    + "#EXT-X-DATERANGE:"
                    + "ID=\"ad1-1\","
                    + "CLASS=\"com.apple.hls.interstitial\","
                    + "START-DATE=\"2020-01-02T21:00:18.000Z\","
                    + "X-ASSET-URI=\"http://example.com/media-1-1.m3u8\"\n"
                    + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:00:12.000Z\n"
                    + "#EXTINF:6,\nmain2.0.ts\n"
                    + "#EXTINF:6,\nmain3.0.ts\n" // ad1-0 cue point: 21:00:18
                    + "#EXTINF:6,\nmain4.0.ts\n"
                    + "#EXTINF:6,\nmain5.0.ts\n"
                    + "#EXTINF:6,\nmain6.0.ts\n"
                    + "\n"))
        .containsExactly(
            new AdPlaybackState("adsId", 6_000_000L)
                .withAdResumePositionUs(0)
                .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
                .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, "ad0-0")
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-0-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withLivePostrollPlaceholderAppended(/* isServerSideInserted= */ false),
            new AdPlaybackState("adsId", 6_000_000L)
                .withAdResumePositionUs(0)
                .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
                .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, "ad0-0")
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-0-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withNewAdGroup(1, 18_000_000L)
                .withAdCount(/* adGroupIndex= */ 1, /* adCount= */ 1)
                .withAdId(1, 0, "ad1-0")
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 1,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-1-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withLivePostrollPlaceholderAppended(/* isServerSideInserted= */ false),
            new AdPlaybackState("adsId", 6_000_000L)
                .withAdResumePositionUs(0)
                .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
                .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, "ad0-0")
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-0-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withNewAdGroup(1, 18_000_000L)
                .withAdCount(/* adGroupIndex= */ 1, /* adCount= */ 2)
                .withAdId(1, 0, "ad1-0")
                .withAdId(1, 1, "ad1-1")
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 1,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-1-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 1,
                    /* adIndexInAdGroup= */ 1,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-1-1.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withLivePostrollPlaceholderAppended(/* isServerSideInserted= */ false))
        .inOrder();
  }

  @Test
  public void
      handleContentTimelineChanged_livePlaylistUpdatesPreRollAndPostRoll_addPreRollIgnorePostRoll()
          throws IOException {
    assertThat(
            callHandleContentTimelineChangedForLiveAndCaptureAdPlaybackStates(
                adsLoader,
                /* startAdsLoader= */ true,
                /* windowOffsetInFirstPeriodUs= */ 0L,
                "#EXTM3U\n"
                    + "#EXT-X-TARGETDURATION:6\n"
                    + "#EXT-X-MEDIA-SEQUENCE:0\n"
                    + "#EXT-X-DATERANGE:"
                    + "ID=\"ad0-0\","
                    + "CLASS=\"com.apple.hls.interstitial\","
                    + "START-DATE=\"2020-01-02T22:00:00.000Z\","
                    + "CUE=\"POST\","
                    + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
                    + "\n"
                    + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:00:00.000Z\n"
                    + "#EXTINF:6,\nmain0.0.ts\n"
                    + "#EXTINF:6,\nmain1.0.ts\n"
                    + "#EXTINF:6,\nmain2.0.ts\n"
                    + "#EXTINF:6,\nmain3.0.ts\n"
                    + "#EXTINF:6,\nmain4.0.ts\n"
                    + "\n",
                "#EXTM3U\n"
                    + "#EXT-X-TARGETDURATION:6\n"
                    + "#EXT-X-MEDIA-SEQUENCE:0\n"
                    + "#EXT-X-DATERANGE:"
                    + "ID=\"ad0-0\","
                    + "CLASS=\"com.apple.hls.interstitial\","
                    + "START-DATE=\"2020-01-02T22:00:00.000Z\","
                    + "CUE=\"POST\","
                    + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
                    + "\n"
                    + "#EXT-X-DATERANGE:"
                    + "ID=\"ad1-0\","
                    + "CLASS=\"com.apple.hls.interstitial\","
                    + "START-DATE=\"2020-01-02T20:00:00.000Z\","
                    + "CUE=\"PRE\","
                    + "X-ASSET-URI=\"http://example.com/media-1-0.m3u8\""
                    + "\n"
                    + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:00:06.000Z\n"
                    + "#EXTINF:6,\nmain1.0.ts\n" // pre-roll queue point
                    + "#EXTINF:6,\nmain2.0.ts\n"
                    + "#EXTINF:6,\nmain3.0.ts\n"
                    + "#EXTINF:6,\nmain4.0.ts\n"
                    + "#EXTINF:6,\nmain5.0.ts\n"
                    + "\n"))
        .containsExactly(
            new AdPlaybackState("adsId")
                .withLivePostrollPlaceholderAppended(/* isServerSideInserted= */ false),
            new AdPlaybackState("adsId", 6_000_000L)
                .withAdResumePositionUs(0)
                .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
                .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, "ad1-0")
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-1-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withLivePostrollPlaceholderAppended(/* isServerSideInserted= */ false))
        .inOrder();
  }

  @Test
  public void
      handleContentTimelineChanged_livePlaylistUpdateNewAdAfterPlayedAd_correctAdPlaybackStateUpdates()
          throws IOException {
    callHandleContentTimelineChangedForLiveAndCaptureAdPlaybackStates(
        adsLoader,
        /* startAdsLoader= */ true,
        /* windowOffsetInFirstPeriodUs= */ 0L,
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-MEDIA-SEQUENCE:0\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:00:06.000Z\","
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
            + "\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:00:00.000Z\n"
            + "#EXTINF:6,\nmain0.0.ts\n"
            + "#EXTINF:6,\nmain1.0.ts\n" // ad0-0 cue point: 21:00:06
            + "#EXTINF:6,\nmain2.0.ts\n"
            + "#EXTINF:6,\nmain3.0.ts\n"
            + "#EXTINF:6,\nmain4.0.ts\n"
            + "\n");
    reset(mockEventListener);
    // Mark ad as played by a automatic discontinuity from the ad to the content.
    ArgumentCaptor<Player.Listener> listener = ArgumentCaptor.forClass(Player.Listener.class);
    verify(mockPlayer).addListener(listener.capture());
    Object windowUid = new Object();
    Object periodUid = new Object();
    listener
        .getValue()
        .onPositionDiscontinuity(
            new Player.PositionInfo(
                windowUid,
                /* mediaItemIndex= */ 0,
                contentMediaItem,
                periodUid,
                /* periodIndex= */ 0,
                /* positionMs= */ 10_000L,
                /* contentPositionMs= */ 0L,
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 0),
            new Player.PositionInfo(
                windowUid,
                /* mediaItemIndex= */ 0,
                contentMediaItem,
                periodUid,
                /* periodIndex= */ 0,
                /* positionMs= */ 0L,
                /* contentPositionMs= */ 0L,
                /* adGroupIndex= */ C.INDEX_UNSET,
                /* adIndexInAdGroup= */ C.INDEX_UNSET),
            DISCONTINUITY_REASON_AUTO_TRANSITION);
    verify(mockEventListener)
        .onAdPlaybackState(
            new AdPlaybackState("adsId", 6_000_000L)
                .withLivePostrollPlaceholderAppended(/* isServerSideInserted= */ false)
                .withAdResumePositionUs(0)
                .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
                .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, "ad0-0")
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-0-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withPlayedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0));
    reset(mockEventListener);

    assertThat(
            callHandleContentTimelineChangedForLiveAndCaptureAdPlaybackStates(
                adsLoader,
                /* startAdsLoader= */ false,
                /* windowOffsetInFirstPeriodUs= */ 6_000_000L,
                "#EXTM3U\n"
                    + "#EXT-X-TARGETDURATION:6\n"
                    + "#EXT-X-MEDIA-SEQUENCE:1\n"
                    + "#EXT-X-DATERANGE:"
                    + "ID=\"ad0-0\","
                    + "CLASS=\"com.apple.hls.interstitial\","
                    + "START-DATE=\"2020-01-02T21:00:06.000Z\","
                    + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
                    + "\n"
                    + "#EXT-X-DATERANGE:"
                    + "ID=\"ad1-0\","
                    + "CLASS=\"com.apple.hls.interstitial\","
                    + "START-DATE=\"2020-01-02T21:00:18.000Z\","
                    + "X-ASSET-URI=\"http://example.com/media-1-0.m3u8\"\n"
                    + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:00:06.000Z\n"
                    + "#EXTINF:6,\nmain1.0.ts\n" // ad0-0 cue point: 21:00:06
                    + "#EXTINF:6,\nmain2.0.ts\n"
                    + "#EXTINF:6,\nmain3.0.ts\n"
                    + "#EXTINF:6,\nmain4.0.ts\n"
                    + "#EXTINF:6,\nmain5.0.ts\n" // ad1-0 cue point: 21:00:30
                    + "\n"))
        .containsExactly(
            new AdPlaybackState("adsId", 6_000_000L)
                .withLivePostrollPlaceholderAppended(/* isServerSideInserted= */ false)
                .withAdResumePositionUs(0)
                .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
                .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, "ad0-0")
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-0-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withPlayedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0)
                .withNewAdGroup(1, 18_000_000L)
                .withAdCount(/* adGroupIndex= */ 1, /* adCount= */ 1)
                .withAdId(1, 0, "ad1-0")
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 1,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-1-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build()))
        .inOrder();
  }

  @Test
  public void
      handleContentTimelineChanged_attemptInsertionForLiveBeforeAvailableAdGroup_interstitialIgnored()
          throws IOException {
    assertThat(
            callHandleContentTimelineChangedForLiveAndCaptureAdPlaybackStates(
                adsLoader,
                /* startAdsLoader= */ true,
                /* windowOffsetInFirstPeriodUs= */ 0L,
                "#EXTM3U\n"
                    + "#EXT-X-TARGETDURATION:6\n"
                    + "#EXT-X-MEDIA-SEQUENCE:0\n"
                    + "#EXT-X-DATERANGE:"
                    + "ID=\"ad0-0\","
                    + "CLASS=\"com.apple.hls.interstitial\","
                    + "START-DATE=\"2020-01-02T21:00:18.000Z\","
                    + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
                    + "\n"
                    + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:00:00.000Z\n"
                    + "#EXTINF:6,\nmain0.0.ts\n"
                    + "#EXTINF:6,\nmain1.0.ts\n"
                    + "#EXTINF:6,\nmain2.0.ts\n"
                    + "#EXTINF:6,\nmain3.0.ts\n" // ad0-0 cue point: 21:00:18
                    + "#EXTINF:6,\nmain4.0.ts\n"
                    + "\n",
                "#EXTM3U\n"
                    + "#EXT-X-TARGETDURATION:6\n"
                    + "#EXT-X-MEDIA-SEQUENCE:1\n"
                    + "#EXT-X-DATERANGE:"
                    + "ID=\"ad1-0\","
                    + "CLASS=\"com.apple.hls.interstitial\","
                    + "START-DATE=\"2020-01-02T21:00:06.000Z\","
                    + "X-ASSET-URI=\"http://example.com/media-1-0.m3u8\""
                    + "\n"
                    + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:00:06.000Z\n"
                    + "#EXTINF:6,\nmain1.0.ts\n" // ad1-0 cue point: 21:00:06
                    + "#EXTINF:6,\nmain2.0.ts\n"
                    + "#EXTINF:6,\nmain3.0.ts\n" // ad0-0 cue point: 21:00:18
                    + "#EXTINF:6,\nmain4.0.ts\n"
                    + "#EXTINF:6,\nmain5.0.ts\n"
                    + "\n"))
        .containsExactly(
            new AdPlaybackState("adsId", 18_000_000L)
                .withAdResumePositionUs(0)
                .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
                .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, "ad0-0")
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-0-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withLivePostrollPlaceholderAppended(/* isServerSideInserted= */ false))
        .inOrder();
  }

  @Test
  public void handleContentTimelineChanged_attemptInsertionBehindLiveWindow_interstitialIgnored()
      throws IOException {
    assertThat(
            callHandleContentTimelineChangedForLiveAndCaptureAdPlaybackStates(
                adsLoader,
                /* startAdsLoader= */ true,
                /* windowOffsetInFirstPeriodUs= */ 0L,
                "#EXTM3U\n"
                    + "#EXT-X-TARGETDURATION:6\n"
                    + "#EXT-X-MEDIA-SEQUENCE:0\n"
                    + "#EXT-X-DATERANGE:"
                    + "ID=\"ad0-0\","
                    + "CLASS=\"com.apple.hls.interstitial\","
                    + "START-DATE=\"2020-01-02T21:00:00.000Z\","
                    + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
                    + "\n"
                    + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:00:00.001Z\n"
                    + "#EXTINF:6,\nmain0.0.ts\n"
                    + "#EXTINF:6,\nmain1.0.ts\n"
                    + "#EXTINF:6,\nmain2.0.ts\n"
                    + "\n"))
        .containsExactly(
            new AdPlaybackState("adsId")
                .withAdResumePositionUs(0)
                .withLivePostrollPlaceholderAppended(/* isServerSideInserted= */ false));
  }

  @Test
  public void handleContentTimelineChanged_preRollWithAssetList_resolveAssetListImmediately()
      throws IOException, TimeoutException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:9\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:00:00.000Z\n"
            + "#EXTINF:9,\n"
            + "main0.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T22:00:00.000Z\","
            + "CUE=\"PRE\","
            + "X-ASSET-LIST=\"http://example.com/assetlist-0-0.json\""
            + "\n";
    when(mockPlayer.getContentPosition()).thenReturn(0L);
    AdPlaybackState expectedAdPlaybackStateAtTimelineChange =
        new AdPlaybackState("adsId", 0L)
            .withAdCount(/* adGroupIndex= */ 0, 1)
            .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, "ad0-0");

    callHandleContentTimelineChangedAndCaptureAdPlaybackState(
        playlistString,
        adsLoader,
        /* windowIndex= */ 0,
        /* windowPositionInPeriodUs= */ 0,
        /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE);

    runMainLooperUntil(assetListLoadingListener::completed, TIMEOUT_MS, Clock.DEFAULT);
    verify(mockAdsLoaderListener)
        .onAssetListLoadCompleted(
            contentMediaItem,
            "adsId",
            /* adGroupIndex= */ 0,
            /* adIndexInAdGroup= */ 0,
            new AssetList(
                ImmutableList.of(new Asset(Uri.parse("http://0"), /* durationUs= */ 10_123_000L)),
                ImmutableList.of(),
                /* skipInfo= */ null));
    ArgumentCaptor<AdPlaybackState> adPlaybackStateCaptor =
        ArgumentCaptor.forClass(AdPlaybackState.class);
    verify(mockEventListener, times(2)).onAdPlaybackState(adPlaybackStateCaptor.capture());
    assertThat(adPlaybackStateCaptor.getAllValues())
        .containsExactly(
            expectedAdPlaybackStateAtTimelineChange,
            expectedAdPlaybackStateAtTimelineChange
                .withAdDurationsUs(/* adGroupIndex= */ 0, 10_123_000L)
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://0")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withContentResumeOffsetUs(/* adGroupIndex= */ 0, 10_123_000L))
        .inOrder();
    InOrder inOrder = inOrder(mockPlayer);
    inOrder.verify(mockPlayer).addListener(any());
    verifyTimelineUpdate(inOrder, mockPlayer, /* verifyMessageScheduled= */ false);
    verifyAssetListLoadCompleted(inOrder, mockPlayer, /* verifyMessageScheduled= */ false);
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void
      handleContentTimelineChanged_preRollWithAssetList_resolvesAndSchedulesNextPlayerMessage()
          throws IOException, TimeoutException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:9\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:00:00.000Z\n"
            + "#EXTINF:9,\n"
            + "main0.ts\n"
            + "#EXTINF:81,\n"
            + "main0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T22:00:00.000Z\","
            + "CUE=\"PRE\","
            + "X-ASSET-LIST=\"http://example.com/assetlist-0-0.json\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad1-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:00:30.000Z\","
            + "X-ASSET-LIST=\"http://example.com/assetlist-1-0.json\""
            + "\n";
    when(mockPlayer.getContentPosition()).thenReturn(0L);
    PlayerMessage midRollPlayerMessage =
        new PlayerMessage(
            mock(PlayerMessage.Sender.class),
            mock(PlayerMessage.Target.class),
            Timeline.EMPTY,
            /* defaultMediaItemIndex= */ 0,
            /* Clock ignored */ null,
            /* Looper ignored */ null);
    when(mockPlayer.createMessage(any())).thenReturn(midRollPlayerMessage);

    callHandleContentTimelineChangedAndCaptureAdPlaybackState(
        playlistString,
        adsLoader,
        /* windowIndex= */ 0,
        /* windowPositionInPeriodUs= */ 0,
        /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE);

    runMainLooperUntil(assetListLoadingListener::completed, TIMEOUT_MS, Clock.DEFAULT);
    verify(mockAdsLoaderListener)
        .onAssetListLoadCompleted(eq(contentMediaItem), eq("adsId"), eq(0), eq(0), any());
    assertThat(midRollPlayerMessage.getPositionMs()).isEqualTo(3_000L);
    assertThat(midRollPlayerMessage.getPayload()).isEqualTo(contentMediaItem);
    assertThat(midRollPlayerMessage.getLooper()).isEqualTo(Looper.myLooper());
    InOrder inOrder = inOrder(mockPlayer);
    inOrder.verify(mockPlayer).addListener(any());
    verifyTimelineUpdate(inOrder, mockPlayer, /* verifyMessageScheduled= */ false);
    verifyAssetListLoadCompleted(inOrder, mockPlayer, /* verifyMessageScheduled= */ true);
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void
      handleContentTimelineChanged_startPositionAfterMidRollTimeUs_resolvesAndSchedulesMidRoll()
          throws IOException, TimeoutException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:30\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:00:00.000Z\n"
            + "#EXTINF:30,\n"
            + "main0.ts\n"
            + "#EXTINF:30,\n"
            + "main1.ts\n"
            + "#EXTINF:30,\n"
            + "main2.ts\n"
            + "#EXTINF:30,\n"
            + "main3.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:00:00.000Z\","
            + "CUE=\"PRE\","
            + "X-ASSET-LIST=\"http://example.com/assetlist-0-0.json\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad1-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:00:15.000Z\","
            + "X-ASSET-LIST=\"http://example.com/assetlist-1-0.json\""
            + "\n";
    when(mockPlayer.getContentPosition()).thenReturn(52_000L);

    callHandleContentTimelineChangedAndCaptureAdPlaybackState(
        playlistString,
        adsLoader,
        /* windowIndex= */ 0,
        /* windowPositionInPeriodUs= */ 0L,
        /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE);

    runMainLooperUntil(assetListLoadingListener::completed, TIMEOUT_MS, Clock.DEFAULT);
    verify(mockAdsLoaderListener)
        .onAssetListLoadStarted(eq(contentMediaItem), eq("adsId"), eq(1), eq(0));
    verify(mockAdsLoaderListener)
        .onAssetListLoadCompleted(eq(contentMediaItem), eq("adsId"), eq(1), eq(0), any());
  }

  @Test
  public void handleContentTimelineChanged_assetListWithMultipleAssets_resolvesAndExpandsAdGroup()
      throws IOException, TimeoutException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:9\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:00:00.000Z\n"
            + "#EXTINF:9,\n"
            + "main0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T22:00:00.000Z\","
            + "CUE=\"PRE\","
            + "X-ASSET-LIST=\"http://three-assets\""
            + "\n";

    when(mockPlayer.getContentPosition()).thenReturn(0L);
    AdPlaybackState expectedAdPlaybackStateAtTimelineChange =
        new AdPlaybackState("adsId", 0L)
            .withAdCount(/* adGroupIndex= */ 0, 1)
            .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, "ad0-0");

    assertThat(
            callHandleContentTimelineChangedAndCaptureAdPlaybackState(
                playlistString,
                adsLoader,
                /* windowIndex= */ 0,
                /* windowPositionInPeriodUs= */ 0,
                /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE))
        .isEqualTo(expectedAdPlaybackStateAtTimelineChange);

    runMainLooperUntil(assetListLoadingListener::completed, TIMEOUT_MS, Clock.DEFAULT);
    ArgumentCaptor<AdPlaybackState> adPlaybackStateCaptor =
        ArgumentCaptor.forClass(AdPlaybackState.class);
    verify(mockEventListener, times(2)).onAdPlaybackState(adPlaybackStateCaptor.capture());
    assertThat(adPlaybackStateCaptor.getAllValues())
        .containsExactly(
            expectedAdPlaybackStateAtTimelineChange,
            expectedAdPlaybackStateAtTimelineChange
                .withAdCount(/* adGroupIndex= */ 0, 3)
                .withAdDurationsUs(/* adGroupIndex= */ 0, 10_123_000L, 11_123_000L, 12_123_000L)
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://0")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 1,
                    new MediaItem.Builder()
                        .setUri("http://1")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 2,
                    new MediaItem.Builder()
                        .setUri("http://2")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withContentResumeOffsetUs(
                    /* adGroupIndex= */ 0, 10_123_000L + 11_123_000L + 12_123_000L))
        .inOrder();
    InOrder inOrder = inOrder(mockPlayer);
    inOrder.verify(mockPlayer).addListener(any());
    verifyTimelineUpdate(inOrder, mockPlayer, /* verifyMessageScheduled= */ false);
    verifyAssetListLoadCompleted(inOrder, mockPlayer, /* verifyMessageScheduled= */ false);
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void handleContentTimelineChanged_loadingAssetListFails_marksAdAndSchedulesNextMessage()
      throws IOException, TimeoutException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:9\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:00:00.000Z\n"
            + "#EXTINF:9,\n"
            + "main0.0.ts\n"
            + "#EXTINF:9,\n"
            + "main1.0.ts\n"
            + "#EXTINF:9,\n"
            + "main2.0.ts\n"
            + "#EXTINF:7.001,\n"
            + "main3.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T22:00:00.000Z\","
            + "CUE=\"PRE\","
            + "X-ASSET-LIST=\"http://invalid\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad1-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:00:34.000Z\","
            + "X-ASSET-LIST=\"http://example.com/assetlist-1-0.json\""
            + "\n";
    when(mockPlayer.getContentPosition()).thenReturn(0L);
    PlayerMessage midRollPlayerMessage =
        new PlayerMessage(
            mock(PlayerMessage.Sender.class),
            mock(PlayerMessage.Target.class),
            Timeline.EMPTY,
            /* defaultMediaItemIndex= */ 0,
            /* Clock ignored */ null,
            /* Looper ignored */ null);
    when(mockPlayer.createMessage(any())).thenReturn(midRollPlayerMessage);
    AdPlaybackState expectedAdPlaybackStateAtTimelineChange =
        new AdPlaybackState("adsId", 0L, 34_000_000L)
            .withAdCount(/* adGroupIndex= */ 0, 1)
            .withAdCount(/* adGroupIndex= */ 1, 1)
            .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, "ad0-0")
            .withAdId(/* adGroupIndex= */ 1, /* adIndexInAdGroup= */ 0, "ad1-0");

    callHandleContentTimelineChangedAndCaptureAdPlaybackState(
        playlistString,
        adsLoader,
        /* windowIndex= */ 1,
        /* windowPositionInPeriodUs= */ 0,
        /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE);

    runMainLooperUntil(assetListLoadingListener::failed, TIMEOUT_MS, Clock.DEFAULT);
    ArgumentCaptor<AdPlaybackState> adPlaybackStateCaptor =
        ArgumentCaptor.forClass(AdPlaybackState.class);
    verify(mockEventListener, times(2)).onAdPlaybackState(adPlaybackStateCaptor.capture());
    assertThat(adPlaybackStateCaptor.getAllValues())
        .containsExactly(
            expectedAdPlaybackStateAtTimelineChange,
            expectedAdPlaybackStateAtTimelineChange.withAdLoadError(
                /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0))
        .inOrder();
    assertThat(midRollPlayerMessage.getPositionMs()).isEqualTo(7_000L);
    InOrder inOrder = inOrder(mockPlayer);
    inOrder.verify(mockPlayer).addListener(any());
    verifyTimelineUpdate(inOrder, mockPlayer, /* verifyMessageScheduled= */ false);
    verifyAssetListLoadCompleted(inOrder, mockPlayer, /* verifyMessageScheduled= */ true);
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void handleContentTimelineChanged_emptyAssetList_marksAdAsFailedAndSchedulesNextMessage()
      throws IOException, TimeoutException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:9\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:00:00.000Z\n"
            + "#EXTINF:9,\n"
            + "main0.0.ts\n"
            + "#EXTINF:81,\n"
            + "main0.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T22:00:00.000Z\","
            + "CUE=\"PRE\","
            + "X-ASSET-LIST=\"http://example.com/assetlist-0-0.json\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad1-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:00:21.000Z\","
            + "X-ASSET-LIST=\"http://empty\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad2-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:00:51.000Z\","
            + "X-ASSET-LIST=\"http://example.com/assetlist-2-0.json\""
            + "\n";
    when(mockPlayer.getContentPosition()).thenReturn(0L);
    PlayerMessage midRollPlayerMessage =
        new PlayerMessage(
            mock(PlayerMessage.Sender.class),
            mock(PlayerMessage.Target.class),
            Timeline.EMPTY,
            /* defaultMediaItemIndex= */ 0,
            /* Clock ignored */ null,
            /* Looper ignored */ null);
    when(mockPlayer.createMessage(any())).thenReturn(midRollPlayerMessage);
    AdPlaybackState expectedAdPlaybackStateAtTimelineChange =
        new AdPlaybackState("adsId", 0L, 21_000_000L, 51_000_000L)
            .withAdCount(/* adGroupIndex= */ 0, 1)
            .withAdCount(/* adGroupIndex= */ 1, 1)
            .withAdCount(/* adGroupIndex= */ 2, 1)
            .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, "ad0-0")
            .withAdId(/* adGroupIndex= */ 1, /* adIndexInAdGroup= */ 0, "ad1-0")
            .withAdId(/* adGroupIndex= */ 2, /* adIndexInAdGroup= */ 0, "ad2-0");
    when(mockPlayer.getContentPosition()).thenReturn(21_000L);

    callHandleContentTimelineChangedAndCaptureAdPlaybackState(
        playlistString,
        adsLoader,
        /* windowIndex= */ 1,
        /* windowPositionInPeriodUs= */ 0,
        /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE);

    runMainLooperUntil(assetListLoadingListener::failed, TIMEOUT_MS, Clock.DEFAULT);
    verify(mockAdsLoaderListener)
        .onAssetListLoadFailed(any(), eq("adsId"), eq(1), eq(0), isNotNull(), eq(false));
    ArgumentCaptor<AdPlaybackState> adPlaybackStateCaptor =
        ArgumentCaptor.forClass(AdPlaybackState.class);
    verify(mockEventListener, times(2)).onAdPlaybackState(adPlaybackStateCaptor.capture());
    assertThat(adPlaybackStateCaptor.getAllValues())
        .containsExactly(
            expectedAdPlaybackStateAtTimelineChange,
            expectedAdPlaybackStateAtTimelineChange.withAdLoadError(
                /* adGroupIndex= */ 1, /* adIndexInAdGroup= */ 0))
        .inOrder();
    assertThat(midRollPlayerMessage.getPositionMs()).isEqualTo(24_000L);
    InOrder inOrder = inOrder(mockPlayer);
    inOrder.verify(mockPlayer).addListener(any());
    verifyTimelineUpdate(inOrder, mockPlayer, /* verifyMessageScheduled= */ false);
    verifyAssetListLoadCompleted(inOrder, mockPlayer, /* verifyMessageScheduled= */ true);
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void
      handleContentTimelineChanged_publicPlaceholderPeriod_useDefaultPositionToScheduleMessage()
          throws IOException, TimeoutException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:9\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:00:00.000Z\","
            + "X-ASSET-LIST=\"http://example.com/assetlist-0-0.json\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad1-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "DURATION=3.246,"
            + "START-DATE=\"2020-01-02T21:00:15.000Z\","
            + "X-ASSET-LIST=\"http://example.com/assetlist-1-0.json\""
            + "\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:00:00.000Z\n"
            + "#EXTINF:9,\nmain0.0.ts\n"
            + "#EXTINF:9,\nmain1.0.ts\n"
            + "#EXTINF:9,\nmain2.0.ts\n"
            + "#EXTINF:3,\nmain3.0.ts\n";
    when(mockPlayer.getContentPosition()).thenReturn(0L);
    contentWindowDefinition =
        contentWindowDefinition
            .buildUpon()
            .setPlaceholder(true)
            .setDynamic(true)
            .setLive(true)
            .setDefaultPositionUs(15_000_000L)
            .build();

    callHandleContentTimelineChangedAndCaptureAdPlaybackState(
        playlistString,
        adsLoader,
        /* windowIndex= */ 2,
        /* windowPositionInPeriodUs= */ 0,
        /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE);

    runMainLooperUntil(assetListLoadingListener::completed, TIMEOUT_MS, Clock.DEFAULT);
    verify(mockAdsLoaderListener)
        .onAssetListLoadCompleted(eq(contentMediaItem), eq("adsId"), eq(1), eq(0), isNotNull());
    verify(mockEventListener, times(2)).onAdPlaybackState(any());
    InOrder inOrder = inOrder(mockPlayer);
    inOrder.verify(mockPlayer).addListener(any());
    // Timeline change immediately starts asset list resolution using the default position instead
    // of the current content position.
    inOrder.verify(mockPlayer).getCurrentMediaItem();
    inOrder.verify(mockPlayer).getCurrentPeriodIndex();
    inOrder.verify(mockPlayer).getCurrentTimeline();
    verifyAssetListLoadCompleted(inOrder, mockPlayer, /* verifyMessageScheduled= */ false);
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void
      handleContentTimelineChanged_assetListAndAssetUriMixture_adPlaybackStateCorrectlyPopulated()
          throws IOException, TimeoutException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:9\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:00:00.000Z\n"
            + "#EXTINF:9,\n"
            + "main0.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T22:00:00.000Z\","
            + "CUE=\"PRE\","
            + "X-ASSET-LIST=\"http://three-assets\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad1-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "DURATION=3.246,"
            + "START-DATE=\"2020-01-02T22:00:00.000Z\","
            + "CUE=\"PRE\","
            + "X-ASSET-URI=\"http://example.com/media-1-0.ts\""
            + "\n";
    when(mockPlayer.getContentPosition()).thenReturn(0L);
    when(mockPlayer.createMessage(any()))
        .thenReturn(
            new PlayerMessage(
                mock(PlayerMessage.Sender.class),
                mock(PlayerMessage.Target.class),
                Timeline.EMPTY,
                /* defaultMediaItemIndex= */ 0,
                /* Clock ignored */ null,
                /* Looper ignored */ null));
    AdPlaybackState expectedAdPlaybackStateAtTimelineChange =
        new AdPlaybackState("adsId", 0L)
            .withAdCount(/* adGroupIndex= */ 0, 2)
            .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, "ad0-0")
            .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1, "ad1-0")
            .withAdDurationsUs(/* adGroupIndex= */ 0, C.TIME_UNSET, 3_246_000L)
            .withContentResumeOffsetUs(/* adGroupIndex= */ 0, 3_246_000L)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 1,
                new MediaItem.Builder()
                    .setUri("http://example.com/media-1-0.ts")
                    .setMimeType("application/x-mpegURL")
                    .build());

    callHandleContentTimelineChangedAndCaptureAdPlaybackState(
        playlistString,
        adsLoader,
        /* windowIndex= */ 0,
        /* windowPositionInPeriodUs= */ 0,
        /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE);

    runMainLooperUntil(assetListLoadingListener::completed, TIMEOUT_MS, Clock.DEFAULT);
    ArgumentCaptor<AdPlaybackState> adPlaybackStateCaptor =
        ArgumentCaptor.forClass(AdPlaybackState.class);
    verify(mockEventListener, times(2)).onAdPlaybackState(adPlaybackStateCaptor.capture());
    assertThat(adPlaybackStateCaptor.getAllValues())
        .containsExactly(
            expectedAdPlaybackStateAtTimelineChange,
            expectedAdPlaybackStateAtTimelineChange
                .withAdCount(/* adGroupIndex= */ 0, 4)
                .withAdDurationsUs(
                    /* adGroupIndex= */ 0, 10_123_000L, 3_246_000L, 11_123_000L, 12_123_000L)
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://0")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 2,
                    new MediaItem.Builder()
                        .setUri("http://1")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 3,
                    new MediaItem.Builder()
                        .setUri("http://2")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withContentResumeOffsetUs(
                    /* adGroupIndex= */ 0, 3_246_000L + 10_123_000L + 11_123_000L + 12_123_000L))
        .inOrder();
  }

  @Test
  public void
      handleContentTimelineChanged_playlistWithSkipInformation_adPlaybackStateCorrectlyPopulated()
          throws IOException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:9\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:00:00.000Z\n"
            + "#EXTINF:9,\n"
            + "main0.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "DURATION=3.246,"
            + "START-DATE=\"2020-01-02T22:00:00.000Z\","
            + "CUE=\"PRE\","
            + "X-SKIP-CONTROL-OFFSET=4.5,"
            + "X-SKIP-CONTROL-DURATION=5.5,"
            + "X-SKIP-CONTROL-LABEL-ID=\"skip_label_from_playlist\","
            + "X-ASSET-URI=\"http://example.com/media-0-0.ts\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-1\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "DURATION=3.246,"
            + "START-DATE=\"2020-01-02T22:00:00.000Z\","
            + "CUE=\"PRE\","
            + "X-SKIP-CONTROL-OFFSET=6.5,"
            + "X-ASSET-URI=\"http://example.com/media-0-1.ts\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-2\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "DURATION=3.246,"
            + "START-DATE=\"2020-01-02T22:00:00.000Z\","
            + "CUE=\"PRE\","
            + "X-SKIP-CONTROL-DURATION=7.5,"
            + "X-ASSET-URI=\"http://example.com/media-0-2.ts\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-3\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "DURATION=3.246,"
            + "START-DATE=\"2020-01-02T22:00:00.000Z\","
            + "CUE=\"PRE\","
            + "X-SKIP-CONTROL-LABEL-ID=\"skip_label_from_playlist\","
            + "X-ASSET-URI=\"http://example.com/media-0-3.ts\""
            + "\n";
    when(mockPlayer.getContentPosition()).thenReturn(0L);
    AdPlaybackState expectedAdPlaybackStateAtTimelineChange =
        new AdPlaybackState("adsId", 0L)
            .withAdCount(/* adGroupIndex= */ 0, 4)
            .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, "ad0-0")
            .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1, "ad0-1")
            .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 2, "ad0-2")
            .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 3, "ad0-3")
            .withAdDurationsUs(
                /* adGroupIndex= */ 0, 3_246_000L, 3_246_000L, 3_246_000L, 3_246_000L)
            .withContentResumeOffsetUs(/* adGroupIndex= */ 0, 4 * 3_246_000L)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 0,
                new MediaItem.Builder()
                    .setUri("http://example.com/media-0-0.ts")
                    .setMimeType("application/x-mpegURL")
                    .build())
            .withAdSkipInfo(
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 0,
                new SkipInfo(
                    /* skipOffsetUs= */ 4_500_000L,
                    /* skipDurationUs= */ 5_500_000L,
                    /* labelId= */ "skip_label_from_playlist"))
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 1,
                new MediaItem.Builder()
                    .setUri("http://example.com/media-0-1.ts")
                    .setMimeType("application/x-mpegURL")
                    .build())
            .withAdSkipInfo(
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 1,
                new SkipInfo(
                    /* skipOffsetUs= */ 6_500_000L,
                    /* skipDurationUs= */ C.TIME_UNSET,
                    /* labelId= */ null))
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 2,
                new MediaItem.Builder()
                    .setUri("http://example.com/media-0-2.ts")
                    .setMimeType("application/x-mpegURL")
                    .build())
            .withAdSkipInfo(
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 2,
                new SkipInfo(
                    /* skipOffsetUs= */ 0L, /* skipDurationUs= */ 7_500_000L, /* labelId= */ null))
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 3,
                new MediaItem.Builder()
                    .setUri("http://example.com/media-0-3.ts")
                    .setMimeType("application/x-mpegURL")
                    .build())
            .withAdSkipInfo(
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 3,
                new SkipInfo(
                    /* skipOffsetUs= */ 0L,
                    /* skipDurationUs= */ C.TIME_UNSET,
                    /* labelId= */ "skip_label_from_playlist"));

    callHandleContentTimelineChangedAndCaptureAdPlaybackState(
        playlistString,
        adsLoader,
        /* windowIndex= */ 0,
        /* windowPositionInPeriodUs= */ 0,
        /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE);

    ArgumentCaptor<AdPlaybackState> adPlaybackStateCaptor =
        ArgumentCaptor.forClass(AdPlaybackState.class);
    verify(mockEventListener).onAdPlaybackState(adPlaybackStateCaptor.capture());
    assertThat(adPlaybackStateCaptor.getAllValues())
        .containsExactly(expectedAdPlaybackStateAtTimelineChange);
  }

  @Test
  public void
      handleContentTimelineChanged_assetListWithSkipInformation_resolvesAndSetsSkipInfoOnEveryAd()
          throws IOException, TimeoutException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:9\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:00:00.000Z\n"
            + "#EXTINF:9,\n"
            + "main0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T22:00:00.000Z\","
            + "CUE=\"PRE\","
            + "X-ASSET-LIST=\"http://three-assets-skip-info\""
            + "\n";
    when(mockPlayer.getContentPosition()).thenReturn(0L);
    AdPlaybackState expectedAdPlaybackStateAtTimelineChange =
        new AdPlaybackState("adsId", 0L)
            .withAdCount(/* adGroupIndex= */ 0, 1)
            .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, "ad0-0");
    SkipInfo expectedSkipInfo =
        new SkipInfo(
            /* skipOffsetUs= */ 5_500_000L, /* skipDurationUs= */ 6_500_000L, /* labelId= */ null);

    assertThat(
            callHandleContentTimelineChangedAndCaptureAdPlaybackState(
                playlistString,
                adsLoader,
                /* windowIndex= */ 0,
                /* windowPositionInPeriodUs= */ 0,
                /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE))
        .isEqualTo(expectedAdPlaybackStateAtTimelineChange);

    runMainLooperUntil(assetListLoadingListener::completed, TIMEOUT_MS, Clock.DEFAULT);
    ArgumentCaptor<AdPlaybackState> adPlaybackStateCaptor =
        ArgumentCaptor.forClass(AdPlaybackState.class);
    verify(mockEventListener, times(2)).onAdPlaybackState(adPlaybackStateCaptor.capture());
    assertThat(adPlaybackStateCaptor.getAllValues())
        .containsExactly(
            expectedAdPlaybackStateAtTimelineChange,
            expectedAdPlaybackStateAtTimelineChange
                .withAdCount(/* adGroupIndex= */ 0, 3)
                .withAdDurationsUs(/* adGroupIndex= */ 0, 10_123_000L, 11_123_000L, 12_123_000L)
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://0")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withAdSkipInfo(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 0,
                    /* skipInfo= */ expectedSkipInfo)
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 1,
                    new MediaItem.Builder()
                        .setUri("http://1")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withAdSkipInfo(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 1,
                    /* skipInfo= */ expectedSkipInfo)
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 2,
                    new MediaItem.Builder()
                        .setUri("http://2")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withAdSkipInfo(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 2,
                    /* skipInfo= */ expectedSkipInfo)
                .withContentResumeOffsetUs(
                    /* adGroupIndex= */ 0, 10_123_000L + 11_123_000L + 12_123_000L))
        .inOrder();
    InOrder inOrder = inOrder(mockPlayer);
    inOrder.verify(mockPlayer).addListener(any());
    verifyTimelineUpdate(inOrder, mockPlayer, /* verifyMessageScheduled= */ false);
    verifyAssetListLoadCompleted(inOrder, mockPlayer, /* verifyMessageScheduled= */ false);
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void
      handleContentTimelineChanged_assetListWithSkipInfoDefaults_resolvesAndSetsSkipInfoOnEveryAd()
          throws IOException, TimeoutException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:9\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:00:00.000Z\n"
            + "#EXTINF:9,\n"
            + "main0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T22:00:00.000Z\","
            + "CUE=\"PRE\","
            + "X-ASSET-LIST=\"http://three-assets-skip-info-label-only\""
            + "\n";
    when(mockPlayer.getContentPosition()).thenReturn(0L);
    AdPlaybackState expectedAdPlaybackStateAtTimelineChange =
        new AdPlaybackState("adsId", 0L)
            .withAdCount(/* adGroupIndex= */ 0, 1)
            .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, "ad0-0");
    SkipInfo expectedSkipInfo =
        new SkipInfo(
            /* skipOffsetUs= */ 0,
            /* skipDurationUs= */ C.TIME_UNSET,
            /* labelId= */ "skip_label_from_json");

    assertThat(
            callHandleContentTimelineChangedAndCaptureAdPlaybackState(
                playlistString,
                adsLoader,
                /* windowIndex= */ 0,
                /* windowPositionInPeriodUs= */ 0,
                /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE))
        .isEqualTo(expectedAdPlaybackStateAtTimelineChange);

    runMainLooperUntil(assetListLoadingListener::completed, TIMEOUT_MS, Clock.DEFAULT);
    ArgumentCaptor<AdPlaybackState> adPlaybackStateCaptor =
        ArgumentCaptor.forClass(AdPlaybackState.class);
    verify(mockEventListener, times(2)).onAdPlaybackState(adPlaybackStateCaptor.capture());
    assertThat(adPlaybackStateCaptor.getAllValues())
        .containsExactly(
            expectedAdPlaybackStateAtTimelineChange,
            expectedAdPlaybackStateAtTimelineChange
                .withAdCount(/* adGroupIndex= */ 0, 3)
                .withAdDurationsUs(/* adGroupIndex= */ 0, 10_123_000L, 11_123_000L, 12_123_000L)
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://0")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withAdSkipInfo(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 0,
                    /* skipInfo= */ expectedSkipInfo)
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 1,
                    new MediaItem.Builder()
                        .setUri("http://1")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withAdSkipInfo(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 1,
                    /* skipInfo= */ expectedSkipInfo)
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 2,
                    new MediaItem.Builder()
                        .setUri("http://2")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withAdSkipInfo(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 2,
                    /* skipInfo= */ expectedSkipInfo)
                .withContentResumeOffsetUs(
                    /* adGroupIndex= */ 0, 10_123_000L + 11_123_000L + 12_123_000L))
        .inOrder();
    InOrder inOrder = inOrder(mockPlayer);
    inOrder.verify(mockPlayer).addListener(any());
    verifyTimelineUpdate(inOrder, mockPlayer, /* verifyMessageScheduled= */ false);
    verifyAssetListLoadCompleted(inOrder, mockPlayer, /* verifyMessageScheduled= */ false);
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void timelineChange_mediaItemsClearedWithoutPositionDiscontinuity_cancelsPendingMessage()
      throws IOException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:9\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:00:00.000Z\n"
            + "#EXTINF:9,\n"
            + "main0.0.ts\n"
            + "#EXTINF:9,\n"
            + "main1.0.ts\n"
            + "#EXTINF:9,\n"
            + "main2.0.ts\n"
            + "#EXTINF:9,\n"
            + "main3.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:00:30.000Z\","
            + "X-ASSET-LIST=\"http://example.com/assetlist-0-0.json\""
            + "\n";
    when(mockPlayer.getContentPosition()).thenReturn(0L);
    PlayerMessage playerMessage =
        new PlayerMessage(
            mock(PlayerMessage.Sender.class),
            mock(PlayerMessage.Target.class),
            Timeline.EMPTY,
            /* defaultMediaItemIndex= */ 0,
            /* Clock ignored */ null,
            /* Looper ignored */ null);
    when(mockPlayer.createMessage(any())).thenReturn(playerMessage);
    callHandleContentTimelineChangedAndCaptureAdPlaybackState(
        playlistString,
        adsLoader,
        /* windowIndex= */ 0,
        /* windowPositionInPeriodUs= */ 0,
        /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE);
    ArgumentCaptor<Player.Listener> listener = ArgumentCaptor.forClass(Player.Listener.class);
    InOrder inOrder = inOrder(mockPlayer);
    inOrder.verify(mockPlayer).addListener(listener.capture());
    verifyTimelineUpdate(inOrder, mockPlayer, /* verifyMessageScheduled= */ true);

    listener.getValue().onTimelineChanged(Timeline.EMPTY, TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED);

    assertThat(playerMessage.isCanceled()).isTrue();
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void handleMessage_playerMessageExecuted_resolvesAssetListAndSchedulesNextMessage()
      throws IOException, TimeoutException, ExoPlaybackException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:9\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:00:00.000Z\n"
            + "#EXTINF:9,\n"
            + "main0.0.ts\n"
            + "#EXTINF:81,\n"
            + "main0.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:00:30.000Z\","
            + "X-ASSET-LIST=\"http://example.com/assetlist-0-0.json\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad1-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:00:54.000Z\","
            + "X-ASSET-LIST=\"http://example.com/assetlist-1-0.json\""
            + "\n";
    PlayerMessage midRoll2PlayerMessage =
        new PlayerMessage(
            mock(PlayerMessage.Sender.class),
            mock(PlayerMessage.Target.class),
            Timeline.EMPTY,
            /* defaultMediaItemIndex= */ 0,
            /* Clock ignored */ null,
            /* Looper ignored */ null);
    when(mockPlayer.getContentPosition()).thenReturn(0L);
    when(mockPlayer.createMessage(any()))
        .thenReturn(
            new PlayerMessage(
                mock(PlayerMessage.Sender.class),
                mock(PlayerMessage.Target.class),
                Timeline.EMPTY,
                /* defaultMediaItemIndex= */ 0,
                /* Clock ignored */ null,
                /* Looper ignored */ null));
    callHandleContentTimelineChangedAndCaptureAdPlaybackState(
        playlistString,
        adsLoader,
        /* windowIndex= */ 2,
        /* windowPositionInPeriodUs= */ 0,
        /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE);
    when(mockPlayer.getCurrentPeriodIndex()).thenReturn(2);
    when(mockPlayer.getCurrentMediaItemIndex()).thenReturn(2);
    InOrder inOrder = inOrder(mockPlayer);
    // Timeline change schedules asset list resolution.
    verifyTimelineUpdate(inOrder, mockPlayer, /* verifyMessageScheduled= */ false);
    ArgumentCaptor<PlayerMessage.Target> targetCaptor =
        ArgumentCaptor.forClass(PlayerMessage.Target.class);
    inOrder.verify(mockPlayer).createMessage(targetCaptor.capture());
    when(mockPlayer.createMessage(any())).thenReturn(midRoll2PlayerMessage);

    targetCaptor.getValue().handleMessage(/* ignored */ -1, contentMediaItem);

    runMainLooperUntil(assetListLoadingListener::completed, TIMEOUT_MS, Clock.DEFAULT);
    assertThat(midRoll2PlayerMessage.isCanceled()).isFalse();
    assertThat(midRoll2PlayerMessage.getPositionMs()).isEqualTo(27_000L);
    assertThat(midRoll2PlayerMessage.getPayload()).isEqualTo(contentMediaItem);
    assertThat(midRoll2PlayerMessage.getLooper()).isEqualTo(Looper.myLooper());
  }

  @Test
  public void positionDiscontinuity_reasonSeek_immediatelyResolvesAndSchedulesNextMessages()
      throws IOException, TimeoutException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:9\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:00:00.000Z\n"
            + "#EXTINF:9,\n"
            + "main0.0.ts\n"
            + "#EXTINF:81,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T22:00:00.000Z\","
            + "CUE=\"PRE\","
            + "X-ASSET-LIST=\"http://example.com/assetlist-0-0.json\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad1-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:00:30.000Z\","
            + "X-ASSET-LIST=\"http://example.com/assetlist-1-0.json\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad2-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:00:54.000Z\","
            + "X-ASSET-LIST=\"http://example.com/assetlist-2-0.json\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad3-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T20:00:00.000Z\","
            + "CUE=\"POST\","
            + "X-ASSET-LIST=\"http://example.com/assetlist-3-0.json\""
            + "\n";
    Object windowUid = new Object();
    Object periodUid = new Object();
    PlayerMessage midRoll1PlayerMessage =
        new PlayerMessage(
            mock(PlayerMessage.Sender.class),
            mock(PlayerMessage.Target.class),
            Timeline.EMPTY,
            0,
            /* Clock ignored */ null,
            /* Looper ignored */ null);
    PlayerMessage midRoll2PlayerMessage =
        new PlayerMessage(
            mock(PlayerMessage.Sender.class),
            mock(PlayerMessage.Target.class),
            Timeline.EMPTY,
            0,
            /* Clock ignored */ null,
            /* Looper ignored */ null);
    PlayerMessage postRollPlayerMessage =
        new PlayerMessage(
            mock(PlayerMessage.Sender.class),
            mock(PlayerMessage.Target.class),
            Timeline.EMPTY,
            0,
            /* Clock ignored */ null,
            /* Looper ignored */ null);
    Player.PositionInfo positionZero =
        new Player.PositionInfo(
            windowUid,
            /* mediaItemIndex= */ 1,
            contentMediaItem,
            periodUid,
            /* periodIndex= */ 1,
            /* positionMs= */ 0L,
            /* contentPositionMs= */ 0L,
            /* adGroupIndex= */ -1,
            /* adIndexInAdGroup= */ -1);
    when(mockPlayer.getContentPosition()).thenReturn(0L);
    when(mockPlayer.createMessage(any())).thenReturn(midRoll1PlayerMessage);
    callHandleContentTimelineChangedAndCaptureAdPlaybackState(
        playlistString,
        adsLoader,
        /* windowIndex= */ 1,
        /* windowPositionInPeriodUs= */ 0,
        /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE);
    ArgumentCaptor<Player.Listener> listener = ArgumentCaptor.forClass(Player.Listener.class);
    InOrder inOrder = inOrder(mockPlayer);
    inOrder.verify(mockPlayer).addListener(listener.capture());
    // This call must be a no-op because the loader is loading.
    listener
        .getValue()
        .onPositionDiscontinuity(
            /* oldPosition= */ positionZero,
            /* newPosition= */ new Player.PositionInfo(
                windowUid,
                /* mediaItemIndex= */ 1,
                contentMediaItem,
                periodUid,
                /* periodIndex= */ 1,
                /* positionMs= */ 30_001L,
                /* contentPositionMs= */ 30_001L,
                /* adGroupIndex= */ -1,
                /* adIndexInAdGroup= */ -1),
            Player.DISCONTINUITY_REASON_SEEK);
    runMainLooperUntil(assetListLoadingListener::completed, TIMEOUT_MS, Clock.DEFAULT);
    when(mockPlayer.createMessage(any())).thenReturn(midRoll2PlayerMessage);

    // A seek beyond the ad group at 30_000_000 with an unresolved asset list. The asset list for
    // 30_000_000 is immediately loaded and the player message for the next mid roll is scheduled.
    listener
        .getValue()
        .onPositionDiscontinuity(
            /* oldPosition= */ positionZero,
            /* newPosition= */ new Player.PositionInfo(
                windowUid,
                /* mediaItemIndex= */ 1,
                contentMediaItem,
                periodUid,
                /* periodIndex= */ 1,
                /* positionMs= */ 30_001L,
                /* contentPositionMs= */ 30_001L,
                /* adGroupIndex= */ -1,
                /* adIndexInAdGroup= */ -1),
            Player.DISCONTINUITY_REASON_SEEK);

    runMainLooperUntil(
        () -> assetListLoadingListener.assetListLoadCompletedCounter.get() == 2,
        TIMEOUT_MS,
        Clock.DEFAULT);
    when(mockPlayer.createMessage(any())).thenReturn(postRollPlayerMessage);
    // A seek beyond the ad group at 54_000_000 with an unresolved asset list. The asset list for
    // 54_000_000 is immediately loaded and the player message for the post roll is scheduled.
    listener
        .getValue()
        .onPositionDiscontinuity(
            /* oldPosition= */ positionZero,
            /* newPosition= */ new Player.PositionInfo(
                windowUid,
                /* mediaItemIndex= */ 1,
                contentMediaItem,
                periodUid,
                /* periodIndex= */ 1,
                /* positionMs= */ 54_001L,
                /* contentPositionMs= */ 54_001L,
                /* adGroupIndex= */ -1,
                /* adIndexInAdGroup= */ -1),
            Player.DISCONTINUITY_REASON_SEEK);

    runMainLooperUntil(
        () -> assetListLoadingListener.assetListLoadCompletedCounter.get() == 3,
        TIMEOUT_MS,
        Clock.DEFAULT);
    assertThat(midRoll1PlayerMessage.isCanceled()).isTrue();
    assertThat(midRoll1PlayerMessage.getPositionMs()).isEqualTo(3_000L);
    assertThat(midRoll1PlayerMessage.getPayload()).isEqualTo(contentMediaItem);
    assertThat(midRoll1PlayerMessage.getLooper()).isEqualTo(Looper.myLooper());
    assertThat(midRoll2PlayerMessage.isCanceled()).isTrue();
    assertThat(midRoll2PlayerMessage.getPositionMs()).isEqualTo(27_000L);
    assertThat(midRoll2PlayerMessage.getPayload()).isEqualTo(contentMediaItem);
    assertThat(midRoll1PlayerMessage.getLooper()).isEqualTo(Looper.myLooper());
    assertThat(postRollPlayerMessage.isCanceled()).isFalse();
    assertThat(postRollPlayerMessage.getPositionMs()).isEqualTo(63_000L);
    assertThat(postRollPlayerMessage.getPayload()).isEqualTo(contentMediaItem);
    assertThat(midRoll1PlayerMessage.getLooper()).isEqualTo(Looper.myLooper());
    ArgumentCaptor<AssetList> argumentCaptor = ArgumentCaptor.forClass(AssetList.class);
    verify(mockAdsLoaderListener, times(3))
        .onAssetListLoadCompleted(
            eq(contentMediaItem), eq("adsId"), anyInt(), anyInt(), argumentCaptor.capture());
    assertThat(argumentCaptor.getAllValues())
        .containsExactly(
            new AssetList(
                ImmutableList.of(new Asset(Uri.parse("http://0"), 10_123_000L)),
                ImmutableList.of(),
                /* skipInfo= */ null),
            new AssetList(
                ImmutableList.of(new Asset(Uri.parse("http://0"), 10_123_000L)),
                ImmutableList.of(),
                /* skipInfo= */ null),
            new AssetList(
                ImmutableList.of(new Asset(Uri.parse("http://0"), 10_123_000L)),
                ImmutableList.of(),
                /* skipInfo= */ null))
        .inOrder();
    // Timeline change immediately starts asset list resolution.
    verifyTimelineUpdate(inOrder, mockPlayer, /* verifyMessageScheduled= */ false);
    // Position discontinuity during asset list loading.
    inOrder.verify(mockPlayer).getCurrentTimeline();
    verifyAssetListLoadCompleted(inOrder, mockPlayer, /* verifyMessageScheduled= */ true);
    // Position discontinuity immediately starts asset list resolution.
    inOrder.verify(mockPlayer).getCurrentTimeline();
    verifyAssetListLoadCompleted(inOrder, mockPlayer, /* verifyMessageScheduled= */ true);
    // Position discontinuity immediately starts asset list resolution.
    inOrder.verify(mockPlayer).getCurrentTimeline();
    verifyAssetListLoadCompleted(inOrder, mockPlayer, /* verifyMessageScheduled= */ true);
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void positionDiscontinuity_reasonSeek_adPlaybackStatePopulatedCorrectly()
      throws IOException, TimeoutException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:9\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:00:00.000Z\n"
            + "#EXTINF:9,\n"
            + "main0.0.ts\n"
            + "#EXTINF:9,\n"
            + "main1.0.ts\n"
            + "#EXTINF:72,\n"
            + "main2.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:00:30.000Z\","
            + "X-ASSET-LIST=\"http://example.com/assetlist-0-0.json\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad1-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:00:51.000Z\","
            + "X-ASSET-LIST=\"http://example.com/assetlist-1-0.json\""
            + "\n";
    Object windowUid = new Object();
    Object periodUid = new Object();
    when(mockPlayer.getContentPosition()).thenReturn(0L);
    AdPlaybackState expectedAdPlaybackStateAtTimelineChange =
        new AdPlaybackState("adsId", 30_000_000L, 51_000_000L)
            .withAdCount(/* adGroupIndex= */ 0, 1)
            .withAdCount(/* adGroupIndex= */ 1, 1)
            .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, "ad0-0")
            .withAdId(/* adGroupIndex= */ 1, /* adIndexInAdGroup= */ 0, "ad1-0");
    PlayerMessage midRoll1PlayerMessage =
        new PlayerMessage(
            mock(PlayerMessage.Sender.class),
            mock(PlayerMessage.Target.class),
            Timeline.EMPTY,
            /* defaultMediaItemIndex= */ 0,
            /* Clock ignored */ null,
            /* Looper ignored */ null);
    when(mockPlayer.createMessage(any())).thenReturn(midRoll1PlayerMessage);
    callHandleContentTimelineChangedAndCaptureAdPlaybackState(
        playlistString,
        adsLoader,
        /* windowIndex= */ 0,
        /* windowPositionInPeriodUs= */ 0,
        /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE);
    // Emulate position discontinuity.
    ArgumentCaptor<Player.Listener> listener = ArgumentCaptor.forClass(Player.Listener.class);
    verify(mockPlayer).addListener(listener.capture());
    PlayerMessage midRoll2PlayerMessage =
        new PlayerMessage(
            mock(PlayerMessage.Sender.class),
            mock(PlayerMessage.Target.class),
            Timeline.EMPTY,
            /* defaultMediaItemIndex= */ 0,
            /* Clock ignored */ null,
            /* Looper ignored */ null);
    when(mockPlayer.createMessage(any())).thenReturn(midRoll2PlayerMessage);

    listener
        .getValue()
        .onPositionDiscontinuity(
            /* oldPosition= */ new Player.PositionInfo(
                windowUid,
                /* mediaItemIndex= */ 0,
                contentMediaItem,
                periodUid,
                /* periodIndex= */ 0,
                /* positionMs= */ 0L,
                /* contentPositionMs= */ 0L,
                /* adGroupIndex= */ -1,
                /* adIndexInAdGroup= */ -1),
            /* newPosition= */ new Player.PositionInfo(
                windowUid,
                /* mediaItemIndex= */ 0,
                contentMediaItem,
                periodUid,
                /* periodIndex= */ 0,
                /* positionMs= */ 20_000L,
                /* contentPositionMs= */ 20_000L,
                /* adGroupIndex= */ -1,
                /* adIndexInAdGroup= */ -1),
            Player.DISCONTINUITY_REASON_SEEK);

    runMainLooperUntil(assetListLoadingListener::completed, TIMEOUT_MS, Clock.DEFAULT);
    assertThat(midRoll1PlayerMessage.isCanceled()).isTrue();
    assertThat(midRoll1PlayerMessage.getPositionMs()).isEqualTo(3_000L);
    assertThat(midRoll1PlayerMessage.getPayload()).isEqualTo(contentMediaItem);
    assertThat(midRoll2PlayerMessage.isCanceled()).isFalse();
    assertThat(midRoll2PlayerMessage.getPositionMs()).isEqualTo(24_000L);
    assertThat(midRoll2PlayerMessage.getPayload()).isEqualTo(contentMediaItem);
    ArgumentCaptor<AdPlaybackState> adPlaybackStateCaptor =
        ArgumentCaptor.forClass(AdPlaybackState.class);
    verify(mockEventListener, times(2)).onAdPlaybackState(adPlaybackStateCaptor.capture());
    assertThat(adPlaybackStateCaptor.getAllValues())
        .containsExactly(
            expectedAdPlaybackStateAtTimelineChange,
            expectedAdPlaybackStateAtTimelineChange
                .withAdDurationsUs(/* adGroupIndex= */ 0, 10_123_000L)
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://0")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withContentResumeOffsetUs(/* adGroupIndex= */ 0, 10_123_000L))
        .inOrder();
  }

  @Test
  public void
      positionDiscontinuity_reasonSeekToMediaItemWithoutAd_cancelsPendingAssetListResolutionMessage()
          throws IOException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:9\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:00:00.000Z\n"
            + "#EXTINF:9,\n"
            + "main0.0.ts\n"
            + "#EXTINF:9,\n"
            + "main1.0.ts\n"
            + "#EXTINF:9,\n"
            + "main2.0.ts\n"
            + "#EXTINF:9,\n"
            + "main3.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:00:30.000Z\","
            + "X-ASSET-LIST=\"http://example.com/assetlist-0-0.json\""
            + "\n";
    when(mockPlayer.getContentPosition()).thenReturn(0L);
    PlayerMessage playerMessage =
        new PlayerMessage(
            mock(PlayerMessage.Sender.class),
            mock(PlayerMessage.Target.class),
            Timeline.EMPTY,
            /* defaultMediaItemIndex= */ 0,
            /* Clock ignored */ null,
            /* Looper ignored */ null);
    when(mockPlayer.createMessage(any())).thenReturn(playerMessage);
    callHandleContentTimelineChangedAndCaptureAdPlaybackState(
        playlistString,
        adsLoader,
        /* windowIndex= */ 1,
        /* windowPositionInPeriodUs= */ 0,
        /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE);
    // Emulate position discontinuity to a non-ad media item.
    MediaItem nonAdMediaItem = MediaItem.fromUri(Uri.parse("http://example.com/no-ad"));
    ArgumentCaptor<Player.Listener> listener = ArgumentCaptor.forClass(Player.Listener.class);
    InOrder inOrder = inOrder(mockPlayer);
    inOrder.verify(mockPlayer).addListener(listener.capture());

    listener
        .getValue()
        .onPositionDiscontinuity(
            /* oldPosition= */ new Player.PositionInfo(
                /* windowUid= */ new Object(),
                /* mediaItemIndex= */ 1,
                contentMediaItem,
                /* periodUid= */ new Object(),
                /* periodIndex= */ 1,
                /* positionMs= */ 0L,
                /* contentPositionMs= */ 0L,
                /* adGroupIndex= */ -1,
                /* adIndexInAdGroup= */ -1),
            /* newPosition= */ new Player.PositionInfo(
                /* windowUid= */ new Object(),
                /* mediaItemIndex= */ 0,
                nonAdMediaItem,
                /* periodUid= */ new Object(),
                /* periodIndex= */ 0,
                /* positionMs= */ 20_000L,
                /* contentPositionMs= */ 20_000L,
                /* adGroupIndex= */ -1,
                /* adIndexInAdGroup= */ -1),
            Player.DISCONTINUITY_REASON_SEEK);

    assertThat(playerMessage.isCanceled()).isTrue();
    verifyTimelineUpdate(inOrder, mockPlayer, /* verifyMessageScheduled= */ true);
    // Position discontinuity to next media item cancels pending message.
    inOrder.verify(mockPlayer).getCurrentTimeline();
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void positionDiscontinuity_reasonAutoTransition_marksAdAsPlayed() throws IOException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:40.000Z\","
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-1\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:40.000Z\","
            + "X-ASSET-URI=\"http://example.com/media-0-1.m3u8\""
            + "\n";
    callHandleContentTimelineChangedAndCaptureAdPlaybackState(
        playlistString,
        adsLoader,
        /* windowIndex= */ 0,
        /* windowPositionInPeriodUs= */ 0,
        /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE);
    ArgumentCaptor<Player.Listener> listener = ArgumentCaptor.forClass(Player.Listener.class);
    verify(mockPlayer).addListener(listener.capture());
    Object windowUid = new Object();
    Object periodUid = new Object();

    listener
        .getValue()
        .onPositionDiscontinuity(
            new Player.PositionInfo(
                windowUid,
                /* mediaItemIndex= */ 0,
                contentMediaItem,
                periodUid,
                /* periodIndex= */ 0,
                /* positionMs= */ 10_000L,
                /* contentPositionMs= */ 0L,
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 0),
            new Player.PositionInfo(
                windowUid,
                /* mediaItemIndex= */ 0,
                contentMediaItem,
                periodUid,
                /* periodIndex= */ 0,
                /* positionMs= */ 0L,
                /* contentPositionMs= */ 0L,
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 1),
            DISCONTINUITY_REASON_AUTO_TRANSITION);
    listener
        .getValue()
        .onPositionDiscontinuity(
            new Player.PositionInfo(
                windowUid,
                /* mediaItemIndex= */ 0,
                contentMediaItem,
                periodUid,
                /* periodIndex= */ 0,
                /* positionMs= */ 10_000L,
                /* contentPositionMs= */ 0L,
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 1),
            new Player.PositionInfo(
                windowUid,
                /* mediaItemIndex= */ 0,
                contentMediaItem,
                periodUid,
                /* periodIndex= */ 0,
                /* positionMs= */ 0L,
                /* contentPositionMs= */ 0L,
                /* adGroupIndex= */ C.INDEX_UNSET,
                /* adIndexInAdGroup= */ C.INDEX_UNSET),
            DISCONTINUITY_REASON_AUTO_TRANSITION);

    verify(mockAdsLoaderListener)
        .onAdCompleted(
            contentMediaItem,
            adsMediaSource.getAdsId(),
            /* adGroupIndex= */ 0,
            /* adIndexInAdGroup= */ 0);
    verify(mockAdsLoaderListener)
        .onAdCompleted(
            contentMediaItem,
            adsMediaSource.getAdsId(),
            /* adGroupIndex= */ 0,
            /* adIndexInAdGroup= */ 1);
    verify(mockEventListener)
        .onAdPlaybackState(
            new AdPlaybackState("adsId", /* adGroupTimesUs...= */ 0L)
                .withAdDurationsUs(/* adGroupIndex= */ 0, C.TIME_UNSET, C.TIME_UNSET)
                .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 2)
                .withContentResumeOffsetUs(/* adGroupIndex= */ 0, /* contentResumeOffsetUs= */ 0)
                .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, "ad0-0")
                .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1, "ad0-1")
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-0-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 1,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-0-1.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withPlayedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0)
                .withPlayedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1));
  }

  @Test
  public void skipCurrentAd_whenAdIsPlaying_adSkippedAndEventListenerNotified() throws IOException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "CUE=\"PRE\","
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
            + "\n";
    AdPlaybackState initialAdPlaybackState =
        callHandleContentTimelineChangedAndCaptureAdPlaybackState(
            playlistString,
            adsLoader,
            /* windowIndex= */ 0,
            /* windowPositionInPeriodUs= */ 0,
            /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE);
    when(mockPlayer.isPlayingAd()).thenReturn(true);
    when(mockPlayer.getCurrentAdGroupIndex()).thenReturn(0);
    when(mockPlayer.getCurrentAdIndexInAdGroup()).thenReturn(0);
    Timeline timeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setAdPlaybackStates(ImmutableList.of(initialAdPlaybackState))
                .build());
    when(mockPlayer.getCurrentTimeline()).thenReturn(timeline);
    when(mockPlayer.getCurrentPeriodIndex()).thenReturn(0);

    adsLoader.skipCurrentAd();

    ArgumentCaptor<AdPlaybackState> adPlaybackStateArgumentCaptor =
        ArgumentCaptor.forClass(AdPlaybackState.class);
    verify(mockEventListener, times(2)).onAdPlaybackState(adPlaybackStateArgumentCaptor.capture());
    List<AdPlaybackState> adPlaybackStates = adPlaybackStateArgumentCaptor.getAllValues();
    assertThat(adPlaybackStates.get(0).getAdGroup(0).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
    assertThat(adPlaybackStates.get(1).getAdGroup(0).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_SKIPPED);
  }

  @Test
  public void skipCurrentAd_whenContentIsPlaying_ignored() throws IOException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "CUE=\"PRE\","
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
            + "\n";
    AdPlaybackState initialAdPlaybackState =
        callHandleContentTimelineChangedAndCaptureAdPlaybackState(
            playlistString,
            adsLoader,
            /* windowIndex= */ 0,
            /* windowPositionInPeriodUs= */ 0,
            /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE);
    when(mockPlayer.isPlayingAd()).thenReturn(false);
    when(mockPlayer.getCurrentAdGroupIndex()).thenReturn(C.INDEX_UNSET);
    when(mockPlayer.getCurrentAdIndexInAdGroup()).thenReturn(C.INDEX_UNSET);
    Timeline timeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setAdPlaybackStates(ImmutableList.of(initialAdPlaybackState))
                .build());
    when(mockPlayer.getCurrentTimeline()).thenReturn(timeline);
    when(mockPlayer.getCurrentPeriodIndex()).thenReturn(0);

    adsLoader.skipCurrentAd();

    ArgumentCaptor<AdPlaybackState> adPlaybackStateArgumentCaptor =
        ArgumentCaptor.forClass(AdPlaybackState.class);
    verify(mockEventListener).onAdPlaybackState(adPlaybackStateArgumentCaptor.capture());
    AdPlaybackState adPlaybackState = adPlaybackStateArgumentCaptor.getValue();
    assertThat(adPlaybackState.getAdGroup(0).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
  }

  @Test
  public void skipCurrentAdGroup_whenAdIsPlaying_adSkippedAndEventListenerNotified()
      throws IOException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "CUE=\"PRE\","
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-1\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "CUE=\"PRE\","
            + "X-ASSET-URI=\"http://example.com/media-0-1.m3u8\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-2\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "CUE=\"PRE\","
            + "X-ASSET-URI=\"http://example.com/media-0-2.m3u8\""
            + "\n";
    AdPlaybackState initialAdPlaybackState =
        callHandleContentTimelineChangedAndCaptureAdPlaybackState(
            playlistString,
            adsLoader,
            /* windowIndex= */ 0,
            /* windowPositionInPeriodUs= */ 0,
            /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE);
    when(mockPlayer.isPlayingAd()).thenReturn(true);
    when(mockPlayer.getCurrentAdGroupIndex()).thenReturn(0);
    when(mockPlayer.getCurrentAdIndexInAdGroup()).thenReturn(0);
    Timeline timeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setAdPlaybackStates(ImmutableList.of(initialAdPlaybackState))
                .build());
    when(mockPlayer.getCurrentTimeline()).thenReturn(timeline);
    when(mockPlayer.getCurrentPeriodIndex()).thenReturn(0);

    adsLoader.skipCurrentAdGroup();

    ArgumentCaptor<AdPlaybackState> adPlaybackStateArgumentCaptor =
        ArgumentCaptor.forClass(AdPlaybackState.class);
    verify(mockEventListener, times(2)).onAdPlaybackState(adPlaybackStateArgumentCaptor.capture());
    List<AdPlaybackState> adPlaybackStates = adPlaybackStateArgumentCaptor.getAllValues();
    assertThat(adPlaybackStates.get(0).getAdGroup(0).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
    assertThat(adPlaybackStates.get(0).getAdGroup(0).states[1])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
    assertThat(adPlaybackStates.get(0).getAdGroup(0).states[2])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
    assertThat(adPlaybackStates.get(1).getAdGroup(0).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_SKIPPED);
    assertThat(adPlaybackStates.get(1).getAdGroup(0).states[1])
        .isEqualTo(AdPlaybackState.AD_STATE_SKIPPED);
    assertThat(adPlaybackStates.get(1).getAdGroup(0).states[2])
        .isEqualTo(AdPlaybackState.AD_STATE_SKIPPED);
  }

  @Test
  public void skipCurrentAdGroup_whenContentIsPlaying_ignored() throws IOException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "CUE=\"PRE\","
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-1\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "CUE=\"PRE\","
            + "X-ASSET-URI=\"http://example.com/media-0-1.m3u8\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-2\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "CUE=\"PRE\","
            + "X-ASSET-URI=\"http://example.com/media-0-2.m3u8\""
            + "\n";
    AdPlaybackState initialAdPlaybackState =
        callHandleContentTimelineChangedAndCaptureAdPlaybackState(
            playlistString,
            adsLoader,
            /* windowIndex= */ 0,
            /* windowPositionInPeriodUs= */ 0,
            /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE);
    when(mockPlayer.isPlayingAd()).thenReturn(false);
    when(mockPlayer.getCurrentAdGroupIndex()).thenReturn(0);
    when(mockPlayer.getCurrentAdIndexInAdGroup()).thenReturn(0);
    Timeline timeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setAdPlaybackStates(ImmutableList.of(initialAdPlaybackState))
                .build());
    when(mockPlayer.getCurrentTimeline()).thenReturn(timeline);
    when(mockPlayer.getCurrentPeriodIndex()).thenReturn(0);

    adsLoader.skipCurrentAdGroup();

    ArgumentCaptor<AdPlaybackState> adPlaybackStateArgumentCaptor =
        ArgumentCaptor.forClass(AdPlaybackState.class);
    verify(mockEventListener).onAdPlaybackState(adPlaybackStateArgumentCaptor.capture());
    List<AdPlaybackState> adPlaybackStates = adPlaybackStateArgumentCaptor.getAllValues();
    assertThat(adPlaybackStates.get(0).getAdGroup(0).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
    assertThat(adPlaybackStates.get(0).getAdGroup(0).states[1])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
    assertThat(adPlaybackStates.get(0).getAdGroup(0).states[2])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
  }

  @Test
  public void setWithSkippedAd_whenContentIsPlaying_adSkippedAndEventListenerNotified()
      throws IOException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "CUE=\"PRE\","
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
            + "\n";
    AdPlaybackState initialAdPlaybackState =
        callHandleContentTimelineChangedAndCaptureAdPlaybackState(
            playlistString,
            adsLoader,
            /* windowIndex= */ 0,
            /* windowPositionInPeriodUs= */ 0,
            /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE);
    when(mockPlayer.isPlayingAd()).thenReturn(false);
    when(mockPlayer.getCurrentAdGroupIndex()).thenReturn(C.INDEX_UNSET);
    when(mockPlayer.getCurrentAdIndexInAdGroup()).thenReturn(C.INDEX_UNSET);
    Timeline timeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setAdPlaybackStates(ImmutableList.of(initialAdPlaybackState))
                .build());
    when(mockPlayer.getCurrentTimeline()).thenReturn(timeline);
    when(mockPlayer.getCurrentPeriodIndex()).thenReturn(0);

    adsLoader.setWithSkippedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);

    ArgumentCaptor<AdPlaybackState> adPlaybackStateArgumentCaptor =
        ArgumentCaptor.forClass(AdPlaybackState.class);
    verify(mockEventListener, times(2)).onAdPlaybackState(adPlaybackStateArgumentCaptor.capture());
    List<AdPlaybackState> adPlaybackStates = adPlaybackStateArgumentCaptor.getAllValues();
    assertThat(adPlaybackStates.get(0).getAdGroup(0).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
    assertThat(adPlaybackStates.get(1).getAdGroup(0).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_SKIPPED);
  }

  @Test
  public void setWithSkippedAd_whenAdToSkipIsPlaying_adSkippedAndEventListenerNotified()
      throws IOException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "CUE=\"PRE\","
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
            + "\n";
    AdPlaybackState initialAdPlaybackState =
        callHandleContentTimelineChangedAndCaptureAdPlaybackState(
            playlistString,
            adsLoader,
            /* windowIndex= */ 0,
            /* windowPositionInPeriodUs= */ 0,
            /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE);
    when(mockPlayer.isPlayingAd()).thenReturn(true);
    when(mockPlayer.getCurrentAdGroupIndex()).thenReturn(0);
    when(mockPlayer.getCurrentAdIndexInAdGroup()).thenReturn(0);
    Timeline timeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setAdPlaybackStates(ImmutableList.of(initialAdPlaybackState))
                .build());
    when(mockPlayer.getCurrentTimeline()).thenReturn(timeline);
    when(mockPlayer.getCurrentPeriodIndex()).thenReturn(0);

    adsLoader.setWithSkippedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);

    ArgumentCaptor<AdPlaybackState> adPlaybackStateArgumentCaptor =
        ArgumentCaptor.forClass(AdPlaybackState.class);
    verify(mockEventListener, times(2)).onAdPlaybackState(adPlaybackStateArgumentCaptor.capture());
    List<AdPlaybackState> adPlaybackStates = adPlaybackStateArgumentCaptor.getAllValues();
    assertThat(adPlaybackStates.get(0).getAdGroup(0).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
    assertThat(adPlaybackStates.get(1).getAdGroup(0).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_SKIPPED);
  }

  @Test
  public void setWithSkippedAd_skipPostRollWhenPreRollIsPlaying_adSkippedAndEventListenerNotified()
      throws IOException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "CUE=\"PRE\","
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad1-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "CUE=\"POST\","
            + "X-ASSET-URI=\"http://example.com/media-1-0.m3u8\""
            + "\n";
    AdPlaybackState initialAdPlaybackState =
        callHandleContentTimelineChangedAndCaptureAdPlaybackState(
            playlistString,
            adsLoader,
            /* windowIndex= */ 0,
            /* windowPositionInPeriodUs= */ 0,
            /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE);
    when(mockPlayer.isPlayingAd()).thenReturn(true);
    when(mockPlayer.getCurrentAdGroupIndex()).thenReturn(0);
    when(mockPlayer.getCurrentAdIndexInAdGroup()).thenReturn(0);
    Timeline timeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setAdPlaybackStates(ImmutableList.of(initialAdPlaybackState))
                .build());
    when(mockPlayer.getCurrentTimeline()).thenReturn(timeline);
    when(mockPlayer.getCurrentPeriodIndex()).thenReturn(0);

    adsLoader.setWithSkippedAd(/* adGroupIndex= */ 1, /* adIndexInAdGroup= */ 0);

    ArgumentCaptor<AdPlaybackState> adPlaybackStateArgumentCaptor =
        ArgumentCaptor.forClass(AdPlaybackState.class);
    verify(mockEventListener, times(2)).onAdPlaybackState(adPlaybackStateArgumentCaptor.capture());
    List<AdPlaybackState> adPlaybackStates = adPlaybackStateArgumentCaptor.getAllValues();
    assertThat(adPlaybackStates.get(0).getAdGroup(0).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
    assertThat(adPlaybackStates.get(1).getAdGroup(0).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
    assertThat(adPlaybackStates.get(0).getAdGroup(1).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
    assertThat(adPlaybackStates.get(1).getAdGroup(1).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_SKIPPED);
  }

  @Test
  public void setWithSkippedAd_invalidIndices_throwsIllegalArgumentException() throws IOException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "CUE=\"PRE\","
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
            + "\n";
    AdPlaybackState initialAdPlaybackState =
        callHandleContentTimelineChangedAndCaptureAdPlaybackState(
            playlistString,
            adsLoader,
            /* windowIndex= */ 0,
            /* windowPositionInPeriodUs= */ 0,
            /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE);
    when(mockPlayer.isPlayingAd()).thenReturn(false);
    when(mockPlayer.getCurrentAdGroupIndex()).thenReturn(C.INDEX_UNSET);
    when(mockPlayer.getCurrentAdIndexInAdGroup()).thenReturn(C.INDEX_UNSET);
    Timeline timeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setAdPlaybackStates(ImmutableList.of(initialAdPlaybackState))
                .build());
    when(mockPlayer.getCurrentTimeline()).thenReturn(timeline);
    when(mockPlayer.getCurrentPeriodIndex()).thenReturn(0);

    assertThrows(
        IllegalArgumentException.class,
        () -> {
          adsLoader.setWithSkippedAd(/* adGroupIndex= */ 42, /* adIndexInAdGroup= */ 0);
        });
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          adsLoader.setWithSkippedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 42);
        });
  }

  @Test
  public void setWithSkippedAdGroup_whenContentIsPlaying_adGroupSkippedAndEventListenerNotified()
      throws IOException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "CUE=\"PRE\","
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-1\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "CUE=\"PRE\","
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
            + "\n";
    AdPlaybackState initialAdPlaybackState =
        callHandleContentTimelineChangedAndCaptureAdPlaybackState(
            playlistString,
            adsLoader,
            /* windowIndex= */ 0,
            /* windowPositionInPeriodUs= */ 0,
            /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE);
    when(mockPlayer.isPlayingAd()).thenReturn(false);
    when(mockPlayer.getCurrentAdGroupIndex()).thenReturn(C.INDEX_UNSET);
    when(mockPlayer.getCurrentAdIndexInAdGroup()).thenReturn(C.INDEX_UNSET);
    Timeline timeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setAdPlaybackStates(ImmutableList.of(initialAdPlaybackState))
                .build());
    when(mockPlayer.getCurrentTimeline()).thenReturn(timeline);
    when(mockPlayer.getCurrentPeriodIndex()).thenReturn(0);

    adsLoader.setWithSkippedAdGroup(/* adGroupIndex= */ 0);

    ArgumentCaptor<AdPlaybackState> adPlaybackStateArgumentCaptor =
        ArgumentCaptor.forClass(AdPlaybackState.class);
    verify(mockEventListener, times(2)).onAdPlaybackState(adPlaybackStateArgumentCaptor.capture());
    List<AdPlaybackState> adPlaybackStates = adPlaybackStateArgumentCaptor.getAllValues();
    assertThat(adPlaybackStates.get(0).getAdGroup(0).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
    assertThat(adPlaybackStates.get(1).getAdGroup(0).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_SKIPPED);
    assertThat(adPlaybackStates.get(0).getAdGroup(0).states[1])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
    assertThat(adPlaybackStates.get(1).getAdGroup(0).states[1])
        .isEqualTo(AdPlaybackState.AD_STATE_SKIPPED);
  }

  @Test
  public void setWithSkippedAdGroup_whenAdToSkipIsPlaying_adGroupSkippedAndEventListenerNotified()
      throws IOException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "CUE=\"PRE\","
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-1\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "CUE=\"PRE\","
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
            + "\n";
    AdPlaybackState initialAdPlaybackState =
        callHandleContentTimelineChangedAndCaptureAdPlaybackState(
            playlistString,
            adsLoader,
            /* windowIndex= */ 0,
            /* windowPositionInPeriodUs= */ 0,
            /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE);
    when(mockPlayer.isPlayingAd()).thenReturn(true);
    when(mockPlayer.getCurrentAdGroupIndex()).thenReturn(0);
    when(mockPlayer.getCurrentAdIndexInAdGroup()).thenReturn(0);
    Timeline timeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setAdPlaybackStates(ImmutableList.of(initialAdPlaybackState))
                .build());
    when(mockPlayer.getCurrentTimeline()).thenReturn(timeline);
    when(mockPlayer.getCurrentPeriodIndex()).thenReturn(0);

    adsLoader.setWithSkippedAdGroup(/* adGroupIndex= */ 0);

    ArgumentCaptor<AdPlaybackState> adPlaybackStateArgumentCaptor =
        ArgumentCaptor.forClass(AdPlaybackState.class);
    verify(mockEventListener, times(2)).onAdPlaybackState(adPlaybackStateArgumentCaptor.capture());
    List<AdPlaybackState> adPlaybackStates = adPlaybackStateArgumentCaptor.getAllValues();
    assertThat(adPlaybackStates.get(0).getAdGroup(0).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
    assertThat(adPlaybackStates.get(1).getAdGroup(0).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_SKIPPED);
    assertThat(adPlaybackStates.get(0).getAdGroup(0).states[1])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
    assertThat(adPlaybackStates.get(1).getAdGroup(0).states[1])
        .isEqualTo(AdPlaybackState.AD_STATE_SKIPPED);
  }

  @Test
  public void
      setWithSkippedAdGroup_skipPostRollWhenPreRollIsPlaying_adSkippedAndEventListenerNotified()
          throws IOException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "CUE=\"PRE\","
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad1-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "CUE=\"POST\","
            + "X-ASSET-URI=\"http://example.com/media-1-0.m3u8\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad1-1\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "CUE=\"POST\","
            + "X-ASSET-URI=\"http://example.com/media-1-1.m3u8\""
            + "\n";
    AdPlaybackState initialAdPlaybackState =
        callHandleContentTimelineChangedAndCaptureAdPlaybackState(
            playlistString,
            adsLoader,
            /* windowIndex= */ 0,
            /* windowPositionInPeriodUs= */ 0,
            /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE);
    when(mockPlayer.isPlayingAd()).thenReturn(true);
    when(mockPlayer.getCurrentAdGroupIndex()).thenReturn(0);
    when(mockPlayer.getCurrentAdIndexInAdGroup()).thenReturn(0);
    Timeline timeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setAdPlaybackStates(ImmutableList.of(initialAdPlaybackState))
                .build());
    when(mockPlayer.getCurrentTimeline()).thenReturn(timeline);
    when(mockPlayer.getCurrentPeriodIndex()).thenReturn(0);

    adsLoader.setWithSkippedAdGroup(/* adGroupIndex= */ 1);

    ArgumentCaptor<AdPlaybackState> adPlaybackStateArgumentCaptor =
        ArgumentCaptor.forClass(AdPlaybackState.class);
    verify(mockEventListener, times(2)).onAdPlaybackState(adPlaybackStateArgumentCaptor.capture());
    List<AdPlaybackState> adPlaybackStates = adPlaybackStateArgumentCaptor.getAllValues();
    assertThat(adPlaybackStates.get(0).getAdGroup(0).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
    assertThat(adPlaybackStates.get(1).getAdGroup(0).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
    assertThat(adPlaybackStates.get(0).getAdGroup(1).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
    assertThat(adPlaybackStates.get(0).getAdGroup(1).states[1])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
    assertThat(adPlaybackStates.get(1).getAdGroup(1).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_SKIPPED);
    assertThat(adPlaybackStates.get(1).getAdGroup(1).states[1])
        .isEqualTo(AdPlaybackState.AD_STATE_SKIPPED);
  }

  @Test
  public void
      setWithSkippedAdGroup_skipsAdDuringLoading_assetListLoadingCancelledToPreferManualChange()
          throws IOException, TimeoutException {
    setWithSkippedAdOrAdGroup_skipsAdDuringLoading_assetListLoadingCancelledToPreferManualChange(
        () -> {
          adsLoader.setWithSkippedAdGroup(/* adGroupIndex= */ 0);
        });
  }

  @Test
  public void setWithSkippedAd_skipsAdDuringLoading_assetListLoadingCancelledToPreferManualChange()
      throws IOException, TimeoutException {
    setWithSkippedAdOrAdGroup_skipsAdDuringLoading_assetListLoadingCancelledToPreferManualChange(
        () -> adsLoader.setWithSkippedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0));
  }

  private void
      setWithSkippedAdOrAdGroup_skipsAdDuringLoading_assetListLoadingCancelledToPreferManualChange(
          Runnable runnableUnderTest) throws IOException, TimeoutException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:9\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:00:00.000Z\n"
            + "#EXTINF:9,\n"
            + "main0.ts\n"
            + "#EXTINF:51,\n"
            + "main1.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T22:00:00.000Z\","
            + "CUE=\"PRE\","
            + "X-ASSET-LIST=\"http://slow_loading\""
            + "\n";
    when(mockPlayer.getContentPosition()).thenReturn(0L);
    AdPlaybackState initialAdPlaybackState =
        new AdPlaybackState("adsId", 0L).withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1);
    Timeline timeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setAdPlaybackStates(ImmutableList.of(initialAdPlaybackState))
                .build());
    when(mockPlayer.getCurrentTimeline()).thenReturn(timeline);
    callHandleContentTimelineChangedAndCaptureAdPlaybackState(
        playlistString,
        adsLoader,
        /* windowIndex= */ 0,
        /* windowPositionInPeriodUs= */ 0,
        /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE);

    runnableUnderTest.run();

    runMainLooperUntil(assetListLoadingListener::failed, TIMEOUT_MS, Clock.DEFAULT);
    verify(mockAdsLoaderListener)
        .onAssetListLoadFailed(
            /* mediaItem= */ eq(contentMediaItem),
            /* adsId= */ eq("adsId"),
            /* adGroupIndex= */ eq(0),
            /* adIndexInAdGroup= */ eq(0),
            /* ioException= */ isNull(),
            /* cancelled= */ eq(true));
    ArgumentCaptor<AdPlaybackState> adPlaybackState =
        ArgumentCaptor.forClass(AdPlaybackState.class);
    verify(mockEventListener, times(2)).onAdPlaybackState(adPlaybackState.capture());
    assertThat(adPlaybackState.getAllValues().get(0).getAdGroup(0).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_UNAVAILABLE);
    assertThat(adPlaybackState.getAllValues().get(1).getAdGroup(0).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_SKIPPED);
    InOrder inOrder = inOrder(mockPlayer);
    inOrder.verify(mockPlayer).addListener(any());
    verifyTimelineUpdate(inOrder, mockPlayer, /* verifyMessageScheduled= */ false);
    verifyAssetListLoadCompleted(inOrder, mockPlayer, /* verifyMessageScheduled= */ false);
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void setWithSkippedAd_skipsAdWithPendingAssetList_assetListRemovedToPreferManualChange()
      throws IOException, TimeoutException {
    setWithSkippedAdOrAdGroup_skipsAdWithPendingAssetList_assetListRemovedToPreferManualChange(
        () -> {
          // Skip the midroll just before its asset list should be scheduled for resolution.
          // Skipping then removes the pending asset list and the next mid roll is expected to be
          // scheduled instead. The expected position when to trigger asset list loading is at
          // position 'adGroup.timeUs - (3 * target duration)' 60_000 - 27_000 = 33_000
          adsLoader.setWithSkippedAd(/* adGroupIndex= */ 1, /* adIndexInAdGroup= */ 0);
        });
  }

  @Test
  public void
      setWithSkippedAdGroup_skipsAdWithPendingAssetList_assetListRemovedToPreferManualChange()
          throws IOException, TimeoutException {
    setWithSkippedAdOrAdGroup_skipsAdWithPendingAssetList_assetListRemovedToPreferManualChange(
        () -> {
          // Skip the midroll group just before its asset list should be scheduled for resolution.
          // Skipping then removes the pending asset list and the next mid roll is expected to be
          // scheduled instead. The expected position when to trigger asset list loading is at
          // position 'adGroup.timeUs - (3 * target duration)' 60_000 - 27_000 = 33_000
          adsLoader.setWithSkippedAdGroup(/* adGroupIndex= */ 1);
        });
  }

  private void
      setWithSkippedAdOrAdGroup_skipsAdWithPendingAssetList_assetListRemovedToPreferManualChange(
          Runnable runnableUnderTest) throws IOException, TimeoutException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:9\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:00:00.000Z\n"
            + "#EXTINF:9,\n"
            + "main0.ts\n"
            + "#EXTINF:51,\n"
            + "main1.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T22:00:00.000Z\","
            + "CUE=\"PRE\","
            + "X-ASSET-LIST=\"http://example.com/assetlist-0-0.json\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad1-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:00:30.000Z\","
            + "X-ASSET-LIST=\"http://example.com/assetlist-1-0.json\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad2-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:01:00.000Z\","
            + "X-ASSET-LIST=\"http://example.com/assetlist-2-0.json\""
            + "\n";
    when(mockPlayer.getContentPosition()).thenReturn(0L);
    PlayerMessage midRollPlayerMessage =
        new PlayerMessage(
            mock(PlayerMessage.Sender.class),
            mock(PlayerMessage.Target.class),
            Timeline.EMPTY,
            /* defaultMediaItemIndex= */ 0,
            /* Clock ignored */ null,
            /* Looper ignored */ null);
    when(mockPlayer.createMessage(any())).thenReturn(midRollPlayerMessage);
    adsLoader.addListener(
        new Listener() {
          @Override
          public void onAssetListLoadCompleted(
              MediaItem mediaItem,
              Object adsId,
              int adGroupIndex,
              int adIndexInAdGroup,
              AssetList assetList) {
            runnableUnderTest.run();
          }
        });

    callHandleContentTimelineChangedAndCaptureAdPlaybackState(
        playlistString,
        adsLoader,
        /* windowIndex= */ 0,
        /* windowPositionInPeriodUs= */ 0,
        /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE);

    runMainLooperUntil(assetListLoadingListener::completed, TIMEOUT_MS, Clock.DEFAULT);
    verify(mockAdsLoaderListener)
        .onAssetListLoadCompleted(eq(contentMediaItem), eq("adsId"), eq(0), eq(0), any());
    // asset list load trigger position is at 'adGroup.timeUs - (3 * target duration)'
    assertThat(midRollPlayerMessage.getPositionMs()).isEqualTo(33_000L);
    assertThat(midRollPlayerMessage.getPayload()).isEqualTo(contentMediaItem);
    assertThat(midRollPlayerMessage.getLooper()).isEqualTo(Looper.myLooper());
    ArgumentCaptor<AdPlaybackState> adPlaybackState =
        ArgumentCaptor.forClass(AdPlaybackState.class);
    verify(mockEventListener, times(3)).onAdPlaybackState(adPlaybackState.capture());
    assertThat(adPlaybackState.getAllValues().get(0).getAdGroup(0).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_UNAVAILABLE); // first ad playback state from timeline
    assertThat(adPlaybackState.getAllValues().get(0).getAdGroup(1).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_UNAVAILABLE);
    assertThat(adPlaybackState.getAllValues().get(0).getAdGroup(2).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_UNAVAILABLE);
    assertThat(adPlaybackState.getAllValues().get(1).getAdGroup(0).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE); // asset list resolved
    assertThat(adPlaybackState.getAllValues().get(1).getAdGroup(1).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_UNAVAILABLE);
    assertThat(adPlaybackState.getAllValues().get(1).getAdGroup(2).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_UNAVAILABLE);
    assertThat(adPlaybackState.getAllValues().get(2).getAdGroup(0).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
    assertThat(adPlaybackState.getAllValues().get(2).getAdGroup(1).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_SKIPPED); // ad was skipped and reported to the source
    assertThat(adPlaybackState.getAllValues().get(2).getAdGroup(2).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_UNAVAILABLE);
    InOrder inOrder = inOrder(mockPlayer);
    inOrder.verify(mockPlayer).addListener(any());
    verifyTimelineUpdate(inOrder, mockPlayer, /* verifyMessageScheduled= */ false);
    verifyAssetListLoadCompleted(inOrder, mockPlayer, /* verifyMessageScheduled= */ true);
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void
      setWithAvailableAdMediaItem_forSkippedAdsWhenContentIsPlaying_adAvailableAndEventListenerNotified()
          throws IOException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "CUE=\"PRE\","
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-1\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "CUE=\"PRE\","
            + "X-ASSET-URI=\"http://example.com/media-0-1.m3u8\""
            + "\n";
    AdPlaybackState initialAdPlaybackState =
        callHandleContentTimelineChangedAndCaptureAdPlaybackState(
            playlistString,
            adsLoader,
            /* windowIndex= */ 0,
            /* windowPositionInPeriodUs= */ 0,
            /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE);
    when(mockPlayer.isPlayingAd()).thenReturn(false);
    when(mockPlayer.getCurrentAdGroupIndex()).thenReturn(C.INDEX_UNSET);
    when(mockPlayer.getCurrentAdIndexInAdGroup()).thenReturn(C.INDEX_UNSET);
    Timeline timeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setAdPlaybackStates(ImmutableList.of(initialAdPlaybackState))
                .build());
    when(mockPlayer.getCurrentTimeline()).thenReturn(timeline);
    when(mockPlayer.getCurrentPeriodIndex()).thenReturn(0);
    adsLoader.setWithSkippedAdGroup(/* adGroupIndex= */ 0);

    adsLoader.setWithAvailableAdMediaItem(
        /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, /* mediaItem= */ null);
    adsLoader.setWithAvailableAdMediaItem(
        /* adGroupIndex= */ 0,
        /* adIndexInAdGroup= */ 1,
        /* mediaItem= */ new MediaItem.Builder()
            .setUri("http://example.com/updated.m3u8")
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .build());

    ArgumentCaptor<AdPlaybackState> adPlaybackStateArgumentCaptor =
        ArgumentCaptor.forClass(AdPlaybackState.class);
    verify(mockEventListener, times(4)).onAdPlaybackState(adPlaybackStateArgumentCaptor.capture());
    List<AdPlaybackState> adPlaybackStates = adPlaybackStateArgumentCaptor.getAllValues();
    assertThat(adPlaybackStates.get(0).getAdGroup(/* adGroupIndex= */ 0).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
    assertThat(adPlaybackStates.get(0).getAdGroup(/* adGroupIndex= */ 0).states[1])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
    assertThat(adPlaybackStates.get(1).getAdGroup(/* adGroupIndex= */ 0).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_SKIPPED);
    assertThat(adPlaybackStates.get(1).getAdGroup(/* adGroupIndex= */ 0).states[1])
        .isEqualTo(AdPlaybackState.AD_STATE_SKIPPED);
    assertThat(adPlaybackStates.get(2).getAdGroup(/* adGroupIndex= */ 0).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
    assertThat(adPlaybackStates.get(2).getAdGroup(/* adGroupIndex= */ 0).states[1])
        .isEqualTo(AdPlaybackState.AD_STATE_SKIPPED);
    assertThat(
            adPlaybackStates
                .get(2)
                .getAdGroup(/* adGroupIndex= */ 0)
                .mediaItems[0]
                .localConfiguration
                .uri)
        .isEqualTo(Uri.parse("http://example.com/media-0-0.m3u8"));
    assertThat(adPlaybackStates.get(3).getAdGroup(/* adGroupIndex= */ 0).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
    assertThat(adPlaybackStates.get(3).getAdGroup(/* adGroupIndex= */ 0).states[1])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
    assertThat(
            adPlaybackStates
                .get(3)
                .getAdGroup(/* adGroupIndex= */ 0)
                .mediaItems[1]
                .localConfiguration
                .uri)
        .isEqualTo(Uri.parse("http://example.com/updated.m3u8"));
  }

  @Test
  public void setWithAvailableAdMediaItem_withNonHlsMediaItem_throwsIllegalArgumentException() {
    adsLoader.setPlayer(mockPlayer);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            adsLoader.setWithAvailableAdMediaItem(
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 0,
                new MediaItem.Builder().setUri("http://example.com/stream.mp4").build()));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            adsLoader.setWithAvailableAdMediaItem(
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 0,
                new MediaItem.Builder()
                    .setUri("http://example.com/stream.something")
                    .setMimeType(MimeTypes.APPLICATION_MP4)
                    .build()));
  }

  @Test
  public void
      setWithAvailableAd_makesAdWithPendingAssetListAvailable_assetListRemovedToPreferManualChange()
          throws IOException, TimeoutException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:9\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:00:00.000Z\n"
            + "#EXTINF:9,\n"
            + "main0.ts\n"
            + "#EXTINF:51,\n"
            + "main1.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T22:00:00.000Z\","
            + "CUE=\"PRE\","
            + "X-ASSET-LIST=\"http://example.com/assetlist-0-0.json\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad1-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:00:30.000Z\","
            + "X-ASSET-LIST=\"http://example.com/assetlist-1-0.json\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad2-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:01:00.000Z\","
            + "X-ASSET-LIST=\"http://example.com/assetlist-2-0.json\""
            + "\n";
    MediaItem availableMediaItem =
        new MediaItem.Builder().setUri("http://example.com/it.m3u8").build();
    adsLoader.addListener(
        new Listener() {
          @Override
          public void onAssetListLoadCompleted(
              MediaItem mediaItem,
              Object adsId,
              int adGroupIndex,
              int adIndexInAdGroup,
              AssetList assetList) {
            // Skip the midroll group just before its asset list should be scheduled for resolution.
            // Skipping then removes the pending asset list and the next mid roll is expected to be
            // scheduled instead. The expected position when to trigger asset list loading is at
            // position 'adGroup.timeUs - (3 * target duration)' 60_000 - 27_000 = 33_000L
            adsLoader.setWithAvailableAdMediaItem(
                /* adGroupIndex= */ 1, /* adIndexInAdGroup= */ 0, availableMediaItem);
          }
        });
    when(mockPlayer.getContentPosition()).thenReturn(0L);
    PlayerMessage midRollPlayerMessage =
        new PlayerMessage(
            mock(PlayerMessage.Sender.class),
            mock(PlayerMessage.Target.class),
            Timeline.EMPTY,
            /* defaultMediaItemIndex= */ 0,
            /* Clock ignored */ null,
            /* Looper ignored */ null);
    when(mockPlayer.createMessage(any())).thenReturn(midRollPlayerMessage);

    callHandleContentTimelineChangedAndCaptureAdPlaybackState(
        playlistString,
        adsLoader,
        /* windowIndex= */ 0,
        /* windowPositionInPeriodUs= */ 0,
        /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE);

    runMainLooperUntil(assetListLoadingListener::completed, TIMEOUT_MS, Clock.DEFAULT);
    verify(mockAdsLoaderListener)
        .onAssetListLoadCompleted(eq(contentMediaItem), eq("adsId"), eq(0), eq(0), any());
    // asset list load trigger position is at 'adGroup.timeUs - (3 * target duration)'
    assertThat(midRollPlayerMessage.getPositionMs()).isEqualTo(33_000L);
    assertThat(midRollPlayerMessage.getPayload()).isEqualTo(contentMediaItem);
    assertThat(midRollPlayerMessage.getLooper()).isEqualTo(Looper.myLooper());
    ArgumentCaptor<AdPlaybackState> adPlaybackState =
        ArgumentCaptor.forClass(AdPlaybackState.class);
    verify(mockEventListener, times(3)).onAdPlaybackState(adPlaybackState.capture());
    assertThat(adPlaybackState.getAllValues().get(0).getAdGroup(0).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_UNAVAILABLE); // first ad playback state from timeline
    assertThat(adPlaybackState.getAllValues().get(0).getAdGroup(1).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_UNAVAILABLE);
    assertThat(adPlaybackState.getAllValues().get(0).getAdGroup(2).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_UNAVAILABLE);
    assertThat(adPlaybackState.getAllValues().get(1).getAdGroup(0).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE); // asset list resolved
    assertThat(adPlaybackState.getAllValues().get(1).getAdGroup(1).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_UNAVAILABLE);
    assertThat(adPlaybackState.getAllValues().get(1).getAdGroup(2).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_UNAVAILABLE);
    assertThat(adPlaybackState.getAllValues().get(2).getAdGroup(0).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
    assertThat(adPlaybackState.getAllValues().get(2).getAdGroup(1).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE); // ad was made available and reported
    assertThat(adPlaybackState.getAllValues().get(2).getAdGroup(1).mediaItems[0])
        .isEqualTo(availableMediaItem);
    assertThat(adPlaybackState.getAllValues().get(2).getAdGroup(2).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_UNAVAILABLE);
    InOrder inOrder = inOrder(mockPlayer);
    inOrder.verify(mockPlayer).addListener(any());
    verifyTimelineUpdate(inOrder, mockPlayer, /* verifyMessageScheduled= */ false);
    verifyAssetListLoadCompleted(inOrder, mockPlayer, /* verifyMessageScheduled= */ true);
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void setWithAvailableAdGroup_resetSkippedAds_adAvailableAndEventListenerNotified()
      throws IOException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "CUE=\"PRE\","
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad1-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "CUE=\"POST\","
            + "X-ASSET-URI=\"http://example.com/media-1-0.m3u8\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad1-1\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "CUE=\"POST\","
            + "X-ASSET-URI=\"http://example.com/media-1-1.m3u8\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad1-2\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "CUE=\"POST\","
            + "X-ASSET-URI=\"http://example.com/media-1-2.m3u8\""
            + "\n";
    AdPlaybackState initialAdPlaybackState =
        callHandleContentTimelineChangedAndCaptureAdPlaybackState(
            playlistString,
            adsLoader,
            /* windowIndex= */ 0,
            /* windowPositionInPeriodUs= */ 0,
            /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE);
    when(mockPlayer.isPlayingAd()).thenReturn(false);
    when(mockPlayer.getCurrentAdGroupIndex()).thenReturn(C.INDEX_UNSET);
    when(mockPlayer.getCurrentAdIndexInAdGroup()).thenReturn(C.INDEX_UNSET);
    Timeline timeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setAdPlaybackStates(ImmutableList.of(initialAdPlaybackState))
                .build());
    when(mockPlayer.getCurrentTimeline()).thenReturn(timeline);
    when(mockPlayer.getCurrentPeriodIndex()).thenReturn(0);
    adsLoader.setWithSkippedAdGroup(/* adGroupIndex= */ 1);

    adsLoader.setWithAvailableAdGroup(/* adGroupIndex= */ 1);

    ArgumentCaptor<AdPlaybackState> adPlaybackStateArgumentCaptor =
        ArgumentCaptor.forClass(AdPlaybackState.class);
    verify(mockEventListener, times(3)).onAdPlaybackState(adPlaybackStateArgumentCaptor.capture());
    List<AdPlaybackState> adPlaybackStates = adPlaybackStateArgumentCaptor.getAllValues();
    assertThat(adPlaybackStates.get(0).getAdGroup(/* adGroupIndex= */ 1).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
    assertThat(adPlaybackStates.get(0).getAdGroup(/* adGroupIndex= */ 1).states[1])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
    assertThat(adPlaybackStates.get(0).getAdGroup(/* adGroupIndex= */ 1).states[2])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
    assertThat(adPlaybackStates.get(1).getAdGroup(/* adGroupIndex= */ 1).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_SKIPPED);
    assertThat(adPlaybackStates.get(1).getAdGroup(/* adGroupIndex= */ 1).states[1])
        .isEqualTo(AdPlaybackState.AD_STATE_SKIPPED);
    assertThat(adPlaybackStates.get(1).getAdGroup(/* adGroupIndex= */ 1).states[2])
        .isEqualTo(AdPlaybackState.AD_STATE_SKIPPED);
    assertThat(adPlaybackStates.get(2).getAdGroup(/* adGroupIndex= */ 1).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
    assertThat(adPlaybackStates.get(2).getAdGroup(/* adGroupIndex= */ 1).states[1])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
    assertThat(adPlaybackStates.get(2).getAdGroup(/* adGroupIndex= */ 1).states[2])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
    assertThat(
            adPlaybackStates
                .get(2)
                .getAdGroup(/* adGroupIndex= */ 1)
                .mediaItems[0]
                .localConfiguration
                .uri)
        .isEqualTo(Uri.parse("http://example.com/media-1-0.m3u8"));
    assertThat(
            adPlaybackStates
                .get(2)
                .getAdGroup(/* adGroupIndex= */ 1)
                .mediaItems[1]
                .localConfiguration
                .uri)
        .isEqualTo(Uri.parse("http://example.com/media-1-1.m3u8"));
    assertThat(
            adPlaybackStates
                .get(2)
                .getAdGroup(/* adGroupIndex= */ 1)
                .mediaItems[2]
                .localConfiguration
                .uri)
        .isEqualTo(Uri.parse("http://example.com/media-1-2.m3u8"));
  }

  @Test
  public void setWithAvailableAdGroup_resetPlayedAds_adAvailableAndEventListenerNotified()
      throws IOException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "CUE=\"POST\","
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-1\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "CUE=\"POST\","
            + "X-ASSET-URI=\"http://example.com/media-0-1.m3u8\""
            + "\n";
    AdPlaybackState initialAdPlaybackState =
        callHandleContentTimelineChangedAndCaptureAdPlaybackState(
            playlistString,
            adsLoader,
            /* windowIndex= */ 0,
            /* windowPositionInPeriodUs= */ 0,
            /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE);
    when(mockPlayer.isPlayingAd()).thenReturn(false);
    when(mockPlayer.getCurrentAdGroupIndex()).thenReturn(C.INDEX_UNSET);
    when(mockPlayer.getCurrentAdIndexInAdGroup()).thenReturn(C.INDEX_UNSET);
    Timeline timeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setAdPlaybackStates(ImmutableList.of(initialAdPlaybackState))
                .build());
    when(mockPlayer.getCurrentTimeline()).thenReturn(timeline);
    when(mockPlayer.getCurrentPeriodIndex()).thenReturn(0);
    ArgumentCaptor<Player.Listener> listener = ArgumentCaptor.forClass(Player.Listener.class);
    verify(mockPlayer).addListener(listener.capture());
    Object windowUid = new Object();
    Object periodUid = new Object();
    listener
        .getValue()
        .onPositionDiscontinuity(
            new Player.PositionInfo(
                windowUid,
                /* mediaItemIndex= */ 0,
                contentMediaItem,
                periodUid,
                /* periodIndex= */ 0,
                /* positionMs= */ 10_000L,
                /* contentPositionMs= */ 10_000L,
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 0),
            new Player.PositionInfo(
                windowUid,
                /* mediaItemIndex= */ 0,
                contentMediaItem,
                periodUid,
                /* periodIndex= */ 0,
                /* positionMs= */ 0L,
                /* contentPositionMs= */ 10_000L,
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 1),
            DISCONTINUITY_REASON_AUTO_TRANSITION);
    listener
        .getValue()
        .onPositionDiscontinuity(
            new Player.PositionInfo(
                windowUid,
                /* mediaItemIndex= */ 0,
                contentMediaItem,
                periodUid,
                /* periodIndex= */ 0,
                /* positionMs= */ 10_000L,
                /* contentPositionMs= */ 10_000L,
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 1),
            new Player.PositionInfo(
                windowUid,
                /* mediaItemIndex= */ 0,
                contentMediaItem,
                periodUid,
                /* periodIndex= */ 0,
                /* positionMs= */ 10_000L,
                /* contentPositionMs= */ 10_000L,
                /* adGroupIndex= */ C.INDEX_UNSET,
                /* adIndexInAdGroup= */ C.INDEX_UNSET),
            DISCONTINUITY_REASON_AUTO_TRANSITION);

    adsLoader.setWithAvailableAdGroup(/* adGroupIndex= */ 0);

    ArgumentCaptor<AdPlaybackState> adPlaybackStateArgumentCaptor =
        ArgumentCaptor.forClass(AdPlaybackState.class);
    verify(mockEventListener, times(4)).onAdPlaybackState(adPlaybackStateArgumentCaptor.capture());
    List<AdPlaybackState> adPlaybackStates = adPlaybackStateArgumentCaptor.getAllValues();
    assertThat(adPlaybackStates.get(0).getAdGroup(/* adGroupIndex= */ 0).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
    assertThat(adPlaybackStates.get(0).getAdGroup(/* adGroupIndex= */ 0).states[1])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
    assertThat(adPlaybackStates.get(1).getAdGroup(/* adGroupIndex= */ 0).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_PLAYED);
    assertThat(adPlaybackStates.get(1).getAdGroup(/* adGroupIndex= */ 0).states[1])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
    assertThat(adPlaybackStates.get(2).getAdGroup(/* adGroupIndex= */ 0).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_PLAYED);
    assertThat(adPlaybackStates.get(2).getAdGroup(/* adGroupIndex= */ 0).states[1])
        .isEqualTo(AdPlaybackState.AD_STATE_PLAYED);
    assertThat(adPlaybackStates.get(3).getAdGroup(/* adGroupIndex= */ 0).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
    assertThat(adPlaybackStates.get(3).getAdGroup(/* adGroupIndex= */ 0).states[1])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
    assertThat(
            adPlaybackStates
                .get(3)
                .getAdGroup(/* adGroupIndex= */ 0)
                .mediaItems[0]
                .localConfiguration
                .uri)
        .isEqualTo(Uri.parse("http://example.com/media-0-0.m3u8"));
    assertThat(
            adPlaybackStates
                .get(3)
                .getAdGroup(/* adGroupIndex= */ 0)
                .mediaItems[1]
                .localConfiguration
                .uri)
        .isEqualTo(Uri.parse("http://example.com/media-0-1.m3u8"));
  }

  @Test
  public void setWithAvailableAdGroup_adsInErrorState_remainInErrorState() throws IOException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "CUE=\"POST\","
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-1\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "CUE=\"POST\","
            + "X-ASSET-URI=\"http://example.com/media-0-1.m3u8\""
            + "\n";
    AdPlaybackState initialAdPlaybackState =
        callHandleContentTimelineChangedAndCaptureAdPlaybackState(
            playlistString,
            adsLoader,
            /* windowIndex= */ 0,
            /* windowPositionInPeriodUs= */ 0,
            /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE);
    when(mockPlayer.isPlayingAd()).thenReturn(false);
    when(mockPlayer.getCurrentAdGroupIndex()).thenReturn(C.INDEX_UNSET);
    when(mockPlayer.getCurrentAdIndexInAdGroup()).thenReturn(C.INDEX_UNSET);
    Timeline timeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setAdPlaybackStates(ImmutableList.of(initialAdPlaybackState))
                .build());
    when(mockPlayer.getCurrentTimeline()).thenReturn(timeline);
    when(mockPlayer.getCurrentPeriodIndex()).thenReturn(0);
    adsLoader.setWithSkippedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    adsLoader.handlePrepareError(
        adsMediaSource, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1, new IOException());

    adsLoader.setWithAvailableAdGroup(/* adGroupIndex= */ 0);

    ArgumentCaptor<AdPlaybackState> adPlaybackStateArgumentCaptor =
        ArgumentCaptor.forClass(AdPlaybackState.class);
    verify(mockEventListener, times(4)).onAdPlaybackState(adPlaybackStateArgumentCaptor.capture());
    List<AdPlaybackState> adPlaybackStates = adPlaybackStateArgumentCaptor.getAllValues();
    assertThat(adPlaybackStates.get(0).getAdGroup(/* adGroupIndex= */ 0).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
    assertThat(adPlaybackStates.get(0).getAdGroup(/* adGroupIndex= */ 0).states[1])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
    assertThat(adPlaybackStates.get(1).getAdGroup(/* adGroupIndex= */ 0).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_SKIPPED);
    assertThat(adPlaybackStates.get(1).getAdGroup(/* adGroupIndex= */ 0).states[1])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
    assertThat(adPlaybackStates.get(2).getAdGroup(/* adGroupIndex= */ 0).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_SKIPPED);
    assertThat(adPlaybackStates.get(2).getAdGroup(/* adGroupIndex= */ 0).states[1])
        .isEqualTo(AdPlaybackState.AD_STATE_ERROR);
    assertThat(adPlaybackStates.get(3).getAdGroup(/* adGroupIndex= */ 0).states[0])
        .isEqualTo(AdPlaybackState.AD_STATE_AVAILABLE);
    assertThat(adPlaybackStates.get(3).getAdGroup(/* adGroupIndex= */ 0).states[1])
        .isEqualTo(AdPlaybackState.AD_STATE_ERROR);
  }

  @Test
  public void onPlaybackStateChanged_stateEndedWhenPlayingAd_marksAdAsPlayed() throws IOException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-1\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:40.000Z\","
            + "CUE=\"POST\","
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
            + "\n";
    callHandleContentTimelineChangedAndCaptureAdPlaybackState(
        playlistString,
        adsLoader,
        /* windowIndex= */ 0,
        /* windowPositionInPeriodUs= */ 0,
        /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE);
    reset(mockEventListener);
    ArgumentCaptor<Player.Listener> listener = ArgumentCaptor.forClass(Player.Listener.class);
    when(mockPlayer.isPlayingAd()).thenReturn(true);
    when(mockPlayer.getCurrentAdGroupIndex()).thenReturn(0);
    when(mockPlayer.getCurrentAdIndexInAdGroup()).thenReturn(0);
    verify(mockPlayer).addListener(listener.capture());

    listener.getValue().onPlaybackStateChanged(Player.STATE_ENDED);

    verify(mockAdsLoaderListener)
        .onAdCompleted(
            contentMediaItem,
            adsMediaSource.getAdsId(),
            /* adGroupIndex= */ 0,
            /* adIndexInAdGroup= */ 0);
    verify(mockEventListener)
        .onAdPlaybackState(
            new AdPlaybackState("adsId", /* adGroupTimesUs...= */ C.TIME_END_OF_SOURCE)
                .withAdDurationsUs(/* adGroupIndex= */ 0, C.TIME_UNSET)
                .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
                .withContentResumeOffsetUs(/* adGroupIndex= */ 0, /* contentResumeOffsetUs= */ 0)
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ 0,
                    new MediaItem.Builder()
                        .setUri("http://example.com/media-0-0.m3u8")
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build())
                .withAdId(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, "ad0-1")
                .withPlayedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0));
  }

  @Test
  public void handlePrepareError_adPlaybackStateUpdatedAccordingly() throws IOException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:41.123Z\","
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-1\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:41.123Z\","
            + "X-ASSET-URI=\"http://example.com/media-0-1.m3u8\""
            + "\n";
    callHandleContentTimelineChangedAndCaptureAdPlaybackState(
        playlistString,
        adsLoader,
        /* windowIndex= */ 0,
        /* windowPositionInPeriodUs= */ 0,
        /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE);

    adsLoader.handlePrepareError(adsMediaSource, 0, 1, new IOException());
    adsLoader.handlePrepareError(adsMediaSource, 0, 0, new IOException());

    ArgumentCaptor<AdPlaybackState> adPlaybackState =
        ArgumentCaptor.forClass(AdPlaybackState.class);
    verify(mockEventListener, times(3)).onAdPlaybackState(adPlaybackState.capture());
    assertThat(adPlaybackState.getAllValues().get(0).getAdGroup(/* adGroupIndex= */ 0).states)
        .isEqualTo(new int[] {1, 1});
    assertThat(adPlaybackState.getAllValues().get(1).getAdGroup(/* adGroupIndex= */ 0).states)
        .isEqualTo(new int[] {1, 4});
    assertThat(adPlaybackState.getAllValues().get(2).getAdGroup(/* adGroupIndex= */ 0).states)
        .isEqualTo(new int[] {4, 4});
  }

  @Test
  public void onMetadata_listenerCallbackCalled() {
    Metadata metadata = new Metadata(/* presentationTimeUs= */ 0L);
    when(mockPlayer.getCurrentMediaItem()).thenReturn(contentMediaItem);
    when(mockPlayer.isPlayingAd()).thenReturn(true);
    when(mockPlayer.getCurrentAdGroupIndex()).thenReturn(1);
    when(mockPlayer.getCurrentAdIndexInAdGroup()).thenReturn(2);
    when(mockPlayer.getCurrentTimeline())
        .thenReturn(
            new FakeTimeline(
                contentWindowDefinition
                    .buildUpon()
                    .setAdPlaybackStates(
                        ImmutableList.of(new AdPlaybackState(adsConfiguration.adsId)))
                    .build()));
    adsLoader.setPlayer(mockPlayer);
    adsLoader.start(adsMediaSource, adTagDataSpec, "adsId", mockAdViewProvider, mockEventListener);
    ArgumentCaptor<Player.Listener> listener = ArgumentCaptor.forClass(Player.Listener.class);
    verify(mockPlayer).addListener(listener.capture());

    listener.getValue().onMetadata(metadata);

    InOrder inOrder = inOrder(mockAdsLoaderListener);
    inOrder
        .verify(mockAdsLoaderListener)
        .onStart(contentMediaItem, adsMediaSource.getAdsId(), mockAdViewProvider);
    inOrder
        .verify(mockAdsLoaderListener)
        .onMetadata(
            contentMediaItem,
            adsMediaSource.getAdsId(),
            /* adGroupIndex= */ 1,
            /* adIndexInAdGroup= */ 2,
            metadata);
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void onMetadata_differentMediaItem_listenerCallbackNotCalled() {
    Metadata metadata = new Metadata(/* presentationTimeUs= */ 0L);
    when(mockPlayer.getCurrentMediaItem()).thenReturn(MediaItem.fromUri(Uri.EMPTY));
    when(mockPlayer.isPlayingAd()).thenReturn(true);
    when(mockPlayer.getCurrentAdGroupIndex()).thenReturn(1);
    when(mockPlayer.getCurrentAdIndexInAdGroup()).thenReturn(2);
    when(mockPlayer.getCurrentTimeline()).thenReturn(new FakeTimeline(contentWindowDefinition));
    adsLoader.setPlayer(mockPlayer);
    adsLoader.start(adsMediaSource, adTagDataSpec, "adsId", mockAdViewProvider, mockEventListener);
    ArgumentCaptor<Player.Listener> listener = ArgumentCaptor.forClass(Player.Listener.class);
    verify(mockPlayer).addListener(listener.capture());

    listener.getValue().onMetadata(metadata);

    verify(mockAdsLoaderListener)
        .onStart(contentMediaItem, adsMediaSource.getAdsId(), mockAdViewProvider);
    verifyNoMoreInteractions(mockAdsLoaderListener);
  }

  @Test
  public void onMetadata_noAdIsPlaying_listenerCallbackNotCalled() {
    Metadata metadata = new Metadata(/* presentationTimeUs= */ 0L);
    when(mockPlayer.getCurrentMediaItem()).thenReturn(contentMediaItem);
    when(mockPlayer.isPlayingAd()).thenReturn(false);
    when(mockPlayer.getCurrentAdGroupIndex()).thenReturn(-1);
    when(mockPlayer.getCurrentAdIndexInAdGroup()).thenReturn(-1);
    when(mockPlayer.getCurrentTimeline()).thenReturn(new FakeTimeline(contentWindowDefinition));
    adsLoader.setPlayer(mockPlayer);
    adsLoader.start(adsMediaSource, adTagDataSpec, "adsId", mockAdViewProvider, mockEventListener);
    ArgumentCaptor<Player.Listener> listener = ArgumentCaptor.forClass(Player.Listener.class);
    verify(mockPlayer).addListener(listener.capture());

    listener.getValue().onMetadata(metadata);

    verify(mockAdsLoaderListener)
        .onStart(contentMediaItem, adsMediaSource.getAdsId(), mockAdViewProvider);
    verifyNoMoreInteractions(mockAdsLoaderListener);
  }

  @Test
  public void stop_playerListenerRemoved() {
    when(mockPlayer.getCurrentTimeline()).thenReturn(new FakeTimeline(contentWindowDefinition));
    adsLoader.setPlayer(mockPlayer);
    adsLoader.start(adsMediaSource, adTagDataSpec, "adsId", mockAdViewProvider, mockEventListener);
    ArgumentCaptor<Player.Listener> listener = ArgumentCaptor.forClass(Player.Listener.class);
    InOrder inOrder = inOrder(mockPlayer);
    inOrder.verify(mockPlayer).addListener(listener.capture());
    inOrder.verifyNoMoreInteractions();
    reset(mockPlayer);

    adsLoader.stop(adsMediaSource, mockEventListener);

    verify(mockPlayer).removeListener(listener.getValue());
    verifyNoMoreInteractions(mockPlayer);
  }

  @Test
  public void release_neverStarted_playerListenerNotAddedNorRemoved() {
    adsLoader.setPlayer(mockPlayer);
    assertThat(adsLoader.isReleased()).isFalse();

    adsLoader.release();

    assertThat(adsLoader.isReleased()).isTrue();
    verifyNoMoreInteractions(mockPlayer);
  }

  @Test
  public void release_clearsResumptionStates() {
    adsLoader.addAdResumptionState(
        "adsId", new AdPlaybackState(/* adsId= */ "adsId", 0L, C.TIME_END_OF_SOURCE));

    adsLoader.release();

    assertThat(adsLoader.removeAdResumptionState("adsId")).isFalse();
  }

  @Test
  public void release_afterStartButBeforeStopped_playerListenerRemovedAfterAllSourcesStopped() {
    when(mockPlayer.getCurrentTimeline()).thenReturn(new FakeTimeline(contentWindowDefinition));
    adsLoader.setPlayer(mockPlayer);
    adsLoader.start(adsMediaSource, adTagDataSpec, "adsId", mockAdViewProvider, mockEventListener);
    ArgumentCaptor<Player.Listener> listener = ArgumentCaptor.forClass(Player.Listener.class);
    InOrder inOrder = inOrder(mockPlayer);
    inOrder.verify(mockPlayer).addListener(listener.capture());
    inOrder.verifyNoMoreInteractions();
    reset(mockPlayer);

    adsLoader.release();
    adsLoader.handleContentTimelineChanged(
        adsMediaSource, new FakeTimeline(contentWindowDefinition));
    adsLoader.stop(adsMediaSource, mockEventListener);

    verify(mockPlayer).removeListener(listener.capture());
    verifyNoMoreInteractions(mockPlayer);
  }

  @Test
  public void start_whenReleased_keepsPlaybackOnGoingAndNoListenerCalled() {
    when(mockPlayer.getCurrentTimeline()).thenReturn(new FakeTimeline(contentWindowDefinition));
    adsLoader.setPlayer(mockPlayer);
    adsLoader.release();

    adsLoader.start(adsMediaSource, adTagDataSpec, "adsId", mockAdViewProvider, mockEventListener);
    adsLoader.handleContentTimelineChanged(
        adsMediaSource, new FakeTimeline(contentWindowDefinition));
    adsLoader.stop(adsMediaSource, mockEventListener);

    verifyNoMoreInteractions(mockAdsLoaderListener);
    verify(mockEventListener).onAdPlaybackState(new AdPlaybackState("adsId"));
    verifyNoMoreInteractions(mockEventListener);
  }

  @Test
  public void
      handleContentTimelineChanged_whenReleasedWithStartedSource_keepsPlaybackOnGoingAndNoListenerCalled()
          throws IOException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:40.000Z\","
            + "DURATION=15,"
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
            + "\n";
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));
    HlsMediaPlaylist contentMediaPlaylist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(Uri.EMPTY, inputStream);
    HlsManifest hlsManifest =
        new HlsManifest(/* multivariantPlaylist= */ null, contentMediaPlaylist);
    when(mockPlayer.getCurrentTimeline()).thenReturn(new FakeTimeline(contentWindowDefinition));
    // Set the player.
    adsLoader.setPlayer(mockPlayer);
    // Start the ad.
    adsLoader.start(adsMediaSource, adTagDataSpec, "adsId", mockAdViewProvider, mockEventListener);
    reset(mockEventListener);
    reset(mockAdsLoaderListener);

    adsLoader.release();
    adsLoader.handleContentTimelineChanged(
        adsMediaSource, new FakeTimeline(new Object[] {hlsManifest}, contentWindowDefinition));
    adsLoader.stop(adsMediaSource, mockEventListener);

    verifyNoMoreInteractions(mockAdsLoaderListener);
    verify(mockEventListener).onAdPlaybackState(new AdPlaybackState("adsId"));
    verifyNoMoreInteractions(mockEventListener);
  }

  @Test
  public void setPlayer_nulledWithStartedSource_doesNotCrashAndListenerCalled() throws IOException {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:40.000Z\","
            + "DURATION=15,"
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
            + "\n";
    callHandleContentTimelineChangedAndCaptureAdPlaybackState(
        playlistString,
        adsLoader,
        /* windowIndex= */ 0,
        /* windowPositionInPeriodUs= */ 0,
        /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE);
    reset(mockPlayer);
    reset(mockEventListener);
    reset(mockAdsLoaderListener);

    adsLoader.setPlayer(null);
    adsLoader.handleContentTimelineChanged(
        adsMediaSource, new FakeTimeline(contentWindowDefinition));
    adsLoader.handlePrepareError(
        adsMediaSource, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, new IOException());
    adsLoader.handlePrepareComplete(
        adsMediaSource, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    adsLoader.stop(adsMediaSource, mockEventListener);

    verify(mockPlayer).removeListener(any());
    verifyNoMoreInteractions(mockPlayer);
    verify(mockEventListener).onAdPlaybackState(any());
    verifyNoMoreInteractions(mockEventListener);
    InOrder inOrder = inOrder(mockAdsLoaderListener);
    inOrder.verify(mockAdsLoaderListener).onPrepareError(any(), any(), anyInt(), anyInt(), any());
    inOrder.verify(mockAdsLoaderListener).onPrepareCompleted(any(), any(), anyInt(), anyInt());
    inOrder.verify(mockAdsLoaderListener).onStop(any(), any(), any());
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void setPlayer_playerAlreadySetWithActiveListeners_throwIllegalArgumentException() {
    Player secondMockPlayer = mock(Player.class);
    when(mockPlayer.getCurrentTimeline()).thenReturn(new FakeTimeline(contentWindowDefinition));
    adsLoader.setPlayer(mockPlayer);
    adsLoader.start(adsMediaSource, adTagDataSpec, "adsId", mockAdViewProvider, mockEventListener);

    assertThrows(IllegalArgumentException.class, () -> adsLoader.setPlayer(secondMockPlayer));
  }

  @Test
  public void setPlayer_playerAlreadySetWithoutActiveListeners_playerSet() {
    Player secondMockPlayer = mock(ExoPlayer.class);
    when(mockPlayer.getCurrentTimeline()).thenReturn(new FakeTimeline(contentWindowDefinition));
    adsLoader.setPlayer(mockPlayer);

    adsLoader.setPlayer(secondMockPlayer);

    verifyNoMoreInteractions(mockPlayer);
    verifyNoMoreInteractions(secondMockPlayer);
  }

  @Test
  public void setPlayer_setToNullWithActiveListeners_playerListenerRemoved() {
    when(mockPlayer.getCurrentTimeline()).thenReturn(new FakeTimeline(contentWindowDefinition));
    adsLoader.setPlayer(mockPlayer);
    adsLoader.start(adsMediaSource, adTagDataSpec, "adsId", mockAdViewProvider, mockEventListener);
    reset(mockPlayer);

    adsLoader.setPlayer(null);

    verify(mockPlayer).removeListener(any());
    verifyNoMoreInteractions(mockPlayer);
  }

  @Test
  public void setPlayer_setToNullWithoutActiveListeners_playerSet() {
    when(mockPlayer.getCurrentTimeline()).thenReturn(new FakeTimeline(contentWindowDefinition));
    adsLoader.setPlayer(mockPlayer);

    adsLoader.setPlayer(/* player= */ null);

    verifyNoMoreInteractions(mockPlayer);
  }

  @Test
  public void addRemoveListener_listenerNotifiedWhenAdded() {
    adsLoader.setPlayer(mockPlayer);
    when(mockPlayer.getCurrentTimeline()).thenReturn(new FakeTimeline(contentWindowDefinition));
    HlsInterstitialsAdsLoader.Listener mockAdsLoaderListener2 =
        mock(HlsInterstitialsAdsLoader.Listener.class);

    // add a second listener and trigger onStart callback
    adsLoader.addListener(mockAdsLoaderListener2);
    adsLoader.start(adsMediaSource, adTagDataSpec, "adsId", mockAdViewProvider, mockEventListener);

    verify(mockAdsLoaderListener2).onStart(any(), any(), any());
    verify(mockAdsLoaderListener).onStart(any(), any(), any());

    // remove the second listener and trigger onStop callback
    adsLoader.removeListener(mockAdsLoaderListener2);
    adsLoader.stop(adsMediaSource, mockEventListener);

    verifyNoMoreInteractions(mockAdsLoaderListener2);
    verify(mockAdsLoaderListener).onStop(any(), any(), any());
    verifyNoMoreInteractions(mockAdsLoaderListener);
  }

  @Test
  public void listener_wholeLifecycle_adsLoaderListenerCallbacksCorrectlyCalled()
      throws IOException {
    Metadata metadata = new Metadata(/* presentationTimeUs= */ 0L);
    when(mockPlayer.isPlayingAd()).thenReturn(true);
    when(mockPlayer.getCurrentAdGroupIndex()).thenReturn(0);
    when(mockPlayer.getCurrentAdIndexInAdGroup()).thenReturn(1);
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:41.123Z\","
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-1\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:43.123Z\","
            + "X-ASSET-URI=\"http://example.com/media-0-1.m3u8\""
            + "\n";
    ArgumentCaptor<AdPlaybackState> adPlaybackState =
        ArgumentCaptor.forClass(AdPlaybackState.class);
    IOException exception = new IOException();
    ArgumentCaptor<Player.Listener> listener = ArgumentCaptor.forClass(Player.Listener.class);

    callHandleContentTimelineChangedAndCaptureAdPlaybackState(
        playlistString,
        adsLoader,
        /* windowIndex= */ 0,
        /* windowPositionInPeriodUs= */ 0,
        /* windowEndPositionInPeriodUs= */ C.TIME_END_OF_SOURCE);
    adsLoader.handlePrepareError(
        adsMediaSource, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, exception);
    adsLoader.handlePrepareComplete(
        adsMediaSource, /* adGroupIndex= */ 1, /* adIndexInAdGroup= */ 0);
    verify(mockPlayer).addListener(listener.capture());
    listener.getValue().onMetadata(metadata);
    adsLoader.stop(adsMediaSource, mockEventListener);

    InOrder inOrder = inOrder(mockAdsLoaderListener);
    inOrder
        .verify(mockAdsLoaderListener)
        .onStart(contentMediaItem, adsMediaSource.getAdsId(), mockAdViewProvider);
    inOrder
        .verify(mockAdsLoaderListener)
        .onContentTimelineChanged(eq(contentMediaItem), eq(adsMediaSource.getAdsId()), any());
    inOrder
        .verify(mockAdsLoaderListener)
        .onPrepareError(contentMediaItem, adsMediaSource.getAdsId(), 0, 0, exception);
    inOrder
        .verify(mockAdsLoaderListener)
        .onPrepareCompleted(contentMediaItem, adsMediaSource.getAdsId(), 1, 0);
    inOrder
        .verify(mockAdsLoaderListener)
        .onMetadata(contentMediaItem, adsMediaSource.getAdsId(), 0, 1, metadata);
    inOrder
        .verify(mockAdsLoaderListener)
        .onStop(eq(contentMediaItem), eq(adsMediaSource.getAdsId()), adPlaybackState.capture());
    inOrder.verifyNoMoreInteractions();
    assertThat(adPlaybackState.getValue().getAdGroup(/* adGroupIndex= */ 0).states)
        .isEqualTo(new int[] {4});
    assertThat(adPlaybackState.getValue().getAdGroup(/* adGroupIndex= */ 1).states)
        .isEqualTo(new int[] {1});
  }

  @Test
  public void listener_unsupportedMediaItem_adsLoaderListenerNotCalled() {
    DefaultMediaSourceFactory defaultMediaSourceFactory =
        new DefaultMediaSourceFactory((Context) ApplicationProvider.getApplicationContext());
    AdsMediaSource adsMediaSource =
        new AdsMediaSource(
            defaultMediaSourceFactory.createMediaSource(
                MediaItem.fromUri("https://example.com/media.mp4")),
            new DataSpec(Uri.EMPTY),
            "adsId",
            defaultMediaSourceFactory,
            adsLoader,
            mockAdViewProvider);
    when(mockPlayer.getCurrentTimeline()).thenReturn(new FakeTimeline(contentWindowDefinition));
    adsLoader.setPlayer(mockPlayer);

    adsLoader.start(adsMediaSource, adTagDataSpec, "adsId", mockAdViewProvider, mockEventListener);
    adsLoader.handleContentTimelineChanged(
        adsMediaSource, new FakeTimeline(contentWindowDefinition));
    adsLoader.stop(adsMediaSource, mockEventListener);

    verifyNoMoreInteractions(mockAdsLoaderListener);
    verify(mockEventListener).onAdPlaybackState(new AdPlaybackState("adsId"));
    verifyNoMoreInteractions(mockEventListener);
  }

  @Test
  public void state_bundleUnbundleRoundTrip_createsEqualInstance() {
    AdPlaybackState adPlaybackState =
        new AdPlaybackState(
                /* adsId= */ "1234", /* adGroupTimesUs...= */ 0L, 10L, C.TIME_END_OF_SOURCE)
            .withLivePostrollPlaceholderAppended(/* isServerSideInserted= */ true)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1);
    AdsResumptionState adsResumptionState = new AdsResumptionState("1234", adPlaybackState);

    AdsResumptionState resultingAdsResumptionState =
        AdsResumptionState.fromBundle(adsResumptionState.toBundle());

    assertThat(resultingAdsResumptionState).isEqualTo(adsResumptionState);
  }

  @Test
  public void state_constructorWithAdsIdsThatDoNotMatch_throwsIllegalArgumentException() {
    AdPlaybackState adPlaybackState = new AdPlaybackState("1234");

    assertThrows(
        IllegalArgumentException.class, () -> new AdsResumptionState("5678", adPlaybackState));
  }

  @Test
  public void getClosestSegmentBoundaryUs_vastlyVariedSegmentSize() throws IOException {
    String interstitialStartDate = "2025-10-07T01:00:14.000Z";
    long interstitialStartDateUs = msToUs(Util.parseXsDateTime(interstitialStartDate));
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:10\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2025-10-07T01:00:00.000Z\n"
            + "#EXTINF:2,\n"
            + "1.aac\n"
            + "#EXTINF:2,\n"
            + "2.aac\n"
            + "#EXTINF:10,\n"
            + "3.aac\n"
            + "#EXT-X-DATERANGE:ID=\"1\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\""
            + interstitialStartDate
            + "\","
            + "X-ASSET-LIST=\"http://example.com/assetlist-0-0.json\","
            + "X-SNAP=\"OUT,IN\"\n"
            + "#EXTINF:10,\n"
            + "4.aac";

    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));
    HlsMediaPlaylist contentMediaPlaylist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(Uri.EMPTY, inputStream);

    long closestSegmentBoundaryUs =
        getClosestSegmentBoundaryUs(interstitialStartDateUs, contentMediaPlaylist);

    assertThat(closestSegmentBoundaryUs)
        .isEqualTo(contentMediaPlaylist.startTimeUs + 2_000_000 + 2_000_000 + 10_000_000);
  }

  @Test
  public void getClosestSegmentBoundaryUs_positionBeforeAndAfterPlaylist_getsSanitizedValues()
      throws IOException {
    String timestampBeforePlaylist = "2025-10-07T00:00:00.000Z";
    long timeBeforePlaylistUs = msToUs(Util.parseXsDateTime(timestampBeforePlaylist));
    String timestampLastNonPostRollSnap = "2025-10-07T01:00:18.999Z";
    long timestampLastNonPostRollSnapUs =
        msToUs(Util.parseXsDateTime(timestampLastNonPostRollSnap));
    String timestampPostRollSnap = "2025-10-07T01:00:19.000Z";
    long timestampPostRollSnapUs = msToUs(Util.parseXsDateTime(timestampPostRollSnap));
    String timestampAfterPlaylist = "2025-10-07T23:59:59.000Z";
    long timestampAfterPlaylistUs = msToUs(Util.parseXsDateTime(timestampAfterPlaylist));
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:10\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2025-10-07T01:00:00.000Z\n"
            + "#EXTINF:2,\n"
            + "1.aac\n"
            + "#EXTINF:2,\n"
            + "2.aac\n"
            + "#EXTINF:10,\n"
            + "3.aac\n"
            + "#EXTINF:10,\n"
            + "4.aac";

    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));
    HlsMediaPlaylist contentMediaPlaylist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(Uri.EMPTY, inputStream);

    assertThat(getClosestSegmentBoundaryUs(timeBeforePlaylistUs, contentMediaPlaylist))
        .isEqualTo(contentMediaPlaylist.startTimeUs);
    assertThat(getClosestSegmentBoundaryUs(timestampLastNonPostRollSnapUs, contentMediaPlaylist))
        .isEqualTo(contentMediaPlaylist.startTimeUs + 14_000_000L);
    assertThat(getClosestSegmentBoundaryUs(timestampPostRollSnapUs, contentMediaPlaylist))
        .isEqualTo(contentMediaPlaylist.startTimeUs + 24_000_000L);
    assertThat(getClosestSegmentBoundaryUs(timestampAfterPlaylistUs, contentMediaPlaylist))
        .isEqualTo(contentMediaPlaylist.startTimeUs + 24_000_000L);
  }

  private List<AdPlaybackState> callHandleContentTimelineChangedForLiveAndCaptureAdPlaybackStates(
      HlsInterstitialsAdsLoader adsLoader,
      boolean startAdsLoader,
      long windowOffsetInFirstPeriodUs,
      String... playlistStrings)
      throws IOException {
    if (startAdsLoader) {
      // Set the player.
      adsLoader.setPlayer(mockPlayer);
      // Start the ad.
      adsLoader.start(
          adsMediaSource, adTagDataSpec, "adsId", mockAdViewProvider, mockEventListener);
    }

    HlsPlaylistParser hlsPlaylistParser = new HlsPlaylistParser();
    long firstPlaylistStartTimeUs = C.TIME_UNSET;
    ArgumentCaptor<AdPlaybackState> adPlaybackState =
        ArgumentCaptor.forClass(AdPlaybackState.class);
    for (String playlistString : playlistStrings) {
      InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));
      HlsMediaPlaylist mediaPlaylist =
          (HlsMediaPlaylist) hlsPlaylistParser.parse(Uri.EMPTY, inputStream);
      if (firstPlaylistStartTimeUs == C.TIME_UNSET) {
        firstPlaylistStartTimeUs = mediaPlaylist.startTimeUs;
      }
      HlsManifest hlsManifest = new HlsManifest(/* multivariantPlaylist= */ null, mediaPlaylist);
      TimelineWindowDefinition timelineWindowDefinition =
          new TimelineWindowDefinition.Builder()
              .setDynamic(true)
              .setLive(true)
              .setDurationUs(mediaPlaylist.durationUs)
              .setDefaultPositionUs(mediaPlaylist.durationUs / 2)
              .setWindowStartTimeUs(mediaPlaylist.startTimeUs)
              .setWindowPositionInFirstPeriodUs(
                  windowOffsetInFirstPeriodUs
                      + (mediaPlaylist.startTimeUs - firstPlaylistStartTimeUs))
              .setMediaItem(contentMediaItem)
              .build();
      adsLoader.handleContentTimelineChanged(
          adsMediaSource, new FakeTimeline(new Object[] {hlsManifest}, timelineWindowDefinition));
      verify(mockEventListener, atMost(playlistStrings.length))
          .onAdPlaybackState(adPlaybackState.capture());
      when(mockPlayer.getCurrentTimeline())
          .thenReturn(
              new FakeTimeline(
                  timelineWindowDefinition
                      .buildUpon()
                      .setAdPlaybackStates(ImmutableList.of(adPlaybackState.getValue()))
                      .build()));
    }
    return adPlaybackState.getAllValues();
  }

  private AdPlaybackState callHandleContentTimelineChangedAndCaptureAdPlaybackState(
      String playlistString,
      HlsInterstitialsAdsLoader adsLoader,
      int windowIndex,
      long windowPositionInPeriodUs,
      long windowEndPositionInPeriodUs)
      throws IOException {
    checkArgument(
        windowPositionInPeriodUs >= 0
            && (windowEndPositionInPeriodUs == C.TIME_END_OF_SOURCE
                || windowPositionInPeriodUs < windowEndPositionInPeriodUs));
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));
    HlsMediaPlaylist contentMediaPlaylist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(Uri.EMPTY, inputStream);
    TimelineWindowDefinition[] initialWindows = new TimelineWindowDefinition[windowIndex + 1];
    Arrays.fill(
        initialWindows,
        new TimelineWindowDefinition.Builder()
            .setMediaItem(MediaItem.fromUri("http://example.com/"))
            .build());
    long durationUs =
        (windowEndPositionInPeriodUs != C.TIME_END_OF_SOURCE
                ? windowEndPositionInPeriodUs
                : contentMediaPlaylist.durationUs)
            - windowPositionInPeriodUs;
    initialWindows[windowIndex] =
        contentWindowDefinition
            .buildUpon()
            .setPlaceholder(true)
            .setDynamic(true)
            .setDurationUs(C.TIME_UNSET)
            .setDurationUs(durationUs)
            .setWindowPositionInFirstPeriodUs(windowPositionInPeriodUs)
            .build();
    when(mockPlayer.getCurrentTimeline()).thenReturn(new FakeTimeline(initialWindows));
    when(mockPlayer.getCurrentMediaItem()).thenReturn(contentWindowDefinition.mediaItem);
    when(mockPlayer.getCurrentMediaItemIndex()).thenReturn(windowIndex);
    when(mockPlayer.getCurrentPeriodIndex()).thenReturn(windowIndex);
    // Set the player.
    adsLoader.setPlayer(mockPlayer);
    // Start the ad.
    adsLoader.start(adsMediaSource, adTagDataSpec, "adsId", mockAdViewProvider, mockEventListener);

    // Notify ads loader about the media playlist.
    HlsManifest hlsManifest =
        new HlsManifest(/* multivariantPlaylist= */ null, contentMediaPlaylist);
    adsLoader.handleContentTimelineChanged(
        adsMediaSource, new FakeTimeline(new Object[] {hlsManifest}, initialWindows[windowIndex]));

    ArgumentCaptor<AdPlaybackState> adPlaybackState =
        ArgumentCaptor.forClass(AdPlaybackState.class);
    verify(mockEventListener).onAdPlaybackState(adPlaybackState.capture());
    TimelineWindowDefinition[] windowsAfterTimelineChange =
        new TimelineWindowDefinition[windowIndex + 1];
    Arrays.fill(
        windowsAfterTimelineChange,
        new TimelineWindowDefinition.Builder()
            .setMediaItem(MediaItem.fromUri("http://example.com/"))
            .build());
    windowsAfterTimelineChange[windowIndex] =
        contentWindowDefinition
            .buildUpon()
            .setDurationUs(durationUs)
            .setWindowStartTimeUs(contentMediaPlaylist.startTimeUs)
            .setWindowPositionInFirstPeriodUs(windowPositionInPeriodUs)
            .setAdPlaybackStates(ImmutableList.of(adPlaybackState.getValue()))
            .build();
    when(mockPlayer.getCurrentTimeline()).thenReturn(new FakeTimeline(windowsAfterTimelineChange));
    return adPlaybackState.getValue();
  }

  private static void verifyTimelineUpdate(
      InOrder inOrder, ExoPlayer mockPlayer, boolean verifyMessageScheduled) {
    inOrder.verify(mockPlayer).getCurrentMediaItem();
    inOrder.verify(mockPlayer).getContentPosition();
    if (verifyMessageScheduled) {
      inOrder.verify(mockPlayer).createMessage(any());
    }
  }

  private static void verifyAssetListLoadCompleted(
      InOrder inOrder, ExoPlayer mockPlayer, boolean verifyMessageScheduled) {
    inOrder.verify(mockPlayer).getCurrentMediaItem();
    inOrder.verify(mockPlayer).getContentPosition();
    inOrder.verify(mockPlayer).getCurrentTimeline();
    inOrder.verify(mockPlayer).getCurrentPeriodIndex();
    inOrder.verify(mockPlayer).getCurrentMediaItemIndex();
    if (verifyMessageScheduled) {
      inOrder.verify(mockPlayer).createMessage(any());
    }
  }

  private byte[] getJsonAssetList(int assetCount, int delayMs) {
    StringBuilder assetList = new StringBuilder("{\"ASSETS\": [");
    for (int i = 0; i < assetCount; i++) {
      assetList.append(getJsonAsset(/* uri= */ "http://" + i, /* durationSec= */ 10.123d + i));
      if (i < assetCount - 1) {
        assetList.append(",");
      }
    }
    if (delayMs > 0) {
      try {
        Thread.sleep(delayMs);
      } catch (InterruptedException e) {
        // ignored.
      }
    }
    return assetList.append("]}\n").toString().getBytes(Charset.defaultCharset());
  }

  private byte[] getJsonAssetListWithSkipInformation(
      int assetCount,
      @Nullable Float skipInfoOffsetSeconds,
      @Nullable Float skipInfoDurationSeconds,
      @Nullable String skipInfoLabelId) {
    StringBuilder assetList = new StringBuilder("{\"ASSETS\": [");
    for (int i = 0; i < assetCount; i++) {
      assetList.append(getJsonAsset(/* uri= */ "http://" + i, /* durationSec= */ 10.123d + i));
      if (i < assetCount - 1) {
        assetList.append(",");
      }
    }
    assetList.append("],\n");
    assetList.append("\"SKIP-CONTROL\": {");
    if (skipInfoOffsetSeconds != null) {
      assetList.append("   \"OFFSET\": ").append(skipInfoOffsetSeconds).append(",");
    }
    if (skipInfoDurationSeconds != null) {
      assetList.append("   \"DURATION\": ").append(skipInfoDurationSeconds);
      if (skipInfoLabelId != null) {
        assetList.append(",");
      }
    }
    if (skipInfoLabelId != null) {
      assetList.append("   \"LABEL-ID\": \"").append(skipInfoLabelId).append("\"");
    }
    assetList.append("}"); // end of SKIP_CONTROL
    assetList.append("}"); // end of document
    return assetList.toString().getBytes(Charset.defaultCharset());
  }

  private static String getJsonAsset(String uri, double durationSec) {
    return String.format(Locale.US, "{\"URI\": \"%s\", \"DURATION\": %f}", uri, durationSec);
  }

  @SuppressWarnings("NewClassNamingConvention")
  private static final class AssetListLoadingListener
      implements HlsInterstitialsAdsLoader.Listener {

    private final AtomicInteger assetListLoadCompletedCounter;
    private final AtomicInteger assetListLoadFailedCounter;

    private AssetListLoadingListener() {
      this.assetListLoadCompletedCounter = new AtomicInteger();
      this.assetListLoadFailedCounter = new AtomicInteger();
    }

    public boolean completed() {
      return assetListLoadCompletedCounter.get() > 0;
    }

    public boolean failed() {
      return assetListLoadFailedCounter.get() > 0;
    }

    @Override
    public void onAssetListLoadCompleted(
        MediaItem mediaItem,
        Object adsId,
        int adGroupIndex,
        int adIndexInAdGroup,
        AssetList assetList) {
      assetListLoadCompletedCounter.incrementAndGet();
    }

    @Override
    public void onAssetListLoadFailed(
        MediaItem mediaItem,
        Object adsId,
        int adGroupIndex,
        int adIndexInAdGroup,
        @Nullable IOException ioException,
        boolean cancelled) {
      assetListLoadFailedCounter.incrementAndGet();
    }
  }
}
