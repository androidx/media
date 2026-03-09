#include <jni.h>
#ifdef __ANDROID__
#include <android/bitmap.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>

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

jint JNI_OnLoad(JavaVM* vm, void* reserved) {
  JNIEnv* env;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
    return -1;
  }

  if (API_AT_LEAST(26)) {
    s_AHardwareBuffer_fromHardwareBuffer = AHardwareBuffer_fromHardwareBuffer;
    s_AHardwareBuffer_describe = AHardwareBuffer_describe;
    s_AHardwareBuffer_lock = AHardwareBuffer_lock;
    s_AHardwareBuffer_unlock = AHardwareBuffer_unlock;
  } else {
    LOGE(
        "AHardwareBuffer APIs are not available on this Android version (API < "
        "26)");
    return -1;
  }

  initializeEGLFunctions();
  if (eglCreateImageKHR == nullptr ||
      eglGetNativeClientBufferANDROID == nullptr ||
      glEGLImageTargetTexture2DOES == nullptr ||
      eglDestroyImageKHR == nullptr) {
    LOGE("Failed to get addresses of GL/EGL functions.");
    return -1;
  }
  return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jlong JNICALL
Java_androidx_media3_effect_ndk_HardwareBufferJni_nativeCreateEglImageFromHardwareBuffer(
    JNIEnv* env, jobject /* this */, jlong displayHandle,
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

extern "C" JNIEXPORT jboolean JNICALL
Java_androidx_media3_effect_ndk_HardwareBufferJni_nativeBindEGLImage(
    JNIEnv* env, jobject clazz, jint target, jlong eglImageHandle) {
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

extern "C" JNIEXPORT jboolean JNICALL
Java_androidx_media3_effect_ndk_HardwareBufferJni_nativeDestroyEGLImage(
    JNIEnv* env, jobject clazz, jlong displayHandle, jlong imageHandle) {
  if (imageHandle == 0) {
    LOGE("Invalid eglImageHandle (0)");
    return JNI_FALSE;
  }

  EGLDisplay display = reinterpret_cast<EGLDisplay>(displayHandle);
  if (display == EGL_NO_DISPLAY) {
    return JNI_FALSE;
  }

  EGLImageKHR image = reinterpret_cast<EGLImageKHR>(imageHandle);
  EGLBoolean result = eglDestroyImageKHR(display, image);

  if (result == EGL_TRUE) {
    return JNI_TRUE;
  } else {
    LOGE("eglDestroyImageKHR failed: EGL error 0x%x", eglGetError());
    return JNI_FALSE;
  }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_androidx_media3_effect_ndk_HardwareBufferJni_nativeCopyBitmapToHardwareBuffer(
    JNIEnv* env, jobject clazz, jobject bitmap, jobject hardwareBuffer) {
  AHardwareBuffer* hb;
  if (API_AT_LEAST(26)) {
    hb = s_AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
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
      bitmapInfo.format != ANDROID_BITMAP_FORMAT_RGBA_1010102) {
    LOGE(
        "Unsupported bitmap format: %d. Only RGBA_8888 and RGBA_1010102 are "
        "supported.",
        bitmapInfo.format);
    return JNI_FALSE;
  }

  AHardwareBuffer_Desc hbDesc;
  s_AHardwareBuffer_describe(hb, &hbDesc);
  if (hbDesc.width != bitmapInfo.width || hbDesc.height != bitmapInfo.height) {
    LOGE("HardwareBuffer dimensions do not match bitmap dimensions.");
    return JNI_FALSE;
  }

  uint32_t expectedHbFormat =
      (bitmapInfo.format == ANDROID_BITMAP_FORMAT_RGBA_1010102)
          ? AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM
          : AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;

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

    // Both RGBA_8888 and RGBA_1010102 formats are 32-bit (4 bytes per pixel).
    const int bpp = 4;
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

extern "C" JNIEXPORT jboolean JNICALL
Java_androidx_media3_effect_ndk_HardwareBufferJni_nativeCopyHardwareBufferToHardwareBuffer(
    JNIEnv* env, jobject clazz, jobject srcHbJava, jobject dstHbJava) {
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

  if (!(srcDesc.usage & AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN)) {
    LOGE("Source HardwareBuffer must have CPU_READ_OFTEN usage");
    return JNI_FALSE;
  }
  if (!(dstDesc.usage & AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN)) {
    LOGE("Destination HardwareBuffer must have CPU_WRITE_OFTEN usage");
    return JNI_FALSE;
  }

  uint32_t bpp;
  if (srcDesc.format == AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM ||
      srcDesc.format == AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM) {
    bpp = 4;
  } else {
    LOGE(
        "Unsupported hardware buffer format: %u. Only RGBA_8888 and "
        "RGBA_1010102 are supported.",
        srcDesc.format);
    return JNI_FALSE;
  }

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
