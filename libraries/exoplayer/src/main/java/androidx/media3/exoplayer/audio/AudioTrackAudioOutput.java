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

import static android.os.Build.VERSION.SDK_INT;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.media.AudioDeviceInfo;
import android.media.AudioRouting;
import android.media.AudioTrack;
import android.media.PlaybackParams;
import android.media.metrics.LogSessionId;
import android.os.Handler;
import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.util.BackgroundExecutor;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.ListenerSet;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.audio.AudioOutputProvider.OutputConfig;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;

/** A default implementation of {@link AudioOutput} that wraps an {@link AudioTrack}. */
public final class AudioTrackAudioOutput implements AudioOutput {

  /** Listener for potential capability change events. */
  /* package */ interface CapabilityChangeListener {

    /** The audio device routing changed. */
    void onRoutedDeviceChanged(AudioDeviceInfo routedDevice);

    /** A recoverable write error occurred. */
    void onRecoverableWriteError();
  }

  private static final String TAG = "AudioTrackAudioOutput";

  /**
   * Native error code equivalent of {@link AudioTrack#ERROR_DEAD_OBJECT} to workaround missing
   * error code translation on some devices.
   *
   * <p>On some devices, AudioTrack native error codes are not always converted to their SDK
   * equivalent.
   *
   * <p>For example: {@link AudioTrack#write(byte[], int, int)} can return -32 instead of {@link
   * AudioTrack#ERROR_DEAD_OBJECT}.
   */
  private static final int ERROR_NATIVE_DEAD_OBJECT = -32;

  /** The time it takes to ramp AudioTrack's volume up or down when pausing or starting to play. */
  private static final int AUDIO_TRACK_VOLUME_RAMP_TIME_MS = 20;

  private static final Object releaseExecutorLock = new Object();

  @SuppressWarnings("NonFinalStaticField") // Intentional statically shared mutable state
  @GuardedBy("releaseExecutorLock")
  @Nullable
  private static ScheduledExecutorService releaseExecutor;

  @GuardedBy("releaseExecutorLock")
  private static int pendingReleaseCount;

  private final AudioTrack audioTrack;
  private final OutputConfig config;
  @Nullable private final CapabilityChangeListener capabilityChangeListener;
  @Nullable private OnRoutingChangedListenerApi24 onRoutingChangedListener;
  private final AudioTrackPositionTracker audioTrackPositionTracker;
  private final boolean isOutputPcm;
  private final int pcmFrameSize;
  @Nullable private final StreamEventCallbackV29 offloadStreamEventCallbackV29;
  private final ListenerSet<Listener> listeners;

  private boolean hasBeenStopped;
  private long writtenPcmBytes;
  private long writtenEncodedFrames;
  private long lastTunnelingAvSyncPresentationTimeUs;
  @Nullable private ByteBuffer avSyncHeader;
  private int bytesUntilNextAvSync;
  private int framesPerEncodedSample;
  private int lastUnderrunCount;
  private boolean hasData;

  /**
   * Creates a new instance.
   *
   * @param audioTrack The audio track to wrap.
   * @param config The output configuration.
   * @param capabilityChangeListener The {@link CapabilityChangeListener}.
   * @param clock The {@link Clock}.
   */
  @UnstableApi
  @SuppressWarnings("WrongConstant") // For config encoding to pcm encoding.
  public AudioTrackAudioOutput(
      AudioTrack audioTrack,
      OutputConfig config,
      @Nullable CapabilityChangeListener capabilityChangeListener,
      Clock clock) {
    this.audioTrack = audioTrack;
    this.config = config;
    this.capabilityChangeListener = capabilityChangeListener;
    listeners = new ListenerSet<>(Thread.currentThread());
    // TODO: b/450556896 - remove this line once threading in CompositionPlayer is fixed.
    listeners.setThrowsWhenUsingWrongThread(false);

    isOutputPcm = Util.isEncodingLinearPcm(config.encoding);
    if (isOutputPcm) {
      int channelCount = Integer.bitCount(config.channelMask);
      pcmFrameSize = Util.getPcmFrameSize(config.encoding, channelCount);
    } else {
      pcmFrameSize = C.LENGTH_UNSET;
    }

    audioTrackPositionTracker =
        new AudioTrackPositionTracker(
            new PositionTrackerListener(),
            clock,
            audioTrack,
            config.encoding,
            pcmFrameSize,
            config.bufferSize);

    if (SDK_INT >= 24 && capabilityChangeListener != null) {
      onRoutingChangedListener =
          new OnRoutingChangedListenerApi24(audioTrack, capabilityChangeListener);
    }
    offloadStreamEventCallbackV29 = isOffloadedPlayback() ? new StreamEventCallbackV29() : null;
  }

  /** Returns the {@link AudioTrack} instance used for audio output. */
  public AudioTrack getAudioTrack() {
    return audioTrack;
  }

  @Override
  public void addListener(Listener listener) {
    listeners.add(listener);
  }

  @Override
  public void removeListener(Listener listener) {
    listeners.remove(listener);
  }

  @Override
  public boolean isOffloadedPlayback() {
    return SDK_INT >= 29 && audioTrack.isOffloadedPlayback();
  }

  @Override
  public int getAudioSessionId() {
    return audioTrack.getAudioSessionId();
  }

  @Override
  public int getSampleRate() {
    return audioTrack.getSampleRate();
  }

  @Override
  public long getBufferSizeInFrames() {
    return audioTrack.getBufferSizeInFrames();
  }

  @Override
  public long getPositionUs() {
    return audioTrackPositionTracker.getCurrentPositionUs();
  }

  @Override
  public PlaybackParameters getPlaybackParameters() {
    PlaybackParams playbackParams = audioTrack.getPlaybackParams();
    return new PlaybackParameters(playbackParams.getSpeed(), playbackParams.getPitch());
  }

  @Override
  public void play() {
    audioTrackPositionTracker.start();
    if (!hasBeenStopped || isOffloadedPlayback()) {
      audioTrack.play();
    }
  }

  @Override
  public void pause() {
    audioTrackPositionTracker.pause();
    if (!hasBeenStopped || isOffloadedPlayback()) {
      audioTrack.pause();
    }
  }

  @Override
  public boolean write(ByteBuffer buffer, int encodedAccessUnitCount, long presentationTimeUs)
      throws WriteException {
    if (!isOutputPcm && framesPerEncodedSample == 0) {
      // If this is the first encoded sample, calculate the sample size in frames.
      framesPerEncodedSample = DefaultAudioSink.getFramesPerEncodedSample(config.encoding, buffer);
    }
    maybeReportUnderrun();
    int bytesRemaining = buffer.remaining();
    int bytesWrittenOrError;
    if (config.isTunneling) {
      if (presentationTimeUs == C.TIME_END_OF_SOURCE) {
        // Audio processors during tunneling are required to produce buffers immediately when
        // queuing, so we can assume the timestamp during draining at the end of the stream is the
        // same as the timestamp of the last sample we processed.
        presentationTimeUs = lastTunnelingAvSyncPresentationTimeUs;
      } else {
        lastTunnelingAvSyncPresentationTimeUs = presentationTimeUs;
      }
      bytesWrittenOrError = writeWithAvSync(audioTrack, buffer, presentationTimeUs);
    } else {
      bytesWrittenOrError =
          audioTrack.write(buffer, buffer.remaining(), AudioTrack.WRITE_NON_BLOCKING);
    }

    if (bytesWrittenOrError < 0) {
      int error = bytesWrittenOrError;
      boolean isRecoverable = isAudioTrackDeadObject(error);
      if (isRecoverable && capabilityChangeListener != null) {
        capabilityChangeListener.onRecoverableWriteError();
      }
      throw new WriteException(error, isRecoverable);
    }
    int bytesWritten = bytesWrittenOrError;
    boolean fullyHandled = bytesWritten == bytesRemaining;

    if (isOutputPcm) {
      writtenPcmBytes += bytesWritten;
    } else if (fullyHandled) {
      // For non-PCM we can only be sure about the number of written frames once the entire buffer
      // is submitted.
      writtenEncodedFrames += (long) framesPerEncodedSample * encodedAccessUnitCount;
    }
    return fullyHandled;
  }

  @Override
  public void flush() {
    avSyncHeader = null;
    bytesUntilNextAvSync = 0;
    writtenPcmBytes = 0;
    writtenEncodedFrames = 0;
    hasBeenStopped = false;
    framesPerEncodedSample = 0;
    audioTrack.flush();
    audioTrackPositionTracker.reset();
  }

  @Override
  public void stop() {
    if (hasBeenStopped) {
      return;
    }
    hasBeenStopped = true;
    audioTrackPositionTracker.handleEndOfStream(getWrittenFrames());
    audioTrack.stop();
    bytesUntilNextAvSync = 0;
  }

  @Override
  public void release() {
    if (audioTrackPositionTracker.isPlaying()) {
      audioTrack.pause();
    }
    if (SDK_INT >= 29 && isOffloadedPlayback()) {
      checkNotNull(offloadStreamEventCallbackV29).unregister();
    }
    if (SDK_INT >= 24 && onRoutingChangedListener != null) {
      onRoutingChangedListener.release();
      onRoutingChangedListener = null;
    }
    releaseAudioTrackAsync(audioTrack, listeners);
  }

  @Override
  public void setVolume(float volume) {
    audioTrack.setVolume(volume);
  }

  @Override
  public void setPlaybackParameters(PlaybackParameters playbackParameters) {
    PlaybackParams playbackParams =
        new PlaybackParams()
            .allowDefaults()
            .setSpeed(playbackParameters.speed)
            .setPitch(playbackParameters.pitch)
            .setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_FAIL);
    try {
      audioTrack.setPlaybackParams(playbackParams);
    } catch (IllegalArgumentException e) {
      Log.w(TAG, "Failed to set playback params", e);
    }
    audioTrackPositionTracker.setAudioTrackPlaybackSpeed(audioTrack.getPlaybackParams().getSpeed());
  }

  @Override
  public void setOffloadDelayPadding(int delayInFrames, int paddingInFrames) {
    if (SDK_INT < 29) {
      return;
    }

    audioTrack.setOffloadDelayPadding(delayInFrames, paddingInFrames);
  }

  @Override
  public void setOffloadEndOfStream() {
    if (SDK_INT < 29) {
      return;
    }
    if (audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
      // If the first track is very short (typically <1s), the offload AudioTrack might
      // not have started yet. Do not call setOffloadEndOfStream as it would throw.
      return;
    }
    audioTrack.setOffloadEndOfStream();
    audioTrackPositionTracker.expectRawPlaybackHeadReset();
  }

  @UnstableApi
  @Override
  public void setPlayerId(PlayerId playerId) {
    if (SDK_INT < 31) {
      return;
    }
    LogSessionId logSessionId = playerId.getLogSessionId();
    if (!logSessionId.equals(LogSessionId.LOG_SESSION_ID_NONE)) {
      audioTrack.setLogSessionId(logSessionId);
    }
  }

  @Override
  public void attachAuxEffect(int effectId) {
    audioTrack.attachAuxEffect(effectId);
  }

  @Override
  public void setAuxEffectSendLevel(float level) {
    audioTrack.setAuxEffectSendLevel(level);
  }

  @Override
  public void setPreferredDevice(@Nullable AudioDeviceInfo preferredDevice) {
    audioTrack.setPreferredDevice(preferredDevice);
  }

  @Override
  public boolean isStalled() {
    return audioTrackPositionTracker.isStalled(getWrittenFrames());
  }

  private long getWrittenFrames() {
    return isOutputPcm ? Util.ceilDivide(writtenPcmBytes, pcmFrameSize) : writtenEncodedFrames;
  }

  private int writeWithAvSync(AudioTrack audioTrack, ByteBuffer buffer, long presentationTimeUs) {
    int size = buffer.remaining();
    if (SDK_INT >= 26) {
      // The underlying platform AudioTrack writes AV sync headers directly.
      return audioTrack.write(
          buffer, size, AudioTrack.WRITE_NON_BLOCKING, presentationTimeUs * 1000);
    }
    if (avSyncHeader == null) {
      avSyncHeader = ByteBuffer.allocate(16);
      avSyncHeader.order(ByteOrder.BIG_ENDIAN);
      avSyncHeader.putInt(0x55550001);
    }
    if (bytesUntilNextAvSync == 0) {
      avSyncHeader.putInt(4, size);
      avSyncHeader.putLong(8, presentationTimeUs * 1000);
      avSyncHeader.position(0);
      bytesUntilNextAvSync = size;
    }
    int avSyncHeaderBytesRemaining = avSyncHeader.remaining();
    if (avSyncHeaderBytesRemaining > 0) {
      int result =
          audioTrack.write(avSyncHeader, avSyncHeaderBytesRemaining, AudioTrack.WRITE_NON_BLOCKING);
      if (result < 0) {
        bytesUntilNextAvSync = 0;
        return result;
      }
      if (result < avSyncHeaderBytesRemaining) {
        return 0;
      }
    }
    int result = audioTrack.write(buffer, size, AudioTrack.WRITE_NON_BLOCKING);
    if (result < 0) {
      bytesUntilNextAvSync = 0;
      return result;
    }
    bytesUntilNextAvSync -= result;
    return result;
  }

  private void maybeReportUnderrun() {
    if (hasPendingAudioTrackUnderruns(getWrittenFrames())) {
      listeners.sendEvent(Listener::onUnderrun);
    }
  }

  private boolean hasPendingAudioTrackUnderruns(long writtenFrames) {
    int underrunCount = getAudioOutputUnderrunCount(writtenFrames);
    boolean result = underrunCount > lastUnderrunCount;

    // If the AudioTrack unexpectedly resets the underrun count, we should update it silently.
    lastUnderrunCount = underrunCount;

    return result;
  }

  private int getAudioOutputUnderrunCount(long writtenFrames) {
    if (SDK_INT >= 24) {
      return audioTrack.getUnderrunCount();
    }
    boolean hadData = hasData;
    long currentPositionFrames = Util.durationUsToSampleCount(getPositionUs(), getSampleRate());
    hasData = writtenFrames > currentPositionFrames;
    // For API 23- AudioTrack has no underrun API so we need to infer underruns heuristically.
    boolean emitUnderrun =
        hadData && !hasData && audioTrack.getPlayState() != AudioTrack.PLAYSTATE_STOPPED;
    return emitUnderrun ? lastUnderrunCount + 1 : lastUnderrunCount;
  }

  private static void releaseAudioTrackAsync(
      AudioTrack audioTrack, ListenerSet<Listener> listeners) {
    // AudioTrack.release can take some time, so we call it on a background thread. The background
    // thread is shared statically to avoid creating many threads when multiple players are released
    // at the same time.
    Handler audioTrackThreadHandler = Util.createHandlerForCurrentLooper();
    synchronized (releaseExecutorLock) {
      if (releaseExecutor == null) {
        releaseExecutor =
            Util.newSingleThreadScheduledExecutor("ExoPlayer:AudioTrackReleaseThread");
      }
      pendingReleaseCount++;
      Future<?> ignored =
          releaseExecutor.schedule(
              () -> {
                try {
                  // We need to flush the audio track as some devices are known to keep state from
                  // previous playbacks if the track is not flushed at all (see b/22967293).
                  audioTrack.flush();
                  audioTrack.release();
                } finally {
                  if (audioTrackThreadHandler.getLooper().getThread().isAlive()) {
                    audioTrackThreadHandler.post(() -> listeners.sendEvent(Listener::onReleased));
                  }
                  synchronized (releaseExecutorLock) {
                    pendingReleaseCount--;
                    if (pendingReleaseCount == 0) {
                      checkNotNull(releaseExecutor).shutdown();
                      releaseExecutor = null;
                    }
                  }
                }
              },
              // We need to schedule the flush and release with a delay to ensure the audio system
              // can completely ramp down the audio output after the preceding pause.
              AUDIO_TRACK_VOLUME_RAMP_TIME_MS,
              MILLISECONDS);
    }
  }

  private static boolean isAudioTrackDeadObject(int status) {
    return (SDK_INT >= 24 && status == AudioTrack.ERROR_DEAD_OBJECT)
        || status == ERROR_NATIVE_DEAD_OBJECT;
  }

  private final class PositionTrackerListener implements AudioTrackPositionTracker.Listener {

    @Override
    public void onPositionFramesMismatch(
        long audioTimestampPositionFrames,
        long audioTimestampSystemTimeUs,
        long systemTimeUs,
        long playbackPositionUs) {
      String message =
          "Spurious audio timestamp (frame position mismatch): "
              + audioTimestampPositionFrames
              + ", "
              + audioTimestampSystemTimeUs
              + ", "
              + systemTimeUs
              + ", "
              + playbackPositionUs
              + ", "
              + getWrittenFrames();

      if (AudioTrackAudioOutputProvider.failOnSpuriousAudioTimestamp) {
        throw new InvalidAudioTrackTimestampException(message);
      }
      Log.w(TAG, message);
    }

    @Override
    public void onSystemTimeUsMismatch(
        long audioTimestampPositionFrames,
        long audioTimestampSystemTimeUs,
        long systemTimeUs,
        long playbackPositionUs) {
      String message =
          "Spurious audio timestamp (system clock mismatch): "
              + audioTimestampPositionFrames
              + ", "
              + audioTimestampSystemTimeUs
              + ", "
              + systemTimeUs
              + ", "
              + playbackPositionUs
              + ", "
              + getWrittenFrames();

      if (AudioTrackAudioOutputProvider.failOnSpuriousAudioTimestamp) {
        throw new InvalidAudioTrackTimestampException(message);
      }
      Log.w(TAG, message);
    }

    @Override
    public void onInvalidLatency(long latencyUs) {
      Log.w(TAG, "Ignoring impossibly large audio latency: " + latencyUs);
    }

    @Override
    public void onPositionAdvancing(long playoutStartSystemTimeMs) {
      listeners.sendEvent(listener -> listener.onPositionAdvancing(playoutStartSystemTimeMs));
    }
  }

  /**
   * Thrown when the audio track has provided a spurious timestamp, if {@link
   * AudioTrackAudioOutputProvider#failOnSpuriousAudioTimestamp} is set.
   */
  @UnstableApi
  public static final class InvalidAudioTrackTimestampException extends RuntimeException {

    /**
     * Creates a new invalid timestamp exception with the specified message.
     *
     * @param message The detail message for this exception.
     */
    private InvalidAudioTrackTimestampException(String message) {
      super(message);
    }
  }

  @RequiresApi(24)
  private static final class OnRoutingChangedListenerApi24 {

    private final AudioTrack audioTrack;
    private final CapabilityChangeListener capabilityChangeListener;
    private final Handler playbackThreadHandler;

    @Nullable private AudioRouting.OnRoutingChangedListener listener;

    private OnRoutingChangedListenerApi24(
        AudioTrack audioTrack, CapabilityChangeListener capabilityChangeListener) {
      this.audioTrack = audioTrack;
      this.capabilityChangeListener = capabilityChangeListener;
      this.playbackThreadHandler = Util.createHandlerForCurrentLooper();
      this.listener = this::onRoutingChanged;
      audioTrack.addOnRoutingChangedListener(listener, playbackThreadHandler);
    }

    private void release() {
      audioTrack.removeOnRoutingChangedListener(checkNotNull(listener));
      listener = null;
    }

    private void onRoutingChanged(AudioRouting router) {
      if (listener == null) {
        // Stale event.
        return;
      }
      BackgroundExecutor.get()
          .execute(
              () -> {
                @Nullable AudioDeviceInfo routedDevice = router.getRoutedDevice();
                if (routedDevice != null) {
                  playbackThreadHandler.post(
                      () -> {
                        if (listener == null) {
                          // Stale event.
                          return;
                        }
                        capabilityChangeListener.onRoutedDeviceChanged(routedDevice);
                      });
                }
              });
    }
  }

  @RequiresApi(29)
  private final class StreamEventCallbackV29 {
    private final Handler handler;
    private final AudioTrack.StreamEventCallback callback;

    private StreamEventCallbackV29() {
      handler = Util.createHandlerForCurrentLooper();
      // Avoid StreamEventCallbackV29 inheriting directly from AudioTrack.StreamEventCallback as it
      // would cause a NoClassDefFoundError warning on load of DefaultAudioSink for SDK < 29.
      // See: https://github.com/google/ExoPlayer/issues/8058
      callback =
          new AudioTrack.StreamEventCallback() {
            @Override
            public void onDataRequest(AudioTrack track, int size) {
              listeners.sendEvent(Listener::onOffloadDataRequest);
            }

            @Override
            public void onPresentationEnded(AudioTrack track) {
              listeners.sendEvent(Listener::onOffloadPresentationEnded);
            }

            @Override
            public void onTearDown(AudioTrack track) {
              // The audio track was destroyed while in use. Thus a new AudioTrack needs to be
              // created and its buffer filled. Request this call explicitly in case ExoPlayer is
              // sleeping waiting for a data request.
              listeners.sendEvent(Listener::onOffloadDataRequest);
            }
          };
      audioTrack.registerStreamEventCallback(handler::post, callback);
    }

    private void unregister() {
      audioTrack.unregisterStreamEventCallback(callback);
      handler.removeCallbacksAndMessages(/* token= */ null);
    }
  }
}
