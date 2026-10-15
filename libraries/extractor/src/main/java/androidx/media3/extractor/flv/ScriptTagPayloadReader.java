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
package androidx.media3.extractor.flv;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.DiscardingTrackOutput;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Parses Script Data tags from an FLV stream and extracts metadata information. */
/* package */ final class ScriptTagPayloadReader extends TagPayloadReader {

  private static final String NAME_METADATA = "onMetaData";
  private static final String KEY_DURATION = "duration";
  private static final String KEY_KEY_FRAMES = "keyframes";
  private static final String KEY_FILE_POSITIONS = "filepositions";
  private static final String KEY_TIMES = "times";

  // AMF object types
  private static final int AMF_TYPE_NUMBER = 0;
  private static final int AMF_TYPE_BOOLEAN = 1;
  private static final int AMF_TYPE_STRING = 2;
  private static final int AMF_TYPE_OBJECT = 3;
  private static final int AMF_TYPE_ECMA_ARRAY = 8;
  private static final int AMF_TYPE_END_MARKER = 9;
  private static final int AMF_TYPE_STRICT_ARRAY = 10;
  private static final int AMF_TYPE_DATE = 11;

  private long durationUs;
  private long[] keyFrameTimesUs;
  private long[] keyFrameTagPositions;

  public ScriptTagPayloadReader() {
    super(new DiscardingTrackOutput());
    durationUs = C.TIME_UNSET;
    keyFrameTimesUs = new long[0];
    keyFrameTagPositions = new long[0];
  }

  public long getDurationUs() {
    return durationUs;
  }

  public long[] getKeyFrameTimesUs() {
    return keyFrameTimesUs;
  }

  public long[] getKeyFrameTagPositions() {
    return keyFrameTagPositions;
  }

  @Override
  public void seek() {
    // Do nothing.
  }

  @Override
  protected boolean parseHeader(ParsableByteArray data) {
    return true;
  }

  @Override
  protected boolean parsePayload(ParsableByteArray data, long timeUs) {
    if (data.bytesLeft() == 0) {
      return false;
    }
    int nameType = readAmfType(data);
    if (nameType != AMF_TYPE_STRING) {
      // Ignore segments with unexpected name type.
      return false;
    }
    @Nullable String name = readAmfString(data);
    if (name == null || !NAME_METADATA.equals(name)) {
      // We're only interested in metadata.
      return false;
    }
    if (data.bytesLeft() == 0) {
      // The metadata script tag has no value.
      return false;
    }
    int type = readAmfType(data);
    if (type != AMF_TYPE_ECMA_ARRAY) {
      // We're not interested in this metadata.
      return false;
    }
    @Nullable Map<String, Object> metadata = readAmfEcmaArray(data);
    if (metadata == null) {
      // The metadata was malformed; ignore it rather than propagating a parse failure.
      return false;
    }
    // Set the duration to the value contained in the metadata, if present.
    @Nullable Object durationSecondsObj = metadata.get(KEY_DURATION);
    if (durationSecondsObj instanceof Double) {
      double durationSeconds = (double) durationSecondsObj;
      if (durationSeconds > 0.0) {
        durationUs = (long) (durationSeconds * C.MICROS_PER_SECOND);
      }
    }
    // Set the key frame times and positions to the value contained in the metadata, if present.
    @Nullable Object keyFramesObj = metadata.get(KEY_KEY_FRAMES);
    if (keyFramesObj instanceof Map) {
      Map<?, ?> keyFrames = (Map<?, ?>) keyFramesObj;
      @Nullable Object positionsObj = keyFrames.get(KEY_FILE_POSITIONS);
      @Nullable Object timesSecondsObj = keyFrames.get(KEY_TIMES);
      if (positionsObj instanceof List && timesSecondsObj instanceof List) {
        List<?> positions = (List<?>) positionsObj;
        List<?> timesSeconds = (List<?>) timesSecondsObj;
        // Guard against malformed metadata where the two arrays have different lengths.
        int keyFrameCount = Math.min(timesSeconds.size(), positions.size());
        keyFrameTimesUs = new long[keyFrameCount];
        keyFrameTagPositions = new long[keyFrameCount];
        for (int i = 0; i < keyFrameCount; i++) {
          Object positionObj = positions.get(i);
          Object timeSecondsObj = timesSeconds.get(i);
          if (timeSecondsObj instanceof Double && positionObj instanceof Double) {
            keyFrameTimesUs[i] = (long) (((Double) timeSecondsObj) * C.MICROS_PER_SECOND);
            keyFrameTagPositions[i] = ((Double) positionObj).longValue();
          } else {
            keyFrameTimesUs = new long[0];
            keyFrameTagPositions = new long[0];
            break;
          }
        }
      }
    }
    return false;
  }

  private static int readAmfType(ParsableByteArray data) {
    return data.readUnsignedByte();
  }

  /**
   * Read a boolean from an AMF encoded buffer.
   *
   * @param data The buffer from which to read.
   * @return The value read from the buffer.
   */
  private static Boolean readAmfBoolean(ParsableByteArray data) {
    return data.readUnsignedByte() == 1;
  }

  /**
   * Read a double number from an AMF encoded buffer.
   *
   * @param data The buffer from which to read.
   * @return The value read from the buffer.
   */
  @Nullable
  private static Double readAmfDouble(ParsableByteArray data) {
    if (data.bytesLeft() < 8) {
      return null;
    }
    return Double.longBitsToDouble(data.readLong());
  }

  /**
   * Read a string from an AMF encoded buffer.
   *
   * @param data The buffer from which to read.
   * @return The value read from the buffer, or null if the buffer does not contain enough data.
   */
  @Nullable
  private static String readAmfString(ParsableByteArray data) {
    if (data.bytesLeft() < 2) {
      return null;
    }
    int size = data.readUnsignedShort();
    if (data.bytesLeft() < size) {
      return null;
    }
    int position = data.getPosition();
    data.skipBytes(size);
    return new String(data.getData(), position, size);
  }

  /**
   * Read an array from an AMF encoded buffer.
   *
   * @param data The buffer from which to read.
   * @return The value read from the buffer, or null if the buffer was exhausted before the
   *     declared element count was reached (malformed data).
   */
  @Nullable
  private static ArrayList<Object> readAmfStrictArray(ParsableByteArray data) {
    if (data.bytesLeft() < 4) {
      return null;
    }
    int count = data.readUnsignedIntToInt();
    if (count < 0) {
      return null;
    }
    ArrayList<Object> list = new ArrayList<>(Math.min(count, 1024));
    for (int i = 0; i < count; i++) {
      if (data.bytesLeft() <= 0) {
        // Malformed: declared count exceeds the data actually present. Return what we have.
        return list;
      }
      int type = readAmfType(data);
      @Nullable Object value = readAmfData(data, type);
      if (value != null) {
        list.add(value);
      }
    }
    return list;
  }

  /**
   * Read an object from an AMF encoded buffer.
   *
   * @param data The buffer from which to read.
   * @return The value read from the buffer, or null if the buffer was exhausted before an end
   *     marker was found (malformed data).
   */
  @Nullable
  private static HashMap<String, Object> readAmfObject(ParsableByteArray data) {
    HashMap<String, Object> array = new HashMap<>();
    while (data.bytesLeft() > 0) {
      @Nullable String key = readAmfString(data);
      if (key == null || data.bytesLeft() == 0) {
        // Malformed: missing end marker. Return what we've parsed so far.
        return array;
      }
      int type = readAmfType(data);
      if (type == AMF_TYPE_END_MARKER) {
        return array;
      }
      @Nullable Object value = readAmfData(data, type);
      if (value != null) {
        array.put(key, value);
      }
    }
    // Ran out of data without an end marker.
    return array;
  }

  /**
   * Read an ECMA array from an AMF encoded buffer.
   *
   * @param data The buffer from which to read.
   * @return The value read from the buffer, or null if the buffer was exhausted before the
   *     declared element count was reached (malformed data).
   */
  @Nullable
  private static HashMap<String, Object> readAmfEcmaArray(ParsableByteArray data) {
    if (data.bytesLeft() < 4) {
      return null;
    }
    int count = data.readUnsignedIntToInt();
    if (count < 0) {
      return null;
    }
    HashMap<String, Object> array = new HashMap<>(Math.min(count, 1024));
    for (int i = 0; i < count; i++) {
      if (data.bytesLeft() <= 0) {
        // Malformed: declared count exceeds the data actually present. Return what we have.
        return array;
      }
      @Nullable String key = readAmfString(data);
      if (key == null || data.bytesLeft() == 0) {
        return array;
      }
      int type = readAmfType(data);
      @Nullable Object value = readAmfData(data, type);
      if (value != null) {
        array.put(key, value);
      }
    }
    return array;
  }

  /**
   * Read a date from an AMF encoded buffer.
   *
   * @param data The buffer from which to read.
   * @return The value read from the buffer, or null if the buffer does not contain enough data.
   */
  @Nullable
  private static Date readAmfDate(ParsableByteArray data) {
    @Nullable Double millis = readAmfDouble(data);
    if (millis == null || data.bytesLeft() < 2) {
      return null;
    }
    Date date = new Date((long) millis.doubleValue());
    data.skipBytes(2); // Skip reserved bytes.
    return date;
  }

  @Nullable
  private static Object readAmfData(ParsableByteArray data, int type) {
    switch (type) {
      case AMF_TYPE_NUMBER:
        return readAmfDouble(data);
      case AMF_TYPE_BOOLEAN:
        return data.bytesLeft() > 0 ? readAmfBoolean(data) : null;
      case AMF_TYPE_STRING:
        return readAmfString(data);
      case AMF_TYPE_OBJECT:
        return readAmfObject(data);
      case AMF_TYPE_ECMA_ARRAY:
        return readAmfEcmaArray(data);
      case AMF_TYPE_STRICT_ARRAY:
        return readAmfStrictArray(data);
      case AMF_TYPE_DATE:
        return readAmfDate(data);
      default:
        // We don't log a warning because there are types that we knowingly don't support.
        return null;
    }
  }
}
