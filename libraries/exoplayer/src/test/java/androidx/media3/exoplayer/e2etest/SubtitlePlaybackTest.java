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
package androidx.media3.exoplayer.e2etest;

import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.run;
import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.view.Surface;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;
import androidx.media3.test.utils.CapturingRenderersFactory;
import androidx.media3.test.utils.DumpFileAsserts;
import androidx.media3.test.utils.FakeClock;
import androidx.media3.test.utils.ThrowingSubtitleParserFactory;
import androidx.media3.test.utils.robolectric.PlaybackOutput;
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** End-to-end tests of subtitle playback behaviour. */
@RunWith(AndroidJUnit4.class)
public class SubtitlePlaybackTest {

  @Rule
  public ShadowMediaCodecConfig mediaCodecConfig =
      ShadowMediaCodecConfig.forAllSupportedMimeTypes();

  @Test
  public void sideloadedSubtitleLoadingError_playbackContinues_errorReportedToAnalyticsListener()
      throws Exception {
    Context applicationContext = ApplicationProvider.getApplicationContext();
    CapturingRenderersFactory capturingRenderersFactory =
        new CapturingRenderersFactory(applicationContext);
    AtomicReference<LoadEventInfo> loadErrorEventInfo = new AtomicReference<>();
    AnalyticsListener analyticsListener =
        new AnalyticsListener() {
          @Override
          public void onLoadError(
              EventTime eventTime,
              LoadEventInfo loadEventInfo,
              MediaLoadData mediaLoadData,
              IOException error,
              boolean wasCanceled) {
            loadErrorEventInfo.set(loadEventInfo);
          }
        };
    ExoPlayer player =
        new ExoPlayer.Builder(applicationContext, capturingRenderersFactory)
            .setClock(new FakeClock(/* isAutoAdvancing= */ true))
            .build();
    player.addAnalyticsListener(analyticsListener);
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player.setVideoSurface(surface);
    PlaybackOutput playbackOutput = PlaybackOutput.register(player, capturingRenderersFactory);
    Uri notFoundSubtitleUri = Uri.parse("asset:///file/not/found");
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri("asset:///media/mp4/sample.mp4")
            .setSubtitleConfigurations(
                ImmutableList.of(
                    new MediaItem.SubtitleConfiguration.Builder(notFoundSubtitleUri)
                        .setMimeType(MimeTypes.TEXT_VTT)
                        .setLanguage("en")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()))
            .build();

    player.setMediaItem(mediaItem);
    player.prepare();
    run(player).ignoringNonFatalErrors().untilState(Player.STATE_READY);
    run(player).untilLoadingIs(false);
    player.play();
    run(player).untilState(Player.STATE_ENDED);
    player.release();
    surface.release();

    assertThat(loadErrorEventInfo.get().uri).isEqualTo(notFoundSubtitleUri);
    // Assert the output is the same as playing the video without sideloaded subtitles.
    DumpFileAsserts.assertOutput(
        applicationContext, playbackOutput, "playbackdumps/mp4/sample.mp4.dump");
  }

  @Test
  public void sideloadedSubtitleParsingError_playbackContinues_errorReportedToAnalyticsListener()
      throws Exception {
    Context applicationContext = ApplicationProvider.getApplicationContext();
    CapturingRenderersFactory capturingRenderersFactory =
        new CapturingRenderersFactory(applicationContext);
    AtomicReference<LoadEventInfo> loadErrorEventInfo = new AtomicReference<>();
    AtomicReference<IOException> loadError = new AtomicReference<>();
    AnalyticsListener analyticsListener =
        new AnalyticsListener() {
          @Override
          public void onLoadError(
              EventTime eventTime,
              LoadEventInfo loadEventInfo,
              MediaLoadData mediaLoadData,
              IOException error,
              boolean wasCanceled) {
            loadErrorEventInfo.set(loadEventInfo);
            loadError.set(error);
          }
        };
    ExoPlayer player =
        new ExoPlayer.Builder(applicationContext, capturingRenderersFactory)
            .setClock(new FakeClock(/* isAutoAdvancing= */ true))
            .setMediaSourceFactory(
                new DefaultMediaSourceFactory(applicationContext)
                    .setSubtitleParserFactory(
                        new ThrowingSubtitleParserFactory(
                            () -> new IllegalStateException("test subtitle parsing error"))))
            .build();
    player.addAnalyticsListener(analyticsListener);
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player.setVideoSurface(surface);
    PlaybackOutput playbackOutput = PlaybackOutput.register(player, capturingRenderersFactory);
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri("asset:///media/mp4/sample.mp4")
            .setSubtitleConfigurations(
                ImmutableList.of(
                    new MediaItem.SubtitleConfiguration.Builder(
                            Uri.parse("asset:///media/webvtt/typical"))
                        .setMimeType(MimeTypes.TEXT_VTT)
                        .setLanguage("en")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()))
            .build();

    player.setMediaItem(mediaItem);
    player.prepare();
    run(player).ignoringNonFatalErrors().untilState(Player.STATE_READY);
    run(player).untilLoadingIs(false);
    player.play();
    run(player).untilState(Player.STATE_ENDED);
    player.release();
    surface.release();

    assertThat(loadError.get()).isInstanceOf(ParserException.class);
    assertThat(loadError.get())
        .hasCauseThat()
        .hasMessageThat()
        .contains("test subtitle parsing error");
    DumpFileAsserts.assertOutput(
        applicationContext,
        playbackOutput,
        "playbackdumps/subtitles/sideloaded-parse-error.mp4.dump");
  }
}
