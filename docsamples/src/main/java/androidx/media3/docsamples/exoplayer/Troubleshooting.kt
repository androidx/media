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
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory

// Code snippets for the Troubleshooting guide.

object TroubleshootingKt {

  @OptIn(UnstableApi::class)
  fun hardcodeTsSubtitleTracks(accessibilityChannel: Int, context: Context) {
    // [START hardcode_ts_subtitle_tracks]
    val extractorsFactory =
      DefaultExtractorsFactory()
        .setTsSubtitleFormats(
          listOf(
            Format.Builder()
              .setSampleMimeType(MimeTypes.APPLICATION_CEA608)
              .setAccessibilityChannel(accessibilityChannel)
              // Set other subtitle format info, such as language.
              .build()
          )
        )
    val player: Player =
      ExoPlayer.Builder(context, DefaultMediaSourceFactory(context, extractorsFactory)).build()
    // [END hardcode_ts_subtitle_tracks]
  }
}
