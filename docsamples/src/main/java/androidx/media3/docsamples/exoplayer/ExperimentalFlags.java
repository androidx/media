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

import androidx.annotation.OptIn;
import androidx.media3.common.Flags;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.test.utils.BindCanaryMode;
import androidx.media3.test.utils.BindFlag;
import androidx.media3.test.utils.Media3FlagsRule;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;

/** Snippets for Feature flags and Canary mode. */
@SuppressWarnings({"unused", "CheckReturnValue", "PrivateConstructorForUtilityClass"})
public class ExperimentalFlags {

  @OptIn(markerClass = UnstableApi.class)
  public static void setCanaryMode() {
    // [START set_canary_mode]
    // Enable or disable Canary mode globally (defaults to true).
    Flags.setCanaryModeEnabled(false);
    // [END set_canary_mode]
  }

  @OptIn(markerClass = {UnstableApi.class, ExperimentalApi.class})
  public static void overrideFlags() {
    // [START override_flags]
    // Explicitly enable an experimental flag.
    Flags.enableFlag(Flags.FLAG_PER_STREAM_MEDIA_PROGRESSION);

    // Explicitly disable a flag.
    Flags.disableFlag(Flags.FLAG_DYNAMIC_SCHEDULING);
    // [END override_flags]
  }

  @OptIn(markerClass = UnstableApi.class)
  // [START remote_config_integration]
  public static void initializeMedia3Flags(
      boolean canaryModeEnabled,
      List<Integer> forceEnabledFlags,
      List<Integer> forceDisabledFlags) {
    // 1. Set global Canary mode state.
    Flags.setCanaryModeEnabled(canaryModeEnabled);

    // 2. Apply explicit force-enable overrides.
    for (int flagId : forceEnabledFlags) {
      Flags.enableFlag(flagId);
    }

    // 3. Apply explicit force-disable overrides (remote kill switch).
    for (int flagId : forceDisabledFlags) {
      Flags.disableFlag(flagId);
    }
  }

  // [END remote_config_integration]

  @OptIn(markerClass = {UnstableApi.class, ExperimentalApi.class})
  public static void checkFlagState() {
    // [START check_flag_state]
    // Query whether Canary mode is active globally.
    boolean isCanaryActive = Flags.isCanaryModeEnabled();

    // Query whether a specific flag is active.
    boolean isFeatureActive = Flags.isEnabled(Flags.FLAG_PER_STREAM_MEDIA_PROGRESSION);
    // [END check_flag_state]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void inspectFlagDiagnostics() {
    // [START inspect_flag_diagnostics]
    // Get formatted string representing active Canary state and overrides.
    String flagLogString = Flags.getLogString();

    // Query explicit override IDs if needed.
    int[] enabledFlags = Flags.getEnabledOverrides();
    int[] disabledFlags = Flags.getDisabledOverrides();
    // [END inspect_flag_diagnostics]
  }

  @OptIn(markerClass = {UnstableApi.class, ExperimentalApi.class})
  // [START test_flags_rule]
  public static class MyPlayerTest {

    @Rule public final Media3FlagsRule flagsRule = new Media3FlagsRule(this);

    @Test
    public void playback_withFeatureFlagEnabled() {
      Flags.enableFlag(Flags.FLAG_PER_STREAM_MEDIA_PROGRESSION);
      // Run test with the flag enabled...
    }
  }

  // [END test_flags_rule]

  @OptIn(markerClass = {UnstableApi.class, ExperimentalApi.class})
  // [START test_bind_flags]
  public static class MyParameterizedPlayerTest {

    @BindCanaryMode public boolean canaryMode;

    @BindFlag(Flags.FLAG_PER_STREAM_MEDIA_PROGRESSION)
    public boolean perStreamProgression;

    @Rule public final Media3FlagsRule flagsRule = new Media3FlagsRule(this);

    @Test
    public void playback() {
      // Flags are automatically set for each test parameter combination.
    }
  }
  // [END test_bind_flags]
}
