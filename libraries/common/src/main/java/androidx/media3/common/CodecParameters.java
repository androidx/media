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
package androidx.media3.common;

import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A collection of {@link CodecParameter} objects. */
@UnstableApi
public final class CodecParameters {

  private final Map<String, CodecParameter> codecParameters;

  /** Creates an instance. */
  public CodecParameters() {
    codecParameters = new HashMap<>();
  }

  /**
   * Sets, replaces, or removes a codec parameter in the collection.
   *
   * <p>If the {@link CodecParameter#valueType} is {@link CodecParameter#TYPE_NULL}, the parameter
   * with the given key will be removed.
   *
   * @param param The {@link CodecParameter} to set, replace, or remove.
   */
  public void set(CodecParameter param) {
    if (param.valueType == CodecParameter.TYPE_NULL) {
      codecParameters.remove(param.key);
    } else {
      codecParameters.put(param.key, param);
    }
  }

  /** Returns the map of codec parameters in this collection. */
  public Map<String, CodecParameter> get() {
    return codecParameters;
  }

  /**
   * Returns a codec parameter from the collection by its key.
   *
   * @param key A string representing the key of the codec parameter.
   * @return The requested {@link CodecParameter}, or {@code null} if not found.
   */
  @Nullable
  public CodecParameter get(String key) {
    return codecParameters.get(key);
  }

  /** Removes all codec parameters from the collection. */
  public void clear() {
    codecParameters.clear();
  }

  /**
   * Converts the collection of codec parameters to a {@link Bundle}.
   *
   * @return A {@link Bundle} containing the codec parameters.
   */
  public Bundle toBundle() {
    Bundle bundle = new Bundle();
    for (Map.Entry<String, CodecParameter> entry : codecParameters.entrySet()) {
      String key = entry.getKey();
      CodecParameter param = entry.getValue();

      switch (param.valueType) {
        case CodecParameter.TYPE_INT:
          bundle.putInt(key, (int) param.value);
          break;
        case CodecParameter.TYPE_LONG:
          bundle.putLong(key, (long) param.value);
          break;
        case CodecParameter.TYPE_FLOAT:
          bundle.putFloat(key, (float) param.value);
          break;
        case CodecParameter.TYPE_STRING:
          bundle.putString(key, (String) param.value);
          break;
        case CodecParameter.TYPE_BYTE_BUFFER:
          bundle.putByteArray(key, ((ByteBuffer) param.value).array());
          break;
        case CodecParameter.TYPE_NULL:
        default:
          break;
      }
    }
    return bundle;
  }

  /**
   * Populates this collection from the entries of a {@link MediaFormat}.
   *
   * @param mediaFormat The {@link MediaFormat} from which to populate this collection.
   * @param filterKeys A list of {@link MediaFormat} entry keys that should be included. If {@code
   *     null}, all entries in the media format will be included (requires API 29+).
   */
  public void setFromMediaFormat(MediaFormat mediaFormat, @Nullable List<String> filterKeys) {
    if (filterKeys == null) {
      if (Build.VERSION.SDK_INT >= 29) {
        Set<String> keys = mediaFormat.getKeys();
        for (String key : keys) {
          int type = mediaFormat.getValueTypeForKey(key);
          @Nullable Object value;
          int valueType;
          switch (type) {
            case MediaFormat.TYPE_INTEGER:
              value = mediaFormat.getInteger(key);
              valueType = CodecParameter.TYPE_INT;
              break;
            case MediaFormat.TYPE_LONG:
              value = mediaFormat.getLong(key);
              valueType = CodecParameter.TYPE_LONG;
              break;
            case MediaFormat.TYPE_FLOAT:
              value = mediaFormat.getFloat(key);
              valueType = CodecParameter.TYPE_FLOAT;
              break;
            case MediaFormat.TYPE_STRING:
              value = mediaFormat.getString(key);
              valueType = CodecParameter.TYPE_STRING;
              break;
            case MediaFormat.TYPE_BYTE_BUFFER:
              value = mediaFormat.getByteBuffer(key);
              valueType = CodecParameter.TYPE_BYTE_BUFFER;
              break;
            case MediaFormat.TYPE_NULL:
            default:
              continue;
          }
          codecParameters.put(key, new CodecParameter(key, value, valueType));
        }
      }
    } else {
      for (String key : filterKeys) {
        if (mediaFormat.containsKey(key)) {
          @Nullable Object value = null;
          @CodecParameter.ValueType int type = CodecParameter.TYPE_NULL;
          boolean success = false;
          try {
            value = mediaFormat.getInteger(key);
            type = CodecParameter.TYPE_INT;
            success = true;
          } catch (Exception e) {
            // Do nothing.
          }

          if (!success) {
            try {
              value = mediaFormat.getLong(key);
              type = CodecParameter.TYPE_LONG;
              success = true;
            } catch (Exception e) {
              // Do nothing.
            }
          }

          if (!success) {
            try {
              value = mediaFormat.getFloat(key);
              type = CodecParameter.TYPE_FLOAT;
              success = true;
            } catch (Exception e) {
              // Do nothing.
            }
          }

          if (!success) {
            try {
              value = mediaFormat.getString(key);
              type = CodecParameter.TYPE_STRING;
              success = true;
            } catch (Exception e) {
              // Do nothing.
            }
          }

          if (!success) {
            try {
              value = mediaFormat.getByteBuffer(key);
              type = CodecParameter.TYPE_BYTE_BUFFER;
              success = true;
            } catch (Exception e) {
              // Do nothing.
            }
          }

          if (success) {
            codecParameters.put(key, new CodecParameter(key, value, type));
          }
        }
      }
    }
  }

  /**
   * Adds all parameters from this collection to a {@link MediaFormat}. Existing keys in the {@link
   * MediaFormat} will be overwritten.
   *
   * @param mediaFormat The {@link MediaFormat} to which codec parameters should be added.
   */
  public void addToMediaFormat(MediaFormat mediaFormat) {
    for (Map.Entry<String, CodecParameter> entry : codecParameters.entrySet()) {
      String key = entry.getKey();
      CodecParameter param = entry.getValue();

      switch (param.valueType) {
        case CodecParameter.TYPE_INT:
          mediaFormat.setInteger(key, (int) param.value);
          break;
        case CodecParameter.TYPE_LONG:
          mediaFormat.setLong(key, (long) param.value);
          break;
        case CodecParameter.TYPE_FLOAT:
          mediaFormat.setFloat(key, (float) param.value);
          break;
        case CodecParameter.TYPE_STRING:
          mediaFormat.setString(key, (String) param.value);
          break;
        case CodecParameter.TYPE_BYTE_BUFFER:
          mediaFormat.setByteBuffer(key, (ByteBuffer) param.value);
          break;
        case CodecParameter.TYPE_NULL:
        default:
          break;
      }
    }
  }
}
