/*
 * Copyright 2022 The Android Open Source Project
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

import static android.media.MediaCodecInfo.CodecProfileLevel.AACObjectHE;
import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.common.util.MediaFormatUtil.createFormatFromMediaFormat;
import static androidx.media3.common.util.Util.isRunningOnEmulator;
import static androidx.media3.test.utils.AssetInfo.JPG_ASSET;
import static androidx.media3.test.utils.AssetInfo.JPG_PIXEL_MOTION_PHOTO_ASSET;
import static androidx.media3.test.utils.AssetInfo.MOV_WITH_PCM_AUDIO;
import static androidx.media3.test.utils.AssetInfo.MP3_ASSET;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET_DOLBY_VISION_HDR;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET_PHOTOS_TRIM_OPTIMIZATION_VIDEO;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET_WITH_INCREASING_TIMESTAMPS;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_GAMMA22_1S;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET_WITH_SHORTER_AUDIO;
import static androidx.media3.test.utils.AssetInfo.MP4_PORTRAIT_ASSET;
import static androidx.media3.test.utils.AssetInfo.MP4_POSITIVE_SHIFT_EDIT_LIST;
import static androidx.media3.test.utils.AssetInfo.MP4_TRIM_OPTIMIZATION;
import static androidx.media3.test.utils.AssetInfo.MP4_TRIM_OPTIMIZATION_180;
import static androidx.media3.test.utils.AssetInfo.MP4_TRIM_OPTIMIZATION_270;
import static androidx.media3.test.utils.AssetInfo.PNG_ASSET;
import static androidx.media3.test.utils.AssetInfo.WAV_192KHZ_ASSET;
import static androidx.media3.test.utils.AssetInfo.WAV_96KHZ_ASSET;
import static androidx.media3.test.utils.AssetInfo.WAV_ASSET;
import static androidx.media3.test.utils.AssetInfo.WEBP_LARGE;
import static androidx.media3.test.utils.FormatSupportAssumptions.assumeFormatsSupported;
import static androidx.media3.test.utils.TestSummaryLogger.recordTestSkipped;
import static androidx.media3.test.utils.TestUtil.createByteCountingAudioProcessor;
import static androidx.media3.test.utils.TestUtil.retrieveTrackFormat;
import static androidx.media3.transformer.AndroidTestUtil.assumeCanEncodeWithProfile;
import static androidx.media3.transformer.AndroidTestUtil.createFrameCountingEffect;
import static androidx.media3.transformer.AndroidTestUtil.createOpenGlObjects;
import static androidx.media3.transformer.AndroidTestUtil.generateTextureFromBitmap;
import static androidx.media3.transformer.ExportResult.CONVERSION_PROCESS_NA;
import static androidx.media3.transformer.ExportResult.CONVERSION_PROCESS_TRANSCODED;
import static androidx.media3.transformer.ExportResult.CONVERSION_PROCESS_TRANSMUXED;
import static androidx.media3.transformer.ExportResult.CONVERSION_PROCESS_TRANSMUXED_AND_TRANSCODED;
import static androidx.media3.transformer.ExportResult.OPTIMIZATION_ABANDONED_KEYFRAME_PLACEMENT_OPTIMAL_FOR_TRIM;
import static androidx.media3.transformer.ExportResult.OPTIMIZATION_ABANDONED_TRIM_AND_TRANSCODING_TRANSFORMATION_REQUESTED;
import static androidx.media3.transformer.ExportResult.OPTIMIZATION_FAILED_FORMAT_MISMATCH;
import static androidx.media3.transformer.ExportResult.OPTIMIZATION_SUCCEEDED;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaFormat;
import android.media.metrics.LogSessionId;
import android.net.Uri;
import android.opengl.EGLContext;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Pair;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.OnInputFrameProcessedListener;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.AudioProcessor.AudioFormat;
import androidx.media3.common.audio.ChannelMixingAudioProcessor;
import androidx.media3.common.audio.ChannelMixingMatrix;
import androidx.media3.common.audio.DefaultGainProvider;
import androidx.media3.common.audio.GainProcessor;
import androidx.media3.common.audio.SonicAudioProcessor;
import androidx.media3.common.audio.SpeedProvider;
import androidx.media3.common.util.CodecSpecificDataUtil;
import androidx.media3.common.util.GlUtil;
import androidx.media3.datasource.DataSourceBitmapLoader;
import androidx.media3.effect.Contrast;
import androidx.media3.effect.DefaultGlObjectsProvider;
import androidx.media3.effect.DefaultVideoFrameProcessor;
import androidx.media3.effect.FrameCache;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.Presentation;
import androidx.media3.effect.RgbFilter;
import androidx.media3.effect.ScaleAndRotateTransformation;
import androidx.media3.effect.SpeedChangeEffect;
import androidx.media3.effect.TimestampWrapper;
import androidx.media3.exoplayer.MediaExtractorCompat;
import androidx.media3.exoplayer.audio.TeeAudioProcessor;
import androidx.media3.extractor.mp4.Mp4Extractor;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.inspector.MetadataRetriever;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.media3.test.utils.TestSpeedProvider;
import androidx.media3.test.utils.TestUtil;
import androidx.media3.transformer.AssetLoader.CompositionSettings;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/**
 * End-to-end instrumentation test for {@link Transformer} for cases that cannot be tested using
 * robolectric.
 */
// TODO: b/443998866 - Use MetadataRetriever to get exact duration in all tests.
@RunWith(AndroidJUnit4.class)
public class TransformerEndToEndTest {

  private static final GlEffect NO_OP_EFFECT = new Contrast(0f);
  private final Context context = ApplicationProvider.getApplicationContext();
  @Rule public final TestName testName = new TestName();

  private String testId;

  private volatile @MonotonicNonNull TextureAssetLoader textureAssetLoader;

  @Before
  public void setUpTestId() {
    testId = testName.getMethodName();
  }

  @Test
  public void compositionEditing_withThreeSequences_completes() throws Exception {
    Transformer transformer = new Transformer.Builder(context).build();
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET.videoFormat,
        /* outputFormat= */ MP4_ASSET.videoFormat);
    EditedMediaItem audioVideoItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET.uri))
            .setEffects(
                new Effects(
                    ImmutableList.of(createSonic(/* pitch= */ 2f)),
                    ImmutableList.of(RgbFilter.createInvertedFilter())))
            .build();
    EditedMediaItem imageItem =
        new EditedMediaItem.Builder(
                new MediaItem.Builder().setUri(JPG_ASSET.uri).setImageDurationMs(1500).build())
            .setFrameRate(30)
            .build();

    EditedMediaItemSequence audioVideoSequence =
        new EditedMediaItemSequence.Builder(audioVideoItem, imageItem, audioVideoItem).build();

    EditedMediaItem.Builder audioBuilder =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET.uri)).setRemoveVideo(true);

    EditedMediaItemSequence audioSequence =
        new EditedMediaItemSequence.Builder(
                audioBuilder
                    .setEffects(
                        new Effects(
                            ImmutableList.of(createSonic(/* pitch= */ 1.3f)),
                            /* videoEffects= */ ImmutableList.of()))
                    .build(),
                audioBuilder
                    .setEffects(
                        new Effects(
                            ImmutableList.of(createSonic(/* pitch= */ 0.85f)),
                            /* videoEffects= */ ImmutableList.of()))
                    .build())
            .build();

    EditedMediaItemSequence loopingAudioSequence =
        new EditedMediaItemSequence.Builder(
                audioBuilder
                    .setEffects(
                        new Effects(
                            ImmutableList.of(createSonic(/* pitch= */ 0.4f)),
                            /* videoEffects= */ ImmutableList.of()))
                    .build())
            .setIsLooping(true)
            .build();

    Composition composition =
        new Composition.Builder(audioVideoSequence, audioSequence, loopingAudioSequence).build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, composition);

    // MP4_ASSET duration is ~1s.
    // Image asset duration is ~1.5s.
    // audioVideoSequence duration: ~3.5s (3 inputs).
    // audioSequence duration: ~2s (2 inputs).
    // loopingAudioSequence: Matches max other sequence (~3.5s) -> 4 inputs of ~1s audio item.
    assertThat(result.exportResult.processedInputs).hasSize(9);
    assertThat(new File(result.filePath).length()).isGreaterThan(0);
  }

  @Test
  public void compositionEditing_withLongLoopingSequence_completes() throws Exception {
    Transformer transformer = new Transformer.Builder(context).build();
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET.videoFormat,
        /* outputFormat= */ MP4_ASSET.videoFormat);
    EditedMediaItem imageItem =
        new EditedMediaItem.Builder(
                new MediaItem.Builder().setUri(JPG_ASSET.uri).setImageDurationMs(500).build())
            .setFrameRate(30)
            .build();

    EditedMediaItemSequence imageSequence = new EditedMediaItemSequence.Builder(imageItem).build();

    EditedMediaItem.Builder audioBuilder =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET.uri)).setRemoveVideo(true);

    EditedMediaItemSequence loopingAudioSequence =
        new EditedMediaItemSequence.Builder(
                audioBuilder
                    .setEffects(
                        new Effects(
                            ImmutableList.of(createSonic(/* pitch= */ 0.4f)),
                            /* videoEffects= */ ImmutableList.of()))
                    .build())
            .setIsLooping(true)
            .build();

    Composition composition = new Composition.Builder(imageSequence, loopingAudioSequence).build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, composition);

    // Image asset duration is ~0.5s.
    // loopingAudioSequence: Matches other sequence (~0.5s) and is cut short.
    MetadataRetriever metadataRetriever =
        new MetadataRetriever.Builder(context, MediaItem.fromUri(result.filePath)).build();
    long actualDurationUs = metadataRetriever.retrieveDurationUs().get();
    assertThat(actualDurationUs).isWithin(50_000).of(500_000);
  }

  @Test
  public void videoEditing_withImageInput_completesWithCorrectFrameCountAndDuration()
      throws Exception {
    Transformer transformer = new Transformer.Builder(context).build();
    ImmutableList<Effect> videoEffects = ImmutableList.of(Presentation.createForHeight(480));
    Effects effects = new Effects(/* audioProcessors= */ ImmutableList.of(), videoEffects);
    int expectedFrameCount = 40;
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(PNG_ASSET.uri)
            .setImageDurationMs(C.MILLIS_PER_SECOND)
            .build();
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(mediaItem)
            .setFrameRate(expectedFrameCount)
            .setEffects(effects)
            .build();
    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, editedMediaItem);

    assertThat(result.exportResult.videoFrameCount).isEqualTo(expectedFrameCount);
    // Expected timestamp of the last frame.
    assertThat(result.exportResult.approximateDurationMs)
        .isEqualTo((C.MILLIS_PER_SECOND / expectedFrameCount) * (expectedFrameCount - 1));
    assertThat(new File(result.filePath).length()).isGreaterThan(0);
  }

  @Test
  public void videoTranscoding_withImageInput_completesWithCorrectFrameCountAndDuration()
      throws Exception {
    Transformer transformer = new Transformer.Builder(context).build();
    int expectedFrameCount = 40;
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(
                new MediaItem.Builder()
                    .setUri(PNG_ASSET.uri)
                    .setImageDurationMs(C.MILLIS_PER_SECOND)
                    .build())
            .setFrameRate(expectedFrameCount)
            .build();
    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, editedMediaItem);

    assertThat(result.exportResult.videoFrameCount).isEqualTo(expectedFrameCount);
    // Expected timestamp of the last frame.
    assertThat(result.exportResult.approximateDurationMs)
        .isEqualTo((C.MILLIS_PER_SECOND / expectedFrameCount) * (expectedFrameCount - 1));
    assertThat(new File(result.filePath).length()).isGreaterThan(0);
  }

  @Test
  public void videoEditing_withLargeImageInput_completesWithCorrectFrameCountAndDuration()
      throws Exception {
    Transformer transformer = new Transformer.Builder(context).build();
    ImmutableList<Effect> videoEffects = ImmutableList.of(Presentation.createForHeight(480));
    Effects effects = new Effects(/* audioProcessors= */ ImmutableList.of(), videoEffects);
    int expectedFrameCount = 40;
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(
                new MediaItem.Builder()
                    .setUri(WEBP_LARGE.uri)
                    .setImageDurationMs(C.MILLIS_PER_SECOND)
                    .build())
            .setFrameRate(expectedFrameCount)
            .setEffects(effects)
            .build();
    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, editedMediaItem);

    assertThat(result.exportResult.videoFrameCount).isEqualTo(expectedFrameCount);
    // Expected timestamp of the last frame.
    assertThat(result.exportResult.approximateDurationMs)
        .isEqualTo((C.MILLIS_PER_SECOND / expectedFrameCount) * (expectedFrameCount - 1));
    assertThat(new File(result.filePath).length()).isGreaterThan(0);
  }

  @Test
  public void videoEditing_withTextureInput_completesWithCorrectFrameCountAndDuration()
      throws Exception {
    Bitmap bitmap =
        new DataSourceBitmapLoader.Builder(context)
            .build()
            .loadBitmap(Uri.parse(PNG_ASSET.uri))
            .get();
    int expectedFrameCount = 2;
    EGLContext currentContext = createOpenGlObjects();
    DefaultVideoFrameProcessor.Factory videoFrameProcessorFactory =
        new DefaultVideoFrameProcessor.Factory.Builder()
            .setGlObjectsProvider(new DefaultGlObjectsProvider(currentContext))
            .build();
    Transformer transformer =
        new Transformer.Builder(context)
            .setAssetLoaderFactory(
                new TestTextureAssetLoaderFactory(bitmap.getWidth(), bitmap.getHeight()))
            .setVideoFrameProcessorFactory(videoFrameProcessorFactory)
            .build();
    ImmutableList<Effect> videoEffects = ImmutableList.of(Presentation.createForHeight(480));
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(Uri.EMPTY))
            .setDurationUs(C.MICROS_PER_SECOND)
            .setEffects(new Effects(/* audioProcessors= */ ImmutableList.of(), videoEffects))
            .build();
    int texId1 = generateTextureFromBitmap(bitmap);
    int texId2 = generateTextureFromBitmap(bitmap);
    HandlerThread textureQueuingThread = new HandlerThread("textureQueuingThread");
    textureQueuingThread.start();
    Looper looper = checkNotNull(textureQueuingThread.getLooper());
    Handler textureHandler =
        new Handler(looper) {
          @Override
          public void handleMessage(Message msg) {
            if (textureAssetLoader != null
                && textureAssetLoader.queueInputTexture(texId1, /* presentationTimeUs= */ 0)) {
              textureAssetLoader.queueInputTexture(
                  texId2, /* presentationTimeUs= */ C.MICROS_PER_SECOND / 2);
              textureAssetLoader.signalEndOfVideoInput();
              return;
            }
            sendEmptyMessage(0);
          }
        };

    textureHandler.sendEmptyMessage(0);
    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, editedMediaItem);

    assertThat(result.exportResult.videoFrameCount).isEqualTo(expectedFrameCount);
    // Expected timestamp of the last frame.
    assertThat(result.exportResult.approximateDurationMs).isEqualTo(C.MILLIS_PER_SECOND / 2);
    assertThat(new File(result.filePath).length()).isGreaterThan(0);
  }

  @Test
  public void videoTranscoding_withTextureInput_completesWithCorrectFrameCountAndDuration()
      throws Exception {
    Bitmap bitmap =
        new DataSourceBitmapLoader.Builder(context)
            .build()
            .loadBitmap(Uri.parse(PNG_ASSET.uri))
            .get();
    int expectedFrameCount = 2;
    EGLContext currentContext = createOpenGlObjects();
    DefaultVideoFrameProcessor.Factory videoFrameProcessorFactory =
        new DefaultVideoFrameProcessor.Factory.Builder()
            .setGlObjectsProvider(new DefaultGlObjectsProvider(currentContext))
            .build();
    Transformer transformer =
        new Transformer.Builder(context)
            .setAssetLoaderFactory(
                new TestTextureAssetLoaderFactory(bitmap.getWidth(), bitmap.getHeight()))
            .setVideoFrameProcessorFactory(videoFrameProcessorFactory)
            .build();
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(Uri.EMPTY))
            .setDurationUs(C.MICROS_PER_SECOND)
            .build();
    int texId1 = generateTextureFromBitmap(bitmap);
    int texId2 = generateTextureFromBitmap(bitmap);
    HandlerThread textureQueuingThread = new HandlerThread("textureQueuingThread");
    textureQueuingThread.start();
    Looper looper = checkNotNull(textureQueuingThread.getLooper());
    Handler textureHandler =
        new Handler(looper) {
          @Override
          public void handleMessage(Message msg) {
            if (textureAssetLoader != null
                && textureAssetLoader.queueInputTexture(texId1, /* presentationTimeUs= */ 0)) {
              textureAssetLoader.queueInputTexture(
                  texId2, /* presentationTimeUs= */ C.MICROS_PER_SECOND / 2);
              textureAssetLoader.signalEndOfVideoInput();
              return;
            }
            sendEmptyMessage(0);
          }
        };
    textureHandler.sendEmptyMessage(0);
    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, editedMediaItem);

    assertThat(result.exportResult.videoFrameCount).isEqualTo(expectedFrameCount);
    // Expected timestamp of the last frame.
    assertThat(result.exportResult.approximateDurationMs).isEqualTo(C.MILLIS_PER_SECOND / 2);
    assertThat(new File(result.filePath).length()).isGreaterThan(0);
  }

  @Test
  public void videoEditing_completesWithConsistentFrameCount() throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET.videoFormat,
        /* outputFormat= */ MP4_ASSET.videoFormat);
    Transformer transformer =
        new Transformer.Builder(context)
            .setEncoderFactory(
                new DefaultEncoderFactory.Builder(context).setEnableFallback(false).build())
            .build();
    MediaItem mediaItem = MediaItem.fromUri(Uri.parse(MP4_ASSET.uri));
    ImmutableList<Effect> videoEffects = ImmutableList.of(Presentation.createForHeight(480));
    Effects effects = new Effects(/* audioProcessors= */ ImmutableList.of(), videoEffects);
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(mediaItem).setEffects(effects).build();
    // Result of the following command:
    // ffprobe -count_frames -select_streams v:0 -show_entries stream=nb_read_frames sample.mp4
    int expectedFrameCount = 30;

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, editedMediaItem);

    assertThat(result.exportResult.videoFrameCount).isEqualTo(expectedFrameCount);
    assertThat(new File(result.filePath).length()).isGreaterThan(0);
  }

  @Test
  public void videoEditing_withPortraitEncodingDisabled_rotatesVideoBeforeEncoding()
      throws Exception {
    Format inputFormat = checkNotNull(MP4_PORTRAIT_ASSET.videoFormat);
    Format outputFormat =
        inputFormat.buildUpon().setWidth(inputFormat.height).setHeight(inputFormat.width).build();
    assumeFormatsSupported(context, testId, inputFormat, outputFormat);
    // Portrait encoding is disabled by default.
    Transformer transformer =
        new Transformer.Builder(context)
            .setEncoderFactory(new AndroidTestUtil.ForceEncodeEncoderFactory(context))
            .build();
    MediaItem mediaItem = MediaItem.fromUri(Uri.parse(MP4_PORTRAIT_ASSET.uri));
    EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(mediaItem).build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, editedMediaItem);

    Format format = retrieveTrackFormat(context, result.filePath, C.TRACK_TYPE_VIDEO);
    assertThat(format.rotationDegrees).isEqualTo(90);
    assertThat(result.exportResult.width).isEqualTo(outputFormat.width);
  }

  @Test
  public void videoEditing_withPortraitEncodingEnabled_doesNotRotateVideoBeforeEncoding()
      throws Exception {
    Format inputFormat = checkNotNull(MP4_PORTRAIT_ASSET.videoFormat);
    assumeFormatsSupported(
        context,
        testId,
        inputFormat,
        /* outputFormat= */ inputFormat,
        /* isPortraitEncodingEnabled= */ true);
    Transformer transformer =
        new Transformer.Builder(context)
            .setEncoderFactory(new AndroidTestUtil.ForceEncodeEncoderFactory(context))
            .setPortraitEncodingEnabled(true)
            .build();
    MediaItem mediaItem = MediaItem.fromUri(Uri.parse(MP4_PORTRAIT_ASSET.uri));
    EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(mediaItem).build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, editedMediaItem);

    Format format = retrieveTrackFormat(context, result.filePath, C.TRACK_TYPE_VIDEO);
    assertThat(format.rotationDegrees).isEqualTo(0);
    assertThat(result.exportResult.width).isEqualTo(inputFormat.width);
  }

  @Test
  public void videoEditing_withOneFrameInEncoder_completesWithConsistentFrameCount()
      throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.videoFormat,
        /* outputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.videoFormat);
    Transformer transformer =
        new Transformer.Builder(context)
            .setEncoderFactory(new AndroidTestUtil.ForceEncodeEncoderFactory(context))
            .experimentalSetMaxFramesInEncoder(1)
            .build();
    MediaItem mediaItem =
        MediaItem.fromUri(Uri.parse(MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.uri));
    EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(mediaItem).build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, editedMediaItem);

    // Rarely, MediaCodec decoders output frames in the wrong order.
    // When the MediaCodec encoder sees frames in the wrong order, fewer output frames are produced.
    // Use a tolerance when comparing frame counts. See b/343476417#comment5.
    assertThat(result.exportResult.videoFrameCount)
        .isWithin(2)
        .of(MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.videoFrameCount);
    assertThat(new File(result.filePath).length()).isGreaterThan(0);
  }

  @Test
  public void videoEditing_withMaxFramesInEncoder_completesWithConsistentFrameCount()
      throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.videoFormat,
        /* outputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.videoFormat);
    Transformer transformer =
        new Transformer.Builder(context)
            .setEncoderFactory(new AndroidTestUtil.ForceEncodeEncoderFactory(context))
            .experimentalSetMaxFramesInEncoder(16)
            .build();
    MediaItem mediaItem =
        MediaItem.fromUri(Uri.parse(MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.uri));
    EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(mediaItem).build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, editedMediaItem);

    // Rarely, MediaCodec decoders output frames in the wrong order.
    // When the MediaCodec encoder sees frames in the wrong order, fewer output frames are produced.
    // Use a tolerance when comparing frame counts. See b/343476417#comment5.
    assertThat(result.exportResult.videoFrameCount)
        .isWithin(2)
        .of(MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.videoFrameCount);
    assertThat(new File(result.filePath).length()).isGreaterThan(0);
  }

  // TODO: b/345483531 - Migrate this test to a Parameterized ImageSequence test.
  @Test
  public void videoEditing_withShortAlternatingImages_completesWithCorrectFrameCountAndDuration()
      throws Exception {
    Transformer transformer =
        new Transformer.Builder(context)
            .setEncoderFactory(
                new DefaultEncoderFactory.Builder(context).setEnableFallback(false).build())
            .build();

    EditedMediaItem image1 =
        new EditedMediaItem.Builder(
                new MediaItem.Builder().setUri(PNG_ASSET.uri).setImageDurationMs(100).build())
            .setFrameRate(30)
            .build();
    int image1FrameCount = 3;
    EditedMediaItem image2 =
        new EditedMediaItem.Builder(
                new MediaItem.Builder().setUri(JPG_ASSET.uri).setImageDurationMs(200).build())
            .setFrameRate(30)
            .build();
    int image2FrameCount = 6;

    ArrayList<EditedMediaItem> editedMediaItems = new ArrayList<>(100);
    for (int i = 0; i < 25; i++) {
      editedMediaItems.add(image1);
      editedMediaItems.add(image2);
    }

    Composition composition =
        new Composition.Builder(new EditedMediaItemSequence.Builder(editedMediaItems).build())
            .setEffects(
                new Effects(
                    /* audioProcessors= */ ImmutableList.of(),
                    ImmutableList.of(
                        // To ensure that software encoders can encode.
                        Presentation.createForWidthAndHeight(
                            /* width= */ 480,
                            /* height= */ 360,
                            Presentation.LAYOUT_SCALE_TO_FIT))))
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, composition);

    // TODO: b/346289922 - Check frame count with extractors.
    assertThat(result.exportResult.videoFrameCount)
        .isEqualTo(25 * image1FrameCount + 25 * image2FrameCount);
    // 25 100ms-images and 25 200ms-images
    assertThat(result.exportResult.approximateDurationMs).isEqualTo(7_466);
    assertThat(new File(result.filePath).length()).isGreaterThan(0);
  }

  @Test
  public void videoEditing_effectsOverTime_completesWithConsistentFrameCount() throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET.videoFormat,
        /* outputFormat= */ MP4_ASSET.videoFormat);
    Transformer transformer =
        new Transformer.Builder(context)
            .setEncoderFactory(
                new DefaultEncoderFactory.Builder(context).setEnableFallback(false).build())
            .build();
    MediaItem mediaItem = MediaItem.fromUri(Uri.parse(MP4_ASSET.uri));
    ImmutableList<Effect> videoEffects =
        ImmutableList.of(
            new TimestampWrapper(
                new Contrast(.5f),
                /* startTimeUs= */ 0,
                /* endTimeUs= */ Math.round(.1f * C.MICROS_PER_SECOND)),
            new TimestampWrapper(
                new FrameCache(/* capacity= */ 5),
                /* startTimeUs= */ Math.round(.2f * C.MICROS_PER_SECOND),
                /* endTimeUs= */ Math.round(.3f * C.MICROS_PER_SECOND)));
    Effects effects = new Effects(/* audioProcessors= */ ImmutableList.of(), videoEffects);
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(mediaItem).setEffects(effects).build();
    // Result of the following command:
    // ffprobe -count_frames -select_streams v:0 -show_entries stream=nb_read_frames sample.mp4
    int expectedFrameCount = 30;

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, editedMediaItem);

    assertThat(result.exportResult.videoFrameCount).isEqualTo(expectedFrameCount);
    assertThat(new File(result.filePath).length()).isGreaterThan(0);
  }

  @Test
  public void videoEditing_withSingleSequenceAndCompositionEffect_appliesEffect() throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET.videoFormat,
        /* outputFormat= */ MP4_ASSET.videoFormat);
    Transformer transformer =
        new Transformer.Builder(context)
            .setEncoderFactory(
                new DefaultEncoderFactory.Builder(context).setEnableFallback(false).build())
            .build();
    MediaItem mediaItem = MediaItem.fromUri(Uri.parse(MP4_ASSET.uri));
    EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(mediaItem).build();
    InputTimestampRecordingShaderProgram timestampRecordingShaderProgram =
        new InputTimestampRecordingShaderProgram();
    ImmutableList<Effect> videoEffects =
        ImmutableList.of((GlEffect) (context, useHdr) -> timestampRecordingShaderProgram);
    Composition composition =
        new Composition.Builder(new EditedMediaItemSequence.Builder(editedMediaItem).build())
            .setEffects(new Effects(/* audioProcessors= */ ImmutableList.of(), videoEffects))
            .build();

    new TransformerAndroidTestRunner.Builder(context, transformer).build().run(testId, composition);

    assertThat(timestampRecordingShaderProgram.getInputTimestampsUs()).isNotEmpty();
  }

  @Test
  public void videoEditing_withMultiSequenceAndCompositionEffect_appliesEffect() throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET.videoFormat,
        /* outputFormat= */ MP4_ASSET.videoFormat);
    Transformer transformer =
        new Transformer.Builder(context)
            .setEncoderFactory(
                new DefaultEncoderFactory.Builder(context).setEnableFallback(false).build())
            .build();
    MediaItem mediaItem = MediaItem.fromUri(Uri.parse(MP4_ASSET.uri));
    EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(mediaItem).build();
    InputTimestampRecordingShaderProgram timestampRecordingShaderProgram =
        new InputTimestampRecordingShaderProgram();
    ImmutableList<Effect> videoEffects =
        ImmutableList.of((GlEffect) (context, useHdr) -> timestampRecordingShaderProgram);
    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence.Builder(editedMediaItem).build(),
                new EditedMediaItemSequence.Builder(editedMediaItem).build())
            .setEffects(new Effects(/* audioProcessors= */ ImmutableList.of(), videoEffects))
            .build();

    new TransformerAndroidTestRunner.Builder(context, transformer).build().run(testId, composition);

    assertThat(timestampRecordingShaderProgram.getInputTimestampsUs()).isNotEmpty();
  }

  @Test
  public void videoOnly_completesWithConsistentDuration() throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET.videoFormat,
        /* outputFormat= */ MP4_ASSET.videoFormat);
    Transformer transformer =
        new Transformer.Builder(context)
            .setEncoderFactory(
                new DefaultEncoderFactory.Builder(context).setEnableFallback(false).build())
            .build();
    MediaItem mediaItem = MediaItem.fromUri(Uri.parse(MP4_ASSET.uri));
    ImmutableList<Effect> videoEffects = ImmutableList.of(Presentation.createForHeight(480));
    Effects effects = new Effects(/* audioProcessors= */ ImmutableList.of(), videoEffects);
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(mediaItem).setRemoveAudio(true).setEffects(effects).build();
    long expectedDurationMs = 967;

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, editedMediaItem);

    assertThat(result.exportResult.approximateDurationMs).isEqualTo(expectedDurationMs);
    assertThat(new File(result.filePath).length()).isGreaterThan(0);
  }

  @Test
  public void clippedMedia_completesWithClippedDuration() throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.videoFormat,
        /* outputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.videoFormat);
    Transformer transformer = new Transformer.Builder(context).build();
    long clippingStartMs = 10_000;
    long clippingEndMs = 11_000;
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(Uri.parse(MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.uri))
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clippingStartMs)
                    .setEndPositionMs(clippingEndMs)
                    .build())
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, mediaItem);

    assertThat(result.exportResult.approximateDurationMs).isAtMost(clippingEndMs - clippingStartMs);
    assertThat(new File(result.filePath).length()).isGreaterThan(0);
  }

  @Test
  public void
      clippedAndRotatedMedia_withNoOpEffect_completesWithClippedDurationAndCorrectOrientation()
          throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.videoFormat,
        /* outputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.videoFormat);
    Transformer transformer = new Transformer.Builder(context).build();
    long clippingStartMs = 10_000;
    long clippingEndMs = 11_000;
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(Uri.parse(MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.uri))
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clippingStartMs)
                    .setEndPositionMs(clippingEndMs)
                    .build())
            .build();
    ImmutableList<Effect> videoEffects =
        ImmutableList.of(
            new ScaleAndRotateTransformation.Builder().setRotationDegrees(90).build(),
            NO_OP_EFFECT);
    Effects effects = new Effects(/* audioProcessors= */ ImmutableList.of(), videoEffects);
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(mediaItem).setEffects(effects).build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, editedMediaItem);

    assertThat(result.exportResult.approximateDurationMs).isAtMost(clippingEndMs - clippingStartMs);
    Format format = retrieveTrackFormat(context, result.filePath, C.TRACK_TYPE_VIDEO);
    // The output video is portrait, but Transformer's default setup encodes videos landscape.
    assertThat(format.rotationDegrees).isEqualTo(90);
  }

  @Test
  public void clippedMedia_trimOptimizationEnabledAndTrimFromCloseToKeyFrame_succeeds()
      throws Exception {
    // This test covers the case where there's no frame between the trim point and the next sync
    // sample. The frame has to be further than roughly 25ms apart.
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET_PHOTOS_TRIM_OPTIMIZATION_VIDEO.videoFormat,
        /* outputFormat= */ MP4_ASSET_PHOTOS_TRIM_OPTIMIZATION_VIDEO.videoFormat);

    Transformer transformer =
        new Transformer.Builder(context).experimentalSetTrimOptimizationEnabled(true).build();

    // The previous sample is at 1137 and the next sample (which is a sync sample) is at 1171.
    long clippingStartMs = 1138;
    long clippingEndMs = 5601;

    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(Uri.parse(MP4_ASSET_PHOTOS_TRIM_OPTIMIZATION_VIDEO.uri))
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(1138)
                    .setEndPositionMs(5601)
                    .build())
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, mediaItem);

    assertThat(result.exportResult.optimizationResult)
        .isEqualTo(OPTIMIZATION_ABANDONED_KEYFRAME_PLACEMENT_OPTIMAL_FOR_TRIM);
    assertThat(result.exportResult.approximateDurationMs).isAtMost(clippingEndMs - clippingStartMs);
    assertThat(result.exportResult.videoConversionProcess).isEqualTo(CONVERSION_PROCESS_TRANSMUXED);
    assertThat(result.exportResult.audioConversionProcess).isEqualTo(CONVERSION_PROCESS_TRANSMUXED);
    assertThat(new File(result.filePath).length()).isGreaterThan(0);
  }

  @Test
  public void clippedMedia_trimOptimizationEnabled_fallbackToNormalExportUponFormatMismatch()
      throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.videoFormat,
        /* outputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.videoFormat);
    Transformer transformer =
        new Transformer.Builder(context).experimentalSetTrimOptimizationEnabled(true).build();
    long clippingStartMs = 10_000;
    long clippingEndMs = 13_000;
    // The file is made artificially on computer software so phones will not have the encoder
    // available to match the csd.
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(Uri.parse(MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.uri))
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clippingStartMs)
                    .setEndPositionMs(clippingEndMs)
                    .build())
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, mediaItem);

    assertThat(result.exportResult.optimizationResult)
        .isEqualTo(OPTIMIZATION_FAILED_FORMAT_MISMATCH);
    assertThat(result.exportResult.approximateDurationMs).isAtMost(clippingEndMs - clippingStartMs);
    assertThat(result.exportResult.videoConversionProcess).isEqualTo(CONVERSION_PROCESS_TRANSCODED);
    assertThat(result.exportResult.audioConversionProcess).isEqualTo(CONVERSION_PROCESS_TRANSMUXED);
    assertThat(new File(result.filePath).length()).isGreaterThan(0);
  }

  @Test
  public void
      clippedAndRotatedMedia_trimOptimizationEnabledButFormatsMismatch_fallsbackWithCorrectOrientationOutput()
          throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.videoFormat,
        /* outputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.videoFormat);
    Transformer transformer =
        new Transformer.Builder(context).experimentalSetTrimOptimizationEnabled(true).build();
    long clippingStartMs = 10_000;
    long clippingEndMs = 13_000;
    // The format for this file cannot be encoded on phones, so it will trigger trim optimization
    // fallback. This is because its csd doesn't match any known phone decoder.
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(Uri.parse(MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.uri))
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clippingStartMs)
                    .setEndPositionMs(clippingEndMs)
                    .build())
            .build();
    ImmutableList<Effect> videoEffects =
        ImmutableList.of(
            new ScaleAndRotateTransformation.Builder().setRotationDegrees(180).build(),
            NO_OP_EFFECT);
    Effects effects = new Effects(/* audioProcessors= */ ImmutableList.of(), videoEffects);
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(mediaItem).setEffects(effects).build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, editedMediaItem);

    assertThat(result.exportResult.optimizationResult)
        .isEqualTo(OPTIMIZATION_FAILED_FORMAT_MISMATCH);
    assertThat(result.exportResult.approximateDurationMs).isAtMost(clippingEndMs - clippingStartMs);
    Format format = retrieveTrackFormat(context, result.filePath, C.TRACK_TYPE_VIDEO);
    // The video is transcoded, so the rotation is performed in the VideoFrameProcessor.
    // The output video is portrait, but Transformer's default setup encodes videos landscape.
    assertThat(format.rotationDegrees).isEqualTo(0);
  }

  @Test
  public void
      clippedMedia_trimOptimizationEnabled_noKeyFrameBetweenClipTimes_fallbackToNormalExport()
          throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.videoFormat,
        /* outputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.videoFormat);
    Transformer transformer =
        new Transformer.Builder(context).experimentalSetTrimOptimizationEnabled(true).build();
    long clippingStartMs = 10_000;
    long clippingEndMs = 11_000;
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(Uri.parse(MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.uri))
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clippingStartMs)
                    .setEndPositionMs(clippingEndMs)
                    .build())
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, mediaItem);

    assertThat(result.exportResult.optimizationResult)
        .isEqualTo(OPTIMIZATION_ABANDONED_KEYFRAME_PLACEMENT_OPTIMAL_FOR_TRIM);
    assertThat(result.exportResult.approximateDurationMs).isAtMost(clippingEndMs - clippingStartMs);
    assertThat(result.exportResult.videoConversionProcess).isEqualTo(CONVERSION_PROCESS_TRANSCODED);
    assertThat(result.exportResult.audioConversionProcess).isEqualTo(CONVERSION_PROCESS_TRANSMUXED);
    assertThat(new File(result.filePath).length()).isGreaterThan(0);
  }

  @Test
  public void
      clippedMedia_trimOptimizationEnabled_noKeyFramesAfterClipStart_fallbackToNormalExport()
          throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.videoFormat,
        /* outputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.videoFormat);
    Transformer transformer =
        new Transformer.Builder(context).experimentalSetTrimOptimizationEnabled(true).build();
    long clippingStartMs = 14_500;
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(Uri.parse(MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.uri))
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clippingStartMs)
                    .build())
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, mediaItem);

    assertThat(result.exportResult.optimizationResult)
        .isEqualTo(OPTIMIZATION_ABANDONED_KEYFRAME_PLACEMENT_OPTIMAL_FOR_TRIM);
    // The asset is 15 s 537 ms long.
    assertThat(result.exportResult.approximateDurationMs).isAtMost(1_017);
    assertThat(result.exportResult.videoConversionProcess).isEqualTo(CONVERSION_PROCESS_TRANSCODED);
    assertThat(result.exportResult.audioConversionProcess).isEqualTo(CONVERSION_PROCESS_TRANSMUXED);
    assertThat(new File(result.filePath).length()).isGreaterThan(0);
  }

  @Test
  public void
      clippedMediaWithPositiveEditList_trimOptimizationEnabled_setsFirstVideoTimestampToZero()
          throws Exception {
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(MP4_POSITIVE_SHIFT_EDIT_LIST.uri)
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder().setStartPositionUs(100_000).build())
            .build();
    EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(mediaItem).build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(
                context,
                new Transformer.Builder(context)
                    .experimentalSetTrimOptimizationEnabled(true)
                    .build())
            .build()
            .run(testId, editedMediaItem);

    Mp4Extractor mp4Extractor = new Mp4Extractor(new DefaultSubtitleParserFactory());
    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(mp4Extractor, checkNotNull(result.filePath));
    assertThat(result.exportResult.fileSizeBytes).isGreaterThan(0);
    List<Long> videoTimestampsUs =
        Iterables.getOnlyElement(fakeExtractorOutput.getTrackOutputsForType(C.TRACK_TYPE_VIDEO))
            .getSampleTimesUs();
    assertThat(videoTimestampsUs).hasSize(270);
    assertThat(videoTimestampsUs.get(0)).isEqualTo(0);
    // The second sample is originally at 1_033_333, clipping at 100_000 results in 933_333.
    assertThat(videoTimestampsUs.get(1)).isEqualTo(933_333);
  }

  @Test
  public void
      clippedMediaWithPositiveEditList_trimOptimizationDisbled_setsFirstVideoTimestampToZero()
          throws Exception {
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(MP4_POSITIVE_SHIFT_EDIT_LIST.uri)
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder().setStartPositionUs(100_000).build())
            .build();
    EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(mediaItem).build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, new Transformer.Builder(context).build())
            .build()
            .run(testId, editedMediaItem);

    Mp4Extractor mp4Extractor = new Mp4Extractor(new DefaultSubtitleParserFactory());
    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(mp4Extractor, checkNotNull(result.filePath));
    assertThat(result.exportResult.fileSizeBytes).isGreaterThan(0);
    List<Long> videoTimestampsUs =
        Iterables.getOnlyElement(fakeExtractorOutput.getTrackOutputsForType(C.TRACK_TYPE_VIDEO))
            .getSampleTimesUs();
    assertThat(videoTimestampsUs).hasSize(270);
    assertThat(videoTimestampsUs.get(0)).isEqualTo(0);
    // The second sample is originally at 1_033_333, clipping at 100_000 results in 933_333.
    assertThat(videoTimestampsUs.get(1)).isEqualTo(933_333);
  }

  @Test
  public void clippedMedia_trimOptimizationEnabled_completesWithOptimizationApplied()
      throws Exception {
    if (!isRunningOnEmulator() || SDK_INT < 33) {
      // The trim optimization is only guaranteed to work on emulator for this (emulator-transcoded)
      // file.
      recordTestSkipped(context, testId, /* reason= */ "SDK 33+ Emulator only test");
      assumeTrue(false);
    }
    Transformer transformer =
        new Transformer.Builder(context).experimentalSetTrimOptimizationEnabled(true).build();
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(MP4_TRIM_OPTIMIZATION.uri)
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(500)
                    .setEndPositionMs(2500)
                    .build())
            .build();
    EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(mediaItem).build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, editedMediaItem);

    assertThat(result.exportResult.optimizationResult).isEqualTo(OPTIMIZATION_SUCCEEDED);
    assertThat(result.exportResult.approximateDurationMs).isAtMost(2000);
    assertThat(result.exportResult.videoConversionProcess)
        .isEqualTo(CONVERSION_PROCESS_TRANSMUXED_AND_TRANSCODED);
    assertThat(result.exportResult.audioConversionProcess).isEqualTo(CONVERSION_PROCESS_TRANSMUXED);
    assertThat(new File(result.filePath).length()).isGreaterThan(0);
  }

  @Test
  public void
      clippedMedia_trimOptimizationEnabled_inputFileRotated270_completesWithOptimizationApplied()
          throws Exception {
    if (!isRunningOnEmulator() || SDK_INT < 33) {
      // The trim optimization is only guaranteed to work on emulator for this (emulator-transcoded)
      // file.
      recordTestSkipped(context, testId, /* reason= */ "SDK 33+ Emulator only test");
      assumeTrue(false);
    }
    Transformer transformer =
        new Transformer.Builder(context).experimentalSetTrimOptimizationEnabled(true).build();
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(MP4_TRIM_OPTIMIZATION_270.uri)
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(500)
                    .setEndPositionMs(2500)
                    .build())
            .build();
    EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(mediaItem).build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, editedMediaItem);

    assertThat(result.exportResult.optimizationResult).isEqualTo(OPTIMIZATION_SUCCEEDED);
    assertThat(result.exportResult.approximateDurationMs).isAtMost(2000);
    assertThat(result.exportResult.videoConversionProcess)
        .isEqualTo(CONVERSION_PROCESS_TRANSMUXED_AND_TRANSCODED);
    assertThat(result.exportResult.audioConversionProcess).isEqualTo(CONVERSION_PROCESS_TRANSMUXED);
    Format format = retrieveTrackFormat(context, result.filePath, C.TRACK_TYPE_VIDEO);
    assertThat(format.rotationDegrees).isEqualTo(270);
  }

  @Test
  public void
      clippedMedia_trimOptimizationEnabled_inputFileRotated180_completesWithOptimizationApplied()
          throws Exception {
    if (!isRunningOnEmulator() || SDK_INT < 33) {
      // The trim optimization is only guaranteed to work on emulator for this (emulator-transcoded)
      // file.
      recordTestSkipped(context, testId, /* reason= */ "SDK 33+ Emulator only test");
      assumeTrue(false);
    }
    Transformer transformer =
        new Transformer.Builder(context).experimentalSetTrimOptimizationEnabled(true).build();
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(MP4_TRIM_OPTIMIZATION_180.uri)
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(500)
                    .setEndPositionMs(2500)
                    .build())
            .build();
    EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(mediaItem).build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, editedMediaItem);

    assertThat(result.exportResult.optimizationResult).isEqualTo(OPTIMIZATION_SUCCEEDED);
    assertThat(result.exportResult.approximateDurationMs).isAtMost(2000);
    assertThat(result.exportResult.videoConversionProcess)
        .isEqualTo(CONVERSION_PROCESS_TRANSMUXED_AND_TRANSCODED);
    assertThat(result.exportResult.audioConversionProcess).isEqualTo(CONVERSION_PROCESS_TRANSMUXED);
    Format format = retrieveTrackFormat(context, result.filePath, C.TRACK_TYPE_VIDEO);
    assertThat(format.rotationDegrees).isEqualTo(180);
  }

  @Test
  public void
      clippedMediaAudioRemovedNoOpEffectAndRotated_trimOptimizationEnabled_completedWithOptimizationAppliedAndCorrectOrientation()
          throws Exception {
    if (!isRunningOnEmulator() || SDK_INT < 33) {
      // The trim optimization is only guaranteed to work on emulator for this (emulator-transcoded)
      // file.
      recordTestSkipped(context, testId, /* reason= */ "SDK 33+ Emulator only test");
      assumeTrue(false);
    }
    Transformer transformer =
        new Transformer.Builder(context).experimentalSetTrimOptimizationEnabled(true).build();
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(MP4_TRIM_OPTIMIZATION.uri)
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(500)
                    .setEndPositionMs(2500)
                    .build())
            .build();
    Effects effects =
        new Effects(
            /* audioProcessors= */ ImmutableList.of(),
            ImmutableList.of(
                new ScaleAndRotateTransformation.Builder().setRotationDegrees(180).build(),
                NO_OP_EFFECT));
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(mediaItem).setRemoveAudio(true).setEffects(effects).build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, editedMediaItem);

    assertThat(result.exportResult.optimizationResult).isEqualTo(OPTIMIZATION_SUCCEEDED);
    assertThat(result.exportResult.approximateDurationMs).isAtMost(2000);
    assertThat(result.exportResult.videoConversionProcess)
        .isEqualTo(CONVERSION_PROCESS_TRANSMUXED_AND_TRANSCODED);
    assertThat(result.exportResult.audioConversionProcess).isEqualTo(CONVERSION_PROCESS_NA);

    Format format = retrieveTrackFormat(context, result.filePath, C.TRACK_TYPE_VIDEO);
    // The video is trim-optimized, so the rotation is performed in MuxerWrapper.
    // The MuxerWrapper rotation is clockwise while the ScaleAndRotateTransformation rotation
    // is counterclockwise.
    // Manually verified that the video has correct rotation.
    assertThat(format.rotationDegrees).isEqualTo(180);
  }

  @Test
  public void videoEditing_trimOptimizationEnabled_fallbackToNormalExport() throws Exception {
    Transformer transformer =
        new Transformer.Builder(context).experimentalSetTrimOptimizationEnabled(true).build();
    if (!isRunningOnEmulator()) {
      // The trim optimization is only guaranteed to work on emulator for this (emulator-transcoded)
      // file.
      recordTestSkipped(context, testId, /* reason= */ "Emulator only test");
      assumeTrue(false);
    }
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(MP4_TRIM_OPTIMIZATION.uri)
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

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, editedMediaItem);

    assertThat(result.exportResult.optimizationResult)
        .isEqualTo(OPTIMIZATION_ABANDONED_TRIM_AND_TRANSCODING_TRANSFORMATION_REQUESTED);
    assertThat(result.exportResult.videoConversionProcess).isEqualTo(CONVERSION_PROCESS_TRANSCODED);
    assertThat(result.exportResult.audioConversionProcess).isEqualTo(CONVERSION_PROCESS_TRANSMUXED);
    assertThat(new File(result.filePath).length()).isGreaterThan(0);
  }

  @Test
  public void speedAdjustedMedia_completesWithCorrectDuration() throws Exception {
    Transformer transformer = new Transformer.Builder(context).build();
    SpeedProvider speedProvider =
        TestSpeedProvider.createWithStartTimes(
            new long[] {
              0L,
              3 * C.MICROS_PER_SECOND,
              6 * C.MICROS_PER_SECOND,
              9 * C.MICROS_PER_SECOND,
              12 * C.MICROS_PER_SECOND
            },
            new float[] {0.5f, 0.75f, 1f, 1.5f, 2f});
    Pair<AudioProcessor, Effect> speedEffect =
        Effects.createExperimentalSpeedChangingEffect(speedProvider);
    Effects effects =
        new Effects(
            /* audioProcessors= */ ImmutableList.of(speedEffect.first),
            /* videoEffects= */ ImmutableList.of(speedEffect.second));
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(
                MediaItem.fromUri(MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.uri))
            .setEffects(effects)
            .build();
    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, editedMediaItem);

    // The input video is 15.537 seconds.
    // 3 / 0.5 + 3 / 0.75 + 3 + 3 / 1.5 + 3.537 / 2 rounds up to 16_770ms
    MetadataRetriever metadataRetriever =
        new MetadataRetriever.Builder(context, MediaItem.fromUri(result.filePath)).build();
    long actualDurationUs = metadataRetriever.retrieveDurationUs().get();
    assertThat(actualDurationUs).isWithin(50_000).of(16_770_000);
  }

  @Test
  public void setSpeed_withAudioAndVideo_completesWithCorrectDuration() throws Exception {
    AtomicInteger bytes = new AtomicInteger();
    AudioProcessor byteCountingAudioProcessor = createByteCountingAudioProcessor(bytes);
    Transformer transformer = new Transformer.Builder(context).build();
    SpeedProvider speedProvider =
        TestSpeedProvider.createWithStartTimes(
            new long[] {0L, 400_000L, 800_000L, 1_200_000L, 1_600_000L},
            new float[] {0.5f, 0.75f, 1f, 1.5f, 2f});
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(MOV_WITH_PCM_AUDIO.uri))
            .setEffects(
                new Effects(ImmutableList.of(byteCountingAudioProcessor), ImmutableList.of()))
            .setSpeed(speedProvider)
            .build();
    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, editedMediaItem);

    long actualDurationUs;
    try (MetadataRetriever metadataRetriever =
        new MetadataRetriever.Builder(context, MediaItem.fromUri(result.filePath)).build()) {
      actualDurationUs = metadataRetriever.retrieveDurationUs().get();
    }
    // The input file is 2 seconds.
    // 400ms / 0.5 + 400ms / 0.75 + 400ms + 400ms / 1.5 + 400ms / 2 = 2200ms
    assertThat(actualDurationUs).isWithin(35_000).of(2_200_000);
    // Allow sample tolerance equal to number of speed regions.
    assertThat(bytes.get() / 4).isWithin(5).of(105_600);
  }

  @Test
  public void speedAdjustedMedia_removingAudioAndForcingAudioTrack_completesWithCorrectDuration()
      throws Exception {
    Transformer transformer = new Transformer.Builder(context).build();
    SpeedProvider speedProvider =
        TestSpeedProvider.createWithStartTimes(
            new long[] {
              0L,
              3 * C.MICROS_PER_SECOND,
              6 * C.MICROS_PER_SECOND,
              9 * C.MICROS_PER_SECOND,
              12 * C.MICROS_PER_SECOND
            },
            new float[] {0.5f, 0.75f, 1f, 1.5f, 2f});
    Pair<AudioProcessor, Effect> speedEffect =
        Effects.createExperimentalSpeedChangingEffect(speedProvider);
    Effects effects =
        new Effects(
            /* audioProcessors= */ ImmutableList.of(speedEffect.first),
            /* videoEffects= */ ImmutableList.of(speedEffect.second));
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(
                MediaItem.fromUri(MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.uri))
            .setEffects(effects)
            .setRemoveAudio(true)
            .build();
    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence.Builder(editedMediaItem)
                    .experimentalSetForceAudioTrack(true)
                    .build())
            .build();
    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, composition);

    // The input video is 15.537 seconds.
    // 3 / 0.5 + 3 / 0.75 + 3 + 3 / 1.5 + 3.537 / 2 rounds up to 16_770ms
    MetadataRetriever metadataRetriever =
        new MetadataRetriever.Builder(context, MediaItem.fromUri(result.filePath)).build();
    long actualDurationUs = metadataRetriever.retrieveDurationUs().get();
    assertThat(actualDurationUs).isWithin(50_000).of(16_770_000);
  }

  @Test
  public void setSpeed_removingAudioAndForcingAudioTrack_completesWithCorrectDuration()
      throws Exception {
    Transformer transformer = new Transformer.Builder(context).build();
    SpeedProvider speedProvider =
        TestSpeedProvider.createWithStartTimes(
            new long[] {
              0L,
              3 * C.MICROS_PER_SECOND,
              6 * C.MICROS_PER_SECOND,
              9 * C.MICROS_PER_SECOND,
              12 * C.MICROS_PER_SECOND
            },
            new float[] {0.5f, 0.75f, 1f, 1.5f, 2f});
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(
                MediaItem.fromUri(MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.uri))
            .setSpeed(speedProvider)
            .setRemoveAudio(true)
            .build();
    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence.Builder(editedMediaItem)
                    .experimentalSetForceAudioTrack(true)
                    .build())
            .build();
    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, composition);

    // The input video is 15.534 seconds.
    // 3 / 0.5 + 3 / 0.75 + 3 + 3 / 1.5 + 3.537 / 2 rounds up to 16_770ms
    long actualDurationUs;
    try (MetadataRetriever metadataRetriever =
        new MetadataRetriever.Builder(context, MediaItem.fromUri(result.filePath)).build()) {
      actualDurationUs = metadataRetriever.retrieveDurationUs().get();
    }
    assertThat(actualDurationUs).isWithin(50_000).of(16_767_000);
  }

  @Test
  public void videoEncoderFormatUnsupported_completesWithError() throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET.videoFormat,
        /* outputFormat= */ MP4_ASSET.videoFormat);
    Transformer transformer =
        new Transformer.Builder(context)
            .setEncoderFactory(new VideoUnsupportedEncoderFactory(context))
            .build();
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(Uri.parse(MP4_ASSET.uri)))
            .setRemoveAudio(true)
            .build();

    ExportException exception =
        assertThrows(
            ExportException.class,
            () ->
                new TransformerAndroidTestRunner.Builder(context, transformer)
                    .build()
                    .run(testId, editedMediaItem));

    assertThat(exception).hasCauseThat().isInstanceOf(IllegalArgumentException.class);
    assertThat(exception.errorCode).isEqualTo(ExportException.ERROR_CODE_ENCODER_INIT_FAILED);
    assertThat(exception.codecInfo.isVideo).isTrue();
  }

  @Test
  public void durationAdjustedSequence_completesWithCorrectDuration() throws Exception {
    Transformer transformer = new Transformer.Builder(context).build();
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.videoFormat,
        /* outputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.videoFormat);
    ImmutableList<Effect> videoEffects =
        ImmutableList.of(new SpeedChangeEffect(1.5f), new SpeedChangeEffect(2f));
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(
                MediaItem.fromUri(
                    Uri.parse(MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.uri)))
            .setEffects(new Effects(/* audioProcessors= */ ImmutableList.of(), videoEffects))
            .setRemoveAudio(true)
            .build();
    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence.Builder(editedMediaItem, editedMediaItem).build())
            .build();
    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, composition);

    assertThat(result.exportResult.approximateDurationMs).isEqualTo(10_351L);
    assertThat(new File(result.filePath).length()).isGreaterThan(0);
  }

  @Test
  public void durationAdjustedSequence_withForcedAudioTrack_completesWithCorrectDuration()
      throws Exception {
    Transformer transformer = new Transformer.Builder(context).build();
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.videoFormat,
        /* outputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.videoFormat);
    ImmutableList<Effect> videoEffects = ImmutableList.of(new SpeedChangeEffect(1.5f));
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(
                MediaItem.fromUri(
                    Uri.parse(MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.uri)))
            .setEffects(new Effects(/* audioProcessors= */ ImmutableList.of(), videoEffects))
            .setRemoveAudio(true)
            .build();
    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence.Builder(editedMediaItem, editedMediaItem)
                    .experimentalSetForceAudioTrack(true)
                    .build())
            .build();
    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, composition);

    MetadataRetriever metadataRetriever =
        new MetadataRetriever.Builder(context, MediaItem.fromUri(result.filePath)).build();
    long actualDurationUs = metadataRetriever.retrieveDurationUs().get();
    assertThat(actualDurationUs).isWithin(50_000).of(20_720_000);
  }

  @Test
  public void audioVideoTranscodedFromDifferentSequences_producesExpectedResult() throws Exception {
    Transformer transformer = new Transformer.Builder(context).build();
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET.videoFormat,
        /* outputFormat= */ MP4_ASSET.videoFormat);
    ImmutableList<AudioProcessor> audioProcessors = ImmutableList.of(createSonic(1.2f));
    ImmutableList<Effect> videoEffects = ImmutableList.of(RgbFilter.createGrayscaleFilter());
    MediaItem mediaItem = MediaItem.fromUri(Uri.parse(MP4_ASSET_WITH_INCREASING_TIMESTAMPS.uri));
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(mediaItem)
            .setEffects(new Effects(audioProcessors, videoEffects))
            .build();
    ExportTestResult expectedResult =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, editedMediaItem);

    EditedMediaItem audioEditedMediaItem =
        new EditedMediaItem.Builder(mediaItem)
            .setEffects(new Effects(audioProcessors, /* videoEffects= */ ImmutableList.of()))
            .setRemoveVideo(true)
            .build();
    EditedMediaItem videoEditedMediaItem =
        new EditedMediaItem.Builder(mediaItem)
            .setEffects(new Effects(/* audioProcessors= */ ImmutableList.of(), videoEffects))
            .setRemoveAudio(true)
            .build();
    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence.Builder(audioEditedMediaItem).build(),
                new EditedMediaItemSequence.Builder(videoEditedMediaItem).build())
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, composition);

    assertThat(result.exportResult.channelCount)
        .isEqualTo(expectedResult.exportResult.channelCount);
    assertThat(result.exportResult.videoFrameCount)
        .isEqualTo(expectedResult.exportResult.videoFrameCount);
    assertThat(result.exportResult.approximateDurationMs)
        .isEqualTo(expectedResult.exportResult.approximateDurationMs);
    assertThat(new File(result.filePath).length()).isGreaterThan(0);
  }

  @Test
  public void loopingTranscodedAudio_producesExpectedResult() throws Exception {
    Transformer transformer = new Transformer.Builder(context).build();
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET.videoFormat,
        /* outputFormat= */ MP4_ASSET.videoFormat);
    EditedMediaItem audioEditedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP3_ASSET.uri)).build();
    EditedMediaItemSequence loopingAudioSequence =
        new EditedMediaItemSequence.Builder(audioEditedMediaItem, audioEditedMediaItem)
            .setIsLooping(true)
            .build();
    EditedMediaItem videoEditedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET_WITH_INCREASING_TIMESTAMPS.uri))
            .setRemoveAudio(true)
            .build();
    EditedMediaItemSequence videoSequence =
        new EditedMediaItemSequence.Builder(
                videoEditedMediaItem, videoEditedMediaItem, videoEditedMediaItem)
            .build();
    Composition composition =
        new Composition.Builder(loopingAudioSequence, videoSequence).setTransmuxVideo(true).build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, composition);

    assertThat(result.exportResult.processedInputs).hasSize(6);
    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), checkNotNull(result.filePath));
    // Audio encoders on different API levels output different audio durations for the same input.
    // On emulator, API 26 always outputs one access unit (23ms) of audio more than API 33.
    // If the video track is a lot longer than the audio track, then this API difference wouldn't be
    // seen in this check as the duration is determined by the last video frame.
    // However, if the audio track is roughly as long as the video track, this API difference
    // will be seen in result.exportResult.durationMs.
    assertThat(fakeExtractorOutput.seekMap.getDurationUs()).isWithin(50_000).of(3_020_000);
    FakeTrackOutput audioTrackOutput =
        Iterables.getOnlyElement(fakeExtractorOutput.getTrackOutputsForType(C.TRACK_TYPE_AUDIO));
    assertThat(audioTrackOutput.lastFormat.channelCount).isEqualTo(1);
    FakeTrackOutput videoTrackOutput =
        Iterables.getOnlyElement(fakeExtractorOutput.getTrackOutputsForType(C.TRACK_TYPE_VIDEO));
    assertThat(videoTrackOutput.getSampleCount()).isEqualTo(90);
  }

  @Test
  public void loopingTranscodedVideo_producesExpectedResult() throws Exception {
    Transformer transformer = new Transformer.Builder(context).build();
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET.videoFormat,
        /* outputFormat= */ MP4_ASSET.videoFormat);
    EditedMediaItem audioEditedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP3_ASSET.uri)).build();
    EditedMediaItemSequence audioSequence =
        new EditedMediaItemSequence.Builder(
                audioEditedMediaItem, audioEditedMediaItem, audioEditedMediaItem)
            .build();
    EditedMediaItem videoEditedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET.uri)).setRemoveAudio(true).build();
    EditedMediaItemSequence loopingVideoSequence =
        new EditedMediaItemSequence.Builder(videoEditedMediaItem, videoEditedMediaItem)
            .setIsLooping(true)
            .build();
    Composition composition = new Composition.Builder(audioSequence, loopingVideoSequence).build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, composition);

    assertThat(result.exportResult.processedInputs).hasSize(7);
    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), checkNotNull(result.filePath));
    // Audio encoders on different API levels output different audio durations for the same input.
    // On emulator, API 26 always outputs one access unit (23ms) of audio more than API 33.
    // If the video track is a lot longer than the audio track, then this API difference wouldn't be
    // seen in this check as the duration is determined by the last video frame.
    // However, if the audio track is roughly as long as the video track, this API difference
    // will be seen in result.exportResult.durationMs.
    assertThat(fakeExtractorOutput.seekMap.getDurationUs()).isWithin(50_000).of(3_150_000);
    FakeTrackOutput audioTrackOutput =
        Iterables.getOnlyElement(fakeExtractorOutput.getTrackOutputsForType(C.TRACK_TYPE_AUDIO));
    assertThat(audioTrackOutput.lastFormat.channelCount).isEqualTo(1);
    FakeTrackOutput videoTrackOutput =
        Iterables.getOnlyElement(fakeExtractorOutput.getTrackOutputsForType(C.TRACK_TYPE_VIDEO));
    assertThat(videoTrackOutput.getSampleCount()).isEqualTo(92);
  }

  @Test
  public void loopingImage_producesExpectedResult() throws Exception {
    Transformer transformer = new Transformer.Builder(context).build();
    EditedMediaItem audioEditedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP3_ASSET.uri)).build();
    EditedMediaItemSequence audioSequence =
        new EditedMediaItemSequence.Builder(
                audioEditedMediaItem, audioEditedMediaItem, audioEditedMediaItem)
            .build();
    EditedMediaItem imageEditedMediaItem =
        new EditedMediaItem.Builder(
                new MediaItem.Builder().setUri(PNG_ASSET.uri).setImageDurationMs(1000).build())
            .setFrameRate(30)
            .build();
    EditedMediaItemSequence loopingImageSequence =
        new EditedMediaItemSequence.Builder(imageEditedMediaItem, imageEditedMediaItem)
            .setIsLooping(true)
            .build();
    Composition composition = new Composition.Builder(audioSequence, loopingImageSequence).build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, composition);

    assertThat(result.exportResult.processedInputs).hasSize(7);
    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), checkNotNull(result.filePath));
    // Audio encoders on different API levels output different audio durations for the same input.
    // On emulator, API 26 always outputs one access unit (23ms) of audio more than API 33.
    // If the video track is a lot longer than the audio track, then this API difference wouldn't be
    // seen in this check as the duration is determined by the last video frame.
    // However, if the audio track is roughly as long as the video track, this API difference
    // will be seen in result.exportResult.durationMs.
    assertThat(fakeExtractorOutput.seekMap.getDurationUs()).isWithin(50_000).of(3_140_000);
    FakeTrackOutput audioTrackOutput =
        Iterables.getOnlyElement(fakeExtractorOutput.getTrackOutputsForType(C.TRACK_TYPE_AUDIO));
    assertThat(audioTrackOutput.lastFormat.channelCount).isEqualTo(1);
    FakeTrackOutput videoTrackOutput =
        Iterables.getOnlyElement(fakeExtractorOutput.getTrackOutputsForType(C.TRACK_TYPE_VIDEO));
    assertThat(videoTrackOutput.getSampleCount()).isEqualTo(95);
  }

  @Test
  public void loopingImage_loopingSequenceIsLongest_producesExpectedResult() throws Exception {
    Transformer transformer = new Transformer.Builder(context).build();
    EditedMediaItem audioEditedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP3_ASSET.uri)).build();
    EditedMediaItemSequence audioSequence =
        new EditedMediaItemSequence.Builder(audioEditedMediaItem).build();
    EditedMediaItem imageEditedMediaItem =
        new EditedMediaItem.Builder(
                new MediaItem.Builder().setUri(PNG_ASSET.uri).setImageDurationMs(1050).build())
            .setFrameRate(20)
            .build();
    EditedMediaItemSequence loopingImageSequence =
        new EditedMediaItemSequence.Builder(imageEditedMediaItem, imageEditedMediaItem)
            .setIsLooping(true)
            .build();
    Composition composition = new Composition.Builder(audioSequence, loopingImageSequence).build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, composition);

    assertThat(result.exportResult.processedInputs).hasSize(3);
    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), checkNotNull(result.filePath));
    // Audio encoders on different API levels output different audio durations for the same input.
    // On emulator, API 26 always outputs one access unit (23ms) of audio more than API 33.
    // If the video track is a lot longer than the audio track, then this API difference wouldn't be
    // seen in this check as the duration is determined by the last video frame.
    // However, if the audio track is roughly as long as the video track, this API difference
    // will be seen in result.exportResult.durationMs.
    assertThat(fakeExtractorOutput.seekMap.getDurationUs()).isWithin(50_000).of(1_050_000);
    FakeTrackOutput audioTrackOutput =
        Iterables.getOnlyElement(fakeExtractorOutput.getTrackOutputsForType(C.TRACK_TYPE_AUDIO));
    assertThat(audioTrackOutput.lastFormat.channelCount).isEqualTo(1);
  }

  @Test
  public void motionPhoto_withNoDurationSet_exportsVideo() throws Exception {
    Transformer transformer = new Transformer.Builder(context).build();
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ null,
        /* outputFormat= */ JPG_PIXEL_MOTION_PHOTO_ASSET.videoFormat);
    EditedMediaItem motionPhotoItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(JPG_PIXEL_MOTION_PHOTO_ASSET.uri)).build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, motionPhotoItem);

    assertThat(result.exportResult.videoFrameCount)
        .isEqualTo(JPG_PIXEL_MOTION_PHOTO_ASSET.videoFrameCount);
  }

  @Test
  public void motionPhoto_withDurationSet_exportsImage() throws Exception {
    Transformer transformer = new Transformer.Builder(context).build();
    MediaItem motionPhotoItem =
        new MediaItem.Builder()
            .setUri(JPG_PIXEL_MOTION_PHOTO_ASSET.uri)
            .setImageDurationMs(500)
            .build();
    // Downscale to make sure the resolution is supported by the encoder.
    Effect downscalingEffect =
        Presentation.createForWidthAndHeight(
            /* width= */ 480, /* height= */ 360, Presentation.LAYOUT_SCALE_TO_FIT);
    EditedMediaItem motionPhotoEditedItem =
        new EditedMediaItem.Builder(motionPhotoItem)
            .setFrameRate(30)
            .setEffects(
                new Effects(
                    /* audioProcessors= */ ImmutableList.of(),
                    /* videoEffects= */ ImmutableList.of(downscalingEffect)))
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, motionPhotoEditedItem);

    assertThat(result.exportResult.videoFrameCount).isEqualTo(15); // 0.5 sec at 30 fps
  }

  @Test
  public void audioTranscode_processesInInt16Pcm() throws Exception {
    FormatTrackingAudioBufferSink audioFormatTracker = new FormatTrackingAudioBufferSink();

    Transformer transformer = new Transformer.Builder(context).build();
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(Uri.parse(MP4_ASSET.uri)))
            .setEffects(
                new Effects(
                    ImmutableList.of(audioFormatTracker.createTeeAudioProcessor()),
                    /* videoEffects= */ ImmutableList.of()))
            .setRemoveVideo(true)
            .build();

    new TransformerAndroidTestRunner.Builder(context, transformer)
        .build()
        .run(testId, editedMediaItem);

    ImmutableList<AudioFormat> audioFormats = audioFormatTracker.getFlushedAudioFormats();
    assertThat(audioFormats).hasSize(1);
    assertThat(audioFormats.get(0).encoding).isEqualTo(C.ENCODING_PCM_16BIT);
  }

  @Test
  public void audioEditing_monoToStereo_outputsStereo() throws Exception {

    ChannelMixingAudioProcessor channelMixingAudioProcessor = new ChannelMixingAudioProcessor();
    channelMixingAudioProcessor.putChannelMixingMatrix(
        ChannelMixingMatrix.createForConstantGain(
            /* inputChannelCount= */ 1, /* outputChannelCount= */ 2));
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(Uri.parse(MP4_ASSET.uri)))
            .setRemoveVideo(true)
            .setEffects(
                new Effects(
                    ImmutableList.of(channelMixingAudioProcessor),
                    /* videoEffects= */ ImmutableList.of()))
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, new Transformer.Builder(context).build())
            .build()
            .run(testId, editedMediaItem);

    assertThat(result.exportResult.channelCount).isEqualTo(2);
  }

  @Test
  public void audioComposition_noEffects_transmuxes() throws Exception {
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(Uri.parse(MP4_ASSET.uri)))
            .setRemoveVideo(true)
            .build();
    Composition composition =
        new Composition.Builder(new EditedMediaItemSequence.Builder(editedMediaItem).build())
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, new Transformer.Builder(context).build())
            .build()
            .run(testId, composition);

    assertThat(result.exportResult.audioConversionProcess).isEqualTo(CONVERSION_PROCESS_TRANSMUXED);
    assertThat(new File(result.filePath).length()).isGreaterThan(0);
  }

  @Test
  public void transmuxDolbyVisionVideo_whenMuxerDoesNotSupportDolbyVision_transmuxesToHevc()
      throws Exception {
    // Hevc support is available from API 24.
    // The asset has B-frames and B-frame support is available from API 25.
    // Dolby vision support is available from API 33.
    assumeTrue(SDK_INT >= 25 && SDK_INT < 33);
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(Uri.parse(MP4_ASSET_DOLBY_VISION_HDR.uri)))
            .setRemoveAudio(true)
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(
                context,
                new Transformer.Builder(context)
                    .setMuxerFactory(new FrameworkMuxer.Factory())
                    .build())
            .build()
            .run(testId, editedMediaItem);

    Format format = retrieveTrackFormat(context, result.filePath, C.TRACK_TYPE_VIDEO);
    assertThat(format.sampleMimeType).isEqualTo(MimeTypes.VIDEO_H265);
    assertThat(result.exportResult.videoConversionProcess).isEqualTo(CONVERSION_PROCESS_TRANSMUXED);
  }

  @Test
  public void transmuxDolbyVisionVideo_transmuxesSuccessfully() throws Exception {
    Transformer transformer = new Transformer.Builder(context).build();
    MediaItem mediaItem = MediaItem.fromUri(Uri.parse(MP4_ASSET_DOLBY_VISION_HDR.uri));

    ExportTestResult exportTestResult =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, mediaItem);

    Format trackFormat =
        retrieveTrackFormat(context, exportTestResult.filePath, C.TRACK_TYPE_VIDEO);
    assertThat(trackFormat.sampleMimeType).isEqualTo(MimeTypes.VIDEO_DOLBY_VISION);
    assertThat(trackFormat.codecs).isEqualTo("dvhe.08.02");
    assertThat(exportTestResult.exportResult.videoConversionProcess)
        .isEqualTo(CONVERSION_PROCESS_TRANSMUXED);
  }

  @Test
  public void dolbyVisionVideo_noEffects_withInAppMuxer_transmuxesSuccessfully() throws Exception {
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(Uri.parse(MP4_ASSET_DOLBY_VISION_HDR.uri)))
            .setRemoveAudio(true)
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(
                context,
                new Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H265)
                    .setMuxerFactory(new InAppMp4Muxer.Factory())
                    .build())
            .build()
            .run(testId, editedMediaItem);

    MediaExtractorCompat mediaExtractor = new MediaExtractorCompat(context);
    mediaExtractor.setDataSource(Uri.parse(result.filePath), /* offset= */ 0);
    checkState(mediaExtractor.getTrackCount() == 2);
    MediaFormat mediaFormat = mediaExtractor.getTrackFormat(/* trackIndex= */ 0);
    Format format = createFormatFromMediaFormat(mediaFormat);
    assertThat(format.sampleMimeType).isEqualTo(MimeTypes.VIDEO_DOLBY_VISION);
    // HEVC track is compatibility track of dolby vision codec.
    mediaFormat = mediaExtractor.getTrackFormat(/* trackIndex= */ 1);
    format = createFormatFromMediaFormat(mediaFormat);
    assertThat(format.sampleMimeType).isEqualTo(MimeTypes.VIDEO_H265);
    assertThat(result.exportResult.videoConversionProcess).isEqualTo(CONVERSION_PROCESS_TRANSMUXED);
  }

  @Test
  public void audioComposition_compositionEffects_transcodes() throws Exception {
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(Uri.parse(MP4_ASSET.uri)))
            .setRemoveVideo(true)
            .build();
    Composition composition =
        new Composition.Builder(new EditedMediaItemSequence.Builder(editedMediaItem).build())
            .setEffects(
                new Effects(ImmutableList.of(createSonic(/* pitch= */ 2f)), ImmutableList.of()))
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, new Transformer.Builder(context).build())
            .build()
            .run(testId, composition);

    assertThat(result.exportResult.audioConversionProcess).isEqualTo(CONVERSION_PROCESS_TRANSCODED);
    assertThat(new File(result.filePath).length()).isGreaterThan(0);
  }

  @Test
  public void start_audioCompositionWithFirstSequenceOffsetGap_isCorrect() throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET.videoFormat,
        /* outputFormat= */ MP4_ASSET.videoFormat);
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(Uri.parse(MP4_ASSET.uri)))
            .setRemoveVideo(true)
            .build();
    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence.Builder()
                    .addGap(100_000)
                    .addItem(editedMediaItem)
                    .experimentalSetForceAudioTrack(true)
                    .build(),
                new EditedMediaItemSequence.Builder(editedMediaItem).build())
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, new Transformer.Builder(context).build())
            .build()
            .run(testId, composition);

    assertThat(new File(result.filePath).length()).isGreaterThan(0);
    assertThat(result.exportResult.processedInputs).hasSize(3);
  }

  @Test
  public void start_audioVideoCompositionWithSecondSequenceIntervalGap_isCorrect()
      throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET.videoFormat,
        /* outputFormat= */ MP4_ASSET.videoFormat);

    EditedMediaItem videoItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(Uri.parse(MP4_ASSET.uri))).build();
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(
                new MediaItem.Builder()
                    .setUri(MP4_ASSET.uri)
                    .setClippingConfiguration(
                        new MediaItem.ClippingConfiguration.Builder().setEndPositionMs(300).build())
                    .build())
            .setRemoveVideo(true)
            .build();
    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence.Builder(videoItem).build(),
                new EditedMediaItemSequence.Builder()
                    .addItem(editedMediaItem)
                    .addGap(200_000)
                    .addItem(editedMediaItem)
                    .build())
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, new Transformer.Builder(context).build())
            .build()
            .run(testId, composition);

    assertThat(new File(result.filePath).length()).isGreaterThan(0);
    assertThat(result.exportResult.processedInputs).hasSize(4);
  }

  @Test
  public void analyzeAudio_completesSuccessfully() throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.videoFormat,
        /* outputFormat= */ null);
    Transformer transformer = ExperimentalAnalyzerModeFactory.buildAnalyzer(context);
    AtomicInteger audioBytesSeen = new AtomicInteger(/* initialValue= */ 0);
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(
                MediaItem.fromUri(
                    Uri.parse(MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.uri)))
            .setRemoveVideo(true)
            .setEffects(
                new Effects(
                    ImmutableList.of(createByteCountingAudioProcessor(audioBytesSeen)),
                    /* videoEffects= */ ImmutableList.of()))
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, editedMediaItem);

    assertThat(audioBytesSeen.get()).isEqualTo(2_985_984);
    // Confirm no data was written to file.
    assertThat(result.exportResult.averageAudioBitrate).isEqualTo(C.RATE_UNSET_INT);
    assertThat(result.exportResult.fileSizeBytes).isEqualTo(C.LENGTH_UNSET);
  }

  @Test
  public void analyzeVideo_completesSuccessfully() throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.videoFormat,
        /* outputFormat= */ null);
    Transformer transformer = ExperimentalAnalyzerModeFactory.buildAnalyzer(context);
    AtomicInteger videoFramesSeen = new AtomicInteger(/* initialValue= */ 0);
    // Analysis must be added to item effects because composition effects are not applied to single
    // input video.
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(
                MediaItem.fromUri(
                    Uri.parse(MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.uri)))
            .setRemoveAudio(true)
            .setEffects(
                new Effects(
                    /* audioProcessors= */ ImmutableList.of(),
                    ImmutableList.of(createFrameCountingEffect(videoFramesSeen))))
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, editedMediaItem);

    assertThat(videoFramesSeen.get()).isEqualTo(932);
    // Confirm no data was written to file.
    assertThat(result.exportResult.videoFrameCount).isEqualTo(0);
    assertThat(result.exportResult.fileSizeBytes).isEqualTo(C.LENGTH_UNSET);
  }

  @Test
  public void analyzeAudioAndVideo_completesSuccessfully() throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.videoFormat,
        /* outputFormat= */ null);
    Transformer transformer = ExperimentalAnalyzerModeFactory.buildAnalyzer(context);
    AtomicInteger audioBytesSeen = new AtomicInteger(/* initialValue= */ 0);
    AtomicInteger videoFramesSeen = new AtomicInteger(/* initialValue= */ 0);
    // Analysis must be added to item effects because composition effects are not applied to single
    // input video.
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(
                MediaItem.fromUri(
                    Uri.parse(MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.uri)))
            .setEffects(
                new Effects(
                    ImmutableList.of(createByteCountingAudioProcessor(audioBytesSeen)),
                    ImmutableList.of(createFrameCountingEffect(videoFramesSeen))))
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, editedMediaItem);

    assertThat(audioBytesSeen.get()).isEqualTo(2_985_984);
    assertThat(videoFramesSeen.get()).isEqualTo(932);
    // Confirm no data was written to file.
    assertThat(result.exportResult.averageAudioBitrate).isEqualTo(C.RATE_UNSET_INT);
    assertThat(result.exportResult.videoFrameCount).isEqualTo(0);
    assertThat(result.exportResult.fileSizeBytes).isEqualTo(C.LENGTH_UNSET);
  }

  @Test
  public void transcode_withOutputVideoMimeTypeAv1_completesSuccessfully() throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET.videoFormat,
        /* outputFormat= */ MP4_ASSET
            .videoFormat
            .buildUpon()
            .setSampleMimeType(MimeTypes.VIDEO_AV1)
            .setCodecs(null)
            .build());
    MediaItem mediaItem = MediaItem.fromUri(Uri.parse(MP4_ASSET.uri));
    EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(mediaItem).build();
    Transformer transformer =
        new Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_AV1).build();

    ExportTestResult exportTestResult =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, editedMediaItem);
    ExportResult exportResult = exportTestResult.exportResult;

    String actualMimeType =
        retrieveTrackFormat(context, exportTestResult.filePath, C.TRACK_TYPE_VIDEO).sampleMimeType;
    assertThat(actualMimeType).isEqualTo(MimeTypes.VIDEO_AV1);
    assertThat(exportResult.exportException).isNull();
    assertThat(exportResult.approximateDurationMs).isGreaterThan(0);
    assertThat(exportResult.videoMimeType).isEqualTo(MimeTypes.VIDEO_AV1);
  }

  @Test
  public void transcode_withOutputAudioMimeTypeAac_completesSuccessfully() throws Exception {
    MediaItem mediaItem = MediaItem.fromUri(Uri.parse(MP3_ASSET.uri));
    EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(mediaItem).build();
    Transformer transformer =
        new Transformer.Builder(context).setAudioMimeType(MimeTypes.AUDIO_AAC).build();

    ExportTestResult exportTestResult =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, editedMediaItem);
    ExportResult exportResult = exportTestResult.exportResult;

    String actualMimeType =
        retrieveTrackFormat(context, exportTestResult.filePath, C.TRACK_TYPE_AUDIO).sampleMimeType;
    assertThat(actualMimeType).isEqualTo(MimeTypes.AUDIO_AAC);
    assertThat(exportResult.exportException).isNull();
    assertThat(exportResult.approximateDurationMs).isGreaterThan(0);
    assertThat(exportResult.audioMimeType).isEqualTo(MimeTypes.AUDIO_AAC);
  }

  @Test
  public void transmux_audioWithEditList_preservesDuration() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    Transformer transformer = new Transformer.Builder(context).build();
    MediaItem mediaItem =
        MediaItem.fromUri(Uri.parse("asset:///media/mp4/long_edit_list_audioonly.mp4"));

    ExportTestResult exportTestResult =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, mediaItem);

    Mp4Extractor mp4Extractor = new Mp4Extractor(new DefaultSubtitleParserFactory());
    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(mp4Extractor, exportTestResult.filePath);
    assertThat(fakeExtractorOutput.seekMap.getDurationUs()).isEqualTo(1_562_100);
    assertThat(fakeExtractorOutput.numberOfTracks).isEqualTo(1);
    FakeTrackOutput audioTrack = fakeExtractorOutput.trackOutputs.get(0);
    int expectedSampleCount = 68;
    audioTrack.assertSampleCount(expectedSampleCount);
    assertThat(audioTrack.lastFormat.encoderDelay).isEqualTo(742);
    assertThat(audioTrack.getSampleTimeUs(/* index= */ 0)).isEqualTo(0);
    assertThat(audioTrack.getSampleTimeUs(/* index= */ expectedSampleCount - 1))
        .isEqualTo(1_555_736);
  }

  @Test
  public void transmux_videoWithEditList_trimsFirstIdrFrameDuration() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    Transformer transformer = new Transformer.Builder(context).build();
    MediaItem mediaItem =
        MediaItem.fromUri(Uri.parse("asset:///media/mp4/iibbibb_editlist_videoonly.mp4"));

    ExportTestResult exportTestResult =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, mediaItem);

    Mp4Extractor mp4Extractor = new Mp4Extractor(new DefaultSubtitleParserFactory());
    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(mp4Extractor, exportTestResult.filePath);
    assertThat(fakeExtractorOutput.numberOfTracks).isEqualTo(1);
    assertThat(fakeExtractorOutput.seekMap.getDurationUs()).isEqualTo(12_500_000);
    FakeTrackOutput videoTrack = fakeExtractorOutput.trackOutputs.get(0);
    int expectedSampleCount = 13;
    videoTrack.assertSampleCount(expectedSampleCount);
    assertThat(videoTrack.getSampleTimeUs(/* index= */ 0)).isEqualTo(-500_000);
    int sampleIndexWithLargestSampleTime = 10;
    assertThat(videoTrack.getSampleTimeUs(sampleIndexWithLargestSampleTime)).isEqualTo(11_500_000);
    assertThat(videoTrack.getSampleTimeUs(/* index= */ expectedSampleCount - 1))
        .isEqualTo(9_500_000);
  }

  @Test
  public void transcode_shorterAudio_extendsAudioTrack() throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET_WITH_SHORTER_AUDIO.videoFormat,
        /* outputFormat= */ MP4_ASSET_WITH_SHORTER_AUDIO.videoFormat);
    Context context = ApplicationProvider.getApplicationContext();
    Transformer transformer =
        new Transformer.Builder(context)
            .setEncoderFactory(new AndroidTestUtil.ForceEncodeEncoderFactory(context))
            .build();
    MediaItem mediaItem = MediaItem.fromUri(Uri.parse(MP4_ASSET_WITH_SHORTER_AUDIO.uri));

    ExportTestResult exportTestResult =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, mediaItem);

    Mp4Extractor mp4Extractor = new Mp4Extractor(new DefaultSubtitleParserFactory());
    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(mp4Extractor, exportTestResult.filePath);
    assertThat(fakeExtractorOutput.seekMap.getDurationUs()).isWithin(50_000).of(1_250_000);
    assertThat(fakeExtractorOutput.numberOfTracks).isEqualTo(2);
    for (int i = 0; i < fakeExtractorOutput.numberOfTracks; ++i) {
      FakeTrackOutput trackOutput = fakeExtractorOutput.trackOutputs.get(i);
      int sampleCount = trackOutput.getSampleCount();
      assertThat(trackOutput.getSampleTimeUs(/* index= */ 0)).isEqualTo(0);
      if (MimeTypes.isVideo(trackOutput.lastFormat.sampleMimeType)) {
        assertThat(trackOutput.getSampleTimeUs(/* index= */ sampleCount - 1)).isEqualTo(1_183_333);
      } else {
        // Input has 800ms audio. Output should be closer to 1.2s
        // Audio encoders on different API levels output different audio durations for the same
        // input.
        // E.g. on emulator, API 26 always outputs one access unit (23ms) of audio more than API 33.
        assertThat(trackOutput.getSampleTimeUs(/* index= */ sampleCount - 1))
            .isWithin(100_000)
            .of(1_250_000);
      }
    }
  }

  @Test
  public void transcode_shorterAudioSequence_extendsAudioTrack() throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET_WITH_SHORTER_AUDIO.videoFormat,
        /* outputFormat= */ MP4_ASSET_WITH_SHORTER_AUDIO.videoFormat);
    assumeTrue(
        "Old SDKs have large audio encoder buffer, and hits deadlocks due to b/329087277.",
        SDK_INT >= 31);
    Context context = ApplicationProvider.getApplicationContext();
    Transformer transformer = new Transformer.Builder(context).build();
    MediaItem mediaItem = MediaItem.fromUri(Uri.parse(MP4_ASSET_WITH_SHORTER_AUDIO.uri));
    EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(mediaItem).build();

    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence.Builder(editedMediaItem, editedMediaItem).build())
            .build();
    ExportTestResult exportTestResult =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, composition);

    Mp4Extractor mp4Extractor = new Mp4Extractor(new DefaultSubtitleParserFactory());
    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(mp4Extractor, exportTestResult.filePath);
    assertThat(fakeExtractorOutput.seekMap.getDurationUs()).isWithin(50_000).of(2_400_000);
    assertThat(fakeExtractorOutput.numberOfTracks).isEqualTo(2);
    // Check that both video and audio tracks have duration close to 1 second.
    for (int i = 0; i < fakeExtractorOutput.numberOfTracks; ++i) {
      FakeTrackOutput trackOutput = fakeExtractorOutput.trackOutputs.get(i);
      int sampleCount = trackOutput.getSampleCount();
      assertThat(trackOutput.getSampleTimeUs(/* index= */ 0)).isEqualTo(0);
      if (MimeTypes.isVideo(trackOutput.lastFormat.sampleMimeType)) {
        assertThat(trackOutput.getSampleTimeUs(/* index= */ sampleCount - 1)).isEqualTo(2_383_333);
      } else {
        // Input has 800ms audio. Output should be closer to 2.4s.
        // Audio encoders on different API levels output different audio durations for the same
        // input.
        // On emulator, API 26 always outputs one access unit (23ms) of audio more than API 33.
        assertThat(trackOutput.getSampleTimeUs(/* index= */ sampleCount - 1))
            .isWithin(50_000)
            .of(2_400_000);
      }
    }
  }

  @Test
  public void resampledAudio_hasExpectedOutputSampleCount() throws Exception {
    float resamplingRate = 1.5f;
    AtomicInteger readBytes = new AtomicInteger();
    Transformer transformer = new Transformer.Builder(context).build();
    SonicAudioProcessor sonic = new SonicAudioProcessor();
    sonic.setSpeed(resamplingRate);
    sonic.setPitch(resamplingRate);
    Effects effects =
        new Effects(
            /* audioProcessors= */ ImmutableList.of(
                sonic, createByteCountingAudioProcessor(readBytes)),
            /* videoEffects= */ ImmutableList.of());
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(WAV_ASSET.uri)).setEffects(effects).build();

    new TransformerAndroidTestRunner.Builder(context, transformer)
        .build()
        .run(testId, editedMediaItem);
    // The test file contains 44100 samples (1 sec @44.1KHz, mono). We expect to receive 44100 / 1.5
    // samples.
    // TODO: b/361768785 - Remove unexpected last sample when Sonic's resampler returns the right
    //  number of samples.
    assertThat(readBytes.get() / 2).isWithin(1).of(29400);
  }

  @Test
  public void adjustAudioSpeed_to2pt5Speed_hasExpectedOutputSampleCount() throws Exception {
    AtomicInteger readBytes = new AtomicInteger();
    Transformer transformer = new Transformer.Builder(context).build();
    SonicAudioProcessor sonic = new SonicAudioProcessor();
    sonic.setSpeed(2.5f);
    Effects effects =
        new Effects(
            /* audioProcessors= */ ImmutableList.of(
                sonic, createByteCountingAudioProcessor(readBytes)),
            /* videoEffects= */ ImmutableList.of());
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(WAV_ASSET.uri)).setEffects(effects).build();

    new TransformerAndroidTestRunner.Builder(context, transformer)
        .build()
        .run(testId, editedMediaItem);
    // The test file contains 44100 samples (1 sec @44.1KHz, mono). We expect to receive 44100 / 2.5
    // samples.
    assertThat(readBytes.get() / 2).isEqualTo(17640);
  }

  @Test
  public void speedAdjustedMedia_shorterAudioTrack_completesWithCorrectDuration() throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET_WITH_SHORTER_AUDIO.videoFormat,
        /* outputFormat= */ MP4_ASSET_WITH_SHORTER_AUDIO.videoFormat);
    Transformer transformer = new Transformer.Builder(context).build();
    SpeedProvider speedProvider =
        TestSpeedProvider.createWithStartTimes(
            new long[] {0L, 1L * C.MICROS_PER_SECOND}, new float[] {1f, 0.5f});
    Pair<AudioProcessor, Effect> speedEffect =
        Effects.createExperimentalSpeedChangingEffect(speedProvider);
    Effects effects =
        new Effects(
            /* audioProcessors= */ ImmutableList.of(speedEffect.first),
            /* videoEffects= */ ImmutableList.of(speedEffect.second));
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET_WITH_SHORTER_AUDIO.uri))
            .setEffects(effects)
            .build();
    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, editedMediaItem);

    // Last video frame PTS is 1.18333
    // (1.183333 - 1) * 2 + 1 = 1.36667
    // Audio encoders on different API levels output different audio durations for the same input.
    // On emulator, API 26 always outputs one access unit (23ms) of audio more than API 33.
    // If the video track is a lot longer than the audio track, then this API difference wouldn't be
    // seen in this check as the duration is determined by the last video frame.
    // However, if the audio track is roughly as long as the video track, this API difference
    // will be seen in result.exportResult.durationMs.
    MetadataRetriever metadataRetriever =
        new MetadataRetriever.Builder(context, MediaItem.fromUri(result.filePath)).build();
    long actualDurationUs = metadataRetriever.retrieveDurationUs().get();
    assertThat(actualDurationUs).isWithin(50_000).of(1_400_000);
  }

  @Test
  public void setSpeed_shorterAudioTrack_completesWithCorrectDuration() throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET_WITH_SHORTER_AUDIO.videoFormat,
        /* outputFormat= */ MP4_ASSET_WITH_SHORTER_AUDIO.videoFormat);
    Transformer transformer = new Transformer.Builder(context).build();
    SpeedProvider speedProvider =
        TestSpeedProvider.createWithStartTimes(new long[] {0L, 1_000_000}, new float[] {1f, 0.5f});
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET_WITH_SHORTER_AUDIO.uri))
            .setSpeed(speedProvider)
            .build();
    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, editedMediaItem);

    long actualDurationUs;
    try (MetadataRetriever metadataRetriever =
        new MetadataRetriever.Builder(context, MediaItem.fromUri(result.filePath)).build()) {
      actualDurationUs = metadataRetriever.retrieveDurationUs().get();
    }
    assertThat(actualDurationUs).isWithin(50_000).of(1_400_000);
  }

  @Test
  public void setSpeed_withAudioOnly_outputsExpectedNumberOfSamples() throws Exception {
    AtomicInteger readBytes = new AtomicInteger();
    Transformer transformer = new Transformer.Builder(context).build();
    SpeedProvider speedProvider =
        TestSpeedProvider.createWithStartTimes(
            new long[] {0L, 300_000, 600_000}, new float[] {4f, 0.5f, 2f});
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(WAV_ASSET.uri))
            .setSpeed(speedProvider)
            .setEffects(
                new Effects(
                    ImmutableList.of(createByteCountingAudioProcessor(readBytes)),
                    ImmutableList.of()))
            .build();
    new TransformerAndroidTestRunner.Builder(context, transformer)
        .build()
        .run(testId, editedMediaItem);

    // Allow sample tolerance equal to number of speed regions.
    assertThat(readBytes.get() / 2).isWithin(3).of(38588);
  }

  @Test
  public void export_setAudioEncodingProfile_changesProfile() throws Exception {
    assumeFalse(shouldSkipDeviceForAacObjectHeProfileEncoding());
    assumeCanEncodeWithProfile(MimeTypes.AUDIO_AAC, AACObjectHE);
    Context context = ApplicationProvider.getApplicationContext();
    Transformer transformer =
        new Transformer.Builder(context)
            .setEncoderFactory(
                new DefaultEncoderFactory.Builder(context)
                    .setRequestedAudioEncoderSettings(
                        new AudioEncoderSettings.Builder().setProfile(AACObjectHE).build())
                    .build())
            .build();
    MediaItem mediaItem = new MediaItem.Builder().setUri(MP4_ASSET.uri).build();
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(mediaItem).setRemoveVideo(true).build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, editedMediaItem);

    MediaExtractorCompat mediaExtractor = new MediaExtractorCompat(context);
    mediaExtractor.setDataSource(Uri.parse(result.filePath), /* offset= */ 0);
    checkState(mediaExtractor.getTrackCount() == 1);
    MediaFormat mediaFormat = mediaExtractor.getTrackFormat(/* trackIndex= */ 0);
    Format format = createFormatFromMediaFormat(mediaFormat);
    Pair<Integer, Integer> profileAndLevel = CodecSpecificDataUtil.getCodecProfileAndLevel(format);
    assertThat(profileAndLevel.first).isEqualTo(AACObjectHE);
  }

  @Test
  public void export_setAudioEncodingBitrate_configuresEncoderWithRequestedBitrate()
      throws Exception {
    // On API 23, the encoder output format does not seem to contain bitrate, hence the test fails.
    assumeTrue(SDK_INT > 23);
    Context context = ApplicationProvider.getApplicationContext();
    int requestedBitrate = 60_000;
    // The MediaMuxer is not writing the bitrate hence use the InAppMuxer.
    Transformer transformer =
        new Transformer.Builder(context)
            .setMuxerFactory(new InAppMp4Muxer.Factory())
            .setEncoderFactory(
                new DefaultEncoderFactory.Builder(context)
                    .setRequestedAudioEncoderSettings(
                        new AudioEncoderSettings.Builder().setBitrate(requestedBitrate).build())
                    .build())
            .build();
    MediaItem mediaItem = new MediaItem.Builder().setUri(MP4_ASSET.uri).build();
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(mediaItem).setRemoveVideo(true).build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, editedMediaItem);

    MediaExtractorCompat mediaExtractor = new MediaExtractorCompat(context);
    mediaExtractor.setDataSource(Uri.parse(result.filePath), /* offset= */ 0);
    checkState(mediaExtractor.getTrackCount() == 1);
    MediaFormat mediaFormat = mediaExtractor.getTrackFormat(/* trackIndex= */ 0);
    Format format = createFormatFromMediaFormat(mediaFormat);
    // The format contains the requested bitrate but the actual bitrate is generally different.
    assertThat(format.bitrate).isEqualTo(requestedBitrate);
  }

  @Test
  public void export_withHighSampleRate_enocdesWithCorrectDuration() throws Exception {
    Transformer transformer =
        new Transformer.Builder(context).setAudioMimeType(MimeTypes.AUDIO_AAC).build();
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(WAV_96KHZ_ASSET.uri))
            .setRemoveVideo(true)
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, editedMediaItem);

    // The original clip is 1 second long.
    MetadataRetriever metadataRetriever =
        new MetadataRetriever.Builder(context, MediaItem.fromUri(result.filePath)).build();
    long actualDurationUs = metadataRetriever.retrieveDurationUs().get();
    assertThat(actualDurationUs).isWithin(70_000).of(1_000_000);
  }

  @Test
  public void export_withMultipleHighSampleRatesAndFallbackEnabled_exportsWithCorrectDuration()
      throws Exception {
    Transformer transformer =
        new Transformer.Builder(context)
            .setEncoderFactory(
                new DefaultEncoderFactory.Builder(context).setEnableFallback(true).build())
            .build();
    EditedMediaItemSequence audioSequence =
        new EditedMediaItemSequence.Builder(
                new EditedMediaItem.Builder(MediaItem.fromUri(WAV_192KHZ_ASSET.uri))
                    .setRemoveVideo(true)
                    .build(),
                new EditedMediaItem.Builder(MediaItem.fromUri(WAV_ASSET.uri))
                    .setRemoveVideo(true)
                    .build(),
                new EditedMediaItem.Builder(MediaItem.fromUri(WAV_96KHZ_ASSET.uri))
                    .setRemoveVideo(true)
                    .build())
            .build();
    Composition composition = new Composition.Builder(audioSequence).build();

    ExportTestResult result;
    try {
      result =
          new TransformerAndroidTestRunner.Builder(context, transformer)
              .build()
              .run(testId, composition);
    } catch (ExportException e) {
      if (e.codecInfo.isDecoder) {
        recordTestSkipped(
            context,
            testId,
            /* reason= */ "Ignore decoder failures, as some devices cannot decode 192KHz");
        assumeTrue(false);
      }
      throw e;
    }
    // Each original clip is 1 second long.
    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), checkNotNull(result.filePath));
    assertThat(fakeExtractorOutput.seekMap.getDurationUs()).isWithin(150_000).of(3_000_000);
  }

  @Test
  public void export_withMutingGainProvider_processesMutedAudio() throws Exception {
    Transformer transformer = new Transformer.Builder(context).build();
    DefaultGainProvider provider = new DefaultGainProvider.Builder(/* defaultGain= */ 0f).build();
    GainProcessor gainProcessor = new GainProcessor(provider);
    TeeAudioProcessor teeAudioProcessor =
        new TeeAudioProcessor(
            new TeeAudioProcessor.AudioBufferSink() {
              @Override
              public void flush(int sampleRateHz, int channelCount, @C.PcmEncoding int encoding) {}

              @Override
              public void handleBuffer(ByteBuffer buffer) {
                ShortBuffer samplesBuffer = buffer.asShortBuffer();
                while (samplesBuffer.hasRemaining()) {
                  assertThat(samplesBuffer.get()).isEqualTo(0);
                }
              }
            });
    EditedMediaItem item =
        new EditedMediaItem.Builder(MediaItem.fromUri(WAV_ASSET.uri))
            .setEffects(
                new Effects(ImmutableList.of(gainProcessor, teeAudioProcessor), ImmutableList.of()))
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer).build().run(testId, item);

    MetadataRetriever metadataRetriever =
        new MetadataRetriever.Builder(context, MediaItem.fromUri(result.filePath)).build();
    long actualDurationUs = metadataRetriever.retrieveDurationUs().get();
    assertThat(actualDurationUs).isWithin(50_000).of(1_000_000);
  }

  @Test
  public void composition_withOneLoopingSequence_throwsIllegalArgumentException() throws Exception {
    EditedMediaItem item = new EditedMediaItem.Builder(MediaItem.fromUri(WAV_ASSET.uri)).build();
    EditedMediaItemSequence sequence =
        new EditedMediaItemSequence.Builder(item).setIsLooping(true).build();
    assertThrows(IllegalArgumentException.class, () -> new Composition.Builder(sequence).build());
  }

  @Test
  public void composition_withMultipleLoopingSequences_throwsIllegalArgumentException()
      throws Exception {
    EditedMediaItem item = new EditedMediaItem.Builder(MediaItem.fromUri(WAV_ASSET.uri)).build();
    EditedMediaItemSequence firstSequence =
        new EditedMediaItemSequence.Builder(item).setIsLooping(true).build();
    EditedMediaItemSequence secondSequence =
        new EditedMediaItemSequence.Builder(item).setIsLooping(true).build();
    assertThrows(
        IllegalArgumentException.class,
        () -> new Composition.Builder(firstSequence, secondSequence).build());
  }

  @Test
  public void transmux_apvFile_transmuxesSuccessfully() throws Exception {
    String apvFile = "asset:///media/mp4/sample_with_apvc.mp4";
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(Uri.parse(apvFile))).build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, new Transformer.Builder(context).build())
            .build()
            .run(testId, editedMediaItem);

    Format format = retrieveTrackFormat(context, result.filePath, C.TRACK_TYPE_VIDEO);
    assertThat(format.sampleMimeType).isEqualTo(MimeTypes.VIDEO_APV);
    assertThat(result.exportResult.videoConversionProcess).isEqualTo(CONVERSION_PROCESS_TRANSMUXED);
  }

  @Test
  public void videoTranscoding_withGamma22Input_completesWithCorrectFrameCount() throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_GAMMA22_1S.videoFormat,
        /* outputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_GAMMA22_1S.videoFormat);
    Transformer transformer =
        new Transformer.Builder(context)
            .setEncoderFactory(new AndroidTestUtil.ForceEncodeEncoderFactory(context))
            .build();
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(
                new MediaItem.Builder()
                    .setUri(MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_GAMMA22_1S.uri)
                    .build())
            .setRemoveAudio(true)
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, editedMediaItem);

    Mp4Extractor mp4Extractor = new Mp4Extractor(new DefaultSubtitleParserFactory());
    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(mp4Extractor, result.filePath);
    FakeTrackOutput videoTrack = fakeExtractorOutput.trackOutputs.get(0);
    videoTrack.assertSampleCount(
        MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_GAMMA22_1S.videoFrameCount);
    // TODO: b/442367126 - Assert that the output video color space is SDR BT.709.
  }

  @Test
  public void exportMp3File_withFallbackNotEnabled_ignoresDisabledFallbackAndFallbacksToAac()
      throws Exception {
    Composition inputComposition =
        new Composition.Builder(
                new EditedMediaItemSequence.Builder(
                        new EditedMediaItem.Builder(
                                new MediaItem.Builder().setUri(MP3_ASSET.uri).build())
                            .build())
                    .build())
            .build();
    TransformationRequest originalRequest = new TransformationRequest.Builder().build();
    TransformationRequest fallbackRequest =
        new TransformationRequest.Builder().setAudioMimeType(MimeTypes.AUDIO_AAC).build();
    AtomicBoolean listenerCalled = new AtomicBoolean();
    Transformer.Listener listener =
        new Transformer.Listener() {
          @Override
          public void onFallbackApplied(
              Composition composition,
              TransformationRequest originalTransformationRequest,
              TransformationRequest fallbackTransformationRequest) {
            if (composition.equals(inputComposition)
                && originalTransformationRequest.equals(originalRequest)
                && fallbackTransformationRequest.equals(fallbackRequest)) {
              listenerCalled.set(true);
            }
          }
        };
    Transformer transformer = new Transformer.Builder(context).addListener(listener).build();

    // Muxer does not support MP3 format, so fallback.
    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, inputComposition);

    assertThat(result.exportResult.audioMimeType).isEqualTo(MimeTypes.AUDIO_AAC);
    assertThat(listenerCalled.get()).isTrue();
  }

  @Test
  public void
      exportMp3File_withFallbackEnabledAndWithMultipleListeners_fallbacksToAacAndCallsEachOnFallback()
          throws Exception {
    Composition inputComposition =
        new Composition.Builder(
                new EditedMediaItemSequence.Builder(
                        new EditedMediaItem.Builder(
                                new MediaItem.Builder().setUri(MP3_ASSET.uri).build())
                            .build())
                    .build())
            .build();
    TransformationRequest originalRequest = new TransformationRequest.Builder().build();
    TransformationRequest fallbackRequest =
        new TransformationRequest.Builder().setAudioMimeType(MimeTypes.AUDIO_AAC).build();
    AtomicBoolean listener1Called = new AtomicBoolean();
    Transformer.Listener listener1 =
        new Transformer.Listener() {
          @Override
          public void onFallbackApplied(
              Composition composition,
              TransformationRequest originalTransformationRequest,
              TransformationRequest fallbackTransformationRequest) {
            if (composition.equals(inputComposition)
                && originalTransformationRequest.equals(originalRequest)
                && fallbackTransformationRequest.equals(fallbackRequest)) {
              listener1Called.set(true);
            }
          }
        };
    AtomicBoolean listener2Called = new AtomicBoolean();
    Transformer.Listener listener2 =
        new Transformer.Listener() {
          @Override
          public void onFallbackApplied(
              Composition composition,
              TransformationRequest originalTransformationRequest,
              TransformationRequest fallbackTransformationRequest) {
            if (composition.equals(inputComposition)
                && originalTransformationRequest.equals(originalRequest)
                && fallbackTransformationRequest.equals(fallbackRequest)) {
              listener2Called.set(true);
            }
          }
        };
    Transformer transformer =
        new Transformer.Builder(context)
            .setEncoderFactory(
                new DefaultEncoderFactory.Builder(context).setEnableFallback(true).build())
            .addListener(listener1)
            .addListener(listener2)
            .build();

    // Muxer does not support MP3 format, so fallback.
    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, inputComposition);

    assertThat(result.exportResult.audioMimeType).isEqualTo(MimeTypes.AUDIO_AAC);
    assertThat(listener1Called.get()).isTrue();
    assertThat(listener2Called.get()).isTrue();
  }

  @Test
  @SdkSuppress(minSdkVersion = 30) // c2.android.aac.encoder was added in newer android versions.
  public void export_audioWithForceEncoding_encoderDelayIsPreserved() throws Exception {
    Composition inputComposition =
        new Composition.Builder(
                new EditedMediaItemSequence.Builder(
                        new EditedMediaItem.Builder(
                                new MediaItem.Builder().setUri(MP4_ASSET.uri).build())
                            .setRemoveVideo(true)
                            .build())
                    .build())
            .build();
    Transformer transformer =
        new Transformer.Builder(context)
            .setEncoderFactory(new AndroidTestUtil.ForceEncodeEncoderFactory(context))
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, inputComposition);

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), result.filePath);
    assertThat(fakeExtractorOutput.numberOfTracks).isEqualTo(1);
    FakeTrackOutput audioTrack = fakeExtractorOutput.trackOutputs.get(0);
    // The encoder-delay for AAC is 1600 PCM samples.
    assertThat(audioTrack.lastFormat.encoderDelay).isEqualTo(1600);
    assertThat(audioTrack.getSampleTimeUs(/* index= */ 0)).isEqualTo(0);
  }

  private static boolean shouldSkipDeviceForAacObjectHeProfileEncoding() {
    return SDK_INT < 29;
  }

  private static AudioProcessor createSonic(float pitch) {
    SonicAudioProcessor sonic = new SonicAudioProcessor();
    sonic.setPitch(pitch);
    return sonic;
  }

  private final class TestTextureAssetLoaderFactory implements AssetLoader.Factory {

    private final int width;
    private final int height;

    TestTextureAssetLoaderFactory(int width, int height) {
      this.width = width;
      this.height = height;
    }

    @Override
    public TextureAssetLoader createAssetLoader(
        EditedMediaItem editedMediaItem,
        Looper looper,
        AssetLoader.Listener listener,
        CompositionSettings compositionSettings) {
      Format format = new Format.Builder().setWidth(width).setHeight(height).build();
      OnInputFrameProcessedListener frameProcessedListener =
          (texId, syncObject) -> {
            try {
              GlUtil.deleteTexture(texId);
              GlUtil.deleteSyncObject(syncObject);
            } catch (GlUtil.GlException e) {
              throw new VideoFrameProcessingException(e);
            }
          };
      textureAssetLoader =
          new TextureAssetLoader(editedMediaItem, listener, format, frameProcessedListener);
      return textureAssetLoader;
    }
  }

  private static final class VideoUnsupportedEncoderFactory implements Codec.EncoderFactory {

    private final Codec.EncoderFactory encoderFactory;

    public VideoUnsupportedEncoderFactory(Context context) {
      encoderFactory = new DefaultEncoderFactory.Builder(context).build();
    }

    @Override
    public Codec createForAudioEncoding(Format format, @Nullable LogSessionId logSessionId)
        throws ExportException {
      return encoderFactory.createForAudioEncoding(format, logSessionId);
    }

    @Override
    public Codec createForVideoEncoding(Format format, @Nullable LogSessionId logSessionId)
        throws ExportException {
      throw ExportException.createForCodec(
          new IllegalArgumentException(),
          ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
          new ExportException.CodecInfo(
              format.toString(), /* isVideo= */ true, /* isDecoder= */ false, /* name= */ null));
    }

    @Override
    public boolean audioNeedsEncoding() {
      return false;
    }

    @Override
    public boolean videoNeedsEncoding() {
      return true;
    }
  }

  private static final class FormatTrackingAudioBufferSink
      implements TeeAudioProcessor.AudioBufferSink {
    private final ImmutableSet.Builder<AudioFormat> flushedAudioFormats;

    public FormatTrackingAudioBufferSink() {
      this.flushedAudioFormats = new ImmutableSet.Builder<>();
    }

    public TeeAudioProcessor createTeeAudioProcessor() {
      return new TeeAudioProcessor(this);
    }

    @Override
    public void flush(int sampleRateHz, int channelCount, @C.PcmEncoding int encoding) {
      flushedAudioFormats.add(new AudioFormat(sampleRateHz, channelCount, encoding));
    }

    @Override
    public void handleBuffer(ByteBuffer buffer) {
      // Do nothing.
    }

    public ImmutableList<AudioFormat> getFlushedAudioFormats() {
      return flushedAudioFormats.build().asList();
    }
  }
}
