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
import androidx.media3.common.VideoCompositorSettings;
import androidx.media3.common.audio.ChannelMixingAudioProcessor;
import androidx.media3.common.audio.ChannelMixingMatrix;
import androidx.media3.common.util.Util;
import androidx.media3.effect.GlTextureFrameCompositor;
import androidx.media3.effect.RgbFilter;
import androidx.media3.effect.SingleContextGlObjectsProvider;
import androidx.test.core.app.ApplicationProvider;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.util.concurrent.ExecutorService;
import kotlinx.coroutines.ExecutorsKt;
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

  @Parameters(name = "{0}, usePacketProcessor={1}")
  public static ImmutableList<Object[]> mediaFiles() {
    ImmutableList.Builder<Object[]> parametersBuilder = new ImmutableList.Builder<>();
    ImmutableList<String> mediaFiles = ImmutableList.of(H264_MP4, H265_MP4);
    ImmutableList<Boolean> usePacketProcessor = ImmutableList.of(true, false);
    for (int i = 0; i < mediaFiles.size(); i++) {
      for (int j = 0; j < usePacketProcessor.size(); j++) {
        parametersBuilder.add(new Object[] {mediaFiles.get(i), usePacketProcessor.get(j)});
      }
    }
    return parametersBuilder.build();
  }

  @Parameter(0)
  public @MonotonicNonNull String inputFile;

  @Parameter(1)
  public boolean usePacketProcessor;

  private final Context context = ApplicationProvider.getApplicationContext();

  @Test
  public void videoEditing_completesSuccessfully() throws Exception {
    String testId = "videoEditing_completesSuccessfully_" + inputFile;
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
      ExecutorService glExecutorService = Util.newSingleThreadExecutor("PacketProcessor:Effect");
      transformerBuilder.setPacketProcessor(
          new GlTextureFrameCompositor(
              context,
              ExecutorsKt.from(glExecutorService),
              singleContextGlObjectsProvider,
              VideoCompositorSettings.DEFAULT),
          singleContextGlObjectsProvider,
          glExecutorService);
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
