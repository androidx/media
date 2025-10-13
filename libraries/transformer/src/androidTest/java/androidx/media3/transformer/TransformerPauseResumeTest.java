/*
 * Copyright 2023 The Android Open Source Project
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

import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S;
import static androidx.media3.test.utils.FormatSupportAssumptions.assumeFormatsSupported;
import static androidx.media3.transformer.Transformer.PROGRESS_STATE_AVAILABLE;
import static androidx.media3.transformer.Transformer.PROGRESS_STATE_WAITING_FOR_AVAILABILITY;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assume.assumeFalse;

import android.content.Context;
import android.os.Build;
import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.SonicAudioProcessor;
import androidx.media3.common.util.NullableType;
import androidx.media3.common.util.Util;
import androidx.media3.effect.RgbFilter;
import androidx.media3.transformer.AndroidTestUtil.BatchProgressReportingMuxer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.SettableFuture;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/** End-to-end instrumentation tests for {@link Transformer} pause and resume scenarios. */
@RunWith(AndroidJUnit4.class)
public class TransformerPauseResumeTest {
  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private static final long PRESENTATION_TIME_US_TO_BLOCK_FRAME = 1_500_000L;
  private static final int DEFAULT_TIMEOUT_SECONDS = 120;
  private static final int MP4_ASSET_FRAME_COUNT =
      MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S.videoFrameCount;

  private final Context context = getApplicationContext();
  @Rule public final TestName testName = new TestName();

  private String testId;

  @Before
  public void setUpTestId() {
    testId = testName.getMethodName();
  }

  @Test
  public void resume_withSingleMediaItem_outputMatchesExpected() throws Exception {
    assumeFalse(shouldSkipDevice());
    assumeFormatsSupported(
        getApplicationContext(),
        testId,
        /* inputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S.videoFormat,
        /* outputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S.videoFormat);
    Composition composition =
        buildSingleSequenceComposition(
            /* clippingStartPositionMs= */ 0,
            /* clippingEndPositionMs= */ C.TIME_END_OF_SOURCE,
            /* mediaItemsInSequence= */ 1);
    CountDownLatch countDownLatch = new CountDownLatch(1);
    Transformer blockingTransformer = buildBlockingTransformer(countDownLatch::countDown);
    String firstOutputPath = temporaryFolder.newFile("FirstOutput.mp4").getAbsolutePath();
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(() -> blockingTransformer.start(composition, firstOutputPath));
    // Block here until timeout reached or latch is counted down.
    if (!countDownLatch.await(DEFAULT_TIMEOUT_SECONDS, SECONDS)) {
      throw new TimeoutException(
          "Transformer timed out after " + DEFAULT_TIMEOUT_SECONDS + " seconds.");
    }
    InstrumentationRegistry.getInstrumentation().runOnMainSync(blockingTransformer::cancel);

    // Resume the export.
    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, new Transformer.Builder(context).build())
            .build()
            .run(testId, composition, firstOutputPath);

    ExportResult exportResult = result.exportResult;
    assertThat(exportResult.processedInputs).hasSize(4);
    // Rarely, MediaCodec decoders output frames in the wrong order.
    // When the MediaCodec encoder sees frames in the wrong order, fewer output frames are produced.
    // Use a tolerance when comparing frame counts. See b/343476417#comment5.
    assertThat(exportResult.videoFrameCount).isWithin(2).of(MP4_ASSET_FRAME_COUNT);
    // The first processed media item corresponds to remuxing previous output video.
    assertThat(exportResult.processedInputs.get(0).audioDecoderName).isNull();
    assertThat(exportResult.processedInputs.get(0).videoDecoderName).isNull();
    // The second processed media item corresponds to processing remaining video.
    assertThat(exportResult.processedInputs.get(1).audioDecoderName).isNull();
    assertThat(exportResult.processedInputs.get(1).videoDecoderName).isNotEmpty();
    assertThat(exportResult.processedInputs.get(1).mediaItem.clippingConfiguration.startPositionMs)
        .isGreaterThan(0);
    // The third processed media item corresponds to processing audio.
    assertThat(exportResult.processedInputs.get(2).audioDecoderName).isNotEmpty();
    assertThat(exportResult.processedInputs.get(2).videoDecoderName).isNull();
    // The fourth processed media item corresponds to transmuxing processed video.
    assertThat(exportResult.processedInputs.get(3).audioDecoderName).isNull();
    assertThat(exportResult.processedInputs.get(3).videoDecoderName).isNull();
    assertThat(new File(result.filePath).length()).isGreaterThan(0);
  }

  @Test
  public void resume_withSingleMediaItemAfterImmediateCancellation_restartsExport()
      throws Exception {
    assumeFalse(shouldSkipDevice());
    assumeFormatsSupported(
        getApplicationContext(),
        testId,
        /* inputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S.videoFormat,
        /* outputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S.videoFormat);
    Composition composition =
        buildSingleSequenceComposition(
            /* clippingStartPositionMs= */ 0,
            /* clippingEndPositionMs= */ C.TIME_END_OF_SOURCE,
            /* mediaItemsInSequence= */ 1);
    Transformer transformer = new Transformer.Builder(context).build();
    String firstOutputPath = temporaryFolder.newFile("FirstOutput.mp4").getAbsolutePath();
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () -> {
              transformer.start(composition, firstOutputPath);
              transformer.cancel();
            });

    // Resume the export.
    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, composition, firstOutputPath);

    ExportResult exportResult = result.exportResult;
    // The first export did not progress because of the immediate cancellation hence resuming
    // actually restarts the export.
    assertThat(exportResult.processedInputs).hasSize(1);
    assertThat(new File(result.filePath).length()).isGreaterThan(0);
  }

  @Test
  public void resume_withSingleMediaItem_outputMatchesWithoutResume() throws Exception {
    assumeFalse(shouldSkipDevice());
    assumeFormatsSupported(
        getApplicationContext(),
        testId,
        /* inputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S.videoFormat,
        /* outputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S.videoFormat);
    Composition composition =
        buildSingleSequenceComposition(
            /* clippingStartPositionMs= */ 0,
            /* clippingEndPositionMs= */ C.TIME_END_OF_SOURCE,
            /* mediaItemsInSequence= */ 1);
    // Export without resume.
    ExportResult exportResultWithoutResume =
        new TransformerAndroidTestRunner.Builder(context, new Transformer.Builder(context).build())
            .build()
            .run(testId, composition)
            .exportResult;
    // Export with resume.
    CountDownLatch countDownLatch = new CountDownLatch(1);
    Transformer blockingTransformer = buildBlockingTransformer(countDownLatch::countDown);
    String firstOutputPath = temporaryFolder.newFile("FirstOutput.mp4").getAbsolutePath();
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(() -> blockingTransformer.start(composition, firstOutputPath));
    // Block here until timeout reached or latch is counted down.
    if (!countDownLatch.await(DEFAULT_TIMEOUT_SECONDS, SECONDS)) {
      throw new TimeoutException(
          "Transformer timed out after " + DEFAULT_TIMEOUT_SECONDS + " seconds.");
    }
    InstrumentationRegistry.getInstrumentation().runOnMainSync(blockingTransformer::cancel);

    // Resume the export.
    ExportTestResult resultWithResume =
        new TransformerAndroidTestRunner.Builder(context, new Transformer.Builder(context).build())
            .build()
            .run(testId, composition, firstOutputPath);

    ExportResult exportResultWithResume = resultWithResume.exportResult;
    assertThat(exportResultWithResume.processedInputs).hasSize(4);
    assertThat(exportResultWithResume.audioEncoderName)
        .isEqualTo(exportResultWithoutResume.audioEncoderName);
    assertThat(exportResultWithResume.videoEncoderName)
        .isEqualTo(exportResultWithoutResume.videoEncoderName);
    // Rarely, MediaCodec decoders output frames in the wrong order.
    // When the MediaCodec encoder sees frames in the wrong order, fewer output frames are produced.
    // Use a tolerance when comparing frame counts. See b/343476417#comment5.
    assertThat(exportResultWithResume.videoFrameCount)
        .isWithin(2)
        .of(exportResultWithoutResume.videoFrameCount);
    // TODO: b/306595508 - Remove this expected difference once inconsistent behaviour of audio
    //  encoder is fixed.
    int maxDiffExpectedInDurationMs = 2;
    // TODO: b/443998866 - Use MetadataRetriever to get exact duration.
    assertThat(
            exportResultWithResume.approximateDurationMs
                - exportResultWithoutResume.approximateDurationMs)
        .isLessThan(maxDiffExpectedInDurationMs);
    assertThat(new File(resultWithResume.filePath).length()).isGreaterThan(0);
  }

  @Test
  public void resume_withSingleMediaItemHavingClippingConfig_outputMatchesWithoutResume()
      throws Exception {
    assumeFalse(shouldSkipDevice());
    assumeFormatsSupported(
        getApplicationContext(),
        testId,
        /* inputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S.videoFormat,
        /* outputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S.videoFormat);
    Composition composition =
        buildSingleSequenceComposition(
            /* clippingStartPositionMs= */ 1_000L,
            /* clippingEndPositionMs= */ 4_000L,
            /* mediaItemsInSequence= */ 1);
    // Export without resume.
    ExportResult exportResultWithoutResume =
        new TransformerAndroidTestRunner.Builder(context, new Transformer.Builder(context).build())
            .build()
            .run(testId, composition)
            .exportResult;
    // Export with resume.
    CountDownLatch countDownLatch = new CountDownLatch(1);
    Transformer blockingTransformer = buildBlockingTransformer(countDownLatch::countDown);
    String firstOutputPath = temporaryFolder.newFile("FirstOutput.mp4").getAbsolutePath();
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(() -> blockingTransformer.start(composition, firstOutputPath));
    // Block here until timeout reached or latch is counted down.
    if (!countDownLatch.await(DEFAULT_TIMEOUT_SECONDS, SECONDS)) {
      throw new TimeoutException(
          "Transformer timed out after " + DEFAULT_TIMEOUT_SECONDS + " seconds.");
    }
    InstrumentationRegistry.getInstrumentation().runOnMainSync(blockingTransformer::cancel);

    // Resume the export.
    ExportTestResult resultWithResume =
        new TransformerAndroidTestRunner.Builder(context, new Transformer.Builder(context).build())
            .build()
            .run(testId, composition, firstOutputPath);

    ExportResult exportResultWithResume = resultWithResume.exportResult;
    assertThat(exportResultWithResume.processedInputs).hasSize(4);
    assertThat(exportResultWithResume.audioEncoderName)
        .isEqualTo(exportResultWithoutResume.audioEncoderName);
    assertThat(exportResultWithResume.videoEncoderName)
        .isEqualTo(exportResultWithoutResume.videoEncoderName);
    // Rarely, MediaCodec decoders output frames in the wrong order.
    // When the MediaCodec encoder sees frames in the wrong order, fewer output frames are produced.
    // Use a tolerance when comparing frame counts. See b/343476417#comment5.
    assertThat(exportResultWithResume.videoFrameCount)
        .isWithin(2)
        .of(exportResultWithoutResume.videoFrameCount);
    int maxDiffExpectedInDurationMs = 2;
    // TODO: b/443998866 - Use MetadataRetriever to get exact duration.
    assertThat(
            exportResultWithResume.approximateDurationMs
                - exportResultWithoutResume.approximateDurationMs)
        .isLessThan(maxDiffExpectedInDurationMs);
    assertThat(new File(resultWithResume.filePath).length()).isGreaterThan(0);
  }

  @Test
  public void resume_withTwoMediaItems_outputMatchesExpected() throws Exception {
    assumeFalse(shouldSkipDevice());
    assumeFormatsSupported(
        getApplicationContext(),
        testId,
        /* inputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S.videoFormat,
        /* outputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S.videoFormat);
    Composition composition =
        buildSingleSequenceComposition(
            /* clippingStartPositionMs= */ 0,
            /* clippingEndPositionMs= */ C.TIME_END_OF_SOURCE,
            /* mediaItemsInSequence= */ 2);
    CountDownLatch countDownLatch = new CountDownLatch(1);
    Transformer blockingTransformer = buildBlockingTransformer(countDownLatch::countDown);
    String firstOutputPath = temporaryFolder.newFile("FirstOutput.mp4").getAbsolutePath();
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(() -> blockingTransformer.start(composition, firstOutputPath));
    // Block here until timeout reached or latch is counted down.
    if (!countDownLatch.await(DEFAULT_TIMEOUT_SECONDS, SECONDS)) {
      throw new TimeoutException(
          "Transformer timed out after " + DEFAULT_TIMEOUT_SECONDS + " seconds.");
    }
    InstrumentationRegistry.getInstrumentation().runOnMainSync(blockingTransformer::cancel);

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, new Transformer.Builder(context).build())
            .build()
            .run(testId, composition, firstOutputPath);

    ExportResult exportResult = result.exportResult;
    assertThat(exportResult.processedInputs).hasSize(6);
    int expectedVideoFrameCount = 2 * MP4_ASSET_FRAME_COUNT;
    // Rarely, MediaCodec decoders output frames in the wrong order.
    // When the MediaCodec encoder sees frames in the wrong order, fewer output frames are produced.
    // Use a tolerance when comparing frame counts. See b/343476417#comment5.
    assertThat(exportResult.videoFrameCount).isWithin(2).of(expectedVideoFrameCount);
    // The first processed media item corresponds to remuxing previous output video.
    assertThat(exportResult.processedInputs.get(0).audioDecoderName).isNull();
    assertThat(exportResult.processedInputs.get(0).videoDecoderName).isNull();
    // The next two processed media item corresponds to processing remaining video.
    assertThat(exportResult.processedInputs.get(1).audioDecoderName).isNull();
    assertThat(exportResult.processedInputs.get(1).videoDecoderName).isNotEmpty();
    assertThat(exportResult.processedInputs.get(1).mediaItem.clippingConfiguration.startPositionMs)
        .isGreaterThan(0);
    assertThat(exportResult.processedInputs.get(2).audioDecoderName).isNull();
    assertThat(exportResult.processedInputs.get(2).videoDecoderName).isNotEmpty();
    // The next two processed media item corresponds to processing audio.
    assertThat(exportResult.processedInputs.get(3).audioDecoderName).isNotEmpty();
    assertThat(exportResult.processedInputs.get(3).videoDecoderName).isNull();
    assertThat(exportResult.processedInputs.get(4).audioDecoderName).isNotEmpty();
    assertThat(exportResult.processedInputs.get(4).videoDecoderName).isNull();
    // The last processed media item corresponds to transmuxing processed video.
    assertThat(exportResult.processedInputs.get(5).audioDecoderName).isNull();
    assertThat(exportResult.processedInputs.get(5).videoDecoderName).isNull();
    assertThat(new File(result.filePath).length()).isGreaterThan(0);
  }

  @Test
  public void resume_withTwoMediaItems_outputMatchesWithoutResume() throws Exception {
    assumeFalse(shouldSkipDevice());
    assumeFormatsSupported(
        getApplicationContext(),
        testId,
        /* inputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S.videoFormat,
        /* outputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S.videoFormat);
    Composition composition =
        buildSingleSequenceComposition(
            /* clippingStartPositionMs= */ 0,
            /* clippingEndPositionMs= */ C.TIME_END_OF_SOURCE,
            /* mediaItemsInSequence= */ 2);
    // Export without resume.
    ExportResult exportResultWithoutResume =
        new TransformerAndroidTestRunner.Builder(context, new Transformer.Builder(context).build())
            .build()
            .run(testId, composition)
            .exportResult;
    // Export with resume.
    CountDownLatch countDownLatch = new CountDownLatch(1);
    Transformer blockingTransformer = buildBlockingTransformer(countDownLatch::countDown);
    String firstOutputPath = temporaryFolder.newFile("FirstOutput.mp4").getAbsolutePath();
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(() -> blockingTransformer.start(composition, firstOutputPath));
    // Block here until timeout reached or latch is counted down.
    if (!countDownLatch.await(DEFAULT_TIMEOUT_SECONDS, SECONDS)) {
      throw new TimeoutException(
          "Transformer timed out after " + DEFAULT_TIMEOUT_SECONDS + " seconds.");
    }
    InstrumentationRegistry.getInstrumentation().runOnMainSync(blockingTransformer::cancel);

    ExportTestResult resultWithResume =
        new TransformerAndroidTestRunner.Builder(context, new Transformer.Builder(context).build())
            .build()
            .run(testId, composition, firstOutputPath);

    ExportResult exportResultWithResume = resultWithResume.exportResult;
    assertThat(exportResultWithResume.processedInputs).hasSize(6);
    assertThat(exportResultWithResume.audioEncoderName)
        .isEqualTo(exportResultWithoutResume.audioEncoderName);
    assertThat(exportResultWithResume.videoEncoderName)
        .isEqualTo(exportResultWithoutResume.videoEncoderName);
    // Rarely, MediaCodec decoders output frames in the wrong order.
    // When the MediaCodec encoder sees frames in the wrong order, fewer output frames are produced.
    // Use a tolerance when comparing frame counts. See b/343476417#comment5.
    assertThat(exportResultWithResume.videoFrameCount)
        .isWithin(2)
        .of(exportResultWithoutResume.videoFrameCount);
    int maxDiffExpectedInDurationMs = 2;
    // TODO: b/443998866 - Use MetadataRetriever to get exact duration.
    assertThat(
            exportResultWithResume.approximateDurationMs
                - exportResultWithoutResume.approximateDurationMs)
        .isLessThan(maxDiffExpectedInDurationMs);
    assertThat(new File(resultWithResume.filePath).length()).isGreaterThan(0);
  }

  @Test
  public void resumeAndGetProgress_returnsIncreasingPercentages() throws Exception {
    assumeFalse(shouldSkipDevice());
    assumeFormatsSupported(
        getApplicationContext(),
        testId,
        /* inputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S.videoFormat,
        /* outputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S.videoFormat);
    Composition composition =
        buildSingleSequenceComposition(
            /* clippingStartPositionMs= */ 0,
            /* clippingEndPositionMs= */ C.TIME_END_OF_SOURCE,
            /* mediaItemsInSequence= */ 1);
    CountDownLatch countDownLatch = new CountDownLatch(1);
    Transformer blockingTransformer = buildBlockingTransformer(countDownLatch::countDown);
    String firstOutputPath = temporaryFolder.newFile("FirstOutput.mp4").getAbsolutePath();
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(() -> blockingTransformer.start(composition, firstOutputPath));
    // Block here until timeout reached or latch is counted down.
    if (!countDownLatch.await(DEFAULT_TIMEOUT_SECONDS, SECONDS)) {
      throw new TimeoutException(
          "Transformer timed out after " + DEFAULT_TIMEOUT_SECONDS + " seconds.");
    }
    InstrumentationRegistry.getInstrumentation().runOnMainSync(blockingTransformer::cancel);
    // Prepare transformer to resume and to get progress.
    SettableFuture<@NullableType Exception> transformerExceptionFuture = SettableFuture.create();
    Transformer.Listener transformerListener =
        new Transformer.Listener() {
          @Override
          public void onCompleted(Composition composition, ExportResult exportResult) {
            transformerExceptionFuture.set(null);
          }

          @Override
          public void onError(
              Composition composition, ExportResult exportResult, ExportException exportException) {
            transformerExceptionFuture.set(exportException);
          }
        };
    Queue<Integer> progresses = new ConcurrentLinkedDeque<>();
    TransformerHolder transformerHolder = new TransformerHolder();
    Runnable getProgressRunnable =
        () ->
            InstrumentationRegistry.getInstrumentation()
                .runOnMainSync(
                    () -> {
                      Transformer transformer = transformerHolder.getTransformer();
                      ProgressHolder progressHolder = new ProgressHolder();
                      if (transformer.getProgress(progressHolder) == PROGRESS_STATE_AVAILABLE
                          && (progresses.isEmpty()
                              || Iterables.getLast(progresses) != progressHolder.progress)) {
                        progresses.add(progressHolder.progress);
                      }
                    });
    // Get progress after every sample batch is written.
    BatchProgressReportingMuxer.Factory muxerFactory =
        new BatchProgressReportingMuxer.Factory(
            /* sampleBatchSize= */ 100, () -> getProgressRunnable.run());
    Transformer transformer =
        new Transformer.Builder(context)
            .setMuxerFactory(muxerFactory)
            .addListener(transformerListener)
            .build();
    transformerHolder.setTransformer(transformer);
    String secondOutputPath = temporaryFolder.newFile("SecondOutput.mp4").getAbsolutePath();
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(() -> transformer.resume(composition, secondOutputPath, firstOutputPath));

    assertThat(transformerExceptionFuture.get()).isNull();
    assertThat(progresses).isNotEmpty();
    assertThat(progresses).isInOrder();
    assertThat(Iterables.getFirst(progresses, /* defaultValue= */ -1)).isAtLeast(0);
    // After progress 85, export will directly complete.
    assertThat(Iterables.getLast(progresses)).isAtMost(85);
  }

  @Test
  public void resumeAndGetProgress_returnsExpectedProgressStates() throws Exception {
    assumeFalse(shouldSkipDevice());
    assumeFormatsSupported(
        getApplicationContext(),
        testId,
        /* inputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S.videoFormat,
        /* outputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S.videoFormat);
    Composition composition =
        buildSingleSequenceComposition(
            /* clippingStartPositionMs= */ 0,
            /* clippingEndPositionMs= */ C.TIME_END_OF_SOURCE,
            /* mediaItemsInSequence= */ 1);
    CountDownLatch countDownLatch = new CountDownLatch(1);
    Transformer blockingTransformer = buildBlockingTransformer(countDownLatch::countDown);
    String firstOutputPath = temporaryFolder.newFile("FirstOutput.mp4").getAbsolutePath();
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(() -> blockingTransformer.start(composition, firstOutputPath));
    // Block here until timeout reached or latch is counted down.
    if (!countDownLatch.await(DEFAULT_TIMEOUT_SECONDS, SECONDS)) {
      throw new TimeoutException(
          "Transformer timed out after " + DEFAULT_TIMEOUT_SECONDS + " seconds.");
    }
    InstrumentationRegistry.getInstrumentation().runOnMainSync(blockingTransformer::cancel);
    // Prepare transformer to resume and to get progress.
    SettableFuture<@NullableType Exception> transformerExceptionFuture = SettableFuture.create();
    Transformer.Listener transformerListener =
        new Transformer.Listener() {
          @Override
          public void onCompleted(Composition composition, ExportResult exportResult) {
            transformerExceptionFuture.set(null);
          }

          @Override
          public void onError(
              Composition composition, ExportResult exportResult, ExportException exportException) {
            transformerExceptionFuture.set(exportException);
          }
        };
    Queue<Integer> progressStates = new ConcurrentLinkedDeque<>();
    // Start with PROGRESS_STATE_WAITING_FOR_AVAILABILITY as the first progress might directly be
    // PROGRESS_STATE_AVAILABLE.
    progressStates.add(PROGRESS_STATE_WAITING_FOR_AVAILABILITY);
    TransformerHolder transformerHolder = new TransformerHolder();
    Runnable getProgressRunnable =
        () ->
            InstrumentationRegistry.getInstrumentation()
                .runOnMainSync(
                    () -> {
                      Transformer transformer = transformerHolder.getTransformer();
                      int currentProgressState = transformer.getProgress(new ProgressHolder());
                      if (progressStates.isEmpty()
                          || Iterables.getLast(progressStates) != currentProgressState) {
                        progressStates.add(currentProgressState);
                      }
                    });
    // Get progress after every sample batch is written.
    BatchProgressReportingMuxer.Factory muxerFactory =
        new BatchProgressReportingMuxer.Factory(
            /* sampleBatchSize= */ 100, () -> getProgressRunnable.run());
    Transformer transformer =
        new Transformer.Builder(context)
            .setMuxerFactory(muxerFactory)
            .addListener(transformerListener)
            .build();
    transformerHolder.setTransformer(transformer);
    String secondOutputPath = temporaryFolder.newFile("SecondOutput.mp4").getAbsolutePath();
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(() -> transformer.resume(composition, secondOutputPath, firstOutputPath));

    assertThat(transformerExceptionFuture.get()).isNull();
    assertThat(progressStates)
        .containsExactly(PROGRESS_STATE_WAITING_FOR_AVAILABILITY, PROGRESS_STATE_AVAILABLE);
  }

  private static Composition buildSingleSequenceComposition(
      long clippingStartPositionMs, long clippingEndPositionMs, int mediaItemsInSequence) {
    SonicAudioProcessor sonic = new SonicAudioProcessor();
    sonic.setPitch(/* pitch= */ 2f);
    ImmutableList<AudioProcessor> audioEffects = ImmutableList.of(sonic);

    ImmutableList<Effect> videoEffects = ImmutableList.of(RgbFilter.createInvertedFilter());

    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(
                new MediaItem.Builder()
                    .setUri(MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S.uri)
                    .setClippingConfiguration(
                        new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(clippingStartPositionMs)
                            .setEndPositionMs(clippingEndPositionMs)
                            .build())
                    .build())
            .setEffects(new Effects(audioEffects, videoEffects))
            .build();

    List<EditedMediaItem> editedMediaItemList = new ArrayList<>();
    while (mediaItemsInSequence-- > 0) {
      editedMediaItemList.add(editedMediaItem);
    }

    return new Composition.Builder(
            EditedMediaItemSequence.withAudioAndVideoFrom(editedMediaItemList))
        .build();
  }

  private static Transformer buildBlockingTransformer(
      AndroidTestUtil.FrameBlockingMuxer.Listener listener) {
    return new Transformer.Builder(getApplicationContext())
        .setMuxerFactory(
            new AndroidTestUtil.FrameBlockingMuxerFactory(
                PRESENTATION_TIME_US_TO_BLOCK_FRAME, listener))
        .build();
  }

  private static boolean shouldSkipDevice() {
    // v26 emulators are not producing I-frames, due to which resuming export does not work as
    // expected.
    // On vivo 1820 and vivo 1906, the process crashes unexpectedly (see b/310566201).
    return (SDK_INT == 26 && Util.isRunningOnEmulator())
        || (SDK_INT == 27 && Ascii.equalsIgnoreCase(Build.MODEL, "vivo 1820"))
        || (SDK_INT == 28 && Ascii.equalsIgnoreCase(Build.MODEL, "vivo 1901"))
        || (SDK_INT == 28 && Ascii.equalsIgnoreCase(Build.MODEL, "vivo 1906"));
  }

  private static class TransformerHolder {
    private Transformer transformer;

    public void setTransformer(Transformer transformer) {
      this.transformer = transformer;
    }

    public Transformer getTransformer() {
      return transformer;
    }
  }
}
