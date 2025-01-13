/*
 * Copyright 2021 The Android Open Source Project
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

import static androidx.media3.common.audio.AudioProcessor.EMPTY_BUFFER;
import static androidx.media3.common.util.Assertions.checkArgument;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.audio.AudioProcessingPipeline;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.AudioProcessor.AudioFormat;
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException;
import androidx.media3.effect.DebugTraceUtil;
import com.google.common.collect.ImmutableList;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Processes raw audio samples. */
/* package */ final class AudioGraph {

  private static final String TAG = "AudioGraph";

  private final List<InputInfo> inputInfos;
  private final AudioMixer mixer;
  private final AudioProcessingPipeline audioProcessingPipeline;

  private AudioFormat mixerAudioFormat;
  private boolean isMixerConfigured;
  private boolean isMixerReady;
  private long pendingStartTimeUs;
  private ByteBuffer mixerOutput;
  private int finishedInputs;

  /**
   * Creates an instance.
   *
   * @param mixerFactory The {@linkplain AudioMixer.Factory factory} used to {@linkplain
   *     AudioMixer.Factory#create() create} the underlying {@link AudioMixer}.
   * @param effects The composition-level audio effects that are applied after mixing.
   */
  public AudioGraph(AudioMixer.Factory mixerFactory, ImmutableList<AudioProcessor> effects) {
    inputInfos = new ArrayList<>();
    mixer = mixerFactory.create();
    mixerAudioFormat = AudioFormat.NOT_SET;
    mixerOutput = EMPTY_BUFFER;
    audioProcessingPipeline = new AudioProcessingPipeline(effects);
  }

  /** Returns whether an {@link AudioFormat} is valid as an input format. */
  public static boolean isInputAudioFormatValid(AudioFormat format) {
    if (format.encoding == Format.NO_VALUE) {
      return false;
    }
    if (format.sampleRate == Format.NO_VALUE) {
      return false;
    }
    if (format.channelCount == Format.NO_VALUE) {
      return false;
    }
    return true;
  }

  /**
   * Returns a new {@link AudioGraphInput} instance.
   *
   * <p>Must be called before {@linkplain #getOutput() accessing output}.
   */
  public AudioGraphInput registerInput(EditedMediaItem editedMediaItem, Format format)
      throws ExportException {
    checkArgument(format.pcmEncoding != Format.NO_VALUE);
    AudioGraphInput audioGraphInput;
    try {
      audioGraphInput = new AudioGraphInput(mixerAudioFormat, editedMediaItem, format);
      if (Objects.equals(mixerAudioFormat, AudioFormat.NOT_SET)) {
        this.mixerAudioFormat = audioGraphInput.getOutputAudioFormat();
        audioProcessingPipeline.configure(mixerAudioFormat);
        audioProcessingPipeline.flush();
      }
    } catch (UnhandledAudioFormatException e) {
      throw ExportException.createForAudioProcessing(
          e, "Error while registering input " + inputInfos.size());
    }
    inputInfos.add(new InputInfo(audioGraphInput));
    DebugTraceUtil.logEvent(
        DebugTraceUtil.COMPONENT_AUDIO_GRAPH,
        DebugTraceUtil.EVENT_REGISTER_NEW_INPUT_STREAM,
        C.TIME_UNSET,
        "%s",
        format);
    return audioGraphInput;
  }

  /**
   * Returns the {@link AudioFormat} of the {@linkplain #getOutput() output}, or {@link
   * AudioFormat#NOT_SET} if no inputs were {@linkplain #registerInput(EditedMediaItem, Format)
   * registered} previously.
   */
  public AudioFormat getOutputAudioFormat() {
    return audioProcessingPipeline.getOutputAudioFormat();
  }

  /**
   * Returns a {@link ByteBuffer} containing output data between the position and limit.
   *
   * <p>The same buffer is returned until it has been fully consumed ({@code position == limit}),
   * unless the graph was {@linkplain #flush() flushed}.
   */
  public ByteBuffer getOutput() throws ExportException {
    if (!ensureMixerReady()) {
      return EMPTY_BUFFER;
    }
    if (!mixer.isEnded()) {
      feedMixer();
    }
    if (!mixerOutput.hasRemaining()) {
      mixerOutput = mixer.getOutput();
    }

    if (audioProcessingPipeline.isOperational()) {
      feedProcessingPipelineFromMixer();
      return audioProcessingPipeline.getOutput();
    }

    return mixerOutput;
  }

  /** Instructs the {@code AudioGraph} to not queue any input buffer. */
  public void blockInput() {
    for (int i = 0; i < inputInfos.size(); i++) {
      inputInfos.get(i).audioGraphInput.blockInput();
    }
  }

  /** Unblocks incoming data if {@linkplain #blockInput() blocked}. */
  public void unblockInput() {
    for (int i = 0; i < inputInfos.size(); i++) {
      inputInfos.get(i).audioGraphInput.unblockInput();
    }
  }

  /**
   * Sets the start time of the audio streams that will enter the audio graph after the next calls
   * to {@link #flush()}, in microseconds.
   */
  public void setPendingStartTimeUs(long startTimeUs) {
    this.pendingStartTimeUs = startTimeUs;
  }

  /** Clears any pending data. */
  public void flush() {
    for (int i = 0; i < inputInfos.size(); i++) {
      InputInfo inputInfo = inputInfos.get(i);
      inputInfo.mixerSourceId = C.INDEX_UNSET;
      inputInfo.audioGraphInput.flush();
    }
    mixer.reset();
    isMixerConfigured = false;
    isMixerReady = false;
    mixerOutput = EMPTY_BUFFER;
    audioProcessingPipeline.flush();
    finishedInputs = 0;
  }

  /**
   * Resets the graph, un-registering inputs and releasing any underlying resources.
   *
   * <p>Call {@link #registerInput(EditedMediaItem, Format)} to prepare the audio graph again.
   */
  public void reset() {
    for (int i = 0; i < inputInfos.size(); i++) {
      inputInfos.get(i).audioGraphInput.release();
    }
    inputInfos.clear();
    mixer.reset();
    audioProcessingPipeline.reset();

    finishedInputs = 0;
    mixerOutput = EMPTY_BUFFER;
    mixerAudioFormat = AudioFormat.NOT_SET;
  }

  /** Returns whether the input has ended and all queued data has been output. */
  public boolean isEnded() {
    if (audioProcessingPipeline.isOperational()) {
      return audioProcessingPipeline.isEnded();
    }
    return isMixerEnded();
  }

  private boolean ensureMixerReady() throws ExportException {
    if (isMixerReady) {
      return true;
    }
    if (!isMixerConfigured) {
      try {
        mixer.configure(mixerAudioFormat, /* bufferSizeMs= */ C.LENGTH_UNSET, pendingStartTimeUs);
      } catch (UnhandledAudioFormatException e) {
        throw ExportException.createForAudioProcessing(e, "Error while configuring mixer");
      }
      isMixerConfigured = true;
    }
    isMixerReady = true;
    for (int i = 0; i < inputInfos.size(); i++) {
      InputInfo inputInfo = inputInfos.get(i);
      if (inputInfo.mixerSourceId != C.INDEX_UNSET) {
        continue; // The source has already been added.
      }
      AudioGraphInput audioGraphInput = inputInfo.audioGraphInput;
      try {
        // Force processing input.
        audioGraphInput.getOutput();
        long sourceStartTimeUs = audioGraphInput.getStartTimeUs();
        if (sourceStartTimeUs == C.TIME_UNSET) {
          isMixerReady = false;
          continue;
        } else if (sourceStartTimeUs == C.TIME_END_OF_SOURCE) {
          continue;
        }
        inputInfo.mixerSourceId =
            mixer.addSource(audioGraphInput.getOutputAudioFormat(), sourceStartTimeUs);
      } catch (UnhandledAudioFormatException e) {
        throw ExportException.createForAudioProcessing(
            e, "Unhandled format while adding source " + inputInfo.mixerSourceId);
      }
    }
    return isMixerReady;
  }

  private void feedMixer() throws ExportException {
    for (int i = 0; i < inputInfos.size(); i++) {
      feedMixerFromInput(inputInfos.get(i));
    }
  }

  private void feedMixerFromInput(InputInfo inputInfo) throws ExportException {
    int sourceId = inputInfo.mixerSourceId;
    if (!mixer.hasSource(sourceId)) {
      return;
    }

    AudioGraphInput input = inputInfo.audioGraphInput;
    if (input.isEnded()) {
      mixer.removeSource(sourceId);
      inputInfo.mixerSourceId = C.INDEX_UNSET;
      finishedInputs++;
      return;
    }

    try {
      mixer.queueInput(sourceId, input.getOutput());
    } catch (UnhandledAudioFormatException e) {
      throw ExportException.createForAudioProcessing(
          e, "AudioGraphInput (sourceId=" + sourceId + ") reconfiguration");
    }
  }

  private void feedProcessingPipelineFromMixer() {
    if (isMixerEnded()) {
      audioProcessingPipeline.queueEndOfStream();
      return;
    }
    audioProcessingPipeline.queueInput(mixerOutput);
  }

  private boolean isMixerEnded() {
    return !mixerOutput.hasRemaining() && finishedInputs >= inputInfos.size() && mixer.isEnded();
  }

  private static final class InputInfo {
    public final AudioGraphInput audioGraphInput;
    public int mixerSourceId;

    public InputInfo(AudioGraphInput audioGraphInput) {
      this.audioGraphInput = audioGraphInput;
      mixerSourceId = C.INDEX_UNSET;
    }
  }
}
