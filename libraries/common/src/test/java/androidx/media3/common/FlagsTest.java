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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link Flags}. */
@RunWith(AndroidJUnit4.class)
public class FlagsTest {

  private static final @Flags.Flag int TEST_FLAG_1 = 1_000_000;
  private static final @Flags.Flag int TEST_FLAG_2 = 1_000_001;

  @After
  public void tearDown() {
    Flags.resetForTesting();
  }

  @Test
  public void isEnabled_defaultState_returnsFalseForStaticallyDisabledFlags() {
    assertThat(Flags.isEnabled(getStaticallyDisabledFlag())).isFalse();
  }

  @Test
  public void isEnabled_defaultState_returnsTrueForStaticallyEnabledFlags() {
    assertThat(Flags.isEnabled(getStaticallyEnabledFlag())).isTrue();
  }

  @Test
  public void isEnabled_canaryModeEnabled_returnsFalseForStaticallyDisabledFlags() {
    Flags.setCanaryModeEnabled(true);

    // Statically disabled flags remain disabled even with canary mode on.
    assertThat(Flags.isEnabled(getStaticallyDisabledFlag())).isFalse();
  }

  @Test
  public void isEnabled_canaryModeDisabled_returnsTrueForStaticallyEnabledFlags() {
    Flags.setCanaryModeEnabled(false);

    // Statically enabled flags remain enabled even with canary mode off.
    assertThat(Flags.isEnabled(getStaticallyEnabledFlag())).isTrue();
  }

  @Test
  public void isEnabled_canaryModeEnabled_returnsTrueForDynamicFlags() {
    Flags.setCanaryModeEnabled(true);

    assertThat(Flags.isEnabled(TEST_FLAG_1)).isTrue();
  }

  @Test
  public void isEnabled_canaryModeDisabled_returnsFalseForDynamicFlags() {
    Flags.setCanaryModeEnabled(false);

    assertThat(Flags.isEnabled(TEST_FLAG_1)).isFalse();
  }

  @Test
  public void enableFlag_overridesStaticFalse() {
    int flag = getStaticallyDisabledFlag();
    Flags.enableFlag(flag);

    assertThat(Flags.isEnabled(flag)).isTrue();
    assertThat(Flags.getEnabledOverrides()).asList().containsExactly(flag);
    assertThat(Flags.getDisabledOverrides()).isEmpty();
  }

  @Test
  public void disableFlag_overridesStaticTrue() {
    int flag = getStaticallyEnabledFlag();
    Flags.disableFlag(flag);

    assertThat(Flags.isEnabled(flag)).isFalse();
    assertThat(Flags.getDisabledOverrides()).asList().containsExactly(flag);
    assertThat(Flags.getEnabledOverrides()).isEmpty();
  }

  @Test
  public void enableFlag_staticallyEnabledFlag_isIgnored() {
    int flag = getStaticallyEnabledFlag();
    Flags.enableFlag(flag);

    assertThat(Flags.isEnabled(flag)).isTrue();
    assertThat(Flags.getEnabledOverrides()).isEmpty();
    assertThat(Flags.getDisabledOverrides()).isEmpty();
  }

  @Test
  public void disableFlag_staticallyDisabledFlag_isIgnored() {
    int flag = getStaticallyDisabledFlag();
    Flags.disableFlag(flag);

    assertThat(Flags.isEnabled(flag)).isFalse();
    assertThat(Flags.getEnabledOverrides()).isEmpty();
    assertThat(Flags.getDisabledOverrides()).isEmpty();
  }

  @Test
  public void enableFlag_staticallyEnabledFlag_clearsPreviousDisable() {
    int flag = getStaticallyEnabledFlag();
    Flags.disableFlag(flag);
    assertThat(Flags.getDisabledOverrides()).asList().containsExactly(flag);

    Flags.enableFlag(flag);

    assertThat(Flags.isEnabled(flag)).isTrue();
    assertThat(Flags.getEnabledOverrides()).isEmpty();
    assertThat(Flags.getDisabledOverrides()).isEmpty();
  }

  @Test
  public void disableFlag_staticallyDisabledFlag_clearsPreviousEnable() {
    int flag = getStaticallyDisabledFlag();
    Flags.enableFlag(flag);
    assertThat(Flags.getEnabledOverrides()).asList().containsExactly(flag);

    Flags.disableFlag(flag);

    assertThat(Flags.isEnabled(flag)).isFalse();
    assertThat(Flags.getEnabledOverrides()).isEmpty();
    assertThat(Flags.getDisabledOverrides()).isEmpty();
  }

  @Test
  public void disableFlag_overridesCanaryDefault() {
    Flags.disableFlag(TEST_FLAG_1);

    assertThat(Flags.isEnabled(TEST_FLAG_1)).isFalse();
    assertThat(Flags.getDisabledOverrides()).asList().containsExactly(TEST_FLAG_1);
  }

  @Test
  public void enableFlag_overridesDisableFlag() {
    Flags.disableFlag(TEST_FLAG_1);
    Flags.enableFlag(TEST_FLAG_1);

    assertThat(Flags.isEnabled(TEST_FLAG_1)).isTrue();
    assertThat(Flags.getEnabledOverrides()).asList().containsExactly(TEST_FLAG_1);
    assertThat(Flags.getDisabledOverrides()).isEmpty();
  }

  @Test
  public void disableFlag_overridesPreviousEnable() {
    Flags.enableFlag(TEST_FLAG_1);
    Flags.disableFlag(TEST_FLAG_1);

    assertThat(Flags.isEnabled(TEST_FLAG_1)).isFalse();
    assertThat(Flags.getDisabledOverrides()).asList().containsExactly(TEST_FLAG_1);
    assertThat(Flags.getEnabledOverrides()).isEmpty();
  }

  @Test
  public void enableFlag_withCanaryModeOff_stillEnablesFlag() {
    Flags.setCanaryModeEnabled(false);
    Flags.enableFlag(TEST_FLAG_1);

    assertThat(Flags.isEnabled(TEST_FLAG_1)).isTrue();
  }

  @Test
  public void enableFlag_calledMultipleTimes_isIdempotent() {
    Flags.enableFlag(TEST_FLAG_1);
    Flags.enableFlag(TEST_FLAG_1);

    assertThat(Flags.isEnabled(TEST_FLAG_1)).isTrue();
    assertThat(Flags.getEnabledOverrides()).asList().containsExactly(TEST_FLAG_1);
    assertThat(Flags.getDisabledOverrides()).isEmpty();
  }

  @Test
  public void disableFlag_calledMultipleTimes_isIdempotent() {
    Flags.disableFlag(TEST_FLAG_1);
    Flags.disableFlag(TEST_FLAG_1);

    assertThat(Flags.isEnabled(TEST_FLAG_1)).isFalse();
    assertThat(Flags.getDisabledOverrides()).asList().containsExactly(TEST_FLAG_1);
    assertThat(Flags.getEnabledOverrides()).isEmpty();
  }

  @Test
  public void enableFlag_invalidFlag_throwsIllegalArgumentException() {
    assertThrows(IllegalArgumentException.class, () -> Flags.enableFlag(0));
    assertThrows(IllegalArgumentException.class, () -> Flags.enableFlag(-1));
  }

  @Test
  public void disableFlag_invalidFlag_throwsIllegalArgumentException() {
    assertThrows(IllegalArgumentException.class, () -> Flags.disableFlag(0));
    assertThrows(IllegalArgumentException.class, () -> Flags.disableFlag(-1));
  }

  @Test
  public void isEnabled_invalidFlag_throwsIllegalArgumentException() {
    assertThrows(IllegalArgumentException.class, () -> Flags.isEnabled(0));
    assertThrows(IllegalArgumentException.class, () -> Flags.isEnabled(-1));
  }

  @Test
  public void resetForTesting_restoresDefaults() {
    int disabledFlag = getStaticallyDisabledFlag();
    int enabledFlag = getStaticallyEnabledFlag();

    Flags.setCanaryModeEnabled(false);
    Flags.enableFlag(disabledFlag);
    Flags.disableFlag(enabledFlag);
    Flags.enableFlag(TEST_FLAG_1);

    Flags.resetForTesting();

    assertThat(Flags.isCanaryModeEnabled()).isTrue();
    assertThat(Flags.getEnabledOverrides()).isEmpty();
    assertThat(Flags.getDisabledOverrides()).isEmpty();
    assertThat(Flags.isEnabled(disabledFlag)).isFalse();
    assertThat(Flags.isEnabled(enabledFlag)).isTrue();
    assertThat(Flags.isEnabled(TEST_FLAG_1)).isTrue();
  }

  @Test
  public void getLogString_noOverrides_canaryOff() {
    Flags.setCanaryModeEnabled(false);

    assertThat(Flags.getLogString()).isEqualTo("[Canary:OFF]");
  }

  @Test
  public void getLogString_noOverrides_canaryOn() {
    Flags.setCanaryModeEnabled(true);

    assertThat(Flags.getLogString()).isEqualTo("[Canary:ON]");
  }

  @Test
  public void getLogString_withOverrides() {
    Flags.setCanaryModeEnabled(true);
    Flags.enableFlag(TEST_FLAG_1);
    Flags.disableFlag(TEST_FLAG_2);

    assertThat(Flags.getLogString())
        .isEqualTo(
            "[Canary:ON, Overrides: enabled=["
                + TEST_FLAG_1
                + "], disabled=["
                + TEST_FLAG_2
                + "]]");
  }

  @Test
  public void getLogString_staticallyConfiguredFlagsNotListedWhenMatchingStaticDefault() {
    int disabledFlag = getStaticallyDisabledFlag();
    int enabledFlag = getStaticallyEnabledFlag();

    Flags.setCanaryModeEnabled(true);
    Flags.disableFlag(disabledFlag);
    Flags.enableFlag(enabledFlag);

    assertThat(Flags.getLogString()).isEqualTo("[Canary:ON]");
  }

  @Test
  public void declaredFlags_haveValidAndUniqueIdsLessThanNextFlagId() throws Exception {
    Set<Integer> seenIds = new HashSet<>();
    for (Field field : Flags.class.getDeclaredFields()) {
      if (field.getName().startsWith("FLAG_")
          && Modifier.isPublic(field.getModifiers())
          && Modifier.isStatic(field.getModifiers())
          && field.getType() == int.class) {
        int flagId = field.getInt(null);
        assertThat(flagId).isGreaterThan(0);
        assertThat(flagId).isLessThan(Flags.NEXT_FLAG_ID);
        assertThat(seenIds.add(flagId)).isTrue();
      }
    }
    assertThat(seenIds).isNotEmpty();
  }

  private static @Flags.Flag int getStaticallyDisabledFlag() {
    // Update to another statically disabled flag when the returned flag is removed.
    return Flags.FLAG_PER_STREAM_MEDIA_PROGRESSION;
  }

  private static @Flags.Flag int getStaticallyEnabledFlag() {
    // Update to another statically enabled flag when the returned flag is removed.
    return Flags.FLAG_DYNAMIC_SCHEDULING;
  }
}
