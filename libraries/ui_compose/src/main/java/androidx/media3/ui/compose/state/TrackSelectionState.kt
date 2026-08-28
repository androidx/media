/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.media3.ui.compose.state

import androidx.annotation.IntDef
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi

private const val TAG = "TrackSelectionState"

/**
 * Represents the track selection state for a specific track type.
 *
 * @see TRACK_SELECTION_OFF
 * @see TRACK_SELECTION_AUTO
 * @see TRACK_SELECTION_ON
 */
@UnstableApi
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.TYPE)
@MustBeDocumented
@IntDef(TRACK_SELECTION_OFF, TRACK_SELECTION_AUTO, TRACK_SELECTION_ON)
annotation class OnOffState

/** The track type is explicitly disabled. */
@UnstableApi const val TRACK_SELECTION_OFF = 0
/**
 * No specific preferences or constraints are applied for the active filters (the player chooses
 * automatically).
 */
@UnstableApi const val TRACK_SELECTION_AUTO = 1
/** Specific preferences or constraints are applied for the active filters. */
@UnstableApi const val TRACK_SELECTION_ON = 2

/**
 * Supported filters for selecting specific tracks.
 *
 * @see TRACK_FILTER_LANGUAGE
 * @see TRACK_FILTER_RESOLUTION
 * @see TRACK_FILTER_MIME_TYPE
 */
@UnstableApi
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.TYPE)
@MustBeDocumented
@IntDef(
  flag = true,
  value = [TRACK_FILTER_LANGUAGE, TRACK_FILTER_RESOLUTION, TRACK_FILTER_MIME_TYPE],
)
annotation class TrackFilter

/** Filter for selecting track language (applies to audio or text depending on track type). */
@UnstableApi const val TRACK_FILTER_LANGUAGE = 1 shl 0
/** Filter for selecting video resolution (`width` and `height`). */
@UnstableApi const val TRACK_FILTER_RESOLUTION = 1 shl 1
/** Filter for selecting video MIME type (`sampleMimeType`). */
@UnstableApi const val TRACK_FILTER_MIME_TYPE = 1 shl 2

/**
 * Represents a unique track configuration available for selection, based on the active
 * [TrackFilter]s.
 *
 * This configuration encapsulates specific track properties (like language or resolution) and
 * indicates whether it is currently supported and selected by the player.
 *
 * @property isSupported Whether the option is supported by the device.
 * @property isSelected Whether the option is currently selected.
 * @property language The language associated with this option, or `null`.
 * @property width The video width associated with this option, or `null`.
 * @property height The video height associated with this option, or `null`.
 * @property sampleMimeType The sample MIME type associated with this option, or `null`.
 * @property onOffState The [OnOffState] representing this option.
 */
@UnstableApi
class TrackSelectionOption(
  val isSupported: Boolean,
  val isSelected: Boolean,
  val language: String? = null,
  val width: Int? = null,
  val height: Int? = null,
  val sampleMimeType: String? = null,
  val onOffState: @OnOffState Int = TRACK_SELECTION_ON,
) {
  internal fun copyInternal(
    isSupported: Boolean = this.isSupported,
    isSelected: Boolean = this.isSelected,
  ): TrackSelectionOption =
    TrackSelectionOption(
      isSupported,
      isSelected,
      language,
      width,
      height,
      sampleMimeType,
      onOffState,
    )

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is TrackSelectionOption) return false
    return isSupported == other.isSupported &&
      isSelected == other.isSelected &&
      language == other.language &&
      width == other.width &&
      height == other.height &&
      sampleMimeType == other.sampleMimeType &&
      onOffState == other.onOffState
  }

  override fun hashCode(): Int {
    var result = isSupported.hashCode()
    result = 31 * result + isSelected.hashCode()
    result = 31 * result + (language?.hashCode() ?: 0)
    result = 31 * result + (width?.hashCode() ?: 0)
    result = 31 * result + (height?.hashCode() ?: 0)
    result = 31 * result + (sampleMimeType?.hashCode() ?: 0)
    result = 31 * result + onOffState
    return result
  }

  override fun toString(): String {
    return "TrackSelectionOption(isSupported=$isSupported, isSelected=$isSelected, language=$language, width=$width, height=$height, sampleMimeType=$sampleMimeType, onOffState=$onOffState)"
  }
}

/**
 * Remembers a [TrackSelectionState] for the given [trackType] and [trackFilters].
 *
 * @param player The [Player] instance to observe and update track selection state.
 * @param trackType The type of track to manage (e.g., [C.TRACK_TYPE_VIDEO]).
 * @param trackFilters The bitmask of filters to apply to the track selection.
 * @param hasOffOption Whether the UI includes an "Off" option.
 * @param hasAutoOption Whether the UI includes an "Auto" option.
 * @param hasOnOption Whether the UI includes explicit track options.
 * @return A [TrackSelectionState] instance.
 * @see [TrackSelectionState]
 */
@UnstableApi
@Composable
fun rememberTrackSelectionState(
  player: Player?,
  trackType: @C.TrackType Int,
  trackFilters: @TrackFilter Int = 0,
  hasOffOption: Boolean = false,
  hasAutoOption: Boolean = false,
  hasOnOption: Boolean = true,
): TrackSelectionState {
  val internalTrackSelectionParametersState = rememberTrackSelectionParametersState(player)
  return remember(
    internalTrackSelectionParametersState,
    trackType,
    trackFilters,
    hasOffOption,
    hasAutoOption,
    hasOnOption,
  ) {
    TrackSelectionState(
      internalTrackSelectionParametersState,
      trackType,
      trackFilters,
      hasOffOption,
      hasAutoOption,
      hasOnOption,
    )
  }
}

/**
 * A state object that manages the track selection for a specific [trackType].
 *
 * Availability of track selection can be determined by checking [canSetTrackSelectionParameters]
 * and whether [selectionOptions] has sufficient options for the desired UI.
 *
 * @property trackType The type of track this state manages (e.g., [C.TRACK_TYPE_VIDEO]).
 * @property trackFilters The bitmask of filters supported for selecting tracks of this type.
 * @property hasOffOption Whether the UI includes an "Off" option.
 * @property hasAutoOption Whether the UI includes an "Auto" option.
 * @property hasOnOption Whether the UI includes explicit track options.
 * @property tracks The current [Tracks] available in the player.
 * @property trackSelectionParameters The current [TrackSelectionParameters] acting on the player.
 * @property canSetTrackSelectionParameters Whether [Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS]
 *   is available on the player.
 * @property selectionOptions Available [TrackSelectionOption]s based on [trackFilters] and options.
 * @property selectedOption The selected option, or null if no option is selected.
 */
@UnstableApi
class TrackSelectionState(
  private val internalTrackSelectionParametersState: TrackSelectionParametersState,
  val trackType: @C.TrackType Int,
  val trackFilters: @TrackFilter Int = 0,
  val hasOffOption: Boolean = false,
  val hasAutoOption: Boolean = false,
  val hasOnOption: Boolean = true,
) {

  val tracks: Tracks
    get() = internalTrackSelectionParametersState.tracks

  val trackSelectionParameters: TrackSelectionParameters
    get() = internalTrackSelectionParametersState.trackSelectionParameters

  val canSetTrackSelectionParameters: Boolean
    get() = internalTrackSelectionParametersState.canSetTrackSelectionParameters

  val selectionOptions: List<TrackSelectionOption> by derivedStateOf {
    val trackSelectionParams = internalTrackSelectionParametersState.trackSelectionParameters
    val currentOnOffState = getCurrentOnOffState(trackSelectionParams)
    buildList {
      if (hasOffOption) {
        add(
          TrackSelectionOption(
            isSupported = true,
            isSelected = (currentOnOffState == TRACK_SELECTION_OFF),
            onOffState = TRACK_SELECTION_OFF,
          )
        )
      }

      if (hasAutoOption) {
        add(
          TrackSelectionOption(
            isSupported = true,
            isSelected = (currentOnOffState == TRACK_SELECTION_AUTO),
            onOffState = TRACK_SELECTION_AUTO,
          )
        )
      }

      if (hasOnOption && trackFilters != 0) {
        val optionsMap = mutableMapOf<OptionKey, TrackSelectionOption>()
        val filteredGroups = getFilteredGroups()
        for (group in filteredGroups) {
          for (trackIndex in 0 until group.length) {
            val format = group.getTrackFormat(trackIndex)

            val language = format.language.takeIf { isFilterEnabled(TRACK_FILTER_LANGUAGE) }
            val width =
              format.width.takeIf {
                isFilterEnabled(TRACK_FILTER_RESOLUTION) && it != Format.NO_VALUE
              }
            val height =
              format.height.takeIf {
                isFilterEnabled(TRACK_FILTER_RESOLUTION) && it != Format.NO_VALUE
              }
            val sampleMimeType =
              format.sampleMimeType.takeIf { isFilterEnabled(TRACK_FILTER_MIME_TYPE) }

            val isSupported = group.isTrackSupported(trackIndex)
            val isSelected =
              (currentOnOffState == TRACK_SELECTION_ON || !hasAutoOption) &&
                group.isTrackSelected(trackIndex)
            val key = OptionKey(language, width, height, sampleMimeType)

            optionsMap[key] =
              optionsMap[key]?.let {
                it.copyInternal(
                  isSupported = it.isSupported || isSupported,
                  isSelected = it.isSelected || isSelected,
                )
              }
                ?: TrackSelectionOption(
                  isSupported = isSupported,
                  isSelected = isSelected,
                  language = language,
                  width = width,
                  height = height,
                  sampleMimeType = sampleMimeType,
                  onOffState = TRACK_SELECTION_ON,
                )
          }
        }
        addAll(optionsMap.values)
      }
    }
  }

  val selectedOption: TrackSelectionOption? by derivedStateOf {
    selectionOptions.firstOrNull { it.isSelected }
  }

  /**
   * Selects an option for this track type.
   *
   * @param option The [TrackSelectionOption] to select, or `null` to clear the selection (treated
   *   as [TRACK_SELECTION_AUTO]).
   */
  @UnstableApi
  fun selectOption(option: TrackSelectionOption?) {
    if (option != null && !selectionOptions.contains(option)) {
      Log.w(TAG, "Ignoring selection of option not present in selectionOptions: $option")
      return
    }

    val newParams =
      internalTrackSelectionParametersState.trackSelectionParameters
        .buildUpon()
        .apply {
          if (option == null || option.onOffState == TRACK_SELECTION_AUTO) {
            setTrackTypeDisabled(trackType, false)
            clearOverridesAndConstraints(this)
          } else {
            when (option.onOffState) {
              TRACK_SELECTION_OFF -> {
                setTrackTypeDisabled(trackType, true)
              }
              TRACK_SELECTION_ON -> {
                setTrackTypeDisabled(trackType, false)
                clearOverridesOfType(trackType)

                if (isFilterEnabled(TRACK_FILTER_LANGUAGE)) {
                  if (trackType == C.TRACK_TYPE_AUDIO) {
                    if (option.language != null) {
                      setPreferredAudioLanguages(option.language)
                    } else {
                      setPreferredAudioLanguages()
                    }
                  } else if (trackType == C.TRACK_TYPE_TEXT) {
                    if (option.language != null) {
                      setPreferredTextLanguages(option.language).setSelectTextByDefault(false)
                    } else {
                      setPreferredTextLanguages().setSelectTextByDefault(true)
                    }
                  }
                }

                if (isFilterEnabled(TRACK_FILTER_MIME_TYPE)) {
                  if (option.sampleMimeType != null) {
                    setPreferredVideoMimeTypes(option.sampleMimeType)
                  } else {
                    setPreferredVideoMimeTypes()
                  }
                }

                if (isFilterEnabled(TRACK_FILTER_RESOLUTION)) {
                  // TODO: Continuous dimensions like resolution are currently treated as
                  // max constraints (`setMaxVideoSize`). Consider adding an option on
                  // TrackSelectionState to configure whether resolution or channel count
                  // should act as max, min, or exact constraints.
                  setMaxVideoSize(option.width ?: Int.MAX_VALUE, option.height ?: Int.MAX_VALUE)
                }
              }
            }
          }
        }
        .build()

    internalTrackSelectionParametersState.updateTrackSelectionParameters(newParams)
  }

  private fun getCurrentOnOffState(params: TrackSelectionParameters): @OnOffState Int {
    if (params.disabledTrackTypes.contains(trackType)) {
      return TRACK_SELECTION_OFF
    }
    return if (hasOverridesOrConstraints(params)) {
      TRACK_SELECTION_ON
    } else {
      TRACK_SELECTION_AUTO
    }
  }

  private fun hasOverridesOrConstraints(params: TrackSelectionParameters): Boolean {
    if (getFilteredGroups().any { params.overrides.containsKey(it.mediaTrackGroup) }) {
      return true
    }
    return when (trackType) {
      C.TRACK_TYPE_AUDIO -> {
        isFilterEnabled(TRACK_FILTER_LANGUAGE) && params.preferredAudioLanguages.isNotEmpty()
      }
      C.TRACK_TYPE_TEXT -> {
        isFilterEnabled(TRACK_FILTER_LANGUAGE) &&
          (params.selectTextByDefault || params.preferredTextLanguages.isNotEmpty())
      }
      C.TRACK_TYPE_VIDEO -> {
        val hasResolutionConstraint =
          isFilterEnabled(TRACK_FILTER_RESOLUTION) &&
            (params.maxVideoWidth != Int.MAX_VALUE || params.maxVideoHeight != Int.MAX_VALUE)
        val hasMimeTypeConstraint =
          isFilterEnabled(TRACK_FILTER_MIME_TYPE) && params.preferredVideoMimeTypes.isNotEmpty()
        hasResolutionConstraint || hasMimeTypeConstraint
      }
      else -> false
    }
  }

  private fun clearOverridesAndConstraints(builder: TrackSelectionParameters.Builder) {
    builder.clearOverridesOfType(trackType)
    when (trackType) {
      C.TRACK_TYPE_AUDIO -> {
        if (isFilterEnabled(TRACK_FILTER_LANGUAGE)) {
          builder.setPreferredAudioLanguages()
        }
      }
      C.TRACK_TYPE_TEXT -> {
        if (isFilterEnabled(TRACK_FILTER_LANGUAGE)) {
          builder.setPreferredTextLanguages().setSelectTextByDefault(false)
        }
      }
      C.TRACK_TYPE_VIDEO -> {
        if (isFilterEnabled(TRACK_FILTER_RESOLUTION)) {
          builder.setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
        }
        if (isFilterEnabled(TRACK_FILTER_MIME_TYPE)) {
          builder.setPreferredVideoMimeTypes()
        }
      }
    }
  }

  private fun isFilterEnabled(filter: @TrackFilter Int): Boolean = (trackFilters and filter) != 0

  private fun getFilteredGroups(): Sequence<Tracks.Group> =
    internalTrackSelectionParametersState.tracks.groups.asSequence().filter { it.type == trackType }

  private data class OptionKey(
    val language: String?,
    val width: Int?,
    val height: Int?,
    val sampleMimeType: String?,
  )
}
