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
package androidx.media3.demo.session

import androidx.annotation.OptIn
import androidx.media3.cast.CastTrackSelector
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import com.google.common.collect.ImmutableSet

/**
 * A [CastTrackSelector] for the session demo app that supports audio and text track selection.
 *
 * This selector handles:
 * - Applying [TrackSelectionOverride]s and [TrackSelectionParameters.disabledTrackTypes] selected
 *   by the user in the UI while casting.
 * - Preserving active non-audio and non-text tracks (such as video) when calling
 *   `RemoteMediaClient.setActiveMediaTracks`, which replaces all active tracks on the receiver.
 * - Mapping track selection overrides from local `ExoPlayer` playback to matching Cast receiver
 *   [TrackGroup]s (by language and track label/name) when playback transfers to Cast.
 * - Keeping [TrackSelectionParameters] in sync with receiver-side track changes and queue item
 *   transitions.
 */
@OptIn(UnstableApi::class)
internal class DemoCastTrackSelector : CastTrackSelector() {
  /**
   * Tracks all Cast [TrackGroup]s seen across items so overrides from previous Cast queue items are
   * not mistaken for local `ExoPlayer` overrides when the receiver advances to a new item.
   */
  private val seenCastTrackGroups = mutableSetOf<TrackGroup>()

  override fun evaluate(request: CastTrackSelectorRequest): CastTrackSelectorResult {
    val isReceiverUpdate =
      request.trackSelectionRequestReason == TRACK_SELECTION_REQUEST_REASON_RECEIVER_UPDATE
    // Overrides not in request.trackGroupList or seenCastTrackGroups originated from local
    // playback (ExoPlayer) and need to be mapped to Cast TrackGroups on transfer.
    val tracksSelectedLocally =
      request.trackSelectionParameters.overrides.keys.any {
        (it.type == C.TRACK_TYPE_AUDIO || it.type == C.TRACK_TYPE_TEXT) &&
          it !in request.trackGroupList &&
          it !in seenCastTrackGroups
      }

    seenCastTrackGroups.addAll(request.trackGroupList)

    if (isReceiverUpdate && !tracksSelectedLocally) {
      return syncParametersWithReceiverState(request)
    }

    val parametersBuilder = request.trackSelectionParameters.buildUpon()
    val selections = ImmutableSet.builder<TrackGroup>()
    val disabledTypes = request.trackSelectionParameters.disabledTrackTypes

    // RemoteMediaClient.setActiveMediaTracks replaces all active tracks on the receiver, so
    // keep active non-audio/non-text tracks (e.g. video) selected.
    for (trackGroup in request.currentlySelectedTrackGroups) {
      if (
        trackGroup.type != C.TRACK_TYPE_AUDIO &&
          trackGroup.type != C.TRACK_TYPE_TEXT &&
          trackGroup.type !in disabledTypes
      ) {
        selections.add(trackGroup)
      }
    }

    if (C.TRACK_TYPE_AUDIO !in disabledTypes) {
      val audioTrack =
        resolveOverrideForType(request, C.TRACK_TYPE_AUDIO, parametersBuilder)
          ?: request.currentlySelectedTrackGroups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO }
          ?: request.trackGroupList.firstOrNull { it.type == C.TRACK_TYPE_AUDIO }
      if (audioTrack != null) {
        selections.add(audioTrack)
      }
    }

    if (C.TRACK_TYPE_TEXT !in disabledTypes) {
      val textTrack =
        resolveOverrideForType(request, C.TRACK_TYPE_TEXT, parametersBuilder)
          ?: if (request.trackSelectionParameters.ignoredTextSelectionFlags == 0) {
            request.currentlySelectedTrackGroups.firstOrNull { it.type == C.TRACK_TYPE_TEXT }
          } else {
            null
          }
      if (textTrack != null) {
        parametersBuilder.setIgnoredTextSelectionFlags(0)
        selections.add(textTrack)
      }
    }

    return request
      .buildResultUpon()
      .setSelections(selections.build())
      .setTrackSelectionParameters(parametersBuilder.build())
      .build()
  }

  /**
   * Synchronizes [TrackSelectionParameters] with the Cast receiver's active tracks without changing
   * the receiver's track selection.
   *
   * Clears any [TrackSelectionOverride] whose [TrackGroup] is no longer active on the receiver (for
   * example, after the receiver advances to the next queue item or switches tracks), and ensures
   * [TrackSelectionParameters.ignoredTextSelectionFlags] is reset to `0` when a text track is
   * active on the receiver.
   */
  private fun syncParametersWithReceiverState(
    request: CastTrackSelectorRequest
  ): CastTrackSelectorResult {
    val parametersBuilder = request.trackSelectionParameters.buildUpon()
    for (override in request.trackSelectionParameters.overrides.values) {
      if (override.mediaTrackGroup !in request.currentlySelectedTrackGroups) {
        parametersBuilder.clearOverridesOfType(override.type)
      }
    }
    if (request.currentlySelectedTrackGroups.any { it.type == C.TRACK_TYPE_TEXT }) {
      // Ensure "None" in PlayerControlView (which sets ~C.SELECTION_FLAG_FORCED) will trigger a
      // parameter change if the user turns off receiver-selected subtitles.
      parametersBuilder.setIgnoredTextSelectionFlags(0)
    }
    return request.buildResultUpon().setTrackSelectionParameters(parametersBuilder.build()).build()
  }

  /**
   * Resolves the [TrackSelectionOverride] for [trackType] against the current Cast request and
   * updates [parametersBuilder] accordingly.
   *
   * - If the override already targets a [TrackGroup] in [CastTrackSelectorRequest.trackGroupList],
   *   returns that [TrackGroup].
   * - If the override targets a [TrackGroup] from a previous Cast queue item
   *   ([seenCastTrackGroups]), clears the stale override and returns `null`.
   * - Otherwise, treats the override as originating from local `ExoPlayer` playback, attempts to
   *   map its [Format] to a matching Cast [TrackGroup] via [findMatchingCastTrackGroup], and
   *   replaces or clears the override in [parametersBuilder].
   */
  private fun resolveOverrideForType(
    request: CastTrackSelectorRequest,
    trackType: @C.TrackType Int,
    parametersBuilder: TrackSelectionParameters.Builder,
  ): TrackGroup? {
    val override =
      request.trackSelectionParameters.overrides.values.firstOrNull { it.type == trackType }
        ?: return null
    if (override.trackIndices.isEmpty()) {
      parametersBuilder.clearOverridesOfType(trackType)
      return null
    }

    if (override.mediaTrackGroup in request.trackGroupList) {
      return override.mediaTrackGroup
    }
    if (override.mediaTrackGroup in seenCastTrackGroups) {
      parametersBuilder.clearOverridesOfType(trackType)
      return null
    }

    val localFormat = override.mediaTrackGroup.getFormat(override.trackIndices.first())
    val matchedCastTrack = findMatchingCastTrackGroup(request, trackType, localFormat)
    if (matchedCastTrack != null) {
      parametersBuilder.setOverrideForType(
        TrackSelectionOverride(matchedCastTrack, /* trackIndex= */ 0)
      )
    } else {
      parametersBuilder.clearOverridesOfType(trackType)
    }
    return matchedCastTrack
  }

  /**
   * Finds the best matching Cast [TrackGroup] of the given [trackType] for a [localFormat] selected
   * during local `ExoPlayer` playback, or returns `null` if no match is found.
   *
   * Matches by normalized [Format.language] first and disambiguates multiple tracks in the same
   * language by comparing [Format.label] against the Cast track's `MediaTrack.getName()`, falling
   * back to a currently active matching track or the first language match.
   */
  private fun findMatchingCastTrackGroup(
    request: CastTrackSelectorRequest,
    trackType: @C.TrackType Int,
    localFormat: Format,
  ): TrackGroup? {
    val targetLanguage = localFormat.language?.let(Util::normalizeLanguageCode)
    val targetLabel = localFormat.label
    if (targetLanguage == null && targetLabel == null) {
      return null
    }
    val languageMatchIndices =
      request.trackGroupList.indices.filter { index ->
        val trackGroup = request.trackGroupList[index]
        trackGroup.type == trackType &&
          (targetLanguage == null ||
            trackGroup.getFormat(0).language?.let(Util::normalizeLanguageCode) == targetLanguage)
      }
    if (languageMatchIndices.isEmpty()) {
      return null
    }
    if (targetLabel != null) {
      languageMatchIndices
        .firstOrNull { index -> request.mediaTracks[index].name == targetLabel }
        ?.let {
          return request.trackGroupList[it]
        }
    }
    if (targetLanguage == null) {
      return null
    }
    val languageMatches = languageMatchIndices.map { request.trackGroupList[it] }
    return languageMatches.firstOrNull { it in request.currentlySelectedTrackGroups }
      ?: languageMatches.first()
  }
}
