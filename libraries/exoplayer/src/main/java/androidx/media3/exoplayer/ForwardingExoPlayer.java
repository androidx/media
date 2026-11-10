/*
 * Copyright 2026 The Android Open Source Project
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

import static com.google.common.base.Preconditions.checkNotNull;

import android.media.AudioDeviceInfo;
import android.os.Looper;
import androidx.annotation.GuardedBy;
import androidx.annotation.IntRange;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.AuxEffectInfo;
import androidx.media3.common.C;
import androidx.media3.common.DeviceInfo;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.ForwardingSimpleBasePlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Metadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.PriorityTaskManager;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.analytics.AnalyticsCollector;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.drm.DrmSession;
import androidx.media3.exoplayer.drm.KeyRequestInfo;
import androidx.media3.exoplayer.image.ImageOutput;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.exoplayer.source.ShuffleOrder;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.TrackSelectionArray;
import androidx.media3.exoplayer.trackselection.TrackSelector;
import androidx.media3.exoplayer.video.VideoFrameMetadataListener;
import androidx.media3.exoplayer.video.spherical.CameraMotionListener;
import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * An {@link ExoPlayer} that forwards all calls to another {@link ExoPlayer}.
 *
 * <p>Note that due to the stateful nature of {@link Player} and {@link ExoPlayer}, safely
 * overriding methods on this class to modify playback behavior can be difficult and error-prone.
 * Applications wishing to modify {@link Player} behavior should generally prefer {@link
 * ForwardingSimpleBasePlayer}.
 */
@UnstableApi
public class ForwardingExoPlayer extends ForwardingPlayer implements ExoPlayer {

  @GuardedBy("analyticsListeners")
  private final IdentityHashMap<AnalyticsListener, ForwardingAnalyticsListener> analyticsListeners =
      new IdentityHashMap<>();

  private final ExoPlayer player;
  private final ForwardingAnalyticsCollector forwardingAnalyticsCollector;

  /** Creates a new instance that forwards all operations to {@code player}. */
  @SuppressWarnings({
    "nullness:argument.type.incompatible",
    "nullness:assignment.type.incompatible"
  }) // Using this in constructor.
  public ForwardingExoPlayer(ExoPlayer player) {
    super(player);
    this.player = player;
    this.forwardingAnalyticsCollector = new ForwardingAnalyticsCollector(this);
  }

  /** Calls {@link ExoPlayer#getPlayerError()} on the delegate and returns the result. */
  @Override
  public @Nullable ExoPlaybackException getPlayerError() {
    return player.getPlayerError();
  }

  /** Calls {@link ExoPlayer#addAudioOffloadListener(AudioOffloadListener)} on the delegate. */
  @Override
  public void addAudioOffloadListener(AudioOffloadListener listener) {
    player.addAudioOffloadListener(listener);
  }

  /** Calls {@link ExoPlayer#removeAudioOffloadListener(AudioOffloadListener)} on the delegate. */
  @Override
  public void removeAudioOffloadListener(AudioOffloadListener listener) {
    player.removeAudioOffloadListener(listener);
  }

  /**
   * Returns an {@link AnalyticsCollector} that forwards all operations to the delegate's {@link
   * AnalyticsCollector}.
   */
  @Override
  public AnalyticsCollector getAnalyticsCollector() {
    return forwardingAnalyticsCollector;
  }

  /**
   * Calls {@link ExoPlayer#addAnalyticsListener(AnalyticsListener)} on the delegate.
   *
   * <p>Overrides of this method must <strong>not</strong> directly call {@code
   * delegate.addAnalyticsListener}. If the override wants to pass the {@link AnalyticsListener}
   * instance to the delegate {@link ExoPlayer}, it must do so by calling {@code
   * super.addAnalyticsListener} instead. This ensures the correct {@link Player} instance is passed
   * to {@link AnalyticsListener#onEvents(Player, AnalyticsListener.Events)} (i.e. this forwarding
   * instance, and not the underlying {@code delegate} instance).
   */
  @Override
  public void addAnalyticsListener(AnalyticsListener listener) {
    checkNotNull(listener);
    synchronized (analyticsListeners) {
      @Nullable ForwardingAnalyticsListener forwardingListener = analyticsListeners.get(listener);
      if (forwardingListener == null) {
        forwardingListener = new ForwardingAnalyticsListener(this, listener);
      }
      player.addAnalyticsListener(forwardingListener);
      analyticsListeners.put(listener, forwardingListener);
    }
  }

  /**
   * Calls {@link ExoPlayer#removeAnalyticsListener(AnalyticsListener)} on the delegate.
   *
   * <p>Overrides of this method must <strong>not</strong> directly call {@code
   * delegate.removeAnalyticsListener}. If the override wants to pass the {@link AnalyticsListener}
   * instance to the delegate {@link ExoPlayer}, it must do so by calling {@code
   * super.removeAnalyticsListener} instead.
   */
  @Override
  public void removeAnalyticsListener(AnalyticsListener listener) {
    checkNotNull(listener);
    synchronized (analyticsListeners) {
      @Nullable AnalyticsListener forwardingListener = analyticsListeners.remove(listener);
      // If forwardingListener is null, we don't know this listener. Just pass in the real listener
      // as the underlying player would not know it either. It can decide to throw or ignore.
      player.removeAnalyticsListener(forwardingListener != null ? forwardingListener : listener);
    }
  }

  /** Calls {@link ExoPlayer#getRendererCount()} on the delegate and returns the result. */
  @Override
  public int getRendererCount() {
    return player.getRendererCount();
  }

  /** Calls {@link ExoPlayer#getRendererType(int)} on the delegate and returns the result. */
  @Override
  public @C.TrackType int getRendererType(int index) {
    return player.getRendererType(index);
  }

  /** Calls {@link ExoPlayer#getRenderer(int)} on the delegate and returns the result. */
  @Override
  public Renderer getRenderer(int index) {
    return player.getRenderer(index);
  }

  /** Calls {@link ExoPlayer#getSecondaryRenderer(int)} on the delegate and returns the result. */
  @Override
  public @Nullable Renderer getSecondaryRenderer(int index) {
    return player.getSecondaryRenderer(index);
  }

  /** Calls {@link ExoPlayer#getTrackSelector()} on the delegate and returns the result. */
  @Override
  public @Nullable TrackSelector getTrackSelector() {
    return player.getTrackSelector();
  }

  /**
   * Calls {@link ExoPlayer#getCurrentTrackGroups()} on the delegate and returns the result.
   *
   * @deprecated Use {@link #getCurrentTracks()} instead.
   */
  @SuppressWarnings("deprecation") // Forwarding to deprecated method.
  @Deprecated
  @Override
  public TrackGroupArray getCurrentTrackGroups() {
    return player.getCurrentTrackGroups();
  }

  /**
   * Calls {@link ExoPlayer#getCurrentTrackSelections()} on the delegate and returns the result.
   *
   * @deprecated Use {@link #getCurrentTracks()} instead.
   */
  @SuppressWarnings("deprecation") // Forwarding to deprecated method.
  @Deprecated
  @Override
  public TrackSelectionArray getCurrentTrackSelections() {
    return player.getCurrentTrackSelections();
  }

  /** Calls {@link ExoPlayer#getPlaybackLooper()} on the delegate and returns the result. */
  @Override
  public Looper getPlaybackLooper() {
    return player.getPlaybackLooper();
  }

  /** Calls {@link ExoPlayer#getClock()} on the delegate and returns the result. */
  @Override
  public Clock getClock() {
    return player.getClock();
  }

  /**
   * Calls {@link ExoPlayer#prepare(MediaSource)} on the delegate.
   *
   * @deprecated Use {@link #setMediaSource(MediaSource)} and {@link #prepare()} instead.
   */
  @SuppressWarnings("deprecation") // Forwarding to deprecated method.
  @Deprecated
  @Override
  public void prepare(MediaSource mediaSource) {
    player.prepare(mediaSource);
  }

  /**
   * Calls {@link ExoPlayer#prepare(MediaSource, boolean, boolean)} on the delegate.
   *
   * @deprecated Use {@link #setMediaSource(MediaSource, boolean)} and {@link #prepare()} instead.
   */
  @SuppressWarnings("deprecation") // Forwarding to deprecated method.
  @Deprecated
  @Override
  public void prepare(MediaSource mediaSource, boolean resetPosition, boolean resetState) {
    player.prepare(mediaSource, resetPosition, resetState);
  }

  /** Calls {@link ExoPlayer#setMediaSources(List)} on the delegate. */
  @Override
  public void setMediaSources(List<MediaSource> mediaSources) {
    player.setMediaSources(mediaSources);
  }

  /** Calls {@link ExoPlayer#setMediaSources(List, boolean)} on the delegate. */
  @Override
  public void setMediaSources(List<MediaSource> mediaSources, boolean resetPosition) {
    player.setMediaSources(mediaSources, resetPosition);
  }

  /** Calls {@link ExoPlayer#setMediaSources(List, int, long)} on the delegate. */
  @Override
  public void setMediaSources(
      List<MediaSource> mediaSources, int startMediaItemIndex, long startPositionMs) {
    player.setMediaSources(mediaSources, startMediaItemIndex, startPositionMs);
  }

  /** Calls {@link ExoPlayer#setMediaSource(MediaSource)} on the delegate. */
  @Override
  public void setMediaSource(MediaSource mediaSource) {
    player.setMediaSource(mediaSource);
  }

  /** Calls {@link ExoPlayer#setMediaSource(MediaSource, long)} on the delegate. */
  @Override
  public void setMediaSource(MediaSource mediaSource, long startPositionMs) {
    player.setMediaSource(mediaSource, startPositionMs);
  }

  /** Calls {@link ExoPlayer#setMediaSource(MediaSource, boolean)} on the delegate. */
  @Override
  public void setMediaSource(MediaSource mediaSource, boolean resetPosition) {
    player.setMediaSource(mediaSource, resetPosition);
  }

  /** Calls {@link ExoPlayer#addMediaSource(MediaSource)} on the delegate. */
  @Override
  public void addMediaSource(MediaSource mediaSource) {
    player.addMediaSource(mediaSource);
  }

  /** Calls {@link ExoPlayer#addMediaSource(int, MediaSource)} on the delegate. */
  @Override
  public void addMediaSource(int index, MediaSource mediaSource) {
    player.addMediaSource(index, mediaSource);
  }

  /** Calls {@link ExoPlayer#addMediaSources(List)} on the delegate. */
  @Override
  public void addMediaSources(List<MediaSource> mediaSources) {
    player.addMediaSources(mediaSources);
  }

  /** Calls {@link ExoPlayer#addMediaSources(int, List)} on the delegate. */
  @Override
  public void addMediaSources(int index, List<MediaSource> mediaSources) {
    player.addMediaSources(index, mediaSources);
  }

  /** Calls {@link ExoPlayer#setShuffleOrder(ShuffleOrder)} on the delegate. */
  @Override
  public void setShuffleOrder(ShuffleOrder shuffleOrder) {
    player.setShuffleOrder(shuffleOrder);
  }

  /** Calls {@link ExoPlayer#getShuffleOrder()} on the delegate and returns the result. */
  @Override
  public ShuffleOrder getShuffleOrder() {
    return player.getShuffleOrder();
  }

  /** Calls {@link ExoPlayer#setPreloadConfiguration(PreloadConfiguration)} on the delegate. */
  @Override
  public void setPreloadConfiguration(PreloadConfiguration preloadConfiguration) {
    player.setPreloadConfiguration(preloadConfiguration);
  }

  /** Calls {@link ExoPlayer#getPreloadConfiguration()} on the delegate and returns the result. */
  @Override
  public PreloadConfiguration getPreloadConfiguration() {
    return player.getPreloadConfiguration();
  }

  /** Calls {@link ExoPlayer#replaceMediaItem(int, MediaItem)} on the delegate. */
  @Override
  public void replaceMediaItem(int index, MediaItem mediaItem) {
    player.replaceMediaItem(index, mediaItem);
  }

  /** Calls {@link ExoPlayer#replaceMediaItems(int, int, List)} on the delegate. */
  @Override
  public void replaceMediaItems(int fromIndex, int toIndex, List<MediaItem> mediaItems) {
    player.replaceMediaItems(fromIndex, toIndex, mediaItems);
  }

  /** Calls {@link ExoPlayer#setAudioSessionId(int)} on the delegate. */
  @Override
  public void setAudioSessionId(int audioSessionId) {
    player.setAudioSessionId(audioSessionId);
  }

  /** Calls {@link ExoPlayer#setAuxEffectInfo(AuxEffectInfo)} on the delegate. */
  @Override
  public void setAuxEffectInfo(AuxEffectInfo auxEffectInfo) {
    player.setAuxEffectInfo(auxEffectInfo);
  }

  /** Calls {@link ExoPlayer#clearAuxEffectInfo()} on the delegate. */
  @Override
  public void clearAuxEffectInfo() {
    player.clearAuxEffectInfo();
  }

  /** Calls {@link ExoPlayer#setPreferredAudioDevice(AudioDeviceInfo)} on the delegate. */
  @Override
  public void setPreferredAudioDevice(@Nullable AudioDeviceInfo audioDeviceInfo) {
    player.setPreferredAudioDevice(audioDeviceInfo);
  }

  /** Calls {@link ExoPlayer#setVirtualDeviceId(int)} on the delegate. */
  @Override
  public void setVirtualDeviceId(int virtualDeviceId) {
    player.setVirtualDeviceId(virtualDeviceId);
  }

  /** Calls {@link ExoPlayer#setSkipSilenceEnabled(boolean)} on the delegate. */
  @Override
  public void setSkipSilenceEnabled(boolean skipSilenceEnabled) {
    player.setSkipSilenceEnabled(skipSilenceEnabled);
  }

  /** Calls {@link ExoPlayer#getSkipSilenceEnabled()} on the delegate and returns the result. */
  @Override
  public boolean getSkipSilenceEnabled() {
    return player.getSkipSilenceEnabled();
  }

  /** Calls {@link ExoPlayer#setScrubbingModeEnabled(boolean)} on the delegate. */
  @Override
  public void setScrubbingModeEnabled(boolean scrubbingModeEnabled) {
    player.setScrubbingModeEnabled(scrubbingModeEnabled);
  }

  /** Calls {@link ExoPlayer#isScrubbingModeEnabled()} on the delegate and returns the result. */
  @Override
  public boolean isScrubbingModeEnabled() {
    return player.isScrubbingModeEnabled();
  }

  /**
   * Calls {@link ExoPlayer#setScrubbingModeParameters(ScrubbingModeParameters)} on the delegate.
   */
  @Override
  public void setScrubbingModeParameters(ScrubbingModeParameters scrubbingModeParameters) {
    player.setScrubbingModeParameters(scrubbingModeParameters);
  }

  /**
   * Calls {@link ExoPlayer#getScrubbingModeParameters()} on the delegate and returns the result.
   */
  @Override
  public ScrubbingModeParameters getScrubbingModeParameters() {
    return player.getScrubbingModeParameters();
  }

  /** Calls {@link ExoPlayer#setVideoEffects(List)} on the delegate. */
  @Override
  public void setVideoEffects(List<Effect> videoEffects) {
    player.setVideoEffects(videoEffects);
  }

  /** Calls {@link ExoPlayer#setVideoScalingMode(int)} on the delegate. */
  @Override
  public void setVideoScalingMode(@C.VideoScalingMode int videoScalingMode) {
    player.setVideoScalingMode(videoScalingMode);
  }

  /** Calls {@link ExoPlayer#getVideoScalingMode()} on the delegate and returns the result. */
  @Override
  public @C.VideoScalingMode int getVideoScalingMode() {
    return player.getVideoScalingMode();
  }

  /** Calls {@link ExoPlayer#setVideoChangeFrameRateStrategy(int)} on the delegate. */
  @Override
  public void setVideoChangeFrameRateStrategy(
      @C.VideoChangeFrameRateStrategy int videoChangeFrameRateStrategy) {
    player.setVideoChangeFrameRateStrategy(videoChangeFrameRateStrategy);
  }

  /**
   * Calls {@link ExoPlayer#getVideoChangeFrameRateStrategy()} on the delegate and returns the
   * result.
   */
  @Override
  public @C.VideoChangeFrameRateStrategy int getVideoChangeFrameRateStrategy() {
    return player.getVideoChangeFrameRateStrategy();
  }

  /**
   * Calls {@link ExoPlayer#addVideoFrameMetadataListener(VideoFrameMetadataListener)} on the
   * delegate.
   */
  @Override
  public void addVideoFrameMetadataListener(VideoFrameMetadataListener listener) {
    player.addVideoFrameMetadataListener(listener);
  }

  /**
   * Calls {@link ExoPlayer#removeVideoFrameMetadataListener(VideoFrameMetadataListener)} on the
   * delegate.
   */
  @Override
  public void removeVideoFrameMetadataListener(VideoFrameMetadataListener listener) {
    player.removeVideoFrameMetadataListener(listener);
  }

  /**
   * Calls {@link ExoPlayer#setVideoFrameMetadataListener(VideoFrameMetadataListener)} on the
   * delegate.
   *
   * @deprecated Use {@link #addVideoFrameMetadataListener(VideoFrameMetadataListener)} and {@link
   *     #removeVideoFrameMetadataListener(VideoFrameMetadataListener)} instead.
   */
  @SuppressWarnings("deprecation") // Forwarding to deprecated method.
  @Deprecated
  @Override
  public void setVideoFrameMetadataListener(VideoFrameMetadataListener listener) {
    player.setVideoFrameMetadataListener(listener);
  }

  /**
   * Calls {@link ExoPlayer#clearVideoFrameMetadataListener(VideoFrameMetadataListener)} on the
   * delegate.
   *
   * @deprecated Use {@link #removeVideoFrameMetadataListener(VideoFrameMetadataListener)} instead.
   */
  @SuppressWarnings("deprecation") // Forwarding to deprecated method.
  @Deprecated
  @Override
  public void clearVideoFrameMetadataListener(VideoFrameMetadataListener listener) {
    player.clearVideoFrameMetadataListener(listener);
  }

  /** Calls {@link ExoPlayer#setCameraMotionListener(CameraMotionListener)} on the delegate. */
  @Override
  public void setCameraMotionListener(CameraMotionListener listener) {
    player.setCameraMotionListener(listener);
  }

  /** Calls {@link ExoPlayer#clearCameraMotionListener(CameraMotionListener)} on the delegate. */
  @Override
  public void clearCameraMotionListener(CameraMotionListener listener) {
    player.clearCameraMotionListener(listener);
  }

  /**
   * Calls {@link ExoPlayer#createMessage(PlayerMessage.Target)} on the delegate and returns the
   * result.
   */
  @Override
  public PlayerMessage createMessage(PlayerMessage.Target target) {
    return player.createMessage(target);
  }

  /** Calls {@link ExoPlayer#setSeekParameters(SeekParameters)} on the delegate. */
  @Override
  public void setSeekParameters(@Nullable SeekParameters seekParameters) {
    player.setSeekParameters(seekParameters);
  }

  /** Calls {@link ExoPlayer#getSeekParameters()} on the delegate and returns the result. */
  @Override
  public SeekParameters getSeekParameters() {
    return player.getSeekParameters();
  }

  /** Calls {@link ExoPlayer#setSeekBackIncrementMs(long)} on the delegate. */
  @Override
  public void setSeekBackIncrementMs(@IntRange(from = 1) long seekBackIncrementMs) {
    player.setSeekBackIncrementMs(seekBackIncrementMs);
  }

  /** Calls {@link ExoPlayer#setSeekForwardIncrementMs(long)} on the delegate. */
  @Override
  public void setSeekForwardIncrementMs(@IntRange(from = 1) long seekForwardIncrementMs) {
    player.setSeekForwardIncrementMs(seekForwardIncrementMs);
  }

  /** Calls {@link ExoPlayer#setMaxSeekToPreviousPositionMs(long)} on the delegate. */
  @Override
  public void setMaxSeekToPreviousPositionMs(@IntRange(from = 0) long maxSeekToPreviousPositionMs) {
    player.setMaxSeekToPreviousPositionMs(maxSeekToPreviousPositionMs);
  }

  /** Calls {@link ExoPlayer#setForegroundMode(boolean)} on the delegate. */
  @Override
  public void setForegroundMode(boolean foregroundMode) {
    player.setForegroundMode(foregroundMode);
  }

  /** Calls {@link ExoPlayer#setPauseAtEndOfMediaItems(boolean)} on the delegate. */
  @Override
  public void setPauseAtEndOfMediaItems(boolean pauseAtEndOfMediaItems) {
    player.setPauseAtEndOfMediaItems(pauseAtEndOfMediaItems);
  }

  /** Calls {@link ExoPlayer#setEnforceAdPlaybackOnTimelineRefresh(boolean)} on the delegate. */
  @Override
  public void setEnforceAdPlaybackOnTimelineRefresh(boolean enforceAdPlaybackOnTimelineRefresh) {
    player.setEnforceAdPlaybackOnTimelineRefresh(enforceAdPlaybackOnTimelineRefresh);
  }

  /** Calls {@link ExoPlayer#getPauseAtEndOfMediaItems()} on the delegate and returns the result. */
  @Override
  public boolean getPauseAtEndOfMediaItems() {
    return player.getPauseAtEndOfMediaItems();
  }

  /** Calls {@link ExoPlayer#getAudioFormat()} on the delegate and returns the result. */
  @Override
  public @Nullable Format getAudioFormat() {
    return player.getAudioFormat();
  }

  /** Calls {@link ExoPlayer#getVideoFormat()} on the delegate and returns the result. */
  @Override
  public @Nullable Format getVideoFormat() {
    return player.getVideoFormat();
  }

  /** Calls {@link ExoPlayer#getAudioDecoderCounters()} on the delegate and returns the result. */
  @Override
  public @Nullable DecoderCounters getAudioDecoderCounters() {
    return player.getAudioDecoderCounters();
  }

  /** Calls {@link ExoPlayer#getVideoDecoderCounters()} on the delegate and returns the result. */
  @Override
  public @Nullable DecoderCounters getVideoDecoderCounters() {
    return player.getVideoDecoderCounters();
  }

  /** Calls {@link ExoPlayer#setHandleAudioBecomingNoisy(boolean)} on the delegate. */
  @Override
  public void setHandleAudioBecomingNoisy(boolean handleAudioBecomingNoisy) {
    player.setHandleAudioBecomingNoisy(handleAudioBecomingNoisy);
  }

  /** Calls {@link ExoPlayer#setWakeMode(int)} on the delegate. */
  @Override
  public void setWakeMode(@C.WakeMode int wakeMode) {
    player.setWakeMode(wakeMode);
  }

  /** Calls {@link ExoPlayer#setPriority(int)} on the delegate. */
  @Override
  public void setPriority(@C.Priority int priority) {
    player.setPriority(priority);
  }

  /** Calls {@link ExoPlayer#setPriorityTaskManager(PriorityTaskManager)} on the delegate. */
  @Override
  public void setPriorityTaskManager(@Nullable PriorityTaskManager priorityTaskManager) {
    player.setPriorityTaskManager(priorityTaskManager);
  }

  /** Calls {@link ExoPlayer#isSleepingForOffload()} on the delegate and returns the result. */
  @Override
  public boolean isSleepingForOffload() {
    return player.isSleepingForOffload();
  }

  /** Calls {@link ExoPlayer#isTunnelingEnabled()} on the delegate and returns the result. */
  @Override
  public boolean isTunnelingEnabled() {
    return player.isTunnelingEnabled();
  }

  /** Calls {@link ExoPlayer#release()} on the delegate. */
  @Override
  public void release() {
    player.release();
  }

  /** Calls {@link ExoPlayer#isReleased()} on the delegate and returns the result. */
  @Override
  public boolean isReleased() {
    return player.isReleased();
  }

  /** Calls {@link ExoPlayer#setImageOutput(ImageOutput)} on the delegate. */
  @Override
  public void setImageOutput(@Nullable ImageOutput imageOutput) {
    player.setImageOutput(imageOutput);
  }

  /** Calls {@link ExoPlayer#setAudioCodecParameters(CodecParameters)} on the delegate. */
  @Override
  public void setAudioCodecParameters(CodecParameters codecParameters) {
    player.setAudioCodecParameters(codecParameters);
  }

  /**
   * Calls {@link ExoPlayer#addAudioCodecParametersChangeListener(CodecParametersChangeListener,
   * List)} on the delegate.
   */
  @Override
  public void addAudioCodecParametersChangeListener(
      CodecParametersChangeListener listener, List<String> keys) {
    player.addAudioCodecParametersChangeListener(listener, keys);
  }

  /**
   * Calls {@link ExoPlayer#removeAudioCodecParametersChangeListener(CodecParametersChangeListener)}
   * on the delegate.
   */
  @Override
  public void removeAudioCodecParametersChangeListener(CodecParametersChangeListener listener) {
    player.removeAudioCodecParametersChangeListener(listener);
  }

  /** Calls {@link ExoPlayer#setVideoCodecParameters(CodecParameters)} on the delegate. */
  @Override
  public void setVideoCodecParameters(CodecParameters codecParameters) {
    player.setVideoCodecParameters(codecParameters);
  }

  /**
   * Calls {@link ExoPlayer#addVideoCodecParametersChangeListener(CodecParametersChangeListener,
   * List)} on the delegate.
   */
  @Override
  public void addVideoCodecParametersChangeListener(
      CodecParametersChangeListener listener, List<String> keys) {
    player.addVideoCodecParametersChangeListener(listener, keys);
  }

  /**
   * Calls {@link ExoPlayer#removeVideoCodecParametersChangeListener(CodecParametersChangeListener)}
   * on the delegate.
   */
  @Override
  public void removeVideoCodecParametersChangeListener(CodecParametersChangeListener listener) {
    player.removeVideoCodecParametersChangeListener(listener);
  }

  /** Returns the {@link ExoPlayer} to which operations are forwarded. */
  @Override
  public ExoPlayer getWrappedPlayer() {
    return player;
  }

  private static final class ForwardingAnalyticsListener implements AnalyticsListener {

    private final ForwardingExoPlayer forwardingExoPlayer;
    private final AnalyticsListener listener;

    private ForwardingAnalyticsListener(
        ForwardingExoPlayer forwardingExoPlayer, AnalyticsListener listener) {
      this.forwardingExoPlayer = forwardingExoPlayer;
      this.listener = listener;
    }

    @SuppressWarnings("deprecation") // Forwarding to deprecated method.
    @Deprecated
    @Override
    public void onPlayerStateChanged(
        EventTime eventTime, boolean playWhenReady, @Player.State int playbackState) {
      listener.onPlayerStateChanged(eventTime, playWhenReady, playbackState);
    }

    @Override
    public void onPlaybackStateChanged(EventTime eventTime, @Player.State int state) {
      listener.onPlaybackStateChanged(eventTime, state);
    }

    @Override
    public void onPlayWhenReadyChanged(
        EventTime eventTime, boolean playWhenReady, @Player.PlayWhenReadyChangeReason int reason) {
      listener.onPlayWhenReadyChanged(eventTime, playWhenReady, reason);
    }

    @Override
    public void onPlaybackSuppressionReasonChanged(
        EventTime eventTime, @PlaybackSuppressionReason int playbackSuppressionReason) {
      listener.onPlaybackSuppressionReasonChanged(eventTime, playbackSuppressionReason);
    }

    @Override
    public void onIsPlayingChanged(EventTime eventTime, boolean isPlaying) {
      listener.onIsPlayingChanged(eventTime, isPlaying);
    }

    @Override
    public void onTimelineChanged(EventTime eventTime, @TimelineChangeReason int reason) {
      listener.onTimelineChanged(eventTime, reason);
    }

    @Override
    public void onMediaItemTransition(
        EventTime eventTime,
        @Nullable MediaItem mediaItem,
        @Player.MediaItemTransitionReason int reason) {
      listener.onMediaItemTransition(eventTime, mediaItem, reason);
    }

    @SuppressWarnings("deprecation") // Forwarding to deprecated method.
    @Deprecated
    @Override
    public void onPositionDiscontinuity(EventTime eventTime, @DiscontinuityReason int reason) {
      listener.onPositionDiscontinuity(eventTime, reason);
    }

    @Override
    public void onPositionDiscontinuity(
        EventTime eventTime,
        Player.PositionInfo oldPosition,
        Player.PositionInfo newPosition,
        @DiscontinuityReason int reason) {
      listener.onPositionDiscontinuity(eventTime, oldPosition, newPosition, reason);
    }

    @SuppressWarnings("deprecation") // Forwarding to deprecated method.
    @Deprecated
    @Override
    public void onSeekStarted(EventTime eventTime) {
      listener.onSeekStarted(eventTime);
    }

    @Override
    public void onPlaybackParametersChanged(
        EventTime eventTime, PlaybackParameters playbackParameters) {
      listener.onPlaybackParametersChanged(eventTime, playbackParameters);
    }

    @Override
    public void onSeekBackIncrementChanged(EventTime eventTime, long seekBackIncrementMs) {
      listener.onSeekBackIncrementChanged(eventTime, seekBackIncrementMs);
    }

    @Override
    public void onSeekForwardIncrementChanged(EventTime eventTime, long seekForwardIncrementMs) {
      listener.onSeekForwardIncrementChanged(eventTime, seekForwardIncrementMs);
    }

    @Override
    public void onMaxSeekToPreviousPositionChanged(
        EventTime eventTime, long maxSeekToPreviousPositionMs) {
      listener.onMaxSeekToPreviousPositionChanged(eventTime, maxSeekToPreviousPositionMs);
    }

    @Override
    public void onRepeatModeChanged(EventTime eventTime, @Player.RepeatMode int repeatMode) {
      listener.onRepeatModeChanged(eventTime, repeatMode);
    }

    @Override
    public void onShuffleModeChanged(EventTime eventTime, boolean shuffleModeEnabled) {
      listener.onShuffleModeChanged(eventTime, shuffleModeEnabled);
    }

    @Override
    public void onIsLoadingChanged(EventTime eventTime, boolean isLoading) {
      listener.onIsLoadingChanged(eventTime, isLoading);
    }

    @SuppressWarnings("deprecation") // Forwarding to deprecated method.
    @Deprecated
    @Override
    public void onLoadingChanged(EventTime eventTime, boolean isLoading) {
      listener.onLoadingChanged(eventTime, isLoading);
    }

    @Override
    public void onAvailableCommandsChanged(EventTime eventTime, Player.Commands availableCommands) {
      listener.onAvailableCommandsChanged(eventTime, availableCommands);
    }

    @Override
    public void onPlayerError(EventTime eventTime, PlaybackException error) {
      listener.onPlayerError(eventTime, error);
    }

    @Override
    public void onPlayerErrorChanged(EventTime eventTime, @Nullable PlaybackException error) {
      listener.onPlayerErrorChanged(eventTime, error);
    }

    @Override
    public void onTracksChanged(EventTime eventTime, Tracks tracks) {
      listener.onTracksChanged(eventTime, tracks);
    }

    @Override
    public void onTrackSelectionParametersChanged(
        EventTime eventTime, TrackSelectionParameters trackSelectionParameters) {
      listener.onTrackSelectionParametersChanged(eventTime, trackSelectionParameters);
    }

    @Override
    public void onMediaMetadataChanged(EventTime eventTime, MediaMetadata mediaMetadata) {
      listener.onMediaMetadataChanged(eventTime, mediaMetadata);
    }

    @Override
    public void onPlaylistMetadataChanged(EventTime eventTime, MediaMetadata playlistMetadata) {
      listener.onPlaylistMetadataChanged(eventTime, playlistMetadata);
    }

    @SuppressWarnings("deprecation") // Forwarding to deprecated method.
    @Deprecated
    @Override
    public void onLoadStarted(
        EventTime eventTime, LoadEventInfo loadEventInfo, MediaLoadData mediaLoadData) {
      listener.onLoadStarted(eventTime, loadEventInfo, mediaLoadData);
    }

    @Override
    public void onLoadStarted(
        EventTime eventTime,
        LoadEventInfo loadEventInfo,
        MediaLoadData mediaLoadData,
        int retryCount) {
      listener.onLoadStarted(eventTime, loadEventInfo, mediaLoadData, retryCount);
    }

    @Override
    public void onLoadCompleted(
        EventTime eventTime, LoadEventInfo loadEventInfo, MediaLoadData mediaLoadData) {
      listener.onLoadCompleted(eventTime, loadEventInfo, mediaLoadData);
    }

    @Override
    public void onLoadCanceled(
        EventTime eventTime, LoadEventInfo loadEventInfo, MediaLoadData mediaLoadData) {
      listener.onLoadCanceled(eventTime, loadEventInfo, mediaLoadData);
    }

    @Override
    public void onLoadError(
        EventTime eventTime,
        LoadEventInfo loadEventInfo,
        MediaLoadData mediaLoadData,
        IOException error,
        boolean wasCanceled) {
      listener.onLoadError(eventTime, loadEventInfo, mediaLoadData, error, wasCanceled);
    }

    @Override
    public void onDownstreamFormatChanged(EventTime eventTime, MediaLoadData mediaLoadData) {
      listener.onDownstreamFormatChanged(eventTime, mediaLoadData);
    }

    @Override
    public void onUpstreamDiscarded(EventTime eventTime, MediaLoadData mediaLoadData) {
      listener.onUpstreamDiscarded(eventTime, mediaLoadData);
    }

    @Override
    public void onBandwidthEstimate(
        EventTime eventTime, int totalLoadTimeMs, long totalBytesLoaded, long bitrateEstimate) {
      listener.onBandwidthEstimate(eventTime, totalLoadTimeMs, totalBytesLoaded, bitrateEstimate);
    }

    @Override
    public void onMetadata(EventTime eventTime, Metadata metadata) {
      listener.onMetadata(eventTime, metadata);
    }

    @SuppressWarnings("deprecation") // Forwarding to deprecated method.
    @Deprecated
    @Override
    public void onCues(EventTime eventTime, List<Cue> cues) {
      listener.onCues(eventTime, cues);
    }

    @Override
    public void onCues(EventTime eventTime, CueGroup cueGroup) {
      listener.onCues(eventTime, cueGroup);
    }

    @Override
    public void onAudioEnabled(EventTime eventTime, DecoderCounters decoderCounters) {
      listener.onAudioEnabled(eventTime, decoderCounters);
    }

    @Override
    public void onAudioDecoderInitialized(
        EventTime eventTime,
        String decoderName,
        long initializedTimestampMs,
        long initializationDurationMs) {
      listener.onAudioDecoderInitialized(
          eventTime, decoderName, initializedTimestampMs, initializationDurationMs);
    }

    @SuppressWarnings("deprecation") // Forwarding to deprecated method.
    @Deprecated
    @Override
    public void onAudioDecoderInitialized(
        EventTime eventTime, String decoderName, long initializationDurationMs) {
      listener.onAudioDecoderInitialized(eventTime, decoderName, initializationDurationMs);
    }

    @Override
    public void onAudioInputFormatChanged(
        EventTime eventTime,
        Format format,
        @Nullable DecoderReuseEvaluation decoderReuseEvaluation) {
      listener.onAudioInputFormatChanged(eventTime, format, decoderReuseEvaluation);
    }

    @Override
    public void onAudioPositionAdvancing(EventTime eventTime, long playoutStartSystemTimeMs) {
      listener.onAudioPositionAdvancing(eventTime, playoutStartSystemTimeMs);
    }

    @Override
    public void onAudioUnderrun(
        EventTime eventTime, int bufferSize, long bufferSizeMs, long elapsedSinceLastFeedMs) {
      listener.onAudioUnderrun(eventTime, bufferSize, bufferSizeMs, elapsedSinceLastFeedMs);
    }

    @Override
    public void onAudioDecoderReleased(EventTime eventTime, String decoderName) {
      listener.onAudioDecoderReleased(eventTime, decoderName);
    }

    @Override
    public void onAudioDisabled(EventTime eventTime, DecoderCounters decoderCounters) {
      listener.onAudioDisabled(eventTime, decoderCounters);
    }

    @Override
    public void onAudioSessionIdChanged(EventTime eventTime, int audioSessionId) {
      listener.onAudioSessionIdChanged(eventTime, audioSessionId);
    }

    @Override
    public void onAudioAttributesChanged(EventTime eventTime, AudioAttributes audioAttributes) {
      listener.onAudioAttributesChanged(eventTime, audioAttributes);
    }

    @Override
    public void onSkipSilenceEnabledChanged(EventTime eventTime, boolean skipSilenceEnabled) {
      listener.onSkipSilenceEnabledChanged(eventTime, skipSilenceEnabled);
    }

    @Override
    public void onAudioSinkError(EventTime eventTime, Exception audioSinkError) {
      listener.onAudioSinkError(eventTime, audioSinkError);
    }

    @Override
    public void onAudioCodecError(EventTime eventTime, Exception audioCodecError) {
      listener.onAudioCodecError(eventTime, audioCodecError);
    }

    @Override
    public void onAudioTrackInitialized(
        EventTime eventTime, AudioSink.AudioTrackConfig audioTrackConfig) {
      listener.onAudioTrackInitialized(eventTime, audioTrackConfig);
    }

    @Override
    public void onAudioTrackReleased(
        EventTime eventTime, AudioSink.AudioTrackConfig audioTrackConfig) {
      listener.onAudioTrackReleased(eventTime, audioTrackConfig);
    }

    @Override
    public void onVolumeChanged(EventTime eventTime, float volume) {
      listener.onVolumeChanged(eventTime, volume);
    }

    @Override
    public void onDeviceInfoChanged(EventTime eventTime, DeviceInfo deviceInfo) {
      listener.onDeviceInfoChanged(eventTime, deviceInfo);
    }

    @Override
    public void onDeviceVolumeChanged(EventTime eventTime, int volume, boolean muted) {
      listener.onDeviceVolumeChanged(eventTime, volume, muted);
    }

    @Override
    public void onVideoEnabled(EventTime eventTime, DecoderCounters decoderCounters) {
      listener.onVideoEnabled(eventTime, decoderCounters);
    }

    @Override
    public void onVideoDecoderInitialized(
        EventTime eventTime,
        String decoderName,
        long initializedTimestampMs,
        long initializationDurationMs) {
      listener.onVideoDecoderInitialized(
          eventTime, decoderName, initializedTimestampMs, initializationDurationMs);
    }

    @SuppressWarnings("deprecation") // Forwarding to deprecated method.
    @Deprecated
    @Override
    public void onVideoDecoderInitialized(
        EventTime eventTime, String decoderName, long initializationDurationMs) {
      listener.onVideoDecoderInitialized(eventTime, decoderName, initializationDurationMs);
    }

    @Override
    public void onVideoInputFormatChanged(
        EventTime eventTime,
        Format format,
        @Nullable DecoderReuseEvaluation decoderReuseEvaluation) {
      listener.onVideoInputFormatChanged(eventTime, format, decoderReuseEvaluation);
    }

    @Override
    public void onDroppedVideoFrames(EventTime eventTime, int droppedFrames, long elapsedMs) {
      listener.onDroppedVideoFrames(eventTime, droppedFrames, elapsedMs);
    }

    @Override
    public void onVideoDecoderReleased(EventTime eventTime, String decoderName) {
      listener.onVideoDecoderReleased(eventTime, decoderName);
    }

    @Override
    public void onVideoDisabled(EventTime eventTime, DecoderCounters decoderCounters) {
      listener.onVideoDisabled(eventTime, decoderCounters);
    }

    @Override
    public void onVideoFrameProcessingOffset(
        EventTime eventTime, long totalProcessingOffsetUs, int frameCount) {
      listener.onVideoFrameProcessingOffset(eventTime, totalProcessingOffsetUs, frameCount);
    }

    @Override
    public void onVideoCodecError(EventTime eventTime, Exception videoCodecError) {
      listener.onVideoCodecError(eventTime, videoCodecError);
    }

    @Override
    public void onRenderedFirstFrame(EventTime eventTime, Object output, long renderTimeMs) {
      listener.onRenderedFirstFrame(eventTime, output, renderTimeMs);
    }

    @Override
    public void onVideoSizeChanged(EventTime eventTime, VideoSize videoSize) {
      listener.onVideoSizeChanged(eventTime, videoSize);
    }

    @SuppressWarnings("deprecation") // Forwarding to deprecated method.
    @Deprecated
    @Override
    public void onVideoSizeChanged(
        EventTime eventTime,
        int width,
        int height,
        int unappliedRotationDegrees,
        float pixelWidthHeightRatio) {
      listener.onVideoSizeChanged(
          eventTime, width, height, unappliedRotationDegrees, pixelWidthHeightRatio);
    }

    @Override
    public void onSurfaceSizeChanged(EventTime eventTime, int width, int height) {
      listener.onSurfaceSizeChanged(eventTime, width, height);
    }

    @SuppressWarnings("deprecation") // Forwarding to deprecated method.
    @Deprecated
    @Override
    public void onDrmSessionAcquired(EventTime eventTime) {
      listener.onDrmSessionAcquired(eventTime);
    }

    @Override
    public void onDrmSessionAcquired(EventTime eventTime, @DrmSession.State int state) {
      listener.onDrmSessionAcquired(eventTime, state);
    }

    @SuppressWarnings("deprecation") // Forwarding to deprecated method.
    @Deprecated
    @Override
    public void onDrmKeysLoaded(EventTime eventTime) {
      listener.onDrmKeysLoaded(eventTime);
    }

    @Override
    public void onDrmKeysLoaded(EventTime eventTime, KeyRequestInfo keyRequestInfo) {
      listener.onDrmKeysLoaded(eventTime, keyRequestInfo);
    }

    @Override
    public void onDrmSessionManagerError(EventTime eventTime, Exception error) {
      listener.onDrmSessionManagerError(eventTime, error);
    }

    @Override
    public void onDrmKeysRestored(EventTime eventTime) {
      listener.onDrmKeysRestored(eventTime);
    }

    @Override
    public void onDrmKeysRemoved(EventTime eventTime) {
      listener.onDrmKeysRemoved(eventTime);
    }

    @Override
    public void onDrmSessionReleased(EventTime eventTime) {
      listener.onDrmSessionReleased(eventTime);
    }

    @Override
    public void onRendererReadyChanged(
        EventTime eventTime,
        int rendererIndex,
        @C.TrackType int rendererTrackType,
        boolean isRendererReady) {
      listener.onRendererReadyChanged(eventTime, rendererIndex, rendererTrackType, isRendererReady);
    }

    @Override
    public void onDroppedSeeksWhileScrubbing(EventTime eventTime, int droppedSeeks) {
      listener.onDroppedSeeksWhileScrubbing(eventTime, droppedSeeks);
    }

    @Override
    public void onPlayerReleased(EventTime eventTime) {
      listener.onPlayerReleased(eventTime);
    }

    @Override
    public void onEvents(Player player, Events events) {
      listener.onEvents(forwardingExoPlayer, events);
    }
  }

  private static final class ForwardingAnalyticsCollector implements AnalyticsCollector {

    private final ForwardingExoPlayer forwardingExoPlayer;

    private ForwardingAnalyticsCollector(ForwardingExoPlayer forwardingExoPlayer) {
      this.forwardingExoPlayer = forwardingExoPlayer;
    }

    @Override
    public void addListener(AnalyticsListener listener) {
      checkNotNull(listener);
      synchronized (forwardingExoPlayer.analyticsListeners) {
        @Nullable ForwardingAnalyticsListener forwardingListener =
            forwardingExoPlayer.analyticsListeners.get(listener);
        if (forwardingListener == null) {
          forwardingListener = new ForwardingAnalyticsListener(forwardingExoPlayer, listener);
        }
        forwardingExoPlayer.player.getAnalyticsCollector().addListener(forwardingListener);
        forwardingExoPlayer.analyticsListeners.put(listener, forwardingListener);
      }
    }

    @Override
    public void removeListener(AnalyticsListener listener) {
      checkNotNull(listener);
      synchronized (forwardingExoPlayer.analyticsListeners) {
        @Nullable AnalyticsListener forwardingListener =
            forwardingExoPlayer.analyticsListeners.remove(listener);
        forwardingExoPlayer
            .player
            .getAnalyticsCollector()
            .removeListener(forwardingListener != null ? forwardingListener : listener);
      }
    }

    @Override
    public void setPlayer(Player player, Looper looper) {
      forwardingExoPlayer.player.getAnalyticsCollector().setPlayer(player, looper);
    }

    @Override
    public void release() {
      forwardingExoPlayer.player.getAnalyticsCollector().release();
    }

    @Override
    public void updateMediaPeriodQueueInfo(
        List<MediaPeriodId> queue, @Nullable MediaPeriodId readingPeriod) {
      forwardingExoPlayer
          .player
          .getAnalyticsCollector()
          .updateMediaPeriodQueueInfo(queue, readingPeriod);
    }

    @Override
    public void notifySeekStarted() {
      forwardingExoPlayer.player.getAnalyticsCollector().notifySeekStarted();
    }

    @Override
    public void onRendererReadyChanged(
        int rendererIndex, @C.TrackType int rendererTrackType, boolean isRendererReady) {
      forwardingExoPlayer
          .player
          .getAnalyticsCollector()
          .onRendererReadyChanged(rendererIndex, rendererTrackType, isRendererReady);
    }

    @Override
    public void onAudioEnabled(DecoderCounters counters) {
      forwardingExoPlayer.player.getAnalyticsCollector().onAudioEnabled(counters);
    }

    @Override
    public void onAudioDecoderInitialized(
        String decoderName, long initializedTimestampMs, long initializationDurationMs) {
      forwardingExoPlayer
          .player
          .getAnalyticsCollector()
          .onAudioDecoderInitialized(decoderName, initializedTimestampMs, initializationDurationMs);
    }

    @Override
    public void onAudioInputFormatChanged(
        Format format, @Nullable DecoderReuseEvaluation decoderReuseEvaluation) {
      forwardingExoPlayer
          .player
          .getAnalyticsCollector()
          .onAudioInputFormatChanged(format, decoderReuseEvaluation);
    }

    @Override
    public void onAudioPositionAdvancing(long playoutStartSystemTimeMs) {
      forwardingExoPlayer
          .player
          .getAnalyticsCollector()
          .onAudioPositionAdvancing(playoutStartSystemTimeMs);
    }

    @Override
    public void onAudioUnderrun(int bufferSize, long bufferSizeMs, long elapsedSinceLastFeedMs) {
      forwardingExoPlayer
          .player
          .getAnalyticsCollector()
          .onAudioUnderrun(bufferSize, bufferSizeMs, elapsedSinceLastFeedMs);
    }

    @Override
    public void onAudioDecoderReleased(String decoderName) {
      forwardingExoPlayer.player.getAnalyticsCollector().onAudioDecoderReleased(decoderName);
    }

    @Override
    public void onAudioDisabled(DecoderCounters counters) {
      forwardingExoPlayer.player.getAnalyticsCollector().onAudioDisabled(counters);
    }

    @Override
    public void onAudioSinkError(Exception audioSinkError) {
      forwardingExoPlayer.player.getAnalyticsCollector().onAudioSinkError(audioSinkError);
    }

    @Override
    public void onAudioCodecError(Exception audioCodecError) {
      forwardingExoPlayer.player.getAnalyticsCollector().onAudioCodecError(audioCodecError);
    }

    @Override
    public void onAudioTrackInitialized(AudioSink.AudioTrackConfig audioTrackConfig) {
      forwardingExoPlayer.player.getAnalyticsCollector().onAudioTrackInitialized(audioTrackConfig);
    }

    @Override
    public void onAudioTrackReleased(AudioSink.AudioTrackConfig audioTrackConfig) {
      forwardingExoPlayer.player.getAnalyticsCollector().onAudioTrackReleased(audioTrackConfig);
    }

    @Override
    public void onVideoEnabled(DecoderCounters counters) {
      forwardingExoPlayer.player.getAnalyticsCollector().onVideoEnabled(counters);
    }

    @Override
    public void onVideoDecoderInitialized(
        String decoderName, long initializedTimestampMs, long initializationDurationMs) {
      forwardingExoPlayer
          .player
          .getAnalyticsCollector()
          .onVideoDecoderInitialized(decoderName, initializedTimestampMs, initializationDurationMs);
    }

    @Override
    public void onVideoInputFormatChanged(
        Format format, @Nullable DecoderReuseEvaluation decoderReuseEvaluation) {
      forwardingExoPlayer
          .player
          .getAnalyticsCollector()
          .onVideoInputFormatChanged(format, decoderReuseEvaluation);
    }

    @Override
    public void onDroppedFrames(int count, long elapsedMs) {
      forwardingExoPlayer.player.getAnalyticsCollector().onDroppedFrames(count, elapsedMs);
    }

    @Override
    public void onVideoDecoderReleased(String decoderName) {
      forwardingExoPlayer.player.getAnalyticsCollector().onVideoDecoderReleased(decoderName);
    }

    @Override
    public void onVideoDisabled(DecoderCounters counters) {
      forwardingExoPlayer.player.getAnalyticsCollector().onVideoDisabled(counters);
    }

    @Override
    public void onRenderedFirstFrame(Object output, long renderTimeMs) {
      forwardingExoPlayer.player.getAnalyticsCollector().onRenderedFirstFrame(output, renderTimeMs);
    }

    @Override
    public void onRenderedFirstFrame() {
      forwardingExoPlayer.player.getAnalyticsCollector().onRenderedFirstFrame();
    }

    @Override
    public void onVideoFrameProcessingOffset(long totalProcessingOffsetUs, int frameCount) {
      forwardingExoPlayer
          .player
          .getAnalyticsCollector()
          .onVideoFrameProcessingOffset(totalProcessingOffsetUs, frameCount);
    }

    @Override
    public void onVideoCodecError(Exception videoCodecError) {
      forwardingExoPlayer.player.getAnalyticsCollector().onVideoCodecError(videoCodecError);
    }

    @Override
    public void onDroppedSeeksWhileScrubbing(int droppedSeekCount) {
      forwardingExoPlayer
          .player
          .getAnalyticsCollector()
          .onDroppedSeeksWhileScrubbing(droppedSeekCount);
    }

    @Override
    public void onEvents(Player player, Player.Events events) {
      forwardingExoPlayer.player.getAnalyticsCollector().onEvents(player, events);
    }

    @Override
    public void onTimelineChanged(Timeline timeline, @Player.TimelineChangeReason int reason) {
      forwardingExoPlayer.player.getAnalyticsCollector().onTimelineChanged(timeline, reason);
    }

    @Override
    public void onMediaItemTransition(
        @Nullable MediaItem mediaItem, @Player.MediaItemTransitionReason int reason) {
      forwardingExoPlayer.player.getAnalyticsCollector().onMediaItemTransition(mediaItem, reason);
    }

    @Override
    public void onTracksChanged(Tracks tracks) {
      forwardingExoPlayer.player.getAnalyticsCollector().onTracksChanged(tracks);
    }

    @Override
    public void onMediaMetadataChanged(MediaMetadata mediaMetadata) {
      forwardingExoPlayer.player.getAnalyticsCollector().onMediaMetadataChanged(mediaMetadata);
    }

    @Override
    public void onPlaylistMetadataChanged(MediaMetadata mediaMetadata) {
      forwardingExoPlayer.player.getAnalyticsCollector().onPlaylistMetadataChanged(mediaMetadata);
    }

    @Override
    public void onIsLoadingChanged(boolean isLoading) {
      forwardingExoPlayer.player.getAnalyticsCollector().onIsLoadingChanged(isLoading);
    }

    @SuppressWarnings("deprecation") // Forwarding to deprecated method.
    @Deprecated
    @Override
    public void onLoadingChanged(boolean isLoading) {
      forwardingExoPlayer.player.getAnalyticsCollector().onLoadingChanged(isLoading);
    }

    @Override
    public void onAvailableCommandsChanged(Player.Commands availableCommands) {
      forwardingExoPlayer
          .player
          .getAnalyticsCollector()
          .onAvailableCommandsChanged(availableCommands);
    }

    @Override
    public void onTrackSelectionParametersChanged(TrackSelectionParameters parameters) {
      forwardingExoPlayer
          .player
          .getAnalyticsCollector()
          .onTrackSelectionParametersChanged(parameters);
    }

    @SuppressWarnings("deprecation") // Forwarding to deprecated method.
    @Deprecated
    @Override
    public void onPlayerStateChanged(boolean playWhenReady, @Player.State int playbackState) {
      forwardingExoPlayer
          .player
          .getAnalyticsCollector()
          .onPlayerStateChanged(playWhenReady, playbackState);
    }

    @Override
    public void onPlaybackStateChanged(@Player.State int playbackState) {
      forwardingExoPlayer.player.getAnalyticsCollector().onPlaybackStateChanged(playbackState);
    }

    @Override
    public void onPlayWhenReadyChanged(
        boolean playWhenReady, @Player.PlayWhenReadyChangeReason int reason) {
      forwardingExoPlayer
          .player
          .getAnalyticsCollector()
          .onPlayWhenReadyChanged(playWhenReady, reason);
    }

    @Override
    public void onPlaybackSuppressionReasonChanged(
        @PlaybackSuppressionReason int playbackSuppressionReason) {
      forwardingExoPlayer
          .player
          .getAnalyticsCollector()
          .onPlaybackSuppressionReasonChanged(playbackSuppressionReason);
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
      forwardingExoPlayer.player.getAnalyticsCollector().onIsPlayingChanged(isPlaying);
    }

    @Override
    public void onRepeatModeChanged(@Player.RepeatMode int repeatMode) {
      forwardingExoPlayer.player.getAnalyticsCollector().onRepeatModeChanged(repeatMode);
    }

    @Override
    public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {
      forwardingExoPlayer
          .player
          .getAnalyticsCollector()
          .onShuffleModeEnabledChanged(shuffleModeEnabled);
    }

    @Override
    public void onPlayerError(PlaybackException error) {
      forwardingExoPlayer.player.getAnalyticsCollector().onPlayerError(error);
    }

    @Override
    public void onPlayerErrorChanged(@Nullable PlaybackException error) {
      forwardingExoPlayer.player.getAnalyticsCollector().onPlayerErrorChanged(error);
    }

    @SuppressWarnings("deprecation") // Forwarding to deprecated method.
    @Deprecated
    @Override
    public void onPositionDiscontinuity(@Player.DiscontinuityReason int reason) {
      forwardingExoPlayer.player.getAnalyticsCollector().onPositionDiscontinuity(reason);
    }

    @Override
    public void onPositionDiscontinuity(
        Player.PositionInfo oldPosition,
        Player.PositionInfo newPosition,
        @Player.DiscontinuityReason int reason) {
      forwardingExoPlayer
          .player
          .getAnalyticsCollector()
          .onPositionDiscontinuity(oldPosition, newPosition, reason);
    }

    @Override
    public void onPlaybackParametersChanged(PlaybackParameters playbackParameters) {
      forwardingExoPlayer
          .player
          .getAnalyticsCollector()
          .onPlaybackParametersChanged(playbackParameters);
    }

    @Override
    public void onSeekBackIncrementChanged(long seekBackIncrementMs) {
      forwardingExoPlayer
          .player
          .getAnalyticsCollector()
          .onSeekBackIncrementChanged(seekBackIncrementMs);
    }

    @Override
    public void onSeekForwardIncrementChanged(long seekForwardIncrementMs) {
      forwardingExoPlayer
          .player
          .getAnalyticsCollector()
          .onSeekForwardIncrementChanged(seekForwardIncrementMs);
    }

    @Override
    public void onMaxSeekToPreviousPositionChanged(long maxSeekToPreviousPositionMs) {
      forwardingExoPlayer
          .player
          .getAnalyticsCollector()
          .onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs);
    }

    @Override
    public void onVideoSizeChanged(VideoSize videoSize) {
      forwardingExoPlayer.player.getAnalyticsCollector().onVideoSizeChanged(videoSize);
    }

    @Override
    public void onSurfaceSizeChanged(int width, int height) {
      forwardingExoPlayer.player.getAnalyticsCollector().onSurfaceSizeChanged(width, height);
    }

    @Override
    public void onAudioSessionIdChanged(int audioSessionId) {
      forwardingExoPlayer.player.getAnalyticsCollector().onAudioSessionIdChanged(audioSessionId);
    }

    @Override
    public void onAudioAttributesChanged(AudioAttributes audioAttributes) {
      forwardingExoPlayer.player.getAnalyticsCollector().onAudioAttributesChanged(audioAttributes);
    }

    @Override
    public void onVolumeChanged(float volume) {
      forwardingExoPlayer.player.getAnalyticsCollector().onVolumeChanged(volume);
    }

    @Override
    public void onSkipSilenceEnabledChanged(boolean skipSilenceEnabled) {
      forwardingExoPlayer
          .player
          .getAnalyticsCollector()
          .onSkipSilenceEnabledChanged(skipSilenceEnabled);
    }

    @SuppressWarnings("deprecation") // Forwarding to deprecated method.
    @Deprecated
    @Override
    public void onCues(List<Cue> cues) {
      forwardingExoPlayer.player.getAnalyticsCollector().onCues(cues);
    }

    @Override
    public void onCues(CueGroup cueGroup) {
      forwardingExoPlayer.player.getAnalyticsCollector().onCues(cueGroup);
    }

    @Override
    public void onMetadata(Metadata metadata) {
      forwardingExoPlayer.player.getAnalyticsCollector().onMetadata(metadata);
    }

    @Override
    public void onDeviceInfoChanged(DeviceInfo deviceInfo) {
      forwardingExoPlayer.player.getAnalyticsCollector().onDeviceInfoChanged(deviceInfo);
    }

    @Override
    public void onDeviceVolumeChanged(int volume, boolean muted) {
      forwardingExoPlayer.player.getAnalyticsCollector().onDeviceVolumeChanged(volume, muted);
    }

    @Override
    public void onLoadStarted(
        int windowIndex,
        @Nullable MediaPeriodId mediaPeriodId,
        LoadEventInfo loadEventInfo,
        MediaLoadData mediaLoadData,
        int retryCount) {
      forwardingExoPlayer
          .player
          .getAnalyticsCollector()
          .onLoadStarted(windowIndex, mediaPeriodId, loadEventInfo, mediaLoadData, retryCount);
    }

    @Override
    public void onLoadCompleted(
        int windowIndex,
        @Nullable MediaPeriodId mediaPeriodId,
        LoadEventInfo loadEventInfo,
        MediaLoadData mediaLoadData) {
      forwardingExoPlayer
          .player
          .getAnalyticsCollector()
          .onLoadCompleted(windowIndex, mediaPeriodId, loadEventInfo, mediaLoadData);
    }

    @Override
    public void onLoadCanceled(
        int windowIndex,
        @Nullable MediaPeriodId mediaPeriodId,
        LoadEventInfo loadEventInfo,
        MediaLoadData mediaLoadData) {
      forwardingExoPlayer
          .player
          .getAnalyticsCollector()
          .onLoadCanceled(windowIndex, mediaPeriodId, loadEventInfo, mediaLoadData);
    }

    @Override
    public void onLoadError(
        int windowIndex,
        @Nullable MediaPeriodId mediaPeriodId,
        LoadEventInfo loadEventInfo,
        MediaLoadData mediaLoadData,
        IOException error,
        boolean wasCanceled) {
      forwardingExoPlayer
          .player
          .getAnalyticsCollector()
          .onLoadError(
              windowIndex, mediaPeriodId, loadEventInfo, mediaLoadData, error, wasCanceled);
    }

    @Override
    public void onUpstreamDiscarded(
        int windowIndex, MediaPeriodId mediaPeriodId, MediaLoadData mediaLoadData) {
      forwardingExoPlayer
          .player
          .getAnalyticsCollector()
          .onUpstreamDiscarded(windowIndex, mediaPeriodId, mediaLoadData);
    }

    @Override
    public void onDownstreamFormatChanged(
        int windowIndex, @Nullable MediaPeriodId mediaPeriodId, MediaLoadData mediaLoadData) {
      forwardingExoPlayer
          .player
          .getAnalyticsCollector()
          .onDownstreamFormatChanged(windowIndex, mediaPeriodId, mediaLoadData);
    }

    @Override
    public void onBandwidthSample(int elapsedMs, long bytesTransferred, long bitrateEstimate) {
      forwardingExoPlayer
          .player
          .getAnalyticsCollector()
          .onBandwidthSample(elapsedMs, bytesTransferred, bitrateEstimate);
    }

    @Override
    public void onDrmSessionAcquired(
        int windowIndex, @Nullable MediaPeriodId mediaPeriodId, @DrmSession.State int state) {
      forwardingExoPlayer
          .player
          .getAnalyticsCollector()
          .onDrmSessionAcquired(windowIndex, mediaPeriodId, state);
    }

    @SuppressWarnings("deprecation") // Forwarding to deprecated method.
    @Deprecated
    @Override
    public void onDrmKeysLoaded(int windowIndex, @Nullable MediaPeriodId mediaPeriodId) {
      forwardingExoPlayer
          .player
          .getAnalyticsCollector()
          .onDrmKeysLoaded(windowIndex, mediaPeriodId);
    }

    @Override
    public void onDrmKeysLoaded(
        int windowIndex, @Nullable MediaPeriodId mediaPeriodId, KeyRequestInfo keyRequestInfo) {
      forwardingExoPlayer
          .player
          .getAnalyticsCollector()
          .onDrmKeysLoaded(windowIndex, mediaPeriodId, keyRequestInfo);
    }

    @Override
    public void onDrmSessionManagerError(
        int windowIndex, @Nullable MediaPeriodId mediaPeriodId, Exception error) {
      forwardingExoPlayer
          .player
          .getAnalyticsCollector()
          .onDrmSessionManagerError(windowIndex, mediaPeriodId, error);
    }

    @Override
    public void onDrmKeysRestored(int windowIndex, @Nullable MediaPeriodId mediaPeriodId) {
      forwardingExoPlayer
          .player
          .getAnalyticsCollector()
          .onDrmKeysRestored(windowIndex, mediaPeriodId);
    }

    @Override
    public void onDrmKeysRemoved(int windowIndex, @Nullable MediaPeriodId mediaPeriodId) {
      forwardingExoPlayer
          .player
          .getAnalyticsCollector()
          .onDrmKeysRemoved(windowIndex, mediaPeriodId);
    }

    @Override
    public void onDrmSessionReleased(int windowIndex, @Nullable MediaPeriodId mediaPeriodId) {
      forwardingExoPlayer
          .player
          .getAnalyticsCollector()
          .onDrmSessionReleased(windowIndex, mediaPeriodId);
    }
  }
}
