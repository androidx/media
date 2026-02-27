/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.exoplayer.dash;

import static androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil;
import static org.junit.Assert.assertThrows;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.Uri;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.SystemClock;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.metadata.MetadataOutput;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.preload.PreloadMediaSource;
import androidx.media3.exoplayer.text.TextOutput;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.TrackSelector;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import androidx.media3.test.utils.FakeAudioRenderer;
import androidx.media3.test.utils.FakeVideoRenderer;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit test for {@link PreloadMediaSource} with DASH stream. */
@RunWith(AndroidJUnit4.class)
public final class DashPreloadMediaSourceTest {
  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  private static final int TARGET_PRELOAD_DURATION_US = 10000;
  private static final int LARGE_TARGET_BUFFER_BYTES_FOR_PRELOAD = Integer.MAX_VALUE;
  private static final int SMALL_TARGET_BUFFER_BYTES_FOR_PRELOAD = 1024;

  private MediaSource.Factory mediaSourceFactory;
  private LoadControl loadControl;
  private BandwidthMeter bandwidthMeter;
  private RenderersFactory renderersFactory;
  private MediaItem mediaItem;
  @Mock private PreloadMediaSource.PreloadControl mockPreloadControl;

  @Before
  public void setUp() {
    mediaSourceFactory =
        new DefaultMediaSourceFactory((Context) ApplicationProvider.getApplicationContext());
    loadControl =
        mock(
            LoadControl.class,
            delegatesTo(
                new DefaultLoadControl.Builder()
                    .setPlayerTargetBufferBytes(
                        PlayerId.PRELOAD.name, LARGE_TARGET_BUFFER_BYTES_FOR_PRELOAD)
                    .build()));
    bandwidthMeter =
        new DefaultBandwidthMeter.Builder(ApplicationProvider.getApplicationContext()).build();
    renderersFactory =
        (handler, videoListener, audioListener, textOutput, metadataOutput) ->
            new Renderer[] {
              new FakeVideoRenderer(
                  SystemClock.DEFAULT.createHandler(handler.getLooper(), /* callback= */ null),
                  videoListener),
              new FakeAudioRenderer(
                  SystemClock.DEFAULT.createHandler(handler.getLooper(), /* callback= */ null),
                  audioListener)
            };
    mediaItem =
        new MediaItem.Builder()
            .setUri(Uri.parse("asset://android_asset/media/dash/multi-track/sample.mpd"))
            .build();
    when(mockPreloadControl.onSourcePrepared(any())).thenReturn(true);
    when(mockPreloadControl.onTracksSelected(any())).thenReturn(true);
    when(mockPreloadControl.onContinueLoadingRequested(any(), anyLong())).thenReturn(true);
  }

  @Test
  public void preload_loadPeriodToTargetPreloadPosition() throws Exception {
    AtomicBoolean preloadTerminated = new AtomicBoolean();
    when(mockPreloadControl.onContinueLoadingRequested(any(), anyLong()))
        .thenAnswer(
            invocation -> {
              long bufferedDurationUs = invocation.getArgument(1);
              if (bufferedDurationUs >= TARGET_PRELOAD_DURATION_US) {
                preloadTerminated.set(true);
                return false;
              }
              return true;
            });
    TrackSelector trackSelector =
        new DefaultTrackSelector(ApplicationProvider.getApplicationContext());
    trackSelector.init(() -> {}, bandwidthMeter);
    PreloadMediaSource.Factory preloadMediaSourceFactory =
        new PreloadMediaSource.Factory(
            mediaSourceFactory,
            mockPreloadControl,
            trackSelector,
            bandwidthMeter,
            getRendererCapabilities(renderersFactory),
            loadControl,
            Util.getCurrentOrMainLooper());
    PreloadMediaSource preloadMediaSource = preloadMediaSourceFactory.createMediaSource(mediaItem);

    preloadMediaSource.preload(/* startPositionUs= */ 0L);
    runMainLooperUntil(preloadTerminated::get);

    verify(loadControl).onPrepared(PlayerId.PRELOAD);
    verify(mockPreloadControl).onSourcePrepared(eq(preloadMediaSource));
    verify(mockPreloadControl).onTracksSelected(eq(preloadMediaSource));
    verify(mockPreloadControl, atLeastOnce())
        .onContinueLoadingRequested(eq(preloadMediaSource), anyLong());
    verify(mockPreloadControl, never()).onUsedByPlayer(eq(preloadMediaSource));
    verify(mockPreloadControl, never()).onLoadingUnableToContinue(eq(preloadMediaSource));
    verify(mockPreloadControl, never()).onPreloadError(any(), eq(preloadMediaSource));
  }

  @Test
  public void preload_stopWhenTracksSelectedByPreloadControl() throws Exception {
    AtomicBoolean preloadTerminated = new AtomicBoolean();
    when(mockPreloadControl.onTracksSelected(any()))
        .thenAnswer(
            invocation -> {
              preloadTerminated.set(true);
              return false;
            });
    TrackSelector trackSelector =
        new DefaultTrackSelector(ApplicationProvider.getApplicationContext());
    trackSelector.init(() -> {}, bandwidthMeter);
    PreloadMediaSource.Factory preloadMediaSourceFactory =
        new PreloadMediaSource.Factory(
            mediaSourceFactory,
            mockPreloadControl,
            trackSelector,
            bandwidthMeter,
            getRendererCapabilities(renderersFactory),
            loadControl,
            Util.getCurrentOrMainLooper());
    PreloadMediaSource preloadMediaSource = preloadMediaSourceFactory.createMediaSource(mediaItem);

    preloadMediaSource.preload(/* startPositionUs= */ 0L);
    runMainLooperUntil(preloadTerminated::get);

    verify(loadControl).onPrepared(PlayerId.PRELOAD);
    verify(mockPreloadControl).onSourcePrepared(eq(preloadMediaSource));
    verify(mockPreloadControl).onTracksSelected(eq(preloadMediaSource));
    verify(mockPreloadControl, never())
        .onContinueLoadingRequested(eq(preloadMediaSource), anyLong());
    verify(mockPreloadControl, never()).onUsedByPlayer(eq(preloadMediaSource));
    verify(mockPreloadControl, never()).onLoadingUnableToContinue(eq(preloadMediaSource));
    verify(mockPreloadControl, never()).onPreloadError(any(), eq(preloadMediaSource));
  }

  @Test
  public void preload_stopWhenSourcePreparedByPreloadControl() throws Exception {
    AtomicBoolean preloadTerminated = new AtomicBoolean();
    when(mockPreloadControl.onSourcePrepared(any()))
        .thenAnswer(
            invocation -> {
              preloadTerminated.set(true);
              return false;
            });
    TrackSelector trackSelector =
        new DefaultTrackSelector(ApplicationProvider.getApplicationContext());
    trackSelector.init(() -> {}, bandwidthMeter);
    PreloadMediaSource.Factory preloadMediaSourceFactory =
        new PreloadMediaSource.Factory(
            mediaSourceFactory,
            mockPreloadControl,
            trackSelector,
            bandwidthMeter,
            getRendererCapabilities(renderersFactory),
            loadControl,
            Util.getCurrentOrMainLooper());
    PreloadMediaSource preloadMediaSource = preloadMediaSourceFactory.createMediaSource(mediaItem);

    preloadMediaSource.preload(/* startPositionUs= */ 0L);
    runMainLooperUntil(preloadTerminated::get);

    verify(loadControl).onPrepared(PlayerId.PRELOAD);
    verify(mockPreloadControl).onSourcePrepared(eq(preloadMediaSource));
    verify(mockPreloadControl, never()).onTracksSelected(eq(preloadMediaSource));
    verify(mockPreloadControl, never())
        .onContinueLoadingRequested(eq(preloadMediaSource), anyLong());
    verify(mockPreloadControl, never()).onUsedByPlayer(eq(preloadMediaSource));
    verify(mockPreloadControl, never()).onLoadingUnableToContinue(eq(preloadMediaSource));
    verify(mockPreloadControl, never()).onPreloadError(any(), eq(preloadMediaSource));
  }

  @Test
  public void preload_loadToTheEndOfSource() throws Exception {
    AtomicBoolean preloadTerminated = new AtomicBoolean();
    doAnswer(
            invocation -> {
              preloadTerminated.set(true);
              return null;
            })
        .when(mockPreloadControl)
        .onLoadedToTheEndOfSource(any());
    TrackSelector trackSelector =
        new DefaultTrackSelector(ApplicationProvider.getApplicationContext());
    trackSelector.init(() -> {}, bandwidthMeter);
    PreloadMediaSource.Factory preloadMediaSourceFactory =
        new PreloadMediaSource.Factory(
            mediaSourceFactory,
            mockPreloadControl,
            trackSelector,
            bandwidthMeter,
            getRendererCapabilities(renderersFactory),
            loadControl,
            Util.getCurrentOrMainLooper());
    PreloadMediaSource preloadMediaSource = preloadMediaSourceFactory.createMediaSource(mediaItem);

    preloadMediaSource.preload(/* startPositionUs= */ 0L);
    runMainLooperUntil(preloadTerminated::get);

    verify(loadControl).onPrepared(PlayerId.PRELOAD);
    verify(mockPreloadControl).onSourcePrepared(eq(preloadMediaSource));
    verify(mockPreloadControl).onTracksSelected(eq(preloadMediaSource));
    // We expect onContinueLoadingRequested() to be called at least once before reaching the end of
    // the source.
    verify(mockPreloadControl, atLeastOnce())
        .onContinueLoadingRequested(eq(preloadMediaSource), anyLong());
    verify(mockPreloadControl).onLoadedToTheEndOfSource(eq(preloadMediaSource));
    verify(mockPreloadControl, never()).onUsedByPlayer(eq(preloadMediaSource));
    verify(mockPreloadControl, never()).onLoadingUnableToContinue(eq(preloadMediaSource));
    verify(mockPreloadControl, never()).onPreloadError(any(), eq(preloadMediaSource));
  }

  @Test
  public void preload_withSmallTargetBufferBytesThreshold_onLoadingUnableToContinueCalled() {
    AtomicBoolean preloadTerminated = new AtomicBoolean();
    doAnswer(
            invocation -> {
              preloadTerminated.set(true);
              return null;
            })
        .when(mockPreloadControl)
        .onLoadedToTheEndOfSource(any());
    TrackSelector trackSelector =
        new DefaultTrackSelector(ApplicationProvider.getApplicationContext());
    trackSelector.init(() -> {}, bandwidthMeter);
    LoadControl loadControl =
        mock(
            LoadControl.class,
            delegatesTo(
                new DefaultLoadControl.Builder()
                    .setPlayerTargetBufferBytes(
                        PlayerId.PRELOAD.name, SMALL_TARGET_BUFFER_BYTES_FOR_PRELOAD)
                    .build()));
    PreloadMediaSource.Factory preloadMediaSourceFactory =
        new PreloadMediaSource.Factory(
            mediaSourceFactory,
            mockPreloadControl,
            trackSelector,
            bandwidthMeter,
            getRendererCapabilities(renderersFactory),
            loadControl,
            Util.getCurrentOrMainLooper());
    PreloadMediaSource preloadMediaSource = preloadMediaSourceFactory.createMediaSource(mediaItem);

    preloadMediaSource.preload(/* startPositionUs= */ 0L);

    assertThrows(TimeoutException.class, () -> runMainLooperUntil(preloadTerminated::get));
    verify(loadControl).onPrepared(PlayerId.PRELOAD);
    verify(mockPreloadControl).onSourcePrepared(eq(preloadMediaSource));
    verify(mockPreloadControl).onTracksSelected(eq(preloadMediaSource));
    verify(mockPreloadControl, atLeastOnce())
        .onContinueLoadingRequested(eq(preloadMediaSource), anyLong());
    verify(mockPreloadControl, never()).onUsedByPlayer(eq(preloadMediaSource));
    verify(mockPreloadControl, atLeastOnce()).onLoadingUnableToContinue(eq(preloadMediaSource));
    verify(mockPreloadControl, never()).onPreloadError(any(), eq(preloadMediaSource));
  }

  private static RendererCapabilities[] getRendererCapabilities(RenderersFactory renderersFactory) {
    Renderer[] renderers =
        renderersFactory.createRenderers(
            Util.createHandlerForCurrentLooper(),
            mock(VideoRendererEventListener.class),
            mock(AudioRendererEventListener.class),
            mock(TextOutput.class),
            mock(MetadataOutput.class));
    RendererCapabilities[] rendererCapabilities = new RendererCapabilities[renderers.length];
    for (int i = 0; i < renderers.length; i++) {
      rendererCapabilities[i] = renderers[i].getCapabilities();
    }
    return rendererCapabilities;
  }
}
