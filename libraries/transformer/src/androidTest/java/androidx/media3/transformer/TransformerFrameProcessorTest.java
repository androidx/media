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
 *
 */
package androidx.media3.transformer;

import static android.graphics.Bitmap.Config.RGBA_1010102;
import static androidx.media3.common.C.MICROS_PER_SECOND;
import static androidx.media3.test.utils.AssetInfo.MP4_SIMPLE_ASSET;
import static androidx.media3.test.utils.AssetInfo.PNG_ASSET;
import static androidx.media3.test.utils.FormatSupportAssumptions.assumeFormatsSupported;
import static androidx.media3.transformer.ExperimentalAnalyzerModeFactory.buildAnalyzer;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.Futures.immediateFuture;

import android.content.Context;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import androidx.annotation.RequiresApi;
import androidx.media3.common.MediaItem;
import androidx.media3.common.VideoCompositorSettings;
import androidx.media3.common.util.BitmapLoader;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.FrameProcessor;
import androidx.media3.common.video.HardwareBufferFrame;
import androidx.media3.effect.AlphaScale;
import androidx.media3.effect.DefaultGlFrameProcessor;
import androidx.media3.effect.HardwareBufferJniWrapper;
import androidx.media3.test.utils.CapturingFrameProcessor;
import androidx.media3.test.utils.FakeFrameProcessor;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/** Unit test for {@link Transformer} and {@link FrameProcessor} integration. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 28)
public class TransformerFrameProcessorTest {
  private final Context context = ApplicationProvider.getApplicationContext();
  @Rule public final TestName testName = new TestName();
  private String testId;

  @Before
  public void setUpTestId() {
    testId = testName.getMethodName();
  }

  // PacketConsumer's underlying input reader requires Image.getHardwareBuffer(), which is only
  // supported on API 28+.
  @Test
  public void export_compositionWithFrameProcessor_populatesRequiredMetadataFields()
      throws Exception {
    // TODO: b/505721737 - Move test to robolectric.
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_SIMPLE_ASSET.videoFormat,
        /* outputFormat= */ MP4_SIMPLE_ASSET.videoFormat);

    FakeFrameProcessor.Factory fakeFrameProcessorFactory =
        new FakeFrameProcessor.Factory(/* shouldCompleteIncomingFrames= */ true);
    CapturingFrameProcessor.Factory frameProcessorFactory =
        new CapturingFrameProcessor.Factory(fakeFrameProcessorFactory);
    Transformer transformer =
        new Transformer.Builder(context)
            .setFrameProcessorFactory(frameProcessorFactory)
            .setNativeHardwareBufferHelpers(new FakeHardwareBufferJniWrapper())
            .build();

    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_SIMPLE_ASSET.uri))
            .setEffects(
                new Effects(
                    /* audioProcessors= */ ImmutableList.of(),
                    /* videoEffects= */ ImmutableList.of(new AlphaScale(0.5f))))
            .build();
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioAndVideoFrom(ImmutableList.of(editedMediaItem)))
            .setVideoCompositorSettings(VideoCompositorSettings.DEFAULT)
            .setEffects(
                new Effects(
                    /* audioProcessors= */ ImmutableList.of(),
                    /* videoEffects= */ ImmutableList.of(new AlphaScale(0.8f))))
            .build();

    new TransformerAndroidTestRunner.Builder(context, transformer).build().run(testId, composition);

    CapturingFrameProcessor frameProcessor = frameProcessorFactory.getCreatedProcessor();
    CapturingFrameProcessor.FramesEvent framesEvent =
        (CapturingFrameProcessor.FramesEvent) frameProcessor.getQueuedEvents().get(0);
    Frame frame = framesEvent.frames.get(0).frame;
    ImmutableMap<String, Object> metadata = frame.getMetadata();

    assertThat(metadata.get(Composition.KEY_COMPOSITION).toString())
        .isEqualTo(composition.toString());
    assertThat(metadata.get(DefaultGlFrameProcessor.KEY_COMPOSITION_SEQUENCE_INDEX)).isEqualTo(0);
    assertThat(metadata.get(Composition.KEY_COMPOSITION_ITEM_INDEX)).isEqualTo(0);
    assertThat(metadata.get(DefaultGlFrameProcessor.KEY_ITEM_EFFECTS))
        .isEqualTo(editedMediaItem.effects.videoEffects);
    assertThat(metadata.get(DefaultGlFrameProcessor.KEY_COMPOSITOR_SETTINGS))
        .isEqualTo(composition.videoCompositorSettings);
    assertThat(metadata.get(DefaultGlFrameProcessor.KEY_COMPOSITION_EFFECTS))
        .isEqualTo(composition.effects.videoEffects);
  }

  @Test
  @SdkSuppress(minSdkVersion = 34)
  public void export_withProgrammaticHdrImage_queuesHardwareBufferWithCorrectFormat()
      throws Exception {
    AtomicInteger receivedFormat = new AtomicInteger();
    // Create a fake BitmapLoader that strictly outputs a true 10-bit SDR/HDR bitmap.
    BitmapLoader fakeBitmapLoader =
        new BitmapLoader() {
          @Override
          public boolean supportsMimeType(String mimeType) {
            return true;
          }

          @Override
          public ListenableFuture<Bitmap> decodeBitmap(byte[] data) {
            return immediateFuture(Bitmap.createBitmap(1, 1, RGBA_1010102));
          }

          @Override
          public ListenableFuture<Bitmap> loadBitmap(Uri uri) {
            return immediateFuture(Bitmap.createBitmap(1, 1, RGBA_1010102));
          }
        };
    FakeFrameProcessor.Factory fakeFrameProcessorFactory =
        new FakeFrameProcessor.Factory(/* shouldCompleteIncomingFrames= */ true);
    FrameProcessor.Factory interceptingFactory =
        (output, listenerExecutor, listener) -> {
          FrameProcessor realProcessor =
              fakeFrameProcessorFactory.create(output, listenerExecutor, listener);

          return new FrameProcessor() {
            @Override
            public boolean queue(List<AsyncFrame> frames) {
              for (AsyncFrame asyncFrame : frames) {
                if (asyncFrame.frame instanceof HardwareBufferFrame) {
                  HardwareBufferFrame hardwareBufferFrame = (HardwareBufferFrame) asyncFrame.frame;
                  // Safely capture the format synchronously before the buffer is closed later
                  receivedFormat.set(hardwareBufferFrame.getHardwareBuffer().getFormat());
                }
              }
              return realProcessor.queue(frames);
            }

            @Override
            public void signalEndOfStream() {
              realProcessor.signalEndOfStream();
            }

            @Override
            public void close() {
              realProcessor.close();
            }
          };
        };
    Transformer transformer =
        buildAnalyzer(
            context,
            new Transformer.Builder(context)
                .setAssetLoaderFactory(new ImageAssetLoader.Factory(context, fakeBitmapLoader))
                .setFrameProcessorFactory(interceptingFactory)
                .setNativeHardwareBufferHelpers(new FakeHardwareBufferJniWrapper())
                .build());
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(PNG_ASSET.uri))
            .setDurationUs(MICROS_PER_SECOND / 2)
            .setFrameRate(30)
            .build();

    new TransformerAndroidTestRunner.Builder(context, transformer)
        .build()
        .run(testId, editedMediaItem);

    assertThat(receivedFormat.get()).isEqualTo(HardwareBuffer.RGBA_1010102);
  }

  /** A no-op {@link HardwareBufferJniWrapper} that always succeeds. */
  @RequiresApi(26)
  private static final class FakeHardwareBufferJniWrapper implements HardwareBufferJniWrapper {
    @Override
    public long nativeCreateEglImageFromHardwareBuffer(
        long displayHandle, HardwareBuffer hardwareBuffer) {
      return 1L;
    }

    @Override
    public boolean nativeBindEGLImage(int target, long eglImageHandle) {
      return true;
    }

    @Override
    public boolean nativeDestroyEGLImage(long displayHandle, long imageHandle) {
      return true;
    }

    @Override
    public boolean nativeCopyBitmapToHardwareBuffer(Bitmap bitmap, HardwareBuffer hb) {
      return true;
    }

    @Override
    public boolean nativeCopyHardwareBufferToHardwareBuffer(
        HardwareBuffer srcHb, HardwareBuffer dstHb) {
      return true;
    }
  }
}
