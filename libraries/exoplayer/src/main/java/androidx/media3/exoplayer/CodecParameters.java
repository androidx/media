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
package androidx.media3.exoplayer;

import android.media.MediaFormat;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;
import androidx.media3.common.util.NullableType;
import androidx.media3.common.util.UnstableApi;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** An immutable collection of parameters to be applied to a codec. */
@UnstableApi
public final class CodecParameters {

  /** An empty {@link CodecParameters} instance. */
  public static final CodecParameters EMPTY = new CodecParameters.Builder().build();

  private final Map<String, @NullableType Object> params;

  private CodecParameters(Map<String, @NullableType Object> params) {
    this.params = Collections.unmodifiableMap(params);
  }

  /** Returns a new {@link Builder} initialized with the values of this instance. */
  public Builder buildUpon() {
    return new Builder(this);
  }

  /** A builder for {@link CodecParameters} instances. */
  public static final class Builder {
    private final Map<String, @NullableType Object> params;

    /** Creates an empty builder. */
    public Builder() {
      params = new HashMap<>();
    }

    private Builder(CodecParameters codecParameters) {
      this.params = new HashMap<>(codecParameters.params);
    }

    /**
     * Sets an integer parameter value.
     *
     * @param key The parameter key.
     * @param value The integer value.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setInteger(String key, int value) {
      params.put(key, value);
      return this;
    }

    /**
     * Sets a long parameter value.
     *
     * @param key The parameter key.
     * @param value The long value.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setLong(String key, long value) {
      params.put(key, value);
      return this;
    }

    /**
     * Sets a float parameter value.
     *
     * @param key The parameter key.
     * @param value The float value.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setFloat(String key, float value) {
      params.put(key, value);
      return this;
    }

    /**
     * Sets a string parameter value.
     *
     * @param key The parameter key.
     * @param value The string value, which may be {@code null}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setString(String key, @Nullable String value) {
      params.put(key, value);
      return this;
    }

    /**
     * Sets a byte buffer parameter value.
     *
     * @param key The parameter key.
     * @param value The {@link ByteBuffer} value, which may be {@code null}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setByteBuffer(String key, @Nullable ByteBuffer value) {
      if (value == null) {
        params.put(key, null);
      } else {
        ByteBuffer clone = ByteBuffer.allocate(value.remaining());
        clone.put(value.duplicate());
        clone.flip();
        params.put(key, clone);
      }
      return this;
    }

    /**
     * Removes a parameter from this builder, preventing it from being applied.
     *
     * <p>Note: This does not reset a parameter on a live codec. To do so, explicitly set the
     * parameter to its default value.
     *
     * @param key The key of the parameter to remove.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder remove(String key) {
      params.remove(key);
      return this;
    }

    /** Builds the {@link CodecParameters} instance. */
    public CodecParameters build() {
      return new CodecParameters(params);
    }
  }

  /**
   * Creates a new {@link Builder} initialized with parameters from the provided {@link
   * MediaFormat}.
   *
   * @param mediaFormat The {@link MediaFormat} to extract parameters from.
   * @param keys The set of keys to include.
   * @return A new {@link Builder} instance.
   */
  @RequiresApi(29)
  @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
  public static Builder createFrom(MediaFormat mediaFormat, Set<String> keys) {
    Builder builder = new Builder();
    for (String key : keys) {
      if (mediaFormat.containsKey(key)) {
        int type = mediaFormat.getValueTypeForKey(key);
        switch (type) {
          case MediaFormat.TYPE_INTEGER:
            builder.setInteger(key, mediaFormat.getInteger(key));
            break;
          case MediaFormat.TYPE_LONG:
            builder.setLong(key, mediaFormat.getLong(key));
            break;
          case MediaFormat.TYPE_FLOAT:
            builder.setFloat(key, mediaFormat.getFloat(key));
            break;
          case MediaFormat.TYPE_STRING:
            builder.setString(key, mediaFormat.getString(key));
            break;
          case MediaFormat.TYPE_BYTE_BUFFER:
            builder.setByteBuffer(key, mediaFormat.getByteBuffer(key));
            break;
          default:
            break;
        }
      }
    }
    return builder;
  }

  /**
   * Retrieves a parameter value by its key.
   *
   * <p><b>Note:</b> If the returned value is a {@link ByteBuffer}, it must be treated as read-only.
   * Modifying the returned {@link ByteBuffer} will affect this {@link CodecParameters} instance and
   * may lead to unexpected behavior.
   *
   * @param key A string representing the key of the codec parameter.
   * @return The value of the requested parameter, or {@code null} if the key is not present.
   */
  @Nullable
  public Object get(String key) {
    return params.get(key);
  }

  /** Returns a {@link Set} of the keys contained in this instance. */
  public Set<String> keySet() {
    return params.keySet();
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof CodecParameters)) {
      return false;
    }
    CodecParameters other = (CodecParameters) obj;
    return this.params.equals(other.params);
  }

  @Override
  public int hashCode() {
    return params.hashCode();
  }

  /** Converts the parameters from this instance to a {@link Bundle}. */
  @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
  public Bundle toBundle() {
    Bundle bundle = new Bundle();
    for (Map.Entry<String, @NullableType Object> entry : params.entrySet()) {
      String key = entry.getKey();
      Object value = entry.getValue();
      if (value == null) {
        continue;
      }
      if (value instanceof Integer) {
        bundle.putInt(key, (Integer) value);
      } else if (value instanceof Long) {
        bundle.putLong(key, (Long) value);
      } else if (value instanceof Float) {
        bundle.putFloat(key, (Float) value);
      } else if (value instanceof String) {
        bundle.putString(key, (String) value);
      } else if (value instanceof ByteBuffer) {
        ByteBuffer byteBuffer = (ByteBuffer) value;
        byte[] bytes = new byte[byteBuffer.remaining()];
        byteBuffer.duplicate().get(bytes);
        bundle.putByteArray(key, bytes);
      }
    }
    return bundle;
  }

  /** Applies the parameters from this instance to the given {@link MediaFormat}. */
  @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
  public void applyTo(MediaFormat mediaFormat) {
    for (Map.Entry<String, @NullableType Object> entry : params.entrySet()) {
      String key = entry.getKey();
      Object value = entry.getValue();
      if (value == null) {
        mediaFormat.setString(key, null);
        continue;
      }
      if (value instanceof Integer) {
        mediaFormat.setInteger(key, (Integer) value);
      } else if (value instanceof Long) {
        mediaFormat.setLong(key, (Long) value);
      } else if (value instanceof Float) {
        mediaFormat.setFloat(key, (Float) value);
      } else if (value instanceof String) {
        mediaFormat.setString(key, (String) value);
      } else if (value instanceof ByteBuffer) {
        mediaFormat.setByteBuffer(key, (ByteBuffer) value);
      }
    }
  }
}
