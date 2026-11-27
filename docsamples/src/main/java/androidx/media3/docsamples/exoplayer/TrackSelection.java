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
package androidx.media3.docsamples.exoplayer;

import android.content.Context;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import com.google.common.collect.ImmutableList;

/** Snippets for the track selection developer guide. */
@SuppressWarnings({
  "unused",
  "CheckReturnValue",
  "UnusedAnonymousClass",
  "PrivateConstructorForUtilityClass"
})
public final class TrackSelection {

  private TrackSelection() {}

  public static void addTracksListener(Player player) {
    // [START add_tracks_listener]
    player.addListener(
        new Player.Listener() {
          @Override
          public void onTracksChanged(Tracks tracks) {
            // Update UI using current tracks.
          }
        });
    // [END add_tracks_listener]
  }

  public static void obtainTrackDetails(Tracks tracks) {
    // [START obtain_track_details]
    for (Tracks.Group trackGroup : tracks.getGroups()) {
      // Group level information.
      @C.TrackType int trackType = trackGroup.getType();
      boolean trackInGroupIsSelected = trackGroup.isSelected();
      boolean trackInGroupIsSupported = trackGroup.isSupported();
      for (int i = 0; i < trackGroup.length; i++) {
        // Individual track information.
        boolean isSupported = trackGroup.isTrackSupported(i);
        boolean isSelected = trackGroup.isTrackSelected(i);
        Format trackFormat = trackGroup.getTrackFormat(i);
      }
    }
    // [END obtain_track_details]
  }

  public static void setTrackSelectionParameters(Player player) {
    // [START set_track_selection_parameters]
    player.setTrackSelectionParameters(
        player
            .getTrackSelectionParameters()
            .buildUpon()
            .setMaxVideoSizeSd()
            .setPreferredAudioLanguage("hu")
            .build());
    // [END set_track_selection_parameters]
  }

  public static void setTrackSelectionOverrides(Player player, Tracks.Group audioTrackGroup) {
    // [START set_track_selection_overrides]
    player.setTrackSelectionParameters(
        player
            .getTrackSelectionParameters()
            .buildUpon()
            .setOverrideForType(
                new TrackSelectionOverride(
                    audioTrackGroup.getMediaTrackGroup(), /* trackIndex= */ 0))
            .build());
    // [END set_track_selection_overrides]
  }

  public static void disableTrackTypes(Player player) {
    // [START disable_track_types]
    player.setTrackSelectionParameters(
        player
            .getTrackSelectionParameters()
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, /* disabled= */ true)
            .build());
    // [END disable_track_types]
  }

  public static void disableTrackGroups(Player player, Tracks.Group disabledTrackGroup) {
    // [START disable_track_groups]
    player.setTrackSelectionParameters(
        player
            .getTrackSelectionParameters()
            .buildUpon()
            .addOverride(
                new TrackSelectionOverride(
                    disabledTrackGroup.getMediaTrackGroup(),
                    /* trackIndices= */ ImmutableList.of()))
            .build());
    // [END disable_track_groups]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void setTrackSelector(Context context) {
    // [START set_track_selector]
    DefaultTrackSelector trackSelector = new DefaultTrackSelector(context);
    ExoPlayer player = new ExoPlayer.Builder(context).setTrackSelector(trackSelector).build();
    // [END set_track_selector]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void setTrackSelectorParameters(DefaultTrackSelector trackSelector) {
    // [START set_track_selector_parameters]
    trackSelector.setParameters(
        trackSelector.buildUponParameters().setAllowVideoMixedMimeTypeAdaptiveness(true));
    // [END set_track_selector_parameters]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void setAudioOffloadPreferences(Player player) {
    // [START set_audio_offload_preferences]
    AudioOffloadPreferences audioOffloadPreferences =
        new AudioOffloadPreferences.Builder()
            .setAudioOffloadMode(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
            // Add additional options as needed
            .setIsGaplessSupportRequired(true)
            .build();
    player.setTrackSelectionParameters(
        player
            .getTrackSelectionParameters()
            .buildUpon()
            .setAudioOffloadPreferences(audioOffloadPreferences)
            .build());
    // [END set_audio_offload_preferences]
  }
}
