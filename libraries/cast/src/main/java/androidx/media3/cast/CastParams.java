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

import android.content.Context;
import android.os.Build;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
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

  /**
   * Holds the minimum Android API version required to show the SystemUI Output Switcher when the
   * user taps on the Cast button.
   *
   * <p>This same value is also compared against the app's target SDK to decide the default Cast
   * button behavior.
   *
   * @see #getShowSystemOutputSwitcherOnCastButtonClick()
   */
  @VisibleForTesting
  /* package */ static final int SDK_VERSION_MIN_SHOW_OUTPUT_SWITCHER_IN_APP = 37;

  // TODO: b/500989268 - Remove the sdkVersionForTesting field once robolectric supports @Config(sdk
  // = 37).
  @VisibleForTesting /* package */ static int sdkVersionForTesting = Build.VERSION.SDK_INT;

  public static final CastParams DEFAULT = new Builder().build();

  /** Builder for {@link CastParams}. */
  @UnstableApi
  public static final class Builder {

    @Nullable String receiverApplicationId;
    @Nullable Boolean remoteToLocalEnabled;
    @Nullable Boolean showSystemOutputSwitcherOnCastButtonClick;

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

    /** See {@link CastParams#getShowSystemOutputSwitcherOnCastButtonClick()}. */
    @CanIgnoreReturnValue
    public Builder setShowSystemOutputSwitcherOnCastButtonClick(
        boolean showSystemOutputSwitcherOnCastButtonClick) {
      this.showSystemOutputSwitcherOnCastButtonClick = showSystemOutputSwitcherOnCastButtonClick;
      return this;
    }

    /** Builds the {@link CastParams}. */
    public CastParams build() {
      return new CastParams(this);
    }
  }

  @Nullable private final String receiverApplicationId;
  @Nullable private final Boolean remoteToLocalEnabled;
  @Nullable private final Boolean showSystemOutputSwitcherOnCastButtonClick;

  private CastParams(Builder builder) {
    receiverApplicationId = builder.receiverApplicationId;
    remoteToLocalEnabled = builder.remoteToLocalEnabled;
    showSystemOutputSwitcherOnCastButtonClick = builder.showSystemOutputSwitcherOnCastButtonClick;
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

  /**
   * Returns whether to show system output switcher when clicking on the cast button, or {@code
   * null} if not set.
   *
   * <p>This configuration is ignored on devices running API level 36 or older, and the cast button
   * always launches the in-app device picker. On devices running API level 37 or newer, the value
   * set by the application is respected, but the default value (the value used when the application
   * doesn't call {@link Builder#setShowSystemOutputSwitcherOnCastButtonClick}) depends on the
   * target SDK, and on the way the app configures Cast options:
   *
   * <ul>
   *   <li>For applications using Manifest-provided Cast options (as opposed to calling {@link
   *       Cast#initialize}), the default value is false.
   *   <li>For applications using {@link Cast#initialize} whose target SDK is 36 or older, the
   *       default value is false.
   *   <li>For applications using {@link Cast#initialize} whose target SDK is 37 or newer, the
   *       default value is true.
   * </ul>
   *
   * @see Builder#setShowSystemOutputSwitcherOnCastButtonClick(boolean)
   */
  @Nullable
  public Boolean getShowSystemOutputSwitcherOnCastButtonClick() {
    return showSystemOutputSwitcherOnCastButtonClick;
  }

  /**
   * Converts this object to its Cast SDK CastOptions.Modifier equivalent.
   *
   * @param context The {@link Context}. May be null if not available.
   * @return The Cast SDK CastOptions.Modifier equivalent of this object.
   */
  /* package */ CastOptions.Modifier toCastOptionsModifier(@Nullable Context context) {
    CastOptions.Modifier.Builder modifierBuilder = new CastOptions.Modifier.Builder();
    if (receiverApplicationId != null) {
      CastOptions.Modifier.Builder unused =
          modifierBuilder.setReceiverApplicationId(receiverApplicationId);
    }
    if (remoteToLocalEnabled != null) {
      CastOptions.Modifier.Builder unused =
          modifierBuilder.setRemoteToLocalEnabled(remoteToLocalEnabled);
    }
    if (sdkVersionForTesting >= SDK_VERSION_MIN_SHOW_OUTPUT_SWITCHER_IN_APP) {
      int targetSdkVersion = context != null ? context.getApplicationInfo().targetSdkVersion : -1;
      boolean showSystemOutputSwitcherOnCastButtonClick =
          this.showSystemOutputSwitcherOnCastButtonClick != null
              ? this.showSystemOutputSwitcherOnCastButtonClick
              : targetSdkVersion >= SDK_VERSION_MIN_SHOW_OUTPUT_SWITCHER_IN_APP;
      CastOptions.Modifier.Builder unused =
          modifierBuilder.setShowSystemOutputSwitcherOnCastIconClick(
              showSystemOutputSwitcherOnCastButtonClick);
    }
    return modifierBuilder.build();
  }
}
