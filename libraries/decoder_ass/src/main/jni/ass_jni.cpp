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

static void draw_ass_rgba(uint8_t *dst, ptrdiff_t dst_stride,
                          const uint8_t *src, ptrdiff_t src_stride,
                          int w, int h, uint32_t color) {
  const uint8_t ass_r = (color >> 24) & 0xff; // Red (bits 24-31)
  const uint8_t ass_g = (color >> 16) & 0xff; // Green (bits 16-23)
  const uint8_t ass_b = (color >> 8) & 0xff; // Blue (bits 8-15)
  const uint8_t ass_a = 0xff - (color & 0xff); // Inverted Alpha (ASS uses 0 = opaque)

  // From libass: https://github.com/libass/libass/blob/1b699559025185e34d21a24cac477ca360cb917d/test/test.c#L149-L165
  const uint16_t ROUNDING_OFFSET = 255 * 255 / 2;
  for (size_t y = 0; y < h; y++) {
    for (size_t x = 0; x < w; x++) {
      uint16_t k = src[x] * ass_a;
      dst[x * 4 + 0] = (k * ass_r + (255 * 255 - k) * dst[x * 4 + 0] + ROUNDING_OFFSET) / (255 * 255);
      dst[x * 4 + 1] = (k * ass_g + (255 * 255 - k) * dst[x * 4 + 1] + ROUNDING_OFFSET) / (255 * 255);
      dst[x * 4 + 2] = (k * ass_b + (255 * 255 - k) * dst[x * 4 + 2] + ROUNDING_OFFSET) / (255 * 255);
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
        current->color
    );
  }

  AndroidBitmap_unlockPixels(env, bitmap);

  return env->NewObject(resultClass, resultConstructor, bitmap, changedSinceLastCall);
}