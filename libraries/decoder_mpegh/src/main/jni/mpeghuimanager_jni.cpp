#include <android/log.h>
#include <jni.h>

#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "mpeghUIManager.h"

#define LOG_TAG "mpeghuimanager_jni"
#define LOGE(...) ((void)__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__))
#define LOGW(...) ((void)__android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__))
#define LOGI(...) ((void)__android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__))
#define LOGD(...) ((void)__android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__))

#define UIMANAGER_FUNC(RETURN_TYPE, NAME, ...)                                         \
  extern "C" {                                                                       \
  JNIEXPORT RETURN_TYPE Java_androidx_media3_decoder_mpegh_MpeghUiManagerJni_##NAME( \
      JNIEnv *env, jobject obj, ##__VA_ARGS__);                                      \
  }                                                                                  \
  JNIEXPORT RETURN_TYPE Java_androidx_media3_decoder_mpegh_MpeghUiManagerJni_##NAME( \
      JNIEnv *env, jobject obj, ##__VA_ARGS__)

#define EXCEPTION_PATH "androidx/media3/decoder/mpegh/MpeghDecoderException"

#define XML_BUFFER_SIZE 10240

typedef struct UIMANAGER_CONTEXT {
  HANDLE_MPEGH_UI_MANAGER handle;

  bool newSceneStateAvailable;
  std::string xmlSceneStateBuf;
} UIMANAGER_CONTEXT;

jfieldID getHandleFieldID_UI(JNIEnv *env, jobject obj) {
  jclass cls = env->GetObjectClass(obj);
  return env->GetFieldID(cls, "uiManagerHandle", "J");
}

void setContext_UI(JNIEnv *env, jobject obj, UIMANAGER_CONTEXT *ctx) {
  jfieldID decoderHandle_fid = getHandleFieldID_UI(env, obj);
  env->SetLongField(obj, decoderHandle_fid, (jlong)ctx);
}

UIMANAGER_CONTEXT *getContext_UI(JNIEnv *env, jobject obj) {
  jfieldID decoderHandle_fid = getHandleFieldID_UI(env, obj);
  return (UIMANAGER_CONTEXT *)env->GetLongField(obj, decoderHandle_fid);
}

/*
 * Method:    init
 * will be used to initialize the JNI MPEG-H UI manager wrapper
 */
UIMANAGER_FUNC(void, init, jobject persistenceBuffer, jint persistenceBufferLength) {
  // create JNI decoder wrapper context
  auto *ctx = (UIMANAGER_CONTEXT *) calloc(1, sizeof(UIMANAGER_CONTEXT));
  if (ctx == nullptr) {
    LOGE("Unable to allocate memory for UIMANAGER_CONTEXT!");
    jclass atscExCls = env->FindClass(EXCEPTION_PATH);
    env->ThrowNew(atscExCls, "cannot create UIMANAGER_CONTEXT");
    return;
  }

  // create MPEG-H UI manager
  ctx->handle = mpegh_UI_Manager_Open();
  if (ctx->handle == nullptr) {
    free(ctx);
    LOGE("Cannot create mpeghuimanager!");
    jclass atscExCls = env->FindClass(EXCEPTION_PATH);
    env->ThrowNew(atscExCls, "Cannot create mpeghuimanager");
    return;
  }

  if (persistenceBuffer != nullptr) {
    auto *inData = (void *) env->GetDirectBufferAddress(persistenceBuffer);
    auto inDataLength = (uint16_t) persistenceBufferLength;
    MPEGH_UI_ERROR err = mpegh_UI_SetPersistenceMemory(ctx->handle, inData, inDataLength);
    if (err != MPEGH_UI_OK) {
      LOGW("Unable to set persistence memory!");
    }
  }

  ctx->newSceneStateAvailable = false;

  // store the wrapper context in JNI env
  setContext_UI(env, obj, ctx);
}

/*
 * Method:    destroy
 * will be called to destroy the JNI MPEG-H UI manager wrapper
 */
UIMANAGER_FUNC(jint, destroy, jobject persistenceBuffer, jint persistenceBufferLength) {
  UIMANAGER_CONTEXT *ctx = getContext_UI(env, obj);

  uint16_t inDataLength = 0;
  if (persistenceBuffer != nullptr) {
    auto *inData = (void *) env->GetDirectBufferAddress(persistenceBuffer);
    inDataLength = (uint16_t) persistenceBufferLength;

    MPEGH_UI_ERROR err = mpegh_UI_GetPersistenceMemory(ctx->handle, &inData, &inDataLength);
    if (err != MPEGH_UI_OK) {
    LOGW("Unable to get persistence memory!");
    }
  }

  mpegh_UI_Manager_Close(ctx->handle);
  free(ctx);
  return inDataLength;
}

/*
 * Method:    command
 * will be called to pass the received MHAS frame to the UI manager
 */
UIMANAGER_FUNC(jboolean, command, jstring xmlAction) {
  UIMANAGER_CONTEXT *ctx = getContext_UI(env, obj);

  const char *str = env->GetStringUTFChars(xmlAction, 0);
  uint32_t strlen = env->GetStringLength(xmlAction);

  unsigned int flagsOut = 0;
  MPEGH_UI_ERROR result = mpegh_UI_ApplyXmlAction(ctx->handle, str, strlen, &flagsOut);
  if (result != MPEGH_UI_OK) {
    LOGW("Failed to apply XML action with result %d for command %s", result, str);
    return false;
  }
  return true;
}

/*
 * Method:    process
 * will be called to pass the received MHAS frame to the UI manager
 */
UIMANAGER_FUNC(jint, process, jobject inData, jint inDataLen, jboolean forceUiUpdate) {
  UIMANAGER_CONTEXT *ctx = getContext_UI(env, obj);

  // get memory pointer to the buffer of the corresponding JAVA input parameter
  auto *inputData = (uint8_t *)env->GetDirectBufferAddress(inData);
  auto inDataCapacity = (uint32_t)env->GetDirectBufferCapacity(inData);
  auto inputDataLen = (uint32_t)inDataLen;

  uint32_t outLength = inputDataLen;

  MPEGH_UI_ERROR feedResult = mpegh_UI_FeedMHAS(ctx->handle, inputData, inputDataLen);
  if (feedResult == MPEGH_UI_OK) {
    // Update the MPEG-H frame
    MPEGH_UI_ERROR updateResult =
        mpegh_UI_UpdateMHAS(ctx->handle, inputData, inDataCapacity, &outLength);
    if (updateResult == MPEGH_UI_OK) {
      uint32_t flagsOut = 0;
      while (!(flagsOut & MPEGH_UI_NO_CHANGE)) {
        char sceneState[XML_BUFFER_SIZE];
        int flagsIn = 0;
        if (forceUiUpdate) {
          flagsIn = MPEGH_UI_FORCE_UPDATE;
          forceUiUpdate = false;
        }
        MPEGH_UI_ERROR stateResult =
            mpegh_UI_GetXmlSceneState(ctx->handle, sceneState, XML_BUFFER_SIZE, flagsIn, &flagsOut);
        if (stateResult == MPEGH_UI_OK) {
          std::string tmp(sceneState);
          if (!tmp.empty() && tmp.compare(ctx->xmlSceneStateBuf) != 0) {
            ctx->xmlSceneStateBuf = tmp;
            ctx->newSceneStateAvailable = true;
          }
        } else {
          LOGW("Failed to get XML scene state with return value = %d", stateResult);
          break;
        }
      }
    } else {
      LOGW("Unable to update new data with return value = %d", updateResult);
    }
  } else {
    LOGW("Unable to feed new data with return value = %d", feedResult);
  }
  return outLength;
}

/*
 * Method:    newOsdAvailable
 * will be called to check if a new OSD string is available
 */
UIMANAGER_FUNC(jboolean, newOsdAvailable) {
  UIMANAGER_CONTEXT *ctx = getContext_UI(env, obj);
  return ctx->newSceneStateAvailable;
}

/*
 * Method:    getOsd
 * will be called to obtain a new OSD string
 */
UIMANAGER_FUNC(jstring, getOsd) {
  UIMANAGER_CONTEXT *ctx = getContext_UI(env, obj);
  ctx->newSceneStateAvailable = false;
  jstring out = env->NewStringUTF(ctx->xmlSceneStateBuf.c_str());
  ctx->xmlSceneStateBuf.clear();
  return out;
}