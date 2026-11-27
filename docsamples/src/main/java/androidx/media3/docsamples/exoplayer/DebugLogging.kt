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

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.util.EventLogger

// Snippets for Debug logging.

object DebugLoggingKt {

  fun addEventLogger(player: ExoPlayer) {
    // [START add_event_logger]
    player.addAnalyticsListener(EventLogger())
    // [END add_event_logger]
  }
}
