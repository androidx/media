/*
 * Copyright 2025 The Android Open Source Project
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

import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.test.utils.AssetInfo.MP4_ADVANCED_ASSET;
import static androidx.media3.test.utils.FormatSupportAssumptions.assumeFormatsSupported;
import static androidx.media3.transformer.AndroidTestUtil.HARDWARE_BUFFER_FRAME_PROCESSOR_MIN_SDK;
import static androidx.media3.transformer.Composition.KEY_COMPOSITION_ITEM_INDEX;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.content.Context;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.HardwareBufferFrame;
import androidx.media3.effect.Presentation;
import androidx.media3.extractor.mp4.Mp4Extractor;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.test.utils.CapturingFrameProcessor;
import androidx.media3.test.utils.CapturingFrameProcessor.EosEvent;
import androidx.media3.test.utils.CapturingFrameProcessor.Event;
import androidx.media3.test.utils.CapturingFrameProcessor.FramesEvent;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameterValuesProvider;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/**
 * End-to-end instrumentation tests for {@link Transformer} when the {@link EditedMediaItemSequence}
 * has gaps.
 */
@RunWith(TestParameterInjector.class)
public class TransformerGapsTest {
  private static final int EXPECTED_GAP_WIDTH = 16;
  private static final int EXPECTED_GAP_HEIGHT = 16;
  private static final EditedMediaItem AUDIO_VIDEO_MEDIA_ITEM =
      new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ADVANCED_ASSET.uri)).build();
  private static final EditedMediaItem AUDIO_ONLY_MEDIA_ITEM =
      AUDIO_VIDEO_MEDIA_ITEM.buildUpon().setRemoveVideo(true).build();
  private static final EditedMediaItem VIDEO_ONLY_MEDIA_ITEM =
      AUDIO_VIDEO_MEDIA_ITEM.buildUpon().setRemoveAudio(true).build();

  @Rule public final TestName testName = new TestName();

  @Rule
  public final GlFrameProcessorTestRule glFrameProcessorTestRule = new GlFrameProcessorTestRule();

  private final Context context = ApplicationProvider.getApplicationContext();

  @TestParameter(valuesProvider = UseDefaultGlFrameProcessorProvider.class)
  private boolean useDefaultGlFrameProcessor;

  private String testId;
  private CapturingFrameProcessor.@Nullable Factory capturingFrameProcessorFactory;

  @Before
  public void setUpTestId() {
    testId = testName.getMethodName();
  }

  @Test
  public void
      export_withThreeItemsHavingNoVideo_inAudioVideoSequence_insertsBlankFramesForAllItems()
          throws Exception {
    int outputWidth = 320;
    int outputHeight = 240;
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ADVANCED_ASSET.videoFormat,
        /* outputFormat= */ null);
    Transformer transformer = createTransformerBuilder().build();
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioAndVideoFrom(
                    ImmutableList.of(
                        AUDIO_ONLY_MEDIA_ITEM, AUDIO_ONLY_MEDIA_ITEM, AUDIO_ONLY_MEDIA_ITEM)))
            .setEffects(
                new Effects(
                    ImmutableList.of(),
                    ImmutableList.of(
                        Presentation.createForWidthAndHeight(
                            outputWidth, outputHeight, Presentation.LAYOUT_SCALE_TO_FIT))))
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, composition);

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), result.filePath);
    FakeTrackOutput videoTrackOutput =
        Iterables.getOnlyElement(fakeExtractorOutput.getTrackOutputsForType(C.TRACK_TYPE_VIDEO));
    // The gap is for 3 * 1024 ms with 30 fps.
    int expectedBlankFrames = 93;
    assertThat(videoTrackOutput.getSampleCount()).isEqualTo(expectedBlankFrames);
    assertQueuedGapFrames(ImmutableMap.of(0, 31, 1, 31, 2, 31));
  }

  @Test
  public void export_withThreeItemsHavingNoAudio_inAudioVideoSequence_insertsSilenceForAllItems()
      throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ADVANCED_ASSET.videoFormat,
        /* outputFormat= */ null);
    Transformer transformer = createTransformerBuilder().build();
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioAndVideoFrom(
                    ImmutableList.of(
                        VIDEO_ONLY_MEDIA_ITEM, VIDEO_ONLY_MEDIA_ITEM, VIDEO_ONLY_MEDIA_ITEM)))
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, composition);

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), result.filePath);
    FakeTrackOutput audioTrackOutput =
        Iterables.getOnlyElement(fakeExtractorOutput.getTrackOutputsForType(C.TRACK_TYPE_AUDIO));
    long lastAudioSampleTimestampUs =
        audioTrackOutput.getSampleTimeUs(audioTrackOutput.getSampleCount() - 1);
    // 3 * 1024 ms silence.
    // Since audio samples are not deterministic, hence use a lower timestamp.
    assertThat(lastAudioSampleTimestampUs).isGreaterThan(3_000_000);
  }

  @Test
  public void
      export_withThreeMediaItemsAndFirstMediaItemHavingNoVideo_inAudioVideoSequence_insertsBlankFramesForFirstMediaItem()
          throws Exception {
    int outputWidth = 320;
    int outputHeight = 240;
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ADVANCED_ASSET.videoFormat,
        /* outputFormat= */ MP4_ADVANCED_ASSET
            .videoFormat
            .buildUpon()
            .setWidth(outputWidth)
            .setHeight(outputHeight)
            .build());
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ADVANCED_ASSET.videoFormat,
        /* outputFormat= */ MP4_ADVANCED_ASSET.videoFormat);
    Transformer transformer =
        createTransformerBuilder().setVideoMimeType(MimeTypes.VIDEO_H264).build();
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioAndVideoFrom(
                    ImmutableList.of(
                        AUDIO_ONLY_MEDIA_ITEM, AUDIO_VIDEO_MEDIA_ITEM, AUDIO_VIDEO_MEDIA_ITEM)))
            .setEffects(
                new Effects(
                    ImmutableList.of(),
                    ImmutableList.of(
                        Presentation.createForWidthAndHeight(
                            outputWidth, outputHeight, Presentation.LAYOUT_SCALE_TO_FIT))))
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, composition);

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), result.filePath);
    FakeTrackOutput videoTrackOutput =
        Iterables.getOnlyElement(fakeExtractorOutput.getTrackOutputsForType(C.TRACK_TYPE_VIDEO));
    // The video gap is for 1024 ms with 30 fps.
    int expectedBlankFrames = 31;
    assertThat(videoTrackOutput.getSampleCount())
        .isEqualTo(2 * MP4_ADVANCED_ASSET.videoFrameCount + expectedBlankFrames);
    assertQueuedGapFrames(ImmutableMap.of(0, expectedBlankFrames));
  }

  @Test
  public void
      export_withThreeMediaItemsAndSecondMediaItemHavingNoVideo_inAudioVideoSequence_insertsBlankFramesForSecondMediaItem()
          throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ADVANCED_ASSET.videoFormat,
        /* outputFormat= */ null);
    Transformer transformer = createTransformerBuilder().build();
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioAndVideoFrom(
                    ImmutableList.of(
                        AUDIO_VIDEO_MEDIA_ITEM, AUDIO_ONLY_MEDIA_ITEM, AUDIO_VIDEO_MEDIA_ITEM)))
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, composition);

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), result.filePath);
    FakeTrackOutput videoTrackOutput =
        Iterables.getOnlyElement(fakeExtractorOutput.getTrackOutputsForType(C.TRACK_TYPE_VIDEO));
    // The gap is for 1024 ms with 30 fps.
    int expectedBlankFrames = 31;
    assertThat(videoTrackOutput.getSampleCount())
        .isEqualTo(2 * MP4_ADVANCED_ASSET.videoFrameCount + expectedBlankFrames);
    assertQueuedGapFrames(ImmutableMap.of(1, expectedBlankFrames));
  }

  @Test
  public void
      export_withThreeMediaItemsAndLastMediaItemHavingNoVideo_inAudioVideoSequence_insertsBlankFramesForLastMediaItem()
          throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ADVANCED_ASSET.videoFormat,
        /* outputFormat= */ null);
    Transformer transformer = createTransformerBuilder().build();
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioAndVideoFrom(
                    ImmutableList.of(
                        AUDIO_VIDEO_MEDIA_ITEM, AUDIO_VIDEO_MEDIA_ITEM, AUDIO_ONLY_MEDIA_ITEM)))
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, composition);

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), result.filePath);
    FakeTrackOutput videoTrackOutput =
        Iterables.getOnlyElement(fakeExtractorOutput.getTrackOutputsForType(C.TRACK_TYPE_VIDEO));
    // The gap is for 1024 ms with 30 fps.
    int expectedBlankFrames = 31;
    assertThat(videoTrackOutput.getSampleCount())
        .isEqualTo(2 * MP4_ADVANCED_ASSET.videoFrameCount + expectedBlankFrames);
    assertQueuedGapFrames(ImmutableMap.of(2, expectedBlankFrames));
  }

  @Test
  public void
      export_withThreeMediaItemsAndFirstMediaItemHavingNoAudio_inAudioVideoSequence_insertsSilenceForFirstMediaItem()
          throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ADVANCED_ASSET.videoFormat,
        /* outputFormat= */ null);
    Transformer transformer = createTransformerBuilder().build();
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioAndVideoFrom(
                    ImmutableList.of(
                        VIDEO_ONLY_MEDIA_ITEM, AUDIO_VIDEO_MEDIA_ITEM, AUDIO_VIDEO_MEDIA_ITEM)))
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, composition);

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), result.filePath);
    FakeTrackOutput audioTrackOutput =
        Iterables.getOnlyElement(fakeExtractorOutput.getTrackOutputsForType(C.TRACK_TYPE_AUDIO));
    long lastAudioSampleTimestampUs =
        audioTrackOutput.getSampleTimeUs(audioTrackOutput.getSampleCount() - 1);
    // 1000 ms gap + 1024 ms audio + 1024 ms audio.
    // Since audio samples are not deterministic, hence use a lower timestamp.
    assertThat(lastAudioSampleTimestampUs).isGreaterThan(3_000_000);
  }

  @Test
  public void
      export_withThreeMediaItemsAndSecondMediaItemHavingNoAudio_inAudioVideoSequence_insertsSilenceForSecondMediaItem()
          throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ADVANCED_ASSET.videoFormat,
        /* outputFormat= */ null);
    Transformer transformer = createTransformerBuilder().build();
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioAndVideoFrom(
                    ImmutableList.of(
                        AUDIO_VIDEO_MEDIA_ITEM, VIDEO_ONLY_MEDIA_ITEM, AUDIO_VIDEO_MEDIA_ITEM)))
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, composition);

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), result.filePath);
    FakeTrackOutput audioTrackOutput =
        Iterables.getOnlyElement(fakeExtractorOutput.getTrackOutputsForType(C.TRACK_TYPE_AUDIO));
    long lastAudioSampleTimestampUs =
        audioTrackOutput.getSampleTimeUs(audioTrackOutput.getSampleCount() - 1);
    // 1024 ms audio + 1000 ms gap + 1024 ms audio.
    // Since audio samples are not deterministic, hence use a lower timestamp.
    assertThat(lastAudioSampleTimestampUs).isGreaterThan(3_000_000);
  }

  @Test
  public void
      export_withThreeMediaItemsAndLastMediaItemHavingNoAudio_inAudioVideoSequence_insertsSilenceForLastMediaItem()
          throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ADVANCED_ASSET.videoFormat,
        /* outputFormat= */ null);
    Transformer transformer = createTransformerBuilder().build();
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioAndVideoFrom(
                    ImmutableList.of(
                        AUDIO_VIDEO_MEDIA_ITEM, AUDIO_VIDEO_MEDIA_ITEM, VIDEO_ONLY_MEDIA_ITEM)))
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, composition);

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), result.filePath);
    FakeTrackOutput audioTrackOutput =
        Iterables.getOnlyElement(fakeExtractorOutput.getTrackOutputsForType(C.TRACK_TYPE_AUDIO));
    long lastAudioSampleTimestampUs =
        audioTrackOutput.getSampleTimeUs(audioTrackOutput.getSampleCount() - 1);
    // 1024 ms audio + 1024 ms audio + 1000 ms gap.
    // Since audio samples are not deterministic, hence use a lower timestamp.
    assertThat(lastAudioSampleTimestampUs).isGreaterThan(3_000_000);
  }

  @Test
  public void
      export_withTwoVideoOnlyMediaItemsAndGapAtStart_inVideoOnlySequence_insertsBlankFramesForGap()
          throws Exception {
    int outputWidth = 320;
    int outputHeight = 240;
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ADVANCED_ASSET.videoFormat,
        /* outputFormat= */ MP4_ADVANCED_ASSET
            .videoFormat
            .buildUpon()
            .setWidth(outputWidth)
            .setHeight(outputHeight)
            .build());
    // The default output mime type is H265 which might not work on all the devices.
    Transformer transformer =
        createTransformerBuilder().setVideoMimeType(MimeTypes.VIDEO_H264).build();
    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence.Builder(ImmutableSet.of(C.TRACK_TYPE_VIDEO))
                    .addGap(/* durationUs= */ 1_000_000)
                    .addItem(VIDEO_ONLY_MEDIA_ITEM)
                    .addItem(VIDEO_ONLY_MEDIA_ITEM)
                    .build())
            .setEffects(
                new Effects(
                    ImmutableList.of(),
                    ImmutableList.of(
                        Presentation.createForWidthAndHeight(
                            outputWidth, outputHeight, Presentation.LAYOUT_SCALE_TO_FIT))))
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, composition);

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), result.filePath);
    FakeTrackOutput videoTrackOutput =
        Iterables.getOnlyElement(fakeExtractorOutput.getTrackOutputsForType(C.TRACK_TYPE_VIDEO));
    // The gap is for 1 sec with 30 fps.
    int expectedBlankFrames = 30;
    assertThat(videoTrackOutput.getSampleCount())
        .isEqualTo(2 * MP4_ADVANCED_ASSET.videoFrameCount + expectedBlankFrames);
    assertQueuedGapFrames(ImmutableMap.of(0, expectedBlankFrames));
  }

  @Test
  public void
      export_withTwoVideoOnlyMediaItemsAndGapInMiddle_inVideoOnlySequence_insertsBlankFramesForGap()
          throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ADVANCED_ASSET.videoFormat,
        /* outputFormat= */ MP4_ADVANCED_ASSET.videoFormat);
    Transformer transformer = createTransformerBuilder().build();
    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence.Builder(ImmutableSet.of(C.TRACK_TYPE_VIDEO))
                    .addItem(VIDEO_ONLY_MEDIA_ITEM)
                    .addGap(/* durationUs= */ 1_000_000)
                    .addItem(VIDEO_ONLY_MEDIA_ITEM)
                    .build())
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, composition);

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), result.filePath);
    FakeTrackOutput videoTrackOutput =
        Iterables.getOnlyElement(fakeExtractorOutput.getTrackOutputsForType(C.TRACK_TYPE_VIDEO));
    // The gap is for 1 sec with 30 fps.
    int expectedBlankFrames = 30;
    assertThat(videoTrackOutput.getSampleCount())
        .isEqualTo(2 * MP4_ADVANCED_ASSET.videoFrameCount + expectedBlankFrames);
    assertQueuedGapFrames(ImmutableMap.of(1, expectedBlankFrames));
  }

  @Test
  public void
      export_withTwoVideoOnlyMediaItemsAndGapAtTheEnd_inVideoOnlySequence_insertsBlankFramesForGap()
          throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ADVANCED_ASSET.videoFormat,
        /* outputFormat= */ MP4_ADVANCED_ASSET.videoFormat);
    Transformer transformer = createTransformerBuilder().build();
    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence.Builder(ImmutableSet.of(C.TRACK_TYPE_VIDEO))
                    .addItem(VIDEO_ONLY_MEDIA_ITEM)
                    .addItem(VIDEO_ONLY_MEDIA_ITEM)
                    .addGap(/* durationUs= */ 1_000_000)
                    .build())
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, composition);

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), result.filePath);
    FakeTrackOutput videoTrackOutput =
        Iterables.getOnlyElement(fakeExtractorOutput.getTrackOutputsForType(C.TRACK_TYPE_VIDEO));
    // The gap is for 1 sec with 30 fps.
    int expectedBlankFrames = 30;
    assertThat(videoTrackOutput.getSampleCount())
        .isEqualTo(2 * MP4_ADVANCED_ASSET.videoFrameCount + expectedBlankFrames);
    assertQueuedGapFrames(ImmutableMap.of(2, expectedBlankFrames));
  }

  @Test
  public void
      export_withTwoMediaItemsAndGapAtStart_inAudioVideoSequence_insertsBlankFramesAndSilenceForGap()
          throws Exception {
    int outputWidth = 320;
    int outputHeight = 240;
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ADVANCED_ASSET.videoFormat,
        /* outputFormat= */ MP4_ADVANCED_ASSET
            .videoFormat
            .buildUpon()
            .setWidth(outputWidth)
            .setHeight(outputHeight)
            .build());
    // The default output mime type is H265 which might not work on all the devices.
    Transformer transformer =
        createTransformerBuilder().setVideoMimeType(MimeTypes.VIDEO_H264).build();
    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence.Builder(
                        ImmutableSet.of(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO))
                    .addGap(/* durationUs= */ 1_000_000)
                    .addItem(AUDIO_VIDEO_MEDIA_ITEM)
                    .addItem(AUDIO_VIDEO_MEDIA_ITEM)
                    .build())
            .setEffects(
                new Effects(
                    ImmutableList.of(),
                    ImmutableList.of(
                        Presentation.createForWidthAndHeight(
                            outputWidth, outputHeight, Presentation.LAYOUT_SCALE_TO_FIT))))
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, composition);

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), result.filePath);
    FakeTrackOutput videoTrackOutput =
        Iterables.getOnlyElement(fakeExtractorOutput.getTrackOutputsForType(C.TRACK_TYPE_VIDEO));
    // The gap is for 1 sec with 30 fps.
    int expectedBlankFrames = 30;
    assertThat(videoTrackOutput.getSampleCount())
        .isEqualTo(2 * MP4_ADVANCED_ASSET.videoFrameCount + expectedBlankFrames);
    FakeTrackOutput audioTrackOutput =
        Iterables.getOnlyElement(fakeExtractorOutput.getTrackOutputsForType(C.TRACK_TYPE_AUDIO));
    long lastAudioSampleTimestampUs =
        audioTrackOutput.getSampleTimeUs(audioTrackOutput.getSampleCount() - 1);
    // 1000 ms gap + 1024 ms audio + 1024 ms audio.
    // Since audio samples are not deterministic, hence use a lower timestamp.
    assertThat(lastAudioSampleTimestampUs).isGreaterThan(3_000_000);
    assertQueuedGapFrames(ImmutableMap.of(0, expectedBlankFrames));
  }

  @Test
  public void export_withTwoMediaItemsAndGapInMiddle_inAudioVideoSequence_insertsBlankFramesForGap()
      throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ADVANCED_ASSET.videoFormat,
        /* outputFormat= */ MP4_ADVANCED_ASSET.videoFormat);
    Transformer transformer = createTransformerBuilder().build();
    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence.Builder(
                        ImmutableSet.of(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO))
                    .addItem(AUDIO_VIDEO_MEDIA_ITEM)
                    .addGap(/* durationUs= */ 1_000_000)
                    .addItem(AUDIO_VIDEO_MEDIA_ITEM)
                    .build())
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, composition);

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), result.filePath);
    FakeTrackOutput videoTrackOutput =
        Iterables.getOnlyElement(fakeExtractorOutput.getTrackOutputsForType(C.TRACK_TYPE_VIDEO));
    // The gap is for 1 sec with 30 fps.
    int expectedBlankFrames = 30;
    assertThat(videoTrackOutput.getSampleCount())
        .isEqualTo(2 * MP4_ADVANCED_ASSET.videoFrameCount + expectedBlankFrames);
    FakeTrackOutput audioTrackOutput =
        Iterables.getOnlyElement(fakeExtractorOutput.getTrackOutputsForType(C.TRACK_TYPE_AUDIO));
    long lastAudioSampleTimestampUs =
        audioTrackOutput.getSampleTimeUs(audioTrackOutput.getSampleCount() - 1);
    // 1024 ms audio + 1000 ms gap + 1024 ms audio.
    // Since audio samples are not deterministic, hence use a lower timestamp.
    assertThat(lastAudioSampleTimestampUs).isGreaterThan(3_000_000);
    assertQueuedGapFrames(ImmutableMap.of(1, expectedBlankFrames));
  }

  @Test
  public void export_withTwoMediaItemsAndGapAtTheEnd_inAudioVideoSequence_insertsBlankFramesForGap()
      throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ADVANCED_ASSET.videoFormat,
        /* outputFormat= */ MP4_ADVANCED_ASSET.videoFormat);
    Transformer transformer = createTransformerBuilder().build();
    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence.Builder(
                        ImmutableSet.of(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO))
                    .addItem(AUDIO_VIDEO_MEDIA_ITEM)
                    .addItem(AUDIO_VIDEO_MEDIA_ITEM)
                    .addGap(/* durationUs= */ 1_000_000)
                    .build())
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, composition);

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), result.filePath);
    FakeTrackOutput videoTrackOutput =
        Iterables.getOnlyElement(fakeExtractorOutput.getTrackOutputsForType(C.TRACK_TYPE_VIDEO));
    // The gap is for 1 sec with 30 fps.
    int expectedBlankFrames = 30;
    assertThat(videoTrackOutput.getSampleCount())
        .isEqualTo(2 * MP4_ADVANCED_ASSET.videoFrameCount + expectedBlankFrames);
    FakeTrackOutput audioTrackOutput =
        Iterables.getOnlyElement(fakeExtractorOutput.getTrackOutputsForType(C.TRACK_TYPE_AUDIO));
    long lastAudioSampleTimestampUs =
        audioTrackOutput.getSampleTimeUs(audioTrackOutput.getSampleCount() - 1);
    // 1024 ms audio + 1024 ms audio + 1000 ms gap.
    // Since audio samples are not deterministic, hence use a lower timestamp.
    assertThat(lastAudioSampleTimestampUs).isGreaterThan(3_000_000);
    assertQueuedGapFrames(ImmutableMap.of(2, expectedBlankFrames));
  }

  @Test
  public void export_withMixOfAudioVideoAndGap_inAudioVideoSequence_insertsBlankFramesAsExpected()
      throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ADVANCED_ASSET.videoFormat,
        /* outputFormat= */ MP4_ADVANCED_ASSET.videoFormat);
    Transformer transformer = createTransformerBuilder().build();
    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence.Builder(
                        ImmutableSet.of(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO))
                    .addItem(AUDIO_VIDEO_MEDIA_ITEM)
                    .addItem(AUDIO_ONLY_MEDIA_ITEM)
                    .addItem(VIDEO_ONLY_MEDIA_ITEM)
                    .addGap(/* durationUs= */ 1_000_000)
                    .build())
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, composition);

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), result.filePath);
    FakeTrackOutput videoTrackOutput =
        Iterables.getOnlyElement(fakeExtractorOutput.getTrackOutputsForType(C.TRACK_TYPE_VIDEO));
    // The gap is for 1024 ms with 30 fps.
    int expectedBlankFramesForAudioOnlyItem = 31;
    // The gap is for 1 sec with 30 fps.
    int expectedBlankFramesForOneSecGap = 30;
    assertThat(videoTrackOutput.getSampleCount())
        .isEqualTo(
            MP4_ADVANCED_ASSET.videoFrameCount
                + expectedBlankFramesForAudioOnlyItem
                + MP4_ADVANCED_ASSET.videoFrameCount
                + expectedBlankFramesForOneSecGap);
    FakeTrackOutput audioTrackOutput =
        Iterables.getOnlyElement(fakeExtractorOutput.getTrackOutputsForType(C.TRACK_TYPE_AUDIO));
    long lastAudioSampleTimestampUs =
        audioTrackOutput.getSampleTimeUs(audioTrackOutput.getSampleCount() - 1);
    // 1024ms audio + 1024ms audio + 1000ms silence + 1000ms gap.
    // Since audio samples are not deterministic, hence use a lower timestamp.
    assertThat(lastAudioSampleTimestampUs).isGreaterThan(4_000_000);
    assertQueuedGapFrames(
        ImmutableMap.of(
            1, expectedBlankFramesForAudioOnlyItem, 3, expectedBlankFramesForOneSecGap));
  }

  private Transformer.Builder createTransformerBuilder() {
    if (useDefaultGlFrameProcessor && SDK_INT >= HARDWARE_BUFFER_FRAME_PROCESSOR_MIN_SDK) {
      capturingFrameProcessorFactory =
          new CapturingFrameProcessor.Factory(
              glFrameProcessorTestRule.createDefaultGlFrameProcessorFactory(context));
      return glFrameProcessorTestRule
          .createTransformerBuilder(context)
          .setFrameProcessorFactory(capturingFrameProcessorFactory);
    }
    capturingFrameProcessorFactory = null;
    return new Transformer.Builder(context);
  }

  private void assertQueuedGapFrames(Map<Integer, Integer> expectedGapFrameCountsByItemIndex) {
    if (!useDefaultGlFrameProcessor || SDK_INT < HARDWARE_BUFFER_FRAME_PROCESSOR_MIN_SDK) {
      return;
    }
    CapturingFrameProcessor capturingFrameProcessor =
        checkNotNull(checkNotNull(capturingFrameProcessorFactory).getCreatedProcessor());
    assertThat(capturingFrameProcessor.isEnded()).isTrue();
    Map<Integer, Integer> actualGapFrameCountsByItemIndex = new HashMap<>();
    int frameIndex = 0;
    for (Event event : capturingFrameProcessor.getQueuedEvents()) {
      if (event instanceof EosEvent) {
        continue;
      }
      FramesEvent framesEvent = (FramesEvent) event;
      assertWithMessage("frameIndex=%s", frameIndex).that(framesEvent.frames).hasSize(1);
      Frame frame = framesEvent.frames.get(0).frame;
      assertWithMessage("frameIndex=%s", frameIndex)
          .that(frame)
          .isInstanceOf(HardwareBufferFrame.class);
      int itemIndex = (int) checkNotNull(frame.getMetadata().get(KEY_COMPOSITION_ITEM_INDEX));
      if (expectedGapFrameCountsByItemIndex.containsKey(itemIndex)) {
        assertWithMessage("itemIndex=%s, frameIndex=%s", itemIndex, frameIndex)
            .that(frame.getFormat().width)
            .isEqualTo(EXPECTED_GAP_WIDTH);
        assertWithMessage("itemIndex=%s, frameIndex=%s", itemIndex, frameIndex)
            .that(frame.getFormat().height)
            .isEqualTo(EXPECTED_GAP_HEIGHT);
        assertWithMessage("itemIndex=%s, frameIndex=%s", itemIndex, frameIndex)
            .that(frame.getFormat().sampleMimeType)
            .isEqualTo(MimeTypes.IMAGE_RAW);
        actualGapFrameCountsByItemIndex.merge(itemIndex, 1, Integer::sum);
      } else {
        assertWithMessage("itemIndex=%s, frameIndex=%s", itemIndex, frameIndex)
            .that(frame.getFormat().width)
            .isEqualTo(MP4_ADVANCED_ASSET.videoFormat.width);
        assertWithMessage("itemIndex=%s, frameIndex=%s", itemIndex, frameIndex)
            .that(frame.getFormat().height)
            .isEqualTo(MP4_ADVANCED_ASSET.videoFormat.height);
      }
      frameIndex++;
    }
    assertThat(actualGapFrameCountsByItemIndex).isEqualTo(expectedGapFrameCountsByItemIndex);
  }

  private static final class UseDefaultGlFrameProcessorProvider
      extends TestParameterValuesProvider {
    @Override
    protected ImmutableList<Boolean> provideValues(TestParameterValuesProvider.Context context) {
      // Provide true only on supported SDK levels to avoid triggering the AssumeSdkInt lint check.
      if (SDK_INT >= HARDWARE_BUFFER_FRAME_PROCESSOR_MIN_SDK) {
        return ImmutableList.of(false, true);
      }
      return ImmutableList.of(false);
    }
  }
}
