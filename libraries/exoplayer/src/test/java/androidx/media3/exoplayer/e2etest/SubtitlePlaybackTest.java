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

import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.view.Surface;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.Player;
import androidx.media3.common.text.CueGroup;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.mp4.FragmentedMp4Extractor;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.test.utils.DumpFileAsserts;
import androidx.media3.test.utils.FakeClock;
import androidx.media3.test.utils.ThrowingSubtitleParserFactory;
import androidx.media3.test.utils.robolectric.CapturingRenderersFactory;
import androidx.media3.test.utils.robolectric.PlaybackOutput;
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** End-to-end tests of subtitle playback behaviour. */
@RunWith(AndroidJUnit4.class)
public class SubtitlePlaybackTest {

  @Rule
  public ShadowMediaCodecConfig mediaCodecConfig =
      ShadowMediaCodecConfig.withAllDefaultSupportedCodecs();

  @Test
  public void sideloadedSubtitle_withPositiveTimeOffset_cuesShiftedLater() throws Exception {
    Context applicationContext = ApplicationProvider.getApplicationContext();
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ true);
    CapturingRenderersFactory capturingRenderersFactory =
        new CapturingRenderersFactory(applicationContext, clock);
    ExoPlayer player =
        new ExoPlayer.Builder(applicationContext, capturingRenderersFactory)
            .setClock(clock)
            .build();
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player.setVideoSurface(surface);
    List<Long> cueChangeTimesUs = new ArrayList<>();
    List<String> cueTexts = new ArrayList<>();
    player.addListener(createNonEmptyCueGroupCollectingListener(cueChangeTimesUs, cueTexts));
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri("asset:///media/mp4/preroll-5s.mp4")
            .setSubtitleConfigurations(
                ImmutableList.of(
                    new MediaItem.SubtitleConfiguration.Builder(
                            Uri.parse("asset:///media/webvtt/typical"))
                        .setMimeType(MimeTypes.TEXT_VTT)
                        .setLanguage("en")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .setTimeOffsetUs(300_000)
                        .build()))
            .build();

    player.setMediaItem(mediaItem);
    player.prepare();
    advance(player).untilState(Player.STATE_READY);
    advance(player).untilFullyBuffered();
    player.play();
    advance(player).untilState(Player.STATE_ENDED);
    player.release();
    surface.release();

    assertThat(cueChangeTimesUs).containsExactly(300_000L, 2_645_000L).inOrder();
    assertThat(cueTexts)
        .containsExactly("This is the first subtitle.", "This is the second subtitle.")
        .inOrder();
  }

  @Test
  public void sideloadedSubtitle_withNegativeTimeOffset_cuesShiftedEarlier() throws Exception {
    Context applicationContext = ApplicationProvider.getApplicationContext();
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ true);
    CapturingRenderersFactory capturingRenderersFactory =
        new CapturingRenderersFactory(applicationContext, clock);
    ExoPlayer player =
        new ExoPlayer.Builder(applicationContext, capturingRenderersFactory)
            .setClock(clock)
            .build();
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player.setVideoSurface(surface);
    List<Long> cueChangeTimesUs = new ArrayList<>();
    List<String> cueTexts = new ArrayList<>();
    player.addListener(createNonEmptyCueGroupCollectingListener(cueChangeTimesUs, cueTexts));
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri("asset:///media/mp4/preroll-5s.mp4")
            .setSubtitleConfigurations(
                ImmutableList.of(
                    new MediaItem.SubtitleConfiguration.Builder(
                            Uri.parse("asset:///media/webvtt/typical"))
                        .setMimeType(MimeTypes.TEXT_VTT)
                        .setLanguage("en")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .setTimeOffsetUs(-2_000_000)
                        .build()))
            .build();

    player.setMediaItem(mediaItem);
    player.prepare();
    advance(player).untilState(Player.STATE_READY);
    advance(player).untilFullyBuffered();
    player.play();
    advance(player).untilState(Player.STATE_ENDED);
    player.release();
    surface.release();

    // The first cue ends before the start of the media, so only the second one is shown.
    assertThat(cueChangeTimesUs).containsExactly(345_000L);
    assertThat(cueTexts).containsExactly("This is the second subtitle.");
  }

  @Test
  public void
      sideloadedSubtitle_timeOffsetUpdatedDuringPlaybackWithTextTrackReenabled_playbackContinuesWithShiftedCues()
          throws Exception {
    Context applicationContext = ApplicationProvider.getApplicationContext();
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ true);
    CapturingRenderersFactory capturingRenderersFactory =
        new CapturingRenderersFactory(applicationContext, clock);
    ExoPlayer player =
        new ExoPlayer.Builder(applicationContext, capturingRenderersFactory)
            .setClock(clock)
            .build();
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player.setVideoSurface(surface);
    List<Long> cueChangeTimesUs = new ArrayList<>();
    List<String> cueTexts = new ArrayList<>();
    player.addListener(createNonEmptyCueGroupCollectingListener(cueChangeTimesUs, cueTexts));
    MediaItem.SubtitleConfiguration subtitleConfiguration =
        new MediaItem.SubtitleConfiguration.Builder(Uri.parse("asset:///media/webvtt/typical"))
            .setMimeType(MimeTypes.TEXT_VTT)
            .setLanguage("en")
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build();
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri("asset:///media/mp4/preroll-5s.mp4")
            .setSubtitleConfigurations(ImmutableList.of(subtitleConfiguration))
            .build();

    player.setMediaItem(mediaItem);
    player.prepare();
    advance(player).untilState(Player.STATE_READY);
    advance(player).untilFullyBuffered();
    advance(player).untilPositionAtLeast(/* positionMs= */ 2000);
    List<Integer> playbackStatesAfterUpdate = new ArrayList<>();
    player.addListener(
        new Player.Listener() {
          @Override
          public void onPlaybackStateChanged(@Player.State int playbackState) {
            playbackStatesAfterUpdate.add(playbackState);
          }
        });
    // Shift the subtitles two seconds later and re-enable the text track to apply the new offset
    // to the cues around the current position.
    player.replaceMediaItem(
        /* index= */ 0,
        mediaItem
            .buildUpon()
            .setSubtitleConfigurations(
                ImmutableList.of(
                    subtitleConfiguration.buildUpon().setTimeOffsetUs(2_000_000).build()))
            .build());
    player.setTrackSelectionParameters(
        player
            .getTrackSelectionParameters()
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, /* disabled= */ true)
            .build());
    player.setTrackSelectionParameters(
        player
            .getTrackSelectionParameters()
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, /* disabled= */ false)
            .build());
    player.play();
    advance(player).untilState(Player.STATE_ENDED);
    long updatedTimeOffsetUs =
        checkNotNull(player.getCurrentMediaItem().localConfiguration)
            .subtitleConfigurations
            .get(0)
            .timeOffsetUs;
    player.release();
    surface.release();

    assertThat(updatedTimeOffsetUs).isEqualTo(2_000_000);
    // The first cue is shown with the initial zero offset, then again when the text track is
    // re-enabled at two seconds with the new offset, followed by the shifted second cue.
    assertThat(cueChangeTimesUs).containsExactly(0L, 2_000_000L, 4_345_000L).inOrder();
    assertThat(cueTexts)
        .containsExactly(
            "This is the first subtitle.",
            "This is the first subtitle.",
            "This is the second subtitle.")
        .inOrder();
    // The media item update must not interrupt playback with a re-preparation.
    assertThat(playbackStatesAfterUpdate).containsExactly(Player.STATE_ENDED);
  }

  @Test
  public void
      sideloadedSubtitle_timeOffsetUpdatedDuringPlaybackWithoutTextTrackReenabled_appliesToFutureCuesOnly()
          throws Exception {
    Context applicationContext = ApplicationProvider.getApplicationContext();
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ true);
    CapturingRenderersFactory capturingRenderersFactory =
        new CapturingRenderersFactory(applicationContext, clock);
    ExoPlayer player =
        new ExoPlayer.Builder(applicationContext, capturingRenderersFactory)
            .setClock(clock)
            .build();
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player.setVideoSurface(surface);
    List<Long> cueChangeTimesUs = new ArrayList<>();
    List<String> cueTexts = new ArrayList<>();
    player.addListener(createNonEmptyCueGroupCollectingListener(cueChangeTimesUs, cueTexts));
    MediaItem.SubtitleConfiguration subtitleConfiguration =
        new MediaItem.SubtitleConfiguration.Builder(Uri.parse("asset:///media/webvtt/typical"))
            .setMimeType(MimeTypes.TEXT_VTT)
            .setLanguage("en")
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build();
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri("asset:///media/mp4/preroll-5s.mp4")
            .setSubtitleConfigurations(ImmutableList.of(subtitleConfiguration))
            .build();

    player.setMediaItem(mediaItem);
    player.prepare();
    advance(player).untilState(Player.STATE_READY);
    advance(player).untilFullyBuffered();
    advance(player).untilPositionAtLeast(/* positionMs= */ 1000);
    List<Integer> playbackStatesAfterUpdate = new ArrayList<>();
    player.addListener(
        new Player.Listener() {
          @Override
          public void onPlaybackStateChanged(@Player.State int playbackState) {
            playbackStatesAfterUpdate.add(playbackState);
          }
        });
    // Shift the subtitles one second later. Without re-enabling the text track, the new offset
    // only applies to future cues that haven't been read by the renderer yet.
    player.replaceMediaItem(
        /* index= */ 0,
        mediaItem
            .buildUpon()
            .setSubtitleConfigurations(
                ImmutableList.of(
                    subtitleConfiguration.buildUpon().setTimeOffsetUs(1_000_000).build()))
            .build());
    player.play();
    advance(player).untilState(Player.STATE_ENDED);
    long updatedTimeOffsetUs =
        checkNotNull(player.getCurrentMediaItem().localConfiguration)
            .subtitleConfigurations
            .get(0)
            .timeOffsetUs;
    player.release();
    surface.release();

    assertThat(updatedTimeOffsetUs).isEqualTo(1_000_000);
    // The first cue is shown with the initial zero offset, and the second cue is shown with the
    // new shifted offset.
    assertThat(cueChangeTimesUs).containsExactly(0L, 3_345_000L).inOrder();
    assertThat(cueTexts)
        .containsExactly("This is the first subtitle.", "This is the second subtitle.")
        .inOrder();
    // The media item update must not interrupt playback with a re-preparation.
    assertThat(playbackStatesAfterUpdate).containsExactly(Player.STATE_ENDED);
  }

  private static Player.Listener createNonEmptyCueGroupCollectingListener(
      List<Long> cueChangeTimesUs, List<String> cueTexts) {
    return new Player.Listener() {
      @Override
      public void onCues(CueGroup cueGroup) {
        if (!cueGroup.cues.isEmpty()) {
          cueChangeTimesUs.add(cueGroup.presentationTimeUs);
          cueTexts.add(String.valueOf(cueGroup.cues.get(0).text));
        }
      }
    };
  }

  // https://github.com/androidx/media/issues/1721
  @Test
  public void multipleSideloadedSubtitles_noneSelected_noneLoaded() throws Exception {
    Context applicationContext = ApplicationProvider.getApplicationContext();
    List<Uri> loadStartedUris = new ArrayList<>();
    AnalyticsListener analyticsListener =
        new AnalyticsListener() {
          @Override
          public void onLoadStarted(
              EventTime eventTime,
              LoadEventInfo loadEventInfo,
              MediaLoadData mediaLoadData,
              int retryCount) {
            loadStartedUris.add(loadEventInfo.uri);
            loadStartedUris.add(loadEventInfo.dataSpec.uri);
          }
        };
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ true);
    CapturingRenderersFactory capturingRenderersFactory =
        new CapturingRenderersFactory(applicationContext, clock);
    ExoPlayer player =
        new ExoPlayer.Builder(applicationContext, capturingRenderersFactory)
            .setClock(clock)
            .build();
    player.addAnalyticsListener(analyticsListener);
    Uri typicalVttUri = Uri.parse("asset:///media/webvtt/typical");
    Uri simpleTtmlUri = Uri.parse("asset:///media/ttml/simple.xml");
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri("asset:///media/mp4/sample.mp4")
            .setSubtitleConfigurations(
                ImmutableList.of(
                    new MediaItem.SubtitleConfiguration.Builder(typicalVttUri)
                        .setMimeType(MimeTypes.TEXT_VTT)
                        .setLanguage("en")
                        .build(),
                    new MediaItem.SubtitleConfiguration.Builder(simpleTtmlUri)
                        .setMimeType(MimeTypes.APPLICATION_TTML)
                        .setLanguage("en")
                        .build()))
            .build();

    player.setMediaItem(mediaItem);
    player.prepare();
    advance(player).untilState(Player.STATE_READY);
    advance(player).untilLoadingIs(false);
    player.play();
    advance(player).untilState(Player.STATE_ENDED);
    player.release();

    assertThat(loadStartedUris).containsNoneOf(typicalVttUri, simpleTtmlUri);
  }

  @Test
  public void cea608() throws Exception {
    Context applicationContext = ApplicationProvider.getApplicationContext();
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ true);
    CapturingRenderersFactory capturingRenderersFactory =
        new CapturingRenderersFactory(applicationContext, clock);
    ExtractorsFactory fragmentedMp4ExtractorFactory =
        new FragmentedMp4CaptionsExtractorsFactory(
            new Format.Builder()
                .setSampleMimeType(MimeTypes.APPLICATION_CEA608)
                .setLanguage("en")
                .build());
    ExoPlayer player =
        new ExoPlayer.Builder(applicationContext, capturingRenderersFactory)
            .setMediaSourceFactory(
                new DefaultMediaSourceFactory(applicationContext, fragmentedMp4ExtractorFactory))
            .setClock(clock)
            .build();
    player.setTrackSelectionParameters(
        player.getTrackSelectionParameters().buildUpon().setPreferredTextLanguage("en").build());
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player.setVideoSurface(surface);
    PlaybackOutput playbackOutput = PlaybackOutput.register(player, capturingRenderersFactory);

    player.setMediaItem(MediaItem.fromUri("asset:///media/mp4/fragmented_captions.mp4"));
    player.prepare();
    advance(player).untilState(Player.STATE_READY);
    advance(player).untilFullyBuffered();
    player.play();
    advance(player).untilState(Player.STATE_ENDED);
    player.release();
    surface.release();

    DumpFileAsserts.assertOutput(
        applicationContext, playbackOutput, "playbackdumps/subtitles/fragmented_captions.mp4.dump");
  }

  // b/388765515
  @Test
  public void clippedCea608() throws Exception {
    Context applicationContext = ApplicationProvider.getApplicationContext();
    ExtractorsFactory fragmentedMp4ExtractorFactory =
        new FragmentedMp4CaptionsExtractorsFactory(
            new Format.Builder()
                .setSampleMimeType(MimeTypes.APPLICATION_CEA608)
                .setLanguage("en")
                .build());
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ true);
    CapturingRenderersFactory capturingRenderersFactory =
        new CapturingRenderersFactory(applicationContext, clock);
    ExoPlayer player =
        new ExoPlayer.Builder(applicationContext, capturingRenderersFactory)
            .setMediaSourceFactory(
                new DefaultMediaSourceFactory(applicationContext, fragmentedMp4ExtractorFactory))
            .setClock(clock)
            .build();
    player.setTrackSelectionParameters(
        player.getTrackSelectionParameters().buildUpon().setPreferredTextLanguage("en").build());
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player.setVideoSurface(surface);
    MediaItem mediaItemFull = MediaItem.fromUri("asset:///media/mp4/fragmented_captions.mp4");
    MediaItem mediaItemClipped =
        mediaItemFull
            .buildUpon()
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder().setEndPositionMs(1830).build())
            .build();

    player.setMediaItems(ImmutableList.of(mediaItemClipped, mediaItemFull));
    player.prepare();
    advance(player).untilState(Player.STATE_READY);
    advance(player).untilFullyBuffered();
    player.play();
    advance(player).untilState(Player.STATE_ENDED);
    player.release();
    surface.release();

    // No output assertion, the test just checks that playback completes.
  }

  @Test
  public void sideloadedSubtitleLoadingError_playbackContinues_errorReportedToAnalyticsListener()
      throws Exception {
    Context applicationContext = ApplicationProvider.getApplicationContext();
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ true);
    CapturingRenderersFactory capturingRenderersFactory =
        new CapturingRenderersFactory(applicationContext, clock);
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
            .setClock(clock)
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
    advance(player).ignoringNonFatalErrors().untilState(Player.STATE_READY);
    advance(player).ignoringNonFatalErrors().untilFullyBuffered();
    player.play();
    advance(player).ignoringNonFatalErrors().untilState(Player.STATE_ENDED);
    player.release();
    surface.release();

    assertThat(loadErrorEventInfo.get().uri).isEqualTo(notFoundSubtitleUri);
    DumpFileAsserts.assertOutput(
        applicationContext, playbackOutput, "playbackdumps/subtitles/sideloaded-error.mp4.dump");
  }

  @Test
  public void sideloadedSubtitleParsingError_playbackContinues_errorReportedToAnalyticsListener()
      throws Exception {
    Context applicationContext = ApplicationProvider.getApplicationContext();
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ true);
    CapturingRenderersFactory capturingRenderersFactory =
        new CapturingRenderersFactory(applicationContext, clock);
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
            .setClock(clock)
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
    advance(player).ignoringNonFatalErrors().untilState(Player.STATE_READY);
    advance(player).ignoringNonFatalErrors().untilFullyBuffered();
    player.play();
    advance(player).ignoringNonFatalErrors().untilState(Player.STATE_ENDED);
    player.release();
    surface.release();

    assertThat(loadError.get()).isInstanceOf(ParserException.class);
    assertThat(loadError.get())
        .hasCauseThat()
        .hasMessageThat()
        .contains("test subtitle parsing error");
    DumpFileAsserts.assertOutput(
        applicationContext, playbackOutput, "playbackdumps/subtitles/sideloaded-error.mp4.dump");
  }

  // TODO: b/391362063 - Assert that this error gets propagated out after that is implemented.
  @Test
  public void muxedSubtitleParsingError_playbackContinues() throws Exception {
    Context applicationContext = ApplicationProvider.getApplicationContext();
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ true);
    CapturingRenderersFactory capturingRenderersFactory =
        new CapturingRenderersFactory(applicationContext, clock);
    ExoPlayer player =
        new ExoPlayer.Builder(applicationContext, capturingRenderersFactory)
            .setClock(clock)
            .setMediaSourceFactory(
                new DefaultMediaSourceFactory(applicationContext)
                    .setSubtitleParserFactory(
                        new ThrowingSubtitleParserFactory(
                            () -> new IllegalStateException("test subtitle parsing error"))))
            .build();
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player.setVideoSurface(surface);
    PlaybackOutput playbackOutput = PlaybackOutput.register(player, capturingRenderersFactory);
    MediaItem mediaItem =
        new MediaItem.Builder().setUri("asset:///media/mkv/sample_with_srt.mkv").build();

    player.setMediaItem(mediaItem);
    player.prepare();
    advance(player).untilState(Player.STATE_READY);
    advance(player).untilFullyBuffered();
    player.play();
    advance(player).untilState(Player.STATE_ENDED);
    player.release();
    surface.release();

    DumpFileAsserts.assertOutput(
        applicationContext, playbackOutput, "playbackdumps/subtitles/muxed-parsing-error.mkv.dump");
  }

  /**
   * An {@link ExtractorsFactory} which creates a {@link FragmentedMp4Extractor} configured to
   * extract a single additional caption track.
   */
  private static class FragmentedMp4CaptionsExtractorsFactory implements ExtractorsFactory {

    private final Format closedCaptionFormat;

    private FragmentedMp4CaptionsExtractorsFactory(Format closedCaptionFormat) {
      this.closedCaptionFormat = closedCaptionFormat;
    }

    @Override
    public Extractor[] createExtractors() {
      return new Extractor[] {
        new FragmentedMp4Extractor(
            new DefaultSubtitleParserFactory(),
            /* flags= */ 0,
            /* timestampAdjuster= */ null,
            /* sideloadedTrack= */ null,
            /* closedCaptionFormats= */ ImmutableList.of(closedCaptionFormat),
            /* additionalEmsgTrackOutput= */ null)
      };
    }
  }
}
