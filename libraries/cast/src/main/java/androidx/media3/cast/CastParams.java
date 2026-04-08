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

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import com.google.android.gms.cast.framework.CastOptions;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

/**
 * Contains Cast configuration options.
 *
 * <p>Options contained in this class can be passed to {@link Cast#initialize(CastParams)} in order
 * to set up the Cast configuration as part of initialization, or to change the Cast configuration
 * after initialization has completed.
 *
 * <p>Options set in this class take precedence over Cast options, such as {@link
 * DefaultCastOptionsProvider}, or Manifest-provided Cast options. However, options not set in this
 * class (modeled using {@code null}) fall back to Manifest-provided options, if available, or
 * {@link DefaultCastOptionsProvider} options otherwise.
 *
 * @see Cast#initialize(CastParams)
 */
@UnstableApi
public final class CastParams {

  public static final CastParams DEFAULT = new Builder().build();

  /** Builder for {@link CastParams}. */
  @UnstableApi
  public static final class Builder {

    @Nullable String receiverApplicationId;
    @Nullable Boolean remoteToLocalEnabled;

    /** See {@link #getReceiverApplicationId()}. */
    @CanIgnoreReturnValue
    public Builder setReceiverApplicationId(String receiverApplicationId) {
      this.receiverApplicationId = receiverApplicationId;
      return this;
    }

    /** See {@link #getRemoteToLocalEnabled()}. */
    @CanIgnoreReturnValue
    public Builder setRemoteToLocalEnabled(boolean remoteToLocalEnabled) {
      this.remoteToLocalEnabled = remoteToLocalEnabled;
      return this;
    }

    /** Builds the {@link CastParams}. */
    public CastParams build() {
      return new CastParams(this);
    }
  }

  @Nullable private final String receiverApplicationId;
  @Nullable private final Boolean remoteToLocalEnabled;

  private CastParams(Builder builder) {
    receiverApplicationId = builder.receiverApplicationId;
    remoteToLocalEnabled = builder.remoteToLocalEnabled;
  }

  /**
   * Returns the Cast receiver application ID, or {@code null} if not set.
   *
   * <p>If not set, the receiver application ID defaults to the one set in the Manifest-provided
   * Cast options if provided, or the one set in {@link DefaultCastOptionsProvider} otherwise.
   *
   * @see Builder#setReceiverApplicationId(String)
   */
  @Nullable
  public String getReceiverApplicationId() {
    return receiverApplicationId;
  }

  /**
   * Returns whether to enable the remote to local feature, or {@code null} if not set.
   *
   * <p>If not set, the "remote to local enabled" config defaults to the one set in the
   * Manifest-provided Cast options if provided, or the one set in {@link
   * DefaultCastOptionsProvider} otherwise.
   *
   * @see Builder#setRemoteToLocalEnabled(boolean)
   */
  @Nullable
  public Boolean getRemoteToLocalEnabled() {
    return remoteToLocalEnabled;
  }

  /* package */ CastOptions.Modifier toCastOptionsModifier() {
    CastOptions.Modifier.Builder modifierBuilder = new CastOptions.Modifier.Builder();
    if (receiverApplicationId != null) {
      CastOptions.Modifier.Builder unused =
          modifierBuilder.setReceiverApplicationId(receiverApplicationId);
    }
    if (remoteToLocalEnabled != null) {
      CastOptions.Modifier.Builder unused =
          modifierBuilder.setRemoteToLocalEnabled(remoteToLocalEnabled);
    }
    return modifierBuilder.build();
  }
}
