/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.extractor.mp3;

import static java.lang.annotation.ElementType.TYPE_USE;

import android.annotation.SuppressLint;
import androidx.annotation.IntDef;
import androidx.media3.common.Metadata;
import androidx.media3.common.util.UnstableApi;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Objects;

/** Representation of the ReplayGain data stored in a LAME Xing or Info frame. */
@UnstableApi
public final class Mp3InfoReplayGain implements Metadata.Entry {
  /**
   * 32 bit floating point "Peak signal amplitude".
   *
   * <p>1.0 is maximal signal amplitude store-able in decoding format. 0.8 is 80% of maximal signal
   * amplitude store-able in decoding format. 1.5 is 150% of maximal signal amplitude store-able in
   * decoding format.
   *
   * <p>A value above 1.0 can occur for example due to "true peak" measurement. A value of 0.0 means
   * the peak signal amplitude is unknown.
   */
  public final float peak;

  /** A gain field can store one gain adjustment with name and originator metadata. */
  public static final class GainField {
    /** This gain field contains no valid data, and should be ignored. */
    public static final int NAME_INVALID = 0;

    /**
     * This gain field contains a gain adjustment that will make all the tracks sound equally loud
     * (as they do on the radio, hence the name!). If the ReplayGain is calculated on a
     * track-by-track basis (i.e. an individual ReplayGain calculation is carried out for each
     * track), this will be the result.
     */
    public static final int NAME_RADIO = 1;

    /**
     * The problem with the "Radio" setting is that tracks which should be quiet will be brought up
     * to the level of all the rest.
     *
     * <p>To solve this problem, the "Audiophile" setting represents the ideal listening gain for
     * each track. ReplayGain can have a good guess at this too, by reading the entire CD, and
     * calculating a single gain adjustment for the whole disc. This works because quiet tracks then
     * stay quieter than the rest, since the gain won't be changed for each track. It still solves
     * the basic problem (annoying, unwanted level differences between discs) because quiet or loud
     * discs are still adjusted overall.
     *
     * <p>Where ReplayGain will fail is if you have an entire CD of quiet music. It will bring it up
     * to an average level. This is why the "Audiophile" Replay Gain adjustment must be user
     * adjustable. The ReplayGain whole disc value represents a good guess, and should be stored in
     * the file. Later, the user can tweak it if required. If the file has originated from the
     * artist, then the "Audiophile" setting can be specified by the artist. Naturally, the user is
     * free to change the value if they desire.
     */
    public static final int NAME_AUDIOPHILE = 2;

    /** The origin of this gain adjustment is not known. */
    public static final int ORIGINATOR_UNKNOWN = 0;

    /** This gain adjustment was manually determined by the artist. */
    public static final int ORIGINATOR_ARTIST = 1;

    /** This gain adjustment was manually determined by the user. */
    public static final int ORIGINATOR_USER = 2;

    /** This gain adjustment was automatically determined by the ReplayGain algorithm. */
    public static final int ORIGINATOR_REPLAYGAIN = 3;

    /** This gain adjustment was automatically determined by a simple RMS algorithm. */
    public static final int ORIGINATOR_SIMPLE_RMS = 4;

    /** Creates a gain field from already unpacked values. */
    public GainField(@Name int name, @Originator int originator, float gain) {
      this.name = name;
      this.originator = originator;
      this.gain = gain;
    }

    /** Creates a gain field from the packed representation. */
    @SuppressLint("WrongConstant")
    public GainField(short field) {
      this.name = (field >> 13) & 7;
      this.originator = (field >> 10) & 7;
      this.gain = ((field & 0x1ff) * ((field & 0x200) != 0 ? -1 : 1)) / 10f;
    }

    @Documented
    @Retention(RetentionPolicy.SOURCE)
    @Target(TYPE_USE)
    @IntDef({NAME_INVALID, NAME_RADIO, NAME_AUDIOPHILE})
    public @interface Name {}

    @Documented
    @Retention(RetentionPolicy.SOURCE)
    @Target(TYPE_USE)
    @IntDef({
      ORIGINATOR_UNKNOWN,
      ORIGINATOR_ARTIST,
      ORIGINATOR_USER,
      ORIGINATOR_REPLAYGAIN,
      ORIGINATOR_SIMPLE_RMS
    })
    public @interface Originator {}

    /**
     * Name/type of the gain field.
     *
     * <p>If equal to {@link #NAME_INVALID}, or an unknown name, the entire {@link GainField} should
     * be ignored.
     */
    public final @Name int name;

    /**
     * Originator of the gain field, i.e. who determined the value / in what way it was determined.
     *
     * <p>Either a human (user / artist) set the value according to their preferences, or an
     * algorithm like ReplayGain or simple RMS average was used to determine it.
     */
    public final @Originator int originator;

    /**
     * Absolute gain adjustment in decibels.
     *
     * <p>Positive values mean the signal should be amplified, negative values mean it should be
     * attenuated.
     *
     * <p>Due to limitations of the storage format, this is only accurate to the first decimal
     * place.
     */
    public final float gain;

    /**
     * @return Whether the name field is set to a valid value, hence, whether this gain field should
     *     be considered or not. If false, the entire field should be ignored.
     */
    public boolean isValid() {
      return name == NAME_RADIO || name == NAME_AUDIOPHILE;
    }

    @Override
    public String toString() {
      return "GainField{" + "name=" + name + ", originator=" + originator + ", gain=" + gain + '}';
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof GainField)) {
        return false;
      }
      GainField gainField = (GainField) o;
      return name == gainField.name
          && originator == gainField.originator
          && Float.compare(gain, gainField.gain) == 0;
    }

    @Override
    public int hashCode() {
      return Objects.hash(name, originator, gain);
    }
  }

  /** The first of two gain fields in the LAME MP3 Info header. */
  public GainField field1;

  /** The second of two gain fields in the LAME MP3 Info header. */
  public GainField field2;

  /** Creates the gain field from already unpacked values. */
  public Mp3InfoReplayGain(float peak, GainField field1, GainField field2) {
    this.peak = peak;
    this.field1 = field1;
    this.field2 = field2;
  }

  /** Creates the gain fields from the packed representation. */
  public Mp3InfoReplayGain(float peak, short field1, short field2) {
    this(peak, new GainField(field1), new GainField(field2));
  }

  @Override
  public String toString() {
    return "ReplayGain Xing/Info: "
        + "peak="
        + peak
        + ", field 1="
        + field1
        + ", field 2="
        + field2;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof Mp3InfoReplayGain)) {
      return false;
    }
    Mp3InfoReplayGain that = (Mp3InfoReplayGain) o;
    return Float.compare(peak, that.peak) == 0
        && Objects.equals(field1, that.field1)
        && Objects.equals(field2, that.field2);
  }

  @Override
  public int hashCode() {
    return Objects.hash(peak, field1, field2);
  }
}
