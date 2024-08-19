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

LOCAL_PATH := $(WORKING_DIR)/libiamf

include $(CLEAR_VARS)

LOCAL_MODULE := libiamf
LOCAL_ARM_MODE := arm
LOCAL_C_INCLUDES := $(LOCAL_PATH)/code/include \
                    $(LOCAL_PATH)/code/src/iamf_dec \
                    $(LOCAL_PATH)/code/src/common \
                    $(LOCAL_PATH)/code/dep_codecs/include \
                    $(LOCAL_PATH)/code/dep_external/include
LOCAL_SRC_FILES := $(shell find $(LOCAL_PATH)/code/src -name "*.c")
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/code/include \
                    $(LOCAL_PATH)/code/src/iamf_dec \
                    $(LOCAL_PATH)/code/src/common \
                    $(LOCAL_PATH)/code/dep_codecs/include \
                    $(LOCAL_PATH)/code/dep_external/include

include $(BUILD_STATIC_LIBRARY)
