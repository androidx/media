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

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.gms.cast.framework.CastOptions;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link CastParams}. */
@RunWith(AndroidJUnit4.class)
public final class CastParamsTest {

  private static final String RECEIVER_APPLICATION_ID = "12345678";
  private static final Boolean REMOTE_TO_LOCAL_ENABLED = true;

  @Test
  public void build_defaultValues_hasDefaultValues() {
    CastParams castParams = new CastParams.Builder().build();
    CastOptions.Modifier modifier = castParams.toCastOptionsModifier();

    assertThat(castParams.getReceiverApplicationId()).isNull();
    assertThat(castParams.getRemoteToLocalEnabled()).isNull();
    assertThat(modifier.getReceiverApplicationId()).isNull();
    assertThat(modifier.getRemoteToLocalEnabled()).isNull();
  }

  @Test
  public void build_withReceiverApplicationId_hasReceiverApplicationId() {
    CastParams castParams =
        new CastParams.Builder().setReceiverApplicationId(RECEIVER_APPLICATION_ID).build();
    CastOptions.Modifier modifier = castParams.toCastOptionsModifier();

    assertThat(castParams.getReceiverApplicationId()).isEqualTo(RECEIVER_APPLICATION_ID);
    assertThat(modifier.getReceiverApplicationId()).isEqualTo(RECEIVER_APPLICATION_ID);
  }

  @Test
  public void build_withRemoteToLocalEnabled_hasRemoteToLocalEnabled() {
    CastParams castParams =
        new CastParams.Builder().setRemoteToLocalEnabled(REMOTE_TO_LOCAL_ENABLED).build();
    CastOptions.Modifier modifier = castParams.toCastOptionsModifier();

    assertThat(castParams.getRemoteToLocalEnabled()).isEqualTo(REMOTE_TO_LOCAL_ENABLED);
    assertThat(modifier.getRemoteToLocalEnabled()).isEqualTo(REMOTE_TO_LOCAL_ENABLED);
  }
}
