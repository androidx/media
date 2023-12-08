package androidx.media3.common;

import static java.lang.annotation.ElementType.TYPE_USE;

import android.media.MediaFormat;
import android.os.Build;

import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A {@link CodecParameter} holds the key, value and the value type for signalling a parameter change to a
 * decoder. The key can be an arbitrary string which must be known by the decoder instance.
 */
public class CodecParameter {

  private static final String TAG = "CodecParameter";

  /**
   * @see MediaFormat#KEY_AAC_DRC_ALBUM_MODE
   */
  @RequiresApi(api = Build.VERSION_CODES.R)
  public final static String KEY_AAC_DRC_ALBUM_MODE = MediaFormat.KEY_AAC_DRC_ALBUM_MODE;

  /**
   * @see MediaFormat#KEY_AAC_DRC_ATTENUATION_FACTOR
   */
  @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
  public final static String KEY_AAC_DRC_ATTENUATION_FACTOR = MediaFormat.KEY_AAC_DRC_ATTENUATION_FACTOR;

  /**
   * @see MediaFormat#KEY_AAC_DRC_BOOST_FACTOR
   */
  @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
  public final static String KEY_AAC_DRC_BOOST_FACTOR = MediaFormat.KEY_AAC_DRC_BOOST_FACTOR;

  /**
   * @see MediaFormat#KEY_AAC_DRC_EFFECT_TYPE
   */
  @RequiresApi(api = Build.VERSION_CODES.P)
  public final static String KEY_AAC_DRC_EFFECT_TYPE = MediaFormat.KEY_AAC_DRC_EFFECT_TYPE;

  /**
   * @see MediaFormat#KEY_AAC_DRC_TARGET_REFERENCE_LEVEL
   */
  @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
  public final static String KEY_AAC_DRC_TARGET_REFERENCE_LEVEL = MediaFormat.KEY_AAC_DRC_TARGET_REFERENCE_LEVEL;

  /**
   * @see MediaFormat#KEY_AAC_DRC_OUTPUT_LOUDNESS
   */
  @RequiresApi(api = Build.VERSION_CODES.R)
  public final static String KEY_AAC_DRC_OUTPUT_LOUDNESS = MediaFormat.KEY_AAC_DRC_OUTPUT_LOUDNESS;


  /**
   * Key to set the MPEG-H output mode.
   * The corresponding value must be of value type {@link ValueType#VALUETYPE_INT}.
   * Possible values are:
   * 0 for PCM output (decoding the MPEG-H bitstream with a certain MPEG-H target layout CICP index)
   * 1 for MPEG-H bitstream bypass (using IEC61937-13 with a sample rate factor of 4)
   * 2 for MPEG-H bitstream bypass (using IEC61937-13 with a sample rate factor of 16)
   */
  public final static String KEY_MPEGH_OUTPUT_MODE = "mpegh-output-mode";

  /**
   * Key to set the MPEG-H target layout CICP index.
   * The corresponding value must be of value type {@link ValueType#VALUETYPE_INT}.
   * It must be set before decoder initialization. A change during runtime does not have any effect.
   * Supported values are: 0, 1, 2, 6, 8, 10, 12
   * A value of 0 tells the decoder to create binauralized output.
   */
  public final static String KEY_MPEGH_TARGET_LAYOUT = "mpegh-target-layout";

  /**
   * Key to set the MPEG-H UI configuration.
   * The corresponding value must be of value type {@link ValueType#VALUETYPE_STRING}.
   * This key is returned from the MPEG-H UI manager.
   */
  public final static String KEY_MPEGH_UI_CONFIG = "mpegh-ui-config";

  /**
   * Key to set the MPEG-H UI command.
   * The corresponding value must be of value type {@link ValueType#VALUETYPE_STRING}.
   * This key is passed to the MPEG-H UI manager.
   */
  public final static String KEY_MPEGH_UI_COMMAND = "mpegh-ui-command";

  /**
   * Key to set the MPEG-H UI persistence storage path.
   * The corresponding value must be of value type {@link ValueType#VALUETYPE_STRING}.
   * This key is passed to the MPEG-H UI manager.
   */
  public final static String KEY_MPEGH_UI_PERSISTENCESTORAGE_PATH = "mpegh-ui-persistencestorage-path";

  /**
   * @see MediaFormat#TYPE_NULL
   */
  public static final int VALUETYPE_NULL = 0; // MediaFormat.TYPE_NULL;
  /**
   * @see MediaFormat#TYPE_INTEGER
   */
  public static final int VALUETYPE_INT = 1; // MediaFormat.TYPE_INTEGER;
  /**
   * @see MediaFormat#TYPE_LONG
   */
  public static final int VALUETYPE_LONG = 2; // MediaFormat.TYPE_LONG;
  /**
   * @see MediaFormat#TYPE_FLOAT
   */
  public static final int VALUETYPE_FLOAT = 3; // MediaFormat.TYPE_FLOAT;
  /**
   * @see MediaFormat#TYPE_STRING
   */
  public static final int VALUETYPE_STRING = 4; // MediaFormat.TYPE_STRING;
  /**
   * @see MediaFormat#TYPE_BYTE_BUFFER
   */
  public static final int VALUETYPE_BYTE_BUFFER = 5; // MediaFormat.TYPE_BYTE_BUFFER;

  /**
   * Value types for a {@link CodecParameter}.
   * One of {@link #VALUETYPE_NULL}, {@link #VALUETYPE_INT}, {@link #VALUETYPE_LONG},
   * {@link #VALUETYPE_FLOAT}, {@link #VALUETYPE_STRING} or {@link #VALUETYPE_BYTE_BUFFER}.
   */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({
      VALUETYPE_NULL,
      VALUETYPE_INT,
      VALUETYPE_LONG,
      VALUETYPE_FLOAT,
      VALUETYPE_STRING,
      VALUETYPE_BYTE_BUFFER
  })
  public @interface ValueType {

  }


  public String key;
  public @Nullable Object value;
  public @ValueType int valueType;


  /**
   * Creates a new codec parameter.
   *
   * @param key       A string holding the key of the codec parameter.
   * @param value     An object representing the value of the codec parameter.
   * @param valueType The value type of the value object.
   */
  public CodecParameter(String key, @Nullable Object value, @ValueType int valueType) {
    this.key = key;
    this.value = value;
    this.valueType = valueType;
  }
}
