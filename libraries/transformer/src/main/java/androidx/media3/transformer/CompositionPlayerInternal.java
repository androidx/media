/*
 * Copyright 2024 The Android Open Source Project
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

import static androidx.media3.exoplayer.DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.video.PlaybackVideoGraphWrapper;

/** Provides access to the composition preview audio and video components on the playback thread. */
/* package */ final class CompositionPlayerInternal implements Handler.Callback {

  /** A listener for events. */
  public interface Listener {

    /**
     * Called when an error occurs
     *
     * @param message The error message.
     * @param cause The error cause.
     * @param errorCode The error code.
     */
    void onError(String message, Exception cause, @PlaybackException.ErrorCode int errorCode);

    /**
     * Reports dropped frames from the video graph wrapper. Dropped frames are reported whenever the
     * video graph is {@linkplain #stopRendering() stopped} having dropped frames, and whenever the
     * count reaches a specified threshold whilst the video graph is started.
     *
     * @param droppedFrameCount The number of dropped frames.
     * @param elapsedMs The duration in milliseconds over which the frames were dropped. This
     *     duration is timed from when the video graph was started or from when dropped frames were
     *     last reported (whichever was more recent), and not from when the first of the reported
     *     drops occurred.
     */
    void onDroppedVideoFrames(int droppedFrameCount, long elapsedMs);
  }

  /** Timeout for {@link #release()}. */
  public static final long RELEASE_TIMEOUT_MS = 500;

  private static final String TAG = "CompPlayerInternal";
  private static final int MSG_SET_COMPOSITION = 0;
  private static final int MSG_START_RENDERING = 1;
  private static final int MSG_STOP_RENDERING = 2;
  private static final int MSG_SET_VOLUME = 3;
  private static final int MSG_SET_PLAYBACK_AUDIO_GRAPH_WRAPPER = 4;
  private static final int MSG_SET_OUTPUT_SURFACE_INFO = 5;
  private static final int MSG_CLEAR_OUTPUT_SURFACE = 6;
  private static final int MSG_START_SEEK = 7;
  private static final int MSG_END_SEEK = 8;
  private static final int MSG_RELEASE = 9;
  private static final int MSG_SET_AUDIO_ATTRIBUTES = 10;

  private final Clock clock;
  private final HandlerWrapper handler;

  /** Must be accessed on the playback thread only. */
  private PlaybackAudioGraphWrapper playbackAudioGraphWrapper;

  /** Must be accessed on the playback thread only. */
  private final PlaybackVideoGraphWrapper playbackVideoGraphWrapper;

  private final Listener listener;
  private final HandlerWrapper listenerHandler;
  @Nullable private final CompositionVideoPacketReleaseControl videoPacketReleaseControl;

  private int droppedFrames;
  private long droppedFrameAccumulationStartTimeMs;

  private boolean released;

  /**
   * Creates a instance.
   *
   * @param playbackLooper The playback thread {@link Looper}.
   * @param clock The {@link Clock} used.
   * @param playbackAudioGraphWrapper The {@link PlaybackAudioGraphWrapper}.
   * @param playbackVideoGraphWrapper The {@link PlaybackVideoGraphWrapper}.
   * @param listener A {@link Listener} to send callbacks back to the player.
   * @param listenerHandler A {@link HandlerWrapper} to dispatch {@link Listener} callbacks.
   */
  public CompositionPlayerInternal(
      Looper playbackLooper,
      Clock clock,
      PlaybackAudioGraphWrapper playbackAudioGraphWrapper,
      PlaybackVideoGraphWrapper playbackVideoGraphWrapper,
      Listener listener,
      HandlerWrapper listenerHandler,
      @Nullable CompositionVideoPacketReleaseControl videoPacketReleaseControl) {
    this.clock = clock;
    this.handler = clock.createHandler(playbackLooper, /* callback= */ this);
    this.playbackAudioGraphWrapper = playbackAudioGraphWrapper;
    this.playbackVideoGraphWrapper = playbackVideoGraphWrapper;
    this.listener = listener;
    this.listenerHandler = listenerHandler;
    this.videoPacketReleaseControl = videoPacketReleaseControl;
  }

  // Public methods

  public void setComposition(Composition composition) {
    handler.obtainMessage(MSG_SET_COMPOSITION, composition).sendToTarget();
  }

  public void startRendering() {
    handler.sendEmptyMessage(MSG_START_RENDERING);
  }

  public void stopRendering() {
    handler.sendEmptyMessage(MSG_STOP_RENDERING);
  }

  public void setVolume(float volume) {
    handler.obtainMessage(MSG_SET_VOLUME, volume).sendToTarget();
  }

  /** Sets the output surface information on the video pipeline. */
  public void setOutputSurfaceInfo(Surface surface, Size size) {
    handler
        .obtainMessage(MSG_SET_OUTPUT_SURFACE_INFO, new OutputSurfaceInfo(surface, size))
        .sendToTarget();
  }

  /** Clears the output surface from the video pipeline. */
  public void clearOutputSurface(ConditionVariable surfaceCleared) {
    handler.obtainMessage(MSG_CLEAR_OUTPUT_SURFACE, surfaceCleared).sendToTarget();
  }

  /** Sets a new {@link PlaybackAudioGraphWrapper}. */
  public void setPlaybackAudioGraphWrapper(PlaybackAudioGraphWrapper playbackAudioGraphWrapper) {
    handler
        .obtainMessage(MSG_SET_PLAYBACK_AUDIO_GRAPH_WRAPPER, playbackAudioGraphWrapper)
        .sendToTarget();
  }

  public void startSeek(long positionMs) {
    handler.obtainMessage(MSG_START_SEEK, positionMs).sendToTarget();
  }

  public void endSeek() {
    handler.sendEmptyMessage(MSG_END_SEEK);
  }

  /**
   * Releases internal components on the playback thread and blocks the current thread until the
   * components are released, with a {@linkplain #RELEASE_TIMEOUT_MS timeout}.
   *
   * @return Whether the internal components are released correctly before timing out.
   */
  public boolean release() {
    checkState(!released);
    // Set released to true now to silence any pending listener callback.
    released = true;
    ConditionVariable conditionVariable = new ConditionVariable(clock);
    handler.obtainMessage(MSG_RELEASE, conditionVariable).sendToTarget();
    return conditionVariable.blockUninterruptible(RELEASE_TIMEOUT_MS);
  }

  public void setAudioAttributes(AudioAttributes attributes) {
    handler.obtainMessage(MSG_SET_AUDIO_ATTRIBUTES, attributes).sendToTarget();
  }

  /**
   * Reports that an output frame was dropped from the video graph.
   *
   * <p>Must be called on the playback thread.
   */
  /* package */ void onFrameDropped() {
    droppedFrames++;
    if (droppedFrames >= MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY) {
      maybeNotifyDroppedFrames();
    }
  }

  private void maybeNotifyDroppedFrames() {
    if (droppedFrames > 0) {
      long now = clock.elapsedRealtime();
      long elapsedMs = now - droppedFrameAccumulationStartTimeMs;
      int droppedFramesToBeReported = droppedFrames;
      listenerHandler.post(
          () -> listener.onDroppedVideoFrames(droppedFramesToBeReported, elapsedMs));
      droppedFrames = 0;
      droppedFrameAccumulationStartTimeMs = now;
    }
  }

  // Handler.Callback methods

  @Override
  public boolean handleMessage(Message message) {
    try {
      switch (message.what) {
        case MSG_START_RENDERING:
          startRenderingInternal();
          break;
        case MSG_STOP_RENDERING:
          stopRenderingInternal();
          break;
        case MSG_SET_VOLUME:
          checkNotNull(playbackAudioGraphWrapper).setVolume(/* volume= */ (float) message.obj);
          break;
        case MSG_SET_PLAYBACK_AUDIO_GRAPH_WRAPPER:
          playbackAudioGraphWrapper = (PlaybackAudioGraphWrapper) message.obj;
          break;
        case MSG_SET_OUTPUT_SURFACE_INFO:
          setOutputSurfaceInfoOnInternalThread(
              /* outputSurfaceInfo= */ (OutputSurfaceInfo) message.obj);
          break;
        case MSG_CLEAR_OUTPUT_SURFACE:
          clearOutputSurfaceInternal((ConditionVariable) message.obj);
          break;
        case MSG_START_SEEK:
          // Video seeking is currently handled by the video renderers, specifically in
          // onPositionReset.
          playbackAudioGraphWrapper.startSeek(/* positionUs= */ Util.msToUs((long) message.obj));
          break;
        case MSG_END_SEEK:
          playbackAudioGraphWrapper.endSeek();
          break;
        case MSG_RELEASE:
          releaseInternal(/* conditionVariable= */ (ConditionVariable) message.obj);
          break;
        case MSG_SET_COMPOSITION:
          setCompositionInternal((Composition) message.obj);
          break;
        case MSG_SET_AUDIO_ATTRIBUTES:
          playbackAudioGraphWrapper.setAudioAttributes((AudioAttributes) message.obj);
          break;
        default:
          maybeRaiseError(
              /* message= */ "Unknown message",
              new IllegalStateException(String.valueOf(message.what)),
              /* errorCode= */ PlaybackException.ERROR_CODE_UNSPECIFIED);
      }
    } catch (RuntimeException e) {
      maybeRaiseError(
          /* message= */ "Unknown error",
          e,
          /* errorCode= */ PlaybackException.ERROR_CODE_UNSPECIFIED);
    }
    return true;
  }

  // Internal methods

  private void setCompositionInternal(Composition composition) {
    // TODO: b/412585856 - Allow setting Composition-level effect on AudioGraph.
    playbackAudioGraphWrapper.setAudioProcessors(composition.effects.audioProcessors);

    playbackVideoGraphWrapper.setCompositionEffects(composition.effects.videoEffects);
    playbackVideoGraphWrapper.setCompositorSettings(composition.videoCompositorSettings);
    playbackVideoGraphWrapper.setRequestOpenGlToneMapping(
        composition.hdrMode == Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL);
    playbackVideoGraphWrapper.setIsInputSdrToneMapped(
        composition.hdrMode == Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC);
  }

  private void releaseInternal(ConditionVariable conditionVariable) {
    try {
      maybeNotifyDroppedFrames();
      playbackAudioGraphWrapper.release();
      playbackVideoGraphWrapper.clearOutputSurfaceInfo();
      playbackVideoGraphWrapper.release();
    } catch (RuntimeException e) {
      Log.e(TAG, "error while releasing the player", e);
    } finally {
      conditionVariable.open();
    }
  }

  private void startRenderingInternal() {
    droppedFrameAccumulationStartTimeMs = clock.elapsedRealtime();
    playbackAudioGraphWrapper.startRendering();
    playbackVideoGraphWrapper.startRendering();
    if (videoPacketReleaseControl != null) {
      videoPacketReleaseControl.onStarted();
    }
  }

  private void stopRenderingInternal() {
    maybeNotifyDroppedFrames();
    playbackAudioGraphWrapper.stopRendering();
    playbackVideoGraphWrapper.stopRendering();
    if (videoPacketReleaseControl != null) {
      videoPacketReleaseControl.onStopped();
    }
  }

  private void clearOutputSurfaceInternal(ConditionVariable surfaceCleared) {
    try {
      if (videoPacketReleaseControl != null) {
        videoPacketReleaseControl.setOutputSurface(null);
      }
      playbackVideoGraphWrapper.clearOutputSurfaceInfo();
      surfaceCleared.open();
    } catch (RuntimeException e) {
      maybeRaiseError(
          /* message= */ "error clearing video output",
          e,
          /* errorCode= */ PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED);
    }
  }

  private void setOutputSurfaceInfoOnInternalThread(OutputSurfaceInfo outputSurfaceInfo) {
    try {
      if (videoPacketReleaseControl != null) {
        videoPacketReleaseControl.setOutputSurface(outputSurfaceInfo.surface);
      }
      playbackVideoGraphWrapper.setOutputSurfaceInfo(
          outputSurfaceInfo.surface, outputSurfaceInfo.size);
    } catch (RuntimeException e) {
      maybeRaiseError(
          /* message= */ "error setting surface view",
          e,
          /* errorCode= */ PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED);
    }
  }

  private void maybeRaiseError(
      String message, Exception cause, @PlaybackException.ErrorCode int errorCode) {
    try {
      listenerHandler.post(
          () -> {
            // This code runs on the application thread, hence access to the `release` field does
            // not need to be synchronized.
            if (!released) {
              listener.onError(message, cause, errorCode);
            }
          });
    } catch (RuntimeException e) {
      Log.e(TAG, "error", e);
    }
  }

  private static final class OutputSurfaceInfo {
    public final Surface surface;
    public final Size size;

    public OutputSurfaceInfo(Surface surface, Size size) {
      this.surface = surface;
      this.size = size;
    }
  }
}
