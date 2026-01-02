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

    /** The name of a gain field. */
    @Documented
    @Retention(RetentionPolicy.SOURCE)
    @Target(TYPE_USE)
    @IntDef(
        value = {NAME_UNSET, NAME_RADIO, NAME_AUDIOPHILE},
        open = true)
    public @interface Name {}

    /** This gain field contains no valid data, and should be ignored. */
    public static final int NAME_UNSET = 0;

    /**
     * A gain adjustment that makes all the tracks sound equally loud.
     *
     * <p>This behaves like tracks do on the radio, hence the name. If the ReplayGain is calculated
     * on a track-by-track basis (i.e. an individual ReplayGain calculation is carried out for each
     * track), this will be the result.
     */
    public static final int NAME_RADIO = 1;

    /**
     * A gain adjustment that represents the ideal listening gain for each track.
     *
     * <p>The problem with {@link #NAME_RADIO} is that tracks which should be quiet will be brought
     * up to the level of all the rest.
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

    /** The originator of a gain field. */
    @Documented
    @Retention(RetentionPolicy.SOURCE)
    @Target(TYPE_USE)
    @IntDef(
        value = {
          ORIGINATOR_UNSET,
          ORIGINATOR_ARTIST,
          ORIGINATOR_USER,
          ORIGINATOR_REPLAYGAIN,
          ORIGINATOR_SIMPLE_RMS
        },
        open = true)
    public @interface Originator {}

    /** The origin of this gain adjustment is not set. */
    public static final int ORIGINATOR_UNSET = 0;

    /** This gain adjustment was manually determined by the artist. */
    public static final int ORIGINATOR_ARTIST = 1;

    /** This gain adjustment was manually determined by the user. */
    public static final int ORIGINATOR_USER = 2;

    /** This gain adjustment was automatically determined by the ReplayGain algorithm. */
    public static final int ORIGINATOR_REPLAYGAIN = 3;

    /** This gain adjustment was automatically determined by a simple RMS algorithm. */
    public static final int ORIGINATOR_SIMPLE_RMS = 4;

    /**
     * Name/type of the gain field.
     *
     * <p>If equal to {@link #NAME_UNSET}, or an unknown name, the entire {@link GainField} should
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

    /** Parses an instance from the packed representation. */
    // Lint incorrectly thinks we're doing bitwise flag manipulation.
    @SuppressWarnings("WrongConstant")
    private GainField(int field) {
      name = (field >> 13) & 7;
      originator = (field >> 10) & 7;
      gain = ((field & 0x1ff) * ((field & 0x200) != 0 ? -1 : 1)) / 10f;
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
      int result = name;
      result = 31 * result + originator;
      result = 31 * result + Float.hashCode(gain);
      return result;
    }
  }

  /** The first of two gain fields in the LAME MP3 Info header. */
  public GainField field1;

  /** The second of two gain fields in the LAME MP3 Info header. */
  public GainField field2;

  /** Creates the gain fields from the packed representation. */
  public Mp3InfoReplayGain(float peak, int field1, int field2) {
    this.peak = peak;
    this.field1 = new GainField(field1);
    this.field2 = new GainField(field2);
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
    int result = Float.hashCode(peak);
    result = 31 * result + field1.hashCode();
    result = 31 * result + field2.hashCode();
    return result;
  }
}
