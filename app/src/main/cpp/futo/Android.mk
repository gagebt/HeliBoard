LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := futo_trie
LOCAL_SRC_FILES := futo_trie.cpp
LOCAL_CPPFLAGS := -std=c++17 -Wall -Wextra -Werror
include $(BUILD_SHARED_LIBRARY)
