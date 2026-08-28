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

package androidx.media3.ui.compose.testutils

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks

/** Creates a standard set of tracks for testing. */
internal fun createTestTracks(
  selectedVideoTrackIndex: Int? = null,
  includeMissingDataTrack: Boolean = false,
  videoHandledStates: IntArray? = null,
): Tracks {
  val baseFormats =
    listOf(
        Triple(1920, 1080, "video/mp4"),
        Triple(1920, 1080, "video/x-matroska"),
        Triple(1280, 720, "video/mp4"),
        Triple(1280, 720, "video/x-matroska"),
        Triple(854, 480, "video/mp4"),
        Triple(854, 480, "video/x-matroska"),
        Triple(640, 360, "video/quicktime"),
      )
      .map { (w, h, mime) ->
        Format.Builder().setWidth(w).setHeight(h).setSampleMimeType(mime).build()
      }

  val videoFormats =
    if (includeMissingDataTrack) {
      baseFormats + Format.Builder().setSampleMimeType("video/mp4").build()
    } else {
      baseFormats
    }
  val videoTrackGroup = TrackGroup(*videoFormats.toTypedArray())

  val audioFormat = Format.Builder().setSampleMimeType("audio/mp4").setLanguage("en").build()
  val audioTrackGroup = TrackGroup(audioFormat)

  val textFormat = Format.Builder().setSampleMimeType("text/vtt").setLanguage("es").build()
  val textTrackGroup = TrackGroup(textFormat)

  val trackCount = if (includeMissingDataTrack) 8 else 7
  val defaultHandledStates = IntArray(trackCount) { C.FORMAT_HANDLED }
  val actualHandledStates = videoHandledStates ?: defaultHandledStates
  val isSelectedArray = BooleanArray(trackCount) { it == selectedVideoTrackIndex }

  return Tracks(
    listOf(
      @Suppress("WrongConstant")
      Tracks.Group(
        videoTrackGroup,
        /* adaptiveSupported= */ true,
        actualHandledStates,
        isSelectedArray,
      ),
      Tracks.Group(
        audioTrackGroup,
        /* adaptiveSupported= */ false,
        intArrayOf(C.FORMAT_HANDLED),
        booleanArrayOf(false),
      ),
      Tracks.Group(
        textTrackGroup,
        /* adaptiveSupported= */ false,
        intArrayOf(C.FORMAT_HANDLED),
        booleanArrayOf(false),
      ),
    )
  )
}

/** Creates a set of tracks with specific languages for testing language-based selection. */
internal fun createTestTracksWithLanguages(
  mimeType: String,
  vararg languages: String,
  selectedLanguage: String? = null,
): Tracks {
  val groups = languages.map { lang ->
    val format = Format.Builder().setSampleMimeType(mimeType).setLanguage(lang).build()
    Tracks.Group(
      TrackGroup(format),
      /* adaptiveSupported= */ false,
      intArrayOf(C.FORMAT_HANDLED),
      booleanArrayOf(lang == selectedLanguage),
    )
  }
  return Tracks(groups)
}
