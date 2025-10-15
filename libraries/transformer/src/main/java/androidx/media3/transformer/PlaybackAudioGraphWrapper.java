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

import static androidx.media3.common.util.Util.sampleCountToDurationUs;
import static com.google.common.base.Preconditions.checkNotNull;

import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.AudioProcessor.AudioFormat;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.audio.AudioSink;
import com.google.common.collect.ImmutableList;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Processes input from {@link AudioGraphInputAudioSink} instances, plumbing the data through an
 * {@link AudioGraph} and writing the output to the provided {@link AudioSink}.
 */
/* package */ final class PlaybackAudioGraphWrapper {

  // The index number for the primary sequence.
  private static final int PRIMARY_SEQUENCE_INDEX = 0;

  private final AudioSink finalAudioSink;
  private final AudioMixer.Factory mixerFactory;

  private @MonotonicNonNull AudioGraph audioGraph;
  private int audioGraphInputsCreated;
  private int inputAudioSinksCreated;
  private boolean hasRegisteredPrimaryFormat;
  private AudioFormat outputAudioFormat;
  private long outputFramesWritten;
  private long seekPositionUs;
  private boolean isRenderingStarted;
  private ImmutableList<AudioProcessor> effects;

  /**
   * Creates an instance.
   *
   * @param mixerFactory The {@linkplain AudioMixer.Factory factory} used to {@linkplain
   *     AudioMixer.Factory#create() create} the underlying {@link AudioMixer}.
   * @param finalAudioSink The {@linkplain AudioSink sink} for processed output audio.
   */
  public PlaybackAudioGraphWrapper(AudioMixer.Factory mixerFactory, AudioSink finalAudioSink) {
    this.finalAudioSink = finalAudioSink;
    this.mixerFactory = mixerFactory;
    outputAudioFormat = AudioFormat.NOT_SET;
    effects = ImmutableList.of();
  }

  /** Sets the composition-level audio effects that are applied after mixing. */
  public void setAudioProcessors(List<AudioProcessor> audioProcessors) {
    if (audioGraph != null) {
      throw new UnsupportedOperationException(
          "Setting AudioProcessors after creating the AudioGraph is not supported");
    }
    effects = ImmutableList.copyOf(audioProcessors);
    audioGraph = new AudioGraph(mixerFactory, effects);
  }

  /** Releases any underlying resources. */
  public void release() {
    if (audioGraph != null) {
      audioGraph.reset();
    }
    finalAudioSink.reset();
    finalAudioSink.release();
    audioGraphInputsCreated = 0;
    inputAudioSinksCreated = 0;
  }

  /** Returns an {@link AudioSink} for a single sequence of non-overlapping raw PCM audio. */
  public AudioGraphInputAudioSink createInput(int inputIndex) {
    return new AudioGraphInputAudioSink(new SinkController(inputIndex));
  }

  /**
   * Processes data through the underlying components.
   *
   * @return Whether more data can be processed by immediately calling this method again.
   */
  public boolean processData()
      throws ExportException,
          AudioSink.WriteException,
          AudioSink.InitializationException,
          AudioSink.ConfigurationException {
    // Do not process any data until the input audio sinks have created audio graph inputs.
    if (inputAudioSinksCreated == 0 || inputAudioSinksCreated != audioGraphInputsCreated) {
      return false;
    }

    if (Objects.equals(outputAudioFormat, AudioFormat.NOT_SET)) {
      AudioFormat audioGraphAudioFormat = checkNotNull(audioGraph).getOutputAudioFormat();
      if (Objects.equals(audioGraphAudioFormat, AudioFormat.NOT_SET)) {
        return false;
      }

      finalAudioSink.configure(
          Util.getPcmFormat(audioGraphAudioFormat),
          /* specifiedBufferSize= */ 0,
          /* outputChannels= */ null);
      outputAudioFormat = audioGraphAudioFormat;
    }

    if (checkNotNull(audioGraph).isEnded()) {
      if (finalAudioSink.isEnded()) {
        return false;
      }
      finalAudioSink.playToEndOfStream();
      return false;
    }

    ByteBuffer audioBuffer = checkNotNull(audioGraph).getOutput();
    if (!audioBuffer.hasRemaining()) {
      return false;
    }

    int bytesToWrite = audioBuffer.remaining();
    boolean bufferHandled =
        finalAudioSink.handleBuffer(
            audioBuffer, getBufferPresentationTimeUs(), /* encodedAccessUnitCount= */ 1);
    outputFramesWritten +=
        (bytesToWrite - audioBuffer.remaining()) / outputAudioFormat.bytesPerFrame;
    return bufferHandled;
  }

  private long getBufferPresentationTimeUs() {
    return seekPositionUs
        + sampleCountToDurationUs(outputFramesWritten, outputAudioFormat.sampleRate);
  }

  public void startRendering() {
    finalAudioSink.play();
    isRenderingStarted = true;
  }

  public void stopRendering() {
    if (!isRenderingStarted) {
      // The finalAudioSink cannot be paused more than once.
      return;
    }
    finalAudioSink.pause();
    isRenderingStarted = false;
  }

  public void setVolume(float volume) {
    finalAudioSink.setVolume(volume);
  }

  /**
   * Handles the steps that need to be executed for a seek before seeking the upstream players.
   *
   * @param positionUs The seek position, in microseconds.
   */
  public void startSeek(long positionUs) {
    if (positionUs == C.TIME_UNSET) {
      positionUs = 0;
    }
    stopRendering();
    checkNotNull(audioGraph).blockInput();
    checkNotNull(audioGraph).flush(positionUs);
    finalAudioSink.flush();
    outputFramesWritten = 0;
    seekPositionUs = positionUs;
  }

  /** Handles the steps that need to be executed for a seek after seeking the upstream players. */
  public void endSeek() {
    checkNotNull(audioGraph).unblockInput();
  }

  /** Updates the {@link AudioAttributes} on the {@linkplain #finalAudioSink final audio sink}. */
  public void setAudioAttributes(AudioAttributes attributes) {
    finalAudioSink.setAudioAttributes(attributes);
  }

  private final class SinkController implements AudioGraphInputAudioSink.Controller {
    private final boolean isSequencePrimary;

    public SinkController(int inputIndex) {
      this.isSequencePrimary = inputIndex == PRIMARY_SEQUENCE_INDEX;
      inputAudioSinksCreated++;
    }

    @Nullable
    @Override
    public AudioGraphInput getAudioGraphInput(EditedMediaItem editedMediaItem, Format format)
        throws ExportException {
      if (!isSequencePrimary && !hasRegisteredPrimaryFormat) {
        // Make sure the format corresponding to the primary sequence is registered first to the
        // AudioGraph.
        return null;
      }

      AudioGraphInput audioGraphInput =
          checkNotNull(audioGraph).registerInput(editedMediaItem, format);
      audioGraphInputsCreated++;
      if (isSequencePrimary) {
        hasRegisteredPrimaryFormat = true;
      }
      return audioGraphInput;
    }

    @Override
    public long getCurrentPositionUs(boolean sourceEnded) {
      return finalAudioSink.getCurrentPositionUs(sourceEnded);
    }

    @Override
    public boolean hasPendingData() {
      return finalAudioSink.hasPendingData();
    }
  }
}
