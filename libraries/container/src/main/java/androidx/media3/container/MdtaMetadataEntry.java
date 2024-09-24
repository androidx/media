/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static androidx.media3.common.util.Assertions.checkArgument;
import static androidx.media3.common.util.Assertions.checkState;

import android.os.Parcel;
import android.os.Parcelable;
import androidx.annotation.Nullable;
import androidx.media3.common.Metadata;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import com.google.common.base.Joiner;
import com.google.common.primitives.Ints;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Stores extensible metadata with handler type 'mdta'. See also the QuickTime File Format
 * Specification.
 */
@UnstableApi
public final class MdtaMetadataEntry implements Metadata.Entry {

  /** Key for the capture frame rate (in frames per second). */
  public static final String KEY_ANDROID_CAPTURE_FPS = "com.android.capture.fps";

  // TODO: b/345219017 - Add depth/editing file format spec link after its published.
  /** Key for editable tracks box (edvd) offset. */
  public static final String KEY_EDITABLE_TRACKS_OFFSET = "editable.tracks.offset";

  /** Key for editable tracks box (edvd) length. */
  public static final String KEY_EDITABLE_TRACKS_LENGTH = "editable.tracks.length";

  /** Key for editable tracks map. */
  public static final String KEY_EDITABLE_TRACKS_MAP = "editable.tracks.map";

  /** Key for editable tracks samples location. */
  public static final String KEY_EDITABLE_TRACKS_SAMPLES_LOCATION =
      "editable.tracks.samples.location";

  /** The editable tracks samples are in edit data MP4. */
  public static final byte EDITABLE_TRACKS_SAMPLES_LOCATION_IN_EDIT_DATA_MP4 = 0;

  /** The editable tracks samples are interleaved with the primary tracks samples. */
  public static final byte EDITABLE_TRACKS_SAMPLES_LOCATION_INTERLEAVED = 1;

  /** The default locale indicator which implies all speakers in all countries. */
  public static final int DEFAULT_LOCALE_INDICATOR = 0;

  /** The type indicator to use when no type needs to be indicated. */
  public static final int TYPE_INDICATOR_RESERVED = 0;

  /** The type indicator for UTF-8 string. */
  public static final int TYPE_INDICATOR_STRING = 1;

  /** The type indicator for Float32. */
  public static final int TYPE_INDICATOR_FLOAT32 = 23;

  /** The type indicator for 32-bit signed integer. */
  public static final int TYPE_INDICATOR_INT32 = 67;

  /** The type indicator for an 8-bit unsigned integer. */
  public static final int TYPE_INDICATOR_8_BIT_UNSIGNED_INT = 75;

  /** The type indicator for 64-bit unsigned integer. */
  public static final int TYPE_INDICATOR_UNSIGNED_INT64 = 78;

  /** The metadata key name. */
  public final String key;

  /** The payload. The interpretation of the value depends on {@link #typeIndicator}. */
  public final byte[] value;

  /** The four byte locale indicator. */
  public final int localeIndicator;

  /** The four byte type indicator. */
  public final int typeIndicator;

  /**
   * Creates a new metadata entry for the specified metadata key/value with {@linkplain
   * #DEFAULT_LOCALE_INDICATOR default locale indicator}.
   */
  public MdtaMetadataEntry(String key, byte[] value, int typeIndicator) {
    this(key, value, DEFAULT_LOCALE_INDICATOR, typeIndicator);
  }

  /** Creates a new metadata entry for the specified metadata key/value. */
  public MdtaMetadataEntry(String key, byte[] value, int localeIndicator, int typeIndicator) {
    validateData(key, value, typeIndicator);
    this.key = key;
    this.value = value;
    this.localeIndicator = localeIndicator;
    this.typeIndicator = typeIndicator;
  }

  private MdtaMetadataEntry(Parcel in) {
    key = Util.castNonNull(in.readString());
    value = Util.castNonNull(in.createByteArray());
    localeIndicator = in.readInt();
    typeIndicator = in.readInt();
    validateData(key, value, typeIndicator);
  }

  /**
   * Returns the editable track types from the {@linkplain #KEY_EDITABLE_TRACKS_MAP editable tracks
   * map} metadata.
   */
  public List<Integer> getEditableTrackTypesFromMap() {
    checkState(key.equals(KEY_EDITABLE_TRACKS_MAP), "Metadata is not an editable tracks map");
    // Value has 1 byte version, 1 byte track count, n bytes track types.
    int numberOfTracks = value[1];
    List<Integer> trackTypes = new ArrayList<>();
    for (int i = 0; i < numberOfTracks; i++) {
      trackTypes.add((int) value[i + 2]);
    }
    return trackTypes;
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null || getClass() != obj.getClass()) {
      return false;
    }
    MdtaMetadataEntry other = (MdtaMetadataEntry) obj;
    return key.equals(other.key)
        && Arrays.equals(value, other.value)
        && localeIndicator == other.localeIndicator
        && typeIndicator == other.typeIndicator;
  }

  @Override
  public int hashCode() {
    int result = 17;
    result = 31 * result + key.hashCode();
    result = 31 * result + Arrays.hashCode(value);
    result = 31 * result + localeIndicator;
    result = 31 * result + typeIndicator;
    return result;
  }

  @Override
  public String toString() {
    String formattedValue;
    switch (typeIndicator) {
      case TYPE_INDICATOR_STRING:
        formattedValue = Util.fromUtf8Bytes(value);
        break;
      case TYPE_INDICATOR_FLOAT32:
        formattedValue = String.valueOf(Float.intBitsToFloat(Ints.fromByteArray(value)));
        break;
      case TYPE_INDICATOR_INT32:
        formattedValue = String.valueOf(Ints.fromByteArray(value));
        break;
      case TYPE_INDICATOR_8_BIT_UNSIGNED_INT:
        formattedValue = String.valueOf(Byte.toUnsignedInt(value[0]));
        break;
      case TYPE_INDICATOR_UNSIGNED_INT64:
        formattedValue = String.valueOf(new ParsableByteArray(value).readUnsignedLongToLong());
        break;
      case TYPE_INDICATOR_RESERVED:
        if (key.equals(KEY_EDITABLE_TRACKS_MAP)) {
          formattedValue = getFormattedValueForEditableTracksMap(getEditableTrackTypesFromMap());
          break;
        }
      // fall through
      default:
        formattedValue = Util.toHexString(value);
    }

    return "mdta: key=" + key + ", value=" + formattedValue;
  }

  // Parcelable implementation.

  @Override
  public void writeToParcel(Parcel dest, int flags) {
    dest.writeString(key);
    dest.writeByteArray(value);
    dest.writeInt(localeIndicator);
    dest.writeInt(typeIndicator);
  }

  @Override
  public int describeContents() {
    return 0;
  }

  public static final Parcelable.Creator<MdtaMetadataEntry> CREATOR =
      new Parcelable.Creator<MdtaMetadataEntry>() {

        @Override
        public MdtaMetadataEntry createFromParcel(Parcel in) {
          return new MdtaMetadataEntry(in);
        }

        @Override
        public MdtaMetadataEntry[] newArray(int size) {
          return new MdtaMetadataEntry[size];
        }
      };

  private static void validateData(String key, byte[] value, int typeIndicator) {
    switch (key) {
      case KEY_ANDROID_CAPTURE_FPS:
        checkArgument(typeIndicator == TYPE_INDICATOR_FLOAT32 && value.length == 4);
        break;
      case KEY_EDITABLE_TRACKS_OFFSET:
      case KEY_EDITABLE_TRACKS_LENGTH:
        checkArgument(typeIndicator == TYPE_INDICATOR_UNSIGNED_INT64 && value.length == 8);
        break;
      case KEY_EDITABLE_TRACKS_MAP:
        checkArgument(typeIndicator == TYPE_INDICATOR_RESERVED);
        break;
      case KEY_EDITABLE_TRACKS_SAMPLES_LOCATION:
        checkArgument(
            typeIndicator == TYPE_INDICATOR_8_BIT_UNSIGNED_INT
                && value.length == 1
                && (value[0] == EDITABLE_TRACKS_SAMPLES_LOCATION_IN_EDIT_DATA_MP4
                    || value[0] == EDITABLE_TRACKS_SAMPLES_LOCATION_INTERLEAVED));
        break;
      default:
        // Ignore custom keys.
    }
  }

  private static String getFormattedValueForEditableTracksMap(List<Integer> trackTypes) {
    StringBuilder sb = new StringBuilder();
    sb.append("track types = ");
    Joiner.on(',').appendTo(sb, trackTypes);
    return sb.toString();
  }
}
