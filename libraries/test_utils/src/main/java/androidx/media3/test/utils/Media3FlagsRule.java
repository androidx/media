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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import androidx.media3.common.Flags;
import androidx.media3.common.util.UnstableApi;
import java.lang.reflect.Field;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/**
 * A JUnit {@link TestRule} for managing {@link Flags} states in tests.
 *
 * <p>This rule ensures that all flag overrides and canary mode configurations are reset after each
 * test to prevent test pollution.
 *
 * <p>It automatically inspects the target object (usually {@code this}) and its superclasses for
 * fields annotated with {@link BindFlag} and {@link BindCanaryMode}, and configures {@link Flags}
 * before each test runs.
 *
 * <p><b>Usage Examples:</b>
 *
 * <p><b>1. Automatic flag reset for manual flag overrides</b>
 *
 * <p>Applying the rule ensures that any flag or canary mode modifications made during test
 * execution (e.g. via {@link Flags#enableFlag(int)} or {@link Flags#setCanaryModeEnabled(boolean)})
 * are automatically reset after each test finishes:
 *
 * <pre>
 * public class MyPlaybackTest {
 *
 *   &#64;Rule public final Media3FlagsRule flagsRule = new Media3FlagsRule(this);
 *
 *   &#64;Test
 *   public void playback_withFeatureFlagEnabled() {
 *     Flags.enableFlag(Flags.FLAG_SOME_FEATURE);
 *     // Test behavior with the flag enabled...
 *   }
 * }
 * </pre>
 *
 * <p><b>2. Parameterized test on a feature flag with {@link BindFlag}</b>
 *
 * <p>The rule automatically enables or disables the flag before each test iteration according to
 * the parameter field's value:
 *
 * <pre>
 * &#64;RunWith(ParameterizedRobolectricTestRunner.class)
 * public class MyPlaybackTest {
 *
 *   &#64;Parameter(0)
 *   &#64;BindFlag(Flags.FLAG_SOME_FEATURE)
 *   public boolean someFeatureEnabled;
 *
 *   &#64;Rule public final Media3FlagsRule flagsRule = new Media3FlagsRule(this);
 *
 *   &#64;Test
 *   public void playback() {
 *     // Runs once with the flag disabled, and once with the flag enabled.
 *   }
 * }
 * </pre>
 *
 * <p><b>3. Parameterized test on canary mode with {@link BindCanaryMode}</b>
 *
 * <p>The rule enables or disables canary mode before each test iteration:
 *
 * <pre>
 * &#64;RunWith(ParameterizedRobolectricTestRunner.class)
 * public class MyPlaybackTest {
 *
 *   &#64;Parameter(0)
 *   &#64;BindCanaryMode
 *   public boolean canaryMode;
 *
 *   &#64;Rule public final Media3FlagsRule flagsRule = new Media3FlagsRule(this);
 *
 *   &#64;Test
 *   public void playback() {
 *     // Runs once with canary mode disabled, and once with canary mode enabled.
 *   }
 * }
 * </pre>
 *
 * <p><b>4. Multiple flags or combined flag and canary mode bindings</b>
 *
 * <p>Multiple annotated fields can be declared to test combinations of flags:
 *
 * <pre>
 * &#64;RunWith(ParameterizedRobolectricTestRunner.class)
 * public class MyPlaybackTest {
 *
 *   &#64;Parameter(0)
 *   &#64;BindFlag(Flags.FLAG_FEATURE_A)
 *   public boolean featureA;
 *
 *   &#64;Parameter(1)
 *   &#64;BindFlag(Flags.FLAG_FEATURE_B)
 *   public boolean featureB;
 *
 *   &#64;Rule public final Media3FlagsRule flagsRule = new Media3FlagsRule(this);
 *
 *   &#64;Test
 *   public void playback() {
 *     // Runs for each combination of flags.
 *   }
 * }
 * </pre>
 */
@UnstableApi
public final class Media3FlagsRule implements TestRule {

  private final Object target;

  /**
   * Creates a rule that binds annotated fields on the {@code target} before each test and resets
   * flags after each test.
   *
   * @param target The test instance (usually {@code this}) containing fields annotated with {@link
   *     BindFlag} or {@link BindCanaryMode}, or simply the test instance to ensure flags are reset.
   */
  public Media3FlagsRule(Object target) {
    this.target = checkNotNull(target);
  }

  @Override
  public Statement apply(Statement base, Description description) {
    return new Statement() {
      @Override
      public void evaluate() throws Throwable {
        Flags.resetForTesting();
        try {
          bindFlags(target);
          base.evaluate();
        } finally {
          Flags.resetForTesting();
        }
      }
    };
  }

  private static void bindFlags(Object target) {
    for (Class<?> clazz = target.getClass(); clazz != null; clazz = clazz.getSuperclass()) {
      for (Field field : clazz.getDeclaredFields()) {
        if (field.isAnnotationPresent(BindCanaryMode.class)) {
          field.setAccessible(true);
          Object value;
          try {
            value = field.get(target);
          } catch (IllegalAccessException e) {
            throw new IllegalStateException("Failed to access field: " + field.getName(), e);
          }
          checkArgument(
              value instanceof Boolean,
              "Field %s annotated with @BindCanaryMode must be a boolean",
              field.getName());
          Flags.setCanaryModeEnabled((Boolean) value);
        }
        if (field.isAnnotationPresent(BindFlag.class)) {
          BindFlag bindFlag = checkNotNull(field.getAnnotation(BindFlag.class));
          field.setAccessible(true);
          Object value;
          try {
            value = field.get(target);
          } catch (IllegalAccessException e) {
            throw new IllegalStateException("Failed to access field: " + field.getName(), e);
          }
          checkArgument(
              value instanceof Boolean,
              "Field %s annotated with @BindFlag must be a boolean",
              field.getName());
          boolean enabled = (Boolean) value;
          if (enabled) {
            Flags.enableFlag(bindFlag.value());
          } else {
            Flags.disableFlag(bindFlag.value());
          }
        }
      }
    }
  }
}
