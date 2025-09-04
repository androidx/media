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

import static androidx.media3.test.utils.TestUtil.extractAllSamplesFromFilePath;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.base.Preconditions.checkNotNull;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.audio.ChannelMixingAudioProcessor;
import androidx.media3.common.audio.ChannelMixingMatrix;
import androidx.media3.common.audio.SonicAudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.mp4.Mp4Extractor;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.test.utils.FakeClock;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.test.core.app.ApplicationProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.StringJoiner;

/** Utility class for {@link Transformer} unit tests */
@UnstableApi
public final class TestUtil {

  /**
   * A {@link BaseAudioProcessor} implementation that accepts all input formats and copies all input
   * buffers onto the output without modifications.
   */
  public static class PassthroughAudioProcessor extends BaseAudioProcessor {
    @Override
    protected AudioFormat onConfigure(AudioFormat inputAudioFormat)
        throws UnhandledAudioFormatException {
      return inputAudioFormat;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
      if (!inputBuffer.hasRemaining()) {
        return;
      }
      replaceOutputBuffer(inputBuffer.remaining()).put(inputBuffer).flip();
    }
  }

  public static final String ASSET_URI_PREFIX = "asset:///media/";
  public static final String FILE_VIDEO_ONLY = "mp4/sample_18byte_nclx_colr.mp4";
  public static final String FILE_AUDIO_ONLY = "mp4/sample_audio_only.mp4";
  public static final String FILE_AUDIO_VIDEO = "mp4/sample.mp4";
  public static final String FILE_AUDIO_VIDEO_STEREO = "mp4/testvid_1022ms.mp4";
  public static final String FILE_AUDIO_RAW_VIDEO = "mp4/sowt-with-video.mov";
  public static final String FILE_AUDIO_VIDEO_INCREASING_TIMESTAMPS_15S =
      "mp4/sample_with_increasing_timestamps_320w_240h.mp4";
  public static final String FILE_AUDIO_RAW = "wav/sample.wav";
  public static final String FILE_AUDIO_RAW_STEREO_48000KHZ = "wav/sample_rf64.wav";
  public static final String FILE_WITH_SUBTITLES = "mkv/sample_with_srt.mkv";
  public static final String FILE_WITH_SEF_SLOW_MOTION = "mp4/sample_sef_slow_motion.mp4";
  public static final String FILE_AUDIO_AMR_WB = "amr/sample_wb.amr";
  public static final String FILE_AUDIO_AMR_NB = "amr/sample_nb.amr";
  public static final String FILE_AUDIO_AC3_UNSUPPORTED_BY_MUXER = "mp4/sample_ac3.mp4";
  public static final String FILE_UNKNOWN_DURATION =
      "mp4/sample_with_increasing_timestamps_320w_240h_fragmented.mp4";
  public static final String FILE_AUDIO_ELST_SKIP_500MS = "mp4/long_edit_list_audioonly.mp4";
  public static final String FILE_VIDEO_ELST_TRIM_IDR_DURATION =
      "mp4/iibbibb_editlist_videoonly.mp4";
  public static final String FILE_MP4_POSITIVE_SHIFT_EDIT_LIST = "mp4/edit_list_positive_shift.mp4";
  public static final String FILE_MP4_VISUAL_TIMESTAMPS =
      "mp4/internal_emulator_transformer_output_visual_timestamps.mp4";
  public static final String FILE_JPG_PIXEL_MOTION_PHOTO =
      "jpeg/pixel-motion-photo-2-hevc-tracks.jpg";
  public static final String FILE_MP4_TRIM_OPTIMIZATION_270 =
      "mp4/internal_emulator_transformer_output_270_rotated.mp4";
  public static final String FILE_MP4_TRIM_OPTIMIZATION_180 =
      "mp4/internal_emulator_transformer_output_180_rotated.mp4";
  private static final String DUMP_FILE_OUTPUT_DIRECTORY = "transformerdumps";
  private static final String DUMP_FILE_EXTENSION = "dump";

  private TestUtil() {}

  public static Effects createAudioEffects(AudioProcessor... audioProcessors) {
    return new Effects(
        ImmutableList.copyOf(audioProcessors), /* videoEffects= */ ImmutableList.of());
  }

  public static SonicAudioProcessor createSampleRateChangingAudioProcessor(int sampleRate) {
    SonicAudioProcessor sonicAudioProcessor = new SonicAudioProcessor();
    sonicAudioProcessor.setOutputSampleRateHz(sampleRate);
    return sonicAudioProcessor;
  }

  public static SonicAudioProcessor createPitchChangingAudioProcessor(float pitch) {
    SonicAudioProcessor sonicAudioProcessor = new SonicAudioProcessor();
    sonicAudioProcessor.setPitch(pitch);
    return sonicAudioProcessor;
  }

  public static SonicAudioProcessor createSpeedChangingAudioProcessor(float speed) {
    SonicAudioProcessor sonicAudioProcessor = new SonicAudioProcessor();
    sonicAudioProcessor.setSpeed(speed);
    return sonicAudioProcessor;
  }

  public static ChannelMixingAudioProcessor createVolumeScalingAudioProcessor(float scale) {
    ChannelMixingAudioProcessor audioProcessor = new ChannelMixingAudioProcessor();
    for (int channel = 1; channel <= 6; channel++) {
      audioProcessor.putChannelMixingMatrix(
          ChannelMixingMatrix.createForConstantGain(
                  /* inputChannelCount= */ channel, /* outputChannelCount= */ channel)
              .scaleBy(scale));
    }
    return audioProcessor;
  }

  public static ChannelMixingAudioProcessor createChannelCountChangingAudioProcessor(
      int outputChannelCount) {
    ChannelMixingAudioProcessor audioProcessor = new ChannelMixingAudioProcessor();
    for (int inputChannelCount = 1; inputChannelCount <= 2; inputChannelCount++) {
      audioProcessor.putChannelMixingMatrix(
          ChannelMixingMatrix.createForConstantGain(inputChannelCount, outputChannelCount));
    }
    return audioProcessor;
  }

  public static String getDumpFileName(String originalFileName, String... modifications) {
    String fileName = DUMP_FILE_OUTPUT_DIRECTORY + '/' + originalFileName + '/';
    if (modifications.length == 0) {
      fileName += "original";
    } else {
      fileName += String.join("_", modifications);
    }
    return fileName + '.' + DUMP_FILE_EXTENSION;
  }

  /**
   * Returns the file path of the sequence export dump file, based on the item summaries provided.
   *
   * <p>The file path is built such that each item in the sequence is a subdirectory. For example, a
   * sequence with 3 items (audio1.wav, audio2.wav_lowPitch, audio3.wav) has the dump file path:
   * {@code transformerdumps/sequence/audio1.wav/audio2.wav_lowPitch/audio3.wav.dump}.
   */
  public static String getSequenceDumpFilePath(List<String> sequenceItemSummaries) {
    StringJoiner stringJoiner =
        new StringJoiner(
            /* delimiter= */ "/",
            /* prefix= */ DUMP_FILE_OUTPUT_DIRECTORY + "/sequence/",
            /* suffix= */ "." + DUMP_FILE_EXTENSION);
    for (String item : sequenceItemSummaries) {
      stringJoiner.add(item);
    }

    return stringJoiner.toString();
  }

  /** Returns the file path of the composition export dump file, based on the summary provided. */
  public static String getCompositionDumpFilePath(String compositionSummary) {
    return DUMP_FILE_OUTPUT_DIRECTORY
        + "/composition/"
        + compositionSummary
        + "."
        + DUMP_FILE_EXTENSION;
  }

  /**
   * Returns the video timestamps of the given file from the {@link FakeTrackOutput}.
   *
   * @param filePath The {@link String filepath} to get video timestamps for.
   * @return The {@link List} of video timestamps.
   */
  public static List<Long> getVideoSampleTimesUs(String filePath) throws IOException {
    Mp4Extractor mp4Extractor = new Mp4Extractor(new DefaultSubtitleParserFactory());
    FakeExtractorOutput fakeExtractorOutput =
        extractAllSamplesFromFilePath(mp4Extractor, checkNotNull(filePath));
    return Iterables.getOnlyElement(fakeExtractorOutput.getTrackOutputsForType(C.TRACK_TYPE_VIDEO))
        .getSampleTimesUs();
  }

  /**
   * Returns the audio timestamps of the given file from the {@link FakeTrackOutput}.
   *
   * @param filePath The {@link String filepath} to get audio timestamps for.
   * @return The {@link List} of audio timestamps.
   */
  public static List<Long> getAudioSampleTimesUs(String filePath) throws IOException {
    Mp4Extractor mp4Extractor = new Mp4Extractor(new DefaultSubtitleParserFactory());
    FakeExtractorOutput fakeExtractorOutput =
        extractAllSamplesFromFilePath(mp4Extractor, checkNotNull(filePath));
    return Iterables.getOnlyElement(fakeExtractorOutput.getTrackOutputsForType(C.TRACK_TYPE_AUDIO))
        .getSampleTimesUs();
  }

  /**
   * Returns a new {@link CompositionPlayer} built using {@link
   * #createTestCompositionPlayerBuilder()}.
   */
  public static CompositionPlayer createTestCompositionPlayer() {
    return createTestCompositionPlayerBuilder().build();
  }

  /**
   * Returns a new {@link CompositionPlayer.Builder} configured for unit tests.
   *
   * <p>This method sets an auto advancing {@link FakeClock} and {@link
   * ApplicationProvider#getApplicationContext()} as context.
   */
  public static CompositionPlayer.Builder createTestCompositionPlayerBuilder() {
    return new CompositionPlayer.Builder(getApplicationContext())
        .setClock(new FakeClock(/* isAutoAdvancing= */ true));
  }
}
