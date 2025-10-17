/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static androidx.media3.common.util.Util.msToUs;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.max;
import static java.lang.Math.min;

import android.content.ContentResolver;
import android.text.TextUtils;
import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.NullableType;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSchemeDataSource;
import androidx.media3.datasource.RawResourceDataSource;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.upstream.Allocation;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import androidx.media3.exoplayer.upstream.PlayerIdAwareAllocator;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The default {@link LoadControl} implementation. */
@UnstableApi
public class DefaultLoadControl implements LoadControl {

  /**
   * The default minimum duration of media that the player will attempt to ensure is buffered at all
   * times, in milliseconds.
   */
  public static final int DEFAULT_MIN_BUFFER_MS = 50_000;

  /**
   * The default minimum duration of media that the player will attempt to ensure is buffered at all
   * times for local on-device playback, in milliseconds.
   */
  // Load at least as much as needed for DEFAULT_BUFFER_FOR_PLAYBACK_FOR_LOCAL_PLAYBACK_MS
  public static final int DEFAULT_MIN_BUFFER_FOR_LOCAL_PLAYBACK_MS = 1000;

  /**
   * The default maximum duration of media that the player will attempt to buffer, in milliseconds.
   */
  public static final int DEFAULT_MAX_BUFFER_MS = 50_000;

  /**
   * The default maximum duration of media that the player will attempt to buffer for local
   * on-device playback, in milliseconds.
   */
  // Upper limit for low-bitrate streams that is also high enough to cover most reasonable timestamp
  // gaps in the media as well.
  public static final int DEFAULT_MAX_BUFFER_FOR_LOCAL_PLAYBACK_MS = 50_000;

  /**
   * The default duration of media that must be buffered for playback to start or resume following a
   * user action such as a seek, in milliseconds.
   */
  public static final int DEFAULT_BUFFER_FOR_PLAYBACK_MS = 1000;

  /**
   * The default duration of media that must be buffered for playback to start or resume following a
   * user action such as a seek for local on-device playback, in milliseconds.
   */
  public static final int DEFAULT_BUFFER_FOR_PLAYBACK_FOR_LOCAL_PLAYBACK_MS = 1000;

  /**
   * The default duration of media that must be buffered for playback to resume after a rebuffer, in
   * milliseconds. A rebuffer is defined to be caused by buffer depletion rather than a user action.
   */
  public static final int DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 2000;

  /**
   * The default duration of media that must be buffered for playback to resume after a rebuffer for
   * local on-device playback, in milliseconds. A rebuffer is defined to be caused by buffer
   * depletion rather than a user action.
   */
  public static final int DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_FOR_LOCAL_PLAYBACK_MS = 1000;

  /**
   * The default target buffer size in bytes. The value ({@link C#LENGTH_UNSET}) means that the load
   * control will calculate the target buffer size based on the selected tracks.
   */
  public static final int DEFAULT_TARGET_BUFFER_BYTES = C.LENGTH_UNSET;

  /** The default prioritization of buffer time constraints over size constraints. */
  public static final boolean DEFAULT_PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS = false;

  /**
   * The default prioritization of buffer time constraints over size constraints for local on-device
   * playback.
   */
  // Ensure DEFAULT_MIN_BUFFER_FOR_LOCAL_PLAYBACK_MS even if target buffer size in bytes is reached
  // for extremely high-bitrate content.
  public static final boolean DEFAULT_PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS_FOR_LOCAL_PLAYBACK =
      true;

  /** The default back buffer duration in milliseconds. */
  public static final int DEFAULT_BACK_BUFFER_DURATION_MS = 0;

  /** The default for whether the back buffer is retained from the previous keyframe. */
  public static final boolean DEFAULT_RETAIN_BACK_BUFFER_FROM_KEYFRAME = false;

  /** A default size in bytes for a video buffer. */
  public static final int DEFAULT_VIDEO_BUFFER_SIZE = 2000 * C.DEFAULT_BUFFER_SEGMENT_SIZE;

  /** A default size in bytes for a video buffer for local playback. */
  // Sufficient size for 1 second at even 150 Mbit/s.
  public static final int DEFAULT_VIDEO_BUFFER_SIZE_FOR_LOCAL_PLAYBACK =
      300 * C.DEFAULT_BUFFER_SEGMENT_SIZE;

  /** A default size in bytes for an audio buffer. */
  public static final int DEFAULT_AUDIO_BUFFER_SIZE = 200 * C.DEFAULT_BUFFER_SEGMENT_SIZE;

  /** A default size in bytes for a text buffer. */
  public static final int DEFAULT_TEXT_BUFFER_SIZE = 2 * C.DEFAULT_BUFFER_SEGMENT_SIZE;

  /** A default size in bytes for a metadata buffer. */
  public static final int DEFAULT_METADATA_BUFFER_SIZE = 2 * C.DEFAULT_BUFFER_SEGMENT_SIZE;

  /** A default size in bytes for a camera motion buffer. */
  public static final int DEFAULT_CAMERA_MOTION_BUFFER_SIZE = 2 * C.DEFAULT_BUFFER_SEGMENT_SIZE;

  /** A default size in bytes for an image buffer. */
  public static final int DEFAULT_IMAGE_BUFFER_SIZE = 400 * C.DEFAULT_BUFFER_SEGMENT_SIZE;

  /** A default size in bytes for a muxed buffer (e.g. containing video, audio and text). */
  public static final int DEFAULT_MUXED_BUFFER_SIZE =
      DEFAULT_VIDEO_BUFFER_SIZE + DEFAULT_AUDIO_BUFFER_SIZE + DEFAULT_TEXT_BUFFER_SIZE;

  /**
   * The buffer size in bytes that will be used as a minimum target buffer in all cases. This is
   * also the default target buffer before tracks are selected.
   */
  public static final int DEFAULT_MIN_BUFFER_SIZE = 200 * C.DEFAULT_BUFFER_SEGMENT_SIZE;

  /**
   * The default target buffer size in bytes that will be used for preloading media outside of
   * player, see {@link PlayerId#PRELOAD}.
   */
  public static final int DEFAULT_TARGET_BUFFER_BYTES_FOR_PRELOAD =
      DEFAULT_VIDEO_BUFFER_SIZE + DEFAULT_AUDIO_BUFFER_SIZE;

  /** List of URL schemes that are considered local on-device playback. */
  @SuppressWarnings("deprecation") // Accepting deprecated RawResourceDataSource.RAW_RESOURCE_SCHEME
  public static final ImmutableList<String> LOCAL_PLAYBACK_SCHEMES =
      ImmutableList.of(
          ContentResolver.SCHEME_FILE,
          ContentResolver.SCHEME_CONTENT,
          DataSchemeDataSource.SCHEME_DATA,
          ContentResolver.SCHEME_ANDROID_RESOURCE,
          RawResourceDataSource.RAW_RESOURCE_SCHEME,
          "asset");

  /** Builder for {@link DefaultLoadControl}. */
  public static final class Builder {

    private final HashMap<String, Integer> playerTargetBufferBytes;

    @Nullable private DefaultAllocator allocator;
    private int minBufferMs;
    private int minBufferForLocalPlaybackMs;
    private int maxBufferMs;
    private int maxBufferForLocalPlaybackMs;
    private int bufferForPlaybackMs;
    private int bufferForPlaybackForLocalPlaybackMs;
    private int bufferForPlaybackAfterRebufferMs;
    private int bufferForPlaybackAfterRebufferForLocalPlaybackMs;
    private int targetBufferBytes;
    private boolean prioritizeTimeOverSizeThresholds;
    private boolean prioritizeTimeOverSizeThresholdsForLocalPlayback;
    private int backBufferDurationMs;
    private boolean retainBackBufferFromKeyframe;
    private boolean buildCalled;

    // For backwards-compatibility, calling only one of the generic setBufferDurationsMs or
    // setPrioritizeTimeOverSizeThresholds methods should not use local playback specific defaults
    // for the other setter to avoid unintended side effects of changing one default but keeping the
    // manual override for the other.
    @Nullable private Boolean onlyGenericConfigurationMethodsCalled;

    /** Constructs a new instance. */
    public Builder() {
      playerTargetBufferBytes = new HashMap<>();
      playerTargetBufferBytes.put(PlayerId.PRELOAD.name, DEFAULT_TARGET_BUFFER_BYTES_FOR_PRELOAD);
      minBufferMs = DEFAULT_MIN_BUFFER_MS;
      minBufferForLocalPlaybackMs = DEFAULT_MIN_BUFFER_FOR_LOCAL_PLAYBACK_MS;
      maxBufferMs = DEFAULT_MAX_BUFFER_MS;
      maxBufferForLocalPlaybackMs = DEFAULT_MAX_BUFFER_FOR_LOCAL_PLAYBACK_MS;
      bufferForPlaybackMs = DEFAULT_BUFFER_FOR_PLAYBACK_MS;
      bufferForPlaybackForLocalPlaybackMs = DEFAULT_BUFFER_FOR_PLAYBACK_FOR_LOCAL_PLAYBACK_MS;
      bufferForPlaybackAfterRebufferMs = DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS;
      bufferForPlaybackAfterRebufferForLocalPlaybackMs =
          DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_FOR_LOCAL_PLAYBACK_MS;
      targetBufferBytes = DEFAULT_TARGET_BUFFER_BYTES;
      prioritizeTimeOverSizeThresholds = DEFAULT_PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS;
      prioritizeTimeOverSizeThresholdsForLocalPlayback =
          DEFAULT_PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS_FOR_LOCAL_PLAYBACK;
      backBufferDurationMs = DEFAULT_BACK_BUFFER_DURATION_MS;
      retainBackBufferFromKeyframe = DEFAULT_RETAIN_BACK_BUFFER_FROM_KEYFRAME;
    }

    /**
     * Sets the {@link DefaultAllocator} used by the loader.
     *
     * @param allocator The {@link DefaultAllocator}.
     * @return This builder, for convenience.
     * @throws IllegalStateException If {@link #build()} has already been called.
     */
    @CanIgnoreReturnValue
    public Builder setAllocator(DefaultAllocator allocator) {
      checkState(!buildCalled);
      this.allocator = allocator;
      return this;
    }

    /**
     * Sets the buffer duration parameters for both streaming and local on-device playback.
     *
     * <p>Use {@link #setBufferDurationsMsForStreaming} or {@link
     * #setBufferDurationsMsForLocalPlayback} to set them separately for each use case.
     *
     * @param minBufferMs The minimum duration of media that the player will attempt to ensure is
     *     buffered at all times, in milliseconds.
     * @param maxBufferMs The maximum duration of media that the player will attempt to buffer, in
     *     milliseconds.
     * @param bufferForPlaybackMs The duration of media that must be buffered for playback to start
     *     or resume following a user action such as a seek, in milliseconds.
     * @param bufferForPlaybackAfterRebufferMs The default duration of media that must be buffered
     *     for playback to resume after a rebuffer, in milliseconds. A rebuffer is defined to be
     *     caused by buffer depletion rather than a user action.
     * @return This builder, for convenience.
     * @throws IllegalStateException If {@link #build()} has already been called.
     */
    @CanIgnoreReturnValue
    public Builder setBufferDurationsMs(
        int minBufferMs,
        int maxBufferMs,
        int bufferForPlaybackMs,
        int bufferForPlaybackAfterRebufferMs) {
      checkState(!buildCalled);
      assertGreaterOrEqual(bufferForPlaybackMs, 0, "bufferForPlaybackMs", "0");
      assertGreaterOrEqual(
          bufferForPlaybackAfterRebufferMs, 0, "bufferForPlaybackAfterRebufferMs", "0");
      assertGreaterOrEqual(minBufferMs, bufferForPlaybackMs, "minBufferMs", "bufferForPlaybackMs");
      assertGreaterOrEqual(
          minBufferMs,
          bufferForPlaybackAfterRebufferMs,
          "minBufferMs",
          "bufferForPlaybackAfterRebufferMs");
      assertGreaterOrEqual(maxBufferMs, minBufferMs, "maxBufferMs", "minBufferMs");
      this.minBufferMs = minBufferMs;
      this.maxBufferMs = maxBufferMs;
      this.bufferForPlaybackMs = bufferForPlaybackMs;
      this.bufferForPlaybackAfterRebufferMs = bufferForPlaybackAfterRebufferMs;
      this.minBufferForLocalPlaybackMs = minBufferMs;
      this.maxBufferForLocalPlaybackMs = maxBufferMs;
      this.bufferForPlaybackForLocalPlaybackMs = bufferForPlaybackMs;
      this.bufferForPlaybackAfterRebufferForLocalPlaybackMs = bufferForPlaybackAfterRebufferMs;
      if (onlyGenericConfigurationMethodsCalled == null) {
        onlyGenericConfigurationMethodsCalled = true;
      }
      return this;
    }

    /**
     * Sets the buffer duration parameters for streaming playback.
     *
     * <p>Playbacks are considered to be streaming if the {@link MediaItem.LocalConfiguration#uri}
     * is none of the schemes in {@link #LOCAL_PLAYBACK_SCHEMES}.
     *
     * @param minBufferMs The minimum duration of media that the player will attempt to ensure is
     *     buffered at all times, in milliseconds.
     * @param maxBufferMs The maximum duration of media that the player will attempt to buffer, in
     *     milliseconds.
     * @param bufferForPlaybackMs The duration of media that must be buffered for playback to start
     *     or resume following a user action such as a seek, in milliseconds.
     * @param bufferForPlaybackAfterRebufferMs The default duration of media that must be buffered
     *     for playback to resume after a rebuffer, in milliseconds. A rebuffer is defined to be
     *     caused by buffer depletion rather than a user action.
     * @return This builder, for convenience.
     * @throws IllegalStateException If {@link #build()} has already been called.
     */
    @CanIgnoreReturnValue
    public Builder setBufferDurationsMsForStreaming(
        int minBufferMs,
        int maxBufferMs,
        int bufferForPlaybackMs,
        int bufferForPlaybackAfterRebufferMs) {
      checkState(!buildCalled);
      assertGreaterOrEqual(bufferForPlaybackMs, 0, "bufferForPlaybackMs", "0");
      assertGreaterOrEqual(
          bufferForPlaybackAfterRebufferMs, 0, "bufferForPlaybackAfterRebufferMs", "0");
      assertGreaterOrEqual(minBufferMs, bufferForPlaybackMs, "minBufferMs", "bufferForPlaybackMs");
      assertGreaterOrEqual(
          minBufferMs,
          bufferForPlaybackAfterRebufferMs,
          "minBufferMs",
          "bufferForPlaybackAfterRebufferMs");
      assertGreaterOrEqual(maxBufferMs, minBufferMs, "maxBufferMs", "minBufferMs");
      this.minBufferMs = minBufferMs;
      this.maxBufferMs = maxBufferMs;
      this.bufferForPlaybackMs = bufferForPlaybackMs;
      this.bufferForPlaybackAfterRebufferMs = bufferForPlaybackAfterRebufferMs;
      onlyGenericConfigurationMethodsCalled = false;
      return this;
    }

    /**
     * Sets the buffer duration parameters for local on-device playback.
     *
     * <p>Playbacks are considered to be on-device if the {@link MediaItem.LocalConfiguration#uri}
     * has an empty scheme or one of the schemes in {@link #LOCAL_PLAYBACK_SCHEMES}.
     *
     * @param minBufferMs The minimum duration of media that the player will attempt to ensure is
     *     buffered at all times, in milliseconds.
     * @param maxBufferMs The maximum duration of media that the player will attempt to buffer, in
     *     milliseconds.
     * @param bufferForPlaybackMs The duration of media that must be buffered for playback to start
     *     or resume following a user action such as a seek, in milliseconds.
     * @param bufferForPlaybackAfterRebufferMs The default duration of media that must be buffered
     *     for playback to resume after a rebuffer, in milliseconds. A rebuffer is defined to be
     *     caused by buffer depletion rather than a user action.
     * @return This builder, for convenience.
     * @throws IllegalStateException If {@link #build()} has already been called.
     */
    @CanIgnoreReturnValue
    public Builder setBufferDurationsMsForLocalPlayback(
        int minBufferMs,
        int maxBufferMs,
        int bufferForPlaybackMs,
        int bufferForPlaybackAfterRebufferMs) {
      checkState(!buildCalled);
      assertGreaterOrEqual(bufferForPlaybackMs, 0, "bufferForPlaybackMs", "0");
      assertGreaterOrEqual(
          bufferForPlaybackAfterRebufferMs, 0, "bufferForPlaybackAfterRebufferMs", "0");
      assertGreaterOrEqual(minBufferMs, bufferForPlaybackMs, "minBufferMs", "bufferForPlaybackMs");
      assertGreaterOrEqual(
          minBufferMs,
          bufferForPlaybackAfterRebufferMs,
          "minBufferMs",
          "bufferForPlaybackAfterRebufferMs");
      assertGreaterOrEqual(maxBufferMs, minBufferMs, "maxBufferMs", "minBufferMs");
      this.minBufferForLocalPlaybackMs = minBufferMs;
      this.maxBufferForLocalPlaybackMs = maxBufferMs;
      this.bufferForPlaybackForLocalPlaybackMs = bufferForPlaybackMs;
      this.bufferForPlaybackAfterRebufferForLocalPlaybackMs = bufferForPlaybackAfterRebufferMs;
      onlyGenericConfigurationMethodsCalled = false;
      return this;
    }

    /**
     * Sets the target buffer size in bytes for each player. If set to {@link C#LENGTH_UNSET}, the
     * target buffer size of a player will be calculated based on the selected tracks of the player.
     *
     * <p>This value will be ignored for the players with a {@code playerName} that has target
     * buffer size set via {@link #setPlayerTargetBufferBytes(String, int)}.
     *
     * @param targetBufferBytes The target buffer size in bytes.
     * @return This builder, for convenience.
     * @throws IllegalStateException If {@link #build()} has already been called.
     */
    @CanIgnoreReturnValue
    public Builder setTargetBufferBytes(int targetBufferBytes) {
      checkState(!buildCalled);
      this.targetBufferBytes = targetBufferBytes;
      return this;
    }

    /**
     * Sets the target buffer size in bytes for a player with the specified {@code playerName}. When
     * not set or set to {@link C#LENGTH_UNSET}, the target buffer size of a player will be the
     * value set via {@link #setTargetBufferBytes(int)} if it is not {@link C#LENGTH_UNSET},
     * otherwise it will be calculated based on the selected tracks of the player.
     *
     * <p>For the {@link PlayerId#PRELOAD} with {@code PlayerId.PRELOAD.name}, the default target
     * buffer bytes is {@link #DEFAULT_TARGET_BUFFER_BYTES_FOR_PRELOAD}.
     *
     * @param playerName The name of the player. The same name must be set to the player via {@link
     *     ExoPlayer.Builder#setName(String)} in order to be effective at the created {@link
     *     DefaultLoadControl}.
     * @param playerTargetBufferBytes The target buffer size in bytes for the specified player.
     * @return This builder, for convenience.
     * @throws IllegalStateException If {@link #build()} has already been called.
     */
    @CanIgnoreReturnValue
    public Builder setPlayerTargetBufferBytes(String playerName, int playerTargetBufferBytes) {
      checkState(!buildCalled);
      this.playerTargetBufferBytes.put(playerName, playerTargetBufferBytes);
      return this;
    }

    /**
     * Sets whether the load control prioritizes buffer time constraints over buffer size
     * constraints for streaming and local on-device playback.
     *
     * <p>Use {@link #setPrioritizeTimeOverSizeThresholdsForStreaming} or {@link
     * #setPrioritizeTimeOverSizeThresholdsForLocalPlayback} to set them separately for each use
     * case.
     *
     * @param prioritizeTimeOverSizeThresholds Whether the load control prioritizes buffer time
     *     constraints over buffer size constraints.
     * @return This builder, for convenience.
     * @throws IllegalStateException If {@link #build()} has already been called.
     */
    @CanIgnoreReturnValue
    public Builder setPrioritizeTimeOverSizeThresholds(boolean prioritizeTimeOverSizeThresholds) {
      checkState(!buildCalled);
      this.prioritizeTimeOverSizeThresholds = prioritizeTimeOverSizeThresholds;
      this.prioritizeTimeOverSizeThresholdsForLocalPlayback = prioritizeTimeOverSizeThresholds;
      if (onlyGenericConfigurationMethodsCalled == null) {
        onlyGenericConfigurationMethodsCalled = true;
      }
      return this;
    }

    /**
     * Sets whether the load control prioritizes buffer time constraints over buffer size
     * constraints for streaming playback.
     *
     * <p>Playbacks are considered to be streaming if the {@link MediaItem.LocalConfiguration#uri}
     * is none of the schemes in {@link #LOCAL_PLAYBACK_SCHEMES}.
     *
     * @param prioritizeTimeOverSizeThresholds Whether the load control prioritizes buffer time
     *     constraints over buffer size constraints.
     * @return This builder, for convenience.
     * @throws IllegalStateException If {@link #build()} has already been called.
     */
    @CanIgnoreReturnValue
    public Builder setPrioritizeTimeOverSizeThresholdsForStreaming(
        boolean prioritizeTimeOverSizeThresholds) {
      checkState(!buildCalled);
      this.prioritizeTimeOverSizeThresholds = prioritizeTimeOverSizeThresholds;
      onlyGenericConfigurationMethodsCalled = false;
      return this;
    }

    /**
     * Sets whether the load control prioritizes buffer time constraints over buffer size
     * constraints for local on-device playback.
     *
     * <p>Playbacks are considered to be on-device if the {@link MediaItem.LocalConfiguration#uri}
     * has an empty scheme or one of the schemes in {@link #LOCAL_PLAYBACK_SCHEMES}.
     *
     * @param prioritizeTimeOverSizeThresholds Whether the load control prioritizes buffer time
     *     constraints over buffer size constraints.
     * @return This builder, for convenience.
     * @throws IllegalStateException If {@link #build()} has already been called.
     */
    @CanIgnoreReturnValue
    public Builder setPrioritizeTimeOverSizeThresholdsForLocalPlayback(
        boolean prioritizeTimeOverSizeThresholds) {
      checkState(!buildCalled);
      this.prioritizeTimeOverSizeThresholdsForLocalPlayback = prioritizeTimeOverSizeThresholds;
      onlyGenericConfigurationMethodsCalled = false;
      return this;
    }

    /**
     * Sets the back buffer duration, and whether the back buffer is retained from the previous
     * keyframe.
     *
     * @param backBufferDurationMs The back buffer duration in milliseconds.
     * @param retainBackBufferFromKeyframe Whether the back buffer is retained from the previous
     *     keyframe.
     * @return This builder, for convenience.
     * @throws IllegalStateException If {@link #build()} has already been called.
     */
    @CanIgnoreReturnValue
    public Builder setBackBuffer(int backBufferDurationMs, boolean retainBackBufferFromKeyframe) {
      checkState(!buildCalled);
      assertGreaterOrEqual(backBufferDurationMs, 0, "backBufferDurationMs", "0");
      this.backBufferDurationMs = backBufferDurationMs;
      this.retainBackBufferFromKeyframe = retainBackBufferFromKeyframe;
      return this;
    }

    /** Creates a {@link DefaultLoadControl}. */
    public DefaultLoadControl build() {
      checkState(!buildCalled);
      buildCalled = true;
      if (allocator == null) {
        allocator = new DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE);
      }
      if (onlyGenericConfigurationMethodsCalled != null && onlyGenericConfigurationMethodsCalled) {
        // For backwards-compatibility, if only generic setters were called, ensure the local
        // playback values are equivalent to the streaming ones even if not explicitly specified.
        minBufferForLocalPlaybackMs = minBufferMs;
        maxBufferForLocalPlaybackMs = maxBufferMs;
        bufferForPlaybackForLocalPlaybackMs = bufferForPlaybackMs;
        bufferForPlaybackAfterRebufferForLocalPlaybackMs = bufferForPlaybackAfterRebufferMs;
        prioritizeTimeOverSizeThresholdsForLocalPlayback = prioritizeTimeOverSizeThresholds;
      }
      return new DefaultLoadControl(
          allocator,
          minBufferMs,
          minBufferForLocalPlaybackMs,
          maxBufferMs,
          maxBufferForLocalPlaybackMs,
          bufferForPlaybackMs,
          bufferForPlaybackForLocalPlaybackMs,
          bufferForPlaybackAfterRebufferMs,
          bufferForPlaybackAfterRebufferForLocalPlaybackMs,
          targetBufferBytes,
          prioritizeTimeOverSizeThresholds,
          prioritizeTimeOverSizeThresholdsForLocalPlayback,
          backBufferDurationMs,
          retainBackBufferFromKeyframe,
          playerTargetBufferBytes);
    }
  }

  private final Timeline.Window window;
  private final Timeline.Period period;
  private final DefaultAllocator allocator;
  private final long minBufferUs;
  private final long minBufferForLocalPlaybackUs;
  private final long maxBufferUs;
  private final long maxBufferForLocalPlaybackUs;
  private final long bufferForPlaybackUs;
  private final long bufferForPlaybackForLocalPlaybackUs;
  private final long bufferForPlaybackAfterRebufferUs;
  private final long bufferForPlaybackAfterRebufferForLocalPlaybackUs;
  private final int targetBufferBytesOverwrite;
  private final boolean prioritizeTimeOverSizeThresholds;
  private final boolean prioritizeTimeOverSizeThresholdsForLocalPlayback;
  private final long backBufferDurationUs;
  private final boolean retainBackBufferFromKeyframe;
  private final ImmutableMap<String, Integer> playerTargetBufferBytesOverwrites;
  private final ConcurrentHashMap<PlayerId, PlayerLoadingState> loadingStates;

  private long threadId;

  /** Constructs a new instance, using the {@code DEFAULT_*} constants defined in this class. */
  public DefaultLoadControl() {
    this(
        new DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
        DEFAULT_MIN_BUFFER_MS,
        DEFAULT_MIN_BUFFER_FOR_LOCAL_PLAYBACK_MS,
        DEFAULT_MAX_BUFFER_MS,
        DEFAULT_MAX_BUFFER_FOR_LOCAL_PLAYBACK_MS,
        DEFAULT_BUFFER_FOR_PLAYBACK_MS,
        DEFAULT_BUFFER_FOR_PLAYBACK_FOR_LOCAL_PLAYBACK_MS,
        DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_FOR_LOCAL_PLAYBACK_MS,
        DEFAULT_TARGET_BUFFER_BYTES,
        DEFAULT_PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS,
        DEFAULT_PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS_FOR_LOCAL_PLAYBACK,
        DEFAULT_BACK_BUFFER_DURATION_MS,
        DEFAULT_RETAIN_BACK_BUFFER_FROM_KEYFRAME);
  }

  protected DefaultLoadControl(
      DefaultAllocator allocator,
      int minBufferMs,
      int minBufferForLocalPlaybackMs,
      int maxBufferMs,
      int maxBufferForLocalPlaybackMs,
      int bufferForPlaybackMs,
      int bufferForPlaybackForLocalPlaybackMs,
      int bufferForPlaybackAfterRebufferMs,
      int bufferForPlaybackAfterRebufferForLocalPlaybackMs,
      int targetBufferBytes,
      boolean prioritizeTimeOverSizeThresholds,
      boolean prioritizeTimeOverSizeThresholdsForLocalPlayback,
      int backBufferDurationMs,
      boolean retainBackBufferFromKeyframe,
      Map<String, Integer> playerTargetBufferBytes) {
    assertGreaterOrEqual(bufferForPlaybackMs, 0, "bufferForPlaybackMs", "0");
    assertGreaterOrEqual(
        bufferForPlaybackForLocalPlaybackMs, 0, "bufferForPlaybackForLocalPlaybackMs", "0");
    assertGreaterOrEqual(
        bufferForPlaybackAfterRebufferMs, 0, "bufferForPlaybackAfterRebufferMs", "0");
    assertGreaterOrEqual(
        bufferForPlaybackAfterRebufferForLocalPlaybackMs,
        0,
        "bufferForPlaybackAfterRebufferForLocalPlaybackMs",
        "0");
    assertGreaterOrEqual(minBufferMs, bufferForPlaybackMs, "minBufferMs", "bufferForPlaybackMs");
    assertGreaterOrEqual(
        minBufferForLocalPlaybackMs,
        bufferForPlaybackForLocalPlaybackMs,
        "minBufferForLocalPlaybackMs",
        "bufferForPlaybackForLocalPlaybackMs");
    assertGreaterOrEqual(
        minBufferMs,
        bufferForPlaybackAfterRebufferMs,
        "minBufferMs",
        "bufferForPlaybackAfterRebufferMs");
    assertGreaterOrEqual(
        minBufferForLocalPlaybackMs,
        bufferForPlaybackAfterRebufferForLocalPlaybackMs,
        "minBufferForLocalPlaybackMs",
        "bufferForPlaybackAfterRebufferForLocalPlaybackMs");
    assertGreaterOrEqual(maxBufferMs, minBufferMs, "maxBufferMs", "minBufferMs");
    assertGreaterOrEqual(
        maxBufferForLocalPlaybackMs,
        minBufferForLocalPlaybackMs,
        "maxBufferForLocalPlaybackMs",
        "minBufferForLocalPlaybackMs");
    assertGreaterOrEqual(backBufferDurationMs, 0, "backBufferDurationMs", "0");

    this.window = new Timeline.Window();
    this.period = new Timeline.Period();
    this.allocator = allocator;
    this.minBufferUs = msToUs(minBufferMs);
    this.minBufferForLocalPlaybackUs = msToUs(minBufferForLocalPlaybackMs);
    this.maxBufferUs = msToUs(maxBufferMs);
    this.maxBufferForLocalPlaybackUs = msToUs(maxBufferForLocalPlaybackMs);
    this.bufferForPlaybackUs = msToUs(bufferForPlaybackMs);
    this.bufferForPlaybackForLocalPlaybackUs = msToUs(bufferForPlaybackForLocalPlaybackMs);
    this.bufferForPlaybackAfterRebufferUs = msToUs(bufferForPlaybackAfterRebufferMs);
    this.bufferForPlaybackAfterRebufferForLocalPlaybackUs =
        msToUs(bufferForPlaybackAfterRebufferForLocalPlaybackMs);
    this.targetBufferBytesOverwrite = targetBufferBytes;
    this.prioritizeTimeOverSizeThresholds = prioritizeTimeOverSizeThresholds;
    this.prioritizeTimeOverSizeThresholdsForLocalPlayback =
        prioritizeTimeOverSizeThresholdsForLocalPlayback;
    this.backBufferDurationUs = msToUs(backBufferDurationMs);
    this.retainBackBufferFromKeyframe = retainBackBufferFromKeyframe;
    loadingStates = new ConcurrentHashMap<>();
    playerTargetBufferBytesOverwrites = ImmutableMap.copyOf(playerTargetBufferBytes);
    threadId = C.INDEX_UNSET;
  }

  protected DefaultLoadControl(
      DefaultAllocator allocator,
      int minBufferMs,
      int minBufferForLocalPlaybackMs,
      int maxBufferMs,
      int maxBufferForLocalPlaybackMs,
      int bufferForPlaybackMs,
      int bufferForPlaybackForLocalPlaybackMs,
      int bufferForPlaybackAfterRebufferMs,
      int bufferForPlaybackAfterRebufferForLocalPlaybackMs,
      int targetBufferBytes,
      boolean prioritizeTimeOverSizeThresholds,
      boolean prioritizeTimeOverSizeThresholdsForLocalPlayback,
      int backBufferDurationMs,
      boolean retainBackBufferFromKeyframe) {
    this(
        allocator,
        minBufferMs,
        minBufferForLocalPlaybackMs,
        maxBufferMs,
        maxBufferForLocalPlaybackMs,
        bufferForPlaybackMs,
        bufferForPlaybackForLocalPlaybackMs,
        bufferForPlaybackAfterRebufferMs,
        bufferForPlaybackAfterRebufferForLocalPlaybackMs,
        targetBufferBytes,
        prioritizeTimeOverSizeThresholds,
        prioritizeTimeOverSizeThresholdsForLocalPlayback,
        backBufferDurationMs,
        retainBackBufferFromKeyframe,
        ImmutableMap.of());
  }

  @Override
  public void onPrepared(PlayerId playerId) {
    long currentThreadId = Thread.currentThread().getId();
    checkState(
        threadId == C.INDEX_UNSET || threadId == currentThreadId,
        "Players that share the same LoadControl must share the same playback thread. See"
            + " ExoPlayer.Builder.setPlaybackLooper(Looper).");
    threadId = currentThreadId;
    @Nullable PlayerLoadingState playerLoadingState = loadingStates.get(playerId);
    if (playerLoadingState == null) {
      loadingStates.put(playerId, new PlayerLoadingState());
    } else {
      playerLoadingState.referenceCount++;
    }
    resetPlayerLoadingState(playerId);
  }

  @Override
  public void onTracksSelected(
      LoadControl.Parameters parameters,
      TrackGroupArray trackGroups,
      @NullableType ExoTrackSelection[] trackSelections) {
    int targetBufferBytesOverwrite = getTargetBufferBytesOverwrite(parameters.playerId);
    checkNotNull(loadingStates.get(parameters.playerId)).targetBufferBytes =
        targetBufferBytesOverwrite == C.LENGTH_UNSET
            ? calculateTargetBufferBytes(parameters, trackSelections)
            : targetBufferBytesOverwrite;
    updateAllocator();
  }

  @Override
  public void onStopped(PlayerId playerId) {
    removePlayer(playerId);
  }

  @Override
  public void onReleased(PlayerId playerId) {
    removePlayer(playerId);
    if (loadingStates.isEmpty()) {
      threadId = C.INDEX_UNSET;
    }
  }

  @Override
  public Allocator getAllocator(PlayerId playerId) {
    return new PlayerIdFilteringAllocatorImpl(playerId);
  }

  @Override
  public long getBackBufferDurationUs(PlayerId playerId) {
    return backBufferDurationUs;
  }

  @Override
  public boolean retainBackBufferFromKeyframe(PlayerId playerId) {
    return retainBackBufferFromKeyframe;
  }

  @Override
  public boolean shouldContinueLoading(Parameters parameters) {
    PlayerId playerId = parameters.playerId;
    PlayerLoadingState playerLoadingState = checkNotNull(loadingStates.get(playerId));
    boolean targetBufferSizeReached =
        getTotalBufferBytesAllocated(playerId) >= getTargetBufferBytes(playerId);
    if (playerId.equals(PlayerId.PRELOAD)) {
      return !targetBufferSizeReached;
    }
    boolean isLocalPlayback = isLocalPlayback(parameters);
    long minBufferUs = getMinBufferUs(isLocalPlayback);
    long maxBufferUs = getMaxBufferUs(isLocalPlayback);
    if (parameters.playbackSpeed > 1) {
      // The playback speed is faster than real time, so scale up the minimum required media
      // duration to keep enough media buffered for a playout duration of minBufferUs.
      long mediaDurationMinBufferUs =
          Util.getMediaDurationForPlayoutDuration(minBufferUs, parameters.playbackSpeed);
      minBufferUs = min(mediaDurationMinBufferUs, maxBufferUs);
    }
    // Prevent playback from getting stuck if minBufferUs is too small.
    minBufferUs = max(minBufferUs, 500_000);
    if (parameters.bufferedDurationUs < minBufferUs) {
      boolean prioritizeTimeOverSizeThresholds = prioritizeTimeOverSizeThresholds(isLocalPlayback);
      playerLoadingState.isLoading = prioritizeTimeOverSizeThresholds || !targetBufferSizeReached;
      if (!playerLoadingState.isLoading && parameters.bufferedDurationUs < 500_000) {
        Log.w(
            "DefaultLoadControl",
            "Target buffer size reached with less than 500ms of buffered media data.");
      }
    } else if (parameters.bufferedDurationUs >= maxBufferUs || targetBufferSizeReached) {
      playerLoadingState.isLoading = false;
    } // Else don't change the loading state.
    return playerLoadingState.isLoading;
  }

  @Override
  public boolean shouldStartPlayback(Parameters parameters) {
    boolean isLocalPlayback = isLocalPlayback(parameters);
    long bufferedDurationUs =
        Util.getPlayoutDurationForMediaDuration(
            parameters.bufferedDurationUs, parameters.playbackSpeed);
    long minBufferDurationUs =
        parameters.rebuffering
            ? getBufferForPlaybackAfterRebufferUs(isLocalPlayback)
            : getBufferForPlaybackUs(isLocalPlayback);
    if (parameters.targetLiveOffsetUs != C.TIME_UNSET) {
      minBufferDurationUs = min(parameters.targetLiveOffsetUs / 2, minBufferDurationUs);
    }
    return minBufferDurationUs <= 0
        || bufferedDurationUs >= minBufferDurationUs
        || (!prioritizeTimeOverSizeThresholds(isLocalPlayback)
            && getTotalBufferBytesAllocated(parameters.playerId)
                >= getTargetBufferBytes(parameters.playerId));
  }

  @Override
  public boolean shouldContinuePreloading(
      PlayerId playerId, Timeline timeline, MediaPeriodId mediaPeriodId, long bufferedDurationUs) {
    for (PlayerLoadingState playerLoadingState : loadingStates.values()) {
      if (playerLoadingState.isLoading) {
        return false;
      }
    }
    return true;
  }

  /**
   * @deprecated Overwrite {@link #calculateTargetBufferBytes(Parameters, ExoTrackSelection[])}
   *     instead.
   */
  @Deprecated
  protected int calculateTargetBufferBytes(@NullableType ExoTrackSelection[] trackSelectionArray) {
    return C.LENGTH_UNSET;
  }

  /**
   * Calculate target buffer size in bytes based on the selected tracks. The player will try not to
   * exceed this target buffer. Only used when {@code targetBufferBytes} is {@link C#LENGTH_UNSET}.
   *
   * @param parameters The {@link LoadControl.Parameters} for the current playback context for which
   *     tracks are selected.
   * @param trackSelectionArray The selected tracks.
   * @return The target buffer size in bytes.
   */
  @SuppressWarnings("deprecation") // Calling deprecated method for backwards compatibility
  protected int calculateTargetBufferBytes(
      LoadControl.Parameters parameters, @NullableType ExoTrackSelection[] trackSelectionArray) {
    int deprecatedResult = calculateTargetBufferBytes(trackSelectionArray);
    if (deprecatedResult != C.LENGTH_UNSET) {
      return deprecatedResult;
    }
    int targetBufferSize = 0;
    boolean isLocalPlayback = isLocalPlayback(parameters);
    for (ExoTrackSelection exoTrackSelection : trackSelectionArray) {
      if (exoTrackSelection != null) {
        targetBufferSize +=
            getDefaultBufferSize(exoTrackSelection.getTrackGroup().type, isLocalPlayback);
      }
    }
    return max(DEFAULT_MIN_BUFFER_SIZE, targetBufferSize);
  }

  @VisibleForTesting
  /* package */ int calculateTotalTargetBufferBytes() {
    int totalTargetBufferBytes = 0;
    for (PlayerLoadingState state : loadingStates.values()) {
      totalTargetBufferBytes += state.targetBufferBytes;
    }
    return totalTargetBufferBytes;
  }

  private void resetPlayerLoadingState(PlayerId playerId) {
    PlayerLoadingState playerLoadingState = checkNotNull(loadingStates.get(playerId));
    int targetBufferBytesOverwrite = getTargetBufferBytesOverwrite(playerId);
    playerLoadingState.targetBufferBytes =
        targetBufferBytesOverwrite != C.LENGTH_UNSET
            ? targetBufferBytesOverwrite
            : DEFAULT_MIN_BUFFER_SIZE;
    playerLoadingState.isLoading = false;
  }

  private int getTargetBufferBytesOverwrite(PlayerId playerId) {
    Integer playerTargetBufferBytesOverwrite = playerTargetBufferBytesOverwrites.get(playerId.name);
    if (playerTargetBufferBytesOverwrite != null
        && playerTargetBufferBytesOverwrite != C.LENGTH_UNSET) {
      return playerTargetBufferBytesOverwrite;
    }
    return targetBufferBytesOverwrite;
  }

  private void removePlayer(PlayerId playerId) {
    @Nullable PlayerLoadingState playerLoadingState = loadingStates.get(playerId);
    if (playerLoadingState != null) {
      playerLoadingState.referenceCount--;
      if (playerLoadingState.referenceCount == 0) {
        loadingStates.remove(playerId);
        updateAllocator();
      }
    }
  }

  private void updateAllocator() {
    if (loadingStates.isEmpty()) {
      allocator.reset();
    } else {
      allocator.setTargetBufferSize(calculateTotalTargetBufferBytes());
    }
  }

  private static int getDefaultBufferSize(@C.TrackType int trackType, boolean isLocalPlayback) {
    switch (trackType) {
      case C.TRACK_TYPE_DEFAULT:
        return DEFAULT_MUXED_BUFFER_SIZE;
      case C.TRACK_TYPE_AUDIO:
        return DEFAULT_AUDIO_BUFFER_SIZE;
      case C.TRACK_TYPE_VIDEO:
        return isLocalPlayback
            ? DEFAULT_VIDEO_BUFFER_SIZE_FOR_LOCAL_PLAYBACK
            : DEFAULT_VIDEO_BUFFER_SIZE;
      case C.TRACK_TYPE_TEXT:
        return DEFAULT_TEXT_BUFFER_SIZE;
      case C.TRACK_TYPE_METADATA:
        return DEFAULT_METADATA_BUFFER_SIZE;
      case C.TRACK_TYPE_CAMERA_MOTION:
        return DEFAULT_CAMERA_MOTION_BUFFER_SIZE;
      case C.TRACK_TYPE_IMAGE:
        return DEFAULT_IMAGE_BUFFER_SIZE;
      case C.TRACK_TYPE_NONE:
        return 0;
      case C.TRACK_TYPE_UNKNOWN:
        return DEFAULT_MIN_BUFFER_SIZE;
      default:
        throw new IllegalArgumentException();
    }
  }

  private boolean isLocalPlayback(Parameters parameters) {
    int windowIndex =
        parameters.timeline.getPeriodByUid(parameters.mediaPeriodId.periodUid, period).windowIndex;
    MediaItem mediaItem = parameters.timeline.getWindow(windowIndex, window).mediaItem;
    if (mediaItem.localConfiguration == null) {
      return false;
    }
    String scheme = mediaItem.localConfiguration.uri.getScheme();
    return TextUtils.isEmpty(scheme) || LOCAL_PLAYBACK_SCHEMES.contains(scheme);
  }

  private long getMinBufferUs(boolean isLocalPlayback) {
    return isLocalPlayback ? minBufferForLocalPlaybackUs : minBufferUs;
  }

  private long getMaxBufferUs(boolean isLocalPlayback) {
    return isLocalPlayback ? maxBufferForLocalPlaybackUs : maxBufferUs;
  }

  private long getBufferForPlaybackUs(boolean isLocalPlayback) {
    return isLocalPlayback ? bufferForPlaybackForLocalPlaybackUs : bufferForPlaybackUs;
  }

  private long getBufferForPlaybackAfterRebufferUs(boolean isLocalPlayback) {
    return isLocalPlayback
        ? bufferForPlaybackAfterRebufferForLocalPlaybackUs
        : bufferForPlaybackAfterRebufferUs;
  }

  private boolean prioritizeTimeOverSizeThresholds(boolean isLocalPlayback) {
    return isLocalPlayback
        ? prioritizeTimeOverSizeThresholdsForLocalPlayback
        : prioritizeTimeOverSizeThresholds;
  }

  private static void assertGreaterOrEqual(int value1, int value2, String name1, String name2) {
    checkArgument(value1 >= value2, "%s cannot be less than %s", name1, name2);
  }

  private int getTotalBufferBytesAllocated(PlayerId playerId) {
    return checkNotNull(loadingStates.get(playerId)).getAllocatedCounts()
        * allocator.getIndividualAllocationLength();
  }

  private int getTargetBufferBytes(PlayerId playerId) {
    return checkNotNull(loadingStates.get(playerId)).targetBufferBytes;
  }

  private static class PlayerLoadingState {
    public int referenceCount;
    public boolean isLoading;
    public int targetBufferBytes;

    @GuardedBy("this")
    private int allocatedCounts;

    public PlayerLoadingState() {
      referenceCount = 1;
    }

    public synchronized void increaseAllocatedCounts() {
      allocatedCounts++;
    }

    public synchronized void decreaseAllocatedCounts() {
      allocatedCounts--;
    }

    public synchronized int getAllocatedCounts() {
      return allocatedCounts;
    }
  }

  private final class PlayerIdFilteringAllocatorImpl implements PlayerIdAwareAllocator {
    @GuardedBy("this")
    private final HashMap<Allocation, PlayerId> allocationPlayerIdMap;

    @GuardedBy("this")
    private PlayerId playerId;

    public PlayerIdFilteringAllocatorImpl(PlayerId playerId) {
      this.allocationPlayerIdMap = new HashMap<>();
      this.playerId = playerId;
    }

    @Override
    public synchronized void setPlayerId(PlayerId playerId) {
      this.playerId = playerId;
    }

    @Override
    public synchronized Allocation allocate() {
      Allocation allocation = allocator.allocate();
      allocationPlayerIdMap.put(allocation, playerId);
      @Nullable PlayerLoadingState playerLoadingState = loadingStates.get(playerId);
      if (playerLoadingState != null) {
        playerLoadingState.increaseAllocatedCounts();
      }
      return allocation;
    }

    @Override
    public synchronized void release(Allocation allocation) {
      allocator.release(allocation);
      releaseInternal(allocation);
    }

    @Override
    public synchronized void release(@Nullable AllocationNode allocationNode) {
      allocator.release(allocationNode);
      while (allocationNode != null) {
        releaseInternal(allocationNode.getAllocation());
        allocationNode = allocationNode.next();
      }
    }

    @Override
    public synchronized void trim() {
      allocator.trim();
    }

    @Override
    public synchronized int getTotalBytesAllocated() {
      return getTotalBufferBytesAllocated(playerId);
    }

    @Override
    public synchronized int getIndividualAllocationLength() {
      return allocator.getIndividualAllocationLength();
    }

    @GuardedBy("this")
    private void releaseInternal(Allocation allocation) {
      PlayerId playerId = checkNotNull(allocationPlayerIdMap.remove(allocation));
      @Nullable PlayerLoadingState playerLoadingState = loadingStates.get(playerId);
      if (playerLoadingState != null) {
        playerLoadingState.decreaseAllocatedCounts();
      }
    }
  }
}
