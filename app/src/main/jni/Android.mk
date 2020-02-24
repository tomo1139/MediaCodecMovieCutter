LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE    := resampler
LOCAL_SRC_FILES := resampler.c

include $(BUILD_SHARED_LIBRARY)