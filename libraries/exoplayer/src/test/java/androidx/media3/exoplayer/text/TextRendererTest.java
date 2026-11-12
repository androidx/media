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
package androidx.media3.exoplayer.text;

import static androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem.END_OF_STREAM_ITEM;
import static androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem.sample;
import static androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil;
import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance;
import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import androidx.media3.common.C;
import androidx.media3.common.Flags;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.NoSampleRenderer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RendererConfiguration;
import androidx.media3.exoplayer.drm.DrmSessionEventListener;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import androidx.media3.extractor.text.CueEncoder;
import androidx.media3.test.utils.FakeMediaSource;
import androidx.media3.test.utils.FakeSampleStream;
import androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem;
import androidx.media3.test.utils.FakeTimeline;
import androidx.media3.test.utils.Media3FlagsRule;
import androidx.media3.test.utils.TestExoPlayerBuilder;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Range;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link TextRenderer}. */
@RunWith(AndroidJUnit4.class)
public class TextRendererTest {

  @Rule public final Media3FlagsRule flagsRule = new Media3FlagsRule(this);

  private static final Format TEXT_FORMAT =
      new Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES).build();

  private static final Format LEGACY_TEXT_FORMAT =
      new Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).build();

  private final CueEncoder cueEncoder = new CueEncoder();
  private final List<TextRenderer> renderersToCleanUp = new ArrayList<>();

  @After
  public void tearDown() {
    for (TextRenderer renderer : renderersToCleanUp) {
      if (renderer.getState() == Renderer.STATE_STARTED) {
        renderer.stop();
      }
      if (renderer.getState() == Renderer.STATE_ENABLED) {
        renderer.disable();
      }
    }
  }

  @Test
  public void renderText_doesNotReadAheadTooFar() throws Exception {
    ImmutableList<Cue> cues = ImmutableList.of(new Cue.Builder().setText("test").build());
    byte[] encodedCues = cueEncoder.encode(cues, /* durationUs= */ 1_000_000);
    TextRenderer renderer = new TextRenderer(cueList -> {}, /* outputLooper= */ null);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            TEXT_FORMAT,
            ImmutableList.of(
                sample(/* timeUs= */ 100_000, C.BUFFER_FLAG_KEY_FRAME, encodedCues),
                sample(/* timeUs= */ 450_000, C.BUFFER_FLAG_KEY_FRAME, encodedCues),
                FakeSampleStream.FakeSampleStreamItem.format(
                    TEXT_FORMAT.buildUpon().setId("2").build()),
                sample(/* timeUs= */ 2_000_000, C.BUFFER_FLAG_KEY_FRAME, encodedCues),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {TEXT_FORMAT},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 123_000_000,
        new MediaSource.MediaPeriodId(new Object()));
    renderer.start();

    // Render a few times to render as many samples as possible.
    for (int i = 0; i < 5; i++) {
      renderer.render(/* positionUs= */ 123_500_000, /* elapsedRealtimeUs= */ 0);
    }

    // Verify that the last sample (at 2_000_000) is NOT yet read.
    assertThat(renderer.getReadingPositionUs()).isEqualTo(123_450_000);
    DecoderInputBuffer buffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    int result = fakeSampleStream.readData(new FormatHolder(), buffer, /* readFlags= */ 0);
    assertThat(result).isEqualTo(C.RESULT_BUFFER_READ);
    assertThat(buffer.timeUs).isEqualTo(2_000_000);
  }

  @Test
  public void renderLegacyText_doesNotReadAheadTooFar() throws Exception {
    byte[] webvttBytes = "WEBVTT\n\n00:00.000 --> 00:01.000\ntest\n".getBytes(UTF_8);
    List<CueGroup> outputCueGroups = new ArrayList<>();
    TextRenderer renderer = new TextRenderer(outputCueGroups::add, /* outputLooper= */ null);
    renderer.experimentalSetLegacyDecodingEnabled(true);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            LEGACY_TEXT_FORMAT,
            ImmutableList.of(
                sample(/* timeUs= */ 100_000, C.BUFFER_FLAG_KEY_FRAME, webvttBytes),
                sample(/* timeUs= */ 450_000, C.BUFFER_FLAG_KEY_FRAME, webvttBytes),
                FakeSampleStream.FakeSampleStreamItem.format(
                    LEGACY_TEXT_FORMAT.buildUpon().setId("2").build()),
                sample(/* timeUs= */ 2_000_000, C.BUFFER_FLAG_KEY_FRAME, webvttBytes),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {LEGACY_TEXT_FORMAT},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 123_000_000,
        new MediaSource.MediaPeriodId(new Object()));
    renderer.start();

    // Render until the first two samples are decoded on the background thread, freeing the
    // decoder's input buffers so the renderer can peek the following format and sample.
    runMainLooperUntil(() -> {
      renderer.render(/* positionUs= */ 123_500_000, /* elapsedRealtimeUs= */ 0);
      return outputCueGroups.size() == 3;
    });

    // Verify that the last sample (at 2_000_000) is NOT yet read.
    assertThat(renderer.getReadingPositionUs()).isEqualTo(123_450_000);
    DecoderInputBuffer buffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    int result = fakeSampleStream.readData(new FormatHolder(), buffer, /* readFlags= */ 0);
    assertThat(result).isEqualTo(C.RESULT_BUFFER_READ);
    assertThat(buffer.timeUs).isEqualTo(2_000_000);
  }

  @Test
  public void renderText_doesNotReadEndOfStreamTooEarly() throws Exception {
    ImmutableList<Cue> cues = ImmutableList.of(new Cue.Builder().setText("test").build());
    byte[] encodedCues = cueEncoder.encode(cues, /* durationUs= */ 1_000_000);
    TextRenderer renderer = new TextRenderer(cueGroup -> {}, /* outputLooper= */ null);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            TEXT_FORMAT,
            ImmutableList.of(
                sample(/* timeUs= */ 100_000, C.BUFFER_FLAG_KEY_FRAME, encodedCues),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    Timeline timeline =
        new FakeTimeline(
            new FakeTimeline.TimelineWindowDefinition.Builder()
                .setDurationUs(5_000_000)
                .setWindowPositionInFirstPeriodUs(0)
                .build());
    renderer.setTimeline(timeline);
    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {TEXT_FORMAT},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 123_000_000,
        new MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)));
    renderer.setCurrentStreamFinal();
    renderer.start();

    // Render a few times to render as many samples as possible.
    for (int i = 0; i < 5; i++) {
      renderer.render(/* positionUs= */ 123_500_000, /* elapsedRealtimeUs= */ 0);
    }

    // EOS shouldn't be read yet.
    assertThat(renderer.getReadingPositionUs()).isEqualTo(123_100_000);
    DecoderInputBuffer buffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    int result = fakeSampleStream.readData(new FormatHolder(), buffer, /* readFlags= */ 0);
    assertThat(result).isEqualTo(C.RESULT_BUFFER_READ);
    assertThat(buffer.isEndOfStream()).isTrue();
  }

  @Test
  public void renderLegacyText_doesNotReadEndOfStreamTooEarly() throws Exception {
    ImmutableList<Cue> cues = ImmutableList.of(new Cue.Builder().setText("test").build());
    byte[] encodedCues = cueEncoder.encode(cues, /* durationUs= */ 1_000_000);
    TextRenderer renderer = new TextRenderer(cueGroup -> {}, /* outputLooper= */ null);
    renderer.experimentalSetLegacyDecodingEnabled(true);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            LEGACY_TEXT_FORMAT,
            ImmutableList.of(
                sample(/* timeUs= */ 100_000, C.BUFFER_FLAG_KEY_FRAME, encodedCues),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    Timeline timeline =
        new FakeTimeline(
            new FakeTimeline.TimelineWindowDefinition.Builder()
                .setDurationUs(5_000_000)
                .setWindowPositionInFirstPeriodUs(0)
                .build());
    renderer.setTimeline(timeline);
    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {LEGACY_TEXT_FORMAT},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 123_000_000,
        new MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)));
    renderer.setCurrentStreamFinal();
    renderer.start();

    // Render a few times to render as many samples as possible.
    for (int i = 0; i < 5; i++) {
      renderer.render(/* positionUs= */ 123_500_000, /* elapsedRealtimeUs= */ 0);
    }

    // EOS shouldn't be read yet.
    assertThat(renderer.getReadingPositionUs()).isEqualTo(123_100_000);
    DecoderInputBuffer buffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    int result = fakeSampleStream.readData(new FormatHolder(), buffer, /* readFlags= */ 0);
    assertThat(result).isEqualTo(C.RESULT_BUFFER_READ);
    assertThat(buffer.isEndOfStream()).isTrue();
  }

  @Test
  public void getDurationToProgressUs_withFlagDisabled_returnsDefaultDuration() throws Exception {
    Flags.disableFlag(Flags.FLAG_TEXT_RENDERER_DURATION_TO_PROGRESS);
    byte[] encodedCues = encodeSingleCue("test", /* durationUs= */ 1_000_000);
    TextRenderer renderer = new TextRenderer(cueGroup -> {}, /* outputLooper= */ null);
    enableRenderer(
        renderer,
        TEXT_FORMAT,
        ImmutableList.of(
            sample(/* timeUs= */ 500_000, C.BUFFER_FLAG_KEY_FRAME, encodedCues),
            END_OF_STREAM_ITEM));
    renderer.start();
    renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);

    long durationToProgressUs =
        renderer.getDurationToProgressUs(/* positionUs= */ 100_000, /* elapsedRealtimeUs= */ 0);

    assertThat(durationToProgressUs).isEqualTo(Renderer.DEFAULT_DURATION_TO_PROGRESS_US);
  }

  @Test
  public void getDurationToProgressUs_afterDisable_returnsDefaultDuration() throws Exception {
    Flags.enableFlag(Flags.FLAG_TEXT_RENDERER_DURATION_TO_PROGRESS);
    byte[] encodedCues = encodeSingleCue("test", /* durationUs= */ 1_000_000);
    TextRenderer renderer = new TextRenderer(cueGroup -> {}, /* outputLooper= */ null);
    enableRenderer(
        renderer,
        TEXT_FORMAT,
        ImmutableList.of(
            sample(/* timeUs= */ 500_000, C.BUFFER_FLAG_KEY_FRAME, encodedCues),
            END_OF_STREAM_ITEM));
    renderer.start();
    renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    renderer.stop();
    renderer.disable();

    long durationToProgressUs =
        renderer.getDurationToProgressUs(/* positionUs= */ 100_000, /* elapsedRealtimeUs= */ 0);

    assertThat(durationToProgressUs).isEqualTo(Renderer.DEFAULT_DURATION_TO_PROGRESS_US);
  }

  @Test
  public void getDurationToProgressUs_afterReset_returnsDefaultDuration() throws Exception {
    Flags.enableFlag(Flags.FLAG_TEXT_RENDERER_DURATION_TO_PROGRESS);
    byte[] encodedCues = encodeSingleCue("test", /* durationUs= */ 1_000_000);
    TextRenderer renderer = new TextRenderer(cueGroup -> {}, /* outputLooper= */ null);
    enableRenderer(
        renderer,
        TEXT_FORMAT,
        ImmutableList.of(
            sample(/* timeUs= */ 500_000, C.BUFFER_FLAG_KEY_FRAME, encodedCues),
            END_OF_STREAM_ITEM));
    renderer.start();
    renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    renderer.stop();
    renderer.disable();
    renderer.reset();

    long durationToProgressUs =
        renderer.getDurationToProgressUs(/* positionUs= */ 100_000, /* elapsedRealtimeUs= */ 0);

    assertThat(durationToProgressUs).isEqualTo(Renderer.DEFAULT_DURATION_TO_PROGRESS_US);
  }

  @Test
  public void getDurationToProgressUs_whenEnabledNotStarted_returnsDefaultIdleDuration()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_TEXT_RENDERER_DURATION_TO_PROGRESS);
    byte[] encodedCues = encodeSingleCue("test", /* durationUs= */ 1_000_000);
    TextRenderer renderer = new TextRenderer(cueGroup -> {}, /* outputLooper= */ null);
    enableRenderer(
        renderer,
        TEXT_FORMAT,
        ImmutableList.of(
            sample(/* timeUs= */ 500_000, C.BUFFER_FLAG_KEY_FRAME, encodedCues),
            END_OF_STREAM_ITEM));
    renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);

    long durationToProgressUs =
        renderer.getDurationToProgressUs(/* positionUs= */ 100_000, /* elapsedRealtimeUs= */ 0);

    assertThat(durationToProgressUs).isEqualTo(Renderer.DEFAULT_IDLE_DURATION_TO_PROGRESS_US);
  }

  @Test
  public void
      getDurationToProgressUs_whenEnabledNotStartedWithEmptySource_returnsDefaultIdleDuration()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_TEXT_RENDERER_DURATION_TO_PROGRESS);
    TextRenderer renderer = new TextRenderer(cueGroup -> {}, /* outputLooper= */ null);
    enableRenderer(renderer, TEXT_FORMAT, ImmutableList.of());
    renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);

    long durationToProgressUs =
        renderer.getDurationToProgressUs(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);

    assertThat(durationToProgressUs).isEqualTo(Renderer.DEFAULT_IDLE_DURATION_TO_PROGRESS_US);
  }

  @Test
  public void
      getDurationToProgressUs_whenEnabledNotStartedBeforeFirstRender_returnsDefaultDuration()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_TEXT_RENDERER_DURATION_TO_PROGRESS);
    TextRenderer renderer = new TextRenderer(cueGroup -> {}, /* outputLooper= */ null);
    enableRenderer(renderer, TEXT_FORMAT, ImmutableList.of());

    long durationToProgressUs =
        renderer.getDurationToProgressUs(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);

    assertThat(durationToProgressUs).isEqualTo(Renderer.DEFAULT_DURATION_TO_PROGRESS_US);
  }

  @Test
  public void
      getDurationToProgressUs_whenEnabledNotStartedWithActiveCueAndUnbufferedSource_returnsDefaultIdleDuration()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_TEXT_RENDERER_DURATION_TO_PROGRESS);
    byte[] encodedCues = encodeSingleCue("test", /* durationUs= */ 1_000_000);
    TextRenderer renderer = new TextRenderer(cueGroup -> {}, /* outputLooper= */ null);
    enableRenderer(
        renderer,
        TEXT_FORMAT,
        ImmutableList.of(sample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME, encodedCues)));
    renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);

    long durationToProgressUs =
        renderer.getDurationToProgressUs(/* positionUs= */ 100_000, /* elapsedRealtimeUs= */ 0);

    assertThat(durationToProgressUs).isEqualTo(Renderer.DEFAULT_IDLE_DURATION_TO_PROGRESS_US);
  }

  @Test
  public void getDurationToProgressUs_withStopAfterRenderToEndOfStream_returnsDefaultIdleDuration()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_TEXT_RENDERER_DURATION_TO_PROGRESS);
    byte[] encodedCues = encodeSingleCue("test", /* durationUs= */ 400_000);
    TextRenderer renderer = new TextRenderer(cueGroup -> {}, /* outputLooper= */ null);
    Timeline timeline = createSinglePeriodTimeline(/* durationUs= */ 2_000_000);
    enableRenderer(
        renderer,
        TEXT_FORMAT,
        ImmutableList.of(
            sample(/* timeUs= */ 100_000, C.BUFFER_FLAG_KEY_FRAME, encodedCues),
            END_OF_STREAM_ITEM),
        /* positionUs= */ 0,
        /* offsetUs= */ 0,
        timeline);
    renderer.setCurrentStreamFinal();
    renderer.start();
    renderer.render(/* positionUs= */ 100_000, /* elapsedRealtimeUs= */ 0);
    renderer.render(/* positionUs= */ 1_100_000, /* elapsedRealtimeUs= */ 0);
    renderer.render(/* positionUs= */ 1_100_000, /* elapsedRealtimeUs= */ 0);
    renderer.stop();

    long durationToProgressUs =
        renderer.getDurationToProgressUs(/* positionUs= */ 1_100_000, /* elapsedRealtimeUs= */ 0);

    assertThat(renderer.isEnded()).isTrue();
    assertThat(durationToProgressUs).isEqualTo(Renderer.DEFAULT_IDLE_DURATION_TO_PROGRESS_US);
  }

  @Test
  public void getDurationToProgressUs_beforeFirstRender_returnsDefaultDuration() throws Exception {
    Flags.enableFlag(Flags.FLAG_TEXT_RENDERER_DURATION_TO_PROGRESS);
    byte[] encodedCues = encodeSingleCue("test", /* durationUs= */ 1_000_000);
    TextRenderer renderer = new TextRenderer(cueGroup -> {}, /* outputLooper= */ null);
    enableRenderer(
        renderer,
        TEXT_FORMAT,
        ImmutableList.of(
            sample(/* timeUs= */ 500_000, C.BUFFER_FLAG_KEY_FRAME, encodedCues),
            END_OF_STREAM_ITEM));
    renderer.start();

    long durationToProgressUs =
        renderer.getDurationToProgressUs(/* positionUs= */ 100_000, /* elapsedRealtimeUs= */ 0);

    assertThat(durationToProgressUs).isEqualTo(Renderer.DEFAULT_DURATION_TO_PROGRESS_US);
  }

  @Test
  public void getDurationToProgressUs_afterResetPosition_returnsDefaultDuration() throws Exception {
    Flags.enableFlag(Flags.FLAG_TEXT_RENDERER_DURATION_TO_PROGRESS);
    byte[] encodedCues = encodeSingleCue("test", /* durationUs= */ 1_000_000);
    TextRenderer renderer = new TextRenderer(cueGroup -> {}, /* outputLooper= */ null);
    enableRenderer(
        renderer,
        TEXT_FORMAT,
        ImmutableList.of(
            sample(/* timeUs= */ 500_000, C.BUFFER_FLAG_KEY_FRAME, encodedCues),
            END_OF_STREAM_ITEM));
    renderer.start();
    renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    renderer.resetPosition(/* positionUs= */ 0, /* sampleStreamIsResetToKeyFrame= */ true);

    long durationToProgressUs =
        renderer.getDurationToProgressUs(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);

    assertThat(durationToProgressUs).isEqualTo(Renderer.DEFAULT_DURATION_TO_PROGRESS_US);
  }

  @Test
  public void getDurationToProgressUs_afterStopAndResetPosition_returnsDefaultDuration()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_TEXT_RENDERER_DURATION_TO_PROGRESS);
    byte[] encodedCues = encodeSingleCue("test", /* durationUs= */ 1_000_000);
    TextRenderer renderer = new TextRenderer(cueGroup -> {}, /* outputLooper= */ null);
    enableRenderer(
        renderer,
        TEXT_FORMAT,
        ImmutableList.of(
            sample(/* timeUs= */ 500_000, C.BUFFER_FLAG_KEY_FRAME, encodedCues),
            END_OF_STREAM_ITEM));
    renderer.start();
    renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    renderer.stop();
    renderer.resetPosition(/* positionUs= */ 0, /* sampleStreamIsResetToKeyFrame= */ true);

    long durationToProgressUs =
        renderer.getDurationToProgressUs(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);

    assertThat(durationToProgressUs).isEqualTo(Renderer.DEFAULT_DURATION_TO_PROGRESS_US);
  }

  @Test
  public void
      getDurationToProgressUs_afterResetPositionOntoEmptySourceAfterEndOfStream_returnsDefaultDuration()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_TEXT_RENDERER_DURATION_TO_PROGRESS);
    byte[] encodedCues = encodeSingleCue("test", /* durationUs= */ 200_000);
    TextRenderer renderer = new TextRenderer(cueGroup -> {}, /* outputLooper= */ null);
    Timeline timeline = createSinglePeriodTimeline(/* durationUs= */ 5_000_000);
    FakeSampleStream fakeSampleStream =
        enableRenderer(
            renderer,
            TEXT_FORMAT,
            ImmutableList.of(
                sample(/* timeUs= */ 100_000, C.BUFFER_FLAG_KEY_FRAME, encodedCues),
                END_OF_STREAM_ITEM),
            /* positionUs= */ 0,
            /* offsetUs= */ 0,
            timeline);
    renderer.start();
    // Reads the cue, then peeks END_OF_STREAM_ITEM into cueDecoderInputBuffer.
    renderer.render(/* positionUs= */ 100_000, /* elapsedRealtimeUs= */ 0);
    renderer.render(/* positionUs= */ 100_000, /* elapsedRealtimeUs= */ 0);
    fakeSampleStream.reset();
    renderer.resetPosition(/* positionUs= */ 0, /* sampleStreamIsResetToKeyFrame= */ true);
    renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);

    long durationToProgressUs =
        renderer.getDurationToProgressUs(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);

    assertThat(durationToProgressUs).isEqualTo(Renderer.DEFAULT_DURATION_TO_PROGRESS_US);
  }

  @Test
  public void
      getDurationToProgressUs_afterReplaceStreamOntoEmptySourceAfterEndOfStream_returnsDefaultDuration()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_TEXT_RENDERER_DURATION_TO_PROGRESS);
    byte[] encodedCues = encodeSingleCue("test", /* durationUs= */ 200_000);
    TextRenderer renderer = new TextRenderer(cueGroup -> {}, /* outputLooper= */ null);
    Timeline timeline = createSinglePeriodTimeline(/* durationUs= */ 2_000_000);
    enableRenderer(
        renderer,
        TEXT_FORMAT,
        ImmutableList.of(
            sample(/* timeUs= */ 100_000, C.BUFFER_FLAG_KEY_FRAME, encodedCues),
            END_OF_STREAM_ITEM),
        /* positionUs= */ 0,
        /* offsetUs= */ 0,
        timeline);
    renderer.start();
    renderer.render(/* positionUs= */ 1_200_000, /* elapsedRealtimeUs= */ 0);
    renderer.render(/* positionUs= */ 1_200_000, /* elapsedRealtimeUs= */ 0);
    FakeSampleStream emptyStream = createFakeSampleStream(TEXT_FORMAT, ImmutableList.of());
    emptyStream.writeData(/* startPositionUs= */ 0);
    renderer.replaceStream(
        new Format[] {TEXT_FORMAT},
        emptyStream,
        /* startPositionUs= */ 2_000_000,
        /* offsetUs= */ 2_000_000,
        new MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)));
    renderer.render(/* positionUs= */ 2_000_000, /* elapsedRealtimeUs= */ 0);

    long durationToProgressUs =
        renderer.getDurationToProgressUs(/* positionUs= */ 2_000_000, /* elapsedRealtimeUs= */ 0);

    assertThat(durationToProgressUs).isEqualTo(Renderer.DEFAULT_DURATION_TO_PROGRESS_US);
  }

  @Test
  public void getDurationToProgressUs_afterTimelineDurationResolved_returnsDefaultDuration()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_TEXT_RENDERER_DURATION_TO_PROGRESS);
    byte[] encodedCues = encodeSingleCue("test", /* durationUs= */ 200_000);
    TextRenderer renderer = new TextRenderer(cueGroup -> {}, /* outputLooper= */ null);
    Timeline resolvedTimeline = createSinglePeriodTimeline(/* durationUs= */ 2_000_000);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            TEXT_FORMAT,
            ImmutableList.of(
                sample(/* timeUs= */ 100_000, C.BUFFER_FLAG_KEY_FRAME, encodedCues),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    renderer.setTimeline(Timeline.EMPTY);
    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {TEXT_FORMAT},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(resolvedTimeline.getUidOfPeriod(0)));
    renderer.setCurrentStreamFinal();
    renderer.start();
    renderer.render(/* positionUs= */ 1_200_000, /* elapsedRealtimeUs= */ 0);
    renderer.render(/* positionUs= */ 1_200_000, /* elapsedRealtimeUs= */ 0);
    long durationBeforeTimelineResolvedUs =
        renderer.getDurationToProgressUs(/* positionUs= */ 1_200_000, /* elapsedRealtimeUs= */ 0);

    renderer.setTimeline(resolvedTimeline);
    long durationAfterTimelineResolvedUs =
        renderer.getDurationToProgressUs(/* positionUs= */ 1_200_000, /* elapsedRealtimeUs= */ 0);

    assertThat(durationBeforeTimelineResolvedUs)
        .isEqualTo(Renderer.DEFAULT_IDLE_DURATION_TO_PROGRESS_US);
    assertThat(durationAfterTimelineResolvedUs).isEqualTo(Renderer.DEFAULT_DURATION_TO_PROGRESS_US);
  }

  @Test
  public void getDurationToProgressUs_withUpcomingCueStartTime_returnsDurationToCueStart()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_TEXT_RENDERER_DURATION_TO_PROGRESS);
    byte[] encodedCues = encodeSingleCue("test", /* durationUs= */ 1_000_000);
    TextRenderer renderer = new TextRenderer(cueGroup -> {}, /* outputLooper= */ null);
    enableRenderer(
        renderer,
        TEXT_FORMAT,
        ImmutableList.of(
            sample(/* timeUs= */ 500_000, C.BUFFER_FLAG_KEY_FRAME, encodedCues),
            END_OF_STREAM_ITEM),
        /* positionUs= */ 1_000_000,
        /* offsetUs= */ 1_000_000);
    renderer.start();
    renderer.render(/* positionUs= */ 1_000_000, /* elapsedRealtimeUs= */ 0);
    renderer.render(/* positionUs= */ 1_000_000, /* elapsedRealtimeUs= */ 0);

    long durationToProgressUs =
        renderer.getDurationToProgressUs(/* positionUs= */ 1_100_000, /* elapsedRealtimeUs= */ 0);

    assertThat(durationToProgressUs).isEqualTo(400_000L);
  }

  @Test
  public void getDurationToProgressUs_withActiveCueEndTime_returnsDurationToCueEnd()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_TEXT_RENDERER_DURATION_TO_PROGRESS);
    byte[] encodedCues = encodeSingleCue("test", /* durationUs= */ 1_000_000);
    TextRenderer renderer = new TextRenderer(cueGroup -> {}, /* outputLooper= */ null);
    enableRenderer(
        renderer,
        TEXT_FORMAT,
        ImmutableList.of(
            sample(/* timeUs= */ 500_000, C.BUFFER_FLAG_KEY_FRAME, encodedCues),
            END_OF_STREAM_ITEM),
        /* positionUs= */ 1_000_000,
        /* offsetUs= */ 1_000_000);
    renderer.start();
    renderer.render(/* positionUs= */ 1_500_000, /* elapsedRealtimeUs= */ 0);
    renderer.render(/* positionUs= */ 1_500_000, /* elapsedRealtimeUs= */ 0);

    long durationToProgressUs =
        renderer.getDurationToProgressUs(/* positionUs= */ 1_700_000, /* elapsedRealtimeUs= */ 0);

    assertThat(durationToProgressUs).isEqualTo(800_000L);
  }

  @Test
  public void
      getDurationToProgressUs_withMergingCuesResolver_returnsDurationToNextOverlappingChange()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_TEXT_RENDERER_DURATION_TO_PROGRESS);
    Format mergingTextFormat =
        TEXT_FORMAT
            .buildUpon()
            .setCueReplacementBehavior(Format.CUE_REPLACEMENT_BEHAVIOR_MERGE)
            .build();
    byte[] cue1 = encodeSingleCue("cue1", /* durationUs= */ 700_000);
    byte[] cue2 = encodeSingleCue("cue2", /* durationUs= */ 300_000);
    TextRenderer renderer = new TextRenderer(cueGroup -> {}, /* outputLooper= */ null);
    enableRenderer(
        renderer,
        mergingTextFormat,
        ImmutableList.of(
            sample(/* timeUs= */ 100_000, C.BUFFER_FLAG_KEY_FRAME, cue1),
            sample(/* timeUs= */ 300_000, C.BUFFER_FLAG_KEY_FRAME, cue2),
            END_OF_STREAM_ITEM));
    renderer.start();
    renderer.render(/* positionUs= */ 200_000, /* elapsedRealtimeUs= */ 0);
    renderer.render(/* positionUs= */ 200_000, /* elapsedRealtimeUs= */ 0);
    renderer.render(/* positionUs= */ 200_000, /* elapsedRealtimeUs= */ 0);

    long durationToProgressUs =
        renderer.getDurationToProgressUs(/* positionUs= */ 200_000, /* elapsedRealtimeUs= */ 0);

    assertThat(durationToProgressUs).isEqualTo(100_000L);
  }

  @Test
  public void getDurationToProgressUs_withDoublePlaybackSpeed_scalesDuration() throws Exception {
    Flags.enableFlag(Flags.FLAG_TEXT_RENDERER_DURATION_TO_PROGRESS);
    byte[] encodedCues = encodeSingleCue("test", /* durationUs= */ 1_000_000);
    TextRenderer renderer = new TextRenderer(cueGroup -> {}, /* outputLooper= */ null);
    enableRenderer(
        renderer,
        TEXT_FORMAT,
        ImmutableList.of(
            sample(/* timeUs= */ 500_000, C.BUFFER_FLAG_KEY_FRAME, encodedCues),
            END_OF_STREAM_ITEM));
    renderer.setPlaybackSpeed(/* currentPlaybackSpeed= */ 2.0f, /* targetPlaybackSpeed= */ 2.0f);
    renderer.start();
    renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);

    long durationToProgressUs =
        renderer.getDurationToProgressUs(/* positionUs= */ 100_000, /* elapsedRealtimeUs= */ 0);

    assertThat(durationToProgressUs).isEqualTo(200_000L);
  }

  @Test
  public void getDurationToProgressUs_withSubMillisecondCueDelta_roundsUpToOneMillisecond()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_TEXT_RENDERER_DURATION_TO_PROGRESS);
    byte[] encodedCues = encodeSingleCue("test", /* durationUs= */ 500_000);
    TextRenderer renderer = new TextRenderer(cueGroup -> {}, /* outputLooper= */ null);
    enableRenderer(
        renderer,
        TEXT_FORMAT,
        ImmutableList.of(
            sample(/* timeUs= */ 500_500, C.BUFFER_FLAG_KEY_FRAME, encodedCues),
            END_OF_STREAM_ITEM));
    renderer.start();
    renderer.render(/* positionUs= */ 500_000, /* elapsedRealtimeUs= */ 0);
    renderer.render(/* positionUs= */ 500_000, /* elapsedRealtimeUs= */ 0);

    long durationToProgressUs =
        renderer.getDurationToProgressUs(/* positionUs= */ 500_000, /* elapsedRealtimeUs= */ 0);

    assertThat(durationToProgressUs).isEqualTo(1_000L);
  }

  @Test
  public void
      getDurationToProgressUs_withFractionalSpeedSubMillisecondDelta_roundsUpToOneMillisecond()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_TEXT_RENDERER_DURATION_TO_PROGRESS);
    byte[] encodedCues = encodeSingleCue("test", /* durationUs= */ 500_000);
    TextRenderer renderer = new TextRenderer(cueGroup -> {}, /* outputLooper= */ null);
    enableRenderer(
        renderer,
        TEXT_FORMAT,
        ImmutableList.of(
            sample(/* timeUs= */ 501_000, C.BUFFER_FLAG_KEY_FRAME, encodedCues),
            END_OF_STREAM_ITEM));
    renderer.setPlaybackSpeed(/* currentPlaybackSpeed= */ 1.5f, /* targetPlaybackSpeed= */ 1.5f);
    renderer.start();
    renderer.render(/* positionUs= */ 500_000, /* elapsedRealtimeUs= */ 0);
    renderer.render(/* positionUs= */ 500_000, /* elapsedRealtimeUs= */ 0);

    long durationToProgressUs =
        renderer.getDurationToProgressUs(/* positionUs= */ 500_000, /* elapsedRealtimeUs= */ 0);

    assertThat(durationToProgressUs).isEqualTo(1_000L);
  }

  @Test
  public void getDurationToProgressUs_afterConsumingCue_returnsZeroUntilNextSourceItemPeeked()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_TEXT_RENDERER_DURATION_TO_PROGRESS);
    byte[] firstCue = encodeSingleCue("first", /* durationUs= */ 800_000);
    byte[] secondCue = encodeSingleCue("second", /* durationUs= */ 200_000);
    TextRenderer renderer = new TextRenderer(cueGroup -> {}, /* outputLooper= */ null);
    enableRenderer(
        renderer,
        TEXT_FORMAT,
        ImmutableList.of(
            sample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME, firstCue),
            sample(/* timeUs= */ 3_000_000, C.BUFFER_FLAG_KEY_FRAME, secondCue),
            END_OF_STREAM_ITEM));
    renderer.start();

    renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    long durationAfterConsumingCueUs =
        renderer.getDurationToProgressUs(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    long durationAfterPeekingNextItemUs =
        renderer.getDurationToProgressUs(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);

    assertThat(durationAfterConsumingCueUs).isEqualTo(0L);
    assertThat(durationAfterPeekingNextItemUs).isEqualTo(800_000L);
  }

  @Test
  public void
      getDurationToProgressUs_withOpenEndedReplacingCueReplacedByNextCue_returnsDurationToNextCue()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_TEXT_RENDERER_DURATION_TO_PROGRESS);
    Format replacingTextFormat =
        TEXT_FORMAT
            .buildUpon()
            .setCueReplacementBehavior(Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE)
            .build();
    byte[] openEndedCue = encodeSingleCue("open", /* durationUs= */ C.TIME_UNSET);
    byte[] replacementCue = encodeSingleCue("replacement", /* durationUs= */ 300_000);
    TextRenderer renderer = new TextRenderer(cueGroup -> {}, /* outputLooper= */ null);
    enableRenderer(
        renderer,
        replacingTextFormat,
        ImmutableList.of(
            sample(/* timeUs= */ 100_000, C.BUFFER_FLAG_KEY_FRAME, openEndedCue),
            sample(/* timeUs= */ 600_000, C.BUFFER_FLAG_KEY_FRAME, replacementCue),
            END_OF_STREAM_ITEM));
    renderer.start();
    renderer.render(/* positionUs= */ 200_000, /* elapsedRealtimeUs= */ 0);
    renderer.render(/* positionUs= */ 200_000, /* elapsedRealtimeUs= */ 0);
    renderer.render(/* positionUs= */ 200_000, /* elapsedRealtimeUs= */ 0);

    long durationToProgressUs =
        renderer.getDurationToProgressUs(/* positionUs= */ 200_000, /* elapsedRealtimeUs= */ 0);

    assertThat(durationToProgressUs).isEqualTo(400_000L);
  }

  @Test
  public void
      getDurationToProgressUs_withOpenEndedReplacingCueAtEndOfStream_returnsZeroThenStreamEnd()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_TEXT_RENDERER_DURATION_TO_PROGRESS);
    Format replacingTextFormat =
        TEXT_FORMAT
            .buildUpon()
            .setCueReplacementBehavior(Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE)
            .build();
    byte[] openEndedCue = encodeSingleCue("open", /* durationUs= */ C.TIME_UNSET);
    List<CueGroup> outputCueGroups = new ArrayList<>();
    TextRenderer renderer = new TextRenderer(outputCueGroups::add, /* outputLooper= */ null);
    Timeline timeline = createSinglePeriodTimeline(/* durationUs= */ 2_000_000);
    enableRenderer(
        renderer,
        replacingTextFormat,
        ImmutableList.of(
            sample(/* timeUs= */ 100_000, C.BUFFER_FLAG_KEY_FRAME, openEndedCue),
            END_OF_STREAM_ITEM),
        /* positionUs= */ 0,
        /* offsetUs= */ 0,
        timeline);
    renderer.setCurrentStreamFinal();
    renderer.start();

    renderer.render(/* positionUs= */ 1_100_000, /* elapsedRealtimeUs= */ 0);
    long durationBeforeEndedUs =
        renderer.getDurationToProgressUs(/* positionUs= */ 1_100_000, /* elapsedRealtimeUs= */ 0);
    renderer.render(/* positionUs= */ 1_100_000, /* elapsedRealtimeUs= */ 0);
    long durationAfterEndedUs =
        renderer.getDurationToProgressUs(/* positionUs= */ 1_100_000, /* elapsedRealtimeUs= */ 0);

    assertThat(renderer.isEnded()).isTrue();
    assertThat(outputCueGroups).hasSize(2);
    assertThat(outputCueGroups.get(1).cues.get(0).text.toString()).isEqualTo("open");
    assertThat(durationBeforeEndedUs).isEqualTo(0L);
    assertThat(durationAfterEndedUs).isEqualTo(900_000L);
  }

  @Test
  public void
      getDurationToProgressUs_withUnreadSampleBeyondMaxReadAhead_returnsDurationToReadAheadThreshold()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_TEXT_RENDERER_DURATION_TO_PROGRESS);
    byte[] encodedCues = encodeSingleCue("test", /* durationUs= */ 500_000);
    TextRenderer renderer = new TextRenderer(cueGroup -> {}, /* outputLooper= */ null);
    enableRenderer(
        renderer,
        TEXT_FORMAT,
        ImmutableList.of(
            sample(/* timeUs= */ 3_000_000, C.BUFFER_FLAG_KEY_FRAME, encodedCues),
            END_OF_STREAM_ITEM),
        /* positionUs= */ 1_000_000,
        /* offsetUs= */ 1_000_000);
    renderer.start();
    renderer.render(/* positionUs= */ 1_000_000, /* elapsedRealtimeUs= */ 0);

    long durationToProgressUs =
        renderer.getDurationToProgressUs(/* positionUs= */ 1_400_000, /* elapsedRealtimeUs= */ 0);

    assertThat(durationToProgressUs).isEqualTo(1_600_000L);
  }

  @Test
  public void getDurationToProgressUs_whenLastCueClearsAtEndOfStream_returnsZeroBeforeOutputEnded()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_TEXT_RENDERER_DURATION_TO_PROGRESS);
    byte[] encodedCues = encodeSingleCue("test", /* durationUs= */ 400_000);
    TextRenderer renderer = new TextRenderer(cueGroup -> {}, /* outputLooper= */ null);
    Timeline timeline = createSinglePeriodTimeline(/* durationUs= */ 2_000_000);
    enableRenderer(
        renderer,
        TEXT_FORMAT,
        ImmutableList.of(
            sample(/* timeUs= */ 100_000, C.BUFFER_FLAG_KEY_FRAME, encodedCues),
            END_OF_STREAM_ITEM),
        /* positionUs= */ 0,
        /* offsetUs= */ 0,
        timeline);
    renderer.setCurrentStreamFinal();
    renderer.start();
    renderer.render(/* positionUs= */ 100_000, /* elapsedRealtimeUs= */ 0);
    // Reads EOS and clears the cue at 500_000; outputStreamEnded becomes true on the next render().
    renderer.render(/* positionUs= */ 1_100_000, /* elapsedRealtimeUs= */ 0);

    long durationToProgressUs =
        renderer.getDurationToProgressUs(/* positionUs= */ 1_100_000, /* elapsedRealtimeUs= */ 0);

    assertThat(renderer.isEnded()).isFalse();
    assertThat(durationToProgressUs).isEqualTo(0L);
  }

  @Test
  public void getDurationToProgressUs_whenOutputEndedBeforeStreamEnd_returnsDurationToStreamEnd()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_TEXT_RENDERER_DURATION_TO_PROGRESS);
    byte[] encodedCues = encodeSingleCue("test", /* durationUs= */ 400_000);
    TextRenderer renderer = new TextRenderer(cueGroup -> {}, /* outputLooper= */ null);
    Timeline timeline = createSinglePeriodTimeline(/* durationUs= */ 2_000_000);
    enableRenderer(
        renderer,
        TEXT_FORMAT,
        ImmutableList.of(
            sample(/* timeUs= */ 100_000, C.BUFFER_FLAG_KEY_FRAME, encodedCues),
            END_OF_STREAM_ITEM),
        /* positionUs= */ 0,
        /* offsetUs= */ 0,
        timeline);
    renderer.setCurrentStreamFinal();
    renderer.start();
    renderer.render(/* positionUs= */ 100_000, /* elapsedRealtimeUs= */ 0);
    renderer.render(/* positionUs= */ 1_100_000, /* elapsedRealtimeUs= */ 0);
    renderer.render(/* positionUs= */ 1_100_000, /* elapsedRealtimeUs= */ 0);

    long durationToProgressUs =
        renderer.getDurationToProgressUs(/* positionUs= */ 1_100_000, /* elapsedRealtimeUs= */ 0);

    assertThat(renderer.isEnded()).isTrue();
    assertThat(durationToProgressUs).isEqualTo(900_000L);
  }

  @Test
  public void getDurationToProgressUs_whenOutputEndedAtStreamEnd_returnsDefaultIdleDuration()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_TEXT_RENDERER_DURATION_TO_PROGRESS);
    byte[] encodedCues = encodeSingleCue("test", /* durationUs= */ 400_000);
    TextRenderer renderer = new TextRenderer(cueGroup -> {}, /* outputLooper= */ null);
    Timeline timeline = createSinglePeriodTimeline(/* durationUs= */ 2_000_000);
    enableRenderer(
        renderer,
        TEXT_FORMAT,
        ImmutableList.of(
            sample(/* timeUs= */ 100_000, C.BUFFER_FLAG_KEY_FRAME, encodedCues),
            END_OF_STREAM_ITEM),
        /* positionUs= */ 0,
        /* offsetUs= */ 0,
        timeline);
    renderer.setCurrentStreamFinal();
    renderer.start();
    renderer.render(/* positionUs= */ 100_000, /* elapsedRealtimeUs= */ 0);
    renderer.render(/* positionUs= */ 1_100_000, /* elapsedRealtimeUs= */ 0);
    renderer.render(/* positionUs= */ 1_100_000, /* elapsedRealtimeUs= */ 0);

    long durationToProgressUs =
        renderer.getDurationToProgressUs(/* positionUs= */ 2_000_000, /* elapsedRealtimeUs= */ 0);

    assertThat(renderer.isEnded()).isTrue();
    assertThat(durationToProgressUs).isEqualTo(Renderer.DEFAULT_IDLE_DURATION_TO_PROGRESS_US);
  }

  @Test
  public void getDurationToProgressUs_beforeFinalStreamEndPosition_clampsDuration()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_TEXT_RENDERER_DURATION_TO_PROGRESS);
    byte[] encodedCues = encodeSingleCue("test", /* durationUs= */ 500_000);
    TextRenderer renderer = new TextRenderer(cueGroup -> {}, /* outputLooper= */ null);
    enableRenderer(
        renderer,
        TEXT_FORMAT,
        ImmutableList.of(
            sample(/* timeUs= */ 800_000, C.BUFFER_FLAG_KEY_FRAME, encodedCues),
            END_OF_STREAM_ITEM));
    renderer.setCurrentStreamFinal();
    renderer.setFinalStreamEndPositionUs(400_000);
    renderer.start();
    renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);

    long durationToProgressUs =
        renderer.getDurationToProgressUs(/* positionUs= */ 100_000, /* elapsedRealtimeUs= */ 0);

    assertThat(durationToProgressUs).isEqualTo(300_000L);
  }

  @Test
  public void getDurationToProgressUs_atFinalStreamEndPosition_returnsZero() throws Exception {
    Flags.enableFlag(Flags.FLAG_TEXT_RENDERER_DURATION_TO_PROGRESS);
    byte[] encodedCues = encodeSingleCue("test", /* durationUs= */ 500_000);
    TextRenderer renderer = new TextRenderer(cueGroup -> {}, /* outputLooper= */ null);
    enableRenderer(
        renderer,
        TEXT_FORMAT,
        ImmutableList.of(
            sample(/* timeUs= */ 800_000, C.BUFFER_FLAG_KEY_FRAME, encodedCues),
            END_OF_STREAM_ITEM));
    renderer.setCurrentStreamFinal();
    renderer.setFinalStreamEndPositionUs(400_000);
    renderer.start();
    renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);

    long durationToProgressUs =
        renderer.getDurationToProgressUs(/* positionUs= */ 400_000, /* elapsedRealtimeUs= */ 0);

    assertThat(durationToProgressUs).isEqualTo(0L);
  }

  @Test
  public void
      getDurationToProgressUs_whenOutputEndedBeforeFinalStreamEndPosition_returnsDurationToFinalEnd()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_TEXT_RENDERER_DURATION_TO_PROGRESS);
    byte[] encodedCues = encodeSingleCue("test", /* durationUs= */ 200_000);
    TextRenderer renderer = new TextRenderer(cueGroup -> {}, /* outputLooper= */ null);
    Timeline timeline = createSinglePeriodTimeline(/* durationUs= */ 1_400_000);
    enableRenderer(
        renderer,
        TEXT_FORMAT,
        ImmutableList.of(
            sample(/* timeUs= */ 100_000, C.BUFFER_FLAG_KEY_FRAME, encodedCues),
            END_OF_STREAM_ITEM),
        /* positionUs= */ 0,
        /* offsetUs= */ 0,
        timeline);
    renderer.setCurrentStreamFinal();
    renderer.setFinalStreamEndPositionUs(800_000);
    renderer.start();
    renderer.render(/* positionUs= */ 100_000, /* elapsedRealtimeUs= */ 0);
    renderer.render(/* positionUs= */ 500_000, /* elapsedRealtimeUs= */ 0);
    renderer.render(/* positionUs= */ 500_000, /* elapsedRealtimeUs= */ 0);

    long durationToProgressUs =
        renderer.getDurationToProgressUs(/* positionUs= */ 500_000, /* elapsedRealtimeUs= */ 0);

    assertThat(renderer.isEnded()).isTrue();
    assertThat(durationToProgressUs).isEqualTo(300_000L);
  }

  @Test
  public void
      getDurationToProgressUs_whenOutputEndedAtFinalStreamEndPosition_returnsDefaultIdleDuration()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_TEXT_RENDERER_DURATION_TO_PROGRESS);
    byte[] encodedCues = encodeSingleCue("test", /* durationUs= */ 500_000);
    TextRenderer renderer = new TextRenderer(cueGroup -> {}, /* outputLooper= */ null);
    Timeline timeline = createSinglePeriodTimeline(/* durationUs= */ 2_000_000);
    enableRenderer(
        renderer,
        TEXT_FORMAT,
        ImmutableList.of(
            sample(/* timeUs= */ 800_000, C.BUFFER_FLAG_KEY_FRAME, encodedCues),
            END_OF_STREAM_ITEM),
        /* positionUs= */ 0,
        /* offsetUs= */ 0,
        timeline);
    renderer.setCurrentStreamFinal();
    renderer.setFinalStreamEndPositionUs(400_000);
    renderer.start();
    renderer.render(/* positionUs= */ 400_000, /* elapsedRealtimeUs= */ 0);

    long durationToProgressUs =
        renderer.getDurationToProgressUs(/* positionUs= */ 400_000, /* elapsedRealtimeUs= */ 0);

    assertThat(renderer.isEnded()).isTrue();
    assertThat(durationToProgressUs).isEqualTo(Renderer.DEFAULT_IDLE_DURATION_TO_PROGRESS_US);
  }

  @Test
  public void getDurationToProgressUs_withEmptySourceNotEnded_returnsDefaultDuration()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_TEXT_RENDERER_DURATION_TO_PROGRESS);
    TextRenderer renderer = new TextRenderer(cueGroup -> {}, /* outputLooper= */ null);
    enableRenderer(renderer, TEXT_FORMAT, ImmutableList.of());
    renderer.start();
    renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);

    long durationToProgressUs =
        renderer.getDurationToProgressUs(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);

    assertThat(durationToProgressUs).isEqualTo(Renderer.DEFAULT_DURATION_TO_PROGRESS_US);
  }

  @Test
  public void
      getDurationToProgressUs_withDeferredEndOfStream_returnsDurationToEndOfStreamReadThreshold()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_TEXT_RENDERER_DURATION_TO_PROGRESS);
    byte[] encodedCues = encodeSingleCue("test", /* durationUs= */ 200_000);
    TextRenderer renderer = new TextRenderer(cueGroup -> {}, /* outputLooper= */ null);
    Timeline timeline = createSinglePeriodTimeline(/* durationUs= */ 5_000_000);
    enableRenderer(
        renderer,
        TEXT_FORMAT,
        ImmutableList.of(
            sample(/* timeUs= */ 100_000, C.BUFFER_FLAG_KEY_FRAME, encodedCues),
            END_OF_STREAM_ITEM),
        /* positionUs= */ 1_000_000,
        /* offsetUs= */ 1_000_000,
        timeline);
    renderer.setCurrentStreamFinal();
    renderer.start();
    renderer.render(/* positionUs= */ 1_100_000, /* elapsedRealtimeUs= */ 0);
    renderer.render(/* positionUs= */ 1_400_000, /* elapsedRealtimeUs= */ 0);

    long durationToProgressUs =
        renderer.getDurationToProgressUs(/* positionUs= */ 1_500_000, /* elapsedRealtimeUs= */ 0);

    assertThat(durationToProgressUs).isEqualTo(3_500_000L);
  }

  @Test
  public void getDurationToProgressUs_withNonFinalEndOfStream_returnsDefaultDuration()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_TEXT_RENDERER_DURATION_TO_PROGRESS);
    byte[] encodedCues = encodeSingleCue("test", /* durationUs= */ 200_000);
    TextRenderer renderer = new TextRenderer(cueGroup -> {}, /* outputLooper= */ null);
    Timeline timeline = createSinglePeriodTimeline(/* durationUs= */ 2_000_000);
    enableRenderer(
        renderer,
        TEXT_FORMAT,
        ImmutableList.of(
            sample(/* timeUs= */ 100_000, C.BUFFER_FLAG_KEY_FRAME, encodedCues),
            END_OF_STREAM_ITEM),
        /* positionUs= */ 0,
        /* offsetUs= */ 0,
        timeline);
    renderer.start();
    renderer.render(/* positionUs= */ 100_000, /* elapsedRealtimeUs= */ 0);
    // At 1_200_000, non-final EOS is within read-ahead: hasReadStreamToEnd() becomes true.
    renderer.render(/* positionUs= */ 1_200_000, /* elapsedRealtimeUs= */ 0);

    long durationToProgressUs =
        renderer.getDurationToProgressUs(/* positionUs= */ 1_200_000, /* elapsedRealtimeUs= */ 0);

    assertThat(renderer.hasReadStreamToEnd()).isTrue();
    assertThat(renderer.isEnded()).isFalse();
    assertThat(durationToProgressUs).isEqualTo(Renderer.DEFAULT_DURATION_TO_PROGRESS_US);
  }

  @Test
  public void
      render_afterSetCurrentStreamFinalFollowingNonFinalEndOfStream_consumesEndOfStreamAndEnds()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_TEXT_RENDERER_DURATION_TO_PROGRESS);
    byte[] encodedCues = encodeSingleCue("test", /* durationUs= */ 200_000);
    TextRenderer renderer = new TextRenderer(cueGroup -> {}, /* outputLooper= */ null);
    Timeline timeline = createSinglePeriodTimeline(/* durationUs= */ 2_000_000);
    enableRenderer(
        renderer,
        TEXT_FORMAT,
        ImmutableList.of(
            sample(/* timeUs= */ 100_000, C.BUFFER_FLAG_KEY_FRAME, encodedCues),
            END_OF_STREAM_ITEM),
        /* positionUs= */ 0,
        /* offsetUs= */ 0,
        timeline);
    renderer.start();
    renderer.render(/* positionUs= */ 100_000, /* elapsedRealtimeUs= */ 0);
    // Reach withheld non-final end-of-stream within read-ahead.
    renderer.render(/* positionUs= */ 1_200_000, /* elapsedRealtimeUs= */ 0);
    renderer.setCurrentStreamFinal();

    renderer.render(/* positionUs= */ 1_200_000, /* elapsedRealtimeUs= */ 0);

    assertThat(renderer.isEnded()).isTrue();
  }

  @Test
  public void getDurationToProgressUs_withCea608Format_returnsDefaultDuration() throws Exception {
    Flags.enableFlag(Flags.FLAG_TEXT_RENDERER_DURATION_TO_PROGRESS);
    Format cea608Format =
        new Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_CEA608).build();
    TextRenderer renderer = new TextRenderer(cueGroup -> {}, /* outputLooper= */ null);
    enableRenderer(renderer, cea608Format, ImmutableList.of(END_OF_STREAM_ITEM));
    renderer.start();
    renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);

    long durationToProgressUs =
        renderer.getDurationToProgressUs(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);

    assertThat(durationToProgressUs).isEqualTo(Renderer.DEFAULT_DURATION_TO_PROGRESS_US);
  }

  @Test
  public void getDurationToProgressUs_withLegacySubtitleFormat_returnsDefaultDuration()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_TEXT_RENDERER_DURATION_TO_PROGRESS);
    byte[] webvttBytes = "WEBVTT\n\n00:00.500 --> 00:01.500\ntest\n".getBytes(UTF_8);
    TextRenderer renderer = new TextRenderer(cueGroup -> {}, /* outputLooper= */ null);
    renderer.experimentalSetLegacyDecodingEnabled(true);
    enableRenderer(
        renderer,
        LEGACY_TEXT_FORMAT,
        ImmutableList.of(
            sample(/* timeUs= */ 500_000, C.BUFFER_FLAG_KEY_FRAME, webvttBytes),
            END_OF_STREAM_ITEM));
    renderer.start();
    renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);

    long durationToProgressUs =
        renderer.getDurationToProgressUs(/* positionUs= */ 100_000, /* elapsedRealtimeUs= */ 0);

    assertThat(durationToProgressUs).isEqualTo(Renderer.DEFAULT_DURATION_TO_PROGRESS_US);
  }

  @Test
  public void play_withDurationToProgressMultiPeriodAndFractionalSpeed_rendersAllCuesToEnd()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_DYNAMIC_SCHEDULING);
    Flags.enableFlag(Flags.FLAG_TEXT_RENDERER_DURATION_TO_PROGRESS);
    Format defaultTextFormat =
        TEXT_FORMAT.buildUpon().setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build();
    byte[] period1Cue = encodeSingleCue("period1", /* durationUs= */ 400_300);
    byte[] period2Cue = encodeSingleCue("period2", /* durationUs= */ 500_000);
    FakeMediaSource firstMediaSource =
        new FakeMediaSource.Builder()
            .setTimeline(createSinglePeriodTimeline(/* durationUs= */ 2_000_000))
            .setFormats(defaultTextFormat)
            .setTrackDataFactory((unusedFormat, unusedMediaPeriodId) ->
                ImmutableList.of(
                    sample(/* timeUs= */ 500_500, C.BUFFER_FLAG_KEY_FRAME, period1Cue),
                    END_OF_STREAM_ITEM))
            .build();
    FakeMediaSource secondMediaSource =
        new FakeMediaSource.Builder()
            .setTimeline(createSinglePeriodTimeline(/* durationUs= */ 2_000_000))
            .setFormats(defaultTextFormat)
            .setTrackDataFactory((unusedFormat, unusedMediaPeriodId) ->
                ImmutableList.of(
                    sample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME, period2Cue),
                    END_OF_STREAM_ITEM))
            .build();
    List<String> renderedCueTexts = new ArrayList<>();
    AtomicInteger renderCount = new AtomicInteger();
    ExoPlayer player =
        new TestExoPlayerBuilder(ApplicationProvider.getApplicationContext())
            .setRenderersFactory(
                (eventHandler, videoListener, audioListener, textOutput, metadataOutput) ->
                    new Renderer[] {
                      new TextRenderer(textOutput, eventHandler.getLooper()),
                      new NoSampleRenderer() {
                        @Override
                        public String getName() {
                          return "RenderCountRenderer";
                        }

                        @Override
                        public void render(long positionUs, long elapsedRealtimeUs) {
                          renderCount.incrementAndGet();
                        }

                        @Override
                        public long getDurationToProgressUs(
                            long positionUs, long elapsedRealtimeUs) {
                          return Long.MAX_VALUE;
                        }
                      }
                    })
            .build();
    player.addListener(
        new Player.Listener() {
          @Override
          public void onCues(CueGroup cueGroup) {
            String text = cueGroup.cues.isEmpty() ? "" : cueGroup.cues.get(0).text.toString();
            if (renderedCueTexts.isEmpty() && text.isEmpty()) {
              return;
            }
            renderedCueTexts.add(text);
          }
        });
    player.setPlaybackParameters(new PlaybackParameters(/* speed= */ 1.5f));
    player.setMediaSources(ImmutableList.of(firstMediaSource, secondMediaSource));

    try {
      player.prepare();
      player.play();
      advance(player).untilState(Player.STATE_ENDED);
    } finally {
      player.release();
    }

    assertThat(renderedCueTexts).containsExactly("period1", "", "period2", "").inOrder();
    assertThat(renderCount.get()).isIn(Range.open(0, 50));
  }

  private byte[] encodeSingleCue(String text, long durationUs) {
    return cueEncoder.encode(ImmutableList.of(new Cue.Builder().setText(text).build()), durationUs);
  }

  private static Timeline createSinglePeriodTimeline(long durationUs) {
    return new FakeTimeline(
        new FakeTimeline.TimelineWindowDefinition.Builder()
            .setDurationUs(durationUs)
            .setWindowPositionInFirstPeriodUs(0)
            .build());
  }

  @CanIgnoreReturnValue
  private FakeSampleStream enableRenderer(
      TextRenderer renderer, Format format, ImmutableList<FakeSampleStreamItem> sampleItems)
      throws ExoPlaybackException {
    return enableRenderer(renderer, format, sampleItems, /* positionUs= */ 0, /* offsetUs= */ 0);
  }

  @CanIgnoreReturnValue
  private FakeSampleStream enableRenderer(
      TextRenderer renderer,
      Format format,
      ImmutableList<FakeSampleStreamItem> sampleItems,
      long positionUs,
      long offsetUs)
      throws ExoPlaybackException {
    renderersToCleanUp.add(renderer);
    FakeSampleStream fakeSampleStream = createFakeSampleStream(format, sampleItems);
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {format},
        fakeSampleStream,
        positionUs,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ positionUs,
        offsetUs,
        new MediaSource.MediaPeriodId(new Object()));
    return fakeSampleStream;
  }

  @CanIgnoreReturnValue
  private FakeSampleStream enableRenderer(
      TextRenderer renderer,
      Format format,
      ImmutableList<FakeSampleStreamItem> sampleItems,
      long positionUs,
      long offsetUs,
      Timeline timeline)
      throws ExoPlaybackException {
    renderersToCleanUp.add(renderer);
    FakeSampleStream fakeSampleStream = createFakeSampleStream(format, sampleItems);
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    renderer.setTimeline(timeline);
    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {format},
        fakeSampleStream,
        positionUs,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ positionUs,
        offsetUs,
        new MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)));
    return fakeSampleStream;
  }

  private static FakeSampleStream createFakeSampleStream(
      Format format, ImmutableList<FakeSampleStreamItem> samples) {
    return new FakeSampleStream(
        new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
        /* mediaSourceEventDispatcher= */ null,
        DrmSessionManager.DRM_UNSUPPORTED,
        new DrmSessionEventListener.EventDispatcher(),
        format,
        samples);
  }
}
