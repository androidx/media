#include <jni.h>
#ifdef __ANDROID__
#include <android/hardware_buffer_jni.h>
#include <android/log.h>
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

static void initializeEGLFunctions() {
  if (!eglCreateImageKHR) {
    eglCreateImageKHR =
        (PFNEGLCREATEIMAGEKHRPROC)eglGetProcAddress("eglCreateImageKHR");
  }
  if (!eglGetNativeClientBufferANDROID) {
    eglGetNativeClientBufferANDROID = PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC(
        eglGetProcAddress("eglGetNativeClientBufferANDROID"));
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
  } else {
    LOGE(
        "AHardwareBuffer_fromHardwareBuffer is not available on the Android "
        "version");
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
