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

package androidx.media3.ui.compose.material3.util

import androidx.annotation.RestrictTo
import androidx.media3.common.Player
import androidx.media3.common.util.Log
import java.lang.reflect.Method

/**
 * A utility object that uses reflection to enable scrubbing mode on supported [Player]
 * implementations (e.g., `ExoPlayer`, `CompositionPlayer`) without introducing direct dependencies
 * on their respective modules.
 */
internal object ScrubbingModeUtil {
  private const val TAG = "ScrubbingModeUtil"

  private val EXO_PLAYER_CLAZZ: Class<*>? =
    try {
      // LINT.IfChange
      Class.forName("androidx.media3.exoplayer.ExoPlayer")
      // LINT.ThenChange(../../../../../../../../../proguard-rules.txt)
    } catch (_: ClassNotFoundException) {
      null
    }

  private val EXO_PLAYER_SETTER: Method? =
    try {
      EXO_PLAYER_CLAZZ?.getMethod("setScrubbingModeEnabled", Boolean::class.java)
    } catch (_: NoSuchMethodException) {
      null
    }

  private val EXO_PLAYER_GETTER: Method? =
    try {
      EXO_PLAYER_CLAZZ?.getMethod("isScrubbingModeEnabled")
    } catch (_: NoSuchMethodException) {
      null
    }

  private val COMPOSITION_PLAYER_CLAZZ: Class<*>? =
    try {
      // LINT.IfChange
      Class.forName("androidx.media3.transformer.CompositionPlayer")
      // LINT.ThenChange(../../../../../../../../../proguard-rules.txt)
    } catch (_: ClassNotFoundException) {
      null
    }

  private val COMPOSITION_PLAYER_SETTER: Method? =
    try {
      COMPOSITION_PLAYER_CLAZZ?.getMethod("setScrubbingModeEnabled", Boolean::class.java)
    } catch (_: NoSuchMethodException) {
      null
    }

  private val COMPOSITION_PLAYER_GETTER: Method? =
    try {
      COMPOSITION_PLAYER_CLAZZ?.getMethod("isScrubbingModeEnabled")
    } catch (_: NoSuchMethodException) {
      null
    }

  fun setScrubbingModeEnabled(player: Player?, enabled: Boolean) {
    try {
      if (EXO_PLAYER_CLAZZ?.isInstance(player) == true) {
        EXO_PLAYER_SETTER?.invoke(player, enabled)
      } else if (COMPOSITION_PLAYER_CLAZZ?.isInstance(player) == true) {
        COMPOSITION_PLAYER_SETTER?.invoke(player, enabled)
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to set scrubbing mode", e)
    }
  }

  fun isScrubbingModeEnabled(player: Player?): Boolean {
    return try {
      if (EXO_PLAYER_CLAZZ?.isInstance(player) == true) {
        EXO_PLAYER_GETTER?.invoke(player) as? Boolean ?: false
      } else if (COMPOSITION_PLAYER_CLAZZ?.isInstance(player) == true) {
        COMPOSITION_PLAYER_GETTER?.invoke(player) as? Boolean ?: false
      } else {
        false
      }
    } catch (_: Exception) {
      false
    }
  }
}

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
fun Player?.setScrubbingModeEnabled(enabled: Boolean) {
  ScrubbingModeUtil.setScrubbingModeEnabled(this, enabled)
}

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
fun Player?.isScrubbingModeEnabled(): Boolean {
  return ScrubbingModeUtil.isScrubbingModeEnabled(this)
}
