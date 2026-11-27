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
@file:Suppress("unused_parameter", "unused_variable", "unused", "CheckReturnValue")

package androidx.media3.docsamples.exoplayer

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

// Snippets for the track selection developer guide.

object TrackSelectionKt {

  fun addTracksListener(player: Player) {
    // [START add_tracks_listener]
    player.addListener(
      object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
          // Update UI using current tracks.
        }
      }
    )
    // [END add_tracks_listener]
  }

  fun obtainTrackDetails(tracks: Tracks) {
    // [START obtain_track_details]
    for (trackGroup in tracks.groups) {
      // Group level information.
      val trackType = trackGroup.type
      val trackInGroupIsSelected = trackGroup.isSelected
      val trackInGroupIsSupported = trackGroup.isSupported
      for (i in 0 until trackGroup.length) {
        // Individual track information.
        val isSupported = trackGroup.isTrackSupported(i)
        val isSelected = trackGroup.isTrackSelected(i)
        val trackFormat = trackGroup.getTrackFormat(i)
      }
    }
    // [END obtain_track_details]
  }

  fun setTrackSelectionParameters(player: Player) {
    // [START set_track_selection_parameters]
    player.trackSelectionParameters =
      player.trackSelectionParameters
        .buildUpon()
        .setMaxVideoSizeSd()
        .setPreferredAudioLanguage("hu")
        .build()
    // [END set_track_selection_parameters]
  }

  fun setTrackSelectionOverrides(player: Player, audioTrackGroup: Tracks.Group) {
    // [START set_track_selection_overrides]
    player.trackSelectionParameters =
      player.trackSelectionParameters
        .buildUpon()
        .setOverrideForType(
          TrackSelectionOverride(audioTrackGroup.mediaTrackGroup, /* trackIndex= */ 0)
        )
        .build()
    // [END set_track_selection_overrides]
  }

  fun disableTrackTypes(player: Player) {
    // [START disable_track_types]
    player.trackSelectionParameters =
      player.trackSelectionParameters
        .buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, /* disabled= */ true)
        .build()
    // [END disable_track_types]
  }

  fun disableTrackGroups(player: Player, disabledTrackGroup: Tracks.Group) {
    // [START disable_track_groups]
    player.trackSelectionParameters =
      player.trackSelectionParameters
        .buildUpon()
        .addOverride(
          TrackSelectionOverride(disabledTrackGroup.mediaTrackGroup, /* trackIndices= */ listOf())
        )
        .build()
    // [END disable_track_groups]
  }

  @OptIn(UnstableApi::class)
  fun setTrackSelector(context: Context) {
    // [START set_track_selector]
    val trackSelector = DefaultTrackSelector(context)
    val player = ExoPlayer.Builder(context).setTrackSelector(trackSelector).build()
    // [END set_track_selector]
  }

  @OptIn(UnstableApi::class)
  fun setTrackSelectorParameters(trackSelector: DefaultTrackSelector) {
    // [START set_track_selector_parameters]
    trackSelector.setParameters(
      trackSelector.buildUponParameters().setAllowVideoMixedMimeTypeAdaptiveness(true)
    )
    // [END set_track_selector_parameters]
  }

  @OptIn(UnstableApi::class)
  fun setAudioOffloadPreferences(player: Player) {
    // [START set_audio_offload_preferences]
    val audioOffloadPreferences =
      AudioOffloadPreferences.Builder()
        .setAudioOffloadMode(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
        // Add additional options as needed
        .setIsGaplessSupportRequired(true)
        .build()
    player.trackSelectionParameters =
      player.trackSelectionParameters
        .buildUpon()
        .setAudioOffloadPreferences(audioOffloadPreferences)
        .build()
    // [END set_audio_offload_preferences]
  }
}
