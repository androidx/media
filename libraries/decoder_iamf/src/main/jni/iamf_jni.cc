/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <cstddef>
#include <optional>
#ifdef __ANDROID__
#include <android/log.h>
#endif
#include <jni.h>

#include "iamf_tools/iamf_decoder_factory.h"
#include "iamf_tools/iamf_decoder_interface.h"
#include "iamf_tools/iamf_tools_api_types.h"

#ifdef __ANDROID__
#define LOG_TAG "iamf_jni"
#define LOGE(...) \
  ((void)__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__))
#else  //  __ANDROID__
#define LOGE(...) \
  do {            \
  } while (0)
#endif  //  __ANDROID__

#include <cstdint>
#include <memory>

namespace {
constexpr int ERROR = -1;
constexpr int OK = 0;
}  // namespace

namespace {
struct IamfDecoderWrapper {
  std::unique_ptr<iamf_tools::api::IamfDecoderInterface> decoder;
};

static const jlong MAX_MIX_PRESENTATION_ID = 4294967295;

/**
 * Translates from the requested Java OutputLayout to an iamf_tools enum.
 *
 * If the requested Java OutputLayout is OUTPUT_LAYOUT_UNSET, returns
 * std::nullopt to allow the decoder to use default behaviour.
 */
std::optional<iamf_tools::api::OutputLayout> ToIamfToolsOutputLayout(
    jint outputLayout) {
  switch (outputLayout) {
    case -1:
      return std::nullopt;
    case 0:
      return iamf_tools::api::OutputLayout::kItu2051_SoundSystemA_0_2_0;
    case 1:
      return iamf_tools::api::OutputLayout::kItu2051_SoundSystemB_0_5_0;
    case 2:
      return iamf_tools::api::OutputLayout::kItu2051_SoundSystemC_2_5_0;
    case 3:
      return iamf_tools::api::OutputLayout::kItu2051_SoundSystemD_4_5_0;
    case 4:
      return iamf_tools::api::OutputLayout::kItu2051_SoundSystemE_4_5_1;
    case 5:
      return iamf_tools::api::OutputLayout::kItu2051_SoundSystemF_3_7_0;
    case 6:
      return iamf_tools::api::OutputLayout::kItu2051_SoundSystemG_4_9_0;
    case 7:
      return iamf_tools::api::OutputLayout::kItu2051_SoundSystemH_9_10_3;
    case 8:
      return iamf_tools::api::OutputLayout::kItu2051_SoundSystemI_0_7_0;
    case 9:
      return iamf_tools::api::OutputLayout::kItu2051_SoundSystemJ_4_7_0;
    case 10:
      return iamf_tools::api::OutputLayout::kIAMF_SoundSystemExtension_2_7_0;
    case 11:
      return iamf_tools::api::OutputLayout::kIAMF_SoundSystemExtension_2_3_0;
    case 12:
      return iamf_tools::api::OutputLayout::kIAMF_SoundSystemExtension_0_1_0;
    case 13:
      return iamf_tools::api::OutputLayout::kIAMF_SoundSystemExtension_6_9_0;
    case 14:
      return iamf_tools::api::OutputLayout::kIAMF_Binaural;
    default:
      return std::nullopt;
  }
}

/** Translates from iamf_tools OutputLayout enum to Java OutputLayout int. */
jint FromIamfToolsOutputLayout(iamf_tools::api::OutputLayout outputLayout) {
  switch (outputLayout) {
    case iamf_tools::api::OutputLayout::kItu2051_SoundSystemA_0_2_0:
      return 0;
    case iamf_tools::api::OutputLayout::kItu2051_SoundSystemB_0_5_0:
      return 1;
    case iamf_tools::api::OutputLayout::kItu2051_SoundSystemC_2_5_0:
      return 2;
    case iamf_tools::api::OutputLayout::kItu2051_SoundSystemD_4_5_0:
      return 3;
    case iamf_tools::api::OutputLayout::kItu2051_SoundSystemE_4_5_1:
      return 4;
    case iamf_tools::api::OutputLayout::kItu2051_SoundSystemF_3_7_0:
      return 5;
    case iamf_tools::api::OutputLayout::kItu2051_SoundSystemG_4_9_0:
      return 6;
    case iamf_tools::api::OutputLayout::kItu2051_SoundSystemH_9_10_3:
      return 7;
    case iamf_tools::api::OutputLayout::kItu2051_SoundSystemI_0_7_0:
      return 8;
    case iamf_tools::api::OutputLayout::kItu2051_SoundSystemJ_4_7_0:
      return 9;
    case iamf_tools::api::OutputLayout::kIAMF_SoundSystemExtension_2_7_0:
      return 10;
    case iamf_tools::api::OutputLayout::kIAMF_SoundSystemExtension_2_3_0:
      return 11;
    case iamf_tools::api::OutputLayout::kIAMF_SoundSystemExtension_0_1_0:
      return 12;
    case iamf_tools::api::OutputLayout::kIAMF_SoundSystemExtension_6_9_0:
      return 13;
    case iamf_tools::api::OutputLayout::kIAMF_Binaural:
      return 14;
    default:
      return ERROR;
  }
}

/**
 * Translates from the requested Java OutputSampleType to an iamf_tools enum.
 *
 * If the requested Java OutputSampleType is OUTPUT_SAMPLE_TYPE_UNSET, returns
 * std::nullopt to allow the decoder to use default behaviour.
 */
std::optional<iamf_tools::api::OutputSampleType> ToIamfToolsOutputSampleType(
    jint outputSampleType) {
  switch (outputSampleType) {
    case 1:
      return iamf_tools::api::OutputSampleType::kInt16LittleEndian;
    case 2:
      return iamf_tools::api::OutputSampleType::kInt32LittleEndian;
    default:
      return std::nullopt;
  }
}

/**
 * Translates from iamf_tools OutputSampleType enum to Java OutputSampleType
 * int.
 */
jint FromIamfToolsOutputSampleType(
    iamf_tools::api::OutputSampleType outputSampleType) {
  switch (outputSampleType) {
    case iamf_tools::api::OutputSampleType::kInt16LittleEndian:
      return 1;
    case iamf_tools::api::OutputSampleType::kInt32LittleEndian:
      return 2;
    default:
      return ERROR;
  }
}

/**
 * Translates from the requested Java ChannelOrdering to an iamf_tools enum.
 *
 * If the requested Java ChannelOrdering is CHANNEL_ORDERING_UNSET, returns
 * std::nullopt to allow the decoder to use default behaviour.
 */
std::optional<iamf_tools::api::ChannelOrdering> ToIamfToolsChannelOrdering(
    jint channelOrdering) {
  switch (channelOrdering) {
    case 0:
      return iamf_tools::api::ChannelOrdering::kIamfOrdering;
    case 1:
      return iamf_tools::api::ChannelOrdering::kOrderingForAndroid;
    default:
      return std::nullopt;
  }
}

/** Creates an IAMF RequestedMix, which has optional fields. */
iamf_tools::api::RequestedMix CreateRequestedMix(
    jint requestedOutputLayout, jlong requestedMixPresnetationId) {
  iamf_tools::api::RequestedMix requested_mix;
  requested_mix.output_layout = ToIamfToolsOutputLayout(requestedOutputLayout);
  if (requestedMixPresnetationId >= 0 &&
      requestedMixPresnetationId <= MAX_MIX_PRESENTATION_ID) {
    requested_mix.mix_presentation_id = requestedMixPresnetationId;
  }
  return requested_mix;
}

/** Creates the Settings struct to create the decoder. */
iamf_tools::api::IamfDecoderFactory::Settings CreateSettings(
    jint requestedOutputLayout, jlong requestedMixPresnetationId,
    jint outputSampleType, jint channelOrdering) {
  iamf_tools::api::IamfDecoderFactory::Settings settings;
  settings.requested_mix =
      CreateRequestedMix(requestedOutputLayout, requestedMixPresnetationId);
  std::optional<iamf_tools::api::OutputSampleType> output_sample_type =
      ToIamfToolsOutputSampleType(outputSampleType);
  if (output_sample_type.has_value()) {
    settings.requested_output_sample_type = output_sample_type.value();
  }
  std::optional<iamf_tools::api::ChannelOrdering> channel_ordering =
      ToIamfToolsChannelOrdering(channelOrdering);
  if (channel_ordering.has_value()) {
    settings.channel_ordering = channel_ordering.value();
  }
  return settings;
}
}  // namespace

jlong iamfOpen(JNIEnv* env, jobject thiz) {
  return reinterpret_cast<intptr_t>(new IamfDecoderWrapper{nullptr});
}

void iamfClose(JNIEnv* env, jobject thiz, jlong decoderRawPointer) {
  delete reinterpret_cast<IamfDecoderWrapper*>(decoderRawPointer);
}

jint iamfCreate(JNIEnv* env, jobject thiz, jint requestedOutputLayout,
                jlong requestedMixPresentationId, jint outputSampleType,
                jint channelOrdering, jlong decoderRawPointer) {
  IamfDecoderWrapper* wrapper =
      reinterpret_cast<IamfDecoderWrapper*>(decoderRawPointer);
  if (!wrapper) {
    LOGE("iamfConfigDecoder called with null wrapper pointer.");
    return ERROR;
  }
  iamf_tools::api::IamfDecoderFactory::Settings settings =
      CreateSettings(requestedOutputLayout, requestedMixPresentationId,
                     outputSampleType, channelOrdering);
  wrapper->decoder = iamf_tools::api::IamfDecoderFactory::Create(settings);
  if (wrapper->decoder == nullptr) {
    LOGE("Failed to create IAMF decoder.");
    return ERROR;
  }
  return OK;
}

jint iamfCreateFromDescriptors(JNIEnv* env, jobject thiz,
                               jbyteArray initializationDataArray,
                               jint requestedOutputLayout,
                               jlong requestedMixPresentationId,
                               jint outputSampleType, jint channelOrdering,
                               jlong decoderRawPointer) {
  IamfDecoderWrapper* wrapper =
      reinterpret_cast<IamfDecoderWrapper*>(decoderRawPointer);
  if (!wrapper) {
    LOGE("iamfConfigDecoder called with null wrapper pointer.");
    return ERROR;
  }

  iamf_tools::api::IamfDecoderFactory::Settings settings =
      CreateSettings(requestedOutputLayout, requestedMixPresentationId,
                     outputSampleType, channelOrdering);
  jbyte* init_data_bytes =
      env->GetByteArrayElements(initializationDataArray, 0);
  int init_data_size = env->GetArrayLength(initializationDataArray);

  wrapper->decoder = iamf_tools::api::IamfDecoderFactory::CreateFromDescriptors(
      settings, reinterpret_cast<uint8_t*>(init_data_bytes), init_data_size);
  env->ReleaseByteArrayElements(initializationDataArray, init_data_bytes, 0);

  if (wrapper->decoder == nullptr) {
    LOGE("Failed to create IAMF decoder from descriptors.");
    return ERROR;
  }
  return OK;  // Success
}

jint iamfDecode(JNIEnv* env, jobject thiz, jobject inputBuffer, jint inputSize,
                jlong decoderRawPointer) {
  IamfDecoderWrapper* wrapper =
      reinterpret_cast<IamfDecoderWrapper*>(decoderRawPointer);
  if (!wrapper || !wrapper->decoder) {
    LOGE("iamfDecode called with invalid decoder.");
    return ERROR;
  }

  void* void_in_buff = env->GetDirectBufferAddress(inputBuffer);
  if (void_in_buff == nullptr) {
    LOGE("Failed to get direct buffer address for input buffer.");
    LOGE("inputSize: %d", inputSize);  //  TODO LOG SOMETHING
    return ERROR;
  }
  uint8_t* in_buf = reinterpret_cast<uint8_t*>(void_in_buff);
  if (in_buf == nullptr && inputSize > 0) {
    LOGE("Failed to cast input buffer to uint8_t*.");
    return ERROR;
  }
  iamf_tools::api::IamfStatus decode_status =
      wrapper->decoder->Decode(in_buf, inputSize);
  if (!decode_status.ok()) {
    LOGE("Failed to decode: %s", decode_status.error_message.c_str());
    return ERROR;
  }
  return OK;
}

jint iamfGetOutputTemporalUnit(JNIEnv* env, jobject thiz, jobject outputBuffer,
                               jint outputSize, jlong decoderRawPointer) {
  IamfDecoderWrapper* wrapper =
      reinterpret_cast<IamfDecoderWrapper*>(decoderRawPointer);
  if (!wrapper || !wrapper->decoder) {
    LOGE("iamfGetOutputTemporalUnit called with invalid decoder.");
    return ERROR;
  }
  void* void_out_buff = env->GetDirectBufferAddress(outputBuffer);
  if (void_out_buff == nullptr) {
    LOGE("Failed to get direct buffer address for output buffer.");
    return ERROR;
  }
  uint8_t* out_buf = reinterpret_cast<uint8_t*>(void_out_buff);
  if (out_buf == nullptr) {
    LOGE("Failed to cast buffer to uint8_t*.");
    return ERROR;
  }
  size_t bytes_written = 0;
  iamf_tools::api::IamfStatus status = wrapper->decoder->GetOutputTemporalUnit(
      out_buf, outputSize, bytes_written);
  if (!status.ok()) {
    LOGE("Failed to get output temporal unit: %s",
         status.error_message.c_str());
    return ERROR;
  }
  return bytes_written;
}

jboolean iamfIsTemporalUnitAvailable(JNIEnv* env, jobject thiz,
                                     jlong decoderRawPointer) {
  IamfDecoderWrapper* wrapper =
      reinterpret_cast<IamfDecoderWrapper*>(decoderRawPointer);
  if (!wrapper || !wrapper->decoder) {
    LOGE("iamfIsTemporalUnitAvailable called with invalid decoder.");
    return false;
  }
  return wrapper->decoder->IsTemporalUnitAvailable();
}

jboolean iamfIsDescriptorProcessingComplete(JNIEnv* env, jobject thiz,
                                            jlong decoderRawPointer) {
  IamfDecoderWrapper* wrapper =
      reinterpret_cast<IamfDecoderWrapper*>(decoderRawPointer);
  if (!wrapper || !wrapper->decoder) {
    LOGE("iamfIsDescriptorProcessingComplete called with invalid decoder.");
    return false;
  }
  return wrapper->decoder->IsDescriptorProcessingComplete();
}

jint iamfGetNumberOfOutputChannels(JNIEnv* env, jobject thiz,
                                   jlong decoderRawPointer) {
  IamfDecoderWrapper* wrapper =
      reinterpret_cast<IamfDecoderWrapper*>(decoderRawPointer);
  if (!wrapper || !wrapper->decoder) {
    LOGE("iamfGetNumberOfOutputChannels called with invalid decoder.");
    return ERROR;
  }
  int channel_count = 0;
  iamf_tools::api::IamfStatus status =
      wrapper->decoder->GetNumberOfOutputChannels(channel_count);
  if (!status.ok()) {
    LOGE("Failed to get number of output channels: %s",
         status.error_message.c_str());
    return ERROR;
  }
  return channel_count;
}

jint iamfGetSelectedOutputLayout(JNIEnv* env, jobject thiz,
                                 jlong decoderRawPointer) {
  IamfDecoderWrapper* wrapper =
      reinterpret_cast<IamfDecoderWrapper*>(decoderRawPointer);
  if (!wrapper || !wrapper->decoder) {
    LOGE("iamfGetSelectedOutputLayout called with invalid decoder.");
    return ERROR;
  }
  iamf_tools::api::SelectedMix selected_mix;
  iamf_tools::api::IamfStatus status =
      wrapper->decoder->GetOutputMix(selected_mix);
  if (!status.ok()) {
    LOGE("Failed to get output layout: %s", status.error_message.c_str());
    return ERROR;
  }
  return FromIamfToolsOutputLayout(selected_mix.output_layout);
}

jlong iamfGetSelectedMixPresentationId(JNIEnv* env, jobject thiz,
                                       jlong decoderRawPointer) {
  IamfDecoderWrapper* wrapper =
      reinterpret_cast<IamfDecoderWrapper*>(decoderRawPointer);
  if (!wrapper || !wrapper->decoder) {
    LOGE("iamfGetSelectedMixPresentationId called with invalid decoder.");
    return ERROR;
  }
  iamf_tools::api::SelectedMix selected_mix;
  iamf_tools::api::IamfStatus status =
      wrapper->decoder->GetOutputMix(selected_mix);
  if (!status.ok()) {
    LOGE("Failed to get output layout: %s", status.error_message.c_str());
    return ERROR;
  }
  return selected_mix.mix_presentation_id;
}

jint iamfGetOutputSampleType(JNIEnv* env, jobject thiz,
                             jlong decoderRawPointer) {
  IamfDecoderWrapper* wrapper =
      reinterpret_cast<IamfDecoderWrapper*>(decoderRawPointer);
  if (!wrapper || !wrapper->decoder) {
    LOGE("iamfGetOutputSampleType called with invalid decoder.");
    return ERROR;
  }
  iamf_tools::api::OutputSampleType sample_type =
      wrapper->decoder->GetOutputSampleType();
  int value = FromIamfToolsOutputSampleType(sample_type);
  if (value == ERROR) {
    LOGE("Failed to get output sample type.");
    return ERROR;
  }
  return value;
}

jint iamfGetSampleRate(JNIEnv* env, jobject thiz, jlong decoderRawPointer) {
  IamfDecoderWrapper* wrapper =
      reinterpret_cast<IamfDecoderWrapper*>(decoderRawPointer);
  if (!wrapper || !wrapper->decoder) {
    LOGE("iamfGetSampleRate called with invalid decoder.");
    return ERROR;
  }
  uint32_t sample_rate = 0;
  iamf_tools::api::IamfStatus status =
      wrapper->decoder->GetSampleRate(sample_rate);
  if (!status.ok()) {
    LOGE("Failed to get sample rate: %s", status.error_message.c_str());
    return ERROR;
  }
  return sample_rate;
}

jint iamfGetFrameSize(JNIEnv* env, jobject thiz, jlong decoderRawPointer) {
  IamfDecoderWrapper* wrapper =
      reinterpret_cast<IamfDecoderWrapper*>(decoderRawPointer);
  if (!wrapper || !wrapper->decoder) {
    LOGE("iamfGetFrameSize called with invalid decoder.");
    return ERROR;
  }
  uint32_t frame_size;
  iamf_tools::api::IamfStatus status =
      wrapper->decoder->GetFrameSize(frame_size);
  if (!status.ok()) {
    LOGE("Failed to get frame size: %s", status.error_message.c_str());
    return ERROR;
  }
  return frame_size;
}

jint iamfReset(JNIEnv* env, jobject thiz, jlong decoderRawPointer) {
  IamfDecoderWrapper* wrapper =
      reinterpret_cast<IamfDecoderWrapper*>(decoderRawPointer);
  if (!wrapper || !wrapper->decoder) {
    LOGE("iamfReset called with invalid decoder.");
    return ERROR;
  }
  iamf_tools::api::IamfStatus status = wrapper->decoder->Reset();
  if (!status.ok()) {
    LOGE("Failed to reset decoder: %s", status.error_message.c_str());
    return ERROR;
  }
  return OK;
}

jint iamfResetWithNewMix(JNIEnv* env, jobject thiz, jint requestedOutputLayout,
                         jlong requestedMixPresentationId,
                         jlong decoderRawPointer) {
  IamfDecoderWrapper* wrapper =
      reinterpret_cast<IamfDecoderWrapper*>(decoderRawPointer);
  if (!wrapper || !wrapper->decoder) {
    LOGE("iamfResetWithNewMix called with invalid decoder.");
    return ERROR;
  }
  iamf_tools::api::RequestedMix requested_mix =
      CreateRequestedMix(requestedOutputLayout, requestedMixPresentationId);
  // We cannot return the selected layout and Mix Presentation ID from this
  // function so we will not use the SelectedMix and instead allow callers to
  // call the two separate methods to get the layout and ID.
  iamf_tools::api::SelectedMix unused_selected_mix;
  iamf_tools::api::IamfStatus status =
      wrapper->decoder->ResetWithNewMix(requested_mix, unused_selected_mix);
  if (!status.ok()) {
    LOGE("Failed to reset decoder with new mix: %s",
         status.error_message.c_str());
    return ERROR;
  }
  return OK;
}

jint iamfSignalEndOfDecoding(JNIEnv* env, jobject thiz,
                             jlong decoderRawPointer) {
  IamfDecoderWrapper* wrapper =
      reinterpret_cast<IamfDecoderWrapper*>(decoderRawPointer);
  if (!wrapper || !wrapper->decoder) {
    LOGE("iamfSignalEndOfDecoding called with invalid decoder.");
    return ERROR;
  }
  iamf_tools::api::IamfStatus status = wrapper->decoder->SignalEndOfDecoding();
  if (!status.ok()) {
    LOGE("Failed to signal end of decoding: %s", status.error_message.c_str());
    return ERROR;
  }
  return OK;
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
  JNIEnv* env;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
    return ERROR;
  }

  static const JNINativeMethod kIamfDecoderMethods[] = {
      {"iamfOpen", "()J", reinterpret_cast<void*>(iamfOpen)},
      {"iamfClose", "(J)V", reinterpret_cast<void*>(iamfClose)},
      {"iamfCreate", "(IIIJ)I", reinterpret_cast<void*>(iamfCreate)},
      {"iamfCreateFromDescriptors", "([BIJIIJ)I",
       reinterpret_cast<void*>(iamfCreateFromDescriptors)},
      {"iamfDecode", "(Ljava/nio/ByteBuffer;IJ)I",
       reinterpret_cast<void*>(iamfDecode)},
      {"iamfGetOutputTemporalUnit", "(Ljava/nio/ByteBuffer;IJ)I",
       reinterpret_cast<void*>(iamfGetOutputTemporalUnit)},
      {"iamfIsTemporalUnitAvailable", "(J)Z",
       reinterpret_cast<void*>(iamfIsTemporalUnitAvailable)},
      {"iamfIsDescriptorProcessingComplete", "(J)Z",
       reinterpret_cast<void*>(iamfIsDescriptorProcessingComplete)},
      {"iamfGetNumberOfOutputChannels", "(J)I",
       reinterpret_cast<void*>(iamfGetNumberOfOutputChannels)},
      {"iamfGetSelectedOutputLayout", "(J)I",
       reinterpret_cast<void*>(iamfGetSelectedOutputLayout)},
      {"iamfGetSelectedMixPresentationId", "(J)J",
       reinterpret_cast<void*>(iamfGetSelectedMixPresentationId)},
      {"iamfGetOutputSampleType", "(J)I",
       reinterpret_cast<void*>(iamfGetOutputSampleType)},
      {"iamfGetSampleRate", "(J)I", reinterpret_cast<void*>(iamfGetSampleRate)},
      {"iamfGetFrameSize", "(J)I", reinterpret_cast<void*>(iamfGetFrameSize)},
      {"iamfReset", "(J)I", reinterpret_cast<void*>(iamfReset)},
      {"iamfResetWithNewMix", "(IJJ)I",
       reinterpret_cast<void*>(iamfResetWithNewMix)},
      {"iamfSignalEndOfDecoding", "(J)I",
       reinterpret_cast<void*>(iamfSignalEndOfDecoding)},
  };

  jclass clazz = env->FindClass("androidx/media3/decoder/iamf/IamfDecoder");
  if (!clazz) {
    LOGE("JNI_OnLoad: FindClass failed for IamfDecoder");
    return ERROR;
  }
  if (env->RegisterNatives(
          clazz, kIamfDecoderMethods,
          sizeof(kIamfDecoderMethods) / sizeof(kIamfDecoderMethods[0])) < 0) {
    LOGE("JNI_OnLoad: RegisterNatives failed for IamfDecoder");
    return ERROR;
  }

  return JNI_VERSION_1_6;
}
