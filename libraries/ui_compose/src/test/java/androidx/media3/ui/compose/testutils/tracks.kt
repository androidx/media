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
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks

/**
 * Creates a set of tracks for testing: a video track group with [videoFormats], an English AAC
 * audio track and a Spanish WebVTT text track.
 *
 * By default, the video track group contains a 1080p and a 720p H.264 format. Tests that assert on
 * specific video formats should pass them in [videoFormats].
 */
internal fun createTestTracks(
  videoFormats: List<Format> = listOf(createVideoFormat(1920, 1080), createVideoFormat(1280, 720)),
  selectedVideoTrackIndex: Int? = null,
  videoHandledStates: IntArray? = null,
): Tracks {
  val videoTrackGroup = TrackGroup(*videoFormats.toTypedArray())

  val audioFormat =
    Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setLanguage("en").build()
  val audioTrackGroup = TrackGroup(audioFormat)

  val textFormat = Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).setLanguage("es").build()
  val textTrackGroup = TrackGroup(textFormat)

  val trackCount = videoFormats.size
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

/** Creates a video [Format] with the specified resolution and sample MIME type. */
internal fun createVideoFormat(
  width: Int,
  height: Int,
  sampleMimeType: String = MimeTypes.VIDEO_H264,
): Format =
  Format.Builder().setWidth(width).setHeight(height).setSampleMimeType(sampleMimeType).build()

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
