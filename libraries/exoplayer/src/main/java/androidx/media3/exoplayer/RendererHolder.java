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
package androidx.media3.exoplayer;

import static androidx.media3.common.C.TRACK_TYPE_VIDEO;
import static androidx.media3.exoplayer.Renderer.STATE_DISABLED;
import static androidx.media3.exoplayer.Renderer.STATE_ENABLED;
import static androidx.media3.exoplayer.Renderer.STATE_STARTED;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.Assertions;
import androidx.media3.exoplayer.metadata.MetadataRenderer;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.SampleStream;
import androidx.media3.exoplayer.text.TextRenderer;
import java.io.IOException;

/** Holds a {@link Renderer renderer}. */
/* package */ class RendererHolder {
  private final Renderer renderer;
  // Index of renderer in renderer list held by the {@link Player}.
  private final int index;
  private boolean requiresReset;

  public RendererHolder(Renderer renderer, int index) {
    this.renderer = renderer;
    this.index = index;
    requiresReset = false;
  }

  public int getEnabledRendererCount() {
    return isRendererEnabled(renderer) ? 1 : 0;
  }

  /**
   * Returns the track type that the renderer handles.
   *
   * @see Renderer#getTrackType()
   */
  public @C.TrackType int getTrackType() {
    return renderer.getTrackType();
  }

  /**
   * Returns reading position from the {@link Renderer} enabled on the {@link MediaPeriodHolder
   * media period}.
   *
   * <p>Call requires that {@link Renderer} is enabled on the provided {@link MediaPeriodHolder
   * media period}.
   *
   * @param period The {@link MediaPeriodHolder media period}
   * @return The {@link Renderer#getReadingPositionUs()} from the {@link Renderer} enabled on the
   *     {@link MediaPeriodHolder media period}.
   */
  public long getReadingPositionUs(@Nullable MediaPeriodHolder period) {
    Assertions.checkState(isReadingFromPeriod(period));
    return renderer.getReadingPositionUs();
  }

  /**
   * Invokes {@link Renderer#hasReadStreamToEnd()}.
   *
   * @see Renderer#hasReadStreamToEnd()
   */
  public boolean hasReadStreamToEnd() {
    return renderer.hasReadStreamToEnd();
  }

  /**
   * Signals to the renderer that the current {@link SampleStream} will be the final one supplied
   * before it is next disabled or reset.
   *
   * @see Renderer#setCurrentStreamFinal()
   * @param streamEndPositionUs The position to stop rendering at or {@link C#LENGTH_UNSET} to
   *     render until the end of the current stream.
   */
  public void setCurrentStreamFinal(long streamEndPositionUs) {
    if (renderer.getStream() != null) {
      setCurrentStreamFinal(renderer, streamEndPositionUs);
    }
  }

  private void setCurrentStreamFinal(Renderer renderer, long streamEndPositionUs) {
    renderer.setCurrentStreamFinal();
    if (renderer instanceof TextRenderer) {
      ((TextRenderer) renderer).setFinalStreamEndPositionUs(streamEndPositionUs);
    }
  }

  /**
   * Returns whether the current {@link SampleStream} will be the final one supplied before the
   * renderer is next disabled or reset.
   *
   * @see Renderer#isCurrentStreamFinal()
   */
  public boolean isCurrentStreamFinal() {
    return renderer.isCurrentStreamFinal();
  }

  /**
   * Invokes {@link Renderer#replaceStream}.
   *
   * @see Renderer#replaceStream
   */
  public void replaceStream(
      Format[] formats,
      SampleStream stream,
      long startPositionUs,
      long offsetUs,
      MediaSource.MediaPeriodId mediaPeriodId)
      throws ExoPlaybackException {
    renderer.replaceStream(formats, stream, startPositionUs, offsetUs, mediaPeriodId);
  }

  /**
   * Returns minimum amount of playback clock time that must pass in order for the {@link #render}
   * call to make progress.
   *
   * <p>Returns {@code Long.MAX_VALUE} if {@link Renderer renderers} are not enabled.
   *
   * @see Renderer#getDurationToProgressUs
   * @param rendererPositionUs The current render position in microseconds, measured at the start of
   *     the current iteration of the rendering loop.
   * @param rendererPositionElapsedRealtimeUs {@link android.os.SystemClock#elapsedRealtime()} in
   *     microseconds, measured at the start of the current iteration of the rendering loop.
   * @return Minimum amount of playback clock time that must pass before renderer is able to make
   *     progress.
   */
  public long getMinDurationToProgressUs(
      long rendererPositionUs, long rendererPositionElapsedRealtimeUs) {
    return isRendererEnabled(renderer)
        ? renderer.getDurationToProgressUs(rendererPositionUs, rendererPositionElapsedRealtimeUs)
        : Long.MAX_VALUE;
  }

  /**
   * Calls {@link Renderer#enableMayRenderStartOfStream} on enabled {@link Renderer renderers}.
   *
   * @see Renderer#enableMayRenderStartOfStream
   */
  public void enableMayRenderStartOfStream() {
    if (isRendererEnabled(renderer)) {
      renderer.enableMayRenderStartOfStream();
    }
  }

  /**
   * Calls {@link Renderer#setPlaybackSpeed} on the {@link Renderer renderers}.
   *
   * @see Renderer#setPlaybackSpeed
   */
  public void setPlaybackSpeed(float currentPlaybackSpeed, float targetPlaybackSpeed)
      throws ExoPlaybackException {
    renderer.setPlaybackSpeed(currentPlaybackSpeed, targetPlaybackSpeed);
  }

  /**
   * Calls {@link Renderer#setTimeline} on the {@link Renderer renderers}.
   *
   * @see Renderer#setTimeline
   */
  public void setTimeline(Timeline timeline) {
    renderer.setTimeline(timeline);
  }

  /**
   * Returns true if all renderers have {@link Renderer#isEnded() ended}.
   *
   * @see Renderer#isEnded()
   * @return if all renderers have {@link Renderer#isEnded() ended}.
   */
  public boolean isEnded() {
    return renderer.isEnded();
  }

  /**
   * Returns whether {@link Renderer} is enabled on a {@link MediaPeriodHolder media period}.
   *
   * @param period The {@link MediaPeriodHolder media period} to check.
   * @return Whether {@link Renderer} is enabled on a {@link MediaPeriodHolder media period}.
   */
  public boolean isReadingFromPeriod(@Nullable MediaPeriodHolder period) {
    return getRendererReadingFromPeriod(period) != null;
  }

  /**
   * Returns the {@link Renderer} that is enabled on the provided media {@link MediaPeriodHolder
   * period}.
   *
   * <p>Returns null if the renderer is not enabled on the requested period.
   *
   * @param period The {@link MediaPeriodHolder period} with which to retrieve the linked {@link
   *     Renderer}
   * @return {@link Renderer} enabled on the {@link MediaPeriodHolder period} or {@code null} if the
   *     renderer is not enabled on the provided period.
   */
  @Nullable
  private Renderer getRendererReadingFromPeriod(@Nullable MediaPeriodHolder period) {
    if (period == null || period.sampleStreams[index] == null) {
      return null;
    }
    if (renderer.getStream() == period.sampleStreams[index]) {
      return renderer;
    }
    return null;
  }

  /**
   * Returns whether the {@link Renderer renderers} are still reading a {@link MediaPeriodHolder
   * media period}.
   *
   * @param periodHolder The {@link MediaPeriodHolder media period} to check.
   * @return true if {@link Renderer renderers} are reading the current reading period.
   */
  public boolean hasFinishedReadingFromPeriod(MediaPeriodHolder periodHolder) {
    return hasFinishedReadingFromPeriodInternal(periodHolder);
  }

  private boolean hasFinishedReadingFromPeriodInternal(MediaPeriodHolder readingPeriodHolder) {
    SampleStream sampleStream = readingPeriodHolder.sampleStreams[index];
    if (renderer.getStream() != sampleStream
        || (sampleStream != null
            && !renderer.hasReadStreamToEnd()
            && !hasReachedServerSideInsertedAdsTransition(renderer, readingPeriodHolder))) {
      // The current reading period is still being read by at least one renderer.
      return false;
    }
    return true;
  }

  private boolean hasReachedServerSideInsertedAdsTransition(
      Renderer renderer, MediaPeriodHolder reading) {
    MediaPeriodHolder nextPeriod = reading.getNext();
    // We can advance the reading period early once we read beyond the transition point in a
    // server-side inserted ads stream because we know the samples are read from the same underlying
    // stream. This shortcut is helpful in case the transition point moved and renderers already
    // read beyond the new transition point. But wait until the next period is actually prepared to
    // allow a seamless transition.
    return reading.info.isFollowedByTransitionToSameStream
        && nextPeriod != null
        && nextPeriod.prepared
        && (renderer instanceof TextRenderer // [internal: b/181312195]
            || renderer instanceof MetadataRenderer
            || renderer.getReadingPositionUs() >= nextPeriod.getStartPositionRendererTime());
  }

  /**
   * Calls {@link Renderer#render} on all enabled {@link Renderer renderers}.
   *
   * @param rendererPositionUs The current media time in microseconds, measured at the start of the
   *     current iteration of the rendering loop.
   * @param rendererPositionElapsedRealtimeUs {@link android.os.SystemClock#elapsedRealtime()} in
   *     microseconds, measured at the start of the current iteration of the rendering loop.
   * @throws ExoPlaybackException If an error occurs.
   */
  public void render(long rendererPositionUs, long rendererPositionElapsedRealtimeUs)
      throws ExoPlaybackException {
    if (isRendererEnabled(renderer)) {
      renderer.render(rendererPositionUs, rendererPositionElapsedRealtimeUs);
    }
  }

  /**
   * Returns whether the renderers allow playback to continue.
   *
   * <p>Determine whether the renderer allows playback to continue. Playback can continue if the
   * renderer is ready or ended. Also continue playback if the renderer is reading ahead into the
   * next stream or is waiting for the next stream. This is to avoid getting stuck if tracks in the
   * current period have uneven durations and are still being read by another renderer. See:
   * https://github.com/google/ExoPlayer/issues/1874.
   *
   * @param playingPeriodHolder The currently playing media {@link MediaPeriodHolder period}.
   * @return whether renderer allows playback.
   */
  public boolean allowsPlayback(MediaPeriodHolder playingPeriodHolder) throws IOException {
    return allowsPlayback(renderer, playingPeriodHolder);
  }

  private boolean allowsPlayback(Renderer renderer, MediaPeriodHolder playingPeriodHolder) {
    boolean isReadingAhead = playingPeriodHolder.sampleStreams[index] != renderer.getStream();
    boolean isWaitingForNextStream = !isReadingAhead && renderer.hasReadStreamToEnd();
    return isReadingAhead || isWaitingForNextStream || renderer.isReady() || renderer.isEnded();
  }

  /**
   * Invokes {Renderer#maybeThrowStreamError}.
   *
   * @see Renderer#maybeThrowStreamError()
   */
  public void maybeThrowStreamError() throws IOException {
    renderer.maybeThrowStreamError();
  }

  /**
   * Calls {@link Renderer#start()} on all enabled {@link Renderer renderers}.
   *
   * @throws ExoPlaybackException If an error occurs.
   */
  public void start() throws ExoPlaybackException {
    if (renderer.getState() == STATE_ENABLED) {
      renderer.start();
    }
  }

  /** Calls {@link Renderer#stop()} on all enabled {@link Renderer renderers}. */
  public void stop() {
    if (isRendererEnabled(renderer)) {
      ensureStopped(renderer);
    }
  }

  private void ensureStopped(Renderer renderer) {
    if (renderer.getState() == STATE_STARTED) {
      renderer.stop();
    }
  }

  /**
   * Enables the renderer to consume from the specified {@link SampleStream}.
   *
   * @see Renderer#enable
   * @param configuration The renderer configuration.
   * @param formats The enabled formats.
   * @param stream The {@link SampleStream} from which the renderer should consume.
   * @param positionUs The player's current position.
   * @param joining Whether this renderer is being enabled to join an ongoing playback.
   * @param mayRenderStartOfStream Whether this renderer is allowed to render the start of the
   *     stream even if the state is not {@link Renderer#STATE_STARTED} yet.
   * @param startPositionUs The start position of the stream in renderer time (microseconds).
   * @param offsetUs The offset to be added to timestamps of buffers read from {@code stream} before
   *     they are rendered.
   * @param mediaPeriodId The {@link MediaSource.MediaPeriodId} of the {@link MediaPeriod} producing
   *     the {@code stream}.
   * @param mediaClock The {@link DefaultMediaClock} with which to call {@link
   *     DefaultMediaClock#onRendererEnabled(Renderer)}.
   * @throws ExoPlaybackException If an error occurs.
   */
  public void enable(
      RendererConfiguration configuration,
      Format[] formats,
      SampleStream stream,
      long positionUs,
      boolean joining,
      boolean mayRenderStartOfStream,
      long startPositionUs,
      long offsetUs,
      MediaSource.MediaPeriodId mediaPeriodId,
      DefaultMediaClock mediaClock)
      throws ExoPlaybackException {
    requiresReset = true;
    renderer.enable(
        configuration,
        formats,
        stream,
        positionUs,
        joining,
        mayRenderStartOfStream,
        startPositionUs,
        offsetUs,
        mediaPeriodId);
    mediaClock.onRendererEnabled(renderer);
  }

  /**
   * Invokes {@link Renderer#handleMessage} on the {@link Renderer}.
   *
   * @see Renderer#handleMessage(int, Object)
   */
  public void handleMessage(@Renderer.MessageType int messageType, @Nullable Object message)
      throws ExoPlaybackException {
    renderer.handleMessage(messageType, message);
  }

  /**
   * Stops and disables all {@link Renderer renderers}.
   *
   * @param mediaClock To call {@link DefaultMediaClock#onRendererDisabled} if disabling a {@link
   *     Renderer}.
   */
  public void disable(DefaultMediaClock mediaClock) {
    disableRenderer(renderer, mediaClock);
  }

  /**
   * Disable a {@link Renderer} if its enabled.
   *
   * <p>The {@link DefaultMediaClock#onRendererDisabled} callback will be invoked if the renderer is
   * disabled.
   *
   * @param renderer The {@link Renderer} to disable.
   * @param mediaClock The {@link DefaultMediaClock} to invoke {@link
   *     DefaultMediaClock#onRendererDisabled onRendererDisabled} with the provided {@code
   *     renderer}.
   */
  private void disableRenderer(Renderer renderer, DefaultMediaClock mediaClock) {
    if (!isRendererEnabled(renderer)) {
      return;
    }
    mediaClock.onRendererDisabled(renderer);
    ensureStopped(renderer);
    renderer.disable();
  }

  /**
   * Calls {@link Renderer#resetPosition} on the {@link Renderer} if its enabled.
   *
   * @see Renderer#resetPosition
   */
  public void resetPosition(long positionUs) throws ExoPlaybackException {
    if (isRendererEnabled(renderer)) {
      renderer.resetPosition(positionUs);
    }
  }

  /** Calls {@link Renderer#reset()} on all renderers that must be reset. */
  public void reset() {
    if (requiresReset) {
      renderer.reset();
      requiresReset = false;
    }
  }

  /** Calls {@link Renderer#release()} on all {@link Renderer renderers}. */
  public void release() {
    renderer.release();
    requiresReset = false;
  }

  public void setVideoOutput(@Nullable Object videoOutput) throws ExoPlaybackException {
    if (renderer.getTrackType() == TRACK_TYPE_VIDEO) {
      renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, videoOutput);
    }
  }

  private static boolean isRendererEnabled(Renderer renderer) {
    return renderer.getState() != STATE_DISABLED;
  }
}
