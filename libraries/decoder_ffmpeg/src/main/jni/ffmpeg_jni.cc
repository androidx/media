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
#include <cstring>
#include <new>

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
#include <android/native_window.h>
#include <android/native_window_jni.h>
}

#define LOG_TAG "ffmpeg_jni"
#define LOGE(...) \
  ((void)__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__))
#define LOGD(...) \
  ((void)__android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__))

#define LIBRARY_FUNC(RETURN_TYPE, NAME, ...)                               \
  extern "C" {                                                             \
  JNIEXPORT RETURN_TYPE                                                    \
  Java_androidx_media3_decoder_ffmpeg_FfmpegLibrary_##NAME(JNIEnv* env,    \
                                                           jobject thiz,   \
                                                           ##__VA_ARGS__); \
  }                                                                        \
  JNIEXPORT RETURN_TYPE                                                    \
  Java_androidx_media3_decoder_ffmpeg_FfmpegLibrary_##NAME(                \
      JNIEnv* env, jobject thiz, ##__VA_ARGS__)

#define AUDIO_DECODER_FUNC(RETURN_TYPE, NAME, ...)               \
  extern "C" {                                                   \
  JNIEXPORT RETURN_TYPE                                          \
  Java_androidx_media3_decoder_ffmpeg_FfmpegAudioDecoder_##NAME( \
      JNIEnv* env, jobject thiz, ##__VA_ARGS__);                 \
  }                                                              \
  JNIEXPORT RETURN_TYPE                                          \
  Java_androidx_media3_decoder_ffmpeg_FfmpegAudioDecoder_##NAME( \
      JNIEnv* env, jobject thiz, ##__VA_ARGS__)

#define VIDEO_DECODER_FUNC(RETURN_TYPE, NAME, ...)                        \
  extern "C" {                                                             \
  JNIEXPORT RETURN_TYPE                                                   \
  Java_androidx_media3_decoder_ffmpeg_ExperimentalFfmpegVideoDecoder_##NAME( \
      JNIEnv* env, jobject thiz, ##__VA_ARGS__);                          \
  }                                                                       \
  JNIEXPORT RETURN_TYPE                                                   \
  Java_androidx_media3_decoder_ffmpeg_ExperimentalFfmpegVideoDecoder_##NAME( \
      JNIEnv* env, jobject thiz, ##__VA_ARGS__)

#define ERROR_STRING_BUFFER_LENGTH 256

// Output format corresponding to AudioFormat.ENCODING_PCM_16BIT.
static const AVSampleFormat OUTPUT_FORMAT_PCM_16BIT = AV_SAMPLE_FMT_S16;
// Output format corresponding to AudioFormat.ENCODING_PCM_FLOAT.
static const AVSampleFormat OUTPUT_FORMAT_PCM_FLOAT = AV_SAMPLE_FMT_FLT;

// LINT.IfChange
static const int AUDIO_DECODER_ERROR_INVALID_DATA = -1;
static const int AUDIO_DECODER_ERROR_OTHER = -2;
// LINT.ThenChange(../java/androidx/media3/decoder/ffmpeg/FfmpegAudioDecoder.java)

static jmethodID growOutputBufferMethod;

/**
 * Returns the AVCodec with the specified name, or NULL if it is not available.
 */
const AVCodec* getCodecByName(JNIEnv* env, jstring codecName);

/**
 * Allocates and opens a new AVCodecContext for the specified codec, passing the
 * provided extraData as initialization data for the decoder if it is non-NULL.
 * Returns the created context.
 */
AVCodecContext* createContext(JNIEnv* env, const AVCodec* codec,
                              jbyteArray extraData, jboolean outputFloat,
                              jint rawSampleRate, jint rawChannelCount);

struct GrowOutputBufferCallback {
  uint8_t* operator()(int requiredSize) const;

  JNIEnv* env;
  jobject thiz;
  jobject decoderOutputBuffer;
};

/**
 * Decodes the packet into the output buffer, returning the number of bytes
 * written, or a negative AUDIO_DECODER_ERROR constant value in the case of an
 * error.
 */
int decodePacket(AVCodecContext* context, AVPacket* packet,
                 uint8_t* outputBuffer, int outputSize,
                 GrowOutputBufferCallback growBuffer);

/**
 * Transforms ffmpeg AVERROR into a negative AUDIO_DECODER_ERROR constant value.
 */
int transformError(int errorNumber);

/**
 * Outputs a log message describing the avcodec error number.
 */
void logError(const char* functionName, int errorNumber);

/**
 * Releases the specified context.
 */
void releaseContext(AVCodecContext* context);

jint JNI_OnLoad(JavaVM* vm, void* reserved) {
  JNIEnv* env;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
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
  const AVCodec* codec = getCodecByName(env, codecName);
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
  uint8_t* inputBuffer = (uint8_t*)env->GetDirectBufferAddress(inputData);
  uint8_t* outputBuffer = (uint8_t*)env->GetDirectBufferAddress(outputData);
  AVPacket* packet = av_packet_alloc();
  if (!packet) {
    LOGE("Failed to allocate packet.");
    return -1;
  }
  packet->data = inputBuffer;
  packet->size = inputSize;
  const int ret =
      decodePacket((AVCodecContext*)context, packet, outputBuffer, outputSize,
                   GrowOutputBufferCallback{env, thiz, decoderOutputBuffer});
  av_packet_free(&packet);
  return ret;
}

uint8_t* GrowOutputBufferCallback::operator()(int requiredSize) const {
  jobject newOutputData = env->CallObjectMethod(
      thiz, growOutputBufferMethod, decoderOutputBuffer, requiredSize);
  if (env->ExceptionCheck()) {
    LOGE("growOutputBuffer() failed");
    env->ExceptionDescribe();
    return nullptr;
  }
  return static_cast<uint8_t*>(env->GetDirectBufferAddress(newOutputData));
}

AUDIO_DECODER_FUNC(jint, ffmpegGetChannelCount, jlong context) {
  if (!context) {
    LOGE("Context must be non-NULL.");
    return -1;
  }
  return ((AVCodecContext*)context)->ch_layout.nb_channels;
}

AUDIO_DECODER_FUNC(jint, ffmpegGetSampleRate, jlong context) {
  if (!context) {
    LOGE("Context must be non-NULL.");
    return -1;
  }
  return ((AVCodecContext*)context)->sample_rate;
}

AUDIO_DECODER_FUNC(jlong, ffmpegReset, jlong jContext, jbyteArray extraData) {
  AVCodecContext* context = (AVCodecContext*)jContext;
  if (!context) {
    LOGE("Tried to reset without a context.");
    return 0L;
  }

  AVCodecID codecId = context->codec_id;
  if (codecId == AV_CODEC_ID_TRUEHD) {
    jboolean outputFloat =
        (jboolean)(context->request_sample_fmt == OUTPUT_FORMAT_PCM_FLOAT);
    // Release and recreate the context if the codec is TrueHD.
    // TODO: Figure out why flushing doesn't work for this codec.
    releaseContext(context);
    const AVCodec* codec = avcodec_find_decoder(codecId);
    if (!codec) {
      LOGE("Unexpected error finding codec %d.", codecId);
      return 0L;
    }
    return (jlong)createContext(env, codec, extraData, outputFloat,
                                /* rawSampleRate= */ -1,
                                /* rawChannelCount= */ -1);
  }

  avcodec_flush_buffers(context);
  return (jlong)context;
}

AUDIO_DECODER_FUNC(void, ffmpegRelease, jlong context) {
  if (context) {
    releaseContext((AVCodecContext*)context);
  }
}

const AVCodec* getCodecByName(JNIEnv* env, jstring codecName) {
  if (!codecName) {
    return NULL;
  }
  const char* codecNameChars = env->GetStringUTFChars(codecName, NULL);
  const AVCodec* codec = avcodec_find_decoder_by_name(codecNameChars);
  env->ReleaseStringUTFChars(codecName, codecNameChars);
  return codec;
}

AVCodecContext* createContext(JNIEnv* env, const AVCodec* codec,
                              jbyteArray extraData, jboolean outputFloat,
                              jint rawSampleRate, jint rawChannelCount) {
  AVCodecContext* context = avcodec_alloc_context3(codec);
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
        (uint8_t*)av_malloc(size + AV_INPUT_BUFFER_PADDING_SIZE);
    if (!context->extradata) {
      LOGE("Failed to allocate extradata.");
      releaseContext(context);
      return NULL;
    }
    env->GetByteArrayRegion(extraData, 0, size, (jbyte*)context->extradata);
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

int decodePacket(AVCodecContext* context, AVPacket* packet,
                 uint8_t* outputBuffer, int outputSize,
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
    AVFrame* frame = av_frame_alloc();
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
    SwrContext* resampleContext = static_cast<SwrContext*>(context->opaque);
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
                         (const uint8_t**)frame->data, frame->nb_samples);
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

void logError(const char* functionName, int errorNumber) {
  char* buffer = (char*)malloc(ERROR_STRING_BUFFER_LENGTH * sizeof(char));
  av_strerror(errorNumber, buffer, ERROR_STRING_BUFFER_LENGTH);
  LOGE("Error in %s: %s", functionName, buffer);
  free(buffer);
}

void releaseContext(AVCodecContext* context) {
  if (!context) {
    return;
  }
  SwrContext* swrContext;
  if ((swrContext = (SwrContext*)context->opaque)) {
    swr_free(&swrContext);
    context->opaque = NULL;
  }
  avcodec_free_context(&context);
}

// ============== Video decoder (ExperimentalFfmpegVideoDecoder) ==============
// LINT.IfChange
static const int VIDEO_DECODER_SUCCESS = 0;
static const int VIDEO_DECODER_ERROR_INVALID_DATA = -1;
static const int VIDEO_DECODER_ERROR_OTHER = -2;
static const int VIDEO_DECODER_ERROR_READ_FRAME = -3;
static const int VIDEO_DECODER_ERROR_SURFACE = -4;
// LINT.ThenChange(../java/androidx/media3/decoder/ffmpeg/ExperimentalFfmpegVideoDecoder.java)

static const int kImageFormatYV12 = 0x32315659;

struct VideoJniContext {
  AVCodecContext* codecContext = nullptr;
  SwsContext* swsContext = nullptr;
  ANativeWindow* nativeWindow = nullptr;
  jobject surface = nullptr;
  int rotateDegree = 0;
  int nativeWindowWidth = 0;
  int nativeWindowHeight = 0;
  jfieldID dataField = nullptr;
  jfieldID widthField = nullptr;
  jfieldID heightField = nullptr;
  jfieldID ptsField = nullptr;
  jfieldID yuvPlanesField = nullptr;
  jfieldID yuvStridesField = nullptr;
  jmethodID initForYuvFrameMethod = nullptr;
  jmethodID initMethod = nullptr;

  ~VideoJniContext() {
    if (nativeWindow) {
      ANativeWindow_release(nativeWindow);
      nativeWindow = nullptr;
    }
    if (codecContext) {
      avcodec_free_context(&codecContext);
      codecContext = nullptr;
    }
    if (swsContext) {
      sws_freeContext(swsContext);
      swsContext = nullptr;
    }
  }

  bool maybeAcquireNativeWindow(JNIEnv* env, jobject newSurface) {
    if (surface == newSurface) return true;
    if (nativeWindow) {
      ANativeWindow_release(nativeWindow);
      nativeWindow = nullptr;
    }
    nativeWindowWidth = 0;
    nativeWindowHeight = 0;
    nativeWindow = ANativeWindow_fromSurface(env, newSurface);
    if (!nativeWindow) {
      LOGE("ANativeWindow_fromSurface failed");
      surface = nullptr;
      return false;
    }
    surface = newSurface;
    return true;
  }
};

static int cvtColorspace(AVColorSpace colorSpace) {
  switch (colorSpace) {
    case AVCOL_SPC_BT470BG:
    case AVCOL_SPC_SMPTE170M:
    case AVCOL_SPC_SMPTE240M:
      return 1;
    case AVCOL_SPC_BT709:
      return 2;
    case AVCOL_SPC_BT2020_NCL:
    case AVCOL_SPC_BT2020_CL:
      return 3;
    default:
      return 0;
  }
}

static AVFrame* cvtFormat(VideoJniContext* ctx, AVFrame* src,
                          AVPixelFormat dstFormat, int dstWidth, int dstHeight) {
  AVPixelFormat srcFormat = (AVPixelFormat)src->format;
  ctx->swsContext = sws_getCachedContext(ctx->swsContext,
      src->width, src->height, srcFormat,
      dstWidth, dstHeight, dstFormat,
      SWS_FAST_BILINEAR, nullptr, nullptr, nullptr);
  if (!ctx->swsContext) {
    LOGE("sws_getCachedContext failed");
    return nullptr;
  }
  AVFrame* dst = av_frame_alloc();
  if (!dst) return nullptr;
  av_frame_copy_props(dst, src);
  dst->width = dstWidth;
  dst->height = dstHeight;
  dst->format = dstFormat;
  if (av_frame_get_buffer(dst, 0) != 0) {
    av_frame_free(&dst);
    return nullptr;
  }
  sws_scale(ctx->swsContext, src->data, src->linesize, 0, src->height,
            dst->data, dst->linesize);
  return dst;
}

static VideoJniContext* createVideoContext(JNIEnv* env, const AVCodec* codec,
    jbyteArray extraData, jint threads, jint degree, jint width, jint height) {
  VideoJniContext* ctx = new (std::nothrow) VideoJniContext();
  if (!ctx) return nullptr;

  ctx->codecContext = avcodec_alloc_context3(codec);
  if (!ctx->codecContext) {
    LOGE("Failed to allocate codec context");
    delete ctx;
    return nullptr;
  }
  ctx->rotateDegree = degree;

  if (extraData && env->GetArrayLength(extraData) > 0) {
    jsize size = env->GetArrayLength(extraData);
    ctx->codecContext->extradata_size = size;
    ctx->codecContext->extradata = (uint8_t*)av_malloc(size + AV_INPUT_BUFFER_PADDING_SIZE);
    if (!ctx->codecContext->extradata) {
      avcodec_free_context(&ctx->codecContext);
      delete ctx;
      return nullptr;
    }
    env->GetByteArrayRegion(extraData, 0, size, (jbyte*)ctx->codecContext->extradata);
  }

  ctx->codecContext->skip_loop_filter = AVDISCARD_ALL;
  ctx->codecContext->skip_frame = AVDISCARD_DEFAULT;
  ctx->codecContext->thread_count = threads;
  ctx->codecContext->thread_type = FF_THREAD_FRAME | FF_THREAD_SLICE;
  ctx->codecContext->err_recognition = AV_EF_IGNORE_ERR;
  if (width > 0 && height > 0) {
    ctx->codecContext->width = width;
    ctx->codecContext->height = height;
  }

  AVDictionary* opts = nullptr;
  if (codec->id == AV_CODEC_ID_AV1) {
    av_dict_set(&opts, "max_frame_delay", "1", 0);
  }
  int result = avcodec_open2(ctx->codecContext, codec, &opts);
  av_dict_free(&opts);
  if (result < 0) {
    logError("avcodec_open2", result);
    avcodec_free_context(&ctx->codecContext);
    delete ctx;
    return nullptr;
  }

  jclass outputBufferClass = env->FindClass("androidx/media3/decoder/VideoDecoderOutputBuffer");
  if (!outputBufferClass) { delete ctx; return nullptr; }
  ctx->dataField = env->GetFieldID(outputBufferClass, "data", "Ljava/nio/ByteBuffer;");
  ctx->widthField = env->GetFieldID(outputBufferClass, "width", "I");
  ctx->heightField = env->GetFieldID(outputBufferClass, "height", "I");
  ctx->ptsField = env->GetFieldID(outputBufferClass, "timeUs", "J");
  ctx->yuvPlanesField = env->GetFieldID(outputBufferClass, "yuvPlanes", "[Ljava/nio/ByteBuffer;");
  ctx->yuvStridesField = env->GetFieldID(outputBufferClass, "yuvStrides", "[I");
  ctx->initForYuvFrameMethod = env->GetMethodID(outputBufferClass, "initForYuvFrame", "(IIIII)Z");
  ctx->initMethod = env->GetMethodID(outputBufferClass, "init", "(JILjava/nio/ByteBuffer;)V");
  if (!ctx->dataField || !ctx->widthField || !ctx->heightField || !ctx->ptsField ||
      !ctx->initForYuvFrameMethod || !ctx->initMethod) {
    delete ctx;
    return nullptr;
  }
  return ctx;
}

VIDEO_DECODER_FUNC(jlong, ffmpegInitialize, jstring codecName, jbyteArray extraData,
                  jint threads, jint degree, jint width, jint height) {
  const AVCodec* codec = getCodecByName(env, codecName);
  if (!codec) {
    LOGE("Video codec not found");
    return 0L;
  }
  return (jlong)createVideoContext(env, codec, extraData, threads, degree, width, height);
}

VIDEO_DECODER_FUNC(jlong, ffmpegReset, jlong jContext) {
  VideoJniContext* ctx = (VideoJniContext*)jContext;
  if (!ctx || !ctx->codecContext) return (jlong)ctx;
  avcodec_flush_buffers(ctx->codecContext);
  return (jlong)ctx;
}

VIDEO_DECODER_FUNC(void, ffmpegRelease, jlong jContext) {
  if (jContext) {
    VideoJniContext* ctx = (VideoJniContext*)jContext;
    delete ctx;
  }
}

VIDEO_DECODER_FUNC(jint, ffmpegSendPacket, jlong jContext, jobject encodedData,
                   jint length, jlong inputTimeUs) {
  VideoJniContext* ctx = (VideoJniContext*)jContext;
  if (!ctx || !ctx->codecContext) return VIDEO_DECODER_ERROR_OTHER;

  uint8_t* inputBuffer = (uint8_t*)env->GetDirectBufferAddress(encodedData);
  AVPacket* packet = av_packet_alloc();
  if (!packet) return VIDEO_DECODER_ERROR_OTHER;
  packet->data = inputBuffer;
  packet->size = length;
  packet->pts = inputTimeUs;

  int result = avcodec_send_packet(ctx->codecContext, packet);
  av_packet_free(&packet);
  if (result == AVERROR_INVALIDDATA) return VIDEO_DECODER_ERROR_INVALID_DATA;
  if (result == AVERROR(EAGAIN)) return VIDEO_DECODER_ERROR_READ_FRAME;
  if (result != 0) {
    logError("avcodec_send_packet", result);
    return VIDEO_DECODER_ERROR_OTHER;
  }
  return VIDEO_DECODER_SUCCESS;
}

VIDEO_DECODER_FUNC(jint, ffmpegReceiveFrame, jlong jContext, jint outputMode,
                  jobject jOutputBuffer, jboolean decodeOnly) {
  VideoJniContext* ctx = (VideoJniContext*)jContext;
  if (!ctx || !ctx->codecContext) return VIDEO_DECODER_ERROR_OTHER;

  AVFrame* rawFrame = av_frame_alloc();
  if (!rawFrame) return VIDEO_DECODER_ERROR_OTHER;

  int result = avcodec_receive_frame(ctx->codecContext, rawFrame);
  if (result == AVERROR(EAGAIN)) {
    av_frame_free(&rawFrame);
    return VIDEO_DECODER_ERROR_READ_FRAME;
  }
  if (result != 0) {
    av_frame_free(&rawFrame);
    logError("avcodec_receive_frame", result);
    return VIDEO_DECODER_ERROR_OTHER;
  }
  if (decodeOnly) {
    av_frame_free(&rawFrame);
    return VIDEO_DECODER_ERROR_INVALID_DATA;
  }

  AVFrame* cvtFrame = cvtFormat(ctx, rawFrame, AV_PIX_FMT_YUV420P,
                               rawFrame->width, rawFrame->height);
  av_frame_free(&rawFrame);
  if (!cvtFrame) return VIDEO_DECODER_ERROR_OTHER;

  int width = cvtFrame->width;
  int height = cvtFrame->height;
  int strideY = width;
  int strideUv = (width + 1) / 2;
  int colorspace = cvtColorspace(cvtFrame->colorspace);
  if (colorspace == 0 && ctx->codecContext->codec_id == AV_CODEC_ID_HEVC) {
    colorspace = 3; /* BT.2020 for HEVC/Dolby Vision when unspecified */
  }

  jboolean initResult = env->CallBooleanMethod(jOutputBuffer, ctx->initForYuvFrameMethod,
      width, height, strideY, strideUv, colorspace);
  if (env->ExceptionCheck() || !initResult) {
    av_frame_free(&cvtFrame);
    return VIDEO_DECODER_ERROR_OTHER;
  }

  jobject dataObj = env->GetObjectField(jOutputBuffer, ctx->dataField);
  uint8_t* data = (uint8_t*)env->GetDirectBufferAddress(dataObj);
  if (!data) {
    av_frame_free(&cvtFrame);
    return VIDEO_DECODER_ERROR_OTHER;
  }

  env->SetLongField(jOutputBuffer, ctx->ptsField, (jlong)cvtFrame->pts);

  size_t lenY = (size_t)strideY * height;
  size_t lenUv = (size_t)strideUv * ((height + 1) / 2);
  memcpy(data, cvtFrame->data[0], lenY);
  memcpy(data + lenY, cvtFrame->data[1], lenUv);
  memcpy(data + lenY + lenUv, cvtFrame->data[2], lenUv);
  av_frame_free(&cvtFrame);
  return VIDEO_DECODER_SUCCESS;
}

static const int AlignTo16(int value) { return (value + 15) & ~15; }

VIDEO_DECODER_FUNC(jint, ffmpegRenderFrame, jlong jContext, jobject jSurface,
                  jobject jOutputBuffer, jint displayedWidth, jint displayedHeight) {
  VideoJniContext* ctx = (VideoJniContext*)jContext;
  if (!ctx) return VIDEO_DECODER_ERROR_OTHER;
  if (!ctx->maybeAcquireNativeWindow(env, jSurface)) return VIDEO_DECODER_ERROR_OTHER;

  if (ctx->nativeWindowWidth != displayedWidth || ctx->nativeWindowHeight != displayedHeight) {
    if (ANativeWindow_setBuffersGeometry(ctx->nativeWindow, displayedWidth, displayedHeight,
                                        kImageFormatYV12) != 0) {
      LOGE("ANativeWindow_setBuffersGeometry failed");
      return VIDEO_DECODER_ERROR_OTHER;
    }
    ctx->nativeWindowWidth = displayedWidth;
    ctx->nativeWindowHeight = displayedHeight;
  }

  ANativeWindow_Buffer buffer;
  if (ANativeWindow_lock(ctx->nativeWindow, &buffer, nullptr) != 0 || !buffer.bits) {
    LOGE("ANativeWindow_lock failed");
    return VIDEO_DECODER_ERROR_OTHER;
  }

  jobject dataObj = env->GetObjectField(jOutputBuffer, ctx->dataField);
  uint8_t* src = (uint8_t*)env->GetDirectBufferAddress(dataObj);
  int frameWidth = env->GetIntField(jOutputBuffer, ctx->widthField);
  int frameHeight = env->GetIntField(jOutputBuffer, ctx->heightField);
  int srcStrideY = frameWidth;
  int srcStrideUv = (frameWidth + 1) / 2;
  int srcHeightUv = (frameHeight + 1) / 2;
  size_t srcLenY = (size_t)srcStrideY * frameHeight;
  size_t srcLenUv = (size_t)srcStrideUv * srcHeightUv;

  uint8_t* dst = (uint8_t*)buffer.bits;
  int dstStride = buffer.stride;
  int dstHeightUv = (buffer.height + 1) / 2;
  int dstStrideUv = AlignTo16(dstStride / 2);
  size_t dstLenY = (size_t)dstStride * buffer.height;
  size_t dstLenUv = (size_t)dstStrideUv * dstHeightUv;

  for (int y = 0; y < frameHeight && y < buffer.height; ++y)
    memcpy(dst + y * dstStride, src + y * srcStrideY, (size_t)srcStrideY);
  for (int y = 0; y < srcHeightUv && y < dstHeightUv; ++y) {
    memcpy(dst + dstLenY + dstLenUv + y * dstStrideUv, src + srcLenY + y * srcStrideUv, (size_t)srcStrideUv);
    memcpy(dst + dstLenY + y * dstStrideUv, src + srcLenY + srcLenUv + y * srcStrideUv, (size_t)srcStrideUv);
  }

  if (ANativeWindow_unlockAndPost(ctx->nativeWindow) != 0) {
    LOGE("ANativeWindow_unlockAndPost failed");
    return VIDEO_DECODER_ERROR_OTHER;
  }
  return VIDEO_DECODER_SUCCESS;
}
