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

import androidx.annotation.FloatRange
import androidx.annotation.RestrictTo
import androidx.media3.common.Player
import androidx.media3.common.util.Log
import java.lang.reflect.Method

/**
 * A utility object that uses reflection to configure and enable scrubbing mode on supported
 * [Player] implementations (e.g., `ExoPlayer`, `CompositionPlayer`) without introducing direct
 * dependencies on their respective modules.
 */
internal object ScrubbingModeUtil {
  private const val TAG = "ScrubbingModeUtil"

  // LINT.IfChange
  private val EXO_PLAYER_CLAZZ: Class<*>? =
    try {
      Class.forName("androidx.media3.exoplayer.ExoPlayer")
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

  private val SCRUBBING_MODE_PARAMETERS_CLAZZ: Class<*>? =
    try {
      Class.forName("androidx.media3.exoplayer.ScrubbingModeParameters")
    } catch (_: ClassNotFoundException) {
      null
    }

  private val SCRUBBING_MODE_PARAMETERS_BUILDER_CLAZZ: Class<*>? =
    try {
      Class.forName("androidx.media3.exoplayer.ScrubbingModeParameters\$Builder")
    } catch (_: ClassNotFoundException) {
      null
    }

  private val EXO_PLAYER_GET_SCRUBBING_MODE_PARAMETERS: Method? =
    try {
      EXO_PLAYER_CLAZZ?.getMethod("getScrubbingModeParameters")
    } catch (_: NoSuchMethodException) {
      null
    }

  private val EXO_PLAYER_SET_SCRUBBING_MODE_PARAMETERS: Method? =
    try {
      SCRUBBING_MODE_PARAMETERS_CLAZZ?.let {
        EXO_PLAYER_CLAZZ?.getMethod("setScrubbingModeParameters", it)
      }
    } catch (_: NoSuchMethodException) {
      null
    }

  private val SCRUBBING_MODE_PARAMETERS_BUILD_UPON: Method? =
    try {
      SCRUBBING_MODE_PARAMETERS_CLAZZ?.getMethod("buildUpon")
    } catch (_: NoSuchMethodException) {
      null
    }

  private val SCRUBBING_MODE_PARAMETERS_BUILDER_SET_FRACTIONAL_SEEK_TOLERANCE: Method? =
    try {
      SCRUBBING_MODE_PARAMETERS_BUILDER_CLAZZ?.getMethod(
        "setFractionalSeekTolerance",
        Double::class.javaObjectType,
        Double::class.javaObjectType,
      )
    } catch (_: NoSuchMethodException) {
      null
    }

  private val SCRUBBING_MODE_PARAMETERS_BUILDER_BUILD: Method? =
    try {
      SCRUBBING_MODE_PARAMETERS_BUILDER_CLAZZ?.getMethod("build")
    } catch (_: NoSuchMethodException) {
      null
    }
  // LINT.ThenChange(../../../../../../../../../proguard-rules.txt)

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

  fun setFractionalSeekTolerance(
    player: Player?,
    toleranceBefore: Double?,
    toleranceAfter: Double?,
  ) {
    if (EXO_PLAYER_CLAZZ?.isInstance(player) != true) {
      return
    }
    try {
      val currentParameters = EXO_PLAYER_GET_SCRUBBING_MODE_PARAMETERS?.invoke(player) ?: return
      val newParameters =
        buildParametersWithFractionalSeekTolerance(
          currentParameters,
          toleranceBefore,
          toleranceAfter,
        ) ?: return
      if (newParameters != currentParameters) {
        EXO_PLAYER_SET_SCRUBBING_MODE_PARAMETERS?.invoke(player, newParameters)
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to set fractional seek tolerance", e)
    }
  }

  fun clearFractionalSeekToleranceIfEquals(
    player: Player?,
    expectedToleranceBefore: Double,
    expectedToleranceAfter: Double,
  ) {
    if (EXO_PLAYER_CLAZZ?.isInstance(player) != true) {
      return
    }
    try {
      val currentParameters = EXO_PLAYER_GET_SCRUBBING_MODE_PARAMETERS?.invoke(player) ?: return
      val expectedParameters =
        buildParametersWithFractionalSeekTolerance(
          currentParameters,
          expectedToleranceBefore,
          expectedToleranceAfter,
        ) ?: return
      if (currentParameters == expectedParameters) {
        val clearedParameters =
          buildParametersWithFractionalSeekTolerance(
            currentParameters,
            toleranceBefore = null,
            toleranceAfter = null,
          ) ?: return
        EXO_PLAYER_SET_SCRUBBING_MODE_PARAMETERS?.invoke(player, clearedParameters)
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to clear fractional seek tolerance", e)
    }
  }

  private fun buildParametersWithFractionalSeekTolerance(
    currentParameters: Any,
    toleranceBefore: Double?,
    toleranceAfter: Double?,
  ): Any? {
    val builder = SCRUBBING_MODE_PARAMETERS_BUILD_UPON?.invoke(currentParameters) ?: return null
    SCRUBBING_MODE_PARAMETERS_BUILDER_SET_FRACTIONAL_SEEK_TOLERANCE?.invoke(
      builder,
      toleranceBefore,
      toleranceAfter,
    ) ?: return null
    return SCRUBBING_MODE_PARAMETERS_BUILDER_BUILD?.invoke(builder)
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

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
fun Player?.setFractionalSeekTolerance(
  @FloatRange(from = 0.0, to = 1.0) toleranceBefore: Double?,
  @FloatRange(from = 0.0, to = 1.0) toleranceAfter: Double?,
) {
  ScrubbingModeUtil.setFractionalSeekTolerance(this, toleranceBefore, toleranceAfter)
}

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
fun Player?.clearFractionalSeekToleranceIfEquals(
  @FloatRange(from = 0.0, to = 1.0) toleranceBefore: Double,
  @FloatRange(from = 0.0, to = 1.0) toleranceAfter: Double,
) {
  ScrubbingModeUtil.clearFractionalSeekToleranceIfEquals(this, toleranceBefore, toleranceAfter)
}
