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

import static androidx.media3.common.C.TRACK_TYPE_AUDIO;
import static androidx.media3.common.C.TRACK_TYPE_VIDEO;
import static androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig.CODEC_INFO_RAW;
import static androidx.media3.transformer.TestUtil.ASSET_URI_PREFIX;
import static androidx.media3.transformer.TestUtil.FILE_AUDIO_AMR_NB;
import static androidx.media3.transformer.TestUtil.FILE_AUDIO_RAW;
import static androidx.media3.transformer.TestUtil.FILE_AUDIO_RAW_STEREO_48000KHZ;
import static androidx.media3.transformer.TestUtil.FILE_AUDIO_RAW_VIDEO;
import static androidx.media3.transformer.TestUtil.FILE_AUDIO_VIDEO;
import static androidx.media3.transformer.TestUtil.FILE_VIDEO_ONLY;
import static androidx.media3.transformer.TestUtil.createAudioEffects;
import static androidx.media3.transformer.TestUtil.createVolumeScalingAudioProcessor;
import static androidx.media3.transformer.TestUtil.getDumpFileName;
import static org.junit.Assume.assumeFalse;

import android.content.Context;
import androidx.media3.common.C.TrackType;
import androidx.media3.common.MediaItem;
import androidx.media3.test.utils.DumpFileAsserts;
import androidx.media3.test.utils.TestTransformerBuilder;
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig;
import androidx.test.core.app.ApplicationProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameter;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters;

/**
 * Parameterized end-to-end test for exporting a single {@link MediaItem} or {@link EditedMediaItem}
 * and asserting on the dump (golden) files.
 *
 * <ul>
 *   <li>Video can not be transcoded, because decoder do not decode and OpenGL is not supported with
 *       Robolectric.
 *   <li>Non RAW audio can not be transcoded, because AudioGraph requires decoded data but
 *       Robolectric decoders do not decode.
 *   <li>RAW audio can be transcoded (like apply effects) but the output will remain RAW audio
 *       because Robolectric encoders do not encode.
 * </ul>
 */
@RunWith(ParameterizedRobolectricTestRunner.class)
public final class ParameterizedItemExportTest {

  private static final ImmutableList<String> AUDIO_ONLY_ASSETS =
      ImmutableList.of(
          FILE_AUDIO_RAW,
          FILE_AUDIO_RAW_STEREO_48000KHZ,
          "wav/sample_ima_adpcm.wav",
          FILE_AUDIO_AMR_NB);

  private static final ImmutableList<String> AUDIO_VIDEO_ASSETS =
      ImmutableList.of(FILE_AUDIO_RAW_VIDEO, FILE_AUDIO_VIDEO);

  private static final ImmutableList<String> VIDEO_ONLY_ASSETS = ImmutableList.of(FILE_VIDEO_ONLY);

  private static final ImmutableSet<String> ENCODED_AUDIO_ASSETS =
      ImmutableSet.of(FILE_AUDIO_VIDEO, FILE_AUDIO_AMR_NB);

  @Parameters(name = "{0}")
  public static ImmutableList<String> params() {
    return new ImmutableList.Builder<String>()
        .addAll(AUDIO_ONLY_ASSETS)
        .addAll(VIDEO_ONLY_ASSETS)
        .addAll(AUDIO_VIDEO_ASSETS)
        .build();
  }

  private static ImmutableSet<@TrackType Integer> getTrackTypesForAsset(String assetFile) {
    if (AUDIO_ONLY_ASSETS.contains(assetFile)) {
      return ImmutableSet.of(TRACK_TYPE_AUDIO);
    } else if (VIDEO_ONLY_ASSETS.contains(assetFile)) {
      return ImmutableSet.of(TRACK_TYPE_VIDEO);
    } else if (AUDIO_VIDEO_ASSETS.contains(assetFile)) {
      return ImmutableSet.of(TRACK_TYPE_AUDIO, TRACK_TYPE_VIDEO);
    }
    throw new IllegalArgumentException("Unknown assetFile: " + assetFile);
  }

  @Rule public final TemporaryFolder outputDir = new TemporaryFolder();

  @Parameter public String assetFile;

  private final Context context = ApplicationProvider.getApplicationContext();

  // Only add RAW decoder, so non-RAW audio has no options for decoding.
  // Use an AAC encoder because muxer supports AAC.
  @Rule
  public ShadowMediaCodecConfig shadowMediaCodecConfig =
      ShadowMediaCodecConfig.withCodecs(
          /* decoders= */ ImmutableList.of(CODEC_INFO_RAW),
          /* encoders= */ ImmutableList.of(CODEC_INFO_RAW));

  @Test
  public void export() throws Exception {
    boolean handleAudioAsPcm = !ENCODED_AUDIO_ASSETS.contains(assetFile);
    CapturingMuxer.Factory muxerFactory = new CapturingMuxer.Factory(handleAudioAsPcm);
    Transformer transformer =
        new TestTransformerBuilder(context).setMuxerFactory(muxerFactory).build();

    transformer.start(
        MediaItem.fromUri(ASSET_URI_PREFIX + assetFile), outputDir.newFile().getPath());
    TransformerTestRunner.runLooper(transformer);

    DumpFileAsserts.assertOutput(
        ApplicationProvider.getApplicationContext(),
        muxerFactory.getCreatedMuxer(),
        getDumpFileName(assetFile));
  }

  @Test
  public void generateSilence() throws Exception {
    assumeFalse(AUDIO_ONLY_ASSETS.contains(assetFile));
    CapturingMuxer.Factory muxerFactory = new CapturingMuxer.Factory(/* handleAudioAsPcm= */ true);
    Transformer transformer =
        new TestTransformerBuilder(context).setMuxerFactory(muxerFactory).build();

    EditedMediaItem item =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + assetFile))
            .setRemoveAudio(true)
            .build();
    // Sequence should have both audio and video tracks. Audio will be silent.
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioAndVideoFrom(ImmutableList.of(item)))
            .build();

    transformer.start(composition, outputDir.newFile().getPath());
    TransformerTestRunner.runLooper(transformer);

    DumpFileAsserts.assertOutput(
        ApplicationProvider.getApplicationContext(),
        muxerFactory.getCreatedMuxer(),
        getDumpFileName(assetFile, /* modifications...= */ "silence"));
  }

  @Test
  public void generateSilenceWithItemEffect() throws Exception {
    assumeFalse(VIDEO_ONLY_ASSETS.contains(assetFile));
    assumeFalse(
        "Audio effects in Robolectric tests require PCM input",
        ENCODED_AUDIO_ASSETS.contains(assetFile));
    CapturingMuxer.Factory muxerFactory = new CapturingMuxer.Factory(/* handleAudioAsPcm= */ true);
    Transformer transformer =
        new TestTransformerBuilder(context).setMuxerFactory(muxerFactory).build();

    EditedMediaItem item =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + assetFile))
            .setEffects(createAudioEffects(createVolumeScalingAudioProcessor(0f)))
            .build();
    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence.Builder(getTrackTypesForAsset(assetFile))
                    .addItem(item)
                    .build())
            .build();

    transformer.start(composition, outputDir.newFile().getPath());
    TransformerTestRunner.runLooper(transformer);

    DumpFileAsserts.assertOutput(
        ApplicationProvider.getApplicationContext(),
        muxerFactory.getCreatedMuxer(),
        getDumpFileName(assetFile, /* modifications...= */ "silenceFromEffect"));
  }

  @Test
  public void generateSilenceWithCompositionEffect() throws Exception {
    assumeFalse(VIDEO_ONLY_ASSETS.contains(assetFile));
    assumeFalse(
        "Audio effects in Robolectric tests require PCM input",
        ENCODED_AUDIO_ASSETS.contains(assetFile));
    CapturingMuxer.Factory muxerFactory = new CapturingMuxer.Factory(/* handleAudioAsPcm= */ true);
    Transformer transformer =
        new TestTransformerBuilder(context).setMuxerFactory(muxerFactory).build();

    EditedMediaItem item =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + assetFile)).build();
    Composition composition =
        new Composition.Builder(
                new EditedMediaItemSequence.Builder(getTrackTypesForAsset(assetFile))
                    .addItem(item)
                    .build())
            .setEffects(createAudioEffects(createVolumeScalingAudioProcessor(0f)))
            .build();

    transformer.start(composition, outputDir.newFile().getPath());
    TransformerTestRunner.runLooper(transformer);

    DumpFileAsserts.assertOutput(
        ApplicationProvider.getApplicationContext(),
        muxerFactory.getCreatedMuxer(),
        getDumpFileName(assetFile, /* modifications...= */ "silenceFromEffect"));
  }
}
