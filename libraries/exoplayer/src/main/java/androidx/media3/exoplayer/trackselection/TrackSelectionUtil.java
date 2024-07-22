/*
 * Copyright (C) 2018 The Android Open Source Project
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
package androidx.media3.exoplayer.trackselection;

import android.os.SystemClock;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.NullableType;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector.SelectionOverride;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection.Definition;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import java.util.List;

/** Track selection related utility methods. */
@UnstableApi
public final class TrackSelectionUtil {

  private TrackSelectionUtil() {}

  /** Functional interface to create a single adaptive track selection. */
  public interface AdaptiveTrackSelectionFactory {

    /**
     * Creates an adaptive track selection for the provided track selection definition.
     *
     * @param trackSelectionDefinition A {@link Definition} for the track selection.
     * @return The created track selection.
     */
    ExoTrackSelection createAdaptiveTrackSelection(Definition trackSelectionDefinition);
  }

  /**
   * Creates track selections for an array of track selection definitions, with at most one
   * multi-track adaptive selection.
   *
   * @param definitions The list of track selection {@link Definition definitions}. May include null
   *     values.
   * @param adaptiveTrackSelectionFactory A factory for the multi-track adaptive track selection.
   * @return The array of created track selection. For null entries in {@code definitions} returns
   *     null values.
   */
  public static @NullableType ExoTrackSelection[] createTrackSelectionsForDefinitions(
      @NullableType Definition[] definitions,
      AdaptiveTrackSelectionFactory adaptiveTrackSelectionFactory) {
    ExoTrackSelection[] selections = new ExoTrackSelection[definitions.length];
    boolean createdAdaptiveTrackSelection = false;
    for (int i = 0; i < definitions.length; i++) {
      Definition definition = definitions[i];
      if (definition == null) {
        continue;
      }
      if (definition.tracks.length > 1 && !createdAdaptiveTrackSelection) {
        createdAdaptiveTrackSelection = true;
        selections[i] = adaptiveTrackSelectionFactory.createAdaptiveTrackSelection(definition);
      } else {
        selections[i] =
            new FixedTrackSelection(
                definition.group, definition.tracks[0], /* type= */ definition.type);
      }
    }
    return selections;
  }

  /**
   * @deprecated Use {@link DefaultTrackSelector.Parameters.Builder#addOverride} instead.
   */
  @SuppressWarnings("deprecation") // Forwarding to deprecated methods
  @Deprecated
  public static DefaultTrackSelector.Parameters updateParametersWithOverride(
      DefaultTrackSelector.Parameters parameters,
      int rendererIndex,
      TrackGroupArray trackGroupArray,
      boolean isDisabled,
      @Nullable SelectionOverride override) {
    DefaultTrackSelector.Parameters.Builder builder =
        parameters
            .buildUpon()
            .clearSelectionOverrides(rendererIndex)
            .setRendererDisabled(rendererIndex, isDisabled);
    if (override != null) {
      builder.setSelectionOverride(rendererIndex, trackGroupArray, override);
    }
    return builder.build();
  }

  /**
   * Returns the {@link LoadErrorHandlingPolicy.FallbackOptions} with the tracks of the given {@link
   * ExoTrackSelection} and with a single location option indicating that there are no alternative
   * locations available.
   *
   * @param trackSelection The track selection to get the number of total and excluded tracks.
   * @return The {@link LoadErrorHandlingPolicy.FallbackOptions} for the given track selection.
   */
  public static LoadErrorHandlingPolicy.FallbackOptions createFallbackOptions(
      ExoTrackSelection trackSelection) {
    long nowMs = SystemClock.elapsedRealtime();
    int numberOfTracks = trackSelection.length();
    int numberOfExcludedTracks = 0;
    for (int i = 0; i < numberOfTracks; i++) {
      if (trackSelection.isTrackExcluded(i, nowMs)) {
        numberOfExcludedTracks++;
      }
    }
    return new LoadErrorHandlingPolicy.FallbackOptions(
        /* numberOfLocations= */ 1,
        /* numberOfExcludedLocations= */ 0,
        numberOfTracks,
        numberOfExcludedTracks);
  }

  /**
   * Returns {@link Tracks} built from {@link MappingTrackSelector.MappedTrackInfo} and {@link
   * TrackSelection TrackSelections} for each renderer.
   *
   * @param mappedTrackInfo The {@link MappingTrackSelector.MappedTrackInfo}
   * @param selections The track selections, indexed by renderer. A null entry indicates that a
   *     renderer does not have any selected tracks.
   * @return The corresponding {@link Tracks}.
   */
  @SuppressWarnings({"unchecked", "rawtypes"}) // Initialization of array of Lists.
  public static Tracks buildTracks(
      MappingTrackSelector.MappedTrackInfo mappedTrackInfo,
      @NullableType TrackSelection[] selections) {
    List<? extends TrackSelection>[] listSelections = new List[selections.length];
    for (int i = 0; i < selections.length; i++) {
      @Nullable TrackSelection selection = selections[i];
      listSelections[i] = selection != null ? ImmutableList.of(selection) : ImmutableList.of();
    }
    return buildTracks(mappedTrackInfo, listSelections);
  }

  /**
   * Returns {@link Tracks} built from {@link MappingTrackSelector.MappedTrackInfo} and {@link
   * TrackSelection TrackSelections} for each renderer.
   *
   * @param mappedTrackInfo The {@link MappingTrackSelector.MappedTrackInfo}
   * @param selections The track selections, indexed by renderer. Null entries are not permitted. An
   *     empty list indicates that a renderer does not have any selected tracks.
   * @return The corresponding {@link Tracks}.
   */
  public static Tracks buildTracks(
      MappingTrackSelector.MappedTrackInfo mappedTrackInfo,
      List<? extends TrackSelection>[] selections) {
    ImmutableList.Builder<Tracks.Group> trackGroups = new ImmutableList.Builder<>();
    for (int rendererIndex = 0;
        rendererIndex < mappedTrackInfo.getRendererCount();
        rendererIndex++) {
      TrackGroupArray trackGroupArray = mappedTrackInfo.getTrackGroups(rendererIndex);
      List<? extends TrackSelection> rendererTrackSelections = selections[rendererIndex];
      for (int groupIndex = 0; groupIndex < trackGroupArray.length; groupIndex++) {
        TrackGroup trackGroup = trackGroupArray.get(groupIndex);
        boolean adaptiveSupported =
            mappedTrackInfo.getAdaptiveSupport(
                    rendererIndex, groupIndex, /* includeCapabilitiesExceededTracks= */ false)
                != RendererCapabilities.ADAPTIVE_NOT_SUPPORTED;
        @C.FormatSupport int[] trackSupport = new int[trackGroup.length];
        boolean[] selected = new boolean[trackGroup.length];
        for (int trackIndex = 0; trackIndex < trackGroup.length; trackIndex++) {
          trackSupport[trackIndex] =
              mappedTrackInfo.getTrackSupport(rendererIndex, groupIndex, trackIndex);
          boolean isTrackSelected = false;
          for (int i = 0; i < rendererTrackSelections.size(); i++) {
            TrackSelection trackSelection = rendererTrackSelections.get(i);
            if (trackSelection.getTrackGroup().equals(trackGroup)
                && trackSelection.indexOf(trackIndex) != C.INDEX_UNSET) {
              isTrackSelected = true;
              break;
            }
          }
          selected[trackIndex] = isTrackSelected;
        }
        trackGroups.add(new Tracks.Group(trackGroup, adaptiveSupported, trackSupport, selected));
      }
    }
    TrackGroupArray unmappedTrackGroups = mappedTrackInfo.getUnmappedTrackGroups();
    for (int groupIndex = 0; groupIndex < unmappedTrackGroups.length; groupIndex++) {
      TrackGroup trackGroup = unmappedTrackGroups.get(groupIndex);
      @C.FormatSupport int[] trackSupport = new int[trackGroup.length];
      Arrays.fill(trackSupport, C.FORMAT_UNSUPPORTED_TYPE);
      boolean[] selected = new boolean[trackGroup.length];
      trackGroups.add(
          new Tracks.Group(trackGroup, /* adaptiveSupported= */ false, trackSupport, selected));
    }
    return new Tracks(trackGroups.build());
  }
}
