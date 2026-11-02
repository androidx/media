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

import static androidx.media3.test.utils.AssetInfo.MP4_SIMPLE_ASSET;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import androidx.media3.common.MediaItem;
import androidx.media3.common.video.FrameProcessor;
import androidx.media3.test.utils.FakeFrameProcessor;
import androidx.media3.test.utils.FakeHardwareBufferJniWrapper;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
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
}
