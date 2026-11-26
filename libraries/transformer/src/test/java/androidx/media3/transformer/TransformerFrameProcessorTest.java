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
package androidx.media3.transformer;

import static androidx.media3.common.C.TRACK_TYPE_AUDIO;
import static androidx.media3.common.C.TRACK_TYPE_VIDEO;
import static androidx.media3.test.utils.AssetInfo.MP4_SIMPLE_ASSET;
import static androidx.media3.test.utils.TestUtil.extractAllSamplesFromFile;
import static androidx.media3.transformer.TestUtil.ASSET_URI_PREFIX;
import static androidx.media3.transformer.TestUtil.FILE_AUDIO_VIDEO_INCREASING_TIMESTAMPS_15S;
import static androidx.media3.transformer.TestUtil.extractAllSamplesFromMp4File;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.os.Looper;
import androidx.media3.common.MediaItem;
import androidx.media3.common.video.FrameProcessor;
import androidx.media3.extractor.mp4.Mp4Extractor;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeFrameProcessor;
import androidx.media3.test.utils.FakeHardwareBufferJniWrapper;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.media3.test.utils.TestTransformerBuilder;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

/** Unit tests for {@link Transformer} and {@link FrameProcessor} integration. */
@RunWith(AndroidJUnit4.class)
public final class TransformerFrameProcessorTest {

  @Rule public final TemporaryFolder outputDir = new TemporaryFolder();

  private final Context context = ApplicationProvider.getApplicationContext();

  @Test
  public void
      start_withFrameProcessorFactoryAndHdrModeToneMapUsingMediaCodec_throwsIllegalStateException()
          throws Exception {
    Transformer transformer =
        new Transformer.Builder(context)
            .setNativeHardwareBufferHelpers(new FakeHardwareBufferJniWrapper())
            .setFrameProcessorFactory(
                new FakeFrameProcessor.Factory(/* shouldCompleteIncomingFrames= */ true))
            .build();
    MediaItem mediaItem = MediaItem.fromUri(MP4_SIMPLE_ASSET.uri);
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioAndVideoFrom(
                    ImmutableList.of(new EditedMediaItem.Builder(mediaItem).build())))
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC)
            .build();
    String outputFilePath = outputDir.newFile().getPath();

    assertThrows(IllegalStateException.class, () -> transformer.start(composition, outputFilePath));
  }

  @Test
  public void resume_withFrameProcessorFactory_remuxesPartialOutputWithoutInvokingFrameProcessor()
      throws Exception {
    AtomicReference<ExportResult> exportResultRef = new AtomicReference<>();
    FakeFrameProcessor.Factory fakeFrameProcessorFactory =
        new FakeFrameProcessor.Factory(/* shouldCompleteIncomingFrames= */ true);
    // The previously exported file is remuxed using a default asset loader. Make the second stage
    // (processing the remaining video) fail on purpose so the test only checks the remux stage.
    AssetLoader.Factory throwingAssetLoaderFactory =
        new AssetLoader.Factory() {
          @Override
          public AssetLoader createAssetLoader(
              EditedMediaItem editedMediaItem,
              Looper looper,
              AssetLoader.Listener listener,
              AssetLoader.CompositionSettings compositionSettings) {
            throw new IllegalStateException("Asset loader creation failed.");
          }
        };
    Transformer transformer =
        new TestTransformerBuilder(context)
            .setAssetLoaderFactory(throwingAssetLoaderFactory)
            .addListener(
                new Transformer.Listener() {
                  @Override
                  public void onError(
                      Composition composition,
                      ExportResult exportResult,
                      ExportException exportException) {
                    exportResultRef.set(exportResult);
                  }
                })
            .build()
            .buildUpon()
            .setFrameProcessorFactory(fakeFrameProcessorFactory)
            .setNativeHardwareBufferHelpers(new FakeHardwareBufferJniWrapper())
            .build();
    String oldFilePath = ASSET_URI_PREFIX + FILE_AUDIO_VIDEO_INCREASING_TIMESTAMPS_15S;
    MediaItem mediaItem = MediaItem.fromUri(oldFilePath);
    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence.Builder(
                        ImmutableSet.of(TRACK_TYPE_AUDIO, TRACK_TYPE_VIDEO))
                    .addItem(new EditedMediaItem.Builder(mediaItem).build())
                    .build())
            .build();
    String outputFilePath = outputDir.newFile().getPath();

    transformer.resume(composition, outputFilePath, oldFilePath);
    ExportException exportException =
        assertThrows(ExportException.class, () -> TransformerTestRunner.runLooper(transformer));

    assertThat(fakeFrameProcessorFactory.createdProcessor).isNull();
    assertThat(exportException).hasCauseThat().isInstanceOf(IllegalStateException.class);
    ExportResult exportResult = checkNotNull(exportResultRef.get());
    assertThat(exportResult.processedInputs).hasSize(1);
    assertThat(
            checkNotNull(exportResult.processedInputs.get(0).mediaItem.localConfiguration)
                .uri
                .toString())
        .isEqualTo(oldFilePath);
    assertThat(exportResult.processedInputs.get(0).videoDecoderName).isNull();
    assertThat(exportResult.processedInputs.get(0).audioDecoderName).isNull();
    assertThat(exportResult.videoEncoderName).isNull();
    assertThat(exportResult.videoFrameCount).isGreaterThan(0);
    // When stage 2 fails, ResumedExportOperation releases the partial muxer with
    // MUXER_RELEASE_REASON_CANCELLED in ComponentListener.onReleased, which still calls
    // muxer.close() and writes a readable MP4 to outputFilePath.
    FakeExtractorOutput extractorOutput = extractAllSamplesFromMp4File(outputFilePath);
    FakeTrackOutput videoTrackOutput =
        getOnlyElement(extractorOutput.getTrackOutputsForType(TRACK_TYPE_VIDEO));
    assertThat(videoTrackOutput.getSampleCount()).isEqualTo(exportResult.videoFrameCount);
    assertThat(extractorOutput.getTrackOutputsForType(TRACK_TYPE_AUDIO)).isEmpty();
    FakeExtractorOutput inputExtractorOutput =
        extractAllSamplesFromFile(
            new Mp4Extractor(new DefaultSubtitleParserFactory()),
            context,
            "media/" + FILE_AUDIO_VIDEO_INCREASING_TIMESTAMPS_15S);
    FakeTrackOutput inputVideoTrackOutput =
        getOnlyElement(inputExtractorOutput.getTrackOutputsForType(TRACK_TYPE_VIDEO));
    for (int i = 0; i < exportResult.videoFrameCount; i++) {
      assertWithMessage("video sample %s timestamp", i)
          .that(videoTrackOutput.getSampleTimeUs(i))
          .isEqualTo(inputVideoTrackOutput.getSampleTimeUs(i));
      assertWithMessage("video sample %s data", i)
          .that(videoTrackOutput.getSampleData(i))
          .isEqualTo(inputVideoTrackOutput.getSampleData(i));
    }
  }
}
