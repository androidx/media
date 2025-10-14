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

import static androidx.media3.common.C.TRACK_TYPE_AUDIO;
import static androidx.media3.common.C.TRACK_TYPE_VIDEO;
import static androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig.CODEC_INFO_RAW;
import static androidx.media3.transformer.TestUtil.ASSET_URI_PREFIX;
import static androidx.media3.transformer.TestUtil.FILE_AUDIO_ONLY;
import static androidx.media3.transformer.TestUtil.FILE_AUDIO_RAW;
import static androidx.media3.transformer.TestUtil.FILE_AUDIO_RAW_STEREO_48000KHZ;
import static androidx.media3.transformer.TestUtil.FILE_AUDIO_RAW_VIDEO;
import static androidx.media3.transformer.TestUtil.FILE_AUDIO_VIDEO;
import static androidx.media3.transformer.TestUtil.FILE_AUDIO_VIDEO_INCREASING_TIMESTAMPS_15S;
import static androidx.media3.transformer.TestUtil.FILE_VIDEO_ONLY;
import static androidx.media3.transformer.TestUtil.createAudioEffects;
import static androidx.media3.transformer.TestUtil.createVolumeScalingAudioProcessor;
import static androidx.media3.transformer.TestUtil.getCompositionDumpFilePath;
import static androidx.media3.transformer.TestUtil.getDumpFileName;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.audio.SonicAudioProcessor;
import androidx.media3.common.audio.SpeedChangingAudioProcessor;
import androidx.media3.common.audio.SpeedProvider;
import androidx.media3.test.utils.DumpFileAsserts;
import androidx.media3.test.utils.TestTransformerBuilder;
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

/**
 * End-to-end test for exporting a {@link Composition} containing multiple {@link
 * EditedMediaItemSequence} instances with {@link Transformer}.
 */
@RunWith(AndroidJUnit4.class)
public class CompositionExportTest {
  @Rule public final TemporaryFolder outputDir = new TemporaryFolder();

  private final Context context = ApplicationProvider.getApplicationContext();

  @Rule
  public ShadowMediaCodecConfig shadowMediaCodecConfig =
      ShadowMediaCodecConfig.withCodecs(
          /* decoders= */ ImmutableList.of(CODEC_INFO_RAW),
          /* encoders= */ ImmutableList.of(CODEC_INFO_RAW));

  @Test
  public void start_audioVideoTransmuxedFromDifferentSequences_matchesSingleSequenceResult()
      throws Exception {
    CapturingMuxer.Factory muxerFactory = new CapturingMuxer.Factory(/* handleAudioAsPcm= */ false);
    Transformer transformer =
        new TestTransformerBuilder(context).setMuxerFactory(muxerFactory).build();
    MediaItem mediaItem = MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_VIDEO);

    EditedMediaItem audioEditedMediaItem =
        new EditedMediaItem.Builder(mediaItem).setRemoveVideo(true).build();
    EditedMediaItem videoEditedMediaItem =
        new EditedMediaItem.Builder(mediaItem).setRemoveAudio(true).build();
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioFrom(ImmutableList.of(audioEditedMediaItem)),
                EditedMediaItemSequence.withVideoFrom(ImmutableList.of(videoEditedMediaItem)))
            .setTransmuxAudio(true)
            .setTransmuxVideo(true)
            .build();
    transformer.start(composition, outputDir.newFile().getPath());
    TransformerTestRunner.runLooper(transformer);

    DumpFileAsserts.assertOutput(
        context, muxerFactory.getCreatedMuxer(), getDumpFileName(FILE_AUDIO_VIDEO));
  }

  @Test
  public void start_loopingTransmuxedAudio_producesExpectedResult() throws Exception {
    CapturingMuxer.Factory muxerFactory = new CapturingMuxer.Factory(/* handleAudioAsPcm= */ false);
    Transformer transformer =
        new TestTransformerBuilder(context).setMuxerFactory(muxerFactory).build();
    EditedMediaItem audioEditedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_ONLY)).build();
    EditedMediaItemSequence loopingAudioSequence =
        new EditedMediaItemSequence.Builder(ImmutableSet.of(TRACK_TYPE_AUDIO))
            .addItem(audioEditedMediaItem)
            .setIsLooping(true)
            .build();
    EditedMediaItem videoEditedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_VIDEO_ONLY)).build();
    EditedMediaItemSequence videoSequence =
        EditedMediaItemSequence.withVideoFrom(
            ImmutableList.of(videoEditedMediaItem, videoEditedMediaItem, videoEditedMediaItem));
    Composition composition =
        new Composition.Builder(loopingAudioSequence, videoSequence)
            .setTransmuxAudio(true)
            .setTransmuxVideo(true)
            .build();

    transformer.start(composition, outputDir.newFile().getPath());
    ExportResult exportResult = TransformerTestRunner.runLooper(transformer);

    assertThat(exportResult.processedInputs).hasSize(6);
    DumpFileAsserts.assertOutput(
        context,
        muxerFactory.getCreatedMuxer(),
        getDumpFileName(
            FILE_AUDIO_ONLY,
            /* modifications...= */ "looping",
            "mixedWith",
            getFileName(FILE_VIDEO_ONLY)));
  }

  @Test
  public void start_loopingTransmuxedVideo_producesExpectedResult() throws Exception {
    CapturingMuxer.Factory muxerFactory = new CapturingMuxer.Factory(/* handleAudioAsPcm= */ false);
    Transformer transformer =
        new TestTransformerBuilder(context).setMuxerFactory(muxerFactory).build();
    EditedMediaItem audioEditedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_ONLY)).build();
    EditedMediaItemSequence audioSequence =
        EditedMediaItemSequence.withAudioFrom(
            ImmutableList.of(audioEditedMediaItem, audioEditedMediaItem, audioEditedMediaItem));
    EditedMediaItem videoEditedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_VIDEO_ONLY)).build();
    EditedMediaItemSequence loopingVideoSequence =
        new EditedMediaItemSequence.Builder(ImmutableSet.of(TRACK_TYPE_VIDEO))
            .addItem(videoEditedMediaItem)
            .setIsLooping(true)
            .build();
    Composition composition =
        new Composition.Builder(audioSequence, loopingVideoSequence)
            .setTransmuxAudio(true)
            .setTransmuxVideo(true)
            .build();

    transformer.start(composition, outputDir.newFile().getPath());
    ExportResult exportResult = TransformerTestRunner.runLooper(transformer);

    assertThat(exportResult.processedInputs).hasSize(7);
    DumpFileAsserts.assertOutput(
        context,
        muxerFactory.getCreatedMuxer(),
        getDumpFileName(
            FILE_VIDEO_ONLY,
            /* modifications...= */ "looping",
            "mixedWith",
            getFileName(FILE_AUDIO_ONLY)));
  }

  @Test
  public void start_longVideoCompositionWithLoopingAudio_producesExpectedResult() throws Exception {
    CapturingMuxer.Factory muxerFactory = new CapturingMuxer.Factory(/* handleAudioAsPcm= */ true);
    Transformer transformer =
        new TestTransformerBuilder(context).setMuxerFactory(muxerFactory).build();
    EditedMediaItemSequence loopingAudioSequence =
        new EditedMediaItemSequence.Builder(ImmutableSet.of(TRACK_TYPE_AUDIO))
            .addItem(
                new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW))
                    .build())
            .setIsLooping(true)
            .build();
    EditedMediaItem videoEditedMediaItem =
        new EditedMediaItem.Builder(
                MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_VIDEO_INCREASING_TIMESTAMPS_15S))
            .setRemoveAudio(true)
            .build();
    EditedMediaItemSequence videoSequence =
        EditedMediaItemSequence.withVideoFrom(
            ImmutableList.of(videoEditedMediaItem, videoEditedMediaItem));
    Composition composition =
        new Composition.Builder(loopingAudioSequence, videoSequence).setTransmuxVideo(true).build();

    transformer.start(composition, outputDir.newFile().getPath());
    ExportResult exportResult = TransformerTestRunner.runLooper(transformer);

    // TODO: b/443998866 - Use MetadataRetriever to get exact duration.
    assertThat(exportResult.approximateDurationMs).isEqualTo(31_065);
    // FILE_AUDIO_RAW duration is 1000ms. Input 32 times to cover the 31_053ms duration.
    assertThat(exportResult.processedInputs).hasSize(34);
    assertThat(exportResult.channelCount).isEqualTo(1);
    assertThat(exportResult.fileSizeBytes).isEqualTo(5_692_714);
  }

  @Test
  public void start_longerLoopingSequence_hasNonLoopingSequenceDuration() throws Exception {
    Transformer transformer = new TestTransformerBuilder(context).build();
    EditedMediaItem audioEditedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_ONLY)).build();
    EditedMediaItemSequence loopingAudioSequence =
        new EditedMediaItemSequence.Builder(ImmutableSet.of(TRACK_TYPE_AUDIO))
            .addItems(audioEditedMediaItem, audioEditedMediaItem)
            .setIsLooping(true)
            .build();
    EditedMediaItem videoEditedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_VIDEO_ONLY)).build();
    EditedMediaItemSequence videoSequence =
        EditedMediaItemSequence.withVideoFrom(ImmutableList.of(videoEditedMediaItem));
    Composition composition =
        new Composition.Builder(loopingAudioSequence, videoSequence)
            .setTransmuxAudio(true)
            .setTransmuxVideo(true)
            .build();

    transformer.start(composition, outputDir.newFile().getPath());
    ExportResult exportResult = TransformerTestRunner.runLooper(transformer);

    // TODO: b/443998866 - Use MetadataRetriever to get exact duration.
    // Video file duration is 1001 ms and audio file duration is 1044 ms.
    assertThat(exportResult.approximateDurationMs).isLessThan(1_001);
  }

  @Test
  public void start_compositionOfConcurrentAudio_isCorrect() throws Exception {
    CapturingMuxer.Factory muxerFactory = new CapturingMuxer.Factory(/* handleAudioAsPcm= */ true);
    Transformer transformer =
        new TestTransformerBuilder(context).setMuxerFactory(muxerFactory).build();
    EditedMediaItem rawAudioEditedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW)).build();
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioFrom(ImmutableList.of(rawAudioEditedMediaItem)),
                EditedMediaItemSequence.withAudioFrom(ImmutableList.of(rawAudioEditedMediaItem)))
            .build();

    transformer.start(composition, outputDir.newFile().getPath());
    ExportResult exportResult = TransformerTestRunner.runLooper(transformer);

    assertThat(exportResult.processedInputs).hasSize(2);
    DumpFileAsserts.assertOutput(
        context,
        muxerFactory.getCreatedMuxer(),
        getDumpFileName(
            FILE_AUDIO_RAW, /* modifications...= */ "mixed", getFileName(FILE_AUDIO_RAW)));
  }

  @Test
  public void start_audioVideoCompositionWithExtraAudio_isCorrect() throws Exception {
    CapturingMuxer.Factory muxerFactory = new CapturingMuxer.Factory(/* handleAudioAsPcm= */ true);
    Transformer transformer =
        new TestTransformerBuilder(context).setMuxerFactory(muxerFactory).build();
    EditedMediaItem audioVideoEditedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW_VIDEO))
            .build();
    EditedMediaItem audioEditedMediaItem =
        new EditedMediaItem.Builder(
                MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW_STEREO_48000KHZ))
            .setRemoveVideo(true)
            .build();
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioAndVideoFrom(
                    ImmutableList.of(audioVideoEditedMediaItem)),
                EditedMediaItemSequence.withAudioFrom(ImmutableList.of(audioEditedMediaItem)))
            .setTransmuxVideo(true)
            .build();

    transformer.start(composition, outputDir.newFile().getPath());
    ExportResult exportResult = TransformerTestRunner.runLooper(transformer);

    assertThat(exportResult.processedInputs).hasSize(2);
    DumpFileAsserts.assertOutput(
        context,
        muxerFactory.getCreatedMuxer(),
        getDumpFileName(
            FILE_AUDIO_RAW_VIDEO,
            /* modifications...= */ "mixed",
            getFileName(FILE_AUDIO_RAW_STEREO_48000KHZ)));
  }

  @Test
  public void start_audioVideoCompositionWithMutedAudio_matchesSingleSequence() throws Exception {
    CapturingMuxer.Factory muxerFactory = new CapturingMuxer.Factory(/* handleAudioAsPcm= */ true);
    Transformer transformer =
        new TestTransformerBuilder(context).setMuxerFactory(muxerFactory).build();
    EditedMediaItem audioVideoEditedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW_VIDEO))
            .build();
    EditedMediaItem mutedAudioEditedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW_VIDEO))
            .setEffects(createAudioEffects(createVolumeScalingAudioProcessor(0f)))
            .setRemoveVideo(true)
            .build();
    EditedMediaItemSequence loopingMutedAudioSequence =
        new EditedMediaItemSequence.Builder(ImmutableSet.of(TRACK_TYPE_AUDIO))
            .addItem(mutedAudioEditedMediaItem)
            .setIsLooping(true)
            .build();

    transformer.start(
        new Composition.Builder(
                EditedMediaItemSequence.withAudioAndVideoFrom(
                    ImmutableList.of(
                        audioVideoEditedMediaItem,
                        audioVideoEditedMediaItem,
                        audioVideoEditedMediaItem)),
                loopingMutedAudioSequence)
            .setTransmuxVideo(true)
            .build(),
        outputDir.newFile().getPath());
    TransformerTestRunner.runLooper(transformer);

    DumpFileAsserts.assertOutput(
        context,
        muxerFactory.getCreatedMuxer(),
        getDumpFileName(
            FILE_AUDIO_RAW_VIDEO, /* modifications...= */ "sequence", "repeated3Times"));
  }

  @Test
  public void start_audioVideoCompositionWithLoopingAudio_isCorrect() throws Exception {
    CapturingMuxer.Factory muxerFactory = new CapturingMuxer.Factory(/* handleAudioAsPcm= */ true);
    Transformer transformer =
        new TestTransformerBuilder(context).setMuxerFactory(muxerFactory).build();
    EditedMediaItem audioVideoEditedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW_VIDEO))
            .build();
    EditedMediaItemSequence audioVideoSequence =
        EditedMediaItemSequence.withAudioAndVideoFrom(
            ImmutableList.of(
                audioVideoEditedMediaItem, audioVideoEditedMediaItem, audioVideoEditedMediaItem));
    EditedMediaItem audioEditedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW_VIDEO))
            .setRemoveVideo(true)
            .build();
    EditedMediaItemSequence loopingAudioSequence =
        new EditedMediaItemSequence.Builder(ImmutableSet.of(TRACK_TYPE_AUDIO))
            .addItem(audioEditedMediaItem)
            .setIsLooping(true)
            .build();
    Composition composition =
        new Composition.Builder(audioVideoSequence, loopingAudioSequence)
            .setTransmuxVideo(true)
            .build();

    transformer.start(composition, outputDir.newFile().getPath());
    ExportResult exportResult = TransformerTestRunner.runLooper(transformer);

    assertThat(exportResult.processedInputs).hasSize(7);
    DumpFileAsserts.assertOutput(
        context,
        muxerFactory.getCreatedMuxer(),
        getDumpFileName(
            /* originalFileName= */ FILE_AUDIO_RAW_VIDEO,
            /* modifications...= */ "sequence",
            "repeated3Times",
            "mixed",
            "loopingAudio" + getFileName(FILE_AUDIO_RAW_VIDEO)));
  }

  @Test
  public void start_adjustSampleRateWithComposition_completesSuccessfully() throws Exception {
    CapturingMuxer.Factory muxerFactory = new CapturingMuxer.Factory(/* handleAudioAsPcm= */ true);
    SonicAudioProcessor sonicAudioProcessor = new SonicAudioProcessor();
    sonicAudioProcessor.setOutputSampleRateHz(48000);
    Transformer transformer =
        new TestTransformerBuilder(context).setMuxerFactory(muxerFactory).build();
    MediaItem mediaItem = MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW);
    EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(mediaItem).build();
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioFrom(ImmutableList.of(editedMediaItem)))
            .setEffects(createAudioEffects(sonicAudioProcessor))
            .build();

    transformer.start(composition, outputDir.newFile().getPath());
    TransformerTestRunner.runLooper(transformer);

    DumpFileAsserts.assertOutput(
        context,
        muxerFactory.getCreatedMuxer(),
        getDumpFileName(/* originalFileName= */ FILE_AUDIO_RAW, /* modifications...= */ "48000hz"));
  }

  @Test
  public void start_compositionOfConcurrentAudio_changesSampleRateWithEffect() throws Exception {
    CapturingMuxer.Factory muxerFactory = new CapturingMuxer.Factory(/* handleAudioAsPcm= */ true);
    SonicAudioProcessor sonicAudioProcessor = new SonicAudioProcessor();
    sonicAudioProcessor.setOutputSampleRateHz(48000);
    Transformer transformer =
        new TestTransformerBuilder(context).setMuxerFactory(muxerFactory).build();
    EditedMediaItem rawAudioEditedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW)).build();
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioFrom(ImmutableList.of(rawAudioEditedMediaItem)),
                EditedMediaItemSequence.withAudioFrom(ImmutableList.of(rawAudioEditedMediaItem)))
            .setEffects(createAudioEffects(sonicAudioProcessor))
            .build();

    transformer.start(composition, outputDir.newFile().getPath());
    ExportResult exportResult = TransformerTestRunner.runLooper(transformer);

    assertThat(exportResult.processedInputs).hasSize(2);
    assertThat(exportResult.sampleRate).isEqualTo(48000);
    DumpFileAsserts.assertOutput(
        context,
        muxerFactory.getCreatedMuxer(),
        getDumpFileName(
            FILE_AUDIO_RAW,
            /* modifications...= */ "mixed",
            getFileName(FILE_AUDIO_RAW),
            "48000hz"));
  }

  @Test
  public void start_firstSequenceFinishesEarly_works() throws Exception {
    CapturingMuxer.Factory muxerFactory = new CapturingMuxer.Factory(/* handleAudioAsPcm= */ true);
    Transformer transformer =
        new TestTransformerBuilder(context).setMuxerFactory(muxerFactory).build();
    EditedMediaItem audioItem300ms =
        new EditedMediaItem.Builder(
                MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW)
                    .buildUpon()
                    .setClippingConfiguration(
                        new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(100)
                            .setEndPositionMs(400)
                            .build())
                    .build())
            .build();
    EditedMediaItem audioItem1000ms =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW)).build();
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioFrom(ImmutableList.of(audioItem300ms)),
                EditedMediaItemSequence.withAudioFrom(ImmutableList.of(audioItem1000ms)))
            .build();

    transformer.start(composition, outputDir.newFile().getPath());
    TransformerTestRunner.runLooper(transformer);

    DumpFileAsserts.assertOutput(
        context,
        muxerFactory.getCreatedMuxer(),
        getCompositionDumpFilePath("seq-sample.wav+seq-sample.wav_clipped_100ms_to_400ms"));
  }

  @Test
  public void start_secondSequenceFinishesEarly_works() throws Exception {
    CapturingMuxer.Factory muxerFactory = new CapturingMuxer.Factory(/* handleAudioAsPcm= */ true);
    Transformer transformer =
        new TestTransformerBuilder(context).setMuxerFactory(muxerFactory).build();
    EditedMediaItem audioItem1000ms =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW)).build();
    EditedMediaItem audioItem300ms =
        new EditedMediaItem.Builder(
                MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW)
                    .buildUpon()
                    .setClippingConfiguration(
                        new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(100)
                            .setEndPositionMs(400)
                            .build())
                    .build())
            .build();
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioFrom(ImmutableList.of(audioItem1000ms)),
                EditedMediaItemSequence.withAudioFrom(ImmutableList.of(audioItem300ms)))
            .build();

    transformer.start(composition, outputDir.newFile().getPath());
    TransformerTestRunner.runLooper(transformer);

    DumpFileAsserts.assertOutput(
        context,
        muxerFactory.getCreatedMuxer(),
        getCompositionDumpFilePath("seq-sample.wav+seq-sample.wav_clipped_100ms_to_400ms"));
  }

  @Test
  public void start_audioCompositionWithFirstSequenceAsGap_isCorrect() throws Exception {
    CapturingMuxer.Factory muxerFactory = new CapturingMuxer.Factory(/* handleAudioAsPcm= */ true);
    Transformer transformer =
        new TestTransformerBuilder(context).setMuxerFactory(muxerFactory).build();
    EditedMediaItem audioItem1000ms =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW)).build();
    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence.Builder(ImmutableSet.of(TRACK_TYPE_AUDIO))
                    .addGap(1_000_000)
                    .build(),
                EditedMediaItemSequence.withAudioFrom(ImmutableList.of(audioItem1000ms)))
            .build();

    transformer.start(composition, outputDir.newFile().getPath());
    TransformerTestRunner.runLooper(transformer);

    // Gaps are 44.1kHz, stereo by default. Sample.wav is 44.1kHz mono, so this test needs its own
    // dump file.
    DumpFileAsserts.assertOutput(
        context,
        muxerFactory.getCreatedMuxer(),
        getCompositionDumpFilePath("seq-" + "gap_1000ms" + "+seq-" + getFileName(FILE_AUDIO_RAW)));
  }

  @Test
  public void start_audioCompositionWithFirstSequenceOffsetGap_isCorrect() throws Exception {
    CapturingMuxer.Factory muxerFactory = new CapturingMuxer.Factory(/* handleAudioAsPcm= */ true);
    Transformer transformer =
        new TestTransformerBuilder(context).setMuxerFactory(muxerFactory).build();
    EditedMediaItem audioEditedMediaItem =
        new EditedMediaItem.Builder(
                MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW_STEREO_48000KHZ))
            .build();
    EditedMediaItem otherAudioEditedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW)).build();
    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence.Builder(ImmutableSet.of(TRACK_TYPE_AUDIO))
                    .addGap(100_000)
                    .addItem(audioEditedMediaItem)
                    .build(),
                EditedMediaItemSequence.withAudioFrom(ImmutableList.of(otherAudioEditedMediaItem)))
            .build();

    transformer.start(composition, outputDir.newFile().getPath());
    ExportResult exportResult = TransformerTestRunner.runLooper(transformer);

    assertThat(exportResult.processedInputs).hasSize(3);
    DumpFileAsserts.assertOutput(
        context,
        muxerFactory.getCreatedMuxer(),
        getCompositionDumpFilePath(
            "seq-"
                + "gap_100ms-"
                + getFileName(FILE_AUDIO_RAW_STEREO_48000KHZ)
                + "+seq-"
                + getFileName(FILE_AUDIO_RAW)));
  }

  @Test
  public void start_audioCompositionWithFirstSequencePaddingGap_isCorrect() throws Exception {
    CapturingMuxer.Factory muxerFactory = new CapturingMuxer.Factory(/* handleAudioAsPcm= */ true);
    Transformer transformer =
        new TestTransformerBuilder(context).setMuxerFactory(muxerFactory).build();
    EditedMediaItem audioItem300ms =
        new EditedMediaItem.Builder(
                MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW)
                    .buildUpon()
                    .setClippingConfiguration(
                        new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(100)
                            .setEndPositionMs(400)
                            .build())
                    .build())
            .build();
    EditedMediaItem audioItem1000ms =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW)).build();
    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence.Builder(ImmutableSet.of(TRACK_TYPE_AUDIO))
                    .addItem(audioItem300ms)
                    .addGap(700_000)
                    .build(),
                EditedMediaItemSequence.withAudioFrom(ImmutableList.of(audioItem1000ms)))
            .build();

    transformer.start(composition, outputDir.newFile().getPath());
    TransformerTestRunner.runLooper(transformer);

    DumpFileAsserts.assertOutput(
        context,
        muxerFactory.getCreatedMuxer(),
        getCompositionDumpFilePath(
            "seq-"
                + getFileName(FILE_AUDIO_RAW)
                + "+seq-"
                + getFileName(FILE_AUDIO_RAW)
                + "_clipped100msTo400ms-gap_700ms"));
  }

  @Test
  public void start_audioVideoCompositionWithSecondSequenceOffsetGap_isCorrect() throws Exception {
    CapturingMuxer.Factory muxerFactory = new CapturingMuxer.Factory(/* handleAudioAsPcm= */ true);
    Transformer transformer =
        new TestTransformerBuilder(context).setMuxerFactory(muxerFactory).build();
    EditedMediaItem audioVideoEditedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW_VIDEO))
            .build();
    EditedMediaItem audioEditedMediaItem =
        new EditedMediaItem.Builder(
                MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW_STEREO_48000KHZ))
            .setRemoveVideo(true)
            .build();
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioAndVideoFrom(
                    ImmutableList.of(audioVideoEditedMediaItem)),
                new EditedMediaItemSequence.Builder(ImmutableSet.of(TRACK_TYPE_AUDIO))
                    .addGap(200_000)
                    .addItem(audioEditedMediaItem)
                    .build())
            .setTransmuxVideo(true)
            .build();

    transformer.start(composition, outputDir.newFile().getPath());
    ExportResult exportResult = TransformerTestRunner.runLooper(transformer);

    assertThat(exportResult.processedInputs).hasSize(3);
    DumpFileAsserts.assertOutput(
        context,
        muxerFactory.getCreatedMuxer(),
        getCompositionDumpFilePath(
            "seq-"
                + getFileName(FILE_AUDIO_RAW_VIDEO)
                + "+seq-gap_200ms-"
                + getFileName(FILE_AUDIO_RAW_STEREO_48000KHZ)));
  }

  @Test
  public void start_audioVideoCompositionWithSecondSequenceIntervalGap_isCorrect()
      throws Exception {
    CapturingMuxer.Factory muxerFactory = new CapturingMuxer.Factory(/* handleAudioAsPcm= */ true);
    Transformer transformer =
        new TestTransformerBuilder(context).setMuxerFactory(muxerFactory).build();
    EditedMediaItem audioVideoEditedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW_VIDEO))
            .build();
    EditedMediaItem audio300msEditedMediaItem =
        new EditedMediaItem.Builder(
                new MediaItem.Builder()
                    .setUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW_STEREO_48000KHZ)
                    .setClippingConfiguration(
                        new MediaItem.ClippingConfiguration.Builder().setEndPositionMs(300).build())
                    .build())
            .setRemoveVideo(true)
            .build();
    EditedMediaItem audio500msEditedMediaItem =
        new EditedMediaItem.Builder(
                new MediaItem.Builder()
                    .setUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW_STEREO_48000KHZ)
                    .setClippingConfiguration(
                        new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(300)
                            .setEndPositionMs(800)
                            .build())
                    .build())
            .setRemoveVideo(true)
            .build();
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioAndVideoFrom(
                    ImmutableList.of(audioVideoEditedMediaItem)),
                new EditedMediaItemSequence.Builder(ImmutableSet.of(TRACK_TYPE_AUDIO))
                    .addItem(audio300msEditedMediaItem)
                    .addGap(200_000)
                    .addItem(audio500msEditedMediaItem)
                    .build())
            .setTransmuxVideo(true)
            .build();

    transformer.start(composition, outputDir.newFile().getPath());
    ExportResult exportResult = TransformerTestRunner.runLooper(transformer);

    assertThat(exportResult.processedInputs).hasSize(4);
    DumpFileAsserts.assertOutput(
        context,
        muxerFactory.getCreatedMuxer(),
        getCompositionDumpFilePath(
            "seq-"
                + getFileName(FILE_AUDIO_RAW_VIDEO)
                + "+seq-"
                + getFileName(FILE_AUDIO_RAW_STEREO_48000KHZ)
                + "_clipped0msTo300ms-"
                + "gap_200ms-"
                + getFileName(FILE_AUDIO_RAW_STEREO_48000KHZ)
                + "_clipped300msTo800ms"));
  }

  @Test
  public void start_audioVideoCompositionWithSecondSequencePaddingGap_isCorrect() throws Exception {
    CapturingMuxer.Factory muxerFactory = new CapturingMuxer.Factory(/* handleAudioAsPcm= */ true);
    Transformer transformer =
        new TestTransformerBuilder(context).setMuxerFactory(muxerFactory).build();
    EditedMediaItem audioVideoEditedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW_VIDEO))
            .build();
    EditedMediaItem audioEditedMediaItem =
        new EditedMediaItem.Builder(
                MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW_STEREO_48000KHZ))
            .setRemoveVideo(true)
            .build();
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioAndVideoFrom(
                    ImmutableList.of(audioVideoEditedMediaItem)),
                new EditedMediaItemSequence.Builder(ImmutableSet.of(TRACK_TYPE_AUDIO))
                    .addItem(audioEditedMediaItem)
                    .addGap(100_000)
                    .build())
            .setTransmuxVideo(true)
            .build();

    transformer.start(composition, outputDir.newFile().getPath());
    ExportResult exportResult = TransformerTestRunner.runLooper(transformer);

    assertThat(exportResult.processedInputs).hasSize(3);
    DumpFileAsserts.assertOutput(
        context,
        muxerFactory.getCreatedMuxer(),
        getCompositionDumpFilePath(
            "seq-"
                + getFileName(FILE_AUDIO_RAW_VIDEO)
                + "+seq-"
                + getFileName(FILE_AUDIO_RAW_STEREO_48000KHZ)
                + "-gap_100ms"));
  }

  @Test
  public void start_audioCompositionWithSecondSequenceAsGap_isCorrect() throws Exception {
    CapturingMuxer.Factory muxerFactory = new CapturingMuxer.Factory(/* handleAudioAsPcm= */ true);
    Transformer transformer =
        new TestTransformerBuilder(context).setMuxerFactory(muxerFactory).build();
    EditedMediaItem audioItem1000ms =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW)).build();
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioFrom(ImmutableList.of(audioItem1000ms)),
                new EditedMediaItemSequence.Builder(ImmutableSet.of(TRACK_TYPE_AUDIO))
                    .addGap(1_000_000)
                    .build())
            .build();

    transformer.start(composition, outputDir.newFile().getPath());
    TransformerTestRunner.runLooper(transformer);
    DumpFileAsserts.assertOutput(
        context,
        muxerFactory.getCreatedMuxer(),
        getCompositionDumpFilePath("seq-" + getFileName(FILE_AUDIO_RAW) + "+seq-gap_1000ms"));
  }

  @Test
  public void start_audioCompositionWithBothSequencesAsGaps_isCorrect() throws Exception {
    CapturingMuxer.Factory muxerFactory = new CapturingMuxer.Factory(/* handleAudioAsPcm= */ true);
    Transformer transformer =
        new TestTransformerBuilder(context).setMuxerFactory(muxerFactory).build();
    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence.Builder(ImmutableSet.of(TRACK_TYPE_AUDIO))
                    .addGap(500_000)
                    .build(),
                new EditedMediaItemSequence.Builder(ImmutableSet.of(TRACK_TYPE_AUDIO))
                    .addGap(500_000)
                    .build())
            .build();

    transformer.start(composition, outputDir.newFile().getPath());
    TransformerTestRunner.runLooper(transformer);

    DumpFileAsserts.assertOutput(
        context, muxerFactory.getCreatedMuxer(), getDumpFileName("gap", "500ms"));
  }

  @Test
  public void resume_withSpeedChangingEffects_throws() {
    SpeedProvider provider =
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
    Effects speedChangingEffects =
        new Effects(
            ImmutableList.of(new SpeedChangingAudioProcessor(provider)), ImmutableList.of());
    EditedMediaItem item = new EditedMediaItem.Builder(MediaItem.EMPTY).build();
    EditedMediaItem itemWithSpeedProvider = item.buildUpon().setSpeed(provider).build();
    Transformer transformer = new Transformer.Builder(context).build();
    EditedMediaItem itemWithEffects = item.buildUpon().setEffects(speedChangingEffects).build();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            transformer.resume(
                new Composition.Builder(
                        new EditedMediaItemSequence.Builder(itemWithSpeedProvider).build())
                    .build(),
                /* outputFilePath= */ "fakePath",
                /* oldFilePath= */ "fakePath"));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            transformer.resume(
                new Composition.Builder(
                        new EditedMediaItemSequence.Builder(itemWithEffects).build())
                    .build(),
                /* outputFilePath= */ "fakePath",
                /* oldFilePath= */ "fakePath"));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            transformer.resume(
                new Composition.Builder(new EditedMediaItemSequence.Builder(item).build())
                    .setEffects(speedChangingEffects)
                    .build(),
                /* outputFilePath= */ "fakePath",
                /* oldFilePath= */ "fakePath"));
  }

  private static String getFileName(String filePath) {
    int lastSeparator = filePath.lastIndexOf("/");
    return filePath.substring(lastSeparator + 1);
  }
}
