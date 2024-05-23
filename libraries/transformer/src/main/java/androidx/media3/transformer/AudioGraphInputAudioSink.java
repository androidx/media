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

import static androidx.media3.common.audio.AudioProcessor.EMPTY_BUFFER;
import static androidx.media3.common.util.Assertions.checkArgument;
import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.common.util.Assertions.checkStateNotNull;

import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.AuxEffectInfo;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.audio.AudioSink;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * An {@link AudioSink} implementation that feeds an {@link AudioGraphInput}.
 *
 * <p>Should be used by {@link PreviewAudioPipeline}.
 */
/* package */ final class AudioGraphInputAudioSink implements AudioSink {

  /**
   * Controller for {@link AudioGraphInputAudioSink}.
   *
   * <p>All methods will be called on the playback thread of the ExoPlayer instance writing to this
   * sink.
   */
  public interface Controller {

    /**
     * Returns the {@link AudioGraphInput} instance associated with this {@linkplain
     * AudioGraphInputAudioSink sink}.
     *
     * <p>Data {@linkplain #handleBuffer written} to the sink will be {@linkplain
     * AudioGraphInput#queueInputBuffer() queued} to the {@link AudioGraphInput}.
     *
     * @param editedMediaItem The first {@link EditedMediaItem} queued to the {@link
     *     AudioGraphInput}.
     * @param format The {@link Format} used to {@linkplain AudioGraphInputAudioSink#configure
     *     configure} the {@linkplain AudioGraphInputAudioSink sink}.
     * @return The {@link AudioGraphInput}.
     * @throws ExportException If there is a problem initializing the {@linkplain AudioGraphInput
     *     input}.
     */
    AudioGraphInput getAudioGraphInput(EditedMediaItem editedMediaItem, Format format)
        throws ExportException;

    /**
     * Returns the position (in microseconds) that should be {@linkplain
     * AudioSink#getCurrentPositionUs returned} by this sink.
     */
    long getCurrentPositionUs();

    /** Returns whether the controller is ended. */
    boolean isEnded();

    /** See {@link #play()}. */
    default void onPlay() {}

    /** See {@link #pause()}. */
    default void onPause() {}

    /** See {@link #reset()}. */
    default void onReset() {}
  }

  private final Controller controller;

  @Nullable private AudioGraphInput outputGraphInput;
  @Nullable private Format currentInputFormat;
  private boolean inputStreamEnded;
  private boolean signalledEndOfStream;
  @Nullable private EditedMediaItemInfo currentEditedMediaItemInfo;
  private long offsetToCompositionTimeUs;

  public AudioGraphInputAudioSink(Controller controller) {
    this.controller = controller;
  }

  /**
   * Informs the audio sink there is a change on the {@link EditedMediaItem} currently rendered by
   * the renderer.
   *
   * @param editedMediaItem The {@link EditedMediaItem}.
   * @param offsetToCompositionTimeUs The offset to add to the audio buffer timestamps to convert
   *     them to the composition time, in microseconds.
   * @param isLastInSequence Whether this is the last item in the sequence.
   */
  public void onMediaItemChanged(
      EditedMediaItem editedMediaItem, long offsetToCompositionTimeUs, boolean isLastInSequence) {
    currentEditedMediaItemInfo = new EditedMediaItemInfo(editedMediaItem, isLastInSequence);
    this.offsetToCompositionTimeUs = offsetToCompositionTimeUs;
  }

  // AudioSink methods

  @Override
  public void configure(Format inputFormat, int specifiedBufferSize, @Nullable int[] outputChannels)
      throws ConfigurationException {
    checkArgument(supportsFormat(inputFormat));
    EditedMediaItem editedMediaItem = checkStateNotNull(currentEditedMediaItemInfo).editedMediaItem;
    // TODO(b/303029969): Evaluate throwing vs ignoring for null outputChannels.
    checkArgument(outputChannels == null);
    currentInputFormat = inputFormat;
    if (outputGraphInput == null) {
      try {
        outputGraphInput = controller.getAudioGraphInput(editedMediaItem, currentInputFormat);
      } catch (ExportException e) {
        throw new ConfigurationException(e, currentInputFormat);
      }
    }

    // During playback, AudioGraphInput doesn't know the full media duration upfront due to seeking.
    // Pass in C.TIME_UNSET to AudioGraphInput.onMediaItemChanged.
    outputGraphInput.onMediaItemChanged(
        editedMediaItem, C.TIME_UNSET, currentInputFormat, /* isLast= */ false);
  }

  @Override
  public boolean isEnded() {
    if (currentInputFormat == null) { // Sink not configured.
      return inputStreamEnded;
    }
    // If we are playing the last media item in the sequence, we must also check that the controller
    // is ended.
    return inputStreamEnded
        && (!checkStateNotNull(currentEditedMediaItemInfo).isLastInSequence
            || controller.isEnded());
  }

  @Override
  public boolean handleBuffer(
      ByteBuffer buffer, long presentationTimeUs, int encodedAccessUnitCount) {
    checkState(!inputStreamEnded);
    return handleBufferInternal(buffer, presentationTimeUs, /* flags= */ 0);
  }

  @Override
  public void playToEndOfStream() {
    inputStreamEnded = true;
    if (currentInputFormat == null) { // Sink not configured.
      return;
    }
    // Queue end-of-stream only if playing the last media item in the sequence.
    if (!signalledEndOfStream && checkStateNotNull(currentEditedMediaItemInfo).isLastInSequence) {
      signalledEndOfStream =
          handleBufferInternal(
              EMPTY_BUFFER, C.TIME_END_OF_SOURCE, /* flags= */ C.BUFFER_FLAG_END_OF_STREAM);
    }
  }

  @Override
  public @SinkFormatSupport int getFormatSupport(Format format) {
    if (Objects.equals(format.sampleMimeType, MimeTypes.AUDIO_RAW)
        && format.pcmEncoding == C.ENCODING_PCM_16BIT) {
      return SINK_FORMAT_SUPPORTED_DIRECTLY;
    }

    return SINK_FORMAT_UNSUPPORTED;
  }

  @Override
  public boolean supportsFormat(Format format) {
    return getFormatSupport(format) == SINK_FORMAT_SUPPORTED_DIRECTLY;
  }

  @Override
  public boolean hasPendingData() {
    return false;
  }

  @Override
  public long getCurrentPositionUs(boolean sourceEnded) {
    long currentPositionUs = controller.getCurrentPositionUs();
    if (currentPositionUs != CURRENT_POSITION_NOT_SET) {
      // Reset the position to the one expected by the player.
      currentPositionUs -= offsetToCompositionTimeUs;
    }
    return currentPositionUs;
  }

  @Override
  public void play() {
    controller.onPlay();
  }

  @Override
  public void pause() {
    controller.onPause();
  }

  @Override
  public void flush() {
    inputStreamEnded = false;
    signalledEndOfStream = false;
  }

  @Override
  public void reset() {
    flush();
    currentInputFormat = null;
    currentEditedMediaItemInfo = null;
    controller.onReset();
  }

  // Unsupported interface functionality.

  @Override
  public void setListener(AudioSink.Listener listener) {}

  @Override
  public void handleDiscontinuity() {}

  @Override
  public void setAudioAttributes(AudioAttributes audioAttributes) {}

  @Nullable
  @Override
  public AudioAttributes getAudioAttributes() {
    return null;
  }

  @Override
  public void setPlaybackParameters(PlaybackParameters playbackParameters) {}

  @Override
  public PlaybackParameters getPlaybackParameters() {
    return PlaybackParameters.DEFAULT;
  }

  @Override
  public void enableTunnelingV21() {}

  @Override
  public void disableTunneling() {}

  @Override
  public void setSkipSilenceEnabled(boolean skipSilenceEnabled) {}

  @Override
  public boolean getSkipSilenceEnabled() {
    return false;
  }

  @Override
  public void setAudioSessionId(int audioSessionId) {}

  @Override
  public void setAuxEffectInfo(AuxEffectInfo auxEffectInfo) {}

  @Override
  public void setVolume(float volume) {}

  // Internal methods

  private boolean handleBufferInternal(ByteBuffer buffer, long presentationTimeUs, int flags) {
    checkStateNotNull(currentInputFormat);
    checkState(!signalledEndOfStream);
    AudioGraphInput outputGraphInput = checkNotNull(this.outputGraphInput);

    @Nullable DecoderInputBuffer outputBuffer = outputGraphInput.getInputBuffer();
    if (outputBuffer == null) {
      return false;
    }
    outputBuffer.ensureSpaceForWrite(buffer.remaining());
    checkNotNull(outputBuffer.data).put(buffer).flip();
    outputBuffer.timeUs =
        presentationTimeUs == C.TIME_END_OF_SOURCE
            ? C.TIME_END_OF_SOURCE
            : presentationTimeUs + offsetToCompositionTimeUs;
    outputBuffer.setFlags(flags);

    return outputGraphInput.queueInputBuffer();
  }

  private static final class EditedMediaItemInfo {
    public final EditedMediaItem editedMediaItem;
    public final boolean isLastInSequence;

    public EditedMediaItemInfo(EditedMediaItem editedMediaItem, boolean isLastInSequence) {
      this.editedMediaItem = editedMediaItem;
      this.isLastInSequence = isLastInSequence;
    }
  }
}
