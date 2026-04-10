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
package androidx.media3.cast;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.gms.cast.framework.CastOptions;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link CastParams}. */
@RunWith(AndroidJUnit4.class)
public final class CastParamsTest {

  private static final String RECEIVER_APPLICATION_ID = "12345678";
  private static final Boolean REMOTE_TO_LOCAL_ENABLED = true;

  private Context context;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
    context.getApplicationInfo().targetSdkVersion =
        CastParams.SDK_VERSION_MIN_SHOW_OUTPUT_SWITCHER_IN_APP;
    CastParams.sdkVersionForTesting = CastParams.SDK_VERSION_MIN_SHOW_OUTPUT_SWITCHER_IN_APP;
  }

  @Test
  public void toCastOptionsModifier_defaultValues_hasDefaultValues() {
    CastParams castParams = new CastParams.Builder().build();

    CastOptions.Modifier modifier = castParams.toCastOptionsModifier(context);

    assertThat(castParams.getReceiverApplicationId()).isNull();
    assertThat(castParams.getRemoteToLocalEnabled()).isNull();
    assertThat(castParams.getShowSystemOutputSwitcherOnCastButtonClick()).isNull();
    assertThat(modifier.getReceiverApplicationId()).isNull();
    assertThat(modifier.getRemoteToLocalEnabled()).isNull();
    assertThat(modifier.getShowSystemOutputSwitcherOnCastIconClick()).isTrue();
  }

  @Test
  public void toCastOptionsModifier_withTargetSdkBelowMin_defaultsToFalse() {
    context.getApplicationInfo().targetSdkVersion =
        CastParams.SDK_VERSION_MIN_SHOW_OUTPUT_SWITCHER_IN_APP - 1;
    CastParams castParams = new CastParams.Builder().build();

    CastOptions.Modifier modifier = castParams.toCastOptionsModifier(context);

    assertThat(modifier.getShowSystemOutputSwitcherOnCastIconClick()).isFalse();
  }

  @Test
  public void toCastOptionsModifier_nullContext_defaultsShowOutputSwitcherToFalse() {
    CastParams castParams = new CastParams.Builder().build();

    CastOptions.Modifier modifier = castParams.toCastOptionsModifier(/* context= */ null);

    assertThat(modifier.getShowSystemOutputSwitcherOnCastIconClick()).isFalse();
  }

  @Test
  public void toCastOptionsModifier_withSdkValuesAtMin_showOutputSwitcherDefaultsToTrue() {
    CastParams castParams = new CastParams.Builder().build();

    CastOptions.Modifier modifier = castParams.toCastOptionsModifier(context);

    assertThat(modifier.getShowSystemOutputSwitcherOnCastIconClick()).isTrue();
  }

  @Test
  public void toCastOptionsModifier_withExplicitValueAndTargetSdkAtMin_overridesDefault() {
    CastParams castParams =
        new CastParams.Builder().setShowSystemOutputSwitcherOnCastButtonClick(false).build();

    CastOptions.Modifier modifier = castParams.toCastOptionsModifier(context);

    assertThat(modifier.getShowSystemOutputSwitcherOnCastIconClick()).isFalse();
  }

  @Test
  public void toCastOptionsModifier_withExplicitValueAndTargetSdkBelowMin_overridesDefault() {
    context.getApplicationInfo().targetSdkVersion =
        CastParams.SDK_VERSION_MIN_SHOW_OUTPUT_SWITCHER_IN_APP - 1;
    CastParams castParams =
        new CastParams.Builder().setShowSystemOutputSwitcherOnCastButtonClick(true).build();

    CastOptions.Modifier modifier = castParams.toCastOptionsModifier(context);

    assertThat(modifier.getShowSystemOutputSwitcherOnCastIconClick()).isTrue();
  }

  @Test
  public void toCastOptionsModifier_onOldApiVersion_ignoresSetShowOutputSwitcherOnCastIconClick() {
    CastParams.sdkVersionForTesting = CastParams.SDK_VERSION_MIN_SHOW_OUTPUT_SWITCHER_IN_APP - 1;
    context.getApplicationInfo().targetSdkVersion =
        CastParams.SDK_VERSION_MIN_SHOW_OUTPUT_SWITCHER_IN_APP - 1;
    CastParams castParams =
        new CastParams.Builder().setShowSystemOutputSwitcherOnCastButtonClick(true).build();

    CastOptions.Modifier modifier = castParams.toCastOptionsModifier(context);

    assertThat(modifier.getShowSystemOutputSwitcherOnCastIconClick()).isNull();
  }

  @Test
  public void toCastOptionsModifier_withReceiverApplicationId_hasReceiverApplicationId() {
    context.getApplicationInfo().targetSdkVersion =
        CastParams.SDK_VERSION_MIN_SHOW_OUTPUT_SWITCHER_IN_APP - 1;
    CastParams castParams =
        new CastParams.Builder().setReceiverApplicationId(RECEIVER_APPLICATION_ID).build();

    CastOptions.Modifier modifier = castParams.toCastOptionsModifier(context);

    assertThat(castParams.getReceiverApplicationId()).isEqualTo(RECEIVER_APPLICATION_ID);
    assertThat(modifier.getReceiverApplicationId()).isEqualTo(RECEIVER_APPLICATION_ID);
  }

  @Test
  public void toCastOptionsModifier_withRemoteToLocalEnabled_hasRemoteToLocalEnabled() {
    context.getApplicationInfo().targetSdkVersion =
        CastParams.SDK_VERSION_MIN_SHOW_OUTPUT_SWITCHER_IN_APP - 1;
    CastParams castParams =
        new CastParams.Builder().setRemoteToLocalEnabled(REMOTE_TO_LOCAL_ENABLED).build();

    CastOptions.Modifier modifier = castParams.toCastOptionsModifier(context);

    assertThat(castParams.getRemoteToLocalEnabled()).isEqualTo(REMOTE_TO_LOCAL_ENABLED);
    assertThat(modifier.getRemoteToLocalEnabled()).isEqualTo(REMOTE_TO_LOCAL_ENABLED);
  }
}
