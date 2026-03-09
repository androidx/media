#!/bin/bash
# 完整解码器列表构建，解码器不可减少。
# 最低 ABI：23。架构：armeabi-v7a, arm64-v8a, x86, x86_64。
# 使用前请按本机修改 NDK_PATH、HOST_PLATFORM。
set -eu
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FFMPEG_MODULE_PATH="$(cd "$SCRIPT_DIR/.." && pwd)"
NDK_PATH="${NDK_PATH:-/Users/attempt/Library/Android/sdk/ndk/27.0.12077973}"
HOST_PLATFORM="${HOST_PLATFORM:-darwin-x86_64}"
ANDROID_ABI="${ANDROID_ABI:-23}"

"$SCRIPT_DIR/build_ffmpeg.sh" "$FFMPEG_MODULE_PATH" "$NDK_PATH" "$HOST_PLATFORM" "$ANDROID_ABI" \
  h263 mpeg4 h264 mpeg1video mpeg2video hevc vp8 vp9 av1 mjpeg flv vc1 theora msmpeg4v1 msmpeg4v2 msmpeg4v3 \
  aac mp1 mp2 mp3 alac vorbis ac3 eac3 flac truehd dca opus pcm_alaw pcm_mulaw amrnb amrwb mlp als wavpack \
  pcm_s16le pcm_s24le pcm_s32le pcm_f32le pcm_s8 pcm_s16be
