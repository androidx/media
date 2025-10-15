/*
 * Copyright 2024 The Android Open Source Project
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

#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <algorithm>
#include <cassert>
#include <cerrno>
#include <cstddef>
#include <cstdio>
#include <memory>
#include <unordered_map>

#include "cpu_features_macros.h"  // NOLINT
#include "dav1d/common.h"
#include "dav1d/data.h"
#include "dav1d/dav1d.h"
#include "dav1d/picture.h"

// For ARMv7, we use `cpu_feature` to detect availability of NEON at runtime.
#ifdef CPU_FEATURES_ARCH_ARM
#include "cpuinfo_arm.h"  // NOLINT
#endif                    // CPU_FEATURES_ARCH_ARM

// For ARM in general (v7/v8) we detect compile time availability of NEON.
#ifdef CPU_FEATURES_ARCH_ANY_ARM
#if CPU_FEATURES_COMPILED_ANY_ARM_NEON  // always defined to 0 or 1.
#define HAS_COMPILE_TIME_NEON_SUPPORT
#endif  // CPU_FEATURES_COMPILED_ANY_ARM_NEON
#endif  // CPU_FEATURES_ARCH_ANY_ARM

#include <jni.h>

#include <cstdint>
#include <cstring>
#include <mutex>  // NOLINT
#include <new>
#include <vector>

#include "cpu_info.h"  // NOLINT

#define LOG_TAG "dav1d_jni"
#define LOGE(...) \
  ((void)__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__))

#define DECODER_FUNC(RETURN_TYPE, NAME, ...)                                  \
  extern "C" {                                                                \
  JNIEXPORT RETURN_TYPE Java_androidx_media3_decoder_av1_Dav1dDecoder_##NAME( \
      JNIEnv* env, jobject thiz, ##__VA_ARGS__);                              \
  }                                                                           \
  JNIEXPORT RETURN_TYPE Java_androidx_media3_decoder_av1_Dav1dDecoder_##NAME( \
      JNIEnv* env, jobject thiz, ##__VA_ARGS__)

// If ANDROID_NATIVE_LIB_MERGING is set, rename JNI_OnLoad to
// JNI_OnLoad_libdav1dJNI. The suffix has to be the same as "lib<rule_name>"
// where <rule_name> is the name of the android_jni_library target. This lets
// apps merge native libraries and reduce the binary size. See go/android_onelib
// for details.
#ifdef ANDROID_NATIVE_LIB_MERGING
jint JNI_OnLoad_libdav1dJNI
#else
jint JNI_OnLoad
#endif
    (JavaVM* vm, void* reserved) {
  JNIEnv* env;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
    return -1;
  }
  return JNI_VERSION_1_6;
}

namespace {

// YUV plane indices.
const int kPlaneY = 0;
const int kPlaneU = 1;
const int kPlaneV = 2;
const int kMaxPlanes = 3;

// Android YUV format. See:
// https://developer.android.com/reference/android/graphics/ImageFormat.html#YV12.
const int kImageFormatYV12 = 0x32315659;

// LINT.IfChange
// Output modes.
const int kOutputModeYuv = 0;
const int kOutputModeSurfaceYuv = 1;
// LINT.ThenChange(../../../../common/src/main/java/androidx/media3/common/C.java)

// LINT.IfChange
const int kColorSpaceUnknown = 0;
const int kColorSpaceBT601 = 1;
const int kColorSpaceBT709 = 2;
const int kColorSpaceBT2020 = 3;
// LINT.ThenChange(../../../../decoder/src/main/java/androidx/media3/decoder/VideoDecoderOutputBuffer.java)

// LINT.IfChange
// Return codes for jni methods.
const int kStatusError = 0;
const int kStatusOk = 1;
const int kStatusDecodeOnly = 2;
const int kStatusEagain = 3;
// LINT.ThenChange(../java/androidx/media3/decoder/av1/Dav1dDecoder.java)

// LINT.IfChange
// Dav1d thread count settings
const int kDav1dThreadCountDefault = 0;
const int kDav1dThreadCountPerformanceCores = -1;
const int kDav1dThreadCountExperimental = -2;
// LINT.ThenChange(../java/androidx/media3/decoder/av1/Libdav1dVideoRenderer.java)

// Status codes specific to the JNI wrapper code.
enum JniStatusCode {
  kJniStatusOk = 0,
  kJniStatusOutOfMemory = -1,
  kJniStatusBufferAlreadyReleased = -2,
  kJniStatusInvalidNumOfPlanes = -3,
  kJniStatusHighBitDepthNotSupportedWithYuv = -4,
  kJniStatusBufferResizeError = -5,
  kJniStatusNeonNotSupported = -6,
  kJniStatusSurfaceYuvNotSupported = -7,
  kJniStatusDecoderInitFailed = -8,
  kJniStatusBufferInitError = -9,
  kJniStatusANativeWindowError = -10,
};

const int kLibdav1dDecoderStatusOk = 0;

const char* GetJniErrorMessage(JniStatusCode error_code) {
  switch (error_code) {
    case kJniStatusOutOfMemory:
      return "Out of memory.";
    case kJniStatusBufferAlreadyReleased:
      return "JNI buffer already released.";
    case kJniStatusHighBitDepthNotSupportedWithYuv:
      return "High bit depth (10 or 12 bits per pixel) output format is not "
             "supported with YUV.";
    case kJniStatusInvalidNumOfPlanes:
      return "Libdav1d decoded buffer has invalid number of planes.";
    case kJniStatusBufferResizeError:
      return "Buffer resize failed.";
    case kJniStatusNeonNotSupported:
      return "Neon is not supported.";
    case kJniStatusSurfaceYuvNotSupported:
      return "Surface YUV is not supported.";
    case kJniStatusDecoderInitFailed:
      return "Decoder initialization failed.";
    case kJniStatusBufferInitError:
      return "Output buffer initialization failed.";
    default:
      return "Unrecognized error code.";
  }
}

const int GetThreadCount(jint threads) {
  switch (threads) {
    case kDav1dThreadCountDefault:
      return 0;
    case kDav1dThreadCountPerformanceCores:
      return dav1d_jni::GetNumberOfPerformanceCoresOnline();
    case kDav1dThreadCountExperimental:
      return dav1d_jni::GetNumberOfProcessorsOnline() / 2;
    default:
      return threads;
  }
}

// TODO: b/446178978 - Refactor buffer management to remove global JNI
// references. Remove global_ref_input_buffer and global_ref_dav1d_data from
// Cookie. Instead, manage buffers by passing an index into a Java-side
// DecoderInputBuffer array.
struct Cookie {
  jobject global_ref_input_buffer;
  jobject global_ref_dav1d_data;
  jlong jni_context;
};

struct UserDataCookie {
  jboolean decode_only;
  jint flags;
  jint output_mode;
  jlong time_us;
  jlong jni_context;
};

struct PictureAllocatorCookie {
  JavaVM* jvm;
  jlong jni_context;
};

struct PictureAllocatorData {
  int aligned_width;
  int aligned_height;
  int offset;
  uint8_t* aligned_buffer_ptr;
  jobject direct_byte_buffer;
};

struct JniContext {
  ~JniContext() {
    if (native_window) {
      ANativeWindow_release(native_window);
    }
  }

  bool MaybeAcquireNativeWindow(JNIEnv* env, jobject new_surface) {
    if (surface == new_surface) {
      return true;
    }
    if (native_window) {
      ANativeWindow_release(native_window);
    }
    native_window_width = 0;
    native_window_height = 0;
    native_window = ANativeWindow_fromSurface(env, new_surface);
    if (native_window == nullptr) {
      jni_status_code = kJniStatusANativeWindowError;
      surface = nullptr;
      return false;
    }
    surface = new_surface;
    return true;
  }

  jobject input_buffer_class;
  jobject output_buffer_class;
  jobject byte_buffer_class;
  jobject decoder_class;
  jfieldID display_width_field;
  jfieldID display_height_field;
  jfieldID data_field;
  jfieldID input_data_field;
  jfieldID output_buffer_stride_array_field;
  jfieldID ystride_field;
  jfieldID uvstride_field;
  jfieldID decoder_private_field;
  jmethodID init_for_yuv_frame_method;
  jmethodID release_input_buffer_method;
  jmethodID init_output_buffer_method;
  jmethodID set_flags_method;
  jmethodID set_format_method;
  jmethodID init_for_offset_frames_method;
  jmethodID init_for_private_frame_method;
  jmethodID create_direct_byte_buffer_method;

  Dav1dContext* decoder;

  int libdav1d_status_code = kLibdav1dDecoderStatusOk;
  JniStatusCode jni_status_code = kJniStatusOk;
  // TODO: b/446178978 - Replace unused_cookies std::vector with a fixed-size
  // array. The size should be bounded by the number of input buffers to avoid
  // dynamic allocations.
  std::vector<std::unique_ptr<Cookie>> unused_cookies;
  std::mutex unused_cookies_mutex;  // NOLINT(build/c++11)
  std::vector<std::unique_ptr<PictureAllocatorData>>
      unused_picture_allocator_data;
  std::mutex unused_picture_allocator_data_mutex;  // NOLINT(build/c++11)
  bool use_custom_allocator;
  std::unique_ptr<PictureAllocatorCookie> picture_allocator_cookie;
  ANativeWindow* native_window = nullptr;
  jobject surface = nullptr;
  int native_window_width = 0;
  int native_window_height = 0;

  JavaVM* jvm;
};

void CopyFrameToDataBuffer(const Dav1dPicture* dav1d_picture, jbyte* data) {
  for (int plane_index = kPlaneY; plane_index < kMaxPlanes; plane_index++) {
    int stride_index = plane_index;
    if (plane_index == kPlaneV) {
      stride_index = kPlaneU;
    }
    uint64_t planeHeight = (plane_index == kPlaneY)
                               ? dav1d_picture->p.h
                               : ((dav1d_picture->p.h + 1) / 2);
    uint64_t length = dav1d_picture->stride[stride_index] * planeHeight;
    memcpy(data, dav1d_picture->data[plane_index], length);
    data += length;
  }
}

constexpr int AlignTo16(int value) { return (value + 15) & (~15); }

void CopyPlane(const uint8_t* source, int source_stride, uint8_t* destination,
               int destination_stride, int width, int height) {
  while (height--) {
    std::memcpy(destination, source, width);
    source += source_stride;
    destination += destination_stride;
  }
}

constexpr int GetColorSpace(Dav1dColorPrimaries primary) {
  switch (primary) {
    case DAV1D_COLOR_PRI_BT601:
      return kColorSpaceBT601;
    case DAV1D_COLOR_PRI_BT709:
      return kColorSpaceBT709;
    case DAV1D_COLOR_PRI_BT2020:
      return kColorSpaceBT2020;
    default:
      return kColorSpaceUnknown;
  }
}
void Dav1dDataFreeCallback(const uint8_t* data, void* cookie) {
  Cookie* cookie_ptr = reinterpret_cast<Cookie*>(cookie);
  JniContext* const context =
      reinterpret_cast<JniContext*>(cookie_ptr->jni_context);
  std::lock_guard<std::mutex> unused_cookies_lock(  // NOLINT(build/c++11)
      context->unused_cookies_mutex);
  try {
    context->unused_cookies.emplace_back(cookie_ptr);
  } catch (const std::bad_alloc&) {
    // Allocation failed for unique_ptr, prevent memory leak
    LOGE("Failed to emplace Cookie: Out of memory");
    JNIEnv* env = nullptr;
    // Ensure the current thread is attached to the JVM
    jint attach_result = context->jvm->AttachCurrentThread(&env, nullptr);
    if (attach_result == JNI_OK) {
      env->DeleteGlobalRef(cookie_ptr->global_ref_dav1d_data);
      env->DeleteGlobalRef(cookie_ptr->global_ref_input_buffer);
    } else {
      LOGE("Failed to attach current thread to JVM in Data callback.");
    }
    delete cookie_ptr;
  }
}

void Dav1dUserDataFreeCallback(const uint8_t* data, void* cookie) {
  const UserDataCookie* cookie_ptr =
      reinterpret_cast<const UserDataCookie*>(data);
  delete cookie_ptr;
}

static int dav1d_picture_allocator(Dav1dPicture* p, void* cookie) {
  // Do all of the sizing math here.
  const int hbd = p->p.bpc > 8;
  const int aligned_w = (p->p.w + 127) & ~127;
  const int aligned_h = (p->p.h + 127) & ~127;
  const int has_chroma = p->p.layout != DAV1D_PIXEL_LAYOUT_I400;
  const int ss_ver = p->p.layout == DAV1D_PIXEL_LAYOUT_I420;
  const int ss_hor = p->p.layout != DAV1D_PIXEL_LAYOUT_I444;
  ptrdiff_t y_stride = aligned_w << hbd;
  ptrdiff_t uv_stride = has_chroma ? y_stride >> ss_hor : 0;
  if (!(y_stride & 1023)) y_stride += DAV1D_PICTURE_ALIGNMENT;
  if (!(uv_stride & 1023) && has_chroma) uv_stride += DAV1D_PICTURE_ALIGNMENT;
  p->stride[0] = y_stride;
  p->stride[1] = uv_stride;
  const size_t y_sz = y_stride * aligned_h;
  const size_t uv_sz = uv_stride * (aligned_h >> ss_ver);
  const size_t pic_size = y_sz + 2 * uv_sz;
  const size_t total_size = pic_size + 2 * DAV1D_PICTURE_ALIGNMENT;

  // Get the byte buffer for storing data.
  JNIEnv* env;
  PictureAllocatorCookie* allocator_cookie =
      reinterpret_cast<PictureAllocatorCookie*>(cookie);
  allocator_cookie->jvm->GetEnv(reinterpret_cast<void**>(&env),
                                JNI_VERSION_1_6);

  JniContext* const context =
      reinterpret_cast<JniContext*>(allocator_cookie->jni_context);
  jobject direct_byte_buffer = env->CallStaticObjectMethod(
      reinterpret_cast<jclass>(context->byte_buffer_class),
      context->create_direct_byte_buffer_method, total_size);

  if (direct_byte_buffer == nullptr) {
    LOGE("Failed to create direct byte buffer.");
    return DAV1D_ERR(ENOMEM);
  }

  void* buffer_ptr = env->GetDirectBufferAddress(direct_byte_buffer);
  if (buffer_ptr == nullptr) {
    LOGE("Failed to get direct buffer address.");
    return DAV1D_ERR(ENOMEM);
  }

  size_t space = total_size;
  uint8_t* aligned_buf_address = reinterpret_cast<uint8_t*>(
      std::align(DAV1D_PICTURE_ALIGNMENT, pic_size + DAV1D_PICTURE_ALIGNMENT,
                 buffer_ptr, space));
  if (aligned_buf_address == nullptr) {
    LOGE("Failed to align buffer.");
    return DAV1D_ERR(ENOMEM);
  }

  PictureAllocatorData* allocator_data = new PictureAllocatorData();
  allocator_data->aligned_width = aligned_w;
  allocator_data->aligned_height = aligned_h;
  allocator_data->offset = total_size - space;
  allocator_data->aligned_buffer_ptr = aligned_buf_address;
  allocator_data->direct_byte_buffer = env->NewGlobalRef(direct_byte_buffer);

  p->allocator_data = reinterpret_cast<void*>(allocator_data);

  p->data[0] = aligned_buf_address;
  p->data[1] = has_chroma ? aligned_buf_address + y_sz : NULL;
  p->data[2] = has_chroma ? aligned_buf_address + y_sz + uv_sz : NULL;

  return 0;
}

static void release_picture_allocator(Dav1dPicture* p, void* cookie) {
  PictureAllocatorCookie* allocator_cookie =
      reinterpret_cast<PictureAllocatorCookie*>(cookie);

  JniContext* const context =
      reinterpret_cast<JniContext*>(allocator_cookie->jni_context);

  std::lock_guard<std::mutex>  // NOLINT(build/c++11)
      unused_picture_allocator_data_lock(
          context->unused_picture_allocator_data_mutex);

  // TODO(b/443089644): Consider wrapping this in a try-catch (std::bad_alloc)
  // to prevent resource leaks if emplace_back fails.
  context->unused_picture_allocator_data.emplace_back(
      reinterpret_cast<PictureAllocatorData*>(p->allocator_data));
}

void CleanUpAllocatorData(jlong jContext, JNIEnv* env) {
  if (jContext == kStatusError) {
    return;
  }
  JniContext* const context = reinterpret_cast<JniContext*>(jContext);
  std::lock_guard<std::mutex>  // NOLINT(build/c++11)
      unused_picture_allocator_data_lock(
          context->unused_picture_allocator_data_mutex);
  while (!context->unused_picture_allocator_data.empty()) {
    PictureAllocatorData* allocator_data =
        context->unused_picture_allocator_data.back().get();
    env->DeleteGlobalRef(allocator_data->direct_byte_buffer);
    context->unused_picture_allocator_data.pop_back();
  }
}
}  // namespace

DECODER_FUNC(jlong, dav1dInit, jint threads, jint max_frame_delay,
             jboolean use_custom_allocator) {
  JniContext* context = new (std::nothrow) JniContext();
  if (context == nullptr) {
    return kStatusError;
  }

  // Always get and store the JVM
  if (env->GetJavaVM(&context->jvm) != JNI_OK) {
    LOGE("Failed to get JavaVM");
    delete context;
    return kStatusError;
  }

#ifdef CPU_FEATURES_ARCH_ANY_ARM       // Arm v7/v8
#ifndef HAS_COMPILE_TIME_NEON_SUPPORT  // no compile time NEON support
#ifdef CPU_FEATURES_ARCH_ARM           // check runtime support for ARMv7
  if (cpu_features::GetArmInfo().features.neon == false) {
    context->jni_status_code = kJniStatusNeonNotSupported;
    return reinterpret_cast<jlong>(context);
  }
#else   // Unexpected case of an ARMv8 with no NEON support.
  context->jni_status_code = kJniStatusNeonNotSupported;
  return reinterpret_cast<jlong>(context);
#endif  // CPU_FEATURES_ARCH_ARM
#endif  // HAS_COMPILE_TIME_NEON_SUPPORT
#endif  // CPU_FEATURES_ARCH_ANY_ARM

  Dav1dSettings settings;
  dav1d_default_settings(&settings);
  settings.n_threads = GetThreadCount(threads);
  settings.max_frame_delay = max_frame_delay;
  context->use_custom_allocator = use_custom_allocator;
  if (use_custom_allocator) {
    PictureAllocatorCookie* cookie = new PictureAllocatorCookie();
    // context->jvm is already set
    cookie->jvm = context->jvm;
    cookie->jni_context = reinterpret_cast<jlong>(context);
    context->picture_allocator_cookie =
        std::unique_ptr<PictureAllocatorCookie>(cookie);

    Dav1dPicAllocator allocator = {
        .cookie = reinterpret_cast<void*>(cookie),
        .alloc_picture_callback = dav1d_picture_allocator,
        .release_picture_callback = release_picture_allocator,
    };
    settings.allocator = allocator;
  }

  context->libdav1d_status_code = dav1d_open(&context->decoder, &settings);
  if (context->libdav1d_status_code != 0) {
    context->jni_status_code = kJniStatusDecoderInitFailed;
  }

  // Populate JNI References.
  const jclass inputBufferClass =
      env->FindClass("androidx/media3/decoder/DecoderInputBuffer");
  const jclass outputBufferClass =
      env->FindClass("androidx/media3/decoder/VideoDecoderOutputBuffer");
  const jclass decoderClass =
      env->FindClass("androidx/media3/decoder/av1/Dav1dDecoder");
  const jclass byteBufferClass = env->FindClass("java/nio/ByteBuffer");

  context->input_buffer_class = env->NewGlobalRef(inputBufferClass);
  context->output_buffer_class = env->NewGlobalRef(outputBufferClass);
  context->decoder_class = env->NewGlobalRef(decoderClass);
  context->byte_buffer_class = env->NewGlobalRef(byteBufferClass);

  context->input_data_field =
      env->GetFieldID(inputBufferClass, "data", "Ljava/nio/ByteBuffer;");
  context->display_width_field =
      env->GetFieldID(outputBufferClass, "width", "I");
  context->display_height_field =
      env->GetFieldID(outputBufferClass, "height", "I");
  context->output_buffer_stride_array_field =
      env->GetFieldID(outputBufferClass, "yuvStrides", "[I");
  context->ystride_field = env->GetFieldID(outputBufferClass, "yStride", "I");
  context->uvstride_field = env->GetFieldID(outputBufferClass, "uvStride", "I");
  context->init_output_buffer_method =
      env->GetMethodID(outputBufferClass, "init", "(JILjava/nio/ByteBuffer;)V");
  context->data_field =
      env->GetFieldID(outputBufferClass, "data", "Ljava/nio/ByteBuffer;");
  context->decoder_private_field =
      env->GetFieldID(outputBufferClass, "decoderPrivate", "J");
  context->init_for_yuv_frame_method =
      env->GetMethodID(outputBufferClass, "initForYuvFrame", "(IIIII)Z");
  context->init_for_private_frame_method =
      env->GetMethodID(outputBufferClass, "initForPrivateFrame", "(II)V");
  context->set_flags_method =
      env->GetMethodID(outputBufferClass, "setFlags", "(I)V");
  context->release_input_buffer_method =
      env->GetMethodID(decoderClass, "releaseInputBuffer",
                       "(Landroidx/media3/decoder/DecoderInputBuffer;)V");
  context->init_for_offset_frames_method =
      env->GetMethodID(outputBufferClass, "initForOffsetFrames", "(IIIIIII)Z");
  context->create_direct_byte_buffer_method = env->GetStaticMethodID(
      byteBufferClass, "allocateDirect", "(I)Ljava/nio/ByteBuffer;");

  // Assert JNI References are valid.
  assert(inputBufferClass);
  assert(outputBufferClass);
  assert(decoderClass);
  assert(byteBufferClass);
  assert(context->input_buffer_class);
  assert(context->output_buffer_class);
  assert(context->input_data_field);
  assert(context->data_field);
  assert(context->display_width_field);
  assert(context->display_height_field);
  assert(context->ystride_field);
  assert(context->uvstride_field);
  assert(context->output_buffer_stride_array_field);
  assert(context->decoder_private_field);
  assert(context->init_output_buffer_method);
  assert(context->set_flags_method);
  assert(context->init_for_yuv_frame_method);
  assert(context->init_for_private_frame_method);
  assert(context->release_input_buffer_method);
  assert(context->init_for_offset_frames_method);
  assert(context->create_direct_byte_buffer_method);

  return reinterpret_cast<jlong>(context);
}

DECODER_FUNC(void, dav1dClose, jlong jContext) {
  if (jContext == kStatusError) {
    return;
  }
  JniContext* const context = reinterpret_cast<JniContext*>(jContext);

  // Close the dav1d decoder context. This should block until all internal
  // decoder threads are joined and resources are released, triggering
  // any pending free callbacks.
  if (context->decoder) {
    dav1d_close(&context->decoder);
  }

  // Clean up JNI global references
  if (context->input_buffer_class)
    env->DeleteGlobalRef(context->input_buffer_class);
  if (context->output_buffer_class)
    env->DeleteGlobalRef(context->output_buffer_class);
  if (context->decoder_class) env->DeleteGlobalRef(context->decoder_class);
  if (context->byte_buffer_class)
    env->DeleteGlobalRef(context->byte_buffer_class);

  // Clean up unused cookies
  {
    std::lock_guard<std::mutex> unused_cookies_lock(  // NOLINT(build/c++11)
        context->unused_cookies_mutex);
    while (!context->unused_cookies.empty()) {
      Cookie* cookie = context->unused_cookies.back().get();
      env->DeleteGlobalRef(cookie->global_ref_dav1d_data);
      env->DeleteGlobalRef(cookie->global_ref_input_buffer);
      context->unused_cookies.pop_back();
    }
  }
  {
    if (context->use_custom_allocator) {
      CleanUpAllocatorData(jContext, env);
    }
  }
  delete context;
}

DECODER_FUNC(jint, dav1dDecode, jlong jContext, jobject jInputBuffer,
             jint offset, jint length, jboolean decodeOnly, jint flags,
             jlong timeUs, jint outputMode) {
  if (jContext == kStatusError) {
    return kStatusError;
  }
  JniContext* const context = reinterpret_cast<JniContext*>(jContext);
  const jobject encoded_data =
      env->GetObjectField(jInputBuffer, context->input_data_field);
  void* const buffer_ptr = env->GetDirectBufferAddress(encoded_data);
  if (buffer_ptr == nullptr) {
    LOGE("Failed to get direct buffer address.");
    return kStatusError;
  }
  const uint8_t* const buf =
      reinterpret_cast<const uint8_t*>(buffer_ptr) + offset;

  Dav1dData data = {};

  Cookie* cookie = new Cookie();
  cookie->global_ref_input_buffer = env->NewGlobalRef(jInputBuffer);
  cookie->global_ref_dav1d_data = env->NewGlobalRef(encoded_data);
  cookie->jni_context = jContext;

  context->libdav1d_status_code = dav1d_data_wrap(
      &data, buf, length, Dav1dDataFreeCallback, static_cast<void*>(cookie));

  if (context->libdav1d_status_code != 0) {
    env->DeleteGlobalRef(cookie->global_ref_input_buffer);
    env->DeleteGlobalRef(cookie->global_ref_dav1d_data);
    delete cookie;
    return kStatusError;
  }

  UserDataCookie* user_data = new UserDataCookie();
  user_data->decode_only = decodeOnly;
  user_data->flags = flags;
  user_data->output_mode = outputMode;
  user_data->time_us = timeUs;
  user_data->jni_context = jContext;

  context->libdav1d_status_code = dav1d_data_wrap_user_data(
      &data, reinterpret_cast<const uint8_t*>(user_data),
      Dav1dUserDataFreeCallback, nullptr);
  if (context->libdav1d_status_code != 0) {
    LOGE("Failed to wrap user data.");
    delete user_data;
    dav1d_data_unref(&data);
    return kStatusError;
  }
  context->libdav1d_status_code = dav1d_send_data(context->decoder, &data);
  if (context->libdav1d_status_code != 0 &&
      context->libdav1d_status_code != DAV1D_ERR(EAGAIN)) {
    LOGE("Failed to send data.");
    dav1d_data_unref(&data);
    return kStatusError;
  }
  return kStatusOk;
}

DECODER_FUNC(jint, dav1dGetFrame, jlong jContext, jobject jOutputBuffer) {
  if (jContext == kStatusError) {
    return kStatusError;
  }
  JniContext* const context = reinterpret_cast<JniContext*>(jContext);

  auto cleanup_dav1d_picture = [](Dav1dPicture* picture) {
    dav1d_picture_unref(picture);
    delete picture;
  };
  std::unique_ptr<Dav1dPicture, decltype(cleanup_dav1d_picture)> dav1d_picture(
      new Dav1dPicture(), cleanup_dav1d_picture);
  context->libdav1d_status_code =
      dav1d_get_picture(context->decoder, dav1d_picture.get());
  if (context->libdav1d_status_code != 0 &&
      context->libdav1d_status_code != DAV1D_ERR(EAGAIN)) {
    LOGE("Failed to get picture. %d", context->libdav1d_status_code);
    return kStatusError;
  }
  if (context->libdav1d_status_code == DAV1D_ERR(EAGAIN)) {
    return kStatusEagain;
  }
  const UserDataCookie* returned_user_data =
      reinterpret_cast<const UserDataCookie*>(dav1d_picture->m.user_data.data);
  env->CallVoidMethod(jOutputBuffer, context->set_flags_method,
                      returned_user_data->flags);
  if (env->ExceptionCheck()) {
    context->jni_status_code = kJniStatusBufferInitError;
    return kStatusError;
  }

  if (returned_user_data->decode_only) {
    return kStatusDecodeOnly;
  }

  // Set up remaining output buffer information
  env->CallVoidMethod(jOutputBuffer, context->init_output_buffer_method,
                      returned_user_data->time_us,
                      returned_user_data->output_mode, nullptr);
  if (env->ExceptionCheck()) {
    context->jni_status_code = kJniStatusBufferInitError;
    return kStatusError;
  }
  if (dav1d_picture->p.bpc != 8) {
    context->jni_status_code = kJniStatusHighBitDepthNotSupportedWithYuv;
    return kStatusError;
  }
  if (returned_user_data->output_mode == kOutputModeYuv) {
    jboolean init_result;
    if (context->use_custom_allocator) {
      PictureAllocatorData* allocator_data =
          reinterpret_cast<PictureAllocatorData*>(
              dav1d_picture->allocator_data);
      env->SetObjectField(jOutputBuffer, context->data_field,
                          allocator_data->direct_byte_buffer);
      init_result = env->CallBooleanMethod(
          jOutputBuffer, context->init_for_offset_frames_method,
          allocator_data->offset, dav1d_picture->p.w, dav1d_picture->p.h,
          dav1d_picture->stride[kPlaneY], dav1d_picture->stride[kPlaneU],
          GetColorSpace(dav1d_picture->seq_hdr->pri),
          allocator_data->aligned_height);
      CleanUpAllocatorData(jContext, env);
    } else {
      init_result = env->CallBooleanMethod(
          jOutputBuffer, context->init_for_yuv_frame_method, dav1d_picture->p.w,
          dav1d_picture->p.h, dav1d_picture->stride[kPlaneY],
          dav1d_picture->stride[kPlaneU],
          GetColorSpace(dav1d_picture->seq_hdr->pri));
    }
    if (!init_result) {
      context->jni_status_code = kJniStatusBufferResizeError;
      return kStatusError;
    }
    if (env->ExceptionCheck()) {
      // Exception is thrown in Java when returning from the native call.
      context->jni_status_code = kJniStatusBufferResizeError;
      return kStatusError;
    }
    if (!context->use_custom_allocator) {
      const jobject data_object =
          env->GetObjectField(jOutputBuffer, context->data_field);
      jbyte* const data =
          reinterpret_cast<jbyte*>(env->GetDirectBufferAddress(data_object));
      CopyFrameToDataBuffer(dav1d_picture.get(), data);
    }
  } else if (returned_user_data->output_mode == kOutputModeSurfaceYuv) {
    // Dav1dPicture is cleaned up as part of dav1dReleaseFrame.
    Dav1dPicture* dav1d_picture_raw_ptr = dav1d_picture.release();
    env->SetLongField(jOutputBuffer, context->decoder_private_field,
                      (uint64_t)dav1d_picture_raw_ptr);
    env->CallVoidMethod(jOutputBuffer, context->init_for_private_frame_method,
                        dav1d_picture_raw_ptr->p.w, dav1d_picture_raw_ptr->p.h);
  }
  return kStatusOk;
}

DECODER_FUNC(jint, dav1dRenderFrame, jlong jContext, jobject jSurface,
             jobject jOutputBuffer) {
  if (jContext == kStatusError) {
    LOGE("Failed to render frame. jContext is error.");
    return kStatusError;
  }
  JniContext* const context = reinterpret_cast<JniContext*>(jContext);
  if (!context->MaybeAcquireNativeWindow(env, jSurface)) {
    LOGE("Failed to acquire native window.");
    return kStatusError;
  }

  int64_t width = env->GetIntField(jOutputBuffer, context->display_width_field);
  int64_t height =
      env->GetIntField(jOutputBuffer, context->display_height_field);
  if (context->native_window_width != width ||
      context->native_window_height != height) {
    if (ANativeWindow_setBuffersGeometry(context->native_window, width, height,
                                         kImageFormatYV12)) {
      context->jni_status_code = kJniStatusBufferResizeError;
      LOGE("Failed to set buffers geometry.");
      return kStatusError;
    }
    context->native_window_width = width;
    context->native_window_height = height;
  }

  ANativeWindow_Buffer native_window_buffer;
  if (ANativeWindow_lock(context->native_window, &native_window_buffer,
                         /*inOutDirtyBounds=*/nullptr) ||
      native_window_buffer.bits == nullptr) {
    context->jni_status_code = kJniStatusANativeWindowError;
    LOGE("Failed to lock native window.");
    return kStatusError;
  }

  Dav1dPicture* dav1d_picture = reinterpret_cast<Dav1dPicture*>(
      env->GetLongField(jOutputBuffer, context->decoder_private_field));
  if (dav1d_picture == nullptr) {
    LOGE("Failed to get dav1d picture.");
    return kStatusError;
  }
  // Y plane
  CopyPlane(reinterpret_cast<const uint8_t*>(dav1d_picture->data[kPlaneY]),
            dav1d_picture->stride[kPlaneY],
            reinterpret_cast<uint8_t*>(native_window_buffer.bits),
            native_window_buffer.stride, width, height);

  const int y_plane_size =
      native_window_buffer.stride * native_window_buffer.height;
  const int32_t native_window_buffer_uv_height =
      (native_window_buffer.height + 1) / 2;
  const int native_window_buffer_uv_stride =
      AlignTo16(native_window_buffer.stride / 2);

  const int uv_plane_height = native_window_buffer_uv_height;

  // TODO(b/140606738): Handle monochrome videos.
  // V plane
  // Since the format for ANativeWindow is YV12, V plane is being processed
  // before U plane.
  CopyPlane(
      reinterpret_cast<const uint8_t*>(dav1d_picture->data[kPlaneV]),
      dav1d_picture->stride[kPlaneU],
      reinterpret_cast<uint8_t*>(native_window_buffer.bits) + y_plane_size,
      native_window_buffer_uv_stride, width / 2, uv_plane_height);

  const int v_plane_size = uv_plane_height * native_window_buffer_uv_stride;

  // U plane
  CopyPlane(reinterpret_cast<const uint8_t*>(dav1d_picture->data[kPlaneU]),
            dav1d_picture->stride[kPlaneU],
            reinterpret_cast<uint8_t*>(native_window_buffer.bits) +
                y_plane_size + v_plane_size,
            native_window_buffer_uv_stride, width / 2, uv_plane_height);

  if (ANativeWindow_unlockAndPost(context->native_window)) {
    context->jni_status_code = kJniStatusANativeWindowError;
    LOGE("Failed to unlock and post native window.");
    return kStatusError;
  }
  return kStatusOk;
}

DECODER_FUNC(void, dav1dReleaseFrame, jlong jContext, jobject jOutputBuffer) {
  if (jContext == kStatusError) {
    return;
  }
  JniContext* const context = reinterpret_cast<JniContext*>(jContext);
  Dav1dPicture* dav1d_picture = (Dav1dPicture*)env->GetLongField(
      jOutputBuffer, context->decoder_private_field);
  env->SetLongField(jOutputBuffer, context->decoder_private_field, 0);
  if (dav1d_picture != nullptr) {
    dav1d_picture_unref(dav1d_picture);
    delete dav1d_picture;
  }
}

DECODER_FUNC(jstring, dav1dGetErrorMessage, jlong jContext) {
  if (jContext == kStatusError) {
    return env->NewStringUTF("Failed to initialize JNI context.");
  }

  JniContext* const context = reinterpret_cast<JniContext*>(jContext);
  if (context->libdav1d_status_code != kLibdav1dDecoderStatusOk) {
    char error_message[100];
    snprintf(error_message, sizeof(error_message),
             "There is a decoder error. %d", context->libdav1d_status_code);
    return env->NewStringUTF(error_message);
  }
  if (context->jni_status_code != kJniStatusOk) {
    return env->NewStringUTF(GetJniErrorMessage(context->jni_status_code));
  }
  return env->NewStringUTF("None.");
}

DECODER_FUNC(jint, dav1dCheckError, jlong jContext) {
  if (jContext == kStatusError) {
    return kStatusError;
  }
  JniContext* const context = reinterpret_cast<JniContext*>(jContext);
  return (context->libdav1d_status_code != kLibdav1dDecoderStatusOk ||
          context->jni_status_code != kJniStatusOk)
             ? kStatusError
             : kStatusOk;
}

DECODER_FUNC(void, dav1dFlush, jlong jContext) {
  if (jContext == kStatusError) {
    return;
  }
  JniContext* const context = reinterpret_cast<JniContext*>(jContext);
  dav1d_flush(context->decoder);
}

DECODER_FUNC(void, releaseUnusedInputBuffers, jlong jContext, jobject decoder) {
  if (jContext == kStatusError) {
    return;
  }
  JniContext* const context = reinterpret_cast<JniContext*>(jContext);
  {
    std::lock_guard<std::mutex> unused_cookies_lock(  // NOLINT(build/c++11)
        context->unused_cookies_mutex);
    while (!context->unused_cookies.empty()) {
      Cookie* cookie = context->unused_cookies.back().get();
      env->CallVoidMethod(decoder, context->release_input_buffer_method,
                          cookie->global_ref_input_buffer);
      if (env->ExceptionCheck()) {
        LOGE("Failed to release input buffer.");
        env->ExceptionClear();
        break;
      }
      env->DeleteGlobalRef(cookie->global_ref_dav1d_data);
      env->DeleteGlobalRef(cookie->global_ref_input_buffer);
      context->unused_cookies.pop_back();
    }
  }
}
