/*
 * Copyright 2023 The Android Open Source Project
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

import static androidx.media3.test.utils.AssetInfo.MP4_ASSET;
import static androidx.media3.test.utils.FormatSupportAssumptions.assumeFormatsSupported;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.net.Uri;
import androidx.media3.common.Effect;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.audio.ChannelMixingAudioProcessor;
import androidx.media3.common.audio.ChannelMixingMatrix;
import androidx.media3.common.util.Util;
import androidx.media3.effect.DefaultHardwareBufferEffectsPipeline;
import androidx.media3.effect.GlTextureFrameRenderer.Listener.NO_OP;
import androidx.media3.effect.RgbFilter;
import androidx.media3.effect.SingleContextGlObjectsProvider;
import androidx.media3.effect.ndk.HardwareBufferSurfaceRenderer;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SdkSuppress;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.File;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

/** End-to-end instrumentation test for {@link Transformer} with {@link InAppMp4Muxer}. */
@RunWith(Parameterized.class)
public class TransformerWithInAppMp4MuxerEndToEndAndroidTest {
  private static final String MP4_FILE_ASSET_DIRECTORY = "asset:///media/mp4/";
  private static final String H264_MP4 = "sample_no_bframes.mp4";
  private static final String H265_MP4 = "h265_with_metadata_track.mp4";

  @Parameters(name = "{0}")
  public static ImmutableList<String> mediaFiles() {
    return ImmutableList.of(H264_MP4, H265_MP4);
  }

  @Parameter public @MonotonicNonNull String inputFile;

  private final Context context = ApplicationProvider.getApplicationContext();

  @Test
  public void videoEditing_completesSuccessfully() throws Exception {
    runVideoEditingTest("videoEditing_completesSuccessfully", /* usePacketProcessor= */ false);
  }

  // TODO: b/479415308 - Expand API versions below 34 once supported.
  // TODO: b/475744934 - Add more thorough PacketProcessor tests.
  @Test
  @SdkSuppress(minSdkVersion = 34)
  public void videoEditing_withPacketProcessor_completesSuccessfully() throws Exception {
    runVideoEditingTest(
        "videoEditing_withPacketProcessor_completesSuccessfully", /* usePacketProcessor= */ true);
  }

  private void runVideoEditingTest(String testName, boolean usePacketProcessor) throws Exception {
    String testId = testName + "_" + inputFile;
    // Use MP4_ASSET_FORMAT for H265_MP4_ASSET_URI_STRING test skipping as well, because emulators
    // signal a lack of support for H265_MP4's actual format, but pass this test when using
    // MP4_ASSET_FORMAT for skipping.
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET.videoFormat,
        /* outputFormat= */ MP4_ASSET.videoFormat);

    Transformer.Builder transformerBuilder =
        new Transformer.Builder(context).setMuxerFactory(new InAppMp4Muxer.Factory());
    if (usePacketProcessor) {
      GlObjectsProvider singleContextGlObjectsProvider = new SingleContextGlObjectsProvider();
      ListeningExecutorService glExecutorService =
          MoreExecutors.listeningDecorator(Util.newSingleThreadExecutor("PacketProcessor:Effect"));
      HardwareBufferSurfaceRenderer renderer =
          HardwareBufferSurfaceRenderer.create(
              context,
              glExecutorService,
              singleContextGlObjectsProvider,
              NO_OP.INSTANCE,
              /* errorConsumer= */ (e) -> {
                throw new AssertionError(e);
              });
      transformerBuilder.setPacketProcessor(new DefaultHardwareBufferEffectsPipeline(), renderer);
    }
    Transformer transformer = transformerBuilder.build();

    ImmutableList<Effect> videoEffects = ImmutableList.of(RgbFilter.createGrayscaleFilter());
    MediaItem mediaItem = MediaItem.fromUri(Uri.parse(MP4_FILE_ASSET_DIRECTORY + inputFile));
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(mediaItem)
            .setEffects(new Effects(/* audioProcessors= */ ImmutableList.of(), videoEffects))
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, editedMediaItem);

    assertThat(new File(result.filePath).length()).isGreaterThan(0);
  }

  @Test
  public void audioEditing_completesSuccessfully() throws Exception {
    // The test does not need not to be parameterised because it only needs to run for a single
    // audio format (AAC).
    assumeTrue(checkNotNull(inputFile).equals(H264_MP4));
    String testId = "audioEditing_completesSuccessfully";
    Transformer transformer =
        new Transformer.Builder(context).setMuxerFactory(new InAppMp4Muxer.Factory()).build();
    ChannelMixingAudioProcessor channelMixingAudioProcessor = new ChannelMixingAudioProcessor();
    channelMixingAudioProcessor.putChannelMixingMatrix(
        ChannelMixingMatrix.createForConstantGain(
            /* inputChannelCount= */ 1, /* outputChannelCount= */ 2));
    MediaItem mediaItem = MediaItem.fromUri(Uri.parse(MP4_FILE_ASSET_DIRECTORY + H264_MP4));
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(mediaItem)
            .setEffects(
                new Effects(
                    ImmutableList.of(channelMixingAudioProcessor),
                    /* videoEffects= */ ImmutableList.of()))
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, editedMediaItem);

    assertThat(new File(result.filePath).length()).isGreaterThan(0);
  }
}
