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
package androidx.media3.effect.playservices.mh;

import static androidx.media3.test.utils.AssetInfo.MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S;
import static androidx.media3.test.utils.BitmapPixelTestUtil.maybeSaveTestBitmap;
import static androidx.media3.test.utils.BitmapPixelTestUtil.readBitmap;
import static androidx.media3.test.utils.FormatSupportAssumptions.assumeFormatsSupported;
import static androidx.media3.test.utils.TestUtil.PSNR_THRESHOLD;
import static androidx.media3.test.utils.TestUtil.assertBitmapsAreSimilar;
import static androidx.media3.test.utils.TestUtil.extractAllSamplesFromFilePath;
import static androidx.media3.test.utils.TestUtil.retrieveTrackFormat;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.DefaultHardwareBufferFrame;
import androidx.media3.common.video.FrameProcessor;
import androidx.media3.effect.DefaultGlFrameProcessor;
import androidx.media3.effect.HardwareBufferJni;
import androidx.media3.effect.playservices.EnhancementSessionDecorator;
import androidx.media3.extractor.mp4.Mp4Extractor;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.inspector.frame.FrameExtractor;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeFrameProcessor;
import androidx.media3.test.utils.FakeFrameWriter;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.gms.media.effect.enhancement.Enhancement;
import com.google.android.gms.tasks.Tasks;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.SettableFuture;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/** Instrumentation tests for {@link EnhancementSessionDecorator} using Google Play services. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 33)
public final class EnhancementSessionDecoratorAndroidTest {

  private static final int WIDTH = 128;
  private static final int HEIGHT = 128;
  private static final long SESSION_TIMEOUT_SECONDS = 30;
  private static final String GOLDEN_IMAGE_PATH =
      "media/mp4/sample_with_increasing_timestamps_320w_240h_5s_play_services_upscaled_000.png";
  private static final int EXPECTED_FRAME_COUNT = 84;

  @Rule public final TestName testName = new TestName();
  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private List<HardwareBuffer> buffersToClose;
  private Context context;
  private File outputVideoFile;
  private FrameProcessor.Factory baseGlFactory;
  private FakeFrameWriter fakeDownstreamOutput;
  private FakeFrameProcessor.Listener testListener;
  @Nullable private FrameProcessor decorator;
  @Nullable private Transformer transformer;

  @Before
  public void setUp() throws Exception {
    buffersToClose = new ArrayList<>();
    context = getApplicationContext();
    outputVideoFile = temporaryFolder.newFile();
    baseGlFactory = new DefaultGlFrameProcessor.Factory.Builder(context).build();
    fakeDownstreamOutput = new FakeFrameWriter();
    testListener = new FakeFrameProcessor.Listener();
  }

  @After
  public void tearDown() throws Exception {
    if (transformer != null) {
      InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> transformer.cancel());
    }
    if (decorator != null) {
      decorator.close();
    }
    for (HardwareBuffer buffer : buffersToClose) {
      buffer.close();
    }
    buffersToClose.clear();
  }

  @Test
  public void queue_withPlayServicesSession_producesOutput() throws Exception {
    assumeDeviceSupported();
    setUpPlayServicesDecorator(/* isUpscaleVideoEnabled= */ false);
    AsyncFrame inputFrame = new AsyncFrame(createHardwareBufferFrame(), /* acquireFence= */ null);

    assertThat(decorator.queue(ImmutableList.of(inputFrame))).isTrue();

    assertThat(fakeDownstreamOutput.frameLatch.await(SESSION_TIMEOUT_SECONDS, SECONDS)).isTrue();
    assertThat(fakeDownstreamOutput.queuedFrames).hasSize(1);
    assertThat(testListener.error.get()).isNull();
  }

  @Test
  public void queue_withUpscaleEnabled_configuresDoubleOutputSize() throws Exception {
    assumeDeviceSupported();
    setUpPlayServicesDecorator(/* isUpscaleVideoEnabled= */ true);
    AsyncFrame inputFrame = new AsyncFrame(createHardwareBufferFrame(), /* acquireFence= */ null);

    assertThat(decorator.queue(ImmutableList.of(inputFrame))).isTrue();

    assertThat(fakeDownstreamOutput.frameLatch.await(SESSION_TIMEOUT_SECONDS, SECONDS)).isTrue();
    assertThat(fakeDownstreamOutput.configuredFormat).isNotNull();
    assertThat(fakeDownstreamOutput.configuredFormat.width).isEqualTo(WIDTH * 2);
    assertThat(fakeDownstreamOutput.configuredFormat.height).isEqualTo(HEIGHT * 2);
  }

  @Test
  public void
      exportWithTransformer_decoratingDefaultGlFrameProcessor_producesExpectedFrameCountAndGolden()
          throws Exception {
    assumeDeviceSupported();

    ExportResult exportResult =
        runTransformer(
            /* isTonemappingEnabled= */ false,
            /* isDeblurAndDenoiseEnabled= */ false,
            /* isUpscaleVideoEnabled= */ false);

    assertThat(exportResult.videoFrameCount).isEqualTo(EXPECTED_FRAME_COUNT);
    assertOutputFileSampleCount(EXPECTED_FRAME_COUNT);
    Bitmap outputFrame = extractFirstFrame(outputVideoFile.getAbsolutePath());
    maybeSaveTestBitmap(
        testName.getMethodName(), /* bitmapLabel= */ "actual", outputFrame, /* path= */ null);
    Bitmap expectedFrame =
        extractFirstFrame(MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.uri);
    assertBitmapsAreSimilar(expectedFrame, outputFrame, PSNR_THRESHOLD);
  }

  @Test
  public void exportWithTransformer_upscaleEnabled_producesDoubledResolution() throws Exception {
    assumeDeviceSupported();

    ExportResult exportResult =
        runTransformer(
            /* isTonemappingEnabled= */ false,
            /* isDeblurAndDenoiseEnabled= */ false,
            /* isUpscaleVideoEnabled= */ true);

    assertThat(exportResult.videoFrameCount).isEqualTo(EXPECTED_FRAME_COUNT);
    assertOutputFileSampleCount(EXPECTED_FRAME_COUNT);
    Format trackFormat =
        retrieveTrackFormat(context, outputVideoFile.getAbsolutePath(), C.TRACK_TYPE_VIDEO);
    assertThat(trackFormat.width).isEqualTo(640);
    assertThat(trackFormat.height).isEqualTo(480);
    Bitmap outputFrame = extractFirstFrame(outputVideoFile.getAbsolutePath());
    maybeSaveTestBitmap(
        testName.getMethodName(), /* bitmapLabel= */ "actual", outputFrame, /* path= */ null);
    Bitmap goldenBitmap = readBitmap(GOLDEN_IMAGE_PATH);
    assertBitmapsAreSimilar(goldenBitmap, outputFrame, PSNR_THRESHOLD);
  }

  @Test
  public void exportWithTransformer_withTonemappingAndDeblurAndDenoise_producesExpectedFrameCount()
      throws Exception {
    assumeDeviceSupported();

    ExportResult exportResult =
        runTransformer(
            /* isTonemappingEnabled= */ true,
            /* isDeblurAndDenoiseEnabled= */ true,
            /* isUpscaleVideoEnabled= */ false);

    assertThat(exportResult.videoFrameCount).isEqualTo(EXPECTED_FRAME_COUNT);
    assertOutputFileSampleCount(EXPECTED_FRAME_COUNT);
  }

  private void assumeDeviceSupported() throws Exception {
    assumeTrue(
        "Device not supported by Google Play services video enhancement",
        Tasks.await(Enhancement.getClient(context).isDeviceSupported()));
  }

  private void setUpPlayServicesDecorator(boolean isUpscaleVideoEnabled) {
    int outputWidth = isUpscaleVideoEnabled ? WIDTH * 2 : WIDTH;
    int outputHeight = isUpscaleVideoEnabled ? HEIGHT * 2 : HEIGHT;
    fakeDownstreamOutput.prepareInputFrame(createHardwareBufferFrame(outputWidth, outputHeight));
    decorator =
        new EnhancementSessionDecorator.Builder(context, baseGlFactory)
            .setTonemappingEnabled(false)
            .setDeblurAndDenoiseVideoEnabled(false)
            .setUpscaleVideoEnabled(isUpscaleVideoEnabled)
            .build()
            .create(fakeDownstreamOutput, directExecutor(), testListener);
  }

  private void assertOutputFileSampleCount(int expectedSampleCount) throws Exception {
    FakeExtractorOutput fakeExtractorOutput =
        extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()),
            outputVideoFile.getAbsolutePath());
    Iterables.getOnlyElement(fakeExtractorOutput.getTrackOutputsForType(C.TRACK_TYPE_VIDEO))
        .assertSampleCount(expectedSampleCount);
  }

  private ExportResult runTransformer(
      boolean isTonemappingEnabled,
      boolean isDeblurAndDenoiseEnabled,
      boolean isUpscaleVideoEnabled)
      throws Exception {
    assumeFormatsSupported(
        context,
        testName.getMethodName(),
        /* inputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.videoFormat,
        /* outputFormat= */ null);
    FrameProcessor.Factory factory =
        new EnhancementSessionDecorator.Builder(context, baseGlFactory)
            .setTonemappingEnabled(isTonemappingEnabled)
            .setDeblurAndDenoiseVideoEnabled(isDeblurAndDenoiseEnabled)
            .setUpscaleVideoEnabled(isUpscaleVideoEnabled)
            .build();
    SettableFuture<ExportResult> exportResultFuture = SettableFuture.create();
    transformer =
        new Transformer.Builder(context)
            .setNativeHardwareBufferHelpers(HardwareBufferJni.INSTANCE)
            .setFrameProcessorFactory(factory)
            .setEncoderFactory(
                new DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(
                        new VideoEncoderSettings.Builder().setBitrate(5_000_000).build())
                    .build())
            .addListener(
                new Transformer.Listener() {
                  @Override
                  public void onCompleted(Composition composition, ExportResult exportResult) {
                    exportResultFuture.set(exportResult);
                  }

                  @Override
                  public void onError(
                      Composition composition,
                      ExportResult exportResult,
                      ExportException exportException) {
                    exportResultFuture.setException(exportException);
                  }
                })
            .build();
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.uri)
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder().setEndPositionMs(1_400).build())
            .build();
    EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(mediaItem).build();
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioAndVideoFrom(ImmutableList.of(editedMediaItem)))
            .build();
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(() -> transformer.start(composition, outputVideoFile.getAbsolutePath()));
    return exportResultFuture.get(SESSION_TIMEOUT_SECONDS, SECONDS);
  }

  private Bitmap extractFirstFrame(String filePath) throws Exception {
    try (FrameExtractor frameExtractor =
        new FrameExtractor.Builder(context, MediaItem.fromUri(filePath)).build()) {
      return frameExtractor.getFrame(0).get().bitmap;
    }
  }

  private DefaultHardwareBufferFrame createHardwareBufferFrame() {
    return createHardwareBufferFrame(WIDTH, HEIGHT);
  }

  private DefaultHardwareBufferFrame createHardwareBufferFrame(int width, int height) {
    HardwareBuffer buffer =
        HardwareBuffer.create(
            width,
            height,
            HardwareBuffer.RGBA_8888,
            /* layers= */ 1,
            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
                | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
                | HardwareBuffer.USAGE_CPU_WRITE_OFTEN);
    buffersToClose.add(buffer);
    return new DefaultHardwareBufferFrame.Builder(buffer)
        .setFormat(
            new Format.Builder()
                .setWidth(width)
                .setHeight(height)
                .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
                .build())
        .setMetadata(ImmutableMap.of(DefaultGlFrameProcessor.KEY_COMPOSITION_SEQUENCE_INDEX, 0))
        .build();
  }
}
