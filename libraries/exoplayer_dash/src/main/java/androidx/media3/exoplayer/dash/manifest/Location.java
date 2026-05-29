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
package androidx.media3.exoplayer.dash.manifest;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import java.util.Objects;

/** Represents a Location element, as defined by ISO 23009-1. */
@UnstableApi
public final class Location {

  /** The URL. */
  public final String url;

  /** The service location. */
  public final String serviceLocation;

  /**
   * Creates an instance using the URL as the service location.
   *
   * @param url The URL.
   */
  public Location(String url) {
    this(url, /* serviceLocation= */ url);
  }

  /**
   * Creates an instance.
   *
   * @param url The URL.
   * @param serviceLocation The service location.
   */
  public Location(String url, String serviceLocation) {
    this.url = url;
    this.serviceLocation = serviceLocation;
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Location)) {
      return false;
    }
    Location location = (Location) o;
    return Objects.equals(url, location.url)
        && Objects.equals(serviceLocation, location.serviceLocation);
  }

  @Override
  public int hashCode() {
    return Objects.hash(url, serviceLocation);
  }
}
