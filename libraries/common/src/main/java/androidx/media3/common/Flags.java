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
package androidx.media3.common;

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.util.SparseBooleanArray;
import androidx.annotation.IntDef;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Central registry for Media3 experimental feature flags.
 *
 * <p>This class provides a mechanism to control experimental features in Media3 via global canary
 * mode and individual flag overrides, enabling safe, gradual rollout of new library behaviors.
 *
 * <p><b>Canary mode</b>
 *
 * <p>Canary mode is enabled by default. Only flags that are considered safe for production usage
 * are included in canary mode. When canary mode is disabled, these flags are inactive.
 *
 * <p><b>Individual overrides</b>
 *
 * <p>Individual flags can be explicitly {@linkplain #enableFlag enabled} or {@linkplain
 * #disableFlag disabled}, regardless of the canary mode state. An explicit override always takes
 * precedence over the canary mode default.
 *
 * <p><b>Threading</b>
 *
 * <p>Flag configuration must be done on the application thread before creating any Media3
 * components (e.g., {@code ExoPlayer}, {@code Transformer}). Flag queries from other threads (e.g.,
 * the playback thread) rely on happens-before relationships established by {@link Thread#start()}
 * and {@link android.os.Handler} message posting during component setup.
 *
 * <p><b>Testing</b>
 *
 * <p>In unit tests, {@code androidx.media3.test.utils.Media3FlagsRule} is the preferred way to
 * manage flag states and automatically reset all flag overrides and canary mode after each test.
 */
@UnstableApi
public final class Flags {

  // ---------------------------------------------------------------------------------------------
  // Adding a new flag:
  // 1. Allocate the next flag ID from NEXT_FLAG_ID and increment NEXT_FLAG_ID by 1.
  // 2. Declare the public static final int constant, annotate with @ExperimentalApi, and add a
  //    TODO with a tracking issue to remove it.
  // 3. Add the flag to the @Flag @IntDef list and update its javadoc.
  // 4. Decide on the initial lifecycle stage (see Flag Lifecycle and Evolution below).
  //
  // Flag Lifecycle and Evolution:
  //
  // Feature flags in Media3 follow a defined progression through their development lifecycle:
  //
  // 1. Initial Development or Experiments (Statically Disabled):
  //    - When a new flag is introduced for an in-development feature or for experimental
  //      validation, register it in STATIC_FLAG_STATES with `false`.
  //    - This ensures the feature is inactive by default in production and excluded from canary
  //      mode rollout until implementation and validation are complete.
  //    - The feature can be tested via explicit `Flags.enableFlag(...)` in targeted unit tests or
  //      experiments.
  //    - This step is optional and can be skipped for flags that are ready for general rollout
  //      immediately.
  //
  // 2. Canary Rollout (Dynamic Flag):
  //    - When the feature is considered safe for general rollout, remove it from
  //      STATIC_FLAG_STATES.
  //    - The flag is now dynamic: enabled by default when canary mode is active (the default),
  //      and disabled when canary mode is turned off (acting as a holdback).
  //    - Keep the flag in canary mode for at least 2 months or until the next Media3 release
  //      (whichever is later).
  //
  // 3. Fallback Mechanism (Statically Enabled, Optional):
  //    - If an opt-out mechanism is temporarily desired after graduating from canary mode,
  //      register the flag in STATIC_FLAG_STATES with `true`.
  //    - The feature remains active even if canary mode is turned off, while still allowing an
  //      opt-out fallback via `Flags.disableFlag(...)` if needed.
  //    - This step is optional and many simple changes can skip it entirely.
  //
  // 4. Permanent Cleanup:
  //    - Once the feature is permanently enabled and the fallback is no longer needed, remove the
  //      flag constant, its @IntDef entry, its STATIC_FLAG_STATES entry, and all conditional code
  //      branches guarding the feature.
  //    - Features requiring permanent configuration should add a dedicated API rather than using
  //      Flags in the long term.
  // ---------------------------------------------------------------------------------------------

  /**
   * Flags for experimental features in Media3.
   *
   * <p>Possible flag values are:
   *
   * <ul>
   *   <li>{@link #FLAG_PER_STREAM_MEDIA_PROGRESSION}
   *   <li>{@link #FLAG_PROCESSED_STREAM_CHANGED_AT_START}
   *   <li>{@link #FLAG_DYNAMIC_SCHEDULING}
   * </ul>
   */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef(
      open = true,
      value = {
        FLAG_PER_STREAM_MEDIA_PROGRESSION,
        FLAG_PROCESSED_STREAM_CHANGED_AT_START,
        FLAG_DYNAMIC_SCHEDULING,
      })
  public @interface Flag {}

  /**
   * Flag to enable per-stream media progression in ExoPlayer.
   *
   * <p>When enabled, ExoPlayer may advance processing on a per-stream basis, potentially starting
   * to process the next item in the playlist before finishing the current one. This can reduce
   * startup latency between media items.
   *
   * <p>This flag is not yet covered by canary mode and defaults to {@code false}.
   */
  @ExperimentalApi // TODO: b/510217604 - Remove this flag.
  public static final int FLAG_PER_STREAM_MEDIA_PROGRESSION = 1;

  /**
   * Flag to enable invoking {@code MediaCodecRenderer.onProcessedStreamChange()} on the first
   * stream.
   *
   * <p>When enabled, {@code onProcessedStreamChange()} is invoked starting from the first stream.
   * When disabled (the default), it is invoked from the second stream onwards.
   *
   * <p>This flag is not yet covered by canary mode and defaults to {@code false}.
   */
  @ExperimentalApi // TODO: b/470373575 - Remove this flag.
  public static final int FLAG_PROCESSED_STREAM_CHANGED_AT_START = 2;

  /**
   * Flag to enable dynamic scheduling in ExoPlayer.
   *
   * <p>When enabled, ExoPlayer uses dynamic scheduling for its playback loop.
   *
   * <p>This flag is statically enabled and defaults to {@code true}.
   */
  @ExperimentalApi // TODO: b/500985770 - Remove this flag.
  public static final int FLAG_DYNAMIC_SCHEDULING = 3;

  @VisibleForTesting /* package */ static final int NEXT_FLAG_ID = 4;

  private static final SparseBooleanArray STATIC_FLAG_STATES = new SparseBooleanArray();

  static {
    // Statically disabled flags (not ready for general rollout).
    STATIC_FLAG_STATES.put(FLAG_PER_STREAM_MEDIA_PROGRESSION, false);
    STATIC_FLAG_STATES.put(FLAG_PROCESSED_STREAM_CHANGED_AT_START, false);

    // Statically enabled flags (kept as fallback for opt-out).
    STATIC_FLAG_STATES.put(FLAG_DYNAMIC_SCHEDULING, true);
  }

  @SuppressWarnings("NonFinalStaticField") // Intentional statically shared mutable state.
  private static boolean canaryModeEnabled = true;

  @SuppressWarnings("NonFinalStaticField") // Intentional statically shared mutable state.
  private static SparseBooleanArray overrides = new SparseBooleanArray();

  /**
   * Sets whether canary mode is enabled globally.
   *
   * <p>Canary mode is enabled by default.
   *
   * <p>When enabled, all flags that are not statically excluded are active by default. Individual
   * overrides set via {@link #enableFlag} or {@link #disableFlag} take precedence.
   *
   * <p>This must be called before creating any Media3 components.
   *
   * @param enabled Whether to enable canary mode.
   */
  public static synchronized void setCanaryModeEnabled(boolean enabled) {
    canaryModeEnabled = enabled;
  }

  /** Returns whether canary mode is currently enabled. */
  public static boolean isCanaryModeEnabled() {
    return canaryModeEnabled;
  }

  /**
   * Explicitly enables a flag, overriding the canary mode default.
   *
   * <p>This must be called before creating any Media3 components.
   *
   * @param flag The {@linkplain Flag flag identifier}.
   */
  public static synchronized void enableFlag(@Flag int flag) {
    checkArgument(flag > 0);
    if (Util.contains(STATIC_FLAG_STATES, flag) && STATIC_FLAG_STATES.get(flag)) {
      if (Util.contains(overrides, flag)) {
        SparseBooleanArray newOverrides = overrides.clone();
        newOverrides.delete(flag);
        overrides = newOverrides;
      }
      return;
    }
    SparseBooleanArray newOverrides = overrides.clone();
    newOverrides.put(flag, true);
    overrides = newOverrides;
  }

  /**
   * Explicitly disables a flag, overriding the canary mode default.
   *
   * <p>This must be called before creating any Media3 components.
   *
   * @param flag The {@linkplain Flag flag identifier}.
   */
  public static synchronized void disableFlag(@Flag int flag) {
    checkArgument(flag > 0);
    if (Util.contains(STATIC_FLAG_STATES, flag) && !STATIC_FLAG_STATES.get(flag)) {
      if (Util.contains(overrides, flag)) {
        SparseBooleanArray newOverrides = overrides.clone();
        newOverrides.delete(flag);
        overrides = newOverrides;
      }
      return;
    }
    SparseBooleanArray newOverrides = overrides.clone();
    newOverrides.put(flag, false);
    overrides = newOverrides;
  }

  /**
   * Checks if a specific flag is enabled.
   *
   * <p>The resolution order is:
   *
   * <ol>
   *   <li>If the flag has an explicit override (via {@link #enableFlag} or {@link #disableFlag}),
   *       the override value is returned.
   *   <li>If the flag has a static value not part of canary mode, that static value is returned.
   *   <li>Otherwise, the flag is enabled if canary mode is enabled.
   * </ol>
   *
   * @param flag The {@linkplain Flag flag identifier}.
   * @return Whether the flag is enabled.
   */
  public static boolean isEnabled(@Flag int flag) {
    checkArgument(flag > 0);
    SparseBooleanArray overrides = Flags.overrides;
    // 1. Check explicit overrides first.
    if (Util.contains(overrides, flag)) {
      return overrides.get(flag);
    }
    // 2. Check static flag states.
    if (Util.contains(STATIC_FLAG_STATES, flag)) {
      return STATIC_FLAG_STATES.get(flag);
    }
    // 3. Default to canary mode state.
    return canaryModeEnabled;
  }

  /**
   * Returns the flag identifiers that are explicitly enabled via {@link #enableFlag}, overriding
   * the canary mode default.
   */
  public static @Flag int[] getEnabledOverrides() {
    return getOverrides(/* enabled= */ true);
  }

  /**
   * Returns the flag identifiers that are explicitly disabled via {@link #disableFlag}, overriding
   * the canary mode default.
   */
  public static @Flag int[] getDisabledOverrides() {
    return getOverrides(/* enabled= */ false);
  }

  /**
   * Returns a log string describing the current canary mode and flag override state.
   *
   * <p>The format is {@code [Canary:<state>, Overrides: enabled=[<ids>], disabled=[<ids>]]}, or
   * {@code [Canary:<state>]} if there are no overrides. Only deviations from the default state are
   * listed.
   */
  public static String getLogString() {
    StringBuilder sb = new StringBuilder();
    sb.append("[Canary:").append(canaryModeEnabled ? "ON" : "OFF");

    @Flag int[] enabledOverrides = getEnabledOverrides();
    @Flag int[] disabledOverrides = getDisabledOverrides();
    if (enabledOverrides.length > 0 || disabledOverrides.length > 0) {
      sb.append(", Overrides:");
      if (enabledOverrides.length > 0) {
        sb.append(" enabled=");
        appendIds(sb, enabledOverrides);
      }
      if (enabledOverrides.length > 0 && disabledOverrides.length > 0) {
        sb.append(",");
      }
      if (disabledOverrides.length > 0) {
        sb.append(" disabled=");
        appendIds(sb, disabledOverrides);
      }
    }
    sb.append("]");
    return sb.toString();
  }

  private static @Flag int[] getOverrides(boolean enabled) {
    SparseBooleanArray overrides = Flags.overrides;
    int count = 0;
    for (int i = 0; i < overrides.size(); i++) {
      if (overrides.valueAt(i) == enabled) {
        count++;
      }
    }
    @Flag int[] result = new int[count];
    int index = 0;
    for (int i = 0; i < overrides.size(); i++) {
      if (overrides.valueAt(i) == enabled) {
        result[index++] = overrides.keyAt(i);
      }
    }
    return result;
  }

  private static void appendIds(StringBuilder sb, @Flag int[] ids) {
    sb.append("[");
    for (int i = 0; i < ids.length; i++) {
      if (i > 0) {
        sb.append(",");
      }
      sb.append(ids[i]);
    }
    sb.append("]");
  }

  /**
   * Resets the flag registry to its default configuration (all overrides cleared, canary mode
   * enabled).
   *
   * <p>This is intended for use in tests only to avoid test pollution. In unit tests, {@code
   * androidx.media3.test.utils.Media3FlagsRule} is the preferred alternative to calling this method
   * directly.
   */
  @VisibleForTesting
  public static synchronized void resetForTesting() {
    canaryModeEnabled = true;
    overrides = new SparseBooleanArray();
  }

  private Flags() {} // Prevents instantiation.
}
