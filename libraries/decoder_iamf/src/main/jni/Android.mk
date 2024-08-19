#
# Copyright (C) 2024 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
WORKING_DIR := $(call my-dir)
include $(CLEAR_VARS)

# build libiamf.a
LOCAL_PATH := $(WORKING_DIR)
include libiamf.mk

# build libiamfJNI.so
include $(CLEAR_VARS)
LOCAL_PATH := $(WORKING_DIR)
LOCAL_MODULE := libiamfJNI
LOCAL_ARM_MODE := arm
LOCAL_CPP_EXTENSION := .cc
LOCAL_SRC_FILES := iamf_jni.cc
LOCAL_LDLIBS := -llog -lz -lm
LOCAL_STATIC_LIBRARIES := libiamf
include $(BUILD_SHARED_LIBRARY)
