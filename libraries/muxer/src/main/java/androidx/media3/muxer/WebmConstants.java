/*
 * Copyright (C) 2025 The Android Open Source Project
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
package androidx.media3.muxer;

import static java.lang.annotation.ElementType.TYPE_USE;

import androidx.annotation.IntDef;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Defines constants used in WebM muxing. */
/* package */ final class WebmConstants {

  /**
   * The maximum size of a meta seek element.
   *
   * <p>This is used to estimate the size of the SeekHead element.
   */
  public static final int MAX_META_SEEK_SIZE = 72;

  /**
   * Represents an unknown length for an EBML Element Data Size field.
   *
   * <p>This is used for the Segment element, which holds the data of the WebM file.
   */
  public static final long MKV_UNKNOWN_LENGTH = 0x01ffffffffffffffL;

  /**
   * EBML element IDs are in form of VINT and are defined in <a
   * href="http://matroska.org/technical/specs/index.html">Matroska specifications</a>.
   */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({
    MkvEbmlElement.EBML,
    MkvEbmlElement.EBML_VERSION,
    MkvEbmlElement.EBML_READ_VERSION,
    MkvEbmlElement.EBML_MAX_ID_LENGTH,
    MkvEbmlElement.EBML_MAX_SIZE_LENGTH,
    MkvEbmlElement.DOC_TYPE,
    MkvEbmlElement.DOC_TYPE_VERSION,
    MkvEbmlElement.DOC_TYPE_READ_VERSION,
    MkvEbmlElement.VOID,
    MkvEbmlElement.SIGNATURE_SLOT,
    MkvEbmlElement.SIGNATURE_ALGO,
    MkvEbmlElement.SIGNATURE_HASH,
    MkvEbmlElement.SIGNATURE_PUBLIC_KEY,
    MkvEbmlElement.SIGNATURE,
    MkvEbmlElement.SIGNATURE_ELEMENTS,
    MkvEbmlElement.SIGNATURE_ELEMENT_LIST,
    MkvEbmlElement.SIGNED_ELEMENT,
    MkvEbmlElement.SEGMENT,
    MkvEbmlElement.SEEK_HEAD,
    MkvEbmlElement.SEEK,
    MkvEbmlElement.SEEK_ID,
    MkvEbmlElement.SEEK_POSITION,
    MkvEbmlElement.INFO,
    MkvEbmlElement.TIMESTAMP_SCALE,
    MkvEbmlElement.SEGMENT_DURATION,
    MkvEbmlElement.DATE_UTC,
    MkvEbmlElement.MUXING_APP,
    MkvEbmlElement.WRITING_APP,
    MkvEbmlElement.CLUSTER,
    MkvEbmlElement.TIMESTAMP,
    MkvEbmlElement.PREV_SIZE,
    MkvEbmlElement.BLOCK_GROUP,
    MkvEbmlElement.BLOCK,
    MkvEbmlElement.BLOCK_ADDITIONS,
    MkvEbmlElement.BLOCK_MORE,
    MkvEbmlElement.BLOCK_ADD_ID,
    MkvEbmlElement.BLOCK_ADDITIONAL,
    MkvEbmlElement.BLOCK_DURATION,
    MkvEbmlElement.REFERENCE_BLOCK,
    MkvEbmlElement.LACE_NUMBER,
    MkvEbmlElement.SIMPLE_BLOCK,
    MkvEbmlElement.TRACKS,
    MkvEbmlElement.TRACK_ENTRY,
    MkvEbmlElement.TRACK_NUMBER,
    MkvEbmlElement.TRACK_UID,
    MkvEbmlElement.TRACK_TYPE,
    MkvEbmlElement.FLAG_ENABLED,
    MkvEbmlElement.FLAG_DEFAULT,
    MkvEbmlElement.FLAG_FORCED,
    MkvEbmlElement.FLAG_LACING,
    MkvEbmlElement.DEFAULT_DURATION,
    MkvEbmlElement.MAX_BLOCK_ADDITION_ID,
    MkvEbmlElement.NAME,
    MkvEbmlElement.LANGUAGE,
    MkvEbmlElement.CODEC_ID,
    MkvEbmlElement.CODEC_PRIVATE,
    MkvEbmlElement.CODEC_NAME,
    MkvEbmlElement.VIDEO,
    MkvEbmlElement.FLAG_INTERLACED,
    MkvEbmlElement.STEREO_MODE,
    MkvEbmlElement.ALPHA_MODE,
    MkvEbmlElement.PIXEL_WIDTH,
    MkvEbmlElement.PIXEL_HEIGHT,
    MkvEbmlElement.PIXEL_CROP_BOTTOM,
    MkvEbmlElement.PIXEL_CROP_TOP,
    MkvEbmlElement.PIXEL_CROP_LEFT,
    MkvEbmlElement.PIXEL_CROP_RIGHT,
    MkvEbmlElement.DISPLAY_WIDTH,
    MkvEbmlElement.DISPLAY_HEIGHT,
    MkvEbmlElement.DISPLAY_UNIT,
    MkvEbmlElement.ASPECT_RATIO_TYPE,
    MkvEbmlElement.COLOUR,
    MkvEbmlElement.MATRIX_COEFFICIENTS,
    MkvEbmlElement.RANGE,
    MkvEbmlElement.TRANSFER_CHARACTERISTICS,
    MkvEbmlElement.PRIMARIES,
    MkvEbmlElement.MAX_CLL,
    MkvEbmlElement.MAX_FALL,
    MkvEbmlElement.MASTERING_METADATA,
    MkvEbmlElement.PRIMARY_R_CHROMATICITY_X,
    MkvEbmlElement.PRIMARY_R_CHROMATICITY_Y,
    MkvEbmlElement.PRIMARY_G_CHROMATICITY_X,
    MkvEbmlElement.PRIMARY_G_CHROMATICITY_Y,
    MkvEbmlElement.PRIMARY_B_CHROMATICITY_X,
    MkvEbmlElement.PRIMARY_B_CHROMATICITY_Y,
    MkvEbmlElement.WHITE_POINT_CHROMATICITY_X,
    MkvEbmlElement.WHITE_POINT_CHROMATICITY_Y,
    MkvEbmlElement.LUMINANCE_MAX,
    MkvEbmlElement.LUMINANCE_MIN,
    MkvEbmlElement.FRAME_RATE,
    MkvEbmlElement.AUDIO,
    MkvEbmlElement.SAMPLING_FREQUENCY,
    MkvEbmlElement.OUTPUT_SAMPLING_FREQUENCY,
    MkvEbmlElement.CHANNELS,
    MkvEbmlElement.BIT_DEPTH,
    MkvEbmlElement.CUES,
    MkvEbmlElement.CUE_POINT,
    MkvEbmlElement.CUE_TIME,
    MkvEbmlElement.CUE_TRACK_POSITIONS,
    MkvEbmlElement.CUE_TRACK,
    MkvEbmlElement.CUE_CLUSTER_POSITION,
    MkvEbmlElement.CUE_BLOCK_NUMBER
  })
  public @interface MkvEbmlElement {
    int EBML = 0x1A45DFA3;
    int EBML_VERSION = 0x4286;
    int EBML_READ_VERSION = 0x42F7;
    int EBML_MAX_ID_LENGTH = 0x42F2;
    int EBML_MAX_SIZE_LENGTH = 0x42F3;
    int DOC_TYPE = 0x4282;
    int DOC_TYPE_VERSION = 0x4287;
    int DOC_TYPE_READ_VERSION = 0x4285;
    int VOID = 0xEC;
    int SIGNATURE_SLOT = 0x1B538667;
    int SIGNATURE_ALGO = 0x7E8A;
    int SIGNATURE_HASH = 0x7E9A;
    int SIGNATURE_PUBLIC_KEY = 0x7EA5;
    int SIGNATURE = 0x7EB5;
    int SIGNATURE_ELEMENTS = 0x7E5B;
    int SIGNATURE_ELEMENT_LIST = 0x7E7B;
    int SIGNED_ELEMENT = 0x6532;
    int SEGMENT = 0x18538067;
    int SEEK_HEAD = 0x114D9B74;
    int SEEK = 0x4DBB;
    int SEEK_ID = 0x53AB;
    int SEEK_POSITION = 0x53AC;
    int INFO = 0x1549A966;
    int TIMESTAMP_SCALE = 0x2AD7B1;
    int SEGMENT_DURATION = 0x4489;
    int DATE_UTC = 0x4461;
    int MUXING_APP = 0x4D80;
    int WRITING_APP = 0x5741;
    int CLUSTER = 0x1F43B675;
    int TIMESTAMP = 0xE7;
    int PREV_SIZE = 0xAB;
    int BLOCK_GROUP = 0xA0;
    int BLOCK = 0xA1;
    int BLOCK_ADDITIONS = 0x75A1;
    int BLOCK_MORE = 0xA6;
    int BLOCK_ADD_ID = 0xEE;
    int BLOCK_ADDITIONAL = 0xA5;
    int BLOCK_DURATION = 0x9B;
    int REFERENCE_BLOCK = 0xFB;
    int LACE_NUMBER = 0xCC;
    int SIMPLE_BLOCK = 0xA3;
    int TRACKS = 0x1654AE6B;
    int TRACK_ENTRY = 0xAE;
    int TRACK_NUMBER = 0xD7;
    int TRACK_UID = 0x73C5;
    int TRACK_TYPE = 0x83;
    int FLAG_ENABLED = 0xB9;
    int FLAG_DEFAULT = 0x88;
    int FLAG_FORCED = 0x55AA;
    int FLAG_LACING = 0x9C;
    int DEFAULT_DURATION = 0x23E383;
    int MAX_BLOCK_ADDITION_ID = 0x55EE;
    int NAME = 0x536E;
    int LANGUAGE = 0x22B59C;
    int CODEC_ID = 0x86;
    int CODEC_PRIVATE = 0x63A2;
    int CODEC_NAME = 0x258688;
    int VIDEO = 0xE0;
    int FLAG_INTERLACED = 0x9A;
    int STEREO_MODE = 0x53B8;
    int ALPHA_MODE = 0x53C0;
    int PIXEL_WIDTH = 0xB0;
    int PIXEL_HEIGHT = 0xBA;
    int PIXEL_CROP_BOTTOM = 0x54AA;
    int PIXEL_CROP_TOP = 0x54BB;
    int PIXEL_CROP_LEFT = 0x54CC;
    int PIXEL_CROP_RIGHT = 0x54DD;
    int DISPLAY_WIDTH = 0x54B0;
    int DISPLAY_HEIGHT = 0x54BA;
    int DISPLAY_UNIT = 0x54B2;
    int ASPECT_RATIO_TYPE = 0x54B3;
    int COLOUR = 0x55B0;
    int MATRIX_COEFFICIENTS = 0x55B1;
    int RANGE = 0x55B9;
    int TRANSFER_CHARACTERISTICS = 0x55BA;
    int PRIMARIES = 0x55BB;
    int MAX_CLL = 0x55BC;
    int MAX_FALL = 0x55BD;
    int MASTERING_METADATA = 0x55D0;
    int PRIMARY_R_CHROMATICITY_X = 0x55D1;
    int PRIMARY_R_CHROMATICITY_Y = 0x55D2;
    int PRIMARY_G_CHROMATICITY_X = 0x55D3;
    int PRIMARY_G_CHROMATICITY_Y = 0x55D4;
    int PRIMARY_B_CHROMATICITY_X = 0x55D5;
    int PRIMARY_B_CHROMATICITY_Y = 0x55D6;
    int WHITE_POINT_CHROMATICITY_X = 0x55D7;
    int WHITE_POINT_CHROMATICITY_Y = 0x55D8;
    int LUMINANCE_MAX = 0x55D9;
    int LUMINANCE_MIN = 0x55DA;
    int FRAME_RATE = 0x2383E3;
    int AUDIO = 0xE1;
    int SAMPLING_FREQUENCY = 0xB5;
    int OUTPUT_SAMPLING_FREQUENCY = 0x78B5;
    int CHANNELS = 0x9F;
    int BIT_DEPTH = 0x6264;
    int CUES = 0x1C53BB6B;
    int CUE_POINT = 0xBB;
    int CUE_TIME = 0xB3;
    int CUE_TRACK_POSITIONS = 0xB7;
    int CUE_TRACK = 0xF7;
    int CUE_CLUSTER_POSITION = 0xF1;
    int CUE_BLOCK_NUMBER = 0x5378;
  }

  /** Standard Matroska track types. */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({
    TrackType.INVALID,
    TrackType.VIDEO,
    TrackType.AUDIO,
    TrackType.COMPLEX,
    TrackType.LOGO,
    TrackType.SUBTITLE,
    TrackType.BUTTONS,
    TrackType.CONTROL
  })
  public @interface TrackType {
    int INVALID = -1;
    int VIDEO = 0x1;
    int AUDIO = 0x2;
    int COMPLEX = 0x3;
    int LOGO = 0x10;
    int SUBTITLE = 0x11;
    int BUTTONS = 0x12;
    int CONTROL = 0x20;
  }

  /** Standard Matroska track numbers. */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({TrackNumber.VIDEO, TrackNumber.AUDIO})
  public @interface TrackNumber {
    int VIDEO = 0x1;
    int AUDIO = 0x2;
  }

  private WebmConstants() {}
}
