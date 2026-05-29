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
package androidx.media3.exoplayer.dash.manifest;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Iterables.getLast;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.StreamKey;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.offline.FilterableManifest;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * Represents a DASH media presentation description (mpd), as defined by ISO/IEC 23009-1:2014
 * Section 5.3.1.2.
 */
@UnstableApi
public class DashManifest implements FilterableManifest<DashManifest> {

  /**
   * The {@code availabilityStartTime} value in milliseconds since epoch, or {@link C#TIME_UNSET} if
   * not present.
   */
  public final long availabilityStartTimeMs;

  /**
   * The duration of the presentation in milliseconds, or {@link C#TIME_UNSET} if not applicable.
   */
  public final long durationMs;

  /** The {@code minBufferTime} value in milliseconds, or {@link C#TIME_UNSET} if not present. */
  public final long minBufferTimeMs;

  /** Whether the manifest has value "dynamic" for the {@code type} attribute. */
  public final boolean dynamic;

  /**
   * The {@code minimumUpdatePeriod} value in milliseconds, or {@link C#TIME_UNSET} if not
   * applicable.
   */
  public final long minUpdatePeriodMs;

  /**
   * The {@code timeShiftBufferDepth} value in milliseconds, or {@link C#TIME_UNSET} if not present.
   */
  public final long timeShiftBufferDepthMs;

  /**
   * The {@code suggestedPresentationDelay} value in milliseconds, or {@link C#TIME_UNSET} if not
   * present.
   */
  public final long suggestedPresentationDelayMs;

  /**
   * The {@code publishTime} value in milliseconds since epoch, or {@link C#TIME_UNSET} if not
   * present.
   */
  public final long publishTimeMs;

  /**
   * The {@link UtcTimingElement}, or null if not present. Defined in DVB A168:7/2016, Section
   * 4.7.2.
   */
  @Nullable public final UtcTimingElement utcTiming;

  /** The {@link ServiceDescriptionElement}, or null if not present. */
  @Nullable public final ServiceDescriptionElement serviceDescription;

  /** The {@link ProgramInformation}, or null if not present. */
  @Nullable public final ProgramInformation programInformation;

  private final List<Period> periods;

  /**
   * @deprecated Use {@link #locations} instead.
   */
  @Deprecated @Nullable public final Uri location;

  /** The locations to request future updates of the DASH media presentation description (mpd). */
  public final ImmutableList<Location> locations;

  /**
   * @deprecated Use {@link #DashManifest(long, long, long, boolean, long, long, long, long,
   *     ProgramInformation, UtcTimingElement, ServiceDescriptionElement, List, List)} instead.
   */
  @Deprecated
  public DashManifest(
      long availabilityStartTimeMs,
      long durationMs,
      long minBufferTimeMs,
      boolean dynamic,
      long minUpdatePeriodMs,
      long timeShiftBufferDepthMs,
      long suggestedPresentationDelayMs,
      long publishTimeMs,
      @Nullable ProgramInformation programInformation,
      @Nullable UtcTimingElement utcTiming,
      @Nullable ServiceDescriptionElement serviceDescription,
      @Nullable Uri location,
      List<Period> periods) {
    this(
        availabilityStartTimeMs,
        durationMs,
        minBufferTimeMs,
        dynamic,
        minUpdatePeriodMs,
        timeShiftBufferDepthMs,
        suggestedPresentationDelayMs,
        publishTimeMs,
        programInformation,
        utcTiming,
        serviceDescription,
        periods,
        location == null
            ? ImmutableList.of()
            : ImmutableList.of(new Location(location.toString())));
  }

  public DashManifest(
      long availabilityStartTimeMs,
      long durationMs,
      long minBufferTimeMs,
      boolean dynamic,
      long minUpdatePeriodMs,
      long timeShiftBufferDepthMs,
      long suggestedPresentationDelayMs,
      long publishTimeMs,
      @Nullable ProgramInformation programInformation,
      @Nullable UtcTimingElement utcTiming,
      @Nullable ServiceDescriptionElement serviceDescription,
      List<Period> periods,
      List<Location> locations) {
    this.availabilityStartTimeMs = availabilityStartTimeMs;
    this.durationMs = durationMs;
    this.minBufferTimeMs = minBufferTimeMs;
    this.dynamic = dynamic;
    this.minUpdatePeriodMs = minUpdatePeriodMs;
    this.timeShiftBufferDepthMs = timeShiftBufferDepthMs;
    this.suggestedPresentationDelayMs = suggestedPresentationDelayMs;
    this.publishTimeMs = publishTimeMs;
    this.programInformation = programInformation;
    this.utcTiming = utcTiming;
    this.serviceDescription = serviceDescription;
    this.periods = periods == null ? Collections.emptyList() : periods;
    this.locations = locations == null ? ImmutableList.of() : ImmutableList.copyOf(locations);
    this.location =
        locations == null || locations.isEmpty() ? null : Uri.parse(getLast(locations).url);
  }

  public final int getPeriodCount() {
    return periods.size();
  }

  public final Period getPeriod(int index) {
    return periods.get(index);
  }

  public final long getPeriodDurationMs(int index) {
    return index == periods.size() - 1
        ? (durationMs == C.TIME_UNSET ? C.TIME_UNSET : (durationMs - periods.get(index).startMs))
        : (periods.get(index + 1).startMs - periods.get(index).startMs);
  }

  public final long getPeriodDurationUs(int index) {
    return Util.msToUs(getPeriodDurationMs(index));
  }

  @Override
  public final DashManifest copy(List<StreamKey> streamKeys) {
    LinkedList<StreamKey> keys = new LinkedList<>(streamKeys);
    Collections.sort(keys);
    keys.add(new StreamKey(-1, -1, -1)); // Add a stopper key to the end

    ArrayList<Period> copyPeriods = new ArrayList<>();
    long shiftMs = 0;
    for (int periodIndex = 0; periodIndex < getPeriodCount(); periodIndex++) {
      if (checkNotNull(keys.peek()).periodIndex != periodIndex) {
        // No representations selected in this period.
        long periodDurationMs = getPeriodDurationMs(periodIndex);
        if (periodDurationMs != C.TIME_UNSET) {
          shiftMs += periodDurationMs;
        }
      } else {
        Period period = getPeriod(periodIndex);
        ArrayList<AdaptationSet> copyAdaptationSets =
            copyAdaptationSets(period.adaptationSets, keys);
        Period copiedPeriod =
            new Period(
                period.id, period.startMs - shiftMs, copyAdaptationSets, period.eventStreams);
        copyPeriods.add(copiedPeriod);
      }
    }
    long newDuration = durationMs != C.TIME_UNSET ? durationMs - shiftMs : C.TIME_UNSET;
    return new DashManifest(
        availabilityStartTimeMs,
        newDuration,
        minBufferTimeMs,
        dynamic,
        minUpdatePeriodMs,
        timeShiftBufferDepthMs,
        suggestedPresentationDelayMs,
        publishTimeMs,
        programInformation,
        utcTiming,
        serviceDescription,
        copyPeriods,
        locations);
  }

  private static ArrayList<AdaptationSet> copyAdaptationSets(
      List<AdaptationSet> adaptationSets, LinkedList<StreamKey> keys) {
    StreamKey key = checkNotNull(keys.poll());
    int periodIndex = key.periodIndex;
    ArrayList<AdaptationSet> copyAdaptationSets = new ArrayList<>();
    do {
      int adaptationSetIndex = key.groupIndex;
      AdaptationSet adaptationSet = adaptationSets.get(adaptationSetIndex);

      List<Representation> representations = adaptationSet.representations;
      ArrayList<Representation> copyRepresentations = new ArrayList<>();
      do {
        Representation representation = representations.get(key.streamIndex);
        copyRepresentations.add(representation);
        key = checkNotNull(keys.poll());
      } while (key.periodIndex == periodIndex && key.groupIndex == adaptationSetIndex);

      copyAdaptationSets.add(
          new AdaptationSet(
              adaptationSet.id,
              adaptationSet.type,
              copyRepresentations,
              adaptationSet.accessibilityDescriptors,
              adaptationSet.essentialProperties,
              adaptationSet.supplementalProperties));
    } while (key.periodIndex == periodIndex);
    // Add back the last key which doesn't belong to the period being processed
    keys.addFirst(key);
    return copyAdaptationSets;
  }
}
