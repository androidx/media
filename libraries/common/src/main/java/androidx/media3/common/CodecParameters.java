package androidx.media3.common;

import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * A {@link CodecParameters} instance provides the possibility to store/cache a set of {@link CodecParameter}.
 * Furthermore, some conversion functions ({@link CodecParameters#setFromMediaFormat}, {@link CodecParameters#addToMediaFormat}
 * and {@link CodecParameters#toBundle}) are given.
 */
public class CodecParameters {

  private static final String TAG = "CodecParameters";

  /**
   * A hashmap for storing several codec parameters.
   */
  private final HashMap<String, CodecParameter> codecParameters;

  /**
   * Creates a new codec parameters instance.
   */
  public CodecParameters() {
    codecParameters = new HashMap<>();
  }


  /**
   * Set, replace or remove a codec parameter in/from the cache.
   * If the value type of the codec parameter is of value type VALUETYPE_NULL, the
   * cached codec parameter will be removed.
   *
   * @param param A {@link CodecParameter} to be set, replaced or removed in/from the cache.
   */
  public void set(CodecParameter param) {
    if (param.valueType == CodecParameter.VALUETYPE_NULL) {
      codecParameters.remove(param.key);
    } else {
      codecParameters.put(param.key, param);
    }
  }

  /**
   * Get the hashmap of the cached codec parameters.
   *
   * @returns The hashmap of cached codec parameters.
   */
  public HashMap<String, CodecParameter> get() {
    return codecParameters;
  }

  /**
   * Get a cached codec parameter according to its key.
   *
   * @param key A string representing the key of the codec parameter.
   * @returns The requested {@link CodecParameter}.
   */
  public @Nullable CodecParameter get(String key) {
    return codecParameters.get(key);
  }

  /**
   * Remove all cached codec parameters from the cache.
   */
  public void clear() {
    codecParameters.clear();
  }

  /**
   * Convert the cached codec parameters to a bundle.
   *
   * @return A {@link Bundle} containing the cached codec parameters.
   */
  public Bundle toBundle() {
    Bundle bundle = new Bundle();
    for (Map.Entry<String, CodecParameter> entry : codecParameters.entrySet()) {
      String key = entry.getKey();
      CodecParameter param = entry.getValue();

      switch (param.valueType) {
        case CodecParameter.VALUETYPE_INT:
          bundle.putInt(key, (int) param.value);
          break;
        case CodecParameter.VALUETYPE_LONG:
          bundle.putLong(key, (long) param.value);
          break;
        case CodecParameter.VALUETYPE_FLOAT:
          bundle.putFloat(key, (float) param.value);
          break;
        case CodecParameter.VALUETYPE_STRING:
          bundle.putString(key, (String) param.value);
          break;
        case CodecParameter.VALUETYPE_BYTE_BUFFER:
          bundle.putByteArray(key, ((ByteBuffer) param.value).array());
          break;
        case CodecParameter.VALUETYPE_NULL:
        default:
          break;
      }
    }
    return bundle;
  }

  /**
   * Convert the entries of a MediaFormat to codec parameters and cache them.
   *
   * @param mediaFormat The media format to be converted and cached.
   * @param filterKeys  A list of media format entry keys which should be converted and cached.
   *                    If null, all entries in the media format will be cached.
   */
  public void setFromMediaFormat(MediaFormat mediaFormat, @Nullable ArrayList<String> filterKeys) {
    CodecParameter param;
    if (filterKeys == null) {
      if (Build.VERSION.SDK_INT >= 29) {
        Set<String> keys = mediaFormat.getKeys();
        for (String key : keys) {
          int type = mediaFormat.getValueTypeForKey(key);
          switch (type) {
            case MediaFormat.TYPE_INTEGER:
              param = new CodecParameter(key, mediaFormat.getInteger(key),
                  CodecParameter.VALUETYPE_INT);
              codecParameters.put(key, param);
              break;
            case MediaFormat.TYPE_LONG:
              param = new CodecParameter(key, mediaFormat.getLong(key),
                  CodecParameter.VALUETYPE_LONG);
              codecParameters.put(key, param);
              break;
            case MediaFormat.TYPE_FLOAT:
              param = new CodecParameter(key, mediaFormat.getFloat(key),
                  CodecParameter.VALUETYPE_FLOAT);
              codecParameters.put(key, param);
              break;
            case MediaFormat.TYPE_STRING:
              param = new CodecParameter(key, mediaFormat.getString(key),
                  CodecParameter.VALUETYPE_STRING);
              codecParameters.put(key, param);
              break;
            case MediaFormat.TYPE_BYTE_BUFFER:
              param = new CodecParameter(key, mediaFormat.getByteBuffer(key),
                  CodecParameter.VALUETYPE_BYTE_BUFFER);
              codecParameters.put(key, param);
              break;
            case MediaFormat.TYPE_NULL:
            default:
              break;
          }
        }
      } else {
        // not implemented
      }
    } else {
      for (String key : filterKeys) {
        if (mediaFormat.containsKey(key)) {
          @Nullable Object value = null;
          @CodecParameter.ValueType int type = CodecParameter.VALUETYPE_NULL;
          boolean success = false;
          try {
            value = mediaFormat.getInteger(key);
            type = CodecParameter.VALUETYPE_INT;
            success = true;
          } catch (Exception e) {
            e.printStackTrace();
          }

          if (!success) {
            try {
              value = mediaFormat.getLong(key);
              type = CodecParameter.VALUETYPE_LONG;
              success = true;
            } catch (Exception e) {
              e.printStackTrace();
            }
          }

          if (!success) {
            try {
              value = mediaFormat.getFloat(key);
              type = CodecParameter.VALUETYPE_FLOAT;
              success = true;
            } catch (Exception e) {
              e.printStackTrace();
            }
          }

          if (!success) {
            try {
              value = mediaFormat.getString(key);
              type = CodecParameter.VALUETYPE_STRING;
              success = true;
            } catch (Exception e) {
              e.printStackTrace();
            }
          }

          if (!success) {
            try {
              value = mediaFormat.getByteBuffer(key);
              type = CodecParameter.VALUETYPE_BYTE_BUFFER;
              success = true;
            } catch (Exception e) {
              e.printStackTrace();
            }
          }

          if (success) {
            param = new CodecParameter(key, value, type);
            codecParameters.put(param.key, param);
          }
        }
      }
    }
  }


  /**
   * Append/overwrite the entries of a MediaFormat by all cached codec parameters.
   *
   * @param mediaFormat The media format to which codec parameters should be added.
   */
  public void addToMediaFormat(MediaFormat mediaFormat) {
    for (Map.Entry<String, CodecParameter> entry : codecParameters.entrySet()) {
      String key = entry.getKey();
      CodecParameter param = entry.getValue();

      switch (param.valueType) {
        case CodecParameter.VALUETYPE_INT:
          mediaFormat.setInteger(key, (int) param.value);
          break;
        case CodecParameter.VALUETYPE_LONG:
          mediaFormat.setLong(key, (long) param.value);
          break;
        case CodecParameter.VALUETYPE_FLOAT:
          mediaFormat.setFloat(key, (float) param.value);
          break;
        case CodecParameter.VALUETYPE_STRING:
          mediaFormat.setString(key, (String) param.value);
          break;
        case CodecParameter.VALUETYPE_BYTE_BUFFER:
          mediaFormat.setByteBuffer(key, (ByteBuffer) param.value);
          break;
        case CodecParameter.VALUETYPE_NULL:
        default:
          break;
      }
    }
  }
}
