/*
 * Copyright (C) 2017 The Android Open Source Project
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
package androidx.media3.test.utils;

import static androidx.media3.test.utils.FakeTimeline.TimelineWindowDefinition.DEFAULT_WINDOW_DURATION_US;
import static androidx.media3.test.utils.FakeTimeline.TimelineWindowDefinition.DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkElementIndex;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.min;

import android.net.Uri;
import android.util.Pair;
import androidx.annotation.Nullable;
import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.source.ShuffleOrder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Fake {@link Timeline} which can be setup to return custom {@link TimelineWindowDefinition}s. */
@UnstableApi
public final class FakeTimeline extends Timeline {

  /** Definition used to define a {@link FakeTimeline}. */
  public static final class TimelineWindowDefinition {

    /** A builder to build instances of {@link FakeTimeline.TimelineWindowDefinition}. */
    public static class Builder {
      private int periodCount;
      private Object uid;
      private boolean isSeekable;
      private boolean isDynamic;
      private boolean isLive;
      private boolean isPlaceholder;
      private long durationUs;
      private long defaultPositionUs;
      private long windowStartTimeUs;
      private long windowPositionInFirstPeriodUs;
      @Nullable private List<AdPlaybackState> adPlaybackStates;
      @Nullable private MediaItem mediaItem;

      /** Create a new instance. */
      public Builder() {
        periodCount = 1;
        uid = 0;
        isSeekable = true;
        durationUs = DEFAULT_WINDOW_DURATION_US;
        defaultPositionUs = 0L;
        windowStartTimeUs = C.TIME_UNSET;
        windowPositionInFirstPeriodUs = DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US;
      }

      private Builder(TimelineWindowDefinition timelineWindowDefinition) {
        periodCount = timelineWindowDefinition.periodCount;
        uid = timelineWindowDefinition.id;
        isSeekable = timelineWindowDefinition.isSeekable;
        isDynamic = timelineWindowDefinition.isDynamic;
        isLive = timelineWindowDefinition.isLive;
        isPlaceholder = timelineWindowDefinition.isPlaceholder;
        durationUs = timelineWindowDefinition.durationUs;
        defaultPositionUs = timelineWindowDefinition.defaultPositionUs;
        windowStartTimeUs = timelineWindowDefinition.windowStartTimeUs;
        windowPositionInFirstPeriodUs = timelineWindowDefinition.windowOffsetInFirstPeriodUs;
        adPlaybackStates = timelineWindowDefinition.adPlaybackStates;
        mediaItem = timelineWindowDefinition.mediaItem;
      }

      /** See {@code Timeline.Window#getPeriodCount()}. Default is 1. */
      @CanIgnoreReturnValue
      public Builder setPeriodCount(int periodCount) {
        this.periodCount = periodCount;
        return this;
      }

      /** See {@link Timeline.Window#uid}. Default is 0 auto-boxed to an {@link Integer}. */
      @CanIgnoreReturnValue
      public Builder setUid(Object uid) {
        this.uid = uid;
        return this;
      }

      /** See {@link Timeline.Window#isSeekable}. Default is true. */
      @CanIgnoreReturnValue
      public Builder setSeekable(boolean seekable) {
        isSeekable = seekable;
        return this;
      }

      /** See {@link Timeline.Window#isDynamic}. Default is false. */
      @CanIgnoreReturnValue
      public Builder setDynamic(boolean dynamic) {
        isDynamic = dynamic;
        return this;
      }

      /** See {@link Window#isLive()}. Default is false. */
      @CanIgnoreReturnValue
      public Builder setLive(boolean live) {
        isLive = live;
        return this;
      }

      /** See {@link Timeline.Window#isPlaceholder}. Default is false. */
      @CanIgnoreReturnValue
      public Builder setPlaceholder(boolean placeholder) {
        isPlaceholder = placeholder;
        return this;
      }

      /**
       * See {@link Timeline.Window#durationUs}. Default is {@link
       * TimelineWindowDefinition#DEFAULT_WINDOW_DURATION_US}.
       */
      @CanIgnoreReturnValue
      public Builder setDurationUs(long durationUs) {
        checkArgument(durationUs >= 0 || durationUs == C.TIME_UNSET);
        this.durationUs = durationUs;
        return this;
      }

      /** See {@link Timeline.Window#defaultPositionUs}. Default is 0. */
      @CanIgnoreReturnValue
      public Builder setDefaultPositionUs(long defaultPositionUs) {
        checkArgument(defaultPositionUs >= 0);
        this.defaultPositionUs = defaultPositionUs;
        return this;
      }

      /**
       * See {@link Timeline.Window#windowStartTimeMs} or {@link C#TIME_UNSET} if unknown. Default
       * is {@link C#TIME_UNSET}.
       */
      @CanIgnoreReturnValue
      public Builder setWindowStartTimeUs(long windowStartTimeUs) {
        checkArgument(windowStartTimeUs >= 0 || windowStartTimeUs == C.TIME_UNSET);
        this.windowStartTimeUs = windowStartTimeUs;
        return this;
      }

      /**
       * See {@link Timeline.Window#positionInFirstPeriodUs}. Default is {@link
       * #DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US}.
       */
      @CanIgnoreReturnValue
      public Builder setWindowPositionInFirstPeriodUs(long windowPositionInFirstPeriodUs) {
        checkArgument(windowPositionInFirstPeriodUs >= 0);
        this.windowPositionInFirstPeriodUs = windowPositionInFirstPeriodUs;
        return this;
      }

      /**
       * See {@link Timeline.Period#adPlaybackState}. Default is a list of size of {@code
       * Window#getPeriodCount()} with an {@linkplain AdPlaybackState#NONE empty ad playback state}
       * on each position.
       */
      @CanIgnoreReturnValue
      public Builder setAdPlaybackStates(List<AdPlaybackState> adPlaybackStates) {
        this.adPlaybackStates = ImmutableList.copyOf(adPlaybackStates);
        return this;
      }

      /**
       * See {@link Timeline.Window#mediaItem}. Default is {@code
       * FAKE_MEDIA_ITEM.buildUpon().setTag(uid).build()}.
       */
      @CanIgnoreReturnValue
      public Builder setMediaItem(MediaItem mediaItem) {
        this.mediaItem = mediaItem;
        return this;
      }

      /** Build an instance of {@link FakeTimeline.TimelineWindowDefinition}. */
      public TimelineWindowDefinition build() {
        MediaItem mediaItem = this.mediaItem;
        if (mediaItem == null) {
          mediaItem = FAKE_MEDIA_ITEM.buildUpon().setTag(uid).build();
        }
        List<AdPlaybackState> adPlaybackStates = this.adPlaybackStates;
        if (adPlaybackStates == null) {
          ImmutableList.Builder<AdPlaybackState> builder = new ImmutableList.Builder<>();
          for (int i = 0; i < periodCount; i++) {
            builder.add(AdPlaybackState.NONE);
          }
          adPlaybackStates = builder.build();
        } else {
          checkState(adPlaybackStates.size() == periodCount);
        }
        return new TimelineWindowDefinition(
            periodCount,
            uid,
            isSeekable,
            isDynamic,
            isLive,
            isPlaceholder,
            durationUs,
            defaultPositionUs,
            windowStartTimeUs,
            /* windowOffsetInFirstPeriodUs= */ windowPositionInFirstPeriodUs,
            adPlaybackStates,
            mediaItem);
      }
    }

    /** Default window duration in microseconds. */
    public static final long DEFAULT_WINDOW_DURATION_US = 10 * C.MICROS_PER_SECOND;

    /** Default offset of a window in its first period in microseconds. */
    public static final long DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US = 123 * C.MICROS_PER_SECOND;

    public final int periodCount;
    public final Object id;
    public final MediaItem mediaItem;
    public final boolean isSeekable;
    public final boolean isDynamic;
    public final boolean isLive;
    public final boolean isPlaceholder;
    public final long durationUs;
    public final long defaultPositionUs;
    public final long windowStartTimeUs;
    public final long windowOffsetInFirstPeriodUs;
    public final List<AdPlaybackState> adPlaybackStates;

    /**
     * Returns a {@link TimelineWindowDefinition.Builder} initialized with the values of this
     * instance.
     */
    public TimelineWindowDefinition.Builder buildUpon() {
      return new Builder(this);
    }

    /**
     * Creates a window definition that corresponds to a placeholder timeline using the given tag.
     *
     * @param tag The tag to use in the timeline.
     */
    public static TimelineWindowDefinition createPlaceholder(Object tag) {
      return new Builder()
          .setUid(tag)
          .setSeekable(false)
          .setDynamic(true)
          .setPlaceholder(true)
          .setDurationUs(C.TIME_UNSET)
          .setWindowStartTimeUs(0L)
          .setWindowPositionInFirstPeriodUs(0L)
          .build();
    }

    /**
     * @deprecated Use {@link FakeTimeline.TimelineWindowDefinition.Builder} instead.
     */
    @Deprecated
    public TimelineWindowDefinition(int periodCount, Object id) {
      this(
          periodCount,
          id,
          /* isSeekable= */ true,
          /* isDynamic= */ false,
          /* isLive= */ false,
          /* isPlaceholder= */ false,
          DEFAULT_WINDOW_DURATION_US,
          /* defaultPositionUs= */ 0,
          /* windowStartTimeUs= */ DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US, // keep for bkw compat.
          /* windowOffsetInFirstPeriodUs= */ DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US,
          ImmutableList.of(AdPlaybackState.NONE),
          FAKE_MEDIA_ITEM.buildUpon().setTag(id).build());
    }

    /**
     * @deprecated Use {@link FakeTimeline.TimelineWindowDefinition.Builder} instead.
     */
    @Deprecated
    public TimelineWindowDefinition(boolean isSeekable, boolean isDynamic, long durationUs) {
      this(
          /* periodCount= */ 1,
          /* id= */ 0,
          isSeekable,
          isDynamic,
          /* isLive= */ isDynamic,
          /* isPlaceholder= */ false,
          durationUs,
          /* defaultPositionUs= */ 0,
          /* windowStartTimeUs= */ DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US, // keep for bkw compat.
          /* windowOffsetInFirstPeriodUs= */ DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US,
          ImmutableList.of(AdPlaybackState.NONE),
          FAKE_MEDIA_ITEM.buildUpon().setTag(0).build());
    }

    /**
     * @deprecated Use {@link FakeTimeline.TimelineWindowDefinition.Builder} instead.
     */
    @Deprecated
    public TimelineWindowDefinition(
        int periodCount, Object id, boolean isSeekable, boolean isDynamic, long durationUs) {
      this(
          periodCount,
          id,
          isSeekable,
          isDynamic,
          /* isLive= */ isDynamic,
          /* isPlaceholder= */ false,
          durationUs,
          /* defaultPositionUs= */ 0,
          /* windowStartTimeUs= */ DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US, // keep for bkw compat.
          /* windowOffsetInFirstPeriodUs= */ DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US,
          ImmutableList.of(AdPlaybackState.NONE),
          FAKE_MEDIA_ITEM.buildUpon().setTag(id).build());
    }

    /**
     * @deprecated Use {@link FakeTimeline.TimelineWindowDefinition.Builder} instead.
     */
    @Deprecated
    public TimelineWindowDefinition(
        int periodCount,
        Object id,
        boolean isSeekable,
        boolean isDynamic,
        long durationUs,
        AdPlaybackState adPlaybackState) {
      this(
          periodCount,
          id,
          isSeekable,
          isDynamic,
          /* isLive= */ isDynamic,
          /* isPlaceholder= */ false,
          durationUs,
          /* defaultPositionUs= */ 0,
          /* windowStartTimeUs= */ DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US, // keep for bkw compat.
          /* windowOffsetInFirstPeriodUs= */ DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US,
          ImmutableList.of(adPlaybackState),
          FAKE_MEDIA_ITEM.buildUpon().setTag(id).build());
    }

    /**
     * @deprecated Use {@link FakeTimeline.TimelineWindowDefinition.Builder} instead.
     */
    @Deprecated
    public TimelineWindowDefinition(
        int periodCount,
        Object id,
        boolean isSeekable,
        boolean isDynamic,
        boolean isLive,
        boolean isPlaceholder,
        long durationUs,
        long defaultPositionUs,
        long windowOffsetInFirstPeriodUs,
        AdPlaybackState adPlaybackState) {
      this(
          periodCount,
          id,
          isSeekable,
          isDynamic,
          isLive,
          isPlaceholder,
          durationUs,
          defaultPositionUs,
          /* windowStartTimeUs= */ windowOffsetInFirstPeriodUs, // keep for backwards compatibility
          windowOffsetInFirstPeriodUs,
          ImmutableList.of(adPlaybackState),
          FAKE_MEDIA_ITEM.buildUpon().setTag(id).build());
    }

    /**
     * @deprecated Use {@link #TimelineWindowDefinition(int, Object, boolean, boolean, boolean,
     *     boolean, long, long, long, List, MediaItem)} instead.
     */
    @Deprecated
    public TimelineWindowDefinition(
        int periodCount,
        Object id,
        boolean isSeekable,
        boolean isDynamic,
        boolean isLive,
        boolean isPlaceholder,
        long durationUs,
        long defaultPositionUs,
        long windowOffsetInFirstPeriodUs,
        AdPlaybackState adPlaybackState,
        MediaItem mediaItem) {
      this(
          periodCount,
          id,
          isSeekable,
          isDynamic,
          isLive,
          isPlaceholder,
          durationUs,
          defaultPositionUs,
          /* windowStartTimeUs= */ windowOffsetInFirstPeriodUs, // keep for backwards compatibility
          windowOffsetInFirstPeriodUs,
          ImmutableList.of(adPlaybackState),
          mediaItem);
    }

    /**
     * @deprecated Use {@link FakeTimeline.TimelineWindowDefinition.Builder} instead.
     */
    @Deprecated
    public TimelineWindowDefinition(
        int periodCount,
        Object id,
        boolean isSeekable,
        boolean isDynamic,
        boolean isLive,
        boolean isPlaceholder,
        long durationUs,
        long defaultPositionUs,
        long windowOffsetInFirstPeriodUs,
        List<AdPlaybackState> adPlaybackStates,
        MediaItem mediaItem) {
      this(
          periodCount,
          id,
          isSeekable,
          isDynamic,
          isLive,
          isPlaceholder,
          durationUs,
          defaultPositionUs,
          /* windowStartTimeUs= */ windowOffsetInFirstPeriodUs, // keep for backwards compatibility
          windowOffsetInFirstPeriodUs,
          adPlaybackStates,
          mediaItem);
    }

    /**
     * Creates a window definition with ad groups and a custom media item.
     *
     * @param periodCount The number of periods in the window. Each period get an equal slice of the
     *     total window duration.
     * @param id The UID of the window.
     * @param isSeekable Whether the window is seekable.
     * @param isDynamic Whether the window is dynamic.
     * @param isLive Whether the window is live.
     * @param isPlaceholder Whether the window is a placeholder.
     * @param durationUs The duration of the window in microseconds.
     * @param defaultPositionUs The default position of the window in microseconds.
     * @param windowStartTimeUs The window start time, in microseconds or {@link C#TIME_UNSET} if
     *     unknown.
     * @param windowOffsetInFirstPeriodUs The offset of the window in the first period, in
     *     microseconds.
     * @param adPlaybackStates The ad playback states for the periods.
     * @param mediaItem The media item to include in the timeline.
     */
    private TimelineWindowDefinition(
        int periodCount,
        Object id,
        boolean isSeekable,
        boolean isDynamic,
        boolean isLive,
        boolean isPlaceholder,
        long durationUs,
        long defaultPositionUs,
        long windowStartTimeUs,
        long windowOffsetInFirstPeriodUs,
        List<AdPlaybackState> adPlaybackStates,
        MediaItem mediaItem) {
      checkArgument(durationUs != C.TIME_UNSET || periodCount == 1);
      this.periodCount = periodCount;
      this.id = id;
      this.mediaItem = mediaItem;
      this.isSeekable = isSeekable;
      this.isDynamic = isDynamic;
      this.isLive = isLive;
      this.isPlaceholder = isPlaceholder;
      this.durationUs = durationUs;
      this.defaultPositionUs = defaultPositionUs;
      this.windowStartTimeUs = windowStartTimeUs;
      this.windowOffsetInFirstPeriodUs = windowOffsetInFirstPeriodUs;
      this.adPlaybackStates = adPlaybackStates;
    }
  }

  /** The fake media item used by the fake timeline. */
  public static final MediaItem FAKE_MEDIA_ITEM =
      new MediaItem.Builder().setMediaId("FakeTimeline").setUri(Uri.EMPTY).build();

  private static final long AD_DURATION_US = 5 * C.MICROS_PER_SECOND;

  private final TimelineWindowDefinition[] windowDefinitions;
  private final Object[] manifests;
  private final int[] periodOffsets;
  private final ShuffleOrder shuffleOrder;

  /**
   * Returns an ad playback state with the specified number of ads in each of the specified ad
   * groups, each ten seconds long.
   *
   * @param adsPerAdGroup The number of ads per ad group.
   * @param adGroupTimesUs The times of ad groups, in microseconds.
   * @return The ad playback state.
   */
  public static AdPlaybackState createAdPlaybackState(int adsPerAdGroup, long... adGroupTimesUs) {
    int adGroupCount = adGroupTimesUs.length;
    AdPlaybackState adPlaybackState =
        new AdPlaybackState(/* adsId= */ new Object(), adGroupTimesUs);
    long[][] adDurationsUs = new long[adGroupCount][];
    for (int i = 0; i < adGroupCount; i++) {
      adPlaybackState = adPlaybackState.withAdCount(/* adGroupIndex= */ i, adsPerAdGroup);
      for (int j = 0; j < adsPerAdGroup; j++) {
        adPlaybackState =
            adPlaybackState.withAvailableAdMediaItem(
                /* adGroupIndex= */ i,
                /* adIndexInAdGroup= */ j,
                MediaItem.fromUri("https://example.com/ad/" + i + "/" + j));
      }
      adDurationsUs[i] = new long[adsPerAdGroup];
      Arrays.fill(adDurationsUs[i], AD_DURATION_US);
    }
    adPlaybackState = adPlaybackState.withAdDurationsUs(adDurationsUs);

    return adPlaybackState;
  }

  /**
   * Creates a multi-period timeline with ad and content periods specified by the flags passed as
   * var-arg arguments.
   *
   * <p>Period uid end up being a {@code new Pair<>(windowId, periodIndex)}.
   *
   * @param windowId The window ID.
   * @param numberOfPlayedAds The number of ads that should be marked as played.
   * @param isAdPeriodFlags A value of true indicates an ad period. A value of false indicated a
   *     content period.
   * @return A timeline with a single window with as many periods as var-arg arguments.
   */
  public static FakeTimeline createMultiPeriodAdTimeline(
      Object windowId, int numberOfPlayedAds, boolean... isAdPeriodFlags) {
    long periodDurationUs = DEFAULT_WINDOW_DURATION_US / isAdPeriodFlags.length;
    AdPlaybackState contentPeriodState = new AdPlaybackState(/* adsId= */ "adsId");
    AdPlaybackState firstAdPeriodState =
        contentPeriodState
            .withNewAdGroup(/* adGroupIndex= */ 0, /* adGroupTimeUs= */ 0)
            .withAdCount(/* adGroupIndex= */ 0, 1)
            .withAdDurationsUs(
                /* adGroupIndex= */ 0, DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US + periodDurationUs)
            .withIsServerSideInserted(/* adGroupIndex= */ 0, true);
    AdPlaybackState commonAdPeriodState = firstAdPeriodState.withAdDurationsUs(0, periodDurationUs);

    List<AdPlaybackState> adPlaybackStates = new ArrayList<>();
    int playedAdsCounter = 0;
    for (boolean isAd : isAdPeriodFlags) {
      AdPlaybackState periodAdPlaybackState =
          isAd
              ? (adPlaybackStates.isEmpty() ? firstAdPeriodState : commonAdPeriodState)
              : contentPeriodState;
      if (isAd && playedAdsCounter < numberOfPlayedAds) {
        periodAdPlaybackState =
            periodAdPlaybackState.withPlayedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
        playedAdsCounter++;
      }
      adPlaybackStates.add(periodAdPlaybackState);
    }
    return new FakeTimeline(
        new FakeTimeline.TimelineWindowDefinition(
            isAdPeriodFlags.length,
            windowId,
            /* isSeekable= */ true,
            /* isDynamic= */ false,
            /* isLive= */ false,
            /* isPlaceholder= */ false,
            /* durationUs= */ DEFAULT_WINDOW_DURATION_US,
            /* defaultPositionUs= */ 0,
            /* windowOffsetInFirstPeriodUs= */ DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US,
            /* adPlaybackStates= */ adPlaybackStates,
            MediaItem.EMPTY));
  }

  /**
   * Create a fake timeline with one seekable, non-dynamic window with one period and a duration of
   * {@link TimelineWindowDefinition#DEFAULT_WINDOW_DURATION_US}.
   */
  public FakeTimeline() {
    this(/* windowCount= */ 1);
  }

  /**
   * Creates a fake timeline with the given number of seekable, non-dynamic windows with one period
   * with a duration of {@link TimelineWindowDefinition#DEFAULT_WINDOW_DURATION_US} each.
   *
   * @param windowCount The number of windows.
   * @param manifests The manifests of the windows.
   */
  public FakeTimeline(int windowCount, Object... manifests) {
    this(manifests, createDefaultWindowDefinitions(windowCount));
  }

  /**
   * Creates a fake timeline with the given window definitions.
   *
   * @param windowDefinitions A list of {@link TimelineWindowDefinition}s.
   */
  public FakeTimeline(TimelineWindowDefinition... windowDefinitions) {
    this(new Object[0], windowDefinitions);
  }

  /**
   * Creates a fake timeline with the given window definitions.
   *
   * @param manifests The manifests of the windows.
   * @param windowDefinitions A list of {@link TimelineWindowDefinition}s.
   */
  public FakeTimeline(Object[] manifests, TimelineWindowDefinition... windowDefinitions) {
    this(manifests, new FakeShuffleOrder(windowDefinitions.length), windowDefinitions);
  }

  /**
   * Creates a fake timeline with the given window definitions and {@link
   * androidx.media3.exoplayer.source.ShuffleOrder}.
   *
   * @param manifests The manifests of the windows.
   * @param shuffleOrder A shuffle ordering for the windows.
   * @param windowDefinitions A list of {@link TimelineWindowDefinition}s.
   */
  public FakeTimeline(
      Object[] manifests,
      ShuffleOrder shuffleOrder,
      TimelineWindowDefinition... windowDefinitions) {
    this.manifests = new Object[windowDefinitions.length];
    System.arraycopy(manifests, 0, this.manifests, 0, min(this.manifests.length, manifests.length));
    this.windowDefinitions = windowDefinitions;
    periodOffsets = new int[windowDefinitions.length + 1];
    periodOffsets[0] = 0;
    for (int i = 0; i < windowDefinitions.length; i++) {
      periodOffsets[i + 1] = periodOffsets[i] + windowDefinitions[i].periodCount;
    }
    this.shuffleOrder = shuffleOrder;
  }

  @Override
  public int getWindowCount() {
    return windowDefinitions.length;
  }

  @Override
  public int getNextWindowIndex(
      int windowIndex, @Player.RepeatMode int repeatMode, boolean shuffleModeEnabled) {
    if (repeatMode == Player.REPEAT_MODE_ONE) {
      return windowIndex;
    }
    if (windowIndex == getLastWindowIndex(shuffleModeEnabled)) {
      return repeatMode == Player.REPEAT_MODE_ALL
          ? getFirstWindowIndex(shuffleModeEnabled)
          : C.INDEX_UNSET;
    }
    return shuffleModeEnabled ? shuffleOrder.getNextIndex(windowIndex) : windowIndex + 1;
  }

  @Override
  public int getPreviousWindowIndex(
      int windowIndex, @Player.RepeatMode int repeatMode, boolean shuffleModeEnabled) {
    if (repeatMode == Player.REPEAT_MODE_ONE) {
      return windowIndex;
    }
    if (windowIndex == getFirstWindowIndex(shuffleModeEnabled)) {
      return repeatMode == Player.REPEAT_MODE_ALL
          ? getLastWindowIndex(shuffleModeEnabled)
          : C.INDEX_UNSET;
    }
    return shuffleModeEnabled ? shuffleOrder.getPreviousIndex(windowIndex) : windowIndex - 1;
  }

  @Override
  public int getLastWindowIndex(boolean shuffleModeEnabled) {
    return shuffleModeEnabled
        ? shuffleOrder.getLastIndex()
        : super.getLastWindowIndex(/* shuffleModeEnabled= */ false);
  }

  @Override
  public int getFirstWindowIndex(boolean shuffleModeEnabled) {
    return shuffleModeEnabled
        ? shuffleOrder.getFirstIndex()
        : super.getFirstWindowIndex(/* shuffleModeEnabled= */ false);
  }

  @Override
  public Window getWindow(int windowIndex, Window window, long defaultPositionProjectionUs) {
    TimelineWindowDefinition windowDefinition = windowDefinitions[windowIndex];
    long windowDurationUs = 0;
    Period period = new Period();
    for (int i = periodOffsets[windowIndex]; i < periodOffsets[windowIndex + 1]; i++) {
      long periodDurationUs = getPeriod(/* periodIndex= */ i, period).durationUs;
      if (i == periodOffsets[windowIndex] && periodDurationUs != 0) {
        windowDurationUs -= windowDefinition.windowOffsetInFirstPeriodUs;
      }
      if (periodDurationUs == C.TIME_UNSET) {
        windowDurationUs = C.TIME_UNSET;
        break;
      }
      windowDurationUs += periodDurationUs;
    }
    window.set(
        /* uid= */ windowDefinition.id,
        windowDefinition.mediaItem,
        manifests[windowIndex],
        /* presentationStartTimeMs= */ C.TIME_UNSET,
        windowDefinition.isLive ? Util.usToMs(windowDefinition.windowStartTimeUs) : C.TIME_UNSET,
        /* elapsedRealtimeEpochOffsetMs= */ windowDefinition.isLive ? 0 : C.TIME_UNSET,
        windowDefinition.isSeekable,
        windowDefinition.isDynamic,
        windowDefinition.isLive ? windowDefinition.mediaItem.liveConfiguration : null,
        windowDefinition.defaultPositionUs,
        windowDurationUs,
        periodOffsets[windowIndex],
        periodOffsets[windowIndex + 1] - 1,
        /* positionInFirstPeriodUs= */ windowDefinition.windowOffsetInFirstPeriodUs);
    window.isPlaceholder = windowDefinition.isPlaceholder;
    return window;
  }

  @Override
  public int getPeriodCount() {
    return periodOffsets[periodOffsets.length - 1];
  }

  @Override
  public Period getPeriod(int periodIndex, Period period, boolean setIds) {
    int windowIndex = Util.binarySearchFloor(periodOffsets, periodIndex, true, false);
    int windowPeriodIndex = periodIndex - periodOffsets[windowIndex];
    TimelineWindowDefinition windowDefinition = windowDefinitions[windowIndex];
    Object id = setIds ? windowPeriodIndex : null;
    Object uid = setIds ? Pair.create(windowDefinition.id, windowPeriodIndex) : null;
    AdPlaybackState adPlaybackState =
        windowDefinition.adPlaybackStates.get(
            periodIndex % windowDefinition.adPlaybackStates.size());
    // Arbitrarily set period duration by distributing window duration equally among all periods.
    long periodDurationUs =
        periodIndex == windowDefinition.periodCount - 1
                && windowDefinition.durationUs == C.TIME_UNSET
            ? C.TIME_UNSET
            : (windowDefinition.durationUs / windowDefinition.periodCount);
    long positionInWindowUs;
    if (windowPeriodIndex == 0) {
      if (windowDefinition.durationUs != C.TIME_UNSET) {
        periodDurationUs += windowDefinition.windowOffsetInFirstPeriodUs;
      }
      positionInWindowUs = -windowDefinition.windowOffsetInFirstPeriodUs;
    } else {
      positionInWindowUs = periodDurationUs * windowPeriodIndex;
    }
    period.set(
        id,
        uid,
        windowIndex,
        periodDurationUs,
        positionInWindowUs,
        adPlaybackState,
        windowDefinition.isPlaceholder);
    return period;
  }

  @Override
  public int getIndexOfPeriod(Object uid) {
    for (int i = 0; i < getPeriodCount(); i++) {
      if (getUidOfPeriod(i).equals(uid)) {
        return i;
      }
    }
    return C.INDEX_UNSET;
  }

  @Override
  public Object getUidOfPeriod(int periodIndex) {
    checkElementIndex(periodIndex, getPeriodCount());
    int windowIndex =
        Util.binarySearchFloor(
            periodOffsets, periodIndex, /* inclusive= */ true, /* stayInBounds= */ false);
    int windowPeriodIndex = periodIndex - periodOffsets[windowIndex];
    TimelineWindowDefinition windowDefinition = windowDefinitions[windowIndex];
    return Pair.create(windowDefinition.id, windowPeriodIndex);
  }

  /**
   * Returns a map of ad playback states keyed by the period UID.
   *
   * @param windowIndex The window index of the window to get the map of ad playback states from.
   * @return The map of {@link AdPlaybackState ad playback states}.
   */
  public ImmutableMap<Object, AdPlaybackState> getAdPlaybackStates(int windowIndex) {
    Map<Object, AdPlaybackState> adPlaybackStateMap = new HashMap<>();
    TimelineWindowDefinition windowDefinition = windowDefinitions[windowIndex];
    for (int i = 0; i < windowDefinition.adPlaybackStates.size(); i++) {
      adPlaybackStateMap.put(
          new Pair<>(windowDefinition.id, i), windowDefinition.adPlaybackStates.get(i));
    }
    return ImmutableMap.copyOf(adPlaybackStateMap);
  }

  private static TimelineWindowDefinition[] createDefaultWindowDefinitions(int windowCount) {
    TimelineWindowDefinition[] windowDefinitions = new TimelineWindowDefinition[windowCount];
    for (int i = 0; i < windowCount; i++) {
      windowDefinitions[i] = new TimelineWindowDefinition.Builder().setUid(i).build();
    }
    return windowDefinitions;
  }
}
