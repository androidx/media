/*
 * Copyright 2023 The Android Open Source Project
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
package androidx.media3.transformer;

import static androidx.media3.common.MimeTypes.VIDEO_H264;
import static androidx.media3.transformer.EditedMediaItemSequence.withAudioAndVideoFrom;
import static androidx.media3.transformer.EditedMediaItemSequence.withAudioFrom;
import static androidx.media3.transformer.MuxerWrapper.MUXER_MODE_DEFAULT;
import static androidx.media3.transformer.TestUtil.ASSET_URI_PREFIX;
import static androidx.media3.transformer.TestUtil.FILE_AUDIO_VIDEO;
import static androidx.media3.transformer.TransformerUtil.shouldTranscodeAudio;
import static androidx.media3.transformer.TransformerUtil.shouldTranscodeVideo;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Timeline;
import androidx.media3.common.audio.SpeedProvider;
import androidx.media3.common.audio.ToInt16PcmAudioProcessor;
import androidx.media3.common.util.Util;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.Presentation;
import androidx.media3.effect.ScaleAndRotateTransformation;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.test.utils.FakeTimeline;
import androidx.media3.test.utils.FakeTimeline.TimelineWindowDefinition;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

/** Unit tests for {@link TransformerUtil}. */
@RunWith(AndroidJUnit4.class)
public final class TransformerUtilTest {
  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  public static final Format FORMAT =
      new Format.Builder()
          .setSampleMimeType(VIDEO_H264)
          .setWidth(1080)
          .setHeight(720)
          .setFrameRate(29.97f)
          .setCodecs("avc1.64001F")
          .build();

  public static final SpeedProvider DOUBLE_SPEED_PROVIDER =
      new SpeedProvider() {
        @Override
        public float getSpeed(long timeUs) {
          return 2f;
        }

        @Override
        public long getNextSpeedChangeTimeUs(long timeUs) {
          return C.TIME_UNSET;
        }
      };

  @Test
  public void shouldTranscodeVideo_regularRotationAndTranscodingPresentation_returnsTrue()
      throws Exception {
    MediaItem mediaItem = MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_VIDEO);
    GlEffect regularRotation =
        new ScaleAndRotateTransformation.Builder().setRotationDegrees(90).build();
    ImmutableList<Effect> videoEffects =
        ImmutableList.of(regularRotation, Presentation.createForHeight(FORMAT.height));
    Effects effects = new Effects(/* audioProcessors= */ ImmutableList.of(), videoEffects);
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(mediaItem).setEffects(effects).build();
    Composition composition =
        new Composition.Builder(withAudioAndVideoFrom(ImmutableList.of(editedMediaItem))).build();
    MuxerWrapper muxerWrapper =
        new MuxerWrapper(
            temporaryFolder.newFile().getPath(),
            new DefaultMuxer.Factory(),
            new NoOpMuxerListenerImpl(),
            MUXER_MODE_DEFAULT,
            /* dropSamplesBeforeFirstVideoSample= */ false,
            /* appendVideoFormat= */ null);

    assertThat(
            shouldTranscodeVideo(
                FORMAT,
                composition,
                /* sequenceIndex= */ 0,
                new TransformationRequest.Builder().build(),
                new DefaultEncoderFactory.Builder(getApplicationContext()).build(),
                muxerWrapper,
                /* hasFrameProcessorFactory= */ false))
        .isTrue();
  }

  @Test
  public void shouldTranscodeVideo_irregularRotationAndPresentation_returnsTrue() throws Exception {
    MediaItem mediaItem = MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_VIDEO);
    GlEffect irregularRotation =
        new ScaleAndRotateTransformation.Builder().setRotationDegrees(45).build();
    ImmutableList<Effect> videoEffects =
        ImmutableList.of(
            irregularRotation, Presentation.createForHeight(FORMAT.height), irregularRotation);
    Effects effects = new Effects(/* audioProcessors= */ ImmutableList.of(), videoEffects);
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(mediaItem).setEffects(effects).build();
    Composition composition =
        new Composition.Builder(withAudioAndVideoFrom(ImmutableList.of(editedMediaItem))).build();
    MuxerWrapper muxerWrapper =
        new MuxerWrapper(
            temporaryFolder.newFile().getPath(),
            new DefaultMuxer.Factory(),
            new NoOpMuxerListenerImpl(),
            MUXER_MODE_DEFAULT,
            /* dropSamplesBeforeFirstVideoSample= */ false,
            /* appendVideoFormat= */ null);

    assertThat(
            shouldTranscodeVideo(
                FORMAT,
                composition,
                /* sequenceIndex= */ 0,
                new TransformationRequest.Builder().build(),
                new DefaultEncoderFactory.Builder(getApplicationContext()).build(),
                muxerWrapper,
                /* hasFrameProcessorFactory= */ false))
        .isTrue();
  }

  @Test
  public void shouldTranscodeAudio_withSpeedProvider_returnsTrue() throws Exception {
    EditedMediaItem item =
        new EditedMediaItem.Builder(MediaItem.EMPTY).setSpeed(DOUBLE_SPEED_PROVIDER).build();
    Composition composition =
        new Composition.Builder(EditedMediaItemSequence.withAudioFrom(ImmutableList.of(item)))
            .build();
    Format format =
        Util.getPcmFormat(C.ENCODING_PCM_16BIT, /* channels= */ 1, /* sampleRate= */ 44100);
    MuxerWrapper muxerWrapper =
        new MuxerWrapper(
            temporaryFolder.newFile().getPath(),
            new DefaultMuxer.Factory(),
            new NoOpMuxerListenerImpl(),
            MUXER_MODE_DEFAULT,
            /* dropSamplesBeforeFirstVideoSample= */ false,
            /* appendVideoFormat= */ null);

    assertThat(
            shouldTranscodeAudio(
                format,
                composition,
                /* sequenceIndex= */ 0,
                new TransformationRequest.Builder().build(),
                new DefaultEncoderFactory.Builder(getApplicationContext()).build(),
                muxerWrapper))
        .isTrue();
  }

  @Test
  public void shouldTranscodeAudio_withOnlyPreProcessingEffects_returnsFalse() throws Exception {
    EditedMediaItem item =
        new EditedMediaItem.Builder(MediaItem.EMPTY)
            .setPreProcessingAudioProcessors(ImmutableList.of(new ToInt16PcmAudioProcessor()))
            .build();

    Composition composition =
        new Composition.Builder(EditedMediaItemSequence.withAudioFrom(ImmutableList.of(item)))
            .build();
    Format format =
        Util.getPcmFormat(C.ENCODING_PCM_24BIT, /* channels= */ 1, /* sampleRate= */ 44100);
    MuxerWrapper muxerWrapper =
        new MuxerWrapper(
            temporaryFolder.newFile().getPath(),
            new DefaultMuxer.Factory(),
            new NoOpMuxerListenerImpl(),
            MUXER_MODE_DEFAULT,
            /* dropSamplesBeforeFirstVideoSample= */ false,
            /* appendVideoFormat= */ null);

    assertThat(
            shouldTranscodeAudio(
                format,
                composition,
                /* sequenceIndex= */ 0,
                new TransformationRequest.Builder().build(),
                new DefaultEncoderFactory.Builder(getApplicationContext()).build(),
                muxerWrapper))
        .isFalse();
  }

  @Test
  public void shouldTranscodeVideo_withPacketProcessor_returnsTrue() throws Exception {
    MediaItem mediaItem = MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_VIDEO);
    EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(mediaItem).build();
    Composition composition =
        new Composition.Builder(withAudioAndVideoFrom(ImmutableList.of(editedMediaItem))).build();
    MuxerWrapper muxerWrapper =
        new MuxerWrapper(
            temporaryFolder.newFile().getPath(),
            new DefaultMuxer.Factory(),
            new NoOpMuxerListenerImpl(),
            MUXER_MODE_DEFAULT,
            /* dropSamplesBeforeFirstVideoSample= */ false,
            /* appendVideoFormat= */ null);

    assertThat(
            shouldTranscodeVideo(
                FORMAT,
                composition,
                /* sequenceIndex= */ 0,
                new TransformationRequest.Builder().build(),
                new DefaultEncoderFactory.Builder(getApplicationContext()).build(),
                muxerWrapper,
                /* hasFrameProcessorFactory= */ true))
        .isTrue();
  }

  @Test
  public void getEditedMediaItemIndex_nonLoopingSequence_returnsPeriodIndex() {
    EditedMediaItem item1 =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_VIDEO)).build();
    EditedMediaItem item2 =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_VIDEO)).build();
    EditedMediaItemSequence sequence = withAudioFrom(ImmutableList.of(item1, item2));
    Timeline timeline =
        new CompositionPlayer.CompositionForwardingTimeline(
            new FakeTimeline(new TimelineWindowDefinition.Builder().setPeriodCount(2).build()),
            sequence);

    assertThat(
            TransformerUtil.getEditedMediaItemIndex(
                timeline, new MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0))))
        .isEqualTo(0);
    assertThat(
            TransformerUtil.getEditedMediaItemIndex(
                timeline, new MediaSource.MediaPeriodId(timeline.getUidOfPeriod(1))))
        .isEqualTo(1);
  }

  @Test
  public void getEditedMediaItemIndex_loopingSequence_returnsModuloWrappedIndex() {
    EditedMediaItem item1 =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_VIDEO)).build();
    EditedMediaItem item2 =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_VIDEO)).build();
    EditedMediaItemSequence sequence =
        new EditedMediaItemSequence.Builder(ImmutableSet.of(C.TRACK_TYPE_AUDIO))
            .addItems(ImmutableList.of(item1, item2))
            .setIsLooping(true)
            .build();
    Timeline timeline =
        new CompositionPlayer.CompositionForwardingTimeline(
            new FakeTimeline(new TimelineWindowDefinition.Builder().setPeriodCount(5).build()),
            sequence);

    // Repetitions wrap: 0, 1, 0, 1, 0
    assertThat(
            TransformerUtil.getEditedMediaItemIndex(
                timeline, new MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0))))
        .isEqualTo(0);
    assertThat(
            TransformerUtil.getEditedMediaItemIndex(
                timeline, new MediaSource.MediaPeriodId(timeline.getUidOfPeriod(1))))
        .isEqualTo(1);
    assertThat(
            TransformerUtil.getEditedMediaItemIndex(
                timeline, new MediaSource.MediaPeriodId(timeline.getUidOfPeriod(2))))
        .isEqualTo(0);
    assertThat(
            TransformerUtil.getEditedMediaItemIndex(
                timeline, new MediaSource.MediaPeriodId(timeline.getUidOfPeriod(3))))
        .isEqualTo(1);
    assertThat(
            TransformerUtil.getEditedMediaItemIndex(
                timeline, new MediaSource.MediaPeriodId(timeline.getUidOfPeriod(4))))
        .isEqualTo(0);
  }

  private static final class NoOpMuxerListenerImpl implements MuxerWrapper.Listener {

    @Override
    public void onTrackEnded(
        @C.TrackType int trackType, Format format, int averageBitrate, int sampleCount) {}

    @Override
    public void onSampleWrittenOrDropped() {}

    @Override
    public void onEnded(long approximateDurationMs) {}

    @Override
    public void onFileSizeBytesAvailable(long fileSizeBytes) {}

    @Override
    public void onError(ExportException exportException) {}
  }
}
