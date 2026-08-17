#
# Copyright (C) 2016 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

LOCAL_PATH := $(call my-dir)
CONFIG_DIR := $(LOCAL_PATH)/libvpx_android_configs/$(TARGET_ARCH_ABI)
libvpx_source_dir := $(LOCAL_PATH)/libvpx

# generate source file list
libvpx_codec_srcs := $(sort $(shell cat $(CONFIG_DIR)/libvpx_srcs.txt))

ifeq ($(TARGET_ARCH_ABI),arm64-v8a)
  # Filter dotprod and i8mm C files into separate variables.
  libvpx_dotprod_c_srcs := $(filter %_neon_dotprod.c,$(libvpx_codec_srcs))
  libvpx_i8mm_c_srcs := $(filter %_neon_i8mm.c,$(libvpx_codec_srcs))
  libvpx_other_srcs := $(filter-out %_neon_dotprod.c %_neon_i8mm.c,$(libvpx_codec_srcs))
else
  libvpx_other_srcs := $(libvpx_codec_srcs)
endif

ifeq ($(TARGET_ARCH_ABI),arm64-v8a)
include $(CLEAR_VARS)
LOCAL_MODULE := libvpx_dotprod
LOCAL_SRC_FILES := $(addprefix libvpx/, $(libvpx_dotprod_c_srcs))
LOCAL_CFLAGS := -DHAVE_CONFIG_H=vpx_config.h -O3
LOCAL_C_INCLUDES := $(CONFIG_DIR) $(libvpx_source_dir)
LOCAL_CFLAGS += -march=armv8.2-a+dotprod
include $(BUILD_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := libvpx_i8mm
LOCAL_SRC_FILES := $(addprefix libvpx/, $(libvpx_i8mm_c_srcs))
LOCAL_CFLAGS := -DHAVE_CONFIG_H=vpx_config.h -O3
LOCAL_C_INCLUDES := $(CONFIG_DIR) $(libvpx_source_dir)
LOCAL_CFLAGS += -march=armv8.2-a+dotprod+i8mm
include $(BUILD_STATIC_LIBRARY)
endif

include $(CLEAR_VARS)
LOCAL_MODULE := libvpx
LOCAL_CFLAGS := -DHAVE_CONFIG_H=vpx_config.h
LOCAL_ARM_MODE := arm
LOCAL_CFLAGS += -O3

# config specific include should go first to pick up the config specific rtcd.
LOCAL_C_INCLUDES := $(CONFIG_DIR) $(libvpx_source_dir)

LOCAL_SRC_FILES := libvpx_android_configs/$(TARGET_ARCH_ABI)/vpx_config.c
LOCAL_SRC_FILES += $(addprefix libvpx/, $(filter-out vpx_config.c, \
                     $(filter %.c, $(libvpx_other_srcs))))
# include assembly files if they exist
# "%.asm.[sS]" covers neon assembly and "%.asm" covers x86 assembly
LOCAL_SRC_FILES += $(addprefix libvpx/, \
                     $(filter %.asm.s %.asm.S %.asm, $(libvpx_other_srcs)))

ifeq ($(TARGET_ARCH_ABI),arm64-v8a)
  LOCAL_WHOLE_STATIC_LIBRARIES := libvpx_dotprod libvpx_i8mm
endif

ifneq ($(findstring armeabi-v7a, $(TARGET_ARCH_ABI)),)
# append .neon to *_neon.c and *.[sS]
LOCAL_SRC_FILES := $(subst _neon.c,_neon.c.neon,$(LOCAL_SRC_FILES))
LOCAL_SRC_FILES := $(subst .s,.s.neon,$(LOCAL_SRC_FILES))
LOCAL_SRC_FILES := $(subst .S,.S.neon,$(LOCAL_SRC_FILES))
endif

LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/libvpx \
                           $(LOCAL_PATH)/libvpx/vpx

LOCAL_LDFLAGS := -Wl,--version-script=$(CONFIG_DIR)/libvpx.ver
include $(BUILD_SHARED_LIBRARY)
