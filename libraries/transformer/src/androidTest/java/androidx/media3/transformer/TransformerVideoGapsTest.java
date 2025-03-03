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

import static androidx.media3.transformer.AndroidTestUtil.MP4_ASSET;
import static androidx.media3.transformer.AndroidTestUtil.assumeFormatsSupported;
import static androidx.media3.transformer.AndroidTestUtil.getVideoTrackOutput;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import androidx.media3.common.MediaItem;
import androidx.media3.extractor.mp4.Mp4Extractor;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/**
 * End-to-end instrumentation tests for {@link Transformer} when the {@link EditedMediaItemSequence}
 * has video gaps.
 */
@RunWith(AndroidJUnit4.class)
public class TransformerVideoGapsTest {
  private static final EditedMediaItem AUDIO_VIDEO_MEDIA_ITEM =
      new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET.uri)).build();
  private static final EditedMediaItem AUDIO_ONLY_MEDIA_ITEM =
      AUDIO_VIDEO_MEDIA_ITEM.buildUpon().setRemoveVideo(true).build();

  private final Context context = ApplicationProvider.getApplicationContext();
  @Rule public final TestName testName = new TestName();

  private String testId;

  @Before
  public void setUpTestId() {
    testId = testName.getMethodName();
  }

  // TODO: b/391111085 - Change test when gaps at the start of the sequence are supported.
  @Test
  public void export_withThreeMediaItemsAndFirstMediaItemHavingNoVideo_throws() {
    Transformer transformer = new Transformer.Builder(context).build();
    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence.Builder(
                        AUDIO_ONLY_MEDIA_ITEM, AUDIO_VIDEO_MEDIA_ITEM, AUDIO_VIDEO_MEDIA_ITEM)
                    .build())
            .build();
    TransformerAndroidTestRunner transformerAndroidTestRunner =
        new TransformerAndroidTestRunner.Builder(context, transformer).build();

    assertThrows(
        ExportException.class, () -> transformerAndroidTestRunner.run(testId, composition));
  }

  @Test
  public void
      export_withThreeMediaItemsAndSecondMediaItemHavingNoVideo_insertsBlankFramesForSecondMediaItem()
          throws Exception {
    assumeFormatsSupported(
        context, testId, /* inputFormat= */ MP4_ASSET.videoFormat, /* outputFormat= */ null);
    Transformer transformer = new Transformer.Builder(context).build();
    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence.Builder(
                        AUDIO_VIDEO_MEDIA_ITEM, AUDIO_ONLY_MEDIA_ITEM, AUDIO_VIDEO_MEDIA_ITEM)
                    .build())
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, composition);

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), result.filePath);
    FakeTrackOutput videoTrackOutput = getVideoTrackOutput(fakeExtractorOutput);
    // The gap is for 1024ms with 30 fps.
    int expectedBlankFrames = 31;
    assertThat(videoTrackOutput.getSampleCount())
        .isEqualTo(2 * MP4_ASSET.videoFrameCount + expectedBlankFrames);
  }

  @Test
  public void
      export_withThreeMediaItemsAndLastMediaItemHavingNoVideo_insertsBlankFramesForLastMediaItem()
          throws Exception {
    assumeFormatsSupported(
        context, testId, /* inputFormat= */ MP4_ASSET.videoFormat, /* outputFormat= */ null);
    Transformer transformer = new Transformer.Builder(context).build();
    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence.Builder(
                        AUDIO_VIDEO_MEDIA_ITEM, AUDIO_VIDEO_MEDIA_ITEM, AUDIO_ONLY_MEDIA_ITEM)
                    .build())
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, composition);

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), result.filePath);
    FakeTrackOutput videoTrackOutput = getVideoTrackOutput(fakeExtractorOutput);
    // The gap is for 1024ms with 30 fps.
    int expectedBlankFrames = 31;
    assertThat(videoTrackOutput.getSampleCount())
        .isEqualTo(2 * MP4_ASSET.videoFrameCount + expectedBlankFrames);
  }
}
