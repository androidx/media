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

import static java.lang.annotation.ElementType.TYPE_USE;

import android.media.MediaFormat;
import android.os.Build;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.util.UnstableApi;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A parameter for configuring an underlying {@link android.media.MediaCodec}.
 *
 * <p>The key must be a key that is understood by the underlying decoder instance.
 */
@UnstableApi
public final class CodecParameter {

  /**
   * Value types for a {@link CodecParameter}. One of {@link #TYPE_NULL}, {@link #TYPE_INT}, {@link
   * #TYPE_LONG}, {@link #TYPE_FLOAT}, {@link #TYPE_STRING} or {@link #TYPE_BYTE_BUFFER}.
   */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({TYPE_NULL, TYPE_INT, TYPE_LONG, TYPE_FLOAT, TYPE_STRING, TYPE_BYTE_BUFFER})
  public @interface ValueType {}

  /**
   * @see MediaFormat#TYPE_NULL
   */
  public static final int TYPE_NULL = 0;

  /**
   * @see MediaFormat#TYPE_INTEGER
   */
  public static final int TYPE_INT = 1;

  /**
   * @see MediaFormat#TYPE_LONG
   */
  public static final int TYPE_LONG = 2;

  /**
   * @see MediaFormat#TYPE_FLOAT
   */
  public static final int TYPE_FLOAT = 3;

  /**
   * @see MediaFormat#TYPE_STRING
   */
  public static final int TYPE_STRING = 4;

  /**
   * @see MediaFormat#TYPE_BYTE_BUFFER
   */
  public static final int TYPE_BYTE_BUFFER = 5;

  /**
   * @see MediaFormat#KEY_AAC_DRC_ALBUM_MODE
   */
  @RequiresApi(api = Build.VERSION_CODES.R)
  public static final String KEY_AAC_DRC_ALBUM_MODE = MediaFormat.KEY_AAC_DRC_ALBUM_MODE;

  /**
   * @see MediaFormat#KEY_AAC_DRC_ATTENUATION_FACTOR
   */
  public static final String KEY_AAC_DRC_ATTENUATION_FACTOR =
      MediaFormat.KEY_AAC_DRC_ATTENUATION_FACTOR;

  /**
   * @see MediaFormat#KEY_AAC_DRC_BOOST_FACTOR
   */
  public static final String KEY_AAC_DRC_BOOST_FACTOR = MediaFormat.KEY_AAC_DRC_BOOST_FACTOR;

  /**
   * @see MediaFormat#KEY_AAC_DRC_EFFECT_TYPE
   */
  @RequiresApi(api = Build.VERSION_CODES.P)
  public static final String KEY_AAC_DRC_EFFECT_TYPE = MediaFormat.KEY_AAC_DRC_EFFECT_TYPE;

  /**
   * @see MediaFormat#KEY_AAC_DRC_TARGET_REFERENCE_LEVEL
   */
  public static final String KEY_AAC_DRC_TARGET_REFERENCE_LEVEL =
      MediaFormat.KEY_AAC_DRC_TARGET_REFERENCE_LEVEL;

  /**
   * @see MediaFormat#KEY_AAC_DRC_OUTPUT_LOUDNESS
   */
  @RequiresApi(api = Build.VERSION_CODES.R)
  public static final String KEY_AAC_DRC_OUTPUT_LOUDNESS = MediaFormat.KEY_AAC_DRC_OUTPUT_LOUDNESS;

  /**
   * Key to set the MPEG-H output mode. The corresponding value must be of value type {@link
   * ValueType#TYPE_INT}.
   *
   * <p>Possible values are:
   *
   * <ul>
   *   <li>0 for PCM output (decoding the MPEG-H bitstream with a certain MPEG-H target layout CICP
   *       index)
   *   <li>1 for MPEG-H bitstream bypass (using IEC61937-13 with a sample rate factor of 4)
   *   <li>2 for MPEG-H bitstream bypass (using IEC61937-13 with a sample rate factor of 16)
   * </ul>
   */
  public static final String KEY_MPEGH_OUTPUT_MODE = "mpegh-output-mode";

  /**
   * Key to set the MPEG-H target layout CICP index. The corresponding value must be of value type
   * {@link ValueType#TYPE_INT}. It must be set before decoder initialization. A change during
   * runtime does not have any effect.
   *
   * <p>Supported values are: 0, 1, 2, 6, 8, 10, 12. A value of 0 tells the decoder to create
   * binauralized output.
   */
  public static final String KEY_MPEGH_TARGET_LAYOUT = "mpegh-target-layout";

  /**
   * Key to set the MPEG-H UI configuration. The corresponding value must be of value type {@link
   * ValueType#TYPE_STRING}. This key is returned from the MPEG-H UI manager.
   */
  public static final String KEY_MPEGH_UI_CONFIG = "mpegh-ui-config";

  /**
   * Key to set the MPEG-H UI command. The corresponding value must be of value type {@link
   * ValueType#TYPE_STRING}. This key is passed to the MPEG-H UI manager.
   */
  public static final String KEY_MPEGH_UI_COMMAND = "mpegh-ui-command";

  /**
   * Key to set the MPEG-H UI persistence storage path. The corresponding value must be of value
   * type {@link ValueType#TYPE_STRING}. This key is passed to the MPEG-H UI manager.
   */
  public static final String KEY_MPEGH_UI_PERSISTENCESTORAGE_PATH =
      "mpegh-ui-persistencestorage-path";

  /** The key of the codec parameter. */
  public final String key;

  /** The value of the codec parameter. */
  public final @Nullable Object value;

  /** The {@link ValueType} of the value object. */
  public final @ValueType int valueType;

  /**
   * Creates an instance.
   *
   * @param key The key of the codec parameter.
   * @param value The value of the codec parameter.
   * @param valueType The {@link ValueType} of the value object.
   */
  public CodecParameter(String key, @Nullable Object value, @ValueType int valueType) {
    this.key = key;
    this.value = value;
    this.valueType = valueType;
  }
}
