#include <jni.h>
#ifdef __ANDROID__
#include <android/bitmap.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>

#include <cstddef>
#include <cstdint>
#include <cstring>
#endif  //  __ANDROID__
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#ifdef __ANDROID__
#define API_AT_LEAST(x) __builtin_available(android x, *)
#define LOG_TAG "hardwarebufferJNI"
#define LOGE(...) \
  ((void)__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__))
#else  //  __ANDROID__
#define LOGE(...) \
  do {            \
  } while (0)
#endif  //  __ANDROID__

static PFNEGLCREATEIMAGEKHRPROC eglCreateImageKHR = nullptr;
static PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC eglGetNativeClientBufferANDROID =
    nullptr;
static PFNGLEGLIMAGETARGETTEXTURE2DOESPROC glEGLImageTargetTexture2DOES =
    nullptr;
static PFNEGLDESTROYIMAGEKHRPROC eglDestroyImageKHR = nullptr;

static AHardwareBuffer* (*s_AHardwareBuffer_fromHardwareBuffer)(
    JNIEnv*, jobject) = nullptr;
static void (*s_AHardwareBuffer_describe)(const AHardwareBuffer*,
                                          AHardwareBuffer_Desc*) = nullptr;
static int (*s_AHardwareBuffer_lock)(AHardwareBuffer*, uint64_t, int32_t,
                                     const ARect*, void**) = nullptr;
static int (*s_AHardwareBuffer_unlock)(AHardwareBuffer*, int32_t*) = nullptr;

static void initializeEGLFunctions() {
  if (!eglCreateImageKHR) {
    eglCreateImageKHR =
        (PFNEGLCREATEIMAGEKHRPROC)eglGetProcAddress("eglCreateImageKHR");
  }
  if (!eglGetNativeClientBufferANDROID) {
    eglGetNativeClientBufferANDROID =
        (PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC)eglGetProcAddress(
            "eglGetNativeClientBufferANDROID");
  }
  if (!glEGLImageTargetTexture2DOES) {
    glEGLImageTargetTexture2DOES =
        (PFNGLEGLIMAGETARGETTEXTURE2DOESPROC)eglGetProcAddress(
            "glEGLImageTargetTexture2DOES");
  }
  if (!eglDestroyImageKHR) {
    eglDestroyImageKHR =
        (PFNEGLDESTROYIMAGEKHRPROC)eglGetProcAddress("eglDestroyImageKHR");
  }
}

jlong nativeCreateEglImageFromHardwareBuffer(JNIEnv* env, jobject /* this */,
                                             jlong displayHandle,
                                             jobject hardwareBufferJava) {
  EGLDisplay display = reinterpret_cast<EGLDisplay>(displayHandle);
  if (display == EGL_NO_DISPLAY) {
    LOGE("Invalid EGL display");
    return 0;  // EGL_NO_IMAGE_KHR
  }

  AHardwareBuffer* hardwareBuffer =
      s_AHardwareBuffer_fromHardwareBuffer(env, hardwareBufferJava);
  if (!hardwareBuffer) {
    LOGE("Null hardware buffer");
    return 0;  // EGL_NO_IMAGE_KHR
  }

  EGLClientBuffer clientBuffer =
      eglGetNativeClientBufferANDROID(hardwareBuffer);
  const EGLint attrs[] = {EGL_NONE};
  EGLImageKHR eglImage = eglCreateImageKHR(
      display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, clientBuffer, attrs);

  if (eglImage == EGL_NO_IMAGE_KHR) {
    LOGE("eglCreateImageKHR failed with error 0x%x", eglGetError());
    return 0;  // EGL_NO_IMAGE_KHR
  }

  return (jlong)eglImage;
}

jboolean nativeBindEGLImage(JNIEnv* /* env */, jobject /* this */, jint target,
                            jlong eglImageHandle) {
  if (eglImageHandle == 0) {
    LOGE("Invalid eglImageHandle (0)");
    return JNI_FALSE;
  }

  EGLImageKHR image = reinterpret_cast<EGLImageKHR>(eglImageHandle);

  glEGLImageTargetTexture2DOES(static_cast<GLenum>(target),
                               (GLeglImageOES)image);

  GLenum error = glGetError();
  if (error != GL_NO_ERROR) {
    LOGE("glEGLImageTargetTexture2DOES failed: 0x%x", error);
    return JNI_FALSE;
  }
  return JNI_TRUE;
}

jboolean nativeDestroyEGLImage(JNIEnv* /* env */, jobject /* this */,
                               jlong displayHandle, jlong eglImageHandle) {
  if (eglImageHandle == 0) {
    LOGE("Invalid eglImageHandle (0)");
    return JNI_FALSE;
  }

  EGLDisplay display = reinterpret_cast<EGLDisplay>(displayHandle);
  if (display == EGL_NO_DISPLAY) {
    return JNI_FALSE;
  }

  EGLImageKHR image = reinterpret_cast<EGLImageKHR>(eglImageHandle);
  EGLBoolean result = eglDestroyImageKHR(display, image);

  if (result == EGL_TRUE) {
    return JNI_TRUE;
  } else {
    LOGE("eglDestroyImageKHR failed: EGL error 0x%x", eglGetError());
    return JNI_FALSE;
  }
}

jboolean nativeCopyBitmapToHardwareBuffer(JNIEnv* env, jobject /* this */,
                                          jobject bitmap,
                                          jobject hardwareBufferJava) {
  AHardwareBuffer* hb;
  if (API_AT_LEAST(26)) {
    hb = s_AHardwareBuffer_fromHardwareBuffer(env, hardwareBufferJava);
  } else {
    LOGE(
        "AHardwareBuffer APIs are not available on this Android version (API < "
        "26)");
    return JNI_FALSE;
  }
  if (!hb) {
    LOGE("Failed to get AHardwareBuffer from jobject");
    return JNI_FALSE;
  }

  AndroidBitmapInfo bitmapInfo;
  if (AndroidBitmap_getInfo(env, bitmap, &bitmapInfo) < 0) {
    LOGE("AndroidBitmap_getInfo failed");
    return JNI_FALSE;
  }

  if (bitmapInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 &&
      bitmapInfo.format != ANDROID_BITMAP_FORMAT_RGBA_1010102 &&
      bitmapInfo.format != ANDROID_BITMAP_FORMAT_RGBA_F16) {
    LOGE(
        "Unsupported bitmap format: %d. Only RGBA_8888, RGBA_1010102, and "
        "RGBA_F16 are supported.",
        bitmapInfo.format);
    return JNI_FALSE;
  }

  AHardwareBuffer_Desc hbDesc;
  s_AHardwareBuffer_describe(hb, &hbDesc);
  if (hbDesc.width != bitmapInfo.width || hbDesc.height != bitmapInfo.height) {
    LOGE("HardwareBuffer dimensions do not match bitmap dimensions.");
    return JNI_FALSE;
  }

  uint32_t expectedHbFormat;
  int bpp;
  if (bitmapInfo.format == ANDROID_BITMAP_FORMAT_RGBA_1010102) {
    expectedHbFormat = AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM;
    bpp = 4;
  } else if (bitmapInfo.format == ANDROID_BITMAP_FORMAT_RGBA_F16) {
    expectedHbFormat = AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT;
    bpp = 8;
  } else {
    expectedHbFormat = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    bpp = 4;
  }

  if (hbDesc.format != expectedHbFormat) {
    LOGE(
        "Unsupported hardware buffer format. Expected format matching the "
        "bitmap.");
    return JNI_FALSE;
  }

  if (!(hbDesc.usage & AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN)) {
    LOGE(
        "Unsupported hardware buffer usage. CPU_WRITE_OFTEN must be "
        "supported.");
    return JNI_FALSE;
  }

  void* bitmapPixels;
  if (AndroidBitmap_lockPixels(env, bitmap, &bitmapPixels) < 0) {
    LOGE("AndroidBitmap_lockPixels failed");
    return JNI_FALSE;
  }

  void* hbPixels;
  jboolean success = JNI_FALSE;
  if (s_AHardwareBuffer_lock(hb, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1,
                             nullptr, &hbPixels) == 0) {
    uint8_t* src = static_cast<uint8_t*>(bitmapPixels);
    uint8_t* dst = static_cast<uint8_t*>(hbPixels);

    const size_t rowSize = bitmapInfo.width * bpp;

    for (uint32_t y = 0; y < bitmapInfo.height; ++y) {
      memcpy(dst + (y * hbDesc.stride * bpp), src + (y * bitmapInfo.stride),
             rowSize);
    }

    s_AHardwareBuffer_unlock(hb, nullptr);
    success = JNI_TRUE;
  } else {
    LOGE("AHardwareBuffer_lock failed");
  }

  AndroidBitmap_unlockPixels(env, bitmap);
  return success;
}

jboolean nativeCopyHardwareBufferToHardwareBuffer(JNIEnv* env,
                                                  jobject /* this */,
                                                  jobject srcHbJava,
                                                  jobject dstHbJava) {
  if (env->IsSameObject(srcHbJava, dstHbJava)) {
    return JNI_TRUE;
  }

  AHardwareBuffer* srcHb;
  AHardwareBuffer* dstHb;
  if (API_AT_LEAST(26)) {
    srcHb = s_AHardwareBuffer_fromHardwareBuffer(env, srcHbJava);
    dstHb = s_AHardwareBuffer_fromHardwareBuffer(env, dstHbJava);
  } else {
    LOGE(
        "AHardwareBuffer APIs are not available on this Android version (API < "
        "26)");
    return JNI_FALSE;
  }

  if (!srcHb || !dstHb) {
    LOGE("Failed to get AHardwareBuffer from jobject");
    return JNI_FALSE;
  }

  AHardwareBuffer_Desc srcDesc;
  s_AHardwareBuffer_describe(srcHb, &srcDesc);
  AHardwareBuffer_Desc dstDesc;
  s_AHardwareBuffer_describe(dstHb, &dstDesc);

  if (srcDesc.width != dstDesc.width || srcDesc.height != dstDesc.height) {
    LOGE("HardwareBuffer dimensions do not match: src(%u, %u), dst(%u, %u)",
         srcDesc.width, srcDesc.height, dstDesc.width, dstDesc.height);
    return JNI_FALSE;
  }

  if (srcDesc.format != dstDesc.format) {
    LOGE("HardwareBuffer formats do not match: src(%u), dst(%u)",
         srcDesc.format, dstDesc.format);
    return JNI_FALSE;
  }

  uint32_t bpp;
  if (srcDesc.format == AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM ||
      srcDesc.format == AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM) {
    bpp = 4;
  } else if (srcDesc.format == AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT) {
    bpp = 8;
  } else {
    LOGE(
        "Unsupported hardware buffer format: %u. Only RGBA_8888, "
        "RGBA_1010102, and RGBA_FP16 are supported.",
        srcDesc.format);
    return JNI_FALSE;
  }

  // Do not check the HardwareBuffer usage before locking, as
  // AHardwareBuffer_Desc can return incorrect usage flags.
  void* srcPixels = nullptr;
  if (s_AHardwareBuffer_lock(srcHb, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1,
                             nullptr, &srcPixels) != 0) {
    LOGE("Failed to lock source HardwareBuffer");
    return JNI_FALSE;
  }

  void* dstPixels = nullptr;
  if (s_AHardwareBuffer_lock(dstHb, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1,
                             nullptr, &dstPixels) != 0) {
    LOGE("Failed to lock destination HardwareBuffer");
    s_AHardwareBuffer_unlock(srcHb, nullptr);
    return JNI_FALSE;
  }

  const uint8_t* srcBase = static_cast<const uint8_t*>(srcPixels);
  uint8_t* dstBase = static_cast<uint8_t*>(dstPixels);
  const size_t rowSize = srcDesc.width * bpp;

  for (uint32_t y = 0; y < srcDesc.height; ++y) {
    memcpy(dstBase + (y * dstDesc.stride * bpp),
           srcBase + (y * srcDesc.stride * bpp), rowSize);
  }

  s_AHardwareBuffer_unlock(srcHb, nullptr);
  s_AHardwareBuffer_unlock(dstHb, nullptr);

  return JNI_TRUE;
}

jboolean nativeIsNv21(JNIEnv* env, jobject /* this */, jobject uBuffer,
                      jobject vBuffer) {
  const auto* uPtr =
      static_cast<const uint8_t*>(env->GetDirectBufferAddress(uBuffer));
  const auto* vPtr =
      static_cast<const uint8_t*>(env->GetDirectBufferAddress(vBuffer));
  return vPtr < uPtr;
}

namespace {

enum class ChromaLayout {
  kPlanar,
  kNv12,
  kNv21,
};

bool hasSufficientPlaneCapacity(JNIEnv* env, jobject buffer, jint offset,
                                jint rowStride, size_t width, size_t height,
                                size_t pixelStride) {
  const jlong capacity = env->GetDirectBufferCapacity(buffer);
  if (capacity < 0 || offset < 0 || rowStride <= 0) {
    return false;
  }
  const uint64_t minRowBytes = static_cast<uint64_t>(width) * pixelStride;
  if (static_cast<uint64_t>(rowStride) < minRowBytes) {
    return false;
  }
  const uint64_t minFinalRowBytes =
      static_cast<uint64_t>(width - 1) * pixelStride + 1;
  const uint64_t requiredBytes =
      static_cast<uint64_t>(offset) +
      static_cast<uint64_t>(height - 1) * static_cast<uint64_t>(rowStride) +
      minFinalRowBytes;
  return requiredBytes <= static_cast<uint64_t>(capacity);
}

void convertPlanarChromaRow(const uint8_t* srcRow0, const uint8_t* srcRow1,
                            size_t chromaWidth, uint8_t* dstURow,
                            uint8_t* dstVRow) {
  for (size_t x = 0; x < chromaWidth; ++x) {
    dstURow[x] = (srcRow0[4 * x + 1] + srcRow1[4 * x + 1] + 1) >> 1;
    dstVRow[x] = (srcRow0[4 * x + 3] + srcRow1[4 * x + 3] + 1) >> 1;
  }
}

void convertNv12ChromaRow(const uint8_t* srcRow0, const uint8_t* srcRow1,
                          size_t chromaWidth, uint8_t* dstUvRow) {
  for (size_t x = 0; x < chromaWidth; ++x) {
    dstUvRow[2 * x] = (srcRow0[4 * x + 1] + srcRow1[4 * x + 1] + 1) >> 1;
    dstUvRow[2 * x + 1] = (srcRow0[4 * x + 3] + srcRow1[4 * x + 3] + 1) >> 1;
  }
}

void convertNv21ChromaRow(const uint8_t* srcRow0, const uint8_t* srcRow1,
                          size_t chromaWidth, uint8_t* dstVuRow) {
  for (size_t x = 0; x < chromaWidth; ++x) {
    dstVuRow[2 * x] = (srcRow0[4 * x + 3] + srcRow1[4 * x + 3] + 1) >> 1;
    dstVuRow[2 * x + 1] = (srcRow0[4 * x + 1] + srcRow1[4 * x + 1] + 1) >> 1;
  }
}

}  // namespace

jboolean nativeConvertYuy2ToYuv420Planes(
    JNIEnv* env, jobject /* this */, jobject packedYuy2Buffer,
    jint packedOffset, jint width, jint height, jobject yBuffer, jint yOffset,
    jint yRowStride, jobject uBuffer, jint uOffset, jint uRowStride,
    jobject vBuffer, jint vOffset, jint vRowStride, jint chromaPixelStride) {
  const auto* packedPtr = static_cast<const uint8_t*>(
      env->GetDirectBufferAddress(packedYuy2Buffer));
  auto* yPtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(yBuffer));
  auto* uPtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(uBuffer));
  auto* vPtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(vBuffer));
  if (!packedPtr || !yPtr || !uPtr || !vPtr) {
    LOGE("GetDirectBufferAddress returned null for YUY2 conversion.");
    return JNI_FALSE;
  }
  if (chromaPixelStride != 1 && chromaPixelStride != 2) {
    LOGE("chromaPixelStride must be 1 or 2, got: %d", chromaPixelStride);
    return JNI_FALSE;
  }
  if (chromaPixelStride == 2 && uRowStride != vRowStride) {
    LOGE("Semi-planar U and V row strides must match.");
    return JNI_FALSE;
  }
  if (width <= 0 || height <= 0) {
    LOGE("Invalid dimensions: %dx%d", width, height);
    return JNI_FALSE;
  }

  const size_t imageWidth = static_cast<size_t>(width);
  const size_t imageHeight = static_cast<size_t>(height);
  const size_t chromaWidth = (imageWidth + 1) / 2;
  const size_t chromaHeight = (imageHeight + 1) / 2;
  const uint64_t packedRowBytes = static_cast<uint64_t>(chromaWidth) * 4;

  const jlong packedCapacity = env->GetDirectBufferCapacity(packedYuy2Buffer);
  const uint64_t requiredPackedBytes =
      static_cast<uint64_t>(packedOffset) + imageHeight * packedRowBytes;
  const bool hasPackedCapacity =
      packedCapacity >= 0 && packedOffset >= 0 &&
      requiredPackedBytes <= static_cast<uint64_t>(packedCapacity);
  const bool hasYCapacity =
      hasSufficientPlaneCapacity(env, yBuffer, yOffset, yRowStride, imageWidth,
                                 imageHeight, /*pixelStride=*/1);
  const bool hasUCapacity = hasSufficientPlaneCapacity(
      env, uBuffer, uOffset, uRowStride, chromaWidth, chromaHeight,
      static_cast<size_t>(chromaPixelStride));
  const bool hasVCapacity = hasSufficientPlaneCapacity(
      env, vBuffer, vOffset, vRowStride, chromaWidth, chromaHeight,
      static_cast<size_t>(chromaPixelStride));
  if (!hasPackedCapacity || !hasYCapacity || !hasUCapacity || !hasVCapacity) {
    LOGE("Insufficient direct ByteBuffer capacity for YUY2 conversion.");
    return JNI_FALSE;
  }

  const uint8_t* packedBase = packedPtr + packedOffset;
  uint8_t* yBase = yPtr + yOffset;
  uint8_t* uBase = uPtr + uOffset;
  uint8_t* vBase = vPtr + vOffset;

  ChromaLayout chromaLayout = ChromaLayout::kPlanar;
  if (chromaPixelStride == 2) {
    const uintptr_t uAddr = reinterpret_cast<uintptr_t>(uBase);
    const uintptr_t vAddr = reinterpret_cast<uintptr_t>(vBase);
    if (uAddr + 1 != vAddr && vAddr + 1 != uAddr) {
      LOGE("Semi-planar U and V buffers must be interleaved 1 byte apart.");
      return JNI_FALSE;
    }
    chromaLayout = (vAddr < uAddr) ? ChromaLayout::kNv21 : ChromaLayout::kNv12;
  }

  // Each 4-byte YUY2 macropixel is [Y0, U, Y1, V]: luma samples are at even
  // byte offsets (2 * x), U is at 4 * x + 1, and V is at 4 * x + 3.
  for (size_t y = 0; y < imageHeight; ++y) {
    const uint8_t* srcRow = packedBase + y * packedRowBytes;
    uint8_t* dstYRow = yBase + y * static_cast<size_t>(yRowStride);
    for (size_t x = 0; x < imageWidth; ++x) {
      dstYRow[x] = srcRow[2 * x];
    }

    if (y % 2 == 0) {
      const size_t chromaRow = y / 2;
      // If height is odd, the final row has no odd partner; passing srcRow
      // twice averages the row with itself ((v + v + 1) >> 1 == v).
      const uint8_t* srcRowNext =
          (y + 1 < imageHeight) ? (srcRow + packedRowBytes) : srcRow;
      // Separate helpers let the compiler auto-vectorize each loop, since
      // NV12/NV21 write via one pointer instead of overlapping uBase/vBase.
      switch (chromaLayout) {
        case ChromaLayout::kPlanar:
          convertPlanarChromaRow(
              srcRow, srcRowNext, chromaWidth,
              uBase + chromaRow * static_cast<size_t>(uRowStride),
              vBase + chromaRow * static_cast<size_t>(vRowStride));
          break;
        case ChromaLayout::kNv12:
          convertNv12ChromaRow(
              srcRow, srcRowNext, chromaWidth,
              uBase + chromaRow * static_cast<size_t>(uRowStride));
          break;
        case ChromaLayout::kNv21:
          convertNv21ChromaRow(
              srcRow, srcRowNext, chromaWidth,
              vBase + chromaRow * static_cast<size_t>(vRowStride));
          break;
      }
    }
  }

  return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
  JNIEnv* env;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
    return -1;
  }

  if (API_AT_LEAST(26)) {
    s_AHardwareBuffer_fromHardwareBuffer = AHardwareBuffer_fromHardwareBuffer;
    s_AHardwareBuffer_describe = AHardwareBuffer_describe;
    s_AHardwareBuffer_lock = AHardwareBuffer_lock;
    s_AHardwareBuffer_unlock = AHardwareBuffer_unlock;

    initializeEGLFunctions();
    if (eglCreateImageKHR == nullptr ||
        eglGetNativeClientBufferANDROID == nullptr ||
        glEGLImageTargetTexture2DOES == nullptr ||
        eglDestroyImageKHR == nullptr) {
      LOGE("Failed to get addresses of GL/EGL functions.");
      return -1;
    }
  }

  jclass clazz = env->FindClass("androidx/media3/effect/HardwareBufferJni");
  if (!clazz) {
    LOGE("JNI_OnLoad: FindClass failed for HardwareBufferJni");
    return -1;
  }
  static const JNINativeMethod kMethods[] = {
      {"nativeCreateEglImageFromHardwareBuffer",
       "(JLandroid/hardware/HardwareBuffer;)J",
       reinterpret_cast<void*>(nativeCreateEglImageFromHardwareBuffer)},
      {"nativeBindEGLImage", "(IJ)Z",
       reinterpret_cast<void*>(nativeBindEGLImage)},
      {"nativeDestroyEGLImage", "(JJ)Z",
       reinterpret_cast<void*>(nativeDestroyEGLImage)},
      {"nativeCopyBitmapToHardwareBuffer",
       "(Landroid/graphics/Bitmap;Landroid/hardware/HardwareBuffer;)Z",
       reinterpret_cast<void*>(nativeCopyBitmapToHardwareBuffer)},
      {"nativeCopyHardwareBufferToHardwareBuffer",
       "(Landroid/hardware/HardwareBuffer;Landroid/hardware/HardwareBuffer;)Z",
       reinterpret_cast<void*>(nativeCopyHardwareBufferToHardwareBuffer)},
      {"nativeIsNv21", "(Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;)Z",
       reinterpret_cast<void*>(nativeIsNv21)},
      {"nativeConvertYuy2ToYuv420Planes",
       "(Ljava/nio/ByteBuffer;IIILjava/nio/ByteBuffer;IILjava/nio/ByteBuffer;"
       "IILjava/nio/ByteBuffer;III)Z",
       reinterpret_cast<void*>(nativeConvertYuy2ToYuv420Planes)},
  };
  if (env->RegisterNatives(clazz, kMethods,
                           sizeof(kMethods) / sizeof(kMethods[0])) < 0) {
    LOGE("JNI_OnLoad: RegisterNatives failed for HardwareBufferJni");
    return -1;
  }

  return JNI_VERSION_1_6;
}
