// Same translation unit/model/context as the established binding. No second model instance.
#include <atomic>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <cstring>
#include <stdexcept>
#include "gguf.h"

common_chat_params gameocr_common_chat_templates_apply(
        const common_chat_templates * tmpls, common_chat_templates_inputs inputs);

namespace {
std::mutex template_requests_mutex;
std::unordered_map<int64_t, std::shared_ptr<std::atomic<bool>>> template_requests;
std::atomic<int64_t> next_template_request{1};

static std::string template_meta(const char * key) {
    const int length = llama_model_meta_val_str(g_model, key, nullptr, 0);
    if (length < 0) return "";
    std::vector<char> value(length + 1);
    llama_model_meta_val_str(g_model, key, value.data(), value.size());
    return value.data();
}

static jobjectArray template_strings(JNIEnv * env, const std::vector<std::string> & values) {
    auto cls = env->FindClass("java/lang/String");
    if (!cls) return nullptr;
    auto result = env->NewObjectArray(values.size(), cls, nullptr);
    env->DeleteLocalRef(cls);
    if (!result) return nullptr;
    for (size_t i = 0; i < values.size(); ++i) {
        auto str = env->NewStringUTF(values[i].c_str());
        if (!str) return nullptr;
        env->SetObjectArrayElement(result, i, str);
        env->DeleteLocalRef(str);
    }
    return result;
}

static bool template_aborted(void * data) {
    return static_cast<std::atomic<bool> *>(data)->load();
}

struct template_request_cleanup {
    ~template_request_cleanup() {
        if (!g_context) return;
        llama_synchronize(g_context);
        llama_set_abort_callback(g_context, nullptr, nullptr);
        // Recurrent/hybrid models cannot safely use arbitrary partial KV removal.
        llama_memory_clear(llama_get_memory(g_context), true);
        if (g_sampler) common_sampler_reset(g_sampler);
        chat_msgs.clear();
        system_prompt_position = 0;
        current_position = 0;
        reset_short_term_states();
    }
};

struct template_sequence {
    common_chat_params params;
    llama_tokens prompt;
    std::string output, finish, content;
    common_sampler * sampler = nullptr;
    int position = 0, logits = 0, tokens = 0;
};
} // namespace

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_gameocr_app_llm_LlamaTemplateGeneration_configureRuntimeNative(JNIEnv * env, jobject) {
    if (!g_context || !g_model) return template_strings(env, {});
    try {
        // Construct first: a failed new sampler must not discard the usable old sampler.
        auto replacement = std::unique_ptr<common_sampler, decltype(&common_sampler_free)>(
                new_sampler(DEFAULT_SAMPLER_TEMP), common_sampler_free);
        if (!replacement) throw std::runtime_error("runtime sampler initialization failed");
        gameocr_configure_runtime_threads(g_context);
        if (g_sampler) common_sampler_free(g_sampler);
        g_sampler = replacement.release();
        return template_strings(env, gameocr_native_runtime_info(g_context));
    } catch (const std::exception & error) {
        LOGe("runtime configuration failed: %s", error.what());
        auto cls = env->FindClass("java/lang/IllegalStateException");
        if (cls) { env->ThrowNew(cls, error.what()); env->DeleteLocalRef(cls); }
        return nullptr;
    } catch (...) {
        LOGe("runtime configuration failed with unknown native exception");
        auto cls = env->FindClass("java/lang/IllegalStateException");
        if (cls) { env->ThrowNew(cls, "runtime configuration failed"); env->DeleteLocalRef(cls); }
        return nullptr;
    }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_gameocr_app_llm_LlamaTemplateGeneration_initializationInfoNative(JNIEnv * env, jobject) {
    return template_strings(env, gameocr_native_initialization_info(g_context));
}

namespace {
// Architecture metadata may be scalar or per-layer arrays. Preserve arrays as CSV;
// unknown/noninteger types remain empty instead of inventing dimensions.
std::string memory_integer(const gguf_context * header, const std::string & key) {
    const auto index = gguf_find_key(header, key.c_str());
    if (index < 0) return "";
    const auto type = gguf_get_kv_type(header, index);
    if (type != GGUF_TYPE_ARRAY) {
        switch (type) {
            case GGUF_TYPE_UINT8: return std::to_string(gguf_get_val_u8(header, index));
            case GGUF_TYPE_INT8: return std::to_string(gguf_get_val_i8(header, index));
            case GGUF_TYPE_UINT16: return std::to_string(gguf_get_val_u16(header, index));
            case GGUF_TYPE_INT16: return std::to_string(gguf_get_val_i16(header, index));
            case GGUF_TYPE_UINT32: return std::to_string(gguf_get_val_u32(header, index));
            case GGUF_TYPE_INT32: return std::to_string(gguf_get_val_i32(header, index));
            case GGUF_TYPE_UINT64: return std::to_string(gguf_get_val_u64(header, index));
            case GGUF_TYPE_INT64: return std::to_string(gguf_get_val_i64(header, index));
            default: return "";
        }
    }
    const auto count = gguf_get_arr_n(header, index);
    if (count == 0 || count > 8192) return "";
    const auto element_type = gguf_get_arr_type(header, index);
    const auto data = static_cast<const uint8_t *>(gguf_get_arr_data(header, index));
    if (!data) return "";
    std::string result;
    for (size_t i = 0; i < count; ++i) {
        std::string value;
#define GAMEOCR_MEMORY_ARRAY_CASE(gguf_kind, native_kind) \
        case gguf_kind: { native_kind number; std::memcpy(&number, data + i * sizeof(number), sizeof(number)); \
            value = std::to_string(number); break; }
        switch (element_type) {
            GAMEOCR_MEMORY_ARRAY_CASE(GGUF_TYPE_UINT8, uint8_t)
            GAMEOCR_MEMORY_ARRAY_CASE(GGUF_TYPE_INT8, int8_t)
            GAMEOCR_MEMORY_ARRAY_CASE(GGUF_TYPE_UINT16, uint16_t)
            GAMEOCR_MEMORY_ARRAY_CASE(GGUF_TYPE_INT16, int16_t)
            GAMEOCR_MEMORY_ARRAY_CASE(GGUF_TYPE_UINT32, uint32_t)
            GAMEOCR_MEMORY_ARRAY_CASE(GGUF_TYPE_INT32, int32_t)
            GAMEOCR_MEMORY_ARRAY_CASE(GGUF_TYPE_UINT64, uint64_t)
            GAMEOCR_MEMORY_ARRAY_CASE(GGUF_TYPE_INT64, int64_t)
            default: return "";
        }
#undef GAMEOCR_MEMORY_ARRAY_CASE
        if (i) result += ",";
        result += value;
    }
    return result;
}
} // namespace

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_gameocr_app_llm_LlamaTemplateGeneration_inspectMemoryFileNative(JNIEnv * env, jobject, jstring path) {
    if (!path) return template_strings(env, {});
    const char * chars = env->GetStringUTFChars(path, nullptr);
    if (!chars) return nullptr;
    gguf_init_params options{};
    options.no_alloc = true;
    options.ctx = nullptr;
    gguf_context * raw_header = nullptr;
    try { raw_header = gguf_init_from_file(chars, options); }
    catch (...) { env->ReleaseStringUTFChars(path, chars); return template_strings(env, {}); }
    env->ReleaseStringUTFChars(path, chars);
    auto header = std::unique_ptr<gguf_context, decltype(&gguf_free)>(raw_header, gguf_free);
    if (!header) return template_strings(env, {});
    try {
        const auto arch_index = gguf_find_key(header.get(), "general.architecture");
        const std::string arch = arch_index >= 0 && gguf_get_kv_type(header.get(), arch_index) == GGUF_TYPE_STRING ?
                gguf_get_val_str(header.get(), arch_index) : "";
        const auto number = [&](const char * suffix) { return memory_integer(header.get(), arch + suffix); };
        const auto vocab_index = gguf_find_key(header.get(), "tokenizer.ggml.tokens");
        const std::string vocab = vocab_index >= 0 && gguf_get_kv_type(header.get(), vocab_index) == GGUF_TYPE_ARRAY ?
                std::to_string(gguf_get_arr_n(header.get(), vocab_index)) : number(".vocab_size");
        // Stable 14-field protocol, separate from the existing 3-field inspectFile.
        return template_strings(env, {arch, number(".context_length"), number(".embedding_length"),
            number(".block_count"), number(".attention.head_count"), number(".attention.head_count_kv"),
            number(".attention.key_length"), number(".attention.value_length"), vocab,
            number(".attention.sliding_window"), number(".ssm.state_size"), number(".ssm.inner_size"),
            number(".ssm.conv_kernel"), number(".full_attention_interval")});
    } catch (...) { return template_strings(env, {}); }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_gameocr_app_llm_LlamaTemplateGeneration_inspectFileNative(JNIEnv * env, jobject, jstring path) {
    const char * chars = env->GetStringUTFChars(path, nullptr);
    if (!chars) return nullptr;
    gguf_init_params options{};
    options.no_alloc = true;
    options.ctx = nullptr;
    auto * header = gguf_init_from_file(chars, options);
    env->ReleaseStringUTFChars(path, chars);
    if (!header) return template_strings(env, {});
    auto get_string = [&](const char * key) -> std::string {
        const auto index = gguf_find_key(header, key);
        return index >= 0 && gguf_get_kv_type(header, index) == GGUF_TYPE_STRING ?
            gguf_get_val_str(header, index) : "";
    };
    const auto quant = gguf_find_key(header, "general.file_type");
    const std::string quantization = quant >= 0 && gguf_get_kv_type(header, quant) == GGUF_TYPE_UINT32 ?
        std::to_string(gguf_get_val_u32(header, quant)) : "";
    const std::vector<std::string> values{get_string("general.architecture"), quantization,
        get_string("tokenizer.chat_template")};
    gguf_free(header);
    return template_strings(env, values);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_gameocr_app_llm_LlamaTemplateGeneration_metadataNative(JNIEnv * env, jobject) {
    if (!g_model || !g_context) return template_strings(env, {});
    return template_strings(env, {template_meta("general.architecture"), template_meta("general.file_type"),
        common_chat_templates_source(g_chat_templates.get()), llama_model_is_recurrent(g_model) ? "1" : "0",
        llama_model_is_hybrid(g_model) ? "1" : "0", std::to_string(llama_n_seq_max(g_context))});
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_gameocr_app_llm_LlamaTemplateGeneration_createRequestNative(JNIEnv *, jobject) {
    const auto id = next_template_request.fetch_add(1);
    std::lock_guard<std::mutex> lock(template_requests_mutex);
    template_requests[id] = std::make_shared<std::atomic<bool>>(false);
    return id;
}
extern "C" JNIEXPORT void JNICALL
Java_com_gameocr_app_llm_LlamaTemplateGeneration_cancelRequestNative(JNIEnv *, jobject, jlong id) {
    std::lock_guard<std::mutex> lock(template_requests_mutex);
    auto found = template_requests.find(id);
    if (found != template_requests.end()) found->second->store(true);
}
extern "C" JNIEXPORT void JNICALL
Java_com_gameocr_app_llm_LlamaTemplateGeneration_releaseRequestNative(JNIEnv *, jobject, jlong id) {
    std::lock_guard<std::mutex> lock(template_requests_mutex);
    template_requests.erase(id);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_gameocr_app_llm_LlamaTemplateGeneration_generateNative(
        JNIEnv * env, jobject, jlong id, jobjectArray prompts, jint max_tokens, jint context_tokens) {
    std::shared_ptr<std::atomic<bool>> cancelled;
    {
        std::lock_guard<std::mutex> lock(template_requests_mutex);
        auto found = template_requests.find(id);
        if (found == template_requests.end()) return nullptr;
        cancelled = found->second;
    }
    if (!prompts || !g_context || !g_model || max_tokens <= 0 || context_tokens <= 0) return nullptr;
    const int count = env->GetArrayLength(prompts);
    if (count < 1 || count > 8 || count > (int) llama_n_seq_max(g_context)) return nullptr;
    std::vector<template_sequence> sequences(count);
    std::vector<std::string> result;
    const int64_t started = ggml_time_us();
    gameocr_multi_resources resources;
    // Destruction is reverse declaration order: synchronize/reset before freeing batches/samplers.
    template_request_cleanup cleanup;
    llama_memory_clear(llama_get_memory(g_context), true);
    llama_set_abort_callback(g_context, template_aborted, cancelled.get());
    const int budget = std::min(context_tokens, (int) llama_n_ctx(g_context));
    try {
        if (!common_chat_templates_was_explicit(g_chat_templates.get())) throw std::runtime_error("missing_template");
        int64_t required = 0;
        for (int i = 0; i < count; ++i) {
            auto text = static_cast<jstring>(env->GetObjectArrayElement(prompts, i));
            if (!text) return nullptr;
            const char * chars = env->GetStringUTFChars(text, nullptr);
            if (!chars) return nullptr;
            common_chat_msg msg;
            msg.role = ROLE_USER;
            msg.content = chars;
            env->ReleaseStringUTFChars(text, chars);
            env->DeleteLocalRef(text);
            common_chat_templates_inputs inputs;
            inputs.messages = {msg};
            inputs.use_jinja = true;
            inputs.enable_thinking = false;
            inputs.chat_template_kwargs["enable_thinking"] = "false";
            inputs.add_generation_prompt = true;
            inputs.reasoning_format = COMMON_REASONING_FORMAT_DEEPSEEK;
            auto & s = sequences[i];
            s.params = gameocr_common_chat_templates_apply(g_chat_templates.get(), inputs);
            s.prompt = common_tokenize(g_context, s.params.prompt, true, true);
            s.position = s.prompt.size();
            required += s.position + max_tokens;
            if (s.prompt.empty() || s.position + max_tokens + 4 > budget) s.finish = "input_limit";
        }
        if (required + 4 > llama_n_ctx(g_context)) {
            for (auto & s : sequences) s.finish = "input_limit";
        }
        const int batch_capacity = std::max(count, (int) llama_n_batch(g_context));
        resources.batch = llama_batch_init(batch_capacity, 0, 1);
        resources.batch_initialized = true;
        for (auto & s : sequences) {
            auto sampler = std::unique_ptr<common_sampler, decltype(&common_sampler_free)>(
                    new_sampler(DEFAULT_SAMPLER_TEMP), common_sampler_free);
            if (!sampler) throw std::runtime_error("sampler_error");
            resources.samplers.push_back(sampler.get());
            s.sampler = sampler.release();
        }
        auto sample = [&](int i, int logits) {
            auto & s = sequences[i];
            const auto token = common_sampler_sample(s.sampler, g_context, logits);
            common_sampler_accept(s.sampler, token, true);
            if (llama_vocab_is_eog(llama_model_get_vocab(g_model), token)) {
                s.finish = "complete";
                return;
            }
            ++s.tokens;
            s.output += common_token_to_piece(g_context, token, true);
            for (const auto & stop : s.params.additional_stops) {
                const auto pos = stop.empty() ? std::string::npos : s.output.find(stop);
                if (pos != std::string::npos) {
                    s.output.resize(pos);
                    s.finish = "complete";
                    return;
                }
            }
            if (s.tokens >= max_tokens) { s.finish = "output_limit"; return; }
            s.logits = resources.batch.n_tokens;
            common_batch_add(resources.batch, token, s.position++, {i}, true);
        };
        // Each prefill owns its complete sequence. Never copy a shared prefix or partially
        // remove recurrent state. Decode still batches one token from every active sequence.
        std::vector<llama_token> first_tokens(count, -1);
        for (int i = 0; i < count && !cancelled->load(); ++i) {
            auto & s = sequences[i];
            if (!s.finish.empty()) continue;
            for (size_t offset = 0; offset < s.prompt.size() && !cancelled->load();) {
                common_batch_clear(resources.batch);
                const size_t end = std::min(s.prompt.size(), offset + (size_t) llama_n_batch(g_context));
                for (size_t p = offset; p < end; ++p)
                    common_batch_add(resources.batch, s.prompt[p], p, {i}, p + 1 == s.prompt.size());
                if (llama_decode(g_context, resources.batch) != 0) throw std::runtime_error("prefill_error");
                offset = end;
            }
            if (cancelled->load()) break;
            // Save the sampled token before the next prefill overwrites logits.
            first_tokens[i] = common_sampler_sample(s.sampler, g_context, resources.batch.n_tokens - 1);
        }
        // Sampling has no side effects until accept; restore the saved choice via the sampler
        // by processing this token directly, not by consulting overwritten model logits.
        common_batch_clear(resources.batch);
        for (int i = 0; i < count && !cancelled->load(); ++i) {
            auto & s = sequences[i];
            if (!s.finish.empty()) continue;
            const auto token = first_tokens[i];
            common_sampler_accept(s.sampler, token, true);
            if (llama_vocab_is_eog(llama_model_get_vocab(g_model), token)) { s.finish = "complete"; continue; }
            s.tokens = 1;
            s.output = common_token_to_piece(g_context, token, true);
            for (const auto & stop : s.params.additional_stops) {
                const auto pos = stop.empty() ? std::string::npos : s.output.find(stop);
                if (pos != std::string::npos) { s.output.resize(pos); s.finish = "complete"; break; }
            }
            if (!s.finish.empty()) continue;
            if (s.tokens >= max_tokens) { s.finish = "output_limit"; continue; }
            s.logits = resources.batch.n_tokens;
            common_batch_add(resources.batch, token, s.position++, {i}, true);
        }
        while (resources.batch.n_tokens > 0 && !cancelled->load()) {
            if (llama_decode(g_context, resources.batch) != 0) throw std::runtime_error("decode_error");
            common_batch_clear(resources.batch);
            for (int i = 0; i < count; ++i)
                if (sequences[i].finish.empty()) sample(i, sequences[i].logits);
        }
        for (auto & s : sequences) {
            if (cancelled->load()) { s.finish = "cancelled"; continue; }
            // A truncated answer is parsed as partial output so reasoning is still separated;
            // it keeps finish=output_limit and Kotlin never caches it as a complete translation.
            const bool partial = s.finish == "output_limit";
            if (s.finish != "complete" && !partial) continue;
            try {
                common_chat_parser_params parser(s.params);
                parser.reasoning_format = COMMON_REASONING_FORMAT_DEEPSEEK;
                parser.reasoning_in_content = false;
                parser.parse_tool_calls = true;
                if (!s.params.parser.empty()) parser.parser.load(s.params.parser);
                auto message = common_chat_parse(s.output, partial, parser);
                s.content = message.content;
                if (!message.tool_calls.empty()) { s.content.clear(); s.finish = "unexpected_tool"; }
                else if (s.content.empty() && !partial) s.finish = "empty";
            } catch (const std::exception &) {
                s.content.clear();
                if (!partial) s.finish = "parse_error";
            }
        }
    } catch (const std::exception & ex) {
        LOGe("Template generation failed: %s", ex.what());
        for (auto & s : sequences) { s.finish = cancelled->load() ? "cancelled" : "error"; s.content.clear(); }
    }
    for (const auto & s : sequences) {
        result.push_back(s.finish == "complete" || s.finish == "output_limit" ? s.content : "");
        result.push_back(s.finish);
        result.push_back(std::to_string(s.tokens));
    }
    LOGi("TEMPLATE sequences=%d recurrent=%d hybrid=%d totalMs=%.1f cancelled=%d", count,
        llama_model_is_recurrent(g_model), llama_model_is_hybrid(g_model),
        (ggml_time_us() - started) / 1000.0, cancelled->load());
    return template_strings(env, result);
}
