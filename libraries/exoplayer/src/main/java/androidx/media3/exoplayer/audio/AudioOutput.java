/*
 * Copyright (C) 2025 The Android Open Source Project
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
package androidx.media3.exoplayer.audio;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioTrack;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.exoplayer.analytics.PlayerId;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.nio.ByteBuffer;

/** An interface to wrap an object that can play audio, like an {@link AudioTrack}. */
/* package */ interface AudioOutput {

  /** Listener for {@link AudioOutput} events. */
  interface Listener {
    /**
     * Called when the audio position is advancing.
     *
     * @param playoutStartSystemTimeMs The {@link System#currentTimeMillis()} when the playout
     *     started.
     */
    void onPositionAdvancing(long playoutStartSystemTimeMs);

    /** Called when {@link #isOffloadedPlayback()} is true and new data is requested. */
    void onOffloadDataRequest();

    /**
     * Called when {@link #isOffloadedPlayback()} is true and all previously written data has been
     * decoded and written to the output device.
     */
    void onOffloadPresentationEnded();

    /** Called when the audio output had an underrun. */
    void onUnderrun();

    /** Called when the audio output has been fully released. */
    void onReleased();
  }

  /** Configuration for an {@link AudioOutput}. */
  final class OutputConfig {

    /** The {@link C.Encoding} of the audio data. */
    public final @C.Encoding int encoding;

    /** The sample rate of the audio data. */
    public final int sampleRate;

    /**
     * The channel configuration of the output. See {@code AudioFormat.CHANNEL_OUT_XXX} constants
     * like {@link android.media.AudioFormat#CHANNEL_OUT_5POINT1}.
     */
    public final int channelConfig;

    /** Whether tunneling is enabled for this output. */
    public final boolean isTunneling;

    /** Whether offload is enabled for this output. */
    public final boolean isOffload;

    /** The buffer size of the output in bytes. */
    public final int bufferSize;

    /** The {@link AudioAttributes} for the audio output. */
    public final AudioAttributes audioAttributes;

    /** The audio session ID. */
    public final int audioSessionId;

    /** The {@link Context}, or null if it should not be used to configure the output. */
    @Nullable public final Context context;

    private OutputConfig(Builder builder) {
      this.encoding = builder.encoding;
      this.sampleRate = builder.sampleRate;
      this.channelConfig = builder.channelConfig;
      this.isTunneling = builder.isTunneling;
      this.isOffload = builder.isOffload;
      this.bufferSize = builder.bufferSize;
      this.audioAttributes = builder.audioAttributes;
      this.audioSessionId = builder.audioSessionId;
      this.context = builder.context;
    }

    /** Returns a {@link Builder} initialized with the values of this instance. */
    public Builder buildUpon() {
      return new Builder(this);
    }

    /** Builder for {@link OutputConfig} instances. */
    public static final class Builder {
      private @C.Encoding int encoding;
      private int sampleRate;
      private int channelConfig;
      private boolean isTunneling;
      private boolean isOffload;
      private int bufferSize;
      private AudioAttributes audioAttributes = AudioAttributes.DEFAULT;
      private int audioSessionId;
      @Nullable private Context context;

      /** Creates a new instance. */
      public Builder() {}

      private Builder(OutputConfig config) {
        this.encoding = config.encoding;
        this.sampleRate = config.sampleRate;
        this.channelConfig = config.channelConfig;
        this.isTunneling = config.isTunneling;
        this.isOffload = config.isOffload;
        this.bufferSize = config.bufferSize;
        this.audioAttributes = config.audioAttributes;
        this.audioSessionId = config.audioSessionId;
        this.context = config.context;
      }

      /** Sets the {@link C.Encoding} of the audio data. */
      @CanIgnoreReturnValue
      public Builder setEncoding(@C.Encoding int encoding) {
        this.encoding = encoding;
        return this;
      }

      /** Sets the sample rate of the audio data. */
      @CanIgnoreReturnValue
      public Builder setSampleRate(int sampleRate) {
        this.sampleRate = sampleRate;
        return this;
      }

      /** Sets the channel configuration of the output. */
      @CanIgnoreReturnValue
      public Builder setChannelConfig(int channelConfig) {
        this.channelConfig = channelConfig;
        return this;
      }

      /** Sets whether tunneling is enabled for this output. */
      @CanIgnoreReturnValue
      public Builder setIsTunneling(boolean isTunneling) {
        this.isTunneling = isTunneling;
        return this;
      }

      /** Sets whether offload is enabled for this output. */
      @CanIgnoreReturnValue
      public Builder setIsOffload(boolean isOffload) {
        this.isOffload = isOffload;
        return this;
      }

      /** Sets the buffer size of the output in bytes. */
      @CanIgnoreReturnValue
      public Builder setBufferSize(int bufferSize) {
        this.bufferSize = bufferSize;
        return this;
      }

      /** Sets the {@link AudioAttributes}. */
      @CanIgnoreReturnValue
      public Builder setAudioAttributes(AudioAttributes audioAttributes) {
        this.audioAttributes = audioAttributes;
        return this;
      }

      /** Sets the audio session ID. */
      @CanIgnoreReturnValue
      public Builder setAudioSessionId(int audioSessionId) {
        this.audioSessionId = audioSessionId;
        return this;
      }

      /** Sets the {@link Context}, or null if it should not be used to configure the output. */
      @CanIgnoreReturnValue
      public Builder setContext(@Nullable Context context) {
        this.context = context;
        return this;
      }

      /** Builds the {@link OutputConfig}. */
      public OutputConfig build() {
        return new OutputConfig(this);
      }
    }
  }

  /** Thrown when a failure occurs writing to the output. */
  final class WriteException extends Exception {

    /**
     * The error value returned from the implementation. If the sink writes to a platform {@link
     * AudioTrack}, this will be the error value returned from {@link AudioTrack#write(byte[], int,
     * int)} or {@link AudioTrack#write(ByteBuffer, int, int)}. Otherwise, the meaning of the error
     * code depends on the implementation.
     */
    public final int errorCode;

    /** If the exception may be recovered by recreating the output. */
    public final boolean isRecoverable;

    /**
     * Creates an instance.
     *
     * @param errorCode The error value returned from the output implementation.
     * @param isRecoverable Whether the exception can be recovered by recreating the output.
     */
    public WriteException(int errorCode, boolean isRecoverable) {
      super("AudioOutput write failed: " + errorCode);
      this.isRecoverable = isRecoverable;
      this.errorCode = errorCode;
    }
  }

  /** Starts or resumes playing audio. */
  void play();

  /** Pauses playback. */
  void pause();

  /**
   * Writes audio data to the audio output for playback with a presentation timestamp.
   *
   * <p>The provided {@code buffer} must be a direct byte buffer with native byte order. The method
   * is read-only and will not modify the bytes. It processes data from {@code buffer.position()} to
   * {@code buffer.limit()} (exclusive). Every call will advance {@code buffer.position()} to
   * reflect the number of bytes written.
   *
   * <p>The first time this method is called with a new buffer, the buffer must contain one or more
   * complete encoded access units or complete PCM frames.
   *
   * <p>If this method returns {@code false}, the same buffer must be used for subsequent calls to
   * this method until it has been fully consumed, or until an intervening call to {@link #flush()}.
   *
   * @param buffer The buffer containing the audio data.
   * @param encodedAccessUnitCount The number of encoded access units in the buffer when the buffer
   *     was first used in this method, or 1 if the buffer contains PCM audio. When calling this
   *     method again after it returned {@code false}, the same number of encoded access units must
   *     be provided.
   * @param presentationTimeUs The presentation timestamp, in microseconds, of the first access unit
   *     or PCM frame when the buffer was first used in this method. When calling this method again
   *     after it returned {@code false}, the same timestamp must be provided.
   * @return Whether the buffer was handled fully.
   * @throws WriteException If the write fails. The audio output cannot be reused after a failure
   *     and needs to be recreated.
   */
  boolean write(ByteBuffer buffer, int encodedAccessUnitCount, long presentationTimeUs)
      throws WriteException;

  /** Flushes the audio data from the output. */
  void flush();

  /**
   * Signal that the last buffer has been written and playback should stop after playing out all
   * remaining buffers
   */
  void stop();

  /** Releases the {@link AudioOutput}. */
  void release();

  /** Sets the volume of the {@link AudioOutput}. */
  void setVolume(float volume);

  /** Returns whether this output is playing back in offload mode. */
  boolean isOffloadedPlayback();

  /** Returns the audio session ID or {@link C#AUDIO_SESSION_ID_UNSET} if unknown. */
  int getAudioSessionId();

  /** Returns the sample rate of the underlying {@link AudioOutput}. */
  int getSampleRate();

  /** Returns the buffer size in frames of the underlying {@link AudioOutput}. */
  long getBufferSizeInFrames();

  /** Returns the current playout timestamp in microseconds. */
  long getPositionUs();

  /** Returns the playback parameters. */
  PlaybackParameters getPlaybackParameters();

  /** Returns whether the audio output is stalled. */
  boolean isStalled();

  /** Adds a {@link Listener} for audio output events. */
  void addListener(Listener listener);

  /** Removes a {@link Listener} for audio output events. */
  void removeListener(Listener listener);

  /** Sets the playback parameters. */
  void setPlaybackParameters(PlaybackParameters playbackParams);

  /** Sets the offload delay and padding. */
  void setOffloadDelayPadding(int delayInFrames, int paddingInFrames);

  /** Notifies the output that this is the end of the stream. */
  void setOffloadEndOfStream();

  /** Sets the {@link PlayerId} on the audio output. */
  void setPlayerId(PlayerId playerId);

  /** Attaches an auxiliary effect to the output. */
  void attachAuxEffect(int effectId);

  /** Sets the send level for the auxiliary effect. */
  void setAuxEffectSendLevel(float level);

  /** Sets the preferred audio device for routing. */
  void setPreferredDevice(@Nullable AudioDeviceInfo preferredDevice);
}
