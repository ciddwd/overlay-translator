#include <algorithm>
#include <android/log.h>
#include <cctype>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <string>
#include <unistd.h>

// ai_chat.cpp is compiled with symbol redirects so model and context creation reach this
// adapter. Undefine them here to call llama.cpp's real implementations.
#ifdef llama_model_load_from_file
#undef llama_model_load_from_file
#endif
#ifdef llama_init_from_model
#undef llama_init_from_model
#endif
#ifdef common_sampler_init
#undef common_sampler_init
#endif
#include "common.h"
#include "ggml-backend.h"
#include "llama.h"
#include "sampling.h"

namespace {

ggml_backend_dev_t no_offload_devices[] = {nullptr};

int select_inference_threads(const int available_processors) {
    const int processors = std::max(1, available_processors);
    if (processors == 1) return 1;
    if (processors <= 4) return 2;
    if (processors <= 6) return 4;
    return 6;
}

int thread_value_from_environment(
        const char * name,
        const int fallback,
        const int maximum) {
    const char * raw_value = std::getenv(name);
    if (raw_value == nullptr) return fallback;

    char * end = nullptr;
    const long value = std::strtol(raw_value, &end, 10);
    if (end != raw_value && *end == '\0' && value >= 1 && value <= maximum) {
        return static_cast<int>(value);
    }
    __android_log_print(
            ANDROID_LOG_WARN,
            "LocalLlmPerf",
            "invalid thread environment %s=%s; keeping %d",
            name,
            raw_value,
            fallback);
    return fallback;
}

bool requests_vulkan() {
    const char * value = std::getenv("GAMEOCR_VULKAN_OFFLOAD");
    return value != nullptr && std::strcmp(value, "1") == 0;
}

bool has_vulkan_backend() {
    for (size_t index = 0; index < ggml_backend_reg_count(); ++index) {
        const ggml_backend_reg_t backend = ggml_backend_reg_get(index);
        const char * raw_name = backend == nullptr ? nullptr : ggml_backend_reg_name(backend);
        if (raw_name == nullptr) continue;

        std::string name(raw_name);
        std::transform(name.begin(), name.end(), name.begin(), [](const unsigned char ch) {
            return static_cast<char>(std::tolower(ch));
        });
        if (name.find("vulkan") != std::string::npos) return true;
    }
    return false;
}

void configure_model_acceleration(llama_model_params & params, const bool use_vulkan) {
    params.n_gpu_layers = use_vulkan ? -1 : 0;
    // llama.cpp treats a null devices pointer as "all available devices". An
    // explicit empty, null-terminated list is required to keep CPU mode from
    // adding Vulkan to the context scheduler.
    params.devices = use_vulkan ? nullptr : no_offload_devices;
}

float sampler_value_from_environment(
        const char * name,
        const float fallback,
        const float minimum,
        const float maximum) {
    const char * raw_value = std::getenv(name);
    if (raw_value == nullptr) return fallback;

    char * end = nullptr;
    const float value = std::strtof(raw_value, &end);
    if (end != raw_value && *end == '\0' && std::isfinite(value) &&
            value >= minimum && value <= maximum) {
        return value;
    }
    __android_log_print(
            ANDROID_LOG_WARN,
            "LocalLlmPerf",
            "invalid sampler environment %s=%s; keeping %.2f",
            name,
            raw_value,
            fallback);
    return fallback;
}

}  // namespace

common_sampler * gameocr_common_sampler_init(
        const llama_model * model,
        common_params_sampling & params) {
    params.temp = sampler_value_from_environment(
            "GAMEOCR_SAMPLER_TEMPERATURE", params.temp, 0.0f, 2.0f);
    params.top_p = sampler_value_from_environment(
            "GAMEOCR_SAMPLER_TOP_P", params.top_p, 0.0f, 1.0f);
    params.penalty_freq = sampler_value_from_environment(
            "GAMEOCR_SAMPLER_FREQUENCY_PENALTY", params.penalty_freq, 0.0f, 2.0f);
    __android_log_print(
            ANDROID_LOG_INFO,
            "LocalLlmPerf",
            "sampler native temperature=%.2f topP=%.2f frequencyPenalty=%.2f repeatPenalty=%.2f",
            params.temp,
            params.top_p,
            params.penalty_freq,
            params.penalty_repeat);
    return common_sampler_init(model, params);
}

extern "C" llama_model * gameocr_llama_model_load_from_file(
        const char * path_model,
        llama_model_params params) {
    const bool requested = requests_vulkan();
    const bool available = has_vulkan_backend();
    const bool use_vulkan = requested && available;
    configure_model_acceleration(params, use_vulkan);

    __android_log_print(
            ANDROID_LOG_INFO,
            "LocalLlmPerf",
            "acceleration native requestedVulkan=%s backendAvailable=%s nGpuLayers=%d devices=%s",
            requested ? "true" : "false",
            available ? "true" : "false",
            params.n_gpu_layers,
            use_vulkan ? "all" : "none");

    llama_model * model = llama_model_load_from_file(path_model, params);
    if (model != nullptr || !use_vulkan) return model;

    __android_log_print(
            ANDROID_LOG_WARN,
            "LocalLlmPerf",
            "Vulkan model load failed; retrying on CPU");
    configure_model_acceleration(params, false);
    model = llama_model_load_from_file(path_model, params);
    __android_log_print(
            model == nullptr ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO,
            "LocalLlmPerf",
            "CPU fallback model load success=%s",
            model != nullptr ? "true" : "false");
    return model;
}

extern "C" llama_context * gameocr_llama_init_from_model(
        llama_model * model,
        llama_context_params params) {
    const long detected = sysconf(_SC_NPROCESSORS_ONLN);
    const int available_processors = detected > 0 ? static_cast<int>(detected) : 1;
    const int selected_threads = select_inference_threads(available_processors);
    const int generation_threads = thread_value_from_environment(
            "GAMEOCR_GENERATION_THREADS", selected_threads, selected_threads);
    const int prompt_threads = thread_value_from_environment(
            "GAMEOCR_PROMPT_THREADS", selected_threads, selected_threads);
    const int upstream_generation_threads = params.n_threads;
    const int upstream_prompt_threads = params.n_threads_batch;

    params.n_threads = generation_threads;
    params.n_threads_batch = prompt_threads;
    // Sequence 0 owns the reusable system prompt; 1..8 are independent translations.
    // Unified KV preserves the full per-sequence context while sharing the system prefix.
    params.n_seq_max = 9;
    params.kv_unified = true;
    __android_log_print(
            ANDROID_LOG_INFO,
            "LocalLlmPerf",
            "thread policy native availableProcessors=%d upstreamTG=%d upstreamPP=%d "
            "TG=%d PP=%d max=6 nSeqMax=%u kvUnified=%s",
            available_processors,
            upstream_generation_threads,
            upstream_prompt_threads,
            generation_threads,
            prompt_threads,
            params.n_seq_max,
            params.kv_unified ? "true" : "false");
    return llama_init_from_model(model, params);
}
