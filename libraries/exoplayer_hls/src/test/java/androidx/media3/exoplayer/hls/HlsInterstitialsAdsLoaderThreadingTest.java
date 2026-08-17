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

import static androidx.media3.common.Player.DISCONTINUITY_REASON_AUTO_TRANSITION;
import static androidx.media3.exoplayer.hls.HlsInterstitialsTestUtil.getJsonAssetList;
import static androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.AdViewProvider;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.ByteArrayDataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsInterstitialsAdsLoader.AssetList;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser;
import androidx.media3.exoplayer.source.ads.AdsLoader;
import androidx.media3.exoplayer.source.ads.AdsMediaSource;
import androidx.media3.test.utils.FakeTimeline;
import androidx.media3.test.utils.FakeTimeline.TimelineWindowDefinition;
import androidx.test.core.app.ApplicationProvider;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameter;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters;

/**
 * Unit tests verifying thread safety and looper confinement in {@link HlsInterstitialsAdsLoader}.
 */
@RunWith(ParameterizedRobolectricTestRunner.class)
public class HlsInterstitialsAdsLoaderThreadingTest {

  public enum LooperType {
    MAIN,
    BACKGROUND
  }

  @Parameters(name = "looperType={0}")
  public static ImmutableList<Object[]> params() {
    return ImmutableList.of(new Object[] {LooperType.MAIN}, new Object[] {LooperType.BACKGROUND});
  }

  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  @Parameter(0)
  public LooperType looperType;

  @Mock private HlsInterstitialsAdsLoader.Listener mockAdsLoaderListener;
  @Mock private AdsLoader.EventListener mockEventListener;
  @Mock private ExoPlayer mockPlayer;
  @Mock private AdViewProvider mockAdViewProvider;

  private Context context;
  private HlsInterstitialsAdsLoader adsLoader;
  private HandlerThread backgroundThread;
  private Looper backgroundLooper;
  private MediaItem.AdsConfiguration adsConfiguration;
  private MediaItem contentMediaItem;
  private TimelineWindowDefinition contentWindowDefinition;
  private DataSpec adTagDataSpec;
  private AdsMediaSource adsMediaSource;

  private Looper getTargetLooper() {
    return looperType == LooperType.MAIN ? Looper.getMainLooper() : backgroundLooper;
  }

  private Looper getOtherLooper() {
    return looperType == LooperType.MAIN ? backgroundLooper : Looper.getMainLooper();
  }

  private void runOnTargetLooper(Runnable runnable) throws Exception {
    if (Objects.equals(Looper.myLooper(), getTargetLooper())) {
      runnable.run();
    } else {
      CountDownLatch latch = new CountDownLatch(1);
      new Handler(getTargetLooper())
          .post(
              () -> {
                runnable.run();
                latch.countDown();
              });
      latch.await();
    }
  }

  @Before
  public void setUp() throws Exception {
    context = ApplicationProvider.getApplicationContext();
    backgroundThread = new HandlerThread("HlsAdsLoaderTestBackgroundThread");
    backgroundThread.start();
    backgroundLooper = backgroundThread.getLooper();
    when(mockPlayer.getApplicationLooper()).thenReturn(getTargetLooper());
    // Initialize AdsLoader on the target looper.
    if (looperType == LooperType.MAIN) {
      adsLoader =
          new HlsInterstitialsAdsLoader(() -> new ByteArrayDataSource(new JsonUriResolver()));
      adsLoader.addListener(mockAdsLoaderListener);
    } else {
      CountDownLatch latch = new CountDownLatch(1);
      new Handler(backgroundLooper)
          .post(
              () -> {
                adsLoader =
                    new HlsInterstitialsAdsLoader(
                        () -> new ByteArrayDataSource(new JsonUriResolver()));
                adsLoader.addListener(mockAdsLoaderListener);
                latch.countDown();
              });
      latch.await();
    }
    adsConfiguration = new MediaItem.AdsConfiguration.Builder(Uri.EMPTY).setAdsId("adsId").build();
    contentMediaItem =
        new MediaItem.Builder()
            .setUri("http://example.com/media.m3u8")
            .setAdsConfiguration(adsConfiguration)
            .build();
    contentWindowDefinition =
        new TimelineWindowDefinition.Builder()
            .setDurationUs(90_000_000L)
            .setWindowPositionInFirstPeriodUs(0L)
            .setMediaItem(contentMediaItem)
            .build();
    adTagDataSpec = new DataSpec(Uri.EMPTY);
    adsMediaSource =
        (AdsMediaSource)
            new HlsInterstitialsAdsLoader.AdsMediaSourceFactory(
                    adsLoader, mockAdViewProvider, context)
                .createMediaSource(contentMediaItem);
  }

  @After
  public void tearDown() throws Exception {
    if (adsLoader != null) {
      runOnTargetLooper(adsLoader::release);
    }
    backgroundThread.quit();
  }

  @Test
  public void backgroundPlayerDiscontinuity_marshaledToTargetLooper() throws Exception {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:40.000Z\","
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\"\n";
    callHandleContentTimelineChangedAndCaptureAdPlaybackStateOnTargetLooper(
        playlistString, adsLoader);
    ArgumentCaptor<Player.Listener> listenerCaptor = ArgumentCaptor.forClass(Player.Listener.class);
    verify(mockPlayer).addListener(listenerCaptor.capture());
    Player.Listener playerListener = listenerCaptor.getValue();
    AdPlaybackCompletionListener adCompletionListener = new AdPlaybackCompletionListener();
    runOnTargetLooper(() -> adsLoader.addListener(adCompletionListener));
    Object windowUid = new Object();
    Object periodUid = new Object();
    Player.PositionInfo oldPosition =
        new Player.PositionInfo(
            windowUid,
            /* mediaItemIndex= */ 0,
            contentMediaItem,
            periodUid,
            /* periodIndex= */ 0,
            /* positionMs= */ 10_000L,
            /* contentPositionMs= */ 0L,
            /* adGroupIndex= */ 0,
            /* adIndexInAdGroup= */ 0);
    Player.PositionInfo newPosition =
        new Player.PositionInfo(
            windowUid,
            /* mediaItemIndex= */ 0,
            contentMediaItem,
            periodUid,
            /* periodIndex= */ 0,
            /* positionMs= */ 0L,
            /* contentPositionMs= */ 0L,
            /* adGroupIndex= */ C.INDEX_UNSET,
            /* adIndexInAdGroup= */ C.INDEX_UNSET);
    // Post onPositionDiscontinuity to other looper.
    new Handler(getOtherLooper())
        .post(
            () -> {
              playerListener.onPositionDiscontinuity(
                  oldPosition, newPosition, DISCONTINUITY_REASON_AUTO_TRANSITION);
            });

    // Verify that onAdCompleted executes on target looper.
    runMainLooperUntil(() -> adCompletionListener.onAdCompletedCalled.get());
    assertThat(adCompletionListener.onAdCompletedLooper.get()).isEqualTo(getTargetLooper());
  }

  @Test
  public void backgroundAssetListCompleted_marshaledToTargetLooper() throws Exception {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:40.000Z\","
            + "X-ASSET-LIST=\"http://example.com/assetlist.json\"\n";
    AdPlaybackCompletionListener adCompletionListener = new AdPlaybackCompletionListener();
    runOnTargetLooper(() -> adsLoader.addListener(adCompletionListener));

    // Post handleContentTimelineChanged to other looper so Loader binds its callback Handler
    // to otherLooper.
    new Handler(getOtherLooper())
        .post(
            () -> {
              try {
                callHandleContentTimelineChangedAndCaptureAdPlaybackState(
                    playlistString, adsLoader);
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            });

    // Pump main looper until onLoadCompleted marshals to target looper and invokes listener on
    // target looper.
    runMainLooperUntil(() -> adCompletionListener.onAssetListLoadCompleted.get());
    assertThat(adCompletionListener.onAssetListLoadCompletedLooper.get())
        .isEqualTo(getTargetLooper());
  }

  @Test
  public void synchronousTargetThreadExecution_doesNotPost() throws Exception {
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:40.000Z\","
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\"\n";
    AdPlaybackCompletionListener adCompletionListener = new AdPlaybackCompletionListener();
    runOnTargetLooper(() -> adsLoader.addListener(adCompletionListener));
    callHandleContentTimelineChangedAndCaptureAdPlaybackStateOnTargetLooper(
        playlistString, adsLoader);
    ArgumentCaptor<Player.Listener> listenerCaptor = ArgumentCaptor.forClass(Player.Listener.class);
    verify(mockPlayer).addListener(listenerCaptor.capture());
    Player.Listener playerListener = listenerCaptor.getValue();
    Object windowUid = new Object();
    Object periodUid = new Object();
    Player.PositionInfo oldPosition =
        new Player.PositionInfo(
            windowUid,
            /* mediaItemIndex= */ 0,
            contentMediaItem,
            periodUid,
            /* periodIndex= */ 0,
            /* positionMs= */ 10_000L,
            /* contentPositionMs= */ 0L,
            /* adGroupIndex= */ 0,
            /* adIndexInAdGroup= */ 0);
    Player.PositionInfo newPosition =
        new Player.PositionInfo(
            windowUid,
            /* mediaItemIndex= */ 0,
            contentMediaItem,
            periodUid,
            /* periodIndex= */ 0,
            /* positionMs= */ 0L,
            /* contentPositionMs= */ 0L,
            /* adGroupIndex= */ C.INDEX_UNSET,
            /* adIndexInAdGroup= */ C.INDEX_UNSET);

    // Invoke onPositionDiscontinuity directly on targetLooper thread.
    if (looperType == LooperType.MAIN) {
      playerListener.onPositionDiscontinuity(
          oldPosition, newPosition, DISCONTINUITY_REASON_AUTO_TRANSITION);
      // Verify that onAdCompleted executes synchronously without needing runMainLooperUntil.
      assertThat(adCompletionListener.onAdCompletedCalled.get()).isTrue();
      assertThat(adCompletionListener.onAdCompletedLooper.get()).isEqualTo(getTargetLooper());
    } else {
      AtomicReference<Boolean> executed = new AtomicReference<>(false);
      AtomicReference<Looper> callbackLooper = new AtomicReference<>();
      CountDownLatch latch = new CountDownLatch(1);
      new Handler(backgroundLooper)
          .post(
              () -> {
                playerListener.onPositionDiscontinuity(
                    oldPosition, newPosition, DISCONTINUITY_REASON_AUTO_TRANSITION);
                executed.set(adCompletionListener.onAdCompletedCalled.get());
                callbackLooper.set(adCompletionListener.onAdCompletedLooper.get());
                latch.countDown();
              });
      latch.await();
      assertThat(executed.get()).isTrue();
      assertThat(callbackLooper.get()).isEqualTo(getTargetLooper());
    }
  }

  @CanIgnoreReturnValue
  private AdPlaybackState callHandleContentTimelineChangedAndCaptureAdPlaybackStateOnTargetLooper(
      String playlistString, HlsInterstitialsAdsLoader adsLoader) throws Exception {
    if (Objects.equals(Looper.myLooper(), getTargetLooper())) {
      return callHandleContentTimelineChangedAndCaptureAdPlaybackState(playlistString, adsLoader);
    } else {
      AtomicReference<AdPlaybackState> result = new AtomicReference<>();
      AtomicReference<Exception> exception = new AtomicReference<>();
      CountDownLatch latch = new CountDownLatch(1);
      new Handler(getTargetLooper())
          .post(
              () -> {
                try {
                  result.set(
                      callHandleContentTimelineChangedAndCaptureAdPlaybackState(
                          playlistString, adsLoader));
                } catch (Exception e) {
                  exception.set(e);
                } finally {
                  latch.countDown();
                }
              });
      latch.await();
      if (exception.get() != null) {
        throw exception.get();
      }
      return result.get();
    }
  }

  @CanIgnoreReturnValue
  private AdPlaybackState callHandleContentTimelineChangedAndCaptureAdPlaybackState(
      String playlistString, HlsInterstitialsAdsLoader adsLoader) throws IOException {
    // Set up the timeline with the interstitials info from the parsed playlist.
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));
    HlsMediaPlaylist contentMediaPlaylist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(Uri.EMPTY, inputStream);
    TimelineWindowDefinition[] initialWindows = new TimelineWindowDefinition[1];
    Arrays.fill(
        initialWindows,
        new TimelineWindowDefinition.Builder()
            .setMediaItem(MediaItem.fromUri("http://example.com/"))
            .build());
    long durationUs = contentMediaPlaylist.durationUs;
    initialWindows[0] =
        contentWindowDefinition
            .buildUpon()
            .setPlaceholder(true)
            .setDynamic(true)
            .setDurationUs(C.TIME_UNSET)
            .setWindowPositionInFirstPeriodUs(0)
            .build();
    // Mock the player state for when the ads loader calls it.
    when(mockPlayer.getCurrentTimeline()).thenReturn(new FakeTimeline(initialWindows));
    when(mockPlayer.getCurrentMediaItem()).thenReturn(contentWindowDefinition.mediaItem);
    when(mockPlayer.getCurrentMediaItemIndex()).thenReturn(0);
    when(mockPlayer.getCurrentPeriodIndex()).thenReturn(0);
    adsLoader.setPlayer(mockPlayer);

    // Start the ads loader which is when preparation of the ads media source starts.
    adsLoader.start(adsMediaSource, adTagDataSpec, "adsId", mockAdViewProvider, mockEventListener);

    // Set up the content timeline.
    HlsManifest hlsManifest =
        new HlsManifest(/* multivariantPlaylist= */ null, contentMediaPlaylist);
    TimelineWindowDefinition contentWindowWithoutAds =
        contentWindowDefinition
            .buildUpon()
            .setDurationUs(durationUs)
            .setWindowPositionInFirstPeriodUs(0)
            .setWindowStartTimeUs(contentMediaPlaylist.startTimeUs)
            .build();

    // Pass the content timeline with the interstitials into the ads loader.
    adsLoader.handleContentTimelineChanged(
        adsMediaSource, new FakeTimeline(new Object[] {hlsManifest}, contentWindowWithoutAds));

    // The ads loader will pass the updated AdPlaybackState the event listener. We capture that!
    ArgumentCaptor<AdPlaybackState> adPlaybackState =
        ArgumentCaptor.forClass(AdPlaybackState.class);
    verify(mockEventListener).onAdPlaybackState(adPlaybackState.capture());
    AdPlaybackState capturedAdPlaybackState = adPlaybackState.getValue();
    // Update the window which is the new currentTimeline of the mockPlayer with the ad data.
    TimelineWindowDefinition[] windowsAfterTimelineChange = new TimelineWindowDefinition[0 + 1];
    Arrays.fill(
        windowsAfterTimelineChange,
        new TimelineWindowDefinition.Builder()
            .setMediaItem(MediaItem.fromUri("http://example.com/"))
            .build());
    windowsAfterTimelineChange[0] =
        contentWindowWithoutAds
            .buildUpon()
            .setAdPlaybackStates(ImmutableList.of(capturedAdPlaybackState))
            .build();
    when(mockPlayer.getCurrentTimeline()).thenReturn(new FakeTimeline(windowsAfterTimelineChange));
    return capturedAdPlaybackState;
  }

  private static final class JsonUriResolver implements ByteArrayDataSource.UriResolver {
    @Override
    public byte[] resolve(Uri uri) {
      return getJsonAssetList(/* assetCount= */ 1, /* delayMs= */ 0);
    }
  }

  private static class AdPlaybackCompletionListener implements HlsInterstitialsAdsLoader.Listener {

    private final AtomicReference<Looper> onAdCompletedLooper;
    private final AtomicReference<Boolean> onAdCompletedCalled;
    private final AtomicReference<Boolean> onAssetListLoadCompleted;
    private final AtomicReference<Looper> onAssetListLoadCompletedLooper;

    private AdPlaybackCompletionListener() {
      this.onAdCompletedLooper = new AtomicReference<>();
      this.onAssetListLoadCompletedLooper = new AtomicReference<>();
      this.onAdCompletedCalled = new AtomicReference<>(false);
      this.onAssetListLoadCompleted = new AtomicReference<>(false);
    }

    @Override
    public void onAdCompleted(
        MediaItem mediaItem, Object adsId, int adGroupIndex, int adIndexInAdGroup) {
      onAdCompletedLooper.set(Looper.myLooper());
      onAdCompletedCalled.set(true);
    }

    @Override
    public void onAssetListLoadCompleted(
        MediaItem mediaItem,
        Object adsId,
        int adGroupIndex,
        int adIndexInAdGroup,
        AssetList assetList,
        JSONObject rawAssetListJson) {
      onAssetListLoadCompletedLooper.set(Looper.myLooper());
      onAssetListLoadCompleted.set(true);
    }
  }
}
