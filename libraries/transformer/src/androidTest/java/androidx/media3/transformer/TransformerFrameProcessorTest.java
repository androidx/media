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

import static androidx.media3.test.utils.AssetInfo.MP4_SIMPLE_ASSET;
import static androidx.media3.test.utils.FormatSupportAssumptions.assumeFormatsSupported;
import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import androidx.annotation.RequiresApi;
import androidx.media3.common.MediaItem;
import androidx.media3.common.VideoCompositorSettings;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.FrameProcessor;
import androidx.media3.effect.AlphaScale;
import androidx.media3.effect.DefaultGlFrameProcessor;
import androidx.media3.effect.HardwareBufferJniWrapper;
import androidx.media3.test.utils.FakeFrameProcessor;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
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

    FakeFrameProcessor.Factory frameProcessorFactory =
        new FakeFrameProcessor.Factory(/* shouldCompleteIncomingFrames= */ true);
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

    FakeFrameProcessor frameProcessor = frameProcessorFactory.createdProcessor;
    FakeFrameProcessor.FramesEvent framesEvent =
        (FakeFrameProcessor.FramesEvent) frameProcessor.getQueuedEvents().get(0);
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
