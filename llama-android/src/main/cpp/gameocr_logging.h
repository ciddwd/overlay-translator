#pragma once

// A build-time boundary, not an Android log-priority default: system properties
// must not be able to turn Release diagnostics back on.
#ifndef GAMEOCR_NATIVE_LOGCAT
#define GAMEOCR_NATIVE_LOGCAT 0
#endif

#if GAMEOCR_NATIVE_LOGCAT
#include "logging.h"
#else
#include "ggml.h"

// Do not evaluate formatting arguments in Release, even without optimization.
#define LOGv(...) ((void)0)
#define LOGd(...) ((void)0)
#define LOGi(...) ((void)0)
#define LOGw(...) ((void)0)
#define LOGe(...) ((void)0)

// Passing nullptr would restore the upstream default stderr logger. Retain an
// explicit no-op callback; errors still propagate through normal JNI results.
static inline void aichat_android_log_callback(
        enum ggml_log_level /*level*/, const char * /*text*/, void * /*user*/) {}
#endif
