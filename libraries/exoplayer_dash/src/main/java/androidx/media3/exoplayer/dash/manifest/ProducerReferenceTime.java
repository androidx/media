/*
 * Copyright (C) 2026 The Android Open Source Project
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

import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.SOURCE;

import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.Objects;

/**
 * Represents a parsed ProducerReferenceTime element as defined in ISO/IEC 23009-1:2022 Section
 * 5.12.
 */
@UnstableApi
public final class ProducerReferenceTime {

  /** Value of {@link #id} indicating no value is set. */
  public static final long ID_UNSET = -1;

  /** The type of the producer reference time source clock. */
  @Documented
  @Retention(SOURCE)
  @Target(TYPE_USE)
  @IntDef({TYPE_ENCODER, TYPE_CAPTURED, TYPE_APPLICATION})
  public @interface ProducerReferenceTimeType {}

  /** Indicates wall-clock time was established at the encoder. */
  public static final int TYPE_ENCODER = 0;

  /** Indicates wall-clock time was established when the media was captured. */
  public static final int TYPE_CAPTURED = 1;

  /** Indicates wall-clock time was established at the application level. */
  public static final int TYPE_APPLICATION = 2;

  /** An identifier for the producer reference time, or {@link #ID_UNSET} if not specified. */
  public final long id;

  /** Whether the producer reference time is also present inband in the media segments. */
  public final boolean inband;

  /** The {@link ProducerReferenceTimeType} indicating the type of timing source. */
  public final @ProducerReferenceTimeType int type;

  /**
   * The application scheme URI if {@link #type} is {@link #TYPE_APPLICATION}, or {@code null}
   * otherwise.
   */
  @Nullable public final String applicationScheme;

  /**
   * The wall-clock time as a Unix epoch timestamp in milliseconds, or {@link C#TIME_UNSET} if not
   * specified.
   */
  public final long wallClockTimeMs;

  /**
   * The media presentation time corresponding to {@link #wallClockTimeMs}, in the track's
   * timescale, or {@link C#TIME_UNSET} if not specified.
   */
  public final long presentationTime;

  /** An optional {@link UtcTimingElement} specifying the UTC timing synchronization source. */
  @Nullable public final UtcTimingElement utcTiming;

  /**
   * Constructs a new instance.
   *
   * @param id An identifier for the producer reference time, or {@link #ID_UNSET} if not specified.
   * @param inband Whether the producer reference time is also present inband.
   * @param type The {@link ProducerReferenceTimeType}.
   * @param applicationScheme The application scheme URI, or null.
   * @param wallClockTimeMs The wall-clock time in milliseconds since epoch, or {@link C#TIME_UNSET}
   *     if not specified.
   * @param presentationTime The presentation time in track timescale, or {@link C#TIME_UNSET} if
   *     not specified.
   * @param utcTiming An optional {@link UtcTimingElement}, or null.
   */
  public ProducerReferenceTime(
      long id,
      boolean inband,
      @ProducerReferenceTimeType int type,
      @Nullable String applicationScheme,
      long wallClockTimeMs,
      long presentationTime,
      @Nullable UtcTimingElement utcTiming) {
    this.id = id;
    this.inband = inband;
    this.type = type;
    this.applicationScheme = applicationScheme;
    this.wallClockTimeMs = wallClockTimeMs;
    this.presentationTime = presentationTime;
    this.utcTiming = utcTiming;
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof ProducerReferenceTime)) {
      return false;
    }
    ProducerReferenceTime other = (ProducerReferenceTime) obj;
    return this.id == other.id
        && this.inband == other.inband
        && this.type == other.type
        && this.wallClockTimeMs == other.wallClockTimeMs
        && this.presentationTime == other.presentationTime
        && Objects.equals(this.applicationScheme, other.applicationScheme)
        && Objects.equals(this.utcTiming, other.utcTiming);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        id, inband, type, applicationScheme, wallClockTimeMs, presentationTime, utcTiming);
  }
}
