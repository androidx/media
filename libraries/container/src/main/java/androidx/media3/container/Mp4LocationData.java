/*
 * Copyright (C) 2023 The Android Open Source Project
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
package androidx.media3.container;

import static com.google.common.base.Preconditions.checkArgument;

import androidx.annotation.FloatRange;
import androidx.annotation.Nullable;
import androidx.media3.common.Metadata;
import androidx.media3.common.util.UnstableApi;

/**
 * Stores MP4 location data.
 *
 * <p>The location data is typically read/written in the "udta" box (user data box, defined in
 * ISO/IEC 14496-12).
 */
@UnstableApi
public final class Mp4LocationData implements Metadata.Entry {

  /** Represents an unset or unknown altitude. */
  public static final float ALTITUDE_UNSET = -Float.MAX_VALUE;

  /** The latitude, in degrees. */
  public final float latitude;

  /** The longitude, in degrees. */
  public final float longitude;

  /** The altitude, in meters, or {@link #ALTITUDE_UNSET} if unset. */
  public final float altitude;

  /**
   * Creates an instance with latitude and longitude only.
   *
   * <p>The {@link #altitude} is set to {@link #ALTITUDE_UNSET}.
   *
   * @param latitude The latitude, in degrees. Its value must be in the range [-90, 90].
   * @param longitude The longitude, in degrees. Its value must be in the range [-180, 180].
   */
  public Mp4LocationData(
      @FloatRange(from = -90.0, to = 90.0) float latitude,
      @FloatRange(from = -180.0, to = 180.0) float longitude) {
    this(latitude, longitude, ALTITUDE_UNSET);
  }

  /**
   * Creates an instance with latitude, longitude, and altitude.
   *
   * @param latitude The latitude, in degrees. Its value must be in the range [-90, 90].
   * @param longitude The longitude, in degrees. Its value must be in the range [-180, 180].
   * @param altitude The altitude, in meters, or {@link #ALTITUDE_UNSET} if unset.
   */
  public Mp4LocationData(
      @FloatRange(from = -90.0, to = 90.0) float latitude,
      @FloatRange(from = -180.0, to = 180.0) float longitude,
      float altitude) {
    checkArgument(
        latitude >= -90.0f && latitude <= 90.0f && longitude >= -180.0f && longitude <= 180.0f,
        "Invalid latitude or longitude");
    this.latitude = latitude;
    this.longitude = longitude;
    this.altitude = altitude;
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null || getClass() != obj.getClass()) {
      return false;
    }
    Mp4LocationData other = (Mp4LocationData) obj;
    return Float.compare(latitude, other.latitude) == 0
        && Float.compare(longitude, other.longitude) == 0
        && Float.compare(altitude, other.altitude) == 0;
  }

  @Override
  public int hashCode() {
    int result = 17;
    result = 31 * result + Float.hashCode(latitude);
    result = 31 * result + Float.hashCode(longitude);
    result = 31 * result + Float.hashCode(altitude);
    return result;
  }

  @Override
  public String toString() {
    return "xyz: latitude="
        + latitude
        + ", longitude="
        + longitude
        + (altitude != ALTITUDE_UNSET ? ", altitude=" + altitude : "");
  }
}
