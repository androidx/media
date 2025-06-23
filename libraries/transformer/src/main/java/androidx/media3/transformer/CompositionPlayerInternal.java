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

import static androidx.media3.common.util.Assertions.checkState;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Pair;
import android.view.Surface;
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
  }

  private static final String TAG = "CompPlayerInternal";
  private static final int MSG_SET_COMPOSITION = 0;
  private static final int MSG_START_RENDERING = 1;
  private static final int MSG_STOP_RENDERING = 2;
  private static final int MSG_SET_VOLUME = 3;
  private static final int MSG_SET_OUTPUT_SURFACE_INFO = 4;
  private static final int MSG_CLEAR_OUTPUT_SURFACE = 5;
  private static final int MSG_START_SEEK = 6;
  private static final int MSG_END_SEEK = 7;
  private static final int MSG_RELEASE = 8;

  private final Clock clock;
  private final HandlerWrapper handler;

  /** Must be accessed on the playback thread only. */
  private final PlaybackAudioGraphWrapper playbackAudioGraphWrapper;

  /** Must be accessed on the playback thread only. */
  private final PlaybackVideoGraphWrapper playbackVideoGraphWrapper;

  private final Listener listener;
  private final HandlerWrapper listenerHandler;

  private boolean hasSetComposition;
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
      HandlerWrapper listenerHandler) {
    this.clock = clock;
    this.handler = clock.createHandler(playbackLooper, /* callback= */ this);
    this.playbackAudioGraphWrapper = playbackAudioGraphWrapper;
    this.playbackVideoGraphWrapper = playbackVideoGraphWrapper;
    this.listener = listener;
    this.listenerHandler = listenerHandler;
  }

  // Public methods

  public void setComposition(Composition composition, long startPositionUs) {
    handler
        .obtainMessage(MSG_SET_COMPOSITION, Pair.create(composition, startPositionUs))
        .sendToTarget();
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
  public void clearOutputSurface() {
    handler.sendEmptyMessage(MSG_CLEAR_OUTPUT_SURFACE);
  }

  public void startSeek(long positionMs) {
    handler.obtainMessage(MSG_START_SEEK, positionMs).sendToTarget();
  }

  public void endSeek() {
    handler.sendEmptyMessage(MSG_END_SEEK);
  }

  /**
   * Releases internal components on the playback thread and blocks the current thread until the
   * components are released.
   */
  public void release() {
    checkState(!released);
    // Set released to true now to silence any pending listener callback.
    released = true;
    ConditionVariable conditionVariable = new ConditionVariable();
    handler.obtainMessage(MSG_RELEASE, conditionVariable).sendToTarget();
    clock.onThreadBlocked();
    try {
      conditionVariable.block();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  // Handler.Callback methods

  @SuppressWarnings("unchecked")
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
          playbackAudioGraphWrapper.setVolume(/* volume= */ (float) message.obj);
          break;
        case MSG_SET_OUTPUT_SURFACE_INFO:
          setOutputSurfaceInfoOnInternalThread(
              /* outputSurfaceInfo= */ (OutputSurfaceInfo) message.obj);
          break;
        case MSG_CLEAR_OUTPUT_SURFACE:
          clearOutputSurfaceInternal();
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
          setCompositionInternal((Pair<Composition, Long>) message.obj);
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

  private void setCompositionInternal(Pair<Composition, Long> compositionAndStartTimeUs) {
    Composition composition = compositionAndStartTimeUs.first;
    long startTimeUs = compositionAndStartTimeUs.second;
    if (!hasSetComposition) {
      // TODO: b/412585856 - Allow setting Composition-level effect on AudioGraph.
      playbackAudioGraphWrapper.setAudioProcessors(composition.effects.audioProcessors);
      hasSetComposition = true;
    }

    // Resets the position of the AudioGraph, or the AudioGraph retains its location in the previous
    // Composition

    playbackAudioGraphWrapper.startSeek(/* positionUs= */ startTimeUs);
    playbackAudioGraphWrapper.endSeek();

    playbackVideoGraphWrapper.setCompositionEffects(composition.effects.videoEffects);
    playbackVideoGraphWrapper.setCompositorSettings(composition.videoCompositorSettings);
    playbackVideoGraphWrapper.setRequestOpenGlToneMapping(
        composition.hdrMode == Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL);
    playbackVideoGraphWrapper.setIsInputSdrToneMapped(
        composition.hdrMode == Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC);
  }

  private void releaseInternal(ConditionVariable conditionVariable) {
    try {
      playbackAudioGraphWrapper.release();
      playbackVideoGraphWrapper.clearOutputSurfaceInfo();
      playbackVideoGraphWrapper.release();
    } catch (RuntimeException e) {
      Log.e(TAG, "error while releasing the player", e);
    } finally {
      conditionVariable.open();
    }
  }

  public void startRenderingInternal() {
    playbackAudioGraphWrapper.startRendering();
    playbackVideoGraphWrapper.startRendering();
  }

  public void stopRenderingInternal() {
    playbackAudioGraphWrapper.stopRendering();
    playbackVideoGraphWrapper.stopRendering();
  }

  private void clearOutputSurfaceInternal() {
    try {
      playbackVideoGraphWrapper.clearOutputSurfaceInfo();
    } catch (RuntimeException e) {
      maybeRaiseError(
          /* message= */ "error clearing video output",
          e,
          /* errorCode= */ PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED);
    }
  }

  private void setOutputSurfaceInfoOnInternalThread(OutputSurfaceInfo outputSurfaceInfo) {
    try {
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
