#include <algorithm>
#include <android/log.h>
#include <cctype>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <string>
#include <stdexcept>
#include <vector>
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
#include "gameocr_logging.h"
#include "sampling.h"

#ifdef common_chat_format_single
#undef common_chat_format_single
#endif
#include "chat.h"
#include "nlohmann/json.hpp"

// TranslateGemma's template accepts a typed content item with language codes. The normal
// common_chat_msg only stores text; Jinja kwargs let us pass that typed message without
// replacing the GGUF template or modifying the llama.cpp submodule.
common_chat_params gameocr_common_chat_templates_apply(
        const common_chat_templates * tmpls, common_chat_templates_inputs inputs) {
    const char * profile = std::getenv("GAMEOCR_TRANSLATEGEMMA");
    std::string requirements;
    if (profile != nullptr && std::strcmp(profile, "1") == 0) {
        nlohmann::ordered_json messages = nlohmann::ordered_json::array();
        for (const auto & msg : inputs.messages) {
            if (msg.role != "user") {
                messages.push_back({{"role", msg.role}, {"content", msg.content}});
                continue;
            }
            const auto content = nlohmann::ordered_json::parse(msg.content);
            messages.push_back({{"role", "user"}, {"content", nlohmann::ordered_json::array({{
                    {"type", "text"},
                    {"source_lang_code", content.at("source_lang_code")},
                    {"target_lang_code", content.at("target_lang_code")},
                    {"text", content.at("text")},
            }})}});
            requirements = content.value("requirements", std::string());
        }
        inputs.chat_template_kwargs["messages"] = messages.dump();
        // Differential parser discovery uses synthetic plain-string user messages, which
        // this translation-only template rejects. Its output is pure translation content.
        inputs.force_pure_content = true;
    }
    auto result = common_chat_templates_apply(tmpls, inputs);
    if (!requirements.empty()) {
        // Keep glossary/background and unlocked requirements outside the text to translate.
        bool inserted = false;
        for (auto & part : result.prompt.parts) {
            const auto boundary = part.text.find("\nProduce only the ");
            if (!part.is_input && boundary != std::string::npos) {
                part.text.insert(boundary, "\n" + requirements + "\n");
                inserted = true;
                break;
            }
        }
        if (!inserted) {
            throw std::runtime_error("TranslateGemma template has no instruction boundary");
        }
    }
    return result;
}

// Template-driven presets use their GGUF Jinja template with thinking disabled.
// All call sites (generation and token budgeting) share this adapter.
std::string gameocr_common_chat_format_single(
        const common_chat_templates * tmpls,
        const std::vector<common_chat_msg> & past_msg,
        const common_chat_msg & new_msg,
        bool add_ass,
        bool use_jinja) {
    const char * enabled = std::getenv("GAMEOCR_GGUF_TEMPLATE");
    if (enabled == nullptr || std::strcmp(enabled, "1") != 0) {
        return common_chat_format_single(tmpls, past_msg, new_msg, add_ass, use_jinja);
    }
    common_chat_templates_inputs inputs;
    inputs.use_jinja = true;
    inputs.enable_thinking = false;
    inputs.chat_template_kwargs["enable_thinking"] = "false";
    inputs.add_generation_prompt = false;
    inputs.messages = past_msg;
    const std::string prefix = past_msg.empty() ? "" : gameocr_common_chat_templates_apply(tmpls, inputs).prompt.str();
    inputs.messages.push_back(new_msg);
    inputs.add_generation_prompt = add_ass;
    const std::string formatted = gameocr_common_chat_templates_apply(tmpls, inputs).prompt.str();
    if (formatted.compare(0, prefix.size(), prefix) != 0) {
        throw std::runtime_error("GGUF chat template changed the conversation prefix");
    }
    return formatted.substr(prefix.size());
}

namespace {

ggml_backend_dev_t no_offload_devices[] = {nullptr};
std::string initialization_model_path;
llama_model_params initialization_model_params{};
llama_context_params initialization_context_params{};
common_params_sampling runtime_sampler_params{};
bool initialization_safe_fallback = false;
int initialization_requested_gpu_layers = 0;

int sampler_integer_from_environment(const char * name, int fallback, int minimum, int maximum);

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
    LOGw(
            "invalid thread environment %s=%s; keeping %d",
            name,
            raw_value,
            fallback);
    return fallback;
}

bool requests_vulkan() {
    // The typed per-model setting wins; retain the old flag only for old callers.
    if (std::getenv("GAMEOCR_GPU_LAYERS") != nullptr) {
        return sampler_integer_from_environment("GAMEOCR_GPU_LAYERS", 0, -1, 999) != 0;
    }
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
        if (name.find("vulkan") == std::string::npos) continue;
        for (size_t device_index = 0; device_index < ggml_backend_reg_dev_count(backend); ++device_index) {
            const auto device = ggml_backend_reg_dev_get(backend, device_index);
            if (device == nullptr) continue;
            const auto type = ggml_backend_dev_type(device);
            if (type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU) return true;
        }
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
    LOGw(
            "invalid sampler environment %s=%s; keeping %.2f",
            name,
            raw_value,
            fallback);
    return fallback;
}

int sampler_integer_from_environment(
        const char * name,
        const int fallback,
        const int minimum,
        const int maximum) {
    const char * raw_value = std::getenv(name);
    if (raw_value == nullptr) return fallback;

    char * end = nullptr;
    const long value = std::strtol(raw_value, &end, 10);
    if (end != raw_value && *end == '\0' && value >= minimum && value <= maximum) {
        return static_cast<int>(value);
    }
    LOGw(
            "invalid sampler environment %s=%s; keeping %d",
            name,
            raw_value,
            fallback);
    return fallback;
}

ggml_type cache_type_from_environment(const char * name) {
    const char * value = std::getenv(name);
    if (value == nullptr || std::strcmp(value, "f16") == 0) return GGML_TYPE_F16;
    if (std::strcmp(value, "q8_0") == 0) return GGML_TYPE_Q8_0;
    if (std::strcmp(value, "q4_0") == 0) return GGML_TYPE_Q4_0;
    LOGw("invalid cache environment %s=%s; keeping f16", name, value);
    return GGML_TYPE_F16;
}

llama_flash_attn_type flash_attention_from_environment() {
    const char * value = std::getenv("GAMEOCR_FLASH_ATTENTION");
    if (value == nullptr || std::strcmp(value, "auto") == 0) return LLAMA_FLASH_ATTN_TYPE_AUTO;
    if (std::strcmp(value, "on") == 0) return LLAMA_FLASH_ATTN_TYPE_ENABLED;
    if (std::strcmp(value, "off") == 0) return LLAMA_FLASH_ATTN_TYPE_DISABLED;
    LOGw("invalid Flash Attention environment %s; keeping auto", value);
    return LLAMA_FLASH_ATTN_TYPE_AUTO;
}

void configure_context_threads(llama_context_params & params) {
    const long detected = sysconf(_SC_NPROCESSORS_ONLN);
    const int processors = detected > 0 ? static_cast<int>(detected) : 1;
    const int selected = select_inference_threads(processors);
    const int maximum = std::min(processors, 64);
    params.n_threads = thread_value_from_environment("GAMEOCR_GENERATION_THREADS", selected, maximum);
    params.n_threads_batch = thread_value_from_environment("GAMEOCR_PROMPT_THREADS", selected, maximum);
}

}  // namespace

common_sampler * gameocr_common_sampler_init(
        const llama_model * model,
        common_params_sampling & params) {
    params.temp = sampler_value_from_environment(
            "GAMEOCR_SAMPLER_TEMPERATURE", params.temp, 0.0f, 2.0f);
    params.top_p = sampler_value_from_environment(
            "GAMEOCR_SAMPLER_TOP_P", params.top_p, 0.0f, 1.0f);
    params.min_p = sampler_value_from_environment("GAMEOCR_SAMPLER_MIN_P", params.min_p, 0.0f, 1.0f);
    params.penalty_present = sampler_value_from_environment(
            "GAMEOCR_SAMPLER_PRESENCE_PENALTY", params.penalty_present, 0.0f, 2.0f);
    params.top_k = sampler_integer_from_environment(
            "GAMEOCR_SAMPLER_TOP_K", params.top_k, 0, 10000);
    params.penalty_repeat = sampler_value_from_environment(
            "GAMEOCR_SAMPLER_REPEAT_PENALTY", params.penalty_repeat, 0.0f, 4.0f);
    params.penalty_freq = sampler_value_from_environment(
            "GAMEOCR_SAMPLER_FREQUENCY_PENALTY", params.penalty_freq, 0.0f, 2.0f);
    LOGi(
            "sampler native temperature=%.2f topP=%.2f topK=%d repeatPenalty=%.2f frequencyPenalty=%.2f",
            params.temp,
            params.top_p,
            params.top_k,
            params.penalty_repeat,
            params.penalty_freq);
    runtime_sampler_params = params;
    return common_sampler_init(model, params);
}

extern "C" llama_model * gameocr_llama_model_load_from_file(
        const char * path_model,
        llama_model_params params) {
    const bool requested = requests_vulkan();
    const bool available = requested && has_vulkan_backend();
    const bool use_vulkan = requested && available;
    configure_model_acceleration(params, use_vulkan);
    initialization_safe_fallback = false;
    initialization_requested_gpu_layers = sampler_integer_from_environment(
            "GAMEOCR_GPU_LAYERS", requested ? -1 : 0, -1, 999);
    if (use_vulkan) params.n_gpu_layers = initialization_requested_gpu_layers;
    const bool use_mmap = sampler_integer_from_environment("GAMEOCR_USE_MMAP", 1, 0, 1) != 0;
    const bool use_mlock = sampler_integer_from_environment("GAMEOCR_USE_MLOCK", 0, 0, 1) != 0;
    params.load_mode = use_mmap
            ? (use_mlock ? LLAMA_LOAD_MODE_MMAP_MLOCK : LLAMA_LOAD_MODE_MMAP)
            : (use_mlock ? LLAMA_LOAD_MODE_MLOCK : LLAMA_LOAD_MODE_NONE);
    initialization_model_path = path_model;
    initialization_model_params = params;

    LOGi(
            "acceleration native requestedVulkan=%s backendAvailable=%s nGpuLayers=%d devices=%s",
            requested ? "true" : "false",
            available ? "true" : "false",
            params.n_gpu_layers,
            use_vulkan ? "all" : "none");

    llama_model * model = llama_model_load_from_file(path_model, params);
    if (model != nullptr || !use_vulkan) return model;

    LOGw("Vulkan model load failed; retrying on CPU");
    configure_model_acceleration(params, false);
    initialization_safe_fallback = true;
    initialization_model_params = params;
    model = llama_model_load_from_file(path_model, params);
    if (model == nullptr) {
        LOGe("CPU fallback model load success=false");
    } else {
        LOGi("CPU fallback model load success=true");
    }
    return model;
}

extern "C" llama_context * gameocr_llama_init_from_model(
        llama_model * model,
        llama_context_params params) {
    configure_context_threads(params);
    const int requested_context = sampler_integer_from_environment("GAMEOCR_CONTEXT_TOKENS", 0, 0, 4096);
    if (requested_context >= 512) params.n_ctx = requested_context;
    params.n_batch = sampler_integer_from_environment("GAMEOCR_BATCH_TOKENS", 512, 32, 2048);
    params.n_ubatch = std::min(params.n_batch, static_cast<uint32_t>(sampler_integer_from_environment(
            "GAMEOCR_MICRO_BATCH_TOKENS", 512, 32, 2048)));
    params.type_k = initialization_safe_fallback ? GGML_TYPE_F16 : cache_type_from_environment("GAMEOCR_CACHE_TYPE_K");
    params.type_v = initialization_safe_fallback ? GGML_TYPE_F16 : cache_type_from_environment("GAMEOCR_CACHE_TYPE_V");
    params.flash_attn_type = initialization_safe_fallback ? LLAMA_FLASH_ATTN_TYPE_DISABLED : flash_attention_from_environment();
    // Sequence 0 owns the reusable system prompt; 1..8 are independent translations.
    // Unified KV preserves the full per-sequence context while sharing the system prefix.
    params.n_seq_max = 9;
    params.kv_unified = true;
    initialization_context_params = params;
    LOGi("initialization effective TG=%d PP=%d context=%u batch=%u micro=%u K=%s V=%s FA=%s safeFallback=%d",
            params.n_threads, params.n_threads_batch, params.n_ctx, params.n_batch, params.n_ubatch,
            ggml_type_name(params.type_k), ggml_type_name(params.type_v),
            llama_flash_attn_type_name(params.flash_attn_type), initialization_safe_fallback);
    return llama_init_from_model(model, params);
}

// prepare() owns the old model pointer and nulls it before transferring ownership here.
// A context/KV/backend failure gets one complete CPU + F16 retry, not a context on old GPU weights.
extern "C" llama_model * gameocr_retry_cpu_f16_model(llama_model * previous) {
    llama_model_free(previous);
    initialization_safe_fallback = true;
    initialization_model_params.n_gpu_layers = 0;
    initialization_model_params.devices = no_offload_devices;
    LOGw("initialization failed; rebuilding model on CPU with F16 KV and Flash Attention off");
    return llama_model_load_from_file(initialization_model_path.c_str(), initialization_model_params);
}

extern "C" void gameocr_configure_runtime_threads(llama_context * context) {
    llama_context_params params{};
    configure_context_threads(params);
    llama_set_n_threads(context, params.n_threads, params.n_threads_batch);
}

std::vector<std::string> gameocr_native_initialization_info(const llama_context * context) {
    if (!context) return {};
    const auto requested = [](const char * key, const char * fallback) {
        const char * value = std::getenv(key);
        return std::string(value == nullptr ? fallback : value);
    };
    // "Configured" intentionally does not claim measured GPU offload, mmap residency or mlock success.
    return {
        "requestedContext=" + requested("GAMEOCR_CONTEXT_TOKENS", "0"),
        "actualContext=" + std::to_string(llama_n_ctx(context)),
        "requestedBatch=" + requested("GAMEOCR_BATCH_TOKENS", "512"),
        "actualBatch=" + std::to_string(llama_n_batch(context)),
        "requestedMicro=" + requested("GAMEOCR_MICRO_BATCH_TOKENS", "512"),
        "actualMicro=" + std::to_string(llama_n_ubatch(context)),
        "requestedGpuLayers=" + std::to_string(initialization_requested_gpu_layers),
        "configuredGpuLayers=" + std::to_string(initialization_model_params.n_gpu_layers),
        "requestedK=" + requested("GAMEOCR_CACHE_TYPE_K", "f16"),
        "configuredK=" + std::string(ggml_type_name(initialization_context_params.type_k)),
        "requestedV=" + requested("GAMEOCR_CACHE_TYPE_V", "f16"),
        "configuredV=" + std::string(ggml_type_name(initialization_context_params.type_v)),
        "requestedFA=" + requested("GAMEOCR_FLASH_ATTENTION", "auto"),
        "configuredFA=" + std::string(llama_flash_attn_type_name(initialization_context_params.flash_attn_type)),
        "mmapRequested=" + requested("GAMEOCR_USE_MMAP", "1"),
        "mlockRequested=" + requested("GAMEOCR_USE_MLOCK", "0"),
        "safeFallback=" + std::string(initialization_safe_fallback ? "1" : "0"),
    };
}

std::vector<std::string> gameocr_native_runtime_info(llama_context * context) {
    if (!context) return {};
    return {
        "actualGenerationThreads=" + std::to_string(llama_n_threads(context)),
        "actualPromptThreads=" + std::to_string(llama_n_threads_batch(context)),
        "actualTemperature=" + std::to_string(runtime_sampler_params.temp),
        "actualTopP=" + std::to_string(runtime_sampler_params.top_p),
        "actualTopK=" + std::to_string(runtime_sampler_params.top_k),
        "actualMinP=" + std::to_string(runtime_sampler_params.min_p),
        "actualRepeatPenalty=" + std::to_string(runtime_sampler_params.penalty_repeat),
        "actualFrequencyPenalty=" + std::to_string(runtime_sampler_params.penalty_freq),
        "actualPresencePenalty=" + std::to_string(runtime_sampler_params.penalty_present),
    };
}
