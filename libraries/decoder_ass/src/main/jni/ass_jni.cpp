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
#include <android/bitmap.h>
#include <android/log.h>
#include <cmath>
#include <string>

#include "ass/ass.h"
#include "ass/ass_types.h"

#define LIBASS_FUNC(RETURN_TYPE, NAME, ...)                                \
  extern "C" {                                                              \
  JNIEXPORT RETURN_TYPE Java_androidx_media3_decoder_ass_LibassJNI_##NAME( \
      JNIEnv* env, jobject thiz, ##__VA_ARGS__);                            \
  }                                                                         \
  JNIEXPORT RETURN_TYPE Java_androidx_media3_decoder_ass_LibassJNI_##NAME( \
      JNIEnv* env, jobject thiz, ##__VA_ARGS__)


class RGB {
 public:
  double r_prime, g_prime, b_prime;
  uint8_t r, g, b;

  RGB() = default;

  explicit RGB(uint8_t r, uint8_t g, uint8_t b) {
    this->r = r;
    this->g = g;
    this->b = b;

    this->r_prime = this->r / 255.0;
    this->g_prime = this->g / 255.0;
    this->b_prime = this->b / 255.0;
  }

  explicit RGB(double r_prime, double g_prime, double b_prime) {
    this->r_prime = r_prime;
    this->g_prime = g_prime;
    this->b_prime = b_prime;

    this->r = std::lround(this->r_prime * 255.0);
    this->g = std::lround(this->g_prime * 255.0);
    this->b = std::lround(this->b_prime * 255.0);
  }
};

struct YCbCr {
  double y, cb, cr;
};

struct ColorSpace {
  double kr, kg, kb;
};

enum class ColorSpaceEnum {
  UNKNOWN,
  BT601,
  BT709,
  FCC,
  SMPTE_240M,
  BT2020
};

// Must match https://developer.android.com/reference/androidx/media3/common/C.ColorSpace
enum ColorSpaceMedia3 {
  COLOR_SPACE_NO_VALUE = -1,
  COLOR_SPACE_BT601 = 2,
  COLOR_SPACE_BT709 = 1,
  COLOR_SPACE_BT2020 = 6,
};

constexpr ColorSpaceEnum getColorSpaceFromMedia3ColorSpace(ColorSpaceMedia3 color_space) {
  switch (color_space) {
    case ColorSpaceMedia3::COLOR_SPACE_NO_VALUE: case ColorSpaceMedia3::COLOR_SPACE_BT709: return ColorSpaceEnum::BT709;
    case ColorSpaceMedia3::COLOR_SPACE_BT601: return ColorSpaceEnum::BT601;
    case ColorSpaceMedia3::COLOR_SPACE_BT2020: return ColorSpaceEnum::BT2020;
    default: return ColorSpaceEnum::UNKNOWN;
  }
}

constexpr ColorSpace getColorSpace(ColorSpaceEnum space) {
  switch (space) {
    case ColorSpaceEnum::BT601: return {0.299, 0.587, 0.114};
    case ColorSpaceEnum::BT709: return {0.2126, 0.7152, 0.0722};
    case ColorSpaceEnum::FCC: return {0.3, 0.59, 0.11};
    case ColorSpaceEnum::SMPTE_240M: return {0.212, 0.701, 0.087};
    case ColorSpaceEnum::BT2020: return {0.2627, 0.6780, 0.0593};
    default: throw std::invalid_argument("Unsupported ColorSpaceEnum");
  }
}

enum class ColorRange {
  UNKNOWN,
  FULL,
  LIMITED
};

// Must match https://developer.android.com/reference/androidx/media3/common/C.ColorRange
enum ColorRangeMedia3 {
  COLOR_RANGE_NO_VALUE = -1,
  COLOR_RANGE_FULL = 1,
  COLOR_RANGE_LIMITED = 2,
};

constexpr ColorRange getColorRangeFromMedia3ColorRange(ColorRangeMedia3 color_range) {
  switch (color_range) {
    case ColorRangeMedia3::COLOR_RANGE_NO_VALUE: case ColorRangeMedia3::COLOR_RANGE_LIMITED: return ColorRange::LIMITED;
    case ColorRangeMedia3::COLOR_RANGE_FULL: return ColorRange::FULL;
    default: return ColorRange::UNKNOWN;
  }
}

class ColorConverter {
 private:
  ColorSpace color_space;
  ColorRange color_range;

 public:
  ColorConverter(ColorSpace color_space, ColorRange color_range)
      : color_space(color_space), color_range(color_range) {}

  YCbCr rgb_to_ycbcr(double r, double g, double b) const {
    // y_prime is [0, 1]
    // pb/pr are [-0.5, 0.5]
    double y_prime = color_space.kr * r + color_space.kg * g + color_space.kb * b;
    double pb = 0.5 * (b - y_prime) / (1.0 - color_space.kb);
    double pr = 0.5 * (r - y_prime) / (1.0 - color_space.kr);

    double y, cb, cr;
    if (color_range == ColorRange::FULL) {
      // y is [0, 255]
      // cb/cr are [0.5, 255.5] (but, anything above 255.5 is clipped to 255)
      y = y_prime * 255.0;
      cb = std::min(pb * 255.0 + 128.0, 255.0);
      cr = std::min(pr * 255.0 + 128.0, 255.0);
    } else if (color_range == ColorRange::LIMITED) {
      // y is [16, 235]
      // cb/cr are [16, 240]
      y = y_prime * 219.0 + 16.0;
      cb = pb * 224.0 + 128.0;
      cr = pr * 224.0 + 128.0;
    } else {
      throw std::invalid_argument("Unsupported ColorRange");
    }
    return YCbCr{y, cb, cr};
  }

  RGB ycbcr_to_rgb(double y, double cb, double cr) const {
    double y_prime, pb, pr;
    if (color_range == ColorRange::FULL) {
      // y is [0, 255] -> y_prime is [0, 1]
      // cb/cr are [0.5, 255] -> pb/pr are [-0.5, 0.5]
      y_prime = y / 255.0;
      pb = (cb - 128.0) / 255.0;
      pr = (cr - 128.0) / 255.0;
    } else if (color_range == ColorRange::LIMITED) {
      // y is [16, 235] -> y_prime is [0, 1]
      // cb/cr are [16, 240] -> pb/pr are [-0.5, 0.5]
      y_prime = (y - 16.0) / 219.0;
      pb = (cb - 128.0) / 224.0;
      pr = (cr - 128.0) / 224.0;
    } else {
      throw std::invalid_argument("Unsupported ColorRange");
    }

    double r = y_prime + pr * (1.0 - color_space.kr) * 2.0;
    double b = y_prime + pb * (1.0 - color_space.kb) * 2.0;
    double g = (y_prime - color_space.kr * r - color_space.kb * b) / color_space.kg;

    return RGB(std::clamp(r, 0.0, 1.0), std::clamp(g, 0.0, 1.0), std::clamp(b, 0.0, 1.0));
  }

  static RGB rgb_to_rgb(
      const ColorSpace src_color_space,
      const ColorRange src_color_range,
      const ColorSpace dst_color_space,
      const ColorRange dst_color_range,
      const RGB src_rgb
  ) {
    ColorConverter src_converter(src_color_space, src_color_range);
    ColorConverter dst_converter(dst_color_space, dst_color_range);

    YCbCr ycbcr = src_converter.rgb_to_ycbcr(src_rgb.r_prime, src_rgb.g_prime, src_rgb.b_prime);
    return dst_converter.ycbcr_to_rgb(ycbcr.y, ycbcr.cb, ycbcr.cr);
  }
};



static void draw_ass_rgba(uint8_t *dst, ptrdiff_t dst_stride,
                          const uint8_t *src, ptrdiff_t src_stride,
                          int w, int h, RGB color, uint8_t alpha) {

  // From libass: https://github.com/libass/libass/blob/1b699559025185e34d21a24cac477ca360cb917d/test/test.c#L149-L165
  const uint16_t ROUNDING_OFFSET = 255 * 255 / 2;
  for (size_t y = 0; y < h; y++) {
    for (size_t x = 0; x < w; x++) {
      uint16_t k = src[x] * alpha;
      dst[x * 4 + 0] = (k * color.r + (255 * 255 - k) * dst[x * 4 + 0] + ROUNDING_OFFSET) / (255 * 255);
      dst[x * 4 + 1] = (k * color.g + (255 * 255 - k) * dst[x * 4 + 1] + ROUNDING_OFFSET) / (255 * 255);
      dst[x * 4 + 2] = (k * color.b + (255 * 255 - k) * dst[x * 4 + 2] + ROUNDING_OFFSET) / (255 * 255);
      dst[x * 4 + 3] = (k * 255   + (255 * 255 - k) * dst[x * 4 + 3] + ROUNDING_OFFSET) / (255 * 255);
    }
    src += src_stride;
    dst += dst_stride;
  }
}

// Callback de libass pour les messages d'erreur
void libass_msg_callback(int level, const char *fmt, va_list args, void *data) {
  if (level < 6) {
    __android_log_vprint(ANDROID_LOG_DEBUG, "LIBASS_LOG", fmt, args);
  }
}

// Function to initialize the ASS_Library
LIBASS_FUNC(jlong, assLibraryInit) {
  ASS_Library *library = ass_library_init();
  if (!library) {
    return reinterpret_cast<jlong>(nullptr);
  }

  ass_set_message_cb(library, libass_msg_callback, nullptr);
  return reinterpret_cast<jlong>(library);
}

// Destroy ASS_Library
LIBASS_FUNC(void, assLibraryDone, jlong ass_library_ptr) {
  ASS_Library *library = reinterpret_cast<ASS_Library *>(ass_library_ptr);
  if (library) {
    ass_library_done(library);
  }
}

// add fonts to the library
LIBASS_FUNC(void, assAddFont, jlong ass_library_ptr, jstring font_name, jbyteArray font_data) {
  ASS_Library *library = reinterpret_cast<ASS_Library *>(ass_library_ptr);
  if (!library) {
    return;
  }

  // Convert jstring to const char *
  const char *name = env->GetStringUTFChars(font_name, nullptr);
  if (!name) {
    return;
  }

  // Convert jbyteArray to const char *
  jbyte *data = env->GetByteArrayElements(font_data, nullptr);
  if (!data) {
    env->ReleaseStringUTFChars(font_name, name);
    return;
  }
  jsize data_size = env->GetArrayLength(font_data);

  ass_add_font(library, name, reinterpret_cast<const char *>(data), data_size);

  // Release the JNI resources
  env->ReleaseStringUTFChars(font_name, name);
  env->ReleaseByteArrayElements(font_data, data, 0);
}

// Prepare data for processChunk
LIBASS_FUNC(void, assProcessChunk, jlong track, jbyteArray eventData,
            jint offset, jint length, jlong timecode, jlong duration) {
  jbyte *data = env->GetByteArrayElements(eventData, nullptr);
  if (!data) {
    return;
  }

  ass_process_chunk(reinterpret_cast<ASS_Track *>(track),
                    reinterpret_cast<const char *>(data + offset), length, timecode, duration);
  env->ReleaseByteArrayElements(eventData, data, 0);
}


// Initialize the ASS_Renderer
LIBASS_FUNC(jlong, assRendererInit, jlong ass_library_ptr) {
  ASS_Library *library = reinterpret_cast<ASS_Library *>(ass_library_ptr);
  if (!library) {
    return NULL;
  }

  ASS_Renderer *renderer = ass_renderer_init(library);
  if (!renderer) {
    return NULL;
  }

  ass_set_fonts(renderer, NULL, NULL, ASS_FONTPROVIDER_AUTODETECT, NULL, 1);
  return reinterpret_cast<jlong>(renderer);
}


// Destroy Ass Renderer instance
LIBASS_FUNC(void, assRendererDone, jlong ass_renderer_ptr) {
  ASS_Renderer *renderer = reinterpret_cast<ASS_Renderer *>(ass_renderer_ptr);
  if (renderer) {
    ass_renderer_done(renderer);
  }
}

// Sets the frame size for the ASS_Renderer.
LIBASS_FUNC(void, assSetFrameSize, jlong ass_renderer_ptr, jint width, jint height) {

  ASS_Renderer *renderer = reinterpret_cast<ASS_Renderer *>(ass_renderer_ptr);
  if (!renderer) {
    return;
  }

  ass_set_frame_size(renderer, width, height);
}


// Sets the storage size for the ASS_Renderer.
LIBASS_FUNC(void, assSetStorageSize, jlong ass_renderer_ptr, jint width, jint height) {
  ASS_Renderer *renderer = reinterpret_cast<ASS_Renderer *>(ass_renderer_ptr);
  if (!renderer) {
    return;
  }

  ass_set_storage_size(renderer, width, height);
}

// Creates new ASS_Track
LIBASS_FUNC(jlong, assNewTrack, jlong ass_library_ptr) {
  auto *library = reinterpret_cast<ASS_Library *>(ass_library_ptr);
  if (!library) {
    return NULL;
  }

  ASS_Track *track = ass_new_track(library);
  if (!track) {
    return NULL;
  }

  return reinterpret_cast<jlong>(track);
}


// Destroys the ASS_Track instance.
LIBASS_FUNC(void, assFreeTrack, jlong ass_track_ptr) {
  ASS_Track *track = reinterpret_cast<ASS_Track *>(ass_track_ptr);
  if (track) {
    ass_free_track(track);
  }
}

// Process codec private data
LIBASS_FUNC(void, assProcessCodecPrivate, jlong ass_track_ptr, jbyteArray data) {
  ASS_Track *track = reinterpret_cast<ASS_Track *>(ass_track_ptr);
  if (!track) {
    return;
  }

  // Convert jbyteArray to const char *
  jbyte *data_bytes = env->GetByteArrayElements(data, nullptr);
  if (!data_bytes) {
    return;
  }
  jsize data_size = env->GetArrayLength(data);

  // Call the libass function
  ass_process_codec_private(track, reinterpret_cast<const char *>(data_bytes), data_size);

  // Release the JNI resources
  env->ReleaseByteArrayElements(data, data_bytes, JNI_OK);
}


// Renders a frame for a specific track at the given timestamp.
LIBASS_FUNC(jobject, assRenderFrame, jlong ass_renderer_ptr, jlong ass_track_ptr, jint frame_width, jint frame_height, jlong time_ms, jint video_color_space, jint video_color_range) {
  jclass resultClass = env->FindClass("androidx/media3/decoder/ass/AssRenderResult");
  jmethodID resultConstructor = env->GetMethodID(resultClass, "<init>", "(Landroid/graphics/Bitmap;Z)V");

  ASS_Renderer *renderer = reinterpret_cast<ASS_Renderer *>(ass_renderer_ptr);
  ASS_Track *track = reinterpret_cast<ASS_Track *>(ass_track_ptr);

  if (!renderer || !track) {
    return env->NewObject(resultClass, resultConstructor, (jobject) nullptr, JNI_FALSE);
  }

  int detect_change;
  ASS_Image *img = ass_render_frame(renderer, track, time_ms, &detect_change);
  jboolean changedSinceLastCall = detect_change ? JNI_TRUE : JNI_FALSE;

  if (!detect_change || !img) {
    return env->NewObject(resultClass, resultConstructor, (jobject) nullptr, changedSinceLastCall);
  }

  ColorSpaceEnum dst_color_space_enum = getColorSpaceFromMedia3ColorSpace((ColorSpaceMedia3) video_color_space);
  if (dst_color_space_enum == ColorSpaceEnum::UNKNOWN) {
    std::string errorMessage = "The color space " + std::to_string(video_color_space) + " is invalid";
    env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), errorMessage.c_str());
    return env->NewObject(resultClass, resultConstructor, (jobject) nullptr, changedSinceLastCall);
  }

  ColorRange dst_color_range = getColorRangeFromMedia3ColorRange((ColorRangeMedia3) video_color_range);
  if (dst_color_range == ColorRange::UNKNOWN) {
    std::string errorMessage = "The color range " + std::to_string(video_color_range) + " is invalid";
    env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), errorMessage.c_str());
    return env->NewObject(resultClass, resultConstructor, (jobject) nullptr, changedSinceLastCall);
  }

  ColorSpaceEnum src_color_space_enum = ColorSpaceEnum::UNKNOWN;
  ColorRange src_color_range = ColorRange::UNKNOWN;
  switch (track->YCbCrMatrix) {
    case YCBCR_DEFAULT:
    case YCBCR_UNKNOWN:
    case YCBCR_BT601_TV:
      src_color_space_enum = ColorSpaceEnum::BT601;
      src_color_range = ColorRange::LIMITED;
      break;
    case YCBCR_BT601_PC:
      src_color_space_enum = ColorSpaceEnum::BT601;
      src_color_range = ColorRange::FULL;
      break;
    case YCBCR_BT709_TV:
      src_color_space_enum = ColorSpaceEnum::BT709;
      src_color_range = ColorRange::LIMITED;
      break;
    case YCBCR_BT709_PC:
      src_color_space_enum = ColorSpaceEnum::BT709;
      src_color_range = ColorRange::FULL;
      break;
    case YCBCR_SMPTE240M_TV:
      src_color_space_enum = ColorSpaceEnum::SMPTE_240M;
      src_color_range = ColorRange::LIMITED;
      break;
    case YCBCR_SMPTE240M_PC:
      src_color_space_enum = ColorSpaceEnum::SMPTE_240M;
      src_color_range = ColorRange::FULL;
      break;
    case YCBCR_FCC_TV:
      src_color_space_enum = ColorSpaceEnum::FCC;
      src_color_range = ColorRange::LIMITED;
      break;
    case YCBCR_FCC_PC:
      src_color_space_enum = ColorSpaceEnum::FCC;
      src_color_range = ColorRange::FULL;
      break;
  }

  ColorSpace src_color_space;
  ColorSpace dst_color_space = getColorSpace(dst_color_space_enum);
  if (src_color_space_enum != ColorSpaceEnum::UNKNOWN) {
    src_color_space = getColorSpace(src_color_space_enum);
  }

  // Create an Android Bitmap
  jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
  jmethodID createBitmapMethod = env->GetStaticMethodID(
      bitmapClass,
      "createBitmap",
      "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;"
  );

  // Use ARGB_8888 configuration for transparent bitmap
  jobject bitmap = env->CallStaticObjectMethod(
      bitmapClass,
      createBitmapMethod,
      frame_width,
      frame_height,
      env->GetStaticObjectField(
          env->FindClass("android/graphics/Bitmap$Config"),
          env->GetStaticFieldID(env->FindClass("android/graphics/Bitmap$Config"),
                                "ARGB_8888", "Landroid/graphics/Bitmap$Config;")
      )
  );

  AndroidBitmapInfo bitmapInfo;
  void *pixels = nullptr;
  if (AndroidBitmap_getInfo(env, bitmap, &bitmapInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
      AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
    return env->NewObject(resultClass, resultConstructor, (jobject) nullptr, changedSinceLastCall);
  }

  for (ASS_Image *current = img; current; current = current->next) {
    if (current->w <= 0 || current->h <= 0) {
      continue;
    }

    const uint8_t ass_r = (current->color >> 24) & 0xff; // Red (bits 24-31)
    const uint8_t ass_g = (current->color >> 16) & 0xff; // Green (bits 16-23)
    const uint8_t ass_b = (current->color >> 8) & 0xff; // Blue (bits 8-15)
    const uint8_t ass_a = 0xff - (current->color & 0xff); // Inverted Alpha (ASS uses 0 = opaque)

    RGB src_rgb = RGB(ass_r, ass_g, ass_b);
    RGB dst_rgb;
    if (src_color_space_enum == ColorSpaceEnum::UNKNOWN || src_color_range == ColorRange::UNKNOWN) {
      dst_rgb = src_rgb;
    } else {
      dst_rgb = ColorConverter::rgb_to_rgb(src_color_space, src_color_range, dst_color_space, dst_color_range, src_rgb);
    }

    uint8_t *dst = reinterpret_cast<uint8_t *>(pixels) +
        current->dst_y * bitmapInfo.stride + // Vertical offset
        current->dst_x * 4;                  // Horizontal offset (4 bytes per pixel)

    draw_ass_rgba(
        dst,
        bitmapInfo.stride,
        current->bitmap,
        current->stride,
        current->w,
        current->h,
        dst_rgb,
        ass_a
    );
  }

  AndroidBitmap_unlockPixels(env, bitmap);

  return env->NewObject(resultClass, resultConstructor, bitmap, changedSinceLastCall);
}