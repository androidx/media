/*
 * Copyright (C) 2016 The Android Open Source Project
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
package androidx.media3.exoplayer.dash.manifest;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import java.util.Objects;

/** Represents a UTCTiming element. */
@UnstableApi
public final class UtcTimingElement {

  public final String schemeIdUri;
  public final String value;

  public UtcTimingElement(String schemeIdUri, String value) {
    this.schemeIdUri = schemeIdUri;
    this.value = value;
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof UtcTimingElement)) {
      return false;
    }
    UtcTimingElement other = (UtcTimingElement) obj;
    return Objects.equals(this.schemeIdUri, other.schemeIdUri)
        && Objects.equals(this.value, other.value);
  }

  @Override
  public int hashCode() {
    return Objects.hash(schemeIdUri, value);
  }

  @Override
  public String toString() {
    return schemeIdUri + ", " + value;
  }
}
