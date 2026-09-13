#include "ggml.h"
#include "gameocr_logging.h"

extern const char * expensive_log_argument();

// The Release object must not reference the argument function or liblog, even
// at -O0. Debug must still reference upstream logging and formatting arguments.
extern "C" void gameocr_logging_smoke(enum ggml_log_level level) {
    LOGv("%s", expensive_log_argument());
    LOGd("%s", expensive_log_argument());
    LOGi("%s", expensive_log_argument());
    LOGw("%s", expensive_log_argument());
    LOGe("%s", expensive_log_argument());
    aichat_android_log_callback(level, "callback diagnostic", nullptr);
}
