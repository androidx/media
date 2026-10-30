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

import androidx.annotation.OptIn
import androidx.media3.common.Flags
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.test.utils.BindCanaryMode
import androidx.media3.test.utils.BindFlag
import androidx.media3.test.utils.Media3FlagsRule
import org.junit.Rule
import org.junit.Test

// Snippets for Feature flags and Canary mode.

object ExperimentalFlagsKt {

  @OptIn(UnstableApi::class)
  fun setCanaryMode() {
    // [START set_canary_mode]
    // Enable or disable Canary mode globally (defaults to true).
    Flags.setCanaryModeEnabled(false)
    // [END set_canary_mode]
  }

  @OptIn(UnstableApi::class, ExperimentalApi::class)
  fun overrideFlags() {
    // [START override_flags]
    // Explicitly enable an experimental flag.
    Flags.enableFlag(Flags.FLAG_PER_STREAM_MEDIA_PROGRESSION)

    // Explicitly disable a flag.
    Flags.disableFlag(Flags.FLAG_DYNAMIC_SCHEDULING)
    // [END override_flags]
  }

  @OptIn(UnstableApi::class)
  // [START remote_config_integration]
  fun initializeMedia3Flags(
    canaryModeEnabled: Boolean,
    forceEnabledFlags: List<Int>,
    forceDisabledFlags: List<Int>,
  ) {
    // 1. Set global Canary mode state.
    Flags.setCanaryModeEnabled(canaryModeEnabled)

    // 2. Apply explicit force-enable overrides.
    for (flagId in forceEnabledFlags) {
      Flags.enableFlag(flagId)
    }

    // 3. Apply explicit force-disable overrides (remote kill switch).
    for (flagId in forceDisabledFlags) {
      Flags.disableFlag(flagId)
    }
  }

  // [END remote_config_integration]

  @OptIn(UnstableApi::class, ExperimentalApi::class)
  fun checkFlagState() {
    // [START check_flag_state]
    // Query whether Canary mode is active globally.
    val isCanaryActive = Flags.isCanaryModeEnabled()

    // Query whether a specific flag is active.
    val isFeatureActive = Flags.isEnabled(Flags.FLAG_PER_STREAM_MEDIA_PROGRESSION)
    // [END check_flag_state]
  }

  @OptIn(UnstableApi::class)
  fun inspectFlagDiagnostics() {
    // [START inspect_flag_diagnostics]
    // Get formatted string representing active Canary state and overrides.
    val flagLogString = Flags.getLogString()

    // Query explicit override IDs if needed.
    val enabledFlags = Flags.getEnabledOverrides()
    val disabledFlags = Flags.getDisabledOverrides()
    // [END inspect_flag_diagnostics]
  }

  @OptIn(UnstableApi::class, ExperimentalApi::class)
  // [START test_flags_rule]
  class MyPlayerTest {

    @get:Rule val flagsRule = Media3FlagsRule(this)

    @Test
    fun playback_withFeatureFlagEnabled() {
      Flags.enableFlag(Flags.FLAG_PER_STREAM_MEDIA_PROGRESSION)
      // Run test with the flag enabled...
    }
  }

  // [END test_flags_rule]

  @OptIn(UnstableApi::class, ExperimentalApi::class)
  // [START test_bind_flags]
  class MyParameterizedPlayerTest {

    @BindCanaryMode var canaryMode: Boolean = false

    @BindFlag(Flags.FLAG_PER_STREAM_MEDIA_PROGRESSION) var perStreamProgression: Boolean = false

    @get:Rule val flagsRule = Media3FlagsRule(this)

    @Test
    fun playback() {
      // Flags are automatically set for each test parameter combination.
    }
  }
  // [END test_bind_flags]
}
