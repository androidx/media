/*
 * Copyright 2024 The Android Open Source Project
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

import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.test.utils.TestUtil.buildAssetUri;
import static androidx.media3.test.utils.TestUtil.retrieveTrackFormat;
import static androidx.media3.transformer.AndroidTestUtil.PNG_ASSET_URI_STRING;
import static androidx.media3.transformer.AndroidTestUtil.createOpenGlObjects;
import static androidx.media3.transformer.AndroidTestUtil.generateTextureFromBitmap;
import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.opengl.EGLContext;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.OnInputFrameProcessedListener;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlUtil;
import androidx.media3.datasource.DataSourceBitmapLoader;
import androidx.media3.effect.DefaultGlObjectsProvider;
import androidx.media3.effect.DefaultVideoFrameProcessor;
import androidx.media3.effect.Presentation;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.io.IOException;
import java.nio.ByteBuffer;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/** End to end instrumentation test for {@link RawAssetLoader} using {@link Transformer}. */
@RunWith(AndroidJUnit4.class)
public class RawAssetLoaderAndroidTest {
  @Rule public final TestName testName = new TestName();

  private final Context context = ApplicationProvider.getApplicationContext();

  private String testId;

  @Before
  public void setUpTestId() {
    testId = testName.getMethodName();
  }

  @Test
  public void audioTranscoding_withRawAudio_completesWithCorrectDuration() throws Exception {
    String rawAudioUri = "media/wav/sample.wav";
    Format rawAudioFormat =
        retrieveTrackFormat(context, buildAssetUri(rawAudioUri).toString(), C.TRACK_TYPE_AUDIO);
    SettableFuture<RawAssetLoader> rawAssetLoaderFuture = SettableFuture.create();
    Transformer transformer =
        new Transformer.Builder(context)
            .setAssetLoaderFactory(
                new TestRawAssetLoaderFactory(
                    rawAudioFormat, /* videoFormat= */ null, rawAssetLoaderFuture))
            .build();
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(Uri.EMPTY)).setDurationUs(1_000_000).build();
    ListenableFuture<ExportResult> exportCompletionFuture =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .runAsync(testId, editedMediaItem);

    RawAssetLoader rawAssetLoader = rawAssetLoaderFuture.get();
    feedRawAudioDataToAssetLoader(rawAssetLoader, rawAudioUri);

    ExportResult exportResult = exportCompletionFuture.get();
    // The durationMs is the timestamp of the last sample and not the total duration.
    // See b/324245196.
    // Audio encoders on different API versions seems to output slightly different durations, so add
    // 50ms tolerance.
    assertThat(exportResult.durationMs).isAtLeast(975);
    assertThat(exportResult.durationMs).isAtMost(1025);
  }

  @Test
  public void videoTranscoding_withTextureInput_completesWithCorrectFrameCountAndDuration()
      throws Exception {
    Bitmap bitmap =
        new DataSourceBitmapLoader(context).loadBitmap(Uri.parse(PNG_ASSET_URI_STRING)).get();
    DefaultVideoFrameProcessor.Factory videoFrameProcessorFactory =
        new DefaultVideoFrameProcessor.Factory.Builder()
            .setGlObjectsProvider(new DefaultGlObjectsProvider(createOpenGlObjects()))
            .build();
    Format videoFormat =
        new Format.Builder().setWidth(bitmap.getWidth()).setHeight(bitmap.getHeight()).build();
    SettableFuture<RawAssetLoader> rawAssetLoaderFuture = SettableFuture.create();
    Transformer transformer =
        new Transformer.Builder(context)
            .setAssetLoaderFactory(
                new TestRawAssetLoaderFactory(
                    /* audioFormat= */ null, videoFormat, rawAssetLoaderFuture))
            .setVideoFrameProcessorFactory(videoFrameProcessorFactory)
            .build();
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(Uri.EMPTY))
            .setDurationUs(C.MICROS_PER_SECOND)
            .build();
    ListenableFuture<ExportResult> exportCompletionFuture =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .runAsync(testId, editedMediaItem);

    RawAssetLoader rawAssetLoader = rawAssetLoaderFuture.get();
    int firstTextureId = generateTextureFromBitmap(bitmap);
    int secondTextureId = generateTextureFromBitmap(bitmap);
    long lastSampleTimestampUs = C.MICROS_PER_SECOND / 2;
    while (!rawAssetLoader.queueInputTexture(firstTextureId, /* presentationTimeUs= */ 0)) {}
    while (!rawAssetLoader.queueInputTexture(secondTextureId, lastSampleTimestampUs)) {}
    rawAssetLoader.signalEndOfVideoInput();

    ExportResult exportResult = exportCompletionFuture.get();
    assertThat(exportResult.videoFrameCount).isEqualTo(2);
    // The durationMs is the timestamp of the last sample and not the total duration.
    // See b/324245196.
    assertThat(exportResult.durationMs).isEqualTo(lastSampleTimestampUs / 1_000);
  }

  @Test
  public void videoEditing_withTextureInput_completesWithCorrectFrameCountAndDuration()
      throws Exception {
    Bitmap bitmap =
        new DataSourceBitmapLoader(context).loadBitmap(Uri.parse(PNG_ASSET_URI_STRING)).get();
    EGLContext currentContext = createOpenGlObjects();
    DefaultVideoFrameProcessor.Factory videoFrameProcessorFactory =
        new DefaultVideoFrameProcessor.Factory.Builder()
            .setGlObjectsProvider(new DefaultGlObjectsProvider(currentContext))
            .build();
    Format videoFormat =
        new Format.Builder().setWidth(bitmap.getWidth()).setHeight(bitmap.getHeight()).build();
    SettableFuture<RawAssetLoader> rawAssetLoaderFuture = SettableFuture.create();
    Transformer transformer =
        new Transformer.Builder(context)
            .setAssetLoaderFactory(
                new TestRawAssetLoaderFactory(
                    /* audioFormat= */ null, videoFormat, rawAssetLoaderFuture))
            .setVideoFrameProcessorFactory(videoFrameProcessorFactory)
            .build();
    ImmutableList<Effect> videoEffects = ImmutableList.of(Presentation.createForHeight(480));
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(Uri.EMPTY))
            .setDurationUs(C.MICROS_PER_SECOND)
            .setEffects(new Effects(/* audioProcessors= */ ImmutableList.of(), videoEffects))
            .build();
    ListenableFuture<ExportResult> exportCompletionFuture =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .runAsync(testId, editedMediaItem);

    RawAssetLoader rawAssetLoader = rawAssetLoaderFuture.get();
    int firstTextureId = generateTextureFromBitmap(bitmap);
    int secondTextureId = generateTextureFromBitmap(bitmap);
    long lastSampleTimestampUs = C.MICROS_PER_SECOND / 2;
    while (!rawAssetLoader.queueInputTexture(firstTextureId, /* presentationTimeUs= */ 0)) {}
    while (!rawAssetLoader.queueInputTexture(secondTextureId, lastSampleTimestampUs)) {}
    rawAssetLoader.signalEndOfVideoInput();

    ExportResult exportResult = exportCompletionFuture.get();
    assertThat(exportResult.videoFrameCount).isEqualTo(2);
    // The durationMs is the timestamp of the last sample and not the total duration.
    // See b/324245196.
    assertThat(exportResult.durationMs).isEqualTo(lastSampleTimestampUs / 1_000);
  }

  @Test
  public void audioAndVideoTranscoding_withRawData_completesWithCorrectFrameCountAndDuration()
      throws Exception {
    String rawAudioUri = "media/wav/sample.wav";
    Format audioFormat =
        retrieveTrackFormat(context, buildAssetUri(rawAudioUri).toString(), C.TRACK_TYPE_AUDIO);
    Bitmap bitmap =
        new DataSourceBitmapLoader(context).loadBitmap(Uri.parse(PNG_ASSET_URI_STRING)).get();
    DefaultVideoFrameProcessor.Factory videoFrameProcessorFactory =
        new DefaultVideoFrameProcessor.Factory.Builder()
            .setGlObjectsProvider(new DefaultGlObjectsProvider(createOpenGlObjects()))
            .build();
    Format videoFormat =
        new Format.Builder().setWidth(bitmap.getWidth()).setHeight(bitmap.getHeight()).build();
    SettableFuture<RawAssetLoader> rawAssetLoaderFuture = SettableFuture.create();
    Transformer transformer =
        new Transformer.Builder(context)
            .setAssetLoaderFactory(
                new TestRawAssetLoaderFactory(audioFormat, videoFormat, rawAssetLoaderFuture))
            .setVideoFrameProcessorFactory(videoFrameProcessorFactory)
            .build();
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(Uri.EMPTY))
            .setDurationUs(C.MICROS_PER_SECOND)
            .build();
    ListenableFuture<ExportResult> exportCompletionFuture =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .runAsync(testId, editedMediaItem);

    RawAssetLoader rawAssetLoader = rawAssetLoaderFuture.get();
    int firstTextureId = generateTextureFromBitmap(bitmap);
    int secondTextureId = generateTextureFromBitmap(bitmap);
    // Feed audio and video data in parallel so that export is not blocked waiting for all the
    // tracks.
    new Thread(
            () -> {
              // Queue raw audio data.
              try {
                feedRawAudioDataToAssetLoader(rawAssetLoader, rawAudioUri);
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            })
        .start();
    // Queue raw video data.
    while (!rawAssetLoader.queueInputTexture(firstTextureId, /* presentationTimeUs= */ 0)) {}
    while (!rawAssetLoader.queueInputTexture(
        secondTextureId, /* presentationTimeUs= */ C.MICROS_PER_SECOND / 2)) {}
    rawAssetLoader.signalEndOfVideoInput();

    ExportResult exportResult = exportCompletionFuture.get();
    assertThat(exportResult.videoFrameCount).isEqualTo(2);
    // The durationMs is the timestamp of the last audio sample and not the total duration.
    // See b/324245196.
    // Audio encoders on different API versions seems to output slightly different durations, so add
    // 50ms tolerance.
    assertThat(exportResult.durationMs).isAtLeast(975);
    assertThat(exportResult.durationMs).isAtMost(1025);
  }

  private void feedRawAudioDataToAssetLoader(RawAssetLoader rawAssetLoader, String audioAssetUri)
      throws IOException {
    // TODO: b/270695884 - Use media3 extractor to extract the samples.
    MediaExtractor extractor = new MediaExtractor();
    extractor.setDataSource(context.getResources().getAssets().openFd(audioAssetUri));

    // The audio only file should have only one track.
    MediaFormat audioFormat = extractor.getTrackFormat(0);
    checkState(MimeTypes.isAudio(audioFormat.getString(MediaFormat.KEY_MIME)));
    extractor.selectTrack(0);
    int maxSampleSize = 34_000;
    do {
      long samplePresentationTimeUs = extractor.getSampleTime();
      ByteBuffer sampleBuffer = ByteBuffer.allocateDirect(maxSampleSize);
      if (extractor.readSampleData(sampleBuffer, /* offset= */ 0) == -1) {
        break;
      }
      while (true) {
        if (rawAssetLoader.queueAudioData(
            sampleBuffer, samplePresentationTimeUs, /* isLast= */ false)) {
          break;
        }
      }
    } while (extractor.advance());
    extractor.release();
    checkState(rawAssetLoader.queueAudioData(ByteBuffer.allocate(0), 0, /* isLast= */ true));
  }

  private static final class TestRawAssetLoaderFactory implements AssetLoader.Factory {
    private final Format audioFormat;
    private final Format videoFormat;
    private final SettableFuture<RawAssetLoader> assetLoaderSettableFuture;

    public TestRawAssetLoaderFactory(
        @Nullable Format audioFormat,
        @Nullable Format videoFormat,
        SettableFuture<RawAssetLoader> assetLoaderSettableFuture) {
      this.audioFormat = audioFormat;
      this.videoFormat = videoFormat;
      this.assetLoaderSettableFuture = assetLoaderSettableFuture;
    }

    @Override
    public RawAssetLoader createAssetLoader(
        EditedMediaItem editedMediaItem,
        Looper looper,
        AssetLoader.Listener listener,
        AssetLoader.CompositionSettings compositionSettings) {
      OnInputFrameProcessedListener frameProcessedListener =
          (texId, syncObject) -> {
            try {
              GlUtil.deleteTexture(texId);
              GlUtil.deleteSyncObject(syncObject);
            } catch (GlUtil.GlException e) {
              throw new VideoFrameProcessingException(e);
            }
          };
      RawAssetLoader rawAssetLoader =
          new RawAssetLoader(
              editedMediaItem, listener, audioFormat, videoFormat, frameProcessedListener);
      assetLoaderSettableFuture.set(rawAssetLoader);
      return rawAssetLoader;
    }
  }
}
