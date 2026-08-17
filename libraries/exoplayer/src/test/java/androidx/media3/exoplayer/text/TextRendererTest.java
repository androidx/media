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
import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Timeline;
import androidx.media3.common.text.Cue;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.RendererConfiguration;
import androidx.media3.exoplayer.drm.DrmSessionEventListener;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import androidx.media3.extractor.text.CueEncoder;
import androidx.media3.test.utils.FakeSampleStream;
import androidx.media3.test.utils.FakeTimeline;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link TextRenderer}. */
@RunWith(AndroidJUnit4.class)
public class TextRendererTest {

  private static final Format TEXT_FORMAT =
      new Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES).build();

  private static final Format LEGACY_TEXT_FORMAT =
      new Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).build();

  private final CueEncoder cueEncoder = new CueEncoder();

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
    ImmutableList<Cue> cues = ImmutableList.of(new Cue.Builder().setText("test").build());
    byte[] encodedCues = cueEncoder.encode(cues, /* durationUs= */ 1_000_000);
    TextRenderer renderer = new TextRenderer(cueList -> {}, /* outputLooper= */ null);
    renderer.experimentalSetLegacyDecodingEnabled(true);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            LEGACY_TEXT_FORMAT,
            ImmutableList.of(
                sample(/* timeUs= */ 100_000, C.BUFFER_FLAG_KEY_FRAME, encodedCues),
                sample(/* timeUs= */ 450_000, C.BUFFER_FLAG_KEY_FRAME, encodedCues),
                sample(/* timeUs= */ 2_000_000, C.BUFFER_FLAG_KEY_FRAME, encodedCues),
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

  private static FakeSampleStream createFakeSampleStream(
      Format format, ImmutableList<FakeSampleStream.FakeSampleStreamItem> samples) {
    return new FakeSampleStream(
        new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
        /* mediaSourceEventDispatcher= */ null,
        DrmSessionManager.DRM_UNSUPPORTED,
        new DrmSessionEventListener.EventDispatcher(),
        format,
        samples);
  }
}
