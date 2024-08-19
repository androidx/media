/*
 * Copyright (C) 2016 The Android Open Source Project
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
#include <jni.h>
#include <stdlib.h>
#include <thread>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <libyuv.h>
#include <libyuv/scale.h>

extern "C" {
#ifdef __cplusplus
#define __STDC_CONSTANT_MACROS
#ifdef _STDINT_H
#undef _STDINT_H
#endif
#include <stdint.h>
#endif
#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>
#include <libavutil/error.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>
#include <libswscale/swscale.h>
}

#define LOG_TAG "ffmpeg_jni"
#define LOGE(...) \
  ((void)__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__))
#define LOGW(...) \
  ((void)__android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__))
#define LOGD(...) \
  ((void)__android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__))

#define LIBRARY_FUNC(RETURN_TYPE, NAME, ...)                                   \
  extern "C" {                                                                 \
  JNIEXPORT RETURN_TYPE                                                        \
      Java_androidx_media3_decoder_ffmpeg_FfmpegLibrary_##NAME(JNIEnv *env,    \
                                                               jobject thiz,   \
                                                               ##__VA_ARGS__); \
  }                                                                            \
  JNIEXPORT RETURN_TYPE                                                        \
      Java_androidx_media3_decoder_ffmpeg_FfmpegLibrary_##NAME(                \
          JNIEnv *env, jobject thiz, ##__VA_ARGS__)

#define AUDIO_DECODER_FUNC(RETURN_TYPE, NAME, ...)                   \
  extern "C" {                                                       \
  JNIEXPORT RETURN_TYPE                                              \
      Java_androidx_media3_decoder_ffmpeg_FfmpegAudioDecoder_##NAME( \
          JNIEnv *env, jobject thiz, ##__VA_ARGS__);                 \
  }                                                                  \
  JNIEXPORT RETURN_TYPE                                              \
      Java_androidx_media3_decoder_ffmpeg_FfmpegAudioDecoder_##NAME( \
          JNIEnv *env, jobject thiz, ##__VA_ARGS__)

#define VIDEO_DECODER_FUNC(RETURN_TYPE, NAME, ...)                             \
  extern "C" {                                                                 \
  JNIEXPORT RETURN_TYPE                                                        \
      Java_androidx_media3_decoder_ffmpeg_ExperimentalFfmpegVideoDecoder_##NAME( \
          JNIEnv *env, jobject thiz, ##__VA_ARGS__);                           \
  }                                                                            \
  JNIEXPORT RETURN_TYPE                                                        \
      Java_androidx_media3_decoder_ffmpeg_ExperimentalFfmpegVideoDecoder_##NAME( \
          JNIEnv *env, jobject thiz, ##__VA_ARGS__)

#define ERROR_STRING_BUFFER_LENGTH 256

// Output format corresponding to AudioFormat.ENCODING_PCM_16BIT.
static const AVSampleFormat OUTPUT_FORMAT_PCM_16BIT = AV_SAMPLE_FMT_S16;
// Output format corresponding to AudioFormat.ENCODING_PCM_FLOAT.
static const AVSampleFormat OUTPUT_FORMAT_PCM_FLOAT = AV_SAMPLE_FMT_FLT;

static const int AUDIO_DECODER_ERROR_INVALID_DATA = -1;
static const int AUDIO_DECODER_ERROR_OTHER = -2;

static const int VIDEO_DECODER_ERROR_SURFACE = -4;
static const int VIDEO_DECODER_SUCCESS = 0;
static const int VIDEO_DECODER_ERROR_INVALID_DATA = -1;
static const int VIDEO_DECODER_ERROR_OTHER = -2;
static const int VIDEO_DECODER_ERROR_READ_FRAME = -3;

static jmethodID growOutputBufferMethod;

/**
 * Returns the AVCodec with the specified name, or NULL if it is not available.
 */
const AVCodec *getCodecByName(JNIEnv *env, jstring codecName);

/**
 * Allocates and opens a new AVCodecContext for the specified codec, passing the
 * provided extraData as initialization data for the decoder if it is non-NULL.
 * Returns the created context.
 */
AVCodecContext *createContext(JNIEnv *env, const AVCodec *codec,
                              jbyteArray extraData, jboolean outputFloat,
                              jint rawSampleRate, jint rawChannelCount);

struct GrowOutputBufferCallback {
  uint8_t *operator()(int requiredSize) const;

  JNIEnv *env;
  jobject thiz;
  jobject decoderOutputBuffer;
};

/**
 * Decodes the packet into the output buffer, returning the number of bytes
 * written, or a negative AUDIO_DECODER_ERROR constant value in the case of an
 * error.
 */
int decodePacket(AVCodecContext *context, AVPacket *packet,
                 uint8_t *outputBuffer, int outputSize,
                 GrowOutputBufferCallback growBuffer);

/**
 * Transforms ffmpeg AVERROR into a negative AUDIO_DECODER_ERROR constant value.
 */
int transformError(int errorNumber);

/**
 * Outputs a log message describing the avcodec error number.
 */
void logError(const char *functionName, int errorNumber);

/**
 * Releases the specified context.
 */
void releaseContext(AVCodecContext *context);

jint JNI_OnLoad(JavaVM *vm, void *reserved) {
  JNIEnv *env;
  if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
    LOGE("JNI_OnLoad: GetEnv failed");
    return -1;
  }
  jclass clazz =
      env->FindClass("androidx/media3/decoder/ffmpeg/FfmpegAudioDecoder");
  if (!clazz) {
    LOGE("JNI_OnLoad: FindClass failed");
    return -1;
  }
  growOutputBufferMethod =
      env->GetMethodID(clazz, "growOutputBuffer",
                       "(Landroidx/media3/decoder/"
                       "SimpleDecoderOutputBuffer;I)Ljava/nio/ByteBuffer;");
  if (!growOutputBufferMethod) {
    LOGE("JNI_OnLoad: GetMethodID failed");
    return -1;
  }
  return JNI_VERSION_1_6;
}

LIBRARY_FUNC(jstring, ffmpegGetVersion) {
  return env->NewStringUTF(LIBAVCODEC_IDENT);
}

LIBRARY_FUNC(jint, ffmpegGetInputBufferPaddingSize) {
  return (jint)AV_INPUT_BUFFER_PADDING_SIZE;
}

LIBRARY_FUNC(jboolean, ffmpegHasDecoder, jstring codecName) {
  return getCodecByName(env, codecName) != NULL;
}

AUDIO_DECODER_FUNC(jlong, ffmpegInitialize, jstring codecName,
                   jbyteArray extraData, jboolean outputFloat,
                   jint rawSampleRate, jint rawChannelCount) {
  const AVCodec *codec = getCodecByName(env, codecName);
  if (!codec) {
    LOGE("Codec not found.");
    return 0L;
  }
  return (jlong)createContext(env, codec, extraData, outputFloat, rawSampleRate,
                              rawChannelCount);
}

AUDIO_DECODER_FUNC(jint, ffmpegDecode, jlong context, jobject inputData,
                   jint inputSize, jobject decoderOutputBuffer,
                   jobject outputData, jint outputSize) {
  if (!context) {
    LOGE("Context must be non-NULL.");
    return -1;
  }
  if (!inputData || !decoderOutputBuffer || !outputData) {
    LOGE("Input and output buffers must be non-NULL.");
    return -1;
  }
  if (inputSize < 0) {
    LOGE("Invalid input buffer size: %d.", inputSize);
    return -1;
  }
  if (outputSize < 0) {
    LOGE("Invalid output buffer length: %d", outputSize);
    return -1;
  }
  uint8_t *inputBuffer = (uint8_t *)env->GetDirectBufferAddress(inputData);
  uint8_t *outputBuffer = (uint8_t *)env->GetDirectBufferAddress(outputData);
  AVPacket *packet = av_packet_alloc();
  if (!packet) {
    LOGE("Failed to allocate packet.");
    return -1;
  }
  packet->data = inputBuffer;
  packet->size = inputSize;
  const int ret =
      decodePacket((AVCodecContext *)context, packet, outputBuffer, outputSize,
                   GrowOutputBufferCallback{env, thiz, decoderOutputBuffer});
  av_packet_free(&packet);
  return ret;
}

uint8_t *GrowOutputBufferCallback::operator()(int requiredSize) const {
  jobject newOutputData = env->CallObjectMethod(
      thiz, growOutputBufferMethod, decoderOutputBuffer, requiredSize);
  if (env->ExceptionCheck()) {
    LOGE("growOutputBuffer() failed");
    env->ExceptionDescribe();
    return nullptr;
  }
  return static_cast<uint8_t *>(env->GetDirectBufferAddress(newOutputData));
}

AUDIO_DECODER_FUNC(jint, ffmpegGetChannelCount, jlong context) {
  if (!context) {
    LOGE("Context must be non-NULL.");
    return -1;
  }
  return ((AVCodecContext *)context)->ch_layout.nb_channels;
}

AUDIO_DECODER_FUNC(jint, ffmpegGetSampleRate, jlong context) {
  if (!context) {
    LOGE("Context must be non-NULL.");
    return -1;
  }
  return ((AVCodecContext *)context)->sample_rate;
}

AUDIO_DECODER_FUNC(jlong, ffmpegReset, jlong jContext, jbyteArray extraData) {
  AVCodecContext *context = (AVCodecContext *)jContext;
  if (!context) {
    LOGE("Tried to reset without a context.");
    return 0L;
  }

  AVCodecID codecId = context->codec_id;
  if (codecId == AV_CODEC_ID_TRUEHD) {
    // Release and recreate the context if the codec is TrueHD.
    // TODO: Figure out why flushing doesn't work for this codec.
    releaseContext(context);
    const AVCodec *codec = avcodec_find_decoder(codecId);
    if (!codec) {
      LOGE("Unexpected error finding codec %d.", codecId);
      return 0L;
    }
    jboolean outputFloat =
        (jboolean)(context->request_sample_fmt == OUTPUT_FORMAT_PCM_FLOAT);
    return (jlong)createContext(env, codec, extraData, outputFloat,
                                /* rawSampleRate= */ -1,
                                /* rawChannelCount= */ -1);
  }

  avcodec_flush_buffers(context);
  return (jlong)context;
}

AUDIO_DECODER_FUNC(void, ffmpegRelease, jlong context) {
  if (context) {
    releaseContext((AVCodecContext *)context);
  }
}

const AVCodec *getCodecByName(JNIEnv *env, jstring codecName) {
  if (!codecName) {
    return NULL;
  }
  const char *codecNameChars = env->GetStringUTFChars(codecName, NULL);
  const AVCodec *codec = avcodec_find_decoder_by_name(codecNameChars);
  env->ReleaseStringUTFChars(codecName, codecNameChars);
  return codec;
}

AVCodecContext *createContext(JNIEnv *env, const AVCodec *codec,
                              jbyteArray extraData, jboolean outputFloat,
                              jint rawSampleRate, jint rawChannelCount) {
  AVCodecContext *context = avcodec_alloc_context3(codec);
  if (!context) {
    LOGE("Failed to allocate context.");
    return NULL;
  }
  context->request_sample_fmt =
      outputFloat ? OUTPUT_FORMAT_PCM_FLOAT : OUTPUT_FORMAT_PCM_16BIT;
  if (extraData) {
    jsize size = env->GetArrayLength(extraData);
    context->extradata_size = size;
    context->extradata =
        (uint8_t *)av_malloc(size + AV_INPUT_BUFFER_PADDING_SIZE);
    if (!context->extradata) {
      LOGE("Failed to allocate extradata.");
      releaseContext(context);
      return NULL;
    }
    env->GetByteArrayRegion(extraData, 0, size, (jbyte *)context->extradata);
  }
  if (context->codec_id == AV_CODEC_ID_PCM_MULAW ||
      context->codec_id == AV_CODEC_ID_PCM_ALAW) {
    context->sample_rate = rawSampleRate;
    av_channel_layout_default(&context->ch_layout, rawChannelCount);
  }
  context->err_recognition = AV_EF_IGNORE_ERR;
  int result = avcodec_open2(context, codec, NULL);
  if (result < 0) {
    logError("avcodec_open2", result);
    releaseContext(context);
    return NULL;
  }
  return context;
}

int decodePacket(AVCodecContext *context, AVPacket *packet,
                 uint8_t *outputBuffer, int outputSize,
                 GrowOutputBufferCallback growBuffer) {
  int result = 0;
  // Queue input data.
  result = avcodec_send_packet(context, packet);
  if (result) {
    logError("avcodec_send_packet", result);
    return transformError(result);
  }

  // Dequeue output data until it runs out.
  int outSize = 0;
  while (true) {
    AVFrame *frame = av_frame_alloc();
    if (!frame) {
      LOGE("Failed to allocate output frame.");
      return AUDIO_DECODER_ERROR_INVALID_DATA;
    }
    result = avcodec_receive_frame(context, frame);
    if (result) {
      av_frame_free(&frame);
      if (result == AVERROR(EAGAIN)) {
        break;
      }
      logError("avcodec_receive_frame", result);
      return transformError(result);
    }

    // Resample output.
    AVSampleFormat sampleFormat = context->sample_fmt;
    int channelCount = context->ch_layout.nb_channels;
    int sampleRate = context->sample_rate;
    int sampleCount = frame->nb_samples;
    int dataSize = av_samples_get_buffer_size(NULL, channelCount, sampleCount,
                                              sampleFormat, 1);
    SwrContext *resampleContext = static_cast<SwrContext *>(context->opaque);
    if (!resampleContext) {
      result =
          swr_alloc_set_opts2(&resampleContext,             // ps
                              &context->ch_layout,          // out_ch_layout
                              context->request_sample_fmt,  // out_sample_fmt
                              sampleRate,                   // out_sample_rate
                              &context->ch_layout,          // in_ch_layout
                              sampleFormat,                 // in_sample_fmt
                              sampleRate,                   // in_sample_rate
                              0,                            // log_offset
                              NULL                          // log_ctx
          );
      if (result < 0) {
        logError("swr_alloc_set_opts2", result);
        av_frame_free(&frame);
        return transformError(result);
      }
      result = swr_init(resampleContext);
      if (result < 0) {
        logError("swr_init", result);
        av_frame_free(&frame);
        return transformError(result);
      }
      context->opaque = resampleContext;
    }

    int outSampleSize = av_get_bytes_per_sample(context->request_sample_fmt);
    int outSamples = swr_get_out_samples(resampleContext, sampleCount);
    int bufferOutSize = outSampleSize * channelCount * outSamples;
    if (outSize + bufferOutSize > outputSize) {
      LOGD(
          "Output buffer size (%d) too small for output data (%d), "
          "reallocating buffer.",
          outputSize, outSize + bufferOutSize);
      outputSize = outSize + bufferOutSize;
      outputBuffer = growBuffer(outputSize);
      if (!outputBuffer) {
        LOGE("Failed to reallocate output buffer.");
        av_frame_free(&frame);
        return AUDIO_DECODER_ERROR_OTHER;
      }
    }
    result = swr_convert(resampleContext, &outputBuffer, bufferOutSize,
                         (const uint8_t **)frame->data, frame->nb_samples);
    av_frame_free(&frame);
    if (result < 0) {
      logError("swr_convert", result);
      return AUDIO_DECODER_ERROR_INVALID_DATA;
    }
    int available = swr_get_out_samples(resampleContext, 0);
    if (available != 0) {
      LOGE("Expected no samples remaining after resampling, but found %d.",
           available);
      return AUDIO_DECODER_ERROR_INVALID_DATA;
    }
    outputBuffer += bufferOutSize;
    outSize += bufferOutSize;
  }
  return outSize;
}

int transformError(int errorNumber) {
  return errorNumber == AVERROR_INVALIDDATA ? AUDIO_DECODER_ERROR_INVALID_DATA
                                            : AUDIO_DECODER_ERROR_OTHER;
}

void logError(const char *functionName, int errorNumber) {
  char *buffer = (char *)malloc(ERROR_STRING_BUFFER_LENGTH * sizeof(char));
  av_strerror(errorNumber, buffer, ERROR_STRING_BUFFER_LENGTH);
  LOGE("Error in %s: %s", functionName, buffer);
  free(buffer);
}

void releaseContext(AVCodecContext *context) {
  if (!context) {
    return;
  }
  SwrContext *swrContext;
  if ((swrContext = (SwrContext *)context->opaque)) {
    swr_free(&swrContext);
    context->opaque = NULL;
  }
  avcodec_free_context(&context);
}

// video

// Android YUV format. See:
// https://developer.android.com/reference/android/graphics/ImageFormat.html#YV12.
const int kImageFormatYV12 = 0x32315659;

struct JniContext {
    ~JniContext() {
        if (native_window) {
            ANativeWindow_release(native_window);
        }
    }

    bool MaybeAcquireNativeWindow(JNIEnv *env, jobject new_surface) {
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
            LOGE("kJniStatusANativeWindowError");
            surface = nullptr;
            return false;
        }
        surface = new_surface;
        return true;
    }

    jfieldID data_field;
    jfieldID yuvPlanes_field;
    jfieldID yuvStrides_field;
    jfieldID width_field;
    jfieldID height_field;
    jfieldID pts_field;
    jmethodID init_for_private_frame_method;
    jmethodID init_for_yuv_frame_method;
    jmethodID init_method;

    AVCodecContext *codecContext;
    SwsContext *swsContext;

    ANativeWindow *native_window = nullptr;
    jobject surface = nullptr;
    // rorate degree from InputFormat
    int rotate_degree = 0;
    int native_window_width = 0;
    int native_window_height = 0;
};

constexpr int AlignTo16(int value) { return (value + 15) & (~15); }

/**
 * Convert AvFrame ColorSpace to exoplayer supported ColorSpace
 */
constexpr int cvt_colorspace(AVColorSpace colorSpace) {
    int colorspace = 0;
    switch (colorSpace) {
        case AVCOL_SPC_BT470BG:
        case AVCOL_SPC_SMPTE170M:
        case AVCOL_SPC_SMPTE240M:
            colorspace = 1;
        case AVCOL_SPC_BT709:
            colorspace = 2;
        case AVCOL_SPC_BT2020_NCL:
        case AVCOL_SPC_BT2020_CL:
            colorspace = 3;
    }
    return colorspace;
}

/**
 * Convert other format like(yuv420p10bit) to yuv420p
 * and scale
 * @return AVFrame
 */
AVFrame *cvt_format(JniContext *jniContext,
                   AVFrame *src,
                   AVPixelFormat dst_format,
                   int dst_width,
                   int dst_height) {
    auto src_format = AVPixelFormat(src->format);
    auto swsContext = sws_getCachedContext(jniContext->swsContext,
                                           src->width, src->height, src_format,
                                           dst_width, dst_height, dst_format,
                                           SWS_FAST_BILINEAR, NULL, NULL, NULL
    );
    if (!swsContext) {
        LOGE("Failed to allocate swsContext.");
        return nullptr;
    }

    jniContext->swsContext = swsContext;
    auto dst = av_frame_alloc();
    av_frame_copy_props(dst, src); // copy meta data
    dst->width = dst_width;
    dst->height = dst_height;
    dst->format = dst_format;
    auto alloc_result = av_frame_get_buffer(dst, 0);    // allocate buffer
    if (alloc_result != 0) {
        logError("av_frame_get_buffer", alloc_result);
        av_frame_free(&dst);
        return nullptr;
    }
    auto scale_result = sws_scale(swsContext,
                                  src->data, src->linesize, 0, src->height,
                                  dst->data, dst->linesize);
    if (!scale_result) {
        logError("sws_scale", scale_result);
        av_frame_free(&dst);
        return nullptr;
    }
    return dst;
}

/**
 * Convert degree to libyuv::RotationMode
 * @return libyuv::RotationMode
 */
libyuv::RotationMode cvt_rotate(int degree) {
    libyuv::RotationMode rotate = libyuv::kRotate0;
    if (degree == 90) {
        rotate = libyuv::kRotate90;
    } else if (degree == 180) {
        rotate = libyuv::kRotate180;
    } else if (degree == 270) {
        rotate = libyuv::kRotate270;
    }
    return rotate;
}

JniContext *createVideoContext(JNIEnv *env,
                               const AVCodec *codec,
                               jbyteArray extraData,
                               jint threads,
                               jint degree) {
    JniContext *jniContext = new(std::nothrow)JniContext();

    AVCodecContext *codecContext = avcodec_alloc_context3(codec);
    if (!codecContext) {
        LOGE("Failed to allocate context.");
        return NULL;
    }

    // rotate
    jniContext->rotate_degree = degree;

    if (extraData) {
        jsize size = env->GetArrayLength(extraData);
        codecContext->extradata_size = size;
        codecContext->extradata = (uint8_t *) av_malloc(size + AV_INPUT_BUFFER_PADDING_SIZE);
        if (!codecContext->extradata) {
            LOGE("Failed to allocate extradata.");
            releaseContext(codecContext);
            return NULL;
        }
        env->GetByteArrayRegion(extraData, 0, size, (jbyte *) codecContext->extradata);
    }

    // opt decode speed.
    codecContext->skip_loop_filter = AVDISCARD_ALL;
    codecContext->skip_frame = AVDISCARD_DEFAULT;
    codecContext->thread_count = threads;
    codecContext->thread_type = FF_THREAD_FRAME;
    codecContext->err_recognition = AV_EF_IGNORE_ERR;
    int result = avcodec_open2(codecContext, codec, NULL);
    if (result < 0) {
        logError("avcodec_open2", result);
        releaseContext(codecContext);
        return NULL;
    }

    jniContext->codecContext = codecContext;

    // Populate JNI References.
    const jclass outputBufferClass = env->FindClass("androidx/media3/decoder/VideoDecoderOutputBuffer");
    jniContext->data_field = env->GetFieldID(outputBufferClass, "data", "Ljava/nio/ByteBuffer;");
    jniContext->width_field = env->GetFieldID(outputBufferClass, "width", "I");
    jniContext->height_field = env->GetFieldID(outputBufferClass, "height", "I");
    jniContext->pts_field = env->GetFieldID(outputBufferClass, "timeUs", "J");


    jniContext->yuvPlanes_field =
            env->GetFieldID(outputBufferClass, "yuvPlanes", "[Ljava/nio/ByteBuffer;");
    jniContext->yuvStrides_field = env->GetFieldID(outputBufferClass, "yuvStrides", "[I");
    jniContext->init_for_private_frame_method =
            env->GetMethodID(outputBufferClass, "initForPrivateFrame", "(II)V");
    jniContext->init_for_yuv_frame_method =
            env->GetMethodID(outputBufferClass, "initForYuvFrame", "(IIIII)Z");
    jniContext->init_method =
            env->GetMethodID(outputBufferClass, "init", "(JILjava/nio/ByteBuffer;)V");

    return jniContext;
}


VIDEO_DECODER_FUNC(jlong, ffmpegInitialize, jstring codecName, jbyteArray extraData, jint threads, jint degree) {
    auto *codec = getCodecByName(env, codecName);
    if (!codec) {
        LOGE("Codec not found.");
        return 0L;
    }

    return (jlong) createVideoContext(env, codec, extraData, threads, degree);
}


VIDEO_DECODER_FUNC(jlong, ffmpegReset, jlong jContext) {
    JniContext *const jniContext = reinterpret_cast<JniContext *>(jContext);
    AVCodecContext *context = jniContext->codecContext;
    if (!context) {
        LOGE("Tried to reset without a context.");
        return 0L;
    }

    avcodec_flush_buffers(context);
    return (jlong) jniContext;
}

VIDEO_DECODER_FUNC(void, ffmpegRelease, jlong jContext) {
    JniContext *const jniContext = reinterpret_cast<JniContext *>(jContext);
    AVCodecContext *context = jniContext->codecContext;
    SwsContext *swsContext = jniContext->swsContext;

    if (context) {
        avcodec_free_context(&context);
        jniContext->codecContext = NULL;
    }

    if (swsContext) {
        sws_freeContext(swsContext);
        jniContext->swsContext = NULL;
    }
}


VIDEO_DECODER_FUNC(jint, ffmpegSendPacket, jlong jContext, jobject encodedData,
                   jint length, jlong inputTimeUs) {
    JniContext *const jniContext = reinterpret_cast<JniContext *>(jContext);
    AVCodecContext *avContext = jniContext->codecContext;

    uint8_t *inputBuffer = (uint8_t *) env->GetDirectBufferAddress(encodedData);
    auto packet = av_packet_alloc();
    packet->data = inputBuffer;
    packet->size = length;
    packet->pts = inputTimeUs;

    int result = 0;
    // Queue input data.
    result = avcodec_send_packet(avContext, packet);
    av_packet_free(&packet);
    if (result) {
        logError("avcodec_send_packet", result);
        if (result == AVERROR_INVALIDDATA) {
            // need more data
            return VIDEO_DECODER_ERROR_INVALID_DATA;
        } else if (result == AVERROR(EAGAIN)) {
            // need read frame
            return VIDEO_DECODER_ERROR_READ_FRAME;
        } else {
            return VIDEO_DECODER_ERROR_OTHER;
        }
    }
    return result;
}

VIDEO_DECODER_FUNC(jint, ffmpegReceiveFrame, jlong jContext, jint outputMode, jobject jOutputBuffer,
                   jboolean decodeOnly) {
    JniContext *const jniContext = reinterpret_cast<JniContext *>(jContext);
    AVCodecContext *avContext = jniContext->codecContext;
    int result = 0;
    AVFrame *raw_frame = av_frame_alloc();
    if (!raw_frame) {
        LOGE("Failed to allocate output frame.");
        return VIDEO_DECODER_ERROR_OTHER;
    }

    result = avcodec_receive_frame(avContext, raw_frame);

    if (decodeOnly || result == AVERROR(EAGAIN)) {
        // This is not an error. The input data was decode-only or no displayable
        // frames are available.
        av_frame_free(&raw_frame);
        return VIDEO_DECODER_ERROR_INVALID_DATA;
    }

    // Some error!
    if (result != 0) {
        av_frame_free(&raw_frame);
        logError("avcodec_receive_frame", result);
        return VIDEO_DECODER_ERROR_OTHER;
    }

    // Use swscale to cvt format to YUV420P
    AVFrame *cvt_frame = cvt_format(jniContext, raw_frame, AV_PIX_FMT_YUV420P, raw_frame->width, raw_frame->height);
    if (cvt_frame == nullptr) {
        av_frame_free(&raw_frame);
        LOGW("Convert To YUV420P failed.");
        return VIDEO_DECODER_ERROR_OTHER;
    }

    // Convert Success! free the raw frame!
    av_frame_free(&raw_frame);

    int width = env->GetIntField(jOutputBuffer, jniContext->width_field);
    int height = env->GetIntField(jOutputBuffer, jniContext->height_field);

    auto dst_width = cvt_frame->width;
    auto dst_height = cvt_frame->height;
    int output_width = dst_width;
    int output_height = dst_height;

    // adjust rotate degree
    if (jniContext->rotate_degree == 90 || jniContext->rotate_degree == 270) {
        output_width = dst_height;
        output_height = dst_width;
    }
    // adjust ColorSpace
    int color_space = cvt_colorspace(cvt_frame->colorspace);

    int stride_y = output_width;
    int stride_uv = (output_width + 1) / 2;

    jboolean init_result = JNI_TRUE;
    if (width != output_width && height != output_height) {
        // init data
        init_result = env->CallBooleanMethod(jOutputBuffer, jniContext->init_for_yuv_frame_method,
                                             output_width, output_height, stride_y, stride_uv, color_space);
        LOGE("init_for_yuv_frame_method! wh [%d,%d], buffer wh [%d,%d]", output_width, output_height, width, height);
    } else {
        env->SetLongField(jOutputBuffer, jniContext->pts_field, cvt_frame->pts);
    }

    if (env->ExceptionCheck()) {
        av_frame_free(&cvt_frame);
        // Exception is thrown in Java when returning from the native call.
        return VIDEO_DECODER_ERROR_OTHER;
    }
    if (!init_result) {
        av_frame_free(&cvt_frame);
        return VIDEO_DECODER_ERROR_OTHER;
    }

    auto data_object = env->GetObjectField(jOutputBuffer, jniContext->data_field);
    auto *data = reinterpret_cast<uint8_t *>(env->GetDirectBufferAddress(data_object));

    const int32_t height_uv = (output_height + 1) / 2;
    const uint64_t length_y = stride_y * output_height;
    const uint64_t length_uv = stride_uv * height_uv;

    // rotate YUV data & copy to OutputBuffer
    libyuv::RotationMode rotate = cvt_rotate(jniContext->rotate_degree);
    libyuv::I420Rotate(
            cvt_frame->data[0], cvt_frame->linesize[0],
            cvt_frame->data[1], cvt_frame->linesize[1],
            cvt_frame->data[2], cvt_frame->linesize[2],
            data, stride_y,
            data + length_y, stride_uv,
            data + length_y + length_uv, stride_uv,
            cvt_frame->width, cvt_frame->height, rotate
    );
    av_frame_free(&cvt_frame);
    return result;
}

VIDEO_DECODER_FUNC(jint, ffmpegRenderFrame, jlong jContext, jobject jSurface,
                   jobject jOutputBuffer, jint displayedWidth, jint displayedHeight) {
    JniContext *const jniContext = reinterpret_cast<JniContext *>(jContext);
    if (!jniContext->MaybeAcquireNativeWindow(env, jSurface)) {
        return VIDEO_DECODER_ERROR_OTHER;
    }

    if (jniContext->native_window_width != displayedWidth ||
        jniContext->native_window_height != displayedHeight) {
        int rst = ANativeWindow_setBuffersGeometry(
                jniContext->native_window,
                displayedWidth,
                displayedHeight,
                kImageFormatYV12);
        if (rst) {
            LOGE("kJniStatusANativeWindowError ANativeWindow_setBuffersGeometry rst [%d]", rst);
            return VIDEO_DECODER_ERROR_OTHER;
        }
        jniContext->native_window_width = displayedWidth;
        jniContext->native_window_height = displayedHeight;
    }

    ANativeWindow_Buffer native_window_buffer;
    int result = ANativeWindow_lock(jniContext->native_window, &native_window_buffer, nullptr);
    if (result == -19) {
        // Surface: dequeueBuffer failed (No such device)
        jniContext->surface = nullptr;
        return VIDEO_DECODER_ERROR_SURFACE;
    } else if (result || native_window_buffer.bits == nullptr) {
        LOGE("kJniStatusANativeWindowError ANativeWindow_lock rst [%d]", result);
        return VIDEO_DECODER_ERROR_OTHER;
    }

    auto data_object = env->GetObjectField(jOutputBuffer, jniContext->data_field);
    auto *data = reinterpret_cast<uint8_t *>(env->GetDirectBufferAddress(data_object));

    auto frame_width = env->GetIntField(jOutputBuffer, jniContext->width_field);
    auto frame_height = env->GetIntField(jOutputBuffer, jniContext->height_field);
    int src_stride_y = frame_width;
    int src_stride_uv = (frame_width + 1) / 2;
    const int32_t height_uv = (frame_height + 1) / 2;
    const uint64_t src_length_y = src_stride_y * frame_height;
    const uint64_t src_length_uv = src_stride_uv * height_uv;

    const int window_y_plane_size = native_window_buffer.stride * native_window_buffer.height;
    const int32_t window_uv_plane_height = (native_window_buffer.height + 1) / 2;
    const int window_uv_plane_stride = AlignTo16(native_window_buffer.stride / 2);
    const int window_v_plane_height = std::min(window_uv_plane_height, native_window_buffer.height);
    const int window_v_plane_size = window_v_plane_height * window_uv_plane_stride;
    const auto window_bits = reinterpret_cast<uint8_t *>(native_window_buffer.bits);

    libyuv::I420Copy(
            data, src_stride_y,
            data + src_length_y, src_stride_uv,
            data + src_length_y + src_length_uv, src_stride_uv,
            window_bits, native_window_buffer.stride,
            window_bits + window_y_plane_size + window_v_plane_size, window_uv_plane_stride,
            window_bits + window_y_plane_size, window_uv_plane_stride,
            native_window_buffer.width, native_window_buffer.height
    );
    int rst = ANativeWindow_unlockAndPost(jniContext->native_window);
    if (rst) {
        LOGE("kJniStatusANativeWindowError ANativeWindow_unlockAndPost rst [%d]", rst);
        return VIDEO_DECODER_ERROR_OTHER;
    }

    return VIDEO_DECODER_SUCCESS;
}


