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

import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.test.utils.TestUtil.extractAllSamplesFromFilePath;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.base.Preconditions.checkNotNull;

import android.graphics.SurfaceTexture;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.os.Handler;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.ChannelMixingAudioProcessor;
import androidx.media3.common.audio.ChannelMixingMatrix;
import androidx.media3.common.audio.SonicAudioProcessor;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.mp4.Mp4Extractor;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.test.utils.FakeClock;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.media3.test.utils.PassthroughAudioProcessor;
import androidx.test.core.app.ApplicationProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicReference;

/** Utility class for {@link Transformer} unit tests */
@UnstableApi
public final class TestUtil {

  public static final String ASSET_URI_PREFIX = "asset:///media/";
  public static final String FILE_VIDEO_ONLY = "mp4/sample_18byte_nclx_colr.mp4";
  public static final String FILE_AUDIO_ONLY = "mp4/sample_audio_only.mp4";
  public static final String FILE_AUDIO_VIDEO = "mp4/sample.mp4";
  public static final String FILE_AUDIO_RAW_AAC = "aac/bbb_1ch_8kHz_aac_lc.aac";
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
  public static final String FILE_PNG = "png/media3test.png";
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

  public static String getSubstitutedPath(String originalAssetPath, String newSubDir) {
    return originalAssetPath.replaceFirst("[^/]+/", newSubDir + "/");
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
  public static ImmutableList<Long> getVideoSampleTimesUs(String filePath) throws IOException {
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
  public static ImmutableList<Long> getAudioSampleTimesUs(String filePath) throws IOException {
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

  public static final class FormatCapturingAudioProcessor extends PassthroughAudioProcessor {
    public final AtomicReference<AudioFormat> inputFormat = new AtomicReference<>();

    @Override
    protected AudioFormat onConfigure(AudioFormat inputAudioFormat)
        throws UnhandledAudioFormatException {
      inputFormat.set(inputAudioFormat);
      return super.onConfigure(inputAudioFormat);
    }
  }

  /**
   * A fake implementation of {@link ImageAdapter} for testing.
   *
   * <p>This class simply holds a presentation timestamp and an optional {@link HardwareBuffer}
   * without relying on a real platform {@link android.media.Image}.
   */
  public static final class FakeImageAdapter implements ImageAdapter {
    private final long timestampNs;
    @Nullable private final HardwareBuffer hardwareBuffer;

    public FakeImageAdapter(long timestampNs, @Nullable HardwareBuffer hardwareBuffer) {
      this.timestampNs = timestampNs;
      this.hardwareBuffer = hardwareBuffer;
    }

    @Override
    public long getTimestampNs() {
      return timestampNs;
    }

    @Override
    @Nullable
    public HardwareBuffer getHardwareBuffer() {
      return hardwareBuffer;
    }

    @Override
    @Nullable
    public Image getInternalImage() {
      return null;
    }

    @Override
    public void close() {
      if (hardwareBuffer != null) {
        hardwareBuffer.close();
      }
    }
  }

  /**
   * A fake implementation of {@link ImageReaderAdapter} for testing.
   *
   * <p>Instead of receiving frames from a real hardware {@link Surface}, this fake manages an
   * internal queue of {@link FakeImageAdapter} instances. Tests can simulate frames being queued by
   * calling {@link #notifyFrameQueued(long)}.
   */
  public static final class FakeImageReaderAdapter implements ImageReaderAdapter {
    private final Queue<ImageAdapter> images;
    @Nullable private Consumer<ImageReaderAdapter> listener;
    @Nullable private Handler handler;

    public FakeImageReaderAdapter() {
      images = new ArrayDeque<>();
    }

    @Override
    @Nullable
    public ImageAdapter acquireNextImage() {
      return images.poll();
    }

    @Override
    public Surface getSurface() {
      return new Surface(new SurfaceTexture(/* texName= */ 0));
    }

    @Override
    public void setOnImageAvailableListener(
        Consumer<ImageReaderAdapter> listener, Handler handler) {
      this.handler = handler;
      this.listener = listener;
    }

    @Override
    public void notifyFrameQueued(long presentationTimeUs) {
      @Nullable HardwareBuffer hardwareBuffer = null;
      if (SDK_INT >= 26) {
        hardwareBuffer =
            HardwareBuffer.create(
                /* width= */ 16,
                /* height= */ 16,
                HardwareBuffer.RGBA_8888,
                /* layers= */ 1,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
      }
      images.add(
          new FakeImageAdapter(/* timestampNs= */ presentationTimeUs * 1000, hardwareBuffer));
      if (handler != null && listener != null) {
        handler.post(() -> listener.accept(this));
      }
    }

    @Override
    public void close() {
      while (!images.isEmpty()) {
        checkNotNull(images.poll()).close();
      }
    }
  }

  /** A factory that returns a pre-configured {@link FakeImageReaderAdapter}. */
  public static final class FakeImageReaderAdapterFactory implements ImageReaderAdapter.Factory {

    public FakeImageReaderAdapterFactory() {}

    @Override
    public ImageReaderAdapter create(int width, int height, int format, int maxImages, long usage) {
      return new FakeImageReaderAdapter();
    }
  }
}
