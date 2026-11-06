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

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.test.utils.createTestTracks
import androidx.media3.test.utils.createTestTracksWithLanguages
import androidx.media3.test.utils.createVideoFormat
import androidx.media3.ui.compose.testutils.createPlayerWithTracks
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [TrackSelectionState]. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TrackSelectionStateTest {

  @Test
  fun constructor_noTracksOfTargetType_initializesEmptyOptions() {
    val player = createPlayerWithTracks()
    val paramState = TrackSelectionParametersState(player)

    val state = TrackSelectionState(paramState, C.TRACK_TYPE_VIDEO)

    assertThat(state.canSetTrackSelectionParameters).isTrue()
    assertThat(state.tracks).isEqualTo(paramState.tracks)
    assertThat(state.trackSelectionParameters).isEqualTo(paramState.trackSelectionParameters)
    assertThat(state.selectionOptions).isEmpty()
    assertThat(state.selectedOption).isNull()
  }

  @Test
  fun selectionOptions_otherTrackTypesExistButNotTargetType_returnsEmpty() {
    val player =
      createPlayerWithTracks(
        tracks = createTestTracksWithLanguages(MimeTypes.AUDIO_AAC, "en", "fr", "es")
      )
    val paramState = TrackSelectionParametersState(player)
    val state = TrackSelectionState(paramState, C.TRACK_TYPE_VIDEO, TRACK_FILTER_RESOLUTION)

    val options = state.selectionOptions

    assertThat(options).isEmpty()
  }

  @Test
  fun selectionOptions_tracksOfTargetTypeExist_returnsOptions() {
    val player = createPlayerWithTracks(tracks = createTestTracks())
    val paramState = TrackSelectionParametersState(player)
    val state = TrackSelectionState(paramState, C.TRACK_TYPE_VIDEO, TRACK_FILTER_RESOLUTION)

    val options = state.selectionOptions

    assertThat(options).isNotEmpty()
  }

  @Test
  fun selectionOptions_trackFilterNone_returnsEmpty() {
    val state = createTrackSelectionState(trackFilters = 0)

    val options = state.selectionOptions

    assertThat(options).isEmpty()
  }

  @Test
  fun selectionOptions_withResolutionFilter_filtersOptionsByResolution() {
    val tracks =
      createTestTracks(
        videoFormats =
          listOf(
            createVideoFormat(1920, 1080, MimeTypes.VIDEO_H264),
            createVideoFormat(1920, 1080, MimeTypes.VIDEO_H265),
            createVideoFormat(1280, 720, MimeTypes.VIDEO_H264),
            createVideoFormat(854, 480, MimeTypes.VIDEO_H264),
            createVideoFormat(640, 360, MimeTypes.VIDEO_VP9),
          )
      )
    val state = createTrackSelectionState(trackFilters = TRACK_FILTER_RESOLUTION, tracks = tracks)

    val options = state.selectionOptions

    assertThat(options).hasSize(4)
    val resolutions = options.map { it.width to it.height }
    assertThat(resolutions).containsExactly(1920 to 1080, 1280 to 720, 854 to 480, 640 to 360)
  }

  @Test
  fun selectionOptions_withMimeTypeFilter_filtersOptionsByMimeType() {
    val tracks =
      createTestTracks(
        videoFormats =
          listOf(
            createVideoFormat(1920, 1080, MimeTypes.VIDEO_H264),
            createVideoFormat(1280, 720, MimeTypes.VIDEO_H264),
            createVideoFormat(1280, 720, MimeTypes.VIDEO_H265),
            createVideoFormat(640, 360, MimeTypes.VIDEO_VP9),
          )
      )
    val state = createTrackSelectionState(trackFilters = TRACK_FILTER_MIME_TYPE, tracks = tracks)

    val options = state.selectionOptions

    assertThat(options).hasSize(3)
    val mimeTypes = options.map { it.sampleMimeType }
    assertThat(mimeTypes)
      .containsExactly(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265, MimeTypes.VIDEO_VP9)
  }

  @Test
  fun selectionOptions_withAudioLanguageFilter_filtersOptionsByLanguage() {
    val state =
      createTrackSelectionState(
        trackType = C.TRACK_TYPE_AUDIO,
        trackFilters = TRACK_FILTER_LANGUAGE,
        tracks = createTestTracksWithLanguages(MimeTypes.AUDIO_AAC, "en", "fr", "es"),
      )

    val options = state.selectionOptions

    assertThat(options).hasSize(3)
    val languages = options.map { it.language }
    assertThat(languages).containsExactly("en", "fr", "es")
  }

  @Test
  fun selectionOptions_withTextLanguageFilter_filtersOptionsByLanguage() {
    val state =
      createTrackSelectionState(
        trackType = C.TRACK_TYPE_TEXT,
        trackFilters = TRACK_FILTER_LANGUAGE,
        tracks = createTestTracksWithLanguages(MimeTypes.TEXT_VTT, "en", "es"),
      )

    val options = state.selectionOptions

    assertThat(options).hasSize(2)
    val languages = options.map { it.language }
    assertThat(languages).containsExactly("en", "es")
  }

  @Test
  fun selectionOptions_withMultipleFilters_returnsFlattenedCombinedOptions() {
    val tracks =
      createTestTracks(
        videoFormats =
          listOf(
            createVideoFormat(1920, 1080, MimeTypes.VIDEO_H264),
            createVideoFormat(1920, 1080, MimeTypes.VIDEO_H265),
            createVideoFormat(1280, 720, MimeTypes.VIDEO_H264),
            createVideoFormat(1280, 720, MimeTypes.VIDEO_H265),
            createVideoFormat(854, 480, MimeTypes.VIDEO_H264),
            createVideoFormat(854, 480, MimeTypes.VIDEO_H265),
            createVideoFormat(640, 360, MimeTypes.VIDEO_VP9),
          )
      )
    val state =
      createTrackSelectionState(
        trackFilters = TRACK_FILTER_RESOLUTION or TRACK_FILTER_MIME_TYPE,
        tracks = tracks,
      )

    val options = state.selectionOptions

    assertThat(options).hasSize(7)
    val combinations = options.map { (it.width to it.height) to it.sampleMimeType }
    assertThat(combinations)
      .containsExactly(
        (1920 to 1080) to MimeTypes.VIDEO_H264,
        (1920 to 1080) to MimeTypes.VIDEO_H265,
        (1280 to 720) to MimeTypes.VIDEO_H264,
        (1280 to 720) to MimeTypes.VIDEO_H265,
        (854 to 480) to MimeTypes.VIDEO_H264,
        (854 to 480) to MimeTypes.VIDEO_H265,
        (640 to 360) to MimeTypes.VIDEO_VP9,
      )
  }

  @Test
  fun selectionOptions_withMissingFilterMetadata_includesInvalidTracksAsNull() {
    val tracks =
      createTestTracks(
        videoFormats =
          listOf(
            createVideoFormat(1920, 1080),
            createVideoFormat(1280, 720),
            Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build(),
          )
      )
    val state = createTrackSelectionState(trackFilters = TRACK_FILTER_RESOLUTION, tracks = tracks)

    val options = state.selectionOptions

    assertThat(options).hasSize(3)
    val hasNullOption = options.any { it.width == null || it.height == null }
    assertThat(hasNullOption).isTrue()
  }

  @Test
  fun selectionOptions_withUnsupportedTracks_marksOptionsAsUnsupported() {
    val tracks =
      createTestTracks(
        videoFormats = listOf(createVideoFormat(1920, 1080), createVideoFormat(640, 360)),
        videoHandledStates = intArrayOf(C.FORMAT_HANDLED, C.FORMAT_EXCEEDS_CAPABILITIES),
      )
    val state = createTrackSelectionState(tracks = tracks, trackFilters = TRACK_FILTER_RESOLUTION)

    val options = state.selectionOptions
    val option = options.first { it.width == 640 && it.height == 360 }

    assertThat(option.isSupported).isFalse()
  }

  @Test
  fun selectionOptions_withSelectedTracks_marksOptionsAsSelected() {
    val tracks =
      createTestTracks(
        videoFormats =
          listOf(
            createVideoFormat(1920, 1080),
            createVideoFormat(1280, 720),
            createVideoFormat(854, 480),
            createVideoFormat(640, 360),
          ),
        selectedVideoTrackIndex = 0,
      )
    val state = createTrackSelectionState(trackFilters = TRACK_FILTER_RESOLUTION, tracks = tracks)

    val options = state.selectionOptions
    val selectionMap = options.associate { it.width to it.isSelected }

    val expectedMap = mapOf(1920 to true, 1280 to false, 854 to false, 640 to false)
    assertThat(selectionMap).isEqualTo(expectedMap)
  }

  @Test
  fun selectedOption_textNoPreferredLanguagesOrOverrides_selectsAutoOption() {
    val player =
      createPlayerWithTracks(tracks = createTestTracksWithLanguages(MimeTypes.TEXT_VTT, "en"))
    val paramState = TrackSelectionParametersState(player)
    val state =
      TrackSelectionState(
        paramState,
        C.TRACK_TYPE_TEXT,
        TRACK_FILTER_LANGUAGE,
        hasAutoOption = true,
      )

    assertThat(state.selectedOption?.onOffState).isEqualTo(TRACK_SELECTION_AUTO)
  }

  @Test
  fun selectedOption_textWithPreferredLanguages_selectsLanguageOption() {
    val initialParams =
      TrackSelectionParameters.DEFAULT.buildUpon().setPreferredTextLanguage("en").build()
    val player =
      createPlayerWithTracks(
        tracks = createTestTracksWithLanguages(MimeTypes.TEXT_VTT, "en", selectedLanguage = "en"),
        params = initialParams,
      )
    val paramState = TrackSelectionParametersState(player)
    val state =
      TrackSelectionState(
        paramState,
        C.TRACK_TYPE_TEXT,
        TRACK_FILTER_LANGUAGE,
        hasAutoOption = true,
      )

    assertThat(state.selectedOption?.onOffState).isEqualTo(TRACK_SELECTION_ON)
    assertThat(state.selectedOption?.language).isEqualTo("en")
  }

  @Test
  fun selectedOption_audio_withPreferredLanguages_selectsLanguageOption() {
    val initialParams =
      TrackSelectionParameters.DEFAULT.buildUpon().setPreferredAudioLanguage("en").build()
    val player =
      createPlayerWithTracks(
        tracks =
          createTestTracksWithLanguages(MimeTypes.AUDIO_AAC, "en", "fr", selectedLanguage = "en"),
        params = initialParams,
      )
    val paramState = TrackSelectionParametersState(player)
    val state =
      TrackSelectionState(
        paramState,
        C.TRACK_TYPE_AUDIO,
        TRACK_FILTER_LANGUAGE,
        hasAutoOption = true,
      )

    assertThat(state.selectedOption?.onOffState).isEqualTo(TRACK_SELECTION_ON)
    assertThat(state.selectedOption?.language).isEqualTo("en")
  }

  @Test
  fun selectedOption_audio_defaultParams_selectsAutoOption() {
    val player = createPlayerWithTracks(tracks = createTestTracks())
    val paramState = TrackSelectionParametersState(player)
    val state =
      TrackSelectionState(
        paramState,
        C.TRACK_TYPE_AUDIO,
        TRACK_FILTER_LANGUAGE,
        hasAutoOption = true,
      )

    assertThat(state.selectedOption?.onOffState).isEqualTo(TRACK_SELECTION_AUTO)
  }

  @Test
  fun selectedOption_video_withMimeTypeConstraintButOnlyResolutionFilterEnabled_selectsAutoOption() {
    val initialParams =
      TrackSelectionParameters.DEFAULT.buildUpon()
        .setPreferredVideoMimeType(MimeTypes.VIDEO_H264)
        .build()
    val player = createPlayerWithTracks(tracks = createTestTracks(), params = initialParams)
    val paramState = TrackSelectionParametersState(player)
    val state =
      TrackSelectionState(
        paramState,
        C.TRACK_TYPE_VIDEO,
        TRACK_FILTER_RESOLUTION,
        hasAutoOption = true,
      )

    assertThat(state.selectedOption?.onOffState).isEqualTo(TRACK_SELECTION_AUTO)
  }

  @Test
  fun selectedOption_video_withMimeTypeConstraintAndMimeTypeFilterEnabled_selectsMimeTypeOption() {
    val initialParams =
      TrackSelectionParameters.DEFAULT.buildUpon()
        .setPreferredVideoMimeType(MimeTypes.VIDEO_H264)
        .build()
    val tracks =
      createTestTracks(
        videoFormats =
          listOf(
            createVideoFormat(1920, 1080, MimeTypes.VIDEO_H264),
            createVideoFormat(1920, 1080, MimeTypes.VIDEO_H265),
          ),
        selectedVideoTrackIndex = 0,
      )
    val player = createPlayerWithTracks(tracks = tracks, params = initialParams)
    val paramState = TrackSelectionParametersState(player)
    val state =
      TrackSelectionState(
        paramState,
        C.TRACK_TYPE_VIDEO,
        TRACK_FILTER_MIME_TYPE,
        hasAutoOption = true,
      )

    assertThat(state.selectedOption?.onOffState).isEqualTo(TRACK_SELECTION_ON)
    assertThat(state.selectedOption?.sampleMimeType).isEqualTo(MimeTypes.VIDEO_H264)
  }

  @Test
  fun selectedOption_withHasAutoOptionTrue_defaultIsAutoOption() {
    val state =
      createTrackSelectionState(trackFilters = TRACK_FILTER_RESOLUTION, hasAutoOption = true)

    val selectedOption = state.selectedOption

    assertThat(selectedOption).isNotNull()
    assertThat(selectedOption?.onOffState).isEqualTo(TRACK_SELECTION_AUTO)
    assertThat(selectedOption?.isSelected).isTrue()
  }

  @Test
  fun selectedOption_withHasAutoOptionFalse_returnsActiveTrackOption() {
    val tracks =
      createTestTracks(
        videoFormats = listOf(createVideoFormat(1920, 1080), createVideoFormat(1280, 720)),
        selectedVideoTrackIndex = 0,
      )
    val state =
      createTrackSelectionState(
        trackFilters = TRACK_FILTER_RESOLUTION,
        tracks = tracks,
        hasAutoOption = false,
      )

    val selectedOption = state.selectedOption

    assertThat(selectedOption).isNotNull()
    assertThat(selectedOption?.width).isEqualTo(1920)
  }

  @Test
  fun selectedOption_onSelectOption_updatesSelectedOptionProperty() = runComposeUiTest {
    val videoFormats = listOf(createVideoFormat(1920, 1080), createVideoFormat(1280, 720))
    val player = createPlayerWithTracks(tracks = createTestTracks(videoFormats = videoFormats))
    lateinit var state: TrackSelectionState
    setContent {
      state = rememberTrackSelectionState(player, C.TRACK_TYPE_VIDEO, TRACK_FILTER_RESOLUTION)
    }
    val options = state.selectionOptions
    val option1080p = options.first { it.width == 1920 }

    state.selectOption(option1080p)
    player.setTracks(createTestTracks(videoFormats = videoFormats, selectedVideoTrackIndex = 0))
    waitForIdle()

    assertThat(state.selectedOption).isEqualTo(state.selectionOptions.first { it.width == 1920 })
    assertThat(state.selectedOption?.isSelected).isTrue()
  }

  @Test
  fun selectedOption_onExternalParametersUpdate_updatesSync() = runComposeUiTest {
    val videoFormats = listOf(createVideoFormat(1920, 1080), createVideoFormat(1280, 720))
    val player = createPlayerWithTracks(tracks = createTestTracks(videoFormats = videoFormats))
    lateinit var state: TrackSelectionState
    setContent {
      state = rememberTrackSelectionState(player, C.TRACK_TYPE_VIDEO, TRACK_FILTER_RESOLUTION)
    }
    val newParams = TrackSelectionParameters.DEFAULT.buildUpon().setMaxVideoSize(1920, 1080).build()

    player.setTrackSelectionParameters(newParams)
    player.setTracks(createTestTracks(videoFormats = videoFormats, selectedVideoTrackIndex = 0))
    waitForIdle()

    val expectedOption = state.selectionOptions.first { it.width == 1920 }
    assertThat(state.selectedOption).isEqualTo(expectedOption)
    assertThat(state.selectedOption?.isSelected).isTrue()
  }

  @Test
  fun selectOption_offOption_disablesTrackTypeInPlayerParams() {
    val player = createPlayerWithTracks()
    val paramState = TrackSelectionParametersState(player)
    val state = TrackSelectionState(paramState, C.TRACK_TYPE_VIDEO, hasOffOption = true)
    val offOption = state.selectionOptions.first { it.onOffState == TRACK_SELECTION_OFF }

    state.selectOption(offOption)

    assertThat(player.trackSelectionParameters.disabledTrackTypes)
      .containsExactly(C.TRACK_TYPE_VIDEO)
  }

  @Test
  fun selectOption_onOption_enablesTrackTypeInPlayerParams() {
    val initialParams =
      TrackSelectionParameters.DEFAULT.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
        .build()
    val player = createPlayerWithTracks(tracks = createTestTracks(), params = initialParams)
    val paramState = TrackSelectionParametersState(player)
    val state =
      TrackSelectionState(
        paramState,
        C.TRACK_TYPE_VIDEO,
        TRACK_FILTER_RESOLUTION,
        hasOffOption = true,
      )
    val onOption = state.selectionOptions.first { it.onOffState == TRACK_SELECTION_ON }

    state.selectOption(onOption)

    assertThat(player.trackSelectionParameters.disabledTrackTypes)
      .doesNotContain(C.TRACK_TYPE_VIDEO)
  }

  @Test
  fun selectOption_textToAuto_setsSelectTextByDefaultFalseAndClearsOverrides() = runComposeUiTest {
    val initialParams =
      TrackSelectionParameters.DEFAULT.buildUpon()
        .setPreferredTextLanguage("en")
        .setSelectTextByDefault(true)
        .build()
    val player =
      createPlayerWithTracks(
        tracks = createTestTracksWithLanguages(MimeTypes.TEXT_VTT, "en", selectedLanguage = "en"),
        params = initialParams,
      )
    lateinit var state: TrackSelectionState
    setContent {
      state =
        rememberTrackSelectionState(
          player = player,
          trackType = C.TRACK_TYPE_TEXT,
          trackFilters = TRACK_FILTER_LANGUAGE,
          hasAutoOption = true,
        )
    }
    val autoOption = state.selectionOptions.first { it.onOffState == TRACK_SELECTION_AUTO }

    state.selectOption(autoOption)
    waitForIdle()

    assertThat(player.trackSelectionParameters.selectTextByDefault).isFalse()
    assertThat(player.trackSelectionParameters.preferredTextLanguages).isEmpty()
    assertThat(state.selectedOption?.onOffState).isEqualTo(TRACK_SELECTION_AUTO)
  }

  @Test
  fun selectOption_textWithLanguage_setsPreferredTextLanguage() = runComposeUiTest {
    val player =
      createPlayerWithTracks(
        tracks = createTestTracksWithLanguages(MimeTypes.TEXT_VTT, "en", selectedLanguage = "en")
      )
    lateinit var state: TrackSelectionState
    setContent {
      state =
        rememberTrackSelectionState(
          player = player,
          trackType = C.TRACK_TYPE_TEXT,
          trackFilters = TRACK_FILTER_LANGUAGE,
          hasAutoOption = true,
        )
    }
    val onOption = state.selectionOptions.first { it.onOffState == TRACK_SELECTION_ON }

    state.selectOption(onOption)
    waitForIdle()

    assertThat(player.trackSelectionParameters.preferredTextLanguages).containsExactly("en")
    assertThat(player.trackSelectionParameters.selectTextByDefault).isFalse()
    assertThat(state.selectedOption?.onOffState).isEqualTo(TRACK_SELECTION_ON)
    assertThat(state.selectedOption?.language).isEqualTo("en")
  }

  @Test
  fun selectOption_withValidOption_appliesPreferencesToPlayerParams() {
    val tracks =
      createTestTracks(
        videoFormats =
          listOf(
            createVideoFormat(1920, 1080, MimeTypes.VIDEO_H264),
            createVideoFormat(1280, 720, MimeTypes.VIDEO_H265),
          )
      )
    val player = createPlayerWithTracks(tracks = tracks)
    val paramState = TrackSelectionParametersState(player)
    val state =
      TrackSelectionState(
        paramState,
        C.TRACK_TYPE_VIDEO,
        TRACK_FILTER_RESOLUTION or TRACK_FILTER_MIME_TYPE,
      )
    val options = state.selectionOptions
    val option1080pMp4 = options.first {
      it.width == 1920 && it.height == 1080 && it.sampleMimeType == MimeTypes.VIDEO_H264
    }

    state.selectOption(option1080pMp4)

    assertThat(player.trackSelectionParameters.maxVideoWidth).isEqualTo(1920)
    assertThat(player.trackSelectionParameters.maxVideoHeight).isEqualTo(1080)
    assertThat(player.trackSelectionParameters.preferredVideoMimeTypes)
      .containsExactly(MimeTypes.VIDEO_H264)
  }

  @Test
  fun selectOption_optionNotInSelectionOptions_ignoresSelection() {
    val player = createPlayerWithTracks()
    val paramState = TrackSelectionParametersState(player)
    val state = TrackSelectionState(paramState, C.TRACK_TYPE_VIDEO, TRACK_FILTER_RESOLUTION)
    val unknownOption =
      TrackSelectionOption(
        isSupported = true,
        isSelected = false,
        width = 1920,
        height = 1080,
        sampleMimeType = MimeTypes.VIDEO_H264,
      )

    state.selectOption(unknownOption)

    assertThat(player.trackSelectionParameters.maxVideoWidth).isEqualTo(Int.MAX_VALUE)
    assertThat(player.trackSelectionParameters.maxVideoHeight).isEqualTo(Int.MAX_VALUE)
  }

  @Test
  fun selectOption_null_clearsOverridesAndConstraints() {
    val initialParams =
      TrackSelectionParameters.DEFAULT.buildUpon().setPreferredAudioLanguage("en").build()
    val player =
      createPlayerWithTracks(
        tracks =
          createTestTracksWithLanguages(MimeTypes.AUDIO_AAC, "en", "fr", selectedLanguage = "en"),
        params = initialParams,
      )
    val paramState = TrackSelectionParametersState(player)
    val state = TrackSelectionState(paramState, C.TRACK_TYPE_AUDIO, TRACK_FILTER_LANGUAGE)

    state.selectOption(null)

    assertThat(player.trackSelectionParameters.preferredAudioLanguages).isEmpty()
  }

  @Test
  fun rememberTrackSelectionState_nullPlayer_initializesEmptyOptionsAndCommandFalse() =
    runComposeUiTest {
      lateinit var state: TrackSelectionState

      setContent {
        state = rememberTrackSelectionState(null, C.TRACK_TYPE_VIDEO, TRACK_FILTER_RESOLUTION)
      }
      waitForIdle()

      assertThat(state).isNotNull()
      assertThat(state.canSetTrackSelectionParameters).isFalse()
      assertThat(state.selectionOptions).isEmpty()
    }

  @Test
  fun rememberTrackSelectionState_withPlayer_initializesOptionsAndCommandTrue() = runComposeUiTest {
    val player = createPlayerWithTracks(tracks = createTestTracks())
    lateinit var state: TrackSelectionState

    setContent {
      state = rememberTrackSelectionState(player, C.TRACK_TYPE_VIDEO, TRACK_FILTER_RESOLUTION)
    }
    waitForIdle()

    assertThat(state).isNotNull()
    assertThat(state.trackType).isEqualTo(C.TRACK_TYPE_VIDEO)
    assertThat(state.canSetTrackSelectionParameters).isTrue()
    assertThat(state.selectionOptions).isNotEmpty()
  }

  @Test
  fun rememberTrackSelectionState_playerBecomesNullRoundTrip_stateUpdatesCorrectly() =
    runComposeUiTest {
      val player = createPlayerWithTracks(tracks = createTestTracks())
      lateinit var isPlayerNull: MutableState<Boolean>
      lateinit var state: TrackSelectionState
      setContent {
        isPlayerNull = remember { mutableStateOf(false) }
        state =
          rememberTrackSelectionState(
            if (isPlayerNull.value) null else player,
            C.TRACK_TYPE_VIDEO,
            TRACK_FILTER_RESOLUTION,
          )
      }

      assertThat(state.canSetTrackSelectionParameters).isTrue()
      assertThat(state.selectionOptions).isNotEmpty()

      isPlayerNull.value = true
      waitForIdle()
      assertThat(state.canSetTrackSelectionParameters).isFalse()
      assertThat(state.selectionOptions).isEmpty()

      isPlayerNull.value = false
      waitForIdle()
      assertThat(state.canSetTrackSelectionParameters).isTrue()
      assertThat(state.selectionOptions).isNotEmpty()
    }

  private fun createTrackSelectionState(
    trackType: @C.TrackType Int = C.TRACK_TYPE_VIDEO,
    trackFilters: @TrackFilter Int = 0,
    tracks: Tracks = createTestTracks(),
    hasOffOption: Boolean = false,
    hasAutoOption: Boolean = false,
    hasOnOption: Boolean = true,
  ): TrackSelectionState {
    val player = createPlayerWithTracks(tracks = tracks)
    val trackSelectionParametersState = TrackSelectionParametersState(player)
    return TrackSelectionState(
      trackSelectionParametersState,
      trackType,
      trackFilters,
      hasOffOption,
      hasAutoOption,
      hasOnOption,
    )
  }
}
