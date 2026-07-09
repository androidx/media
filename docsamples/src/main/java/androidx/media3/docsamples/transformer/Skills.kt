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

package androidx.media3.docsamples.transformer

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.LanczosResample
import androidx.media3.effect.MultipleInputVideoGraph
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence

/** Code snippets for media editing agent skill. */
@OptIn(UnstableApi::class, ExperimentalApi::class)
class Skills {

  /** Adds background audio that loops for an entire composition. */
  fun robustBackgroundAudio() {
    val audioUri = "dummy_uri"
    val audioDurationUs = 59_000_000L
    val sequences = mutableListOf<EditedMediaItemSequence>()

    // [START background_audio]
    // CORRECT: Rely on the framework to loop the audio indefinitely
    val audioMediaItem = MediaItem.Builder().setUri(audioUri).build()
    val audioItem = EditedMediaItem.Builder(audioMediaItem).setDurationUs(audioDurationUs).build()

    val audioSequence =
      EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
        .addItem(audioItem)
        .setIsLooping(true) // Native API handles continuous playback safely
        .build()

    sequences.add(audioSequence)
    // [END background_audio]
  }

  /**
   * Performs frame dropping by setting a target frame rate.
   *
   * @param mediaItem The [MediaItem] to which frame dropping will be applied.
   */
  fun frameDropping(mediaItem: MediaItem) {
    // [START frame_dropping]
    // CORRECT: Set the target frame rate on the EditedMediaItem
    val targetFps = 15 // Example: 15 FPS

    val itemBuilder = EditedMediaItem.Builder(mediaItem).setFrameRate(targetFps)
    // ... apply other effects

    val editedMediaItem = itemBuilder.build()
    // [END frame_dropping]
  }

  /**
   * Applies a grayscale color filter to a video.
   *
   * @param itemVideoEffects The list of all video effects that will be applied to a media item.
   */
  fun grayscaleColorFilter(itemVideoEffects: MutableList<Effect>) {
    // [START grayscale_color_filter]
    // CORRECT: Apply a grayscale filter
    val grayscaleEffect = RgbFilter.createGrayscaleFilter()
    itemVideoEffects.add(grayscaleEffect)
    // [END grayscale_color_filter]
  }

  /**
   * Configures [CompositionPlayer] for multi-track composition previews.
   *
   * @param context The application [Context].
   * @param sequences The list of [EditedMediaItemSequence] instances in the composition.
   */
  fun multiTrackCompositionPreviews(context: Context, sequences: List<EditedMediaItemSequence>) {
    // [START multi_track_preview]
    // CORRECT: Attach the MultipleInputVideoGraph for multi-track support
    val playerBuilder = CompositionPlayer.Builder(context)

    if (sequences.size > 1) { // Or check track types
      playerBuilder.setVideoGraphFactory(MultipleInputVideoGraph.Factory())
    }

    val player = playerBuilder.build()
    // [END multi_track_preview]
  }

  /**
   * Mutes the audio of a [MediaItem].
   *
   * @param mediaItem The [MediaItem] whose audio should be muted.
   */
  fun muteAudio(mediaItem: MediaItem) {
    // [START mute_audio]
    // CORRECT: Remove the audio track from the media item
    val itemBuilder = EditedMediaItem.Builder(mediaItem).setRemoveAudio(true)
    // ... apply other effects

    val editedMediaItem = itemBuilder.build()
    // [END mute_audio]
  }

  /**
   * Scales the output video resolution to a specific height.
   *
   * @param globalVideoEffects The list of global video effects to which scaling effects are added.
   */
  fun outputResolutionScaling(globalVideoEffects: MutableList<Effect>) {
    // [START output_resolution_scaling]
    // CORRECT: Safe and high-quality downscaling
    val resolutionHeight = 720 // Example 720p

    globalVideoEffects.add(
      // We use an arbitrarily high width (e.g., 10000) as a non-binding constraint.
      // This ensures scaling is driven entirely by the target height while
      // maintaining a flexible orientation (handling both landscape and portrait).
      LanczosResample.scaleToFitWithFlexibleOrientation(10000, resolutionHeight)
    )
    // [END output_resolution_scaling]
  }

  /**
   * Rotates a video frame by 90 degrees.
   *
   * @param globalVideoEffects The list of global video effects to which the rotation is added.
   */
  fun rotateVideoFrame(globalVideoEffects: MutableList<Effect>) {
    // [START rotate_video_frame]
    // CORRECT: Rotate the video by 90 degrees
    val rotationEffect = ScaleAndRotateTransformation.Builder().setRotationDegrees(90f).build()

    globalVideoEffects.add(rotationEffect)
    // [END rotate_video_frame]
  }

  /**
   * Trims a video using start and end times.
   *
   * @param item The original [MediaItem].
   * @param startTimeMs The start time of the trim in milliseconds. Must be >= 0, and if [endTimeMs]
   *   is not [C.TIME_END_OF_SOURCE], must be <= [endTimeMs].
   * @param endTimeMs The end time of the trim in milliseconds. Must be >= [startTimeMs] and <=
   *   duration of the media item, or [C.TIME_END_OF_SOURCE] to play to the end of the media.
   */
  fun videoTrimming(item: MediaItem, startTimeMs: Long, endTimeMs: Long) {
    // [START video_trimming]
    // CORRECT: Use ClippingConfiguration to trim media
    val clippingConfig =
      MediaItem.ClippingConfiguration.Builder()
        .setStartPositionMs(startTimeMs)
        .setEndPositionMs(endTimeMs)
        .build()

    val mediaItem = item.buildUpon().setClippingConfiguration(clippingConfig).build()

    val editedMediaItem = EditedMediaItem.Builder(mediaItem).build()
    // [END video_trimming]
  }
}
