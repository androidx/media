/*
 * Copyright 2025 The Android Open Source Project
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

package androidx.media3.transformer;

import static androidx.media3.test.utils.AssetInfo.MP4_VISUAL_TIMESTAMPS;
import static androidx.media3.test.utils.FormatSupportAssumptions.assumeFormatsSupported;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.net.Uri;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.effect.Presentation;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.io.File;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/** Android tests for MP4 edit list trimming in {@link Transformer}. */
@RunWith(AndroidJUnit4.class)
public class TransformerMp4EditListTrimAndroidTest {

  private final Context context = ApplicationProvider.getApplicationContext();
  @Rule public final TestName testName = new TestName();

  private String testId;

  @Before
  public void setUpTestId() {
    testId = testName.getMethodName();
  }

  @Test
  public void trimAndExport_withVideoEffectAndMp4EditListTrimEnabled_throws() throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_VISUAL_TIMESTAMPS.videoFormat,
        /* outputFormat= */ MP4_VISUAL_TIMESTAMPS.videoFormat);
    Transformer transformer =
        new Transformer.Builder(context)
            .experimentalSetMp4EditListTrimEnabled(true)
            .setMuxerFactory(new InAppMp4Muxer.Factory())
            .build();
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(MP4_VISUAL_TIMESTAMPS.uri)
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(500)
                    .setEndPositionMs(2500)
                    .build())
            .build();
    ImmutableList<Effect> videoEffects = ImmutableList.of(Presentation.createForHeight(480));
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(mediaItem)
            .setEffects(new Effects(/* audioProcessors= */ ImmutableList.of(), videoEffects))
            .build();

    assertThrows(
        ExportException.class,
        () ->
            new TransformerAndroidTestRunner.Builder(context, transformer)
                .build()
                .run(testId, editedMediaItem));
  }

  @Test
  public void trimFromStartAndExport_withVideoEffectAndMp4EditListTrimEnabled_throws()
      throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_VISUAL_TIMESTAMPS.videoFormat,
        /* outputFormat= */ MP4_VISUAL_TIMESTAMPS.videoFormat);
    Transformer transformer =
        new Transformer.Builder(context)
            .experimentalSetMp4EditListTrimEnabled(true)
            .setMuxerFactory(new InAppMp4Muxer.Factory())
            .build();
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(MP4_VISUAL_TIMESTAMPS.uri)
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder().setEndPositionMs(2500).build())
            .build();
    ImmutableList<Effect> videoEffects = ImmutableList.of(Presentation.createForHeight(480));
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(mediaItem)
            .setEffects(new Effects(/* audioProcessors= */ ImmutableList.of(), videoEffects))
            .build();

    assertThrows(
        ExportException.class,
        () ->
            new TransformerAndroidTestRunner.Builder(context, transformer)
                .build()
                .run(testId, editedMediaItem));
  }

  @Test
  public void exportWithoutTrimming_withVideoEffectAndMp4EditListTrimEnabled_transcodes()
      throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_VISUAL_TIMESTAMPS.videoFormat,
        /* outputFormat= */ MP4_VISUAL_TIMESTAMPS.videoFormat);
    Transformer transformer =
        new Transformer.Builder(context)
            .setMuxerFactory(new InAppMp4Muxer.Factory())
            .experimentalSetMp4EditListTrimEnabled(true)
            .build();
    MediaItem mediaItem =
        new MediaItem.Builder().setUri(Uri.parse(MP4_VISUAL_TIMESTAMPS.uri)).build();
    ImmutableList<Effect> videoEffects = ImmutableList.of(Presentation.createForHeight(480));
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(mediaItem)
            .setEffects(new Effects(/* audioProcessors= */ ImmutableList.of(), videoEffects))
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, editedMediaItem);

    // TODO: b/443998866 - Use MetadataRetriever to get exact duration.
    // The final PTS is at 10.007 sec.
    assertThat(result.exportResult.approximateDurationMs).isWithin(100).of(10_007);
    assertThat(new File(result.filePath).length()).isGreaterThan(0);
    assertThat(result.exportResult.videoEncoderName).isNotNull();
    assertThat(result.exportResult.audioEncoderName).isNull();
  }

  @Test
  public void trimAndExport_multiAssetSequenceWithMp4EditListTrimEnabled_transcodes()
      throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_VISUAL_TIMESTAMPS.videoFormat,
        /* outputFormat= */ MP4_VISUAL_TIMESTAMPS.videoFormat);
    Transformer transformer =
        new Transformer.Builder(context)
            .experimentalSetMp4EditListTrimEnabled(true)
            .setMuxerFactory(new InAppMp4Muxer.Factory())
            .build();
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(Uri.parse(MP4_VISUAL_TIMESTAMPS.uri))
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(500)
                    .setEndPositionMs(2500)
                    .build())
            .build();
    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence.Builder(
                        new EditedMediaItem.Builder(mediaItem).build(),
                        new EditedMediaItem.Builder(mediaItem).build())
                    .build())
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, composition);

    // TODO: b/443998866 - Use MetadataRetriever to get exact duration.
    assertThat(result.exportResult.approximateDurationMs).isWithin(100).of(3_970);
    assertThat(new File(result.filePath).length()).isGreaterThan(0);
    assertThat(result.exportResult.videoEncoderName).isNotNull();
    assertThat(result.exportResult.audioEncoderName).isNotNull();
  }

  @Test
  public void trimAndExport_multiSequenceCompositionWithMp4EditListTrimEnabled_transcodes()
      throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_VISUAL_TIMESTAMPS.videoFormat,
        /* outputFormat= */ MP4_VISUAL_TIMESTAMPS.videoFormat);
    Transformer transformer =
        new Transformer.Builder(context)
            .experimentalSetMp4EditListTrimEnabled(true)
            .setMuxerFactory(new InAppMp4Muxer.Factory())
            .build();
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(Uri.parse(MP4_VISUAL_TIMESTAMPS.uri))
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(500)
                    .setEndPositionMs(2500)
                    .build())
            .build();
    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence.Builder(new EditedMediaItem.Builder(mediaItem).build())
                    .build(),
                new EditedMediaItemSequence.Builder(new EditedMediaItem.Builder(mediaItem).build())
                    .build())
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, composition);

    // TODO: b/443998866 - Use MetadataRetriever to get exact duration.
    assertThat(result.exportResult.approximateDurationMs).isWithin(100).of(1_973);
    assertThat(new File(result.filePath).length()).isGreaterThan(0);
    assertThat(result.exportResult.videoEncoderName).isNotNull();
    assertThat(result.exportResult.audioEncoderName).isNotNull();
  }
}
