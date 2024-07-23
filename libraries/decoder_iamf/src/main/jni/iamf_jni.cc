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

#ifdef __ANDROID__
#include <android/log.h>
#endif
#include <jni.h>

#include <cstdint>
#include <cstdlib>

#include "IAMF_decoder.h"
#include "IAMF_defines.h"

#ifdef __ANDROID__
#define LOG_TAG "iamf_jni"
#define LOGE(...) \
  ((void)__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__))
#else  //  __ANDROID__
#define LOGE(...) \
  do {            \
  } while (0)
#endif  //  __ANDROID__

#define DECODER_FUNC(RETURN_TYPE, NAME, ...)                                  \
  extern "C" {                                                                \
  JNIEXPORT RETURN_TYPE Java_androidx_media3_decoder_iamf_IamfDecoder_##NAME( \
      JNIEnv* env, jobject thiz, ##__VA_ARGS__);                              \
  }                                                                           \
  JNIEXPORT RETURN_TYPE Java_androidx_media3_decoder_iamf_IamfDecoder_##NAME( \
      JNIEnv* env, jobject thiz, ##__VA_ARGS__)

jint JNI_OnLoad(JavaVM* vm, void* reserved) {
  JNIEnv* env;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
    return -1;
  }
  return JNI_VERSION_1_6;
}
DECODER_FUNC(jint, iamfLayoutBinauralChannelsCount) {
  return IAMF_layout_binaural_channels_count();
}

IAMF_DecoderHandle handle;

DECODER_FUNC(jint, iamfConfigDecoder, jbyteArray initializationDataArray) {
  handle = IAMF_decoder_open();
  IAMF_decoder_peak_limiter_enable(handle, 0);
  IAMF_decoder_peak_limiter_set_threshold(handle, -1.0f);
  IAMF_decoder_set_normalization_loudness(handle, 0.0f);
  IAMF_decoder_set_bit_depth(handle, 16);
  IAMF_decoder_set_sampling_rate(handle, 48000);
  IAMF_decoder_output_layout_set_binaural(handle);
  IAMF_decoder_set_pts(handle, 0, 90000);

  uint32_t* bytes_read = nullptr;
  jbyte* initializationDataBytes =
      env->GetByteArrayElements(initializationDataArray, 0);

  int status = IAMF_decoder_configure(
      handle, reinterpret_cast<uint8_t*>(initializationDataBytes),
      env->GetArrayLength(initializationDataArray), bytes_read);
  env->ReleaseByteArrayElements(initializationDataArray,
                                initializationDataBytes, 0);
  return status;
}

DECODER_FUNC(void, iamfClose) { IAMF_decoder_close(handle); }
