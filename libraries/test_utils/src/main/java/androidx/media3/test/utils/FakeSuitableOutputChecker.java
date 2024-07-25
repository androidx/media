/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP;
import static androidx.media3.common.util.Assertions.checkState;

import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.SuitableOutputChecker;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

/** Fake implementation for {@link SuitableOutputChecker}. */
@RestrictTo(LIBRARY_GROUP)
@UnstableApi
@RequiresApi(35)
public final class FakeSuitableOutputChecker implements SuitableOutputChecker {

  /** Builder for {@link FakeSuitableOutputChecker} instance. */
  public static final class Builder {

    private boolean isSuitableOutputAvailable;

    /**
     * Sets the initial value to be returned from {@link
     * SuitableOutputChecker#isSelectedRouteSuitableForPlayback()}. The default value is false.
     */
    @CanIgnoreReturnValue
    public Builder setIsSuitableExternalOutputAvailable(boolean isSuitableOutputAvailable) {
      this.isSuitableOutputAvailable = isSuitableOutputAvailable;
      return this;
    }

    /**
     * Builds a {@link FakeSuitableOutputChecker} with the builder's current values.
     *
     * @return The built {@link FakeSuitableOutputChecker}.
     */
    public FakeSuitableOutputChecker build() {
      return new FakeSuitableOutputChecker(isSuitableOutputAvailable);
    }
  }

  private final boolean isSuitableOutputAvailable;
  private boolean isEnabled;

  public FakeSuitableOutputChecker(boolean isSuitableOutputAvailable) {
    this.isSuitableOutputAvailable = isSuitableOutputAvailable;
  }

  @Override
  public void setEnabled(boolean isEnabled) {
    this.isEnabled = isEnabled;
  }

  @Override
  public boolean isSelectedRouteSuitableForPlayback() {
    checkState(isEnabled, "SuitableOutputChecker is not enabled");
    return isSuitableOutputAvailable;
  }
}
