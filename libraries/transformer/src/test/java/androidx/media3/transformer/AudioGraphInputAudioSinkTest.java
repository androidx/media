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

import static androidx.media3.test.utils.TestUtil.getNonRandomByteBuffer;
import static com.google.common.truth.Truth.assertThat;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.audio.AudioProcessor.AudioFormat;
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.transformer.AudioGraphInputAudioSink.Controller;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link AudioGraphInputAudioSink}. */
@RunWith(AndroidJUnit4.class)
public class AudioGraphInputAudioSinkTest {

  @Test
  public void hasPendingData_beforeConfiguration_returnsFalse() {
    MockController mockController = new MockController();
    AudioGraphInputAudioSink sink = new AudioGraphInputAudioSink(mockController);

    assertThat(sink.hasPendingData()).isFalse();
  }

  @Test
  public void hasPendingData_withConfiguredSink_returnsBasedOnPlayerPosition() throws Exception {
    AtomicLong currentPlayerPositionUs = new AtomicLong();
    MockController mockController =
        new MockController() {
          @Override
          public long getCurrentPositionUs(boolean sourceEnded) {
            return currentPlayerPositionUs.get();
          }
        };
    AudioGraphInputAudioSink sink = new AudioGraphInputAudioSink(mockController);
    sink.onMediaItemChanged(
        new EditedMediaItem.Builder(MediaItem.EMPTY).build(),
        /* offsetToCompositionTimeUs= */ 0,
        /* offsetToEditedMediaItemStartUs= */ 0,
        /* isLastInSequence= */ true);
    sink.configure(
        Util.getPcmFormat(C.ENCODING_PCM_16BIT, /* channels= */ 2, /* sampleRate= */ 44100),
        0,
        null);

    // Activate new configuration.
    assertThat(
            sink.handleBuffer(
                getNonRandomByteBuffer(1024, 4),
                /* presentationTimeUs= */ 100_000,
                /* encodedAccessUnitCount= */ 1))
        .isFalse();
    // Force AudioGraphInput to handle media item change.
    assertThat(mockController.input.getOutput().hasRemaining()).isFalse();

    // Push samples with timestamp of 100_000us.
    assertThat(
            sink.handleBuffer(
                getNonRandomByteBuffer(1024, 4),
                /* presentationTimeUs= */ 100_000,
                /* encodedAccessUnitCount= */ 1))
        .isTrue();

    // Current player position is 0, so we still have not played out buffer at 100_000.
    assertThat(sink.hasPendingData()).isTrue();

    // Queued buffer ends at 123_220us, so "play" past that buffer.
    currentPlayerPositionUs.set(124_000);

    // No more buffers remaining to be played out.
    assertThat(sink.hasPendingData()).isFalse();
  }

  @Test
  public void isEnded_afterInitialization_returnsTrue() {
    MockController mockController = new MockController();
    AudioGraphInputAudioSink sink = new AudioGraphInputAudioSink(mockController);
    assertThat(sink.isEnded()).isTrue();
  }

  @Test
  public void isEnded_afterConfiguring_returnsTrue() throws Exception {
    MockController mockController = new MockController();
    AudioGraphInputAudioSink sink = new AudioGraphInputAudioSink(mockController);

    sink.configure(
        Util.getPcmFormat(C.ENCODING_PCM_16BIT, /* channels= */ 2, /* sampleRate= */ 44100),
        0,
        null);

    // Configuration only takes effect after subsequent #handleBuffer() call.
    assertThat(sink.isEnded()).isTrue();
  }

  @Test
  public void isEnded_afterConfigureAndHandleBuffer_returnsFalse() throws Exception {
    MockController mockController = new MockController();
    AudioGraphInputAudioSink sink = new AudioGraphInputAudioSink(mockController);

    sink.onMediaItemChanged(
        new EditedMediaItem.Builder(MediaItem.EMPTY).build(),
        /* offsetToCompositionTimeUs= */ 0,
        /* offsetToEditedMediaItemStartUs= */ 0,
        /* isLastInSequence= */ true);
    sink.configure(
        Util.getPcmFormat(C.ENCODING_PCM_16BIT, /* channels= */ 2, /* sampleRate= */ 44100),
        0,
        null);
    boolean unused =
        sink.handleBuffer(
            getNonRandomByteBuffer(1024, 4),
            /* presentationTimeUs= */ 0,
            /* encodedAccessUnitCount= */ 1);

    assertThat(sink.isEnded()).isFalse();
  }

  @Test
  public void isEnded_afterReset_returnsTrue() throws Exception {
    MockController mockController = new MockController();
    AudioGraphInputAudioSink sink = new AudioGraphInputAudioSink(mockController);

    sink.onMediaItemChanged(
        new EditedMediaItem.Builder(MediaItem.EMPTY).build(),
        /* offsetToCompositionTimeUs= */ 0,
        /* offsetToEditedMediaItemStartUs= */ 0,
        /* isLastInSequence= */ true);
    sink.configure(
        Util.getPcmFormat(C.ENCODING_PCM_16BIT, /* channels= */ 2, /* sampleRate= */ 44100),
        0,
        null);
    boolean unused =
        sink.handleBuffer(
            getNonRandomByteBuffer(1024, 4),
            /* presentationTimeUs= */ 0,
            /* encodedAccessUnitCount= */ 1);
    assertThat(sink.isEnded()).isFalse();

    sink.reset();

    assertThat(sink.isEnded()).isTrue();
  }

  @Test
  public void isEnded_withEndedAudioGraphInput_returnsShouldEnd() throws Exception {
    AtomicBoolean shouldEnd = new AtomicBoolean();
    MockController mockController =
        new MockController() {
          @Override
          public boolean shouldEnd() {
            return shouldEnd.get();
          }
        };
    AudioGraphInputAudioSink sink = new AudioGraphInputAudioSink(mockController);
    sink.onMediaItemChanged(
        new EditedMediaItem.Builder(MediaItem.EMPTY).build(),
        /* offsetToCompositionTimeUs= */ 0,
        /* offsetToEditedMediaItemStartUs= */ 0,
        /* isLastInSequence= */ true);
    sink.configure(
        Util.getPcmFormat(C.ENCODING_PCM_16BIT, /* channels= */ 2, /* sampleRate= */ 44100),
        0,
        null);

    // Push buffer to activate previous configuration.
    assertThat(
            sink.handleBuffer(
                getNonRandomByteBuffer(1024, 4),
                /* presentationTimeUs= */ 0,
                /* encodedAccessUnitCount= */ 1))
        .isFalse();
    // Force AudioGraphInput to handle media item change.
    assertThat(mockController.input.getOutput().hasRemaining()).isFalse();
    // Push actual samples.
    assertThat(
            sink.handleBuffer(
                getNonRandomByteBuffer(1024, 4),
                /* presentationTimeUs= */ 0,
                /* encodedAccessUnitCount= */ 1))
        .isTrue();
    // Queue EoS.
    sink.playToEndOfStream();
    // Drain AudioGraphInput until ended.
    drainOutput(mockController.input);

    assertThat(mockController.input.isEnded()).isTrue();
    assertThat(sink.isEnded()).isFalse();

    shouldEnd.set(true);

    assertThat(sink.isEnded()).isTrue();
  }

  @Test
  public void getCurrentPositionUs_afterInitialization_returnsPositionNotSet() {
    MockController mockController = new MockController();
    AudioGraphInputAudioSink sink = new AudioGraphInputAudioSink(mockController);

    assertThat(sink.getCurrentPositionUs(/* sourceEnded= */ false))
        .isEqualTo(AudioSink.CURRENT_POSITION_NOT_SET);
  }

  @Test
  public void getCurrentPositionUs_afterReset_returnsPositionNotSet() throws Exception {
    MockController mockController = new MockController();
    AudioGraphInputAudioSink sink = new AudioGraphInputAudioSink(mockController);

    sink.onMediaItemChanged(
        new EditedMediaItem.Builder(MediaItem.EMPTY).build(),
        /* offsetToCompositionTimeUs= */ 0,
        /* offsetToEditedMediaItemStartUs= */ 0,
        /* isLastInSequence= */ true);
    sink.configure(
        Util.getPcmFormat(C.ENCODING_PCM_16BIT, /* channels= */ 2, /* sampleRate= */ 44100),
        0,
        null);
    boolean unused =
        sink.handleBuffer(
            getNonRandomByteBuffer(1024, 4),
            /* presentationTimeUs= */ 0,
            /* encodedAccessUnitCount= */ 1);

    assertThat(sink.getCurrentPositionUs(/* sourceEnded= */ false))
        .isNotEqualTo(AudioSink.CURRENT_POSITION_NOT_SET);
    sink.reset();
    assertThat(sink.getCurrentPositionUs(/* sourceEnded= */ false))
        .isEqualTo(AudioSink.CURRENT_POSITION_NOT_SET);
  }

  @Test
  public void reset_releasesActiveAudioGraphInputAndNotifiesController() throws Exception {
    AtomicBoolean isInputReleased = new AtomicBoolean();
    MockController mockController =
        new MockController() {
          @Override
          public void onAudioGraphInputReleased() {
            isInputReleased.set(true);
          }
        };
    AudioGraphInputAudioSink sink = new AudioGraphInputAudioSink(mockController);
    sink.onMediaItemChanged(
        new EditedMediaItem.Builder(MediaItem.EMPTY).build(),
        /* offsetToCompositionTimeUs= */ 0,
        /* offsetToEditedMediaItemStartUs= */ 0,
        /* isLastInSequence= */ true);
    sink.configure(
        Util.getPcmFormat(C.ENCODING_PCM_16BIT, /* channels= */ 2, /* sampleRate= */ 44100),
        0,
        null);
    // Queue buffer to activate last configuration.
    boolean unused =
        sink.handleBuffer(
            getNonRandomByteBuffer(1024, 4),
            /* presentationTimeUs= */ 0,
            /* encodedAccessUnitCount= */ 1);
    assertThat(mockController.input).isNotNull();
    assertThat(mockController.input.isReleased()).isFalse();
    AudioGraphInput input = mockController.input;

    sink.reset();

    assertThat(input.isReleased()).isTrue();
    assertThat(isInputReleased.get()).isTrue();
  }

  private static void drainOutput(AudioGraphInput input) throws UnhandledAudioFormatException {
    while (!input.isEnded()) {
      ByteBuffer output = input.getOutput();
      // Consume buffer.
      output.position(output.limit());
    }
  }

  private static class MockController implements Controller {
    @Nullable private AudioGraphInput input;

    @Nullable
    @Override
    public AudioGraphInput getAudioGraphInput(EditedMediaItem editedMediaItem, Format format) {
      try {
        input =
            new AudioGraphInput(
                /* requestedOutputAudioFormat= */ AudioFormat.NOT_SET,
                editedMediaItem,
                /* inputFormat= */ format);
        return input;
      } catch (UnhandledAudioFormatException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public void onAudioGraphInputReleased() {}

    @Override
    public long getCurrentPositionUs(boolean sourceEnded) {
      return 0;
    }

    @Override
    public boolean shouldEnd() {
      return false;
    }
  }
}
