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
package androidx.media3.test.utils;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import androidx.media3.common.Flags;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runners.model.Statement;

/** Unit tests for {@link Media3FlagsRule}. */
@RunWith(AndroidJUnit4.class)
public class Media3FlagsRuleTest {

  private static final @Flags.Flag int TEST_FLAG_1 = 1_000_000;
  private static final @Flags.Flag int TEST_FLAG_2 = 1_000_001;

  private static final Statement EMPTY_STATEMENT =
      new Statement() {
        @Override
        public void evaluate() {}
      };

  @Test
  public void constructor_withNullTarget_throwsNullPointerException() {
    assertThrows(NullPointerException.class, () -> new Media3FlagsRule(null));
  }

  @Test
  public void apply_withoutAnnotatedFields_resetsFlagsAfterTest() throws Throwable {
    Media3FlagsRule rule = new Media3FlagsRule(new Object());
    Statement testStatement =
        new Statement() {
          @Override
          public void evaluate() {
            Flags.enableFlag(TEST_FLAG_1);
            assertThat(Flags.isEnabled(TEST_FLAG_1)).isTrue();
          }
        };

    rule.apply(testStatement, Description.EMPTY).evaluate();

    assertThat(Flags.getEnabledOverrides()).asList().doesNotContain(TEST_FLAG_1);
  }

  @Test
  public void apply_withoutAnnotatedFields_resetsFlagsEvenIfTestThrows() {
    Media3FlagsRule rule = new Media3FlagsRule(new Object());
    Statement testStatement =
        new Statement() {
          @Override
          public void evaluate() {
            Flags.enableFlag(TEST_FLAG_1);
            throw new RuntimeException("Test failure");
          }
        };
    Statement appliedStatement = rule.apply(testStatement, Description.EMPTY);

    assertThrows(RuntimeException.class, appliedStatement::evaluate);

    assertThat(Flags.getEnabledOverrides()).asList().doesNotContain(TEST_FLAG_1);
  }

  @Test
  public void apply_withBindFlagEnabled_enablesFlagDuringTestAndResetsAfter() throws Throwable {
    Media3FlagsRule rule = new Media3FlagsRule(new TargetWithBindFlagTrue());
    Statement testStatement =
        new Statement() {
          @Override
          public void evaluate() {
            assertThat(Flags.isEnabled(TEST_FLAG_1)).isTrue();
            assertThat(Flags.getEnabledOverrides()).asList().contains(TEST_FLAG_1);
          }
        };

    rule.apply(testStatement, Description.EMPTY).evaluate();

    assertThat(Flags.getEnabledOverrides()).asList().doesNotContain(TEST_FLAG_1);
  }

  @Test
  public void apply_withBindFlagDisabled_disablesFlagDuringTestAndResetsAfter() throws Throwable {
    Media3FlagsRule rule = new Media3FlagsRule(new TargetWithBindFlagFalse());
    Statement testStatement =
        new Statement() {
          @Override
          public void evaluate() {
            assertThat(Flags.isEnabled(TEST_FLAG_1)).isFalse();
            assertThat(Flags.getDisabledOverrides()).asList().contains(TEST_FLAG_1);
          }
        };

    rule.apply(testStatement, Description.EMPTY).evaluate();

    assertThat(Flags.getDisabledOverrides()).asList().doesNotContain(TEST_FLAG_1);
  }

  @Test
  public void apply_withBoxedBooleanBindFlag_bindsCorrectly() throws Throwable {
    Media3FlagsRule rule = new Media3FlagsRule(new TargetWithBoxedBooleanBindFlag());
    Statement testStatement =
        new Statement() {
          @Override
          public void evaluate() {
            assertThat(Flags.isEnabled(TEST_FLAG_1)).isTrue();
          }
        };

    rule.apply(testStatement, Description.EMPTY).evaluate();
  }

  @Test
  public void apply_withBindCanaryMode_bindsCanaryModeStateAndResetsAfter() throws Throwable {
    Media3FlagsRule rule = new Media3FlagsRule(new TargetWithBindCanaryModeDisabled());
    Statement testStatement =
        new Statement() {
          @Override
          public void evaluate() {
            assertThat(Flags.isCanaryModeEnabled()).isFalse();
          }
        };

    rule.apply(testStatement, Description.EMPTY).evaluate();

    assertThat(Flags.isCanaryModeEnabled()).isTrue();
  }

  @Test
  public void apply_withMultipleFlagsAndCanaryMode_bindsAll() throws Throwable {
    Media3FlagsRule rule = new Media3FlagsRule(new TargetWithMultipleFlagsAndCanaryMode());
    Statement testStatement =
        new Statement() {
          @Override
          public void evaluate() {
            assertThat(Flags.isCanaryModeEnabled()).isFalse();
            assertThat(Flags.isEnabled(TEST_FLAG_1)).isTrue();
            assertThat(Flags.isEnabled(TEST_FLAG_2)).isFalse();
          }
        };

    rule.apply(testStatement, Description.EMPTY).evaluate();

    assertThat(Flags.isCanaryModeEnabled()).isTrue();
    assertThat(Flags.getEnabledOverrides()).asList().doesNotContain(TEST_FLAG_1);
    assertThat(Flags.getDisabledOverrides()).asList().doesNotContain(TEST_FLAG_2);
  }

  @Test
  public void apply_withInheritedAnnotatedFields_bindsSuperclassFields() throws Throwable {
    Media3FlagsRule rule = new Media3FlagsRule(new SubTestClass());
    Statement testStatement =
        new Statement() {
          @Override
          public void evaluate() {
            assertThat(Flags.isEnabled(TEST_FLAG_1)).isTrue();
            assertThat(Flags.isEnabled(TEST_FLAG_2)).isFalse();
          }
        };

    rule.apply(testStatement, Description.EMPTY).evaluate();
  }

  @Test
  public void apply_withNonBooleanFieldForBindFlag_throwsIllegalArgumentException() {
    Media3FlagsRule rule = new Media3FlagsRule(new TargetWithNonBooleanFieldForBindFlag());
    Statement appliedStatement = rule.apply(EMPTY_STATEMENT, Description.EMPTY);

    assertThrows(IllegalArgumentException.class, appliedStatement::evaluate);
  }

  @Test
  public void apply_withNonBooleanFieldForBindCanaryMode_throwsIllegalArgumentException() {
    Media3FlagsRule rule = new Media3FlagsRule(new TargetWithNonBooleanFieldForBindCanaryMode());
    Statement appliedStatement = rule.apply(EMPTY_STATEMENT, Description.EMPTY);

    assertThrows(IllegalArgumentException.class, appliedStatement::evaluate);
  }

  // Fields are read reflectively by Media3FlagsRule.
  @SuppressWarnings({"unused", "FieldCanBeStatic"})
  private static class TargetWithBindFlagTrue {
    @BindFlag(TEST_FLAG_1)
    private final boolean flagEnabled = true;
  }

  // Fields are read reflectively by Media3FlagsRule.
  @SuppressWarnings({"unused", "FieldCanBeStatic"})
  private static class TargetWithBindFlagFalse {
    @BindFlag(TEST_FLAG_1)
    private final boolean flagEnabled = false;
  }

  // Fields are read reflectively by Media3FlagsRule.
  @SuppressWarnings({"unused", "FieldCanBeStatic"})
  private static class TargetWithBoxedBooleanBindFlag {
    @BindFlag(TEST_FLAG_1)
    private final Boolean flagEnabled = Boolean.TRUE;
  }

  // Fields are read reflectively by Media3FlagsRule.
  @SuppressWarnings({"unused", "FieldCanBeStatic"})
  private static class TargetWithBindCanaryModeDisabled {
    @BindCanaryMode private final boolean canaryMode = false;
  }

  // Fields are read reflectively by Media3FlagsRule.
  @SuppressWarnings({"unused", "FieldCanBeStatic"})
  private static class TargetWithMultipleFlagsAndCanaryMode {
    @BindCanaryMode private final boolean canaryMode = false;

    @BindFlag(TEST_FLAG_1)
    private final boolean flag1 = true;

    @BindFlag(TEST_FLAG_2)
    private final boolean flag2 = false;
  }

  // Fields are read reflectively by Media3FlagsRule.
  @SuppressWarnings({"unused", "FieldCanBeStatic"})
  private static class BaseTestClass {
    @BindFlag(TEST_FLAG_1)
    private final boolean baseFlag = true;
  }

  // Fields are read reflectively by Media3FlagsRule.
  @SuppressWarnings({"unused", "FieldCanBeStatic"})
  private static class SubTestClass extends BaseTestClass {
    @BindFlag(TEST_FLAG_2)
    private final boolean subFlag = false;
  }

  // Fields are read reflectively by Media3FlagsRule.
  @SuppressWarnings({"unused", "FieldCanBeStatic"})
  private static class TargetWithNonBooleanFieldForBindFlag {
    @BindFlag(TEST_FLAG_1)
    private final int invalidType = 123;
  }

  // Fields are read reflectively by Media3FlagsRule.
  @SuppressWarnings({"unused", "FieldCanBeStatic"})
  private static class TargetWithNonBooleanFieldForBindCanaryMode {
    @BindCanaryMode private final String invalidType = "true";
  }
}
