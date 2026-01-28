/*
 * Copyright 2022 The Android Open Source Project
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
package androidx.media3.exoplayer.ima;

import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.widget.LinearLayout;
import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.ima.ImaServerSideAdInsertionMediaSource.AdsLoader.State;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ads.ServerSideAdInsertionUtil;
import androidx.media3.test.utils.TestExoPlayerBuilder;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.ads.interactivemedia.v3.api.AdsLoader;
import com.google.ads.interactivemedia.v3.api.AdsLoader.AdsLoadedListener;
import com.google.ads.interactivemedia.v3.api.AdsManagerLoadedEvent;
import com.google.ads.interactivemedia.v3.api.AdsRenderingSettings;
import com.google.ads.interactivemedia.v3.api.ImaSdkFactory;
import com.google.ads.interactivemedia.v3.api.StreamDisplayContainer;
import com.google.ads.interactivemedia.v3.api.StreamManager;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.stubbing.Answer;

/** Unit tests for {@link ImaServerSideAdInsertionMediaSource}. */
@RunWith(AndroidJUnit4.class)
public class ImaServerSideAdInsertionMediaSourceTest {
  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  private static final Context context = ApplicationProvider.getApplicationContext();

  @Mock private AdsLoader mockAdsLoader;
  @Mock private ImaSdkFactory mockImaFactory;
  @Mock private AdsRenderingSettings mockAdsRenderingSettings;
  @Mock private AdsManagerLoadedEvent mockAdsManagerLoadedEvent;
  @Mock private StreamManager mockStreamManager;

  private ExoPlayer player;
  private ImaServerSideAdInsertionMediaSource.AdsLoader adsLoader;
  private ImaServerSideAdInsertionMediaSource.Factory factory;
  private MediaSource mediaSource;

  @Before
  public void setUp() {
    setupMocks();
    player = new TestExoPlayerBuilder(context).build();
    adsLoader =
        new ImaServerSideAdInsertionMediaSource.AdsLoader.Builder(
                context, /* adViewProvider= */ () -> new LinearLayout(context))
            .build();
    adsLoader.setPlayer(player);
    factory =
        new ImaServerSideAdInsertionMediaSource.Factory(
            adsLoader, new DefaultMediaSourceFactory(context));
    factory.setImaSdkFactory(mockImaFactory);
    mediaSource =
        factory.createMediaSource(
            MediaItem.fromUri("ssai://dai.google.com/?assetKey=ABC&format=0&adsId=2"));
  }

  @After
  public void teardown() {
    adsLoader.release();
    player.release();
  }

  @Test
  public void adsLoaderStateToBundle_marshallAndUnmarshalling_resultIsEqual() {
    AdPlaybackState firstAdPlaybackState =
        ServerSideAdInsertionUtil.addAdGroupToAdPlaybackState(
            new AdPlaybackState("adsId1"),
            /* fromPositionUs= */ 0,
            /* contentResumeOffsetUs= */ 10,
            /* adDurationsUs...= */ 5_000_000,
            10_000_000,
            20_000_000);
    AdPlaybackState secondAdPlaybackState =
        ServerSideAdInsertionUtil.addAdGroupToAdPlaybackState(
                new AdPlaybackState("adsId2"),
                /* fromPositionUs= */ 0,
                /* contentResumeOffsetUs= */ 10,
                /* adDurationsUs...= */ 10_000_000)
            .withPlayedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    AdPlaybackState thirdAdPlaybackState =
        ServerSideAdInsertionUtil.addAdGroupToAdPlaybackState(
            new AdPlaybackState("adsId3"),
            /* fromPositionUs= */ C.TIME_END_OF_SOURCE,
            /* contentResumeOffsetUs= */ 10,
            /* adDurationsUs...= */ 10_000_000);
    thirdAdPlaybackState =
        ServerSideAdInsertionUtil.addAdGroupToAdPlaybackState(
                thirdAdPlaybackState,
                /* fromPositionUs= */ 0,
                /* contentResumeOffsetUs= */ 10,
                /* adDurationsUs...= */ 10_000_000)
            .withRemovedAdGroupCount(1);
    State state =
        new State(
            ImmutableMap.<String, AdPlaybackState>builder()
                .put("adsId1", firstAdPlaybackState)
                .put("adsId2", secondAdPlaybackState)
                .put("adsId3", thirdAdPlaybackState)
                .buildOrThrow());

    assertThat(State.fromBundle(state.toBundle())).isEqualTo(state);
  }

  @Test
  public void clearPlaylist_withAdsSource_handlesCleanupWithoutThrowing() throws Exception {
    player.setMediaSource(mediaSource);

    player.prepare();
    advance(player).untilPendingCommandsAreFullyHandled();

    // Clearing the playlist will cause internal state of the ads source to be invalid and
    // potentially accessing empty timelines. See b/354026260. The test simply ensures that clearing
    // the playlist will not throw any exceptions.
    player.clearMediaItems();
    advance(player).untilPendingCommandsAreFullyHandled();
  }

  @Test
  public void
      prepare_withoutEnableCustomTabsInAdsLoader_doesNotEnablesCustomTabsInAdsRenderingSettings()
          throws Exception {
    player.setMediaSource(mediaSource);

    player.prepare();
    advance(player).untilPendingCommandsAreFullyHandled();

    verify(mockAdsRenderingSettings).setEnableCustomTabs(false);
  }

  @Test
  public void prepare_withEnableCustomTabsInAdsLoader_enablesCustomTabsInAdsRenderingSettings()
      throws Exception {
    ImaServerSideAdInsertionMediaSource.AdsLoader adsLoader =
        new ImaServerSideAdInsertionMediaSource.AdsLoader.Builder(
                context, /* adViewProvider= */ () -> new LinearLayout(context))
            .setEnableCustomTabs(true)
            .build();
    adsLoader.setPlayer(player);
    ImaServerSideAdInsertionMediaSource.Factory factory =
        new ImaServerSideAdInsertionMediaSource.Factory(
            adsLoader, new DefaultMediaSourceFactory(context));
    factory.setImaSdkFactory(mockImaFactory);
    MediaSource mediaSource =
        factory.createMediaSource(
            MediaItem.fromUri("ssai://dai.google.com/?assetKey=ABC&format=0&adsId=2"));
    player.setMediaSource(mediaSource);

    player.prepare();
    advance(player).untilPendingCommandsAreFullyHandled();

    verify(mockAdsRenderingSettings).setEnableCustomTabs(true);
  }

  private void setupMocks() {
    when(mockImaFactory.createAdsLoader(any(), any(), any(StreamDisplayContainer.class)))
        .thenReturn(mockAdsLoader);
    List<AdsLoadedListener> adsLoadedListeners = new ArrayList<>();
    doAnswer(
            invocation -> {
              adsLoadedListeners.add(invocation.getArgument(0));
              return null;
            })
        .when(mockAdsLoader)
        .addAdsLoadedListener(any());
    when(mockAdsLoader.requestStream(any()))
        .thenAnswer(
            (Answer<Object>)
                unused -> {
                  for (AdsLoadedListener listener : adsLoadedListeners) {
                    listener.onAdsManagerLoaded(mockAdsManagerLoadedEvent);
                  }
                  return null;
                });
    when(mockAdsManagerLoadedEvent.getStreamManager()).thenReturn(mockStreamManager);
    when(mockImaFactory.createAdsRenderingSettings()).thenReturn(mockAdsRenderingSettings);
  }
}
