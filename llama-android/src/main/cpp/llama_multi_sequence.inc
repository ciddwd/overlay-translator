// Appended to the generated ai_chat.cpp translation unit by CMakeLists.txt.
// Sequence 0 retains the reusable system prompt. Sequences 1..8 are independent requests.

namespace {

constexpr int GAMEOCR_MAX_PARALLEL_SEQUENCES = 8;

struct gameocr_multi_sequence_state {
    llama_seq_id seq_id = -1;
    llama_tokens prompt_tokens;
    common_sampler * sampler = nullptr;
    std::string output;
    int32_t position = 0;
    int32_t logit_index = -1;
    int32_t generated_tokens = 0;
    bool finished = false;
};

struct gameocr_multi_resources {
    llama_batch batch{};
    bool batch_initialized = false;
    std::vector<common_sampler *> samplers;

    ~gameocr_multi_resources() {
        for (auto * sampler : samplers) {
            common_sampler_free(sampler);
        }
        if (batch_initialized) {
            llama_batch_free(batch);
        }
    }
};

struct gameocr_multi_state_reset {
    llama_memory_t memory;

    explicit gameocr_multi_state_reset(llama_memory_t memory) : memory(memory) {
    }

    ~gameocr_multi_state_reset() {
        for (llama_seq_id seq_id = 1; seq_id <= GAMEOCR_MAX_PARALLEL_SEQUENCES; ++seq_id) {
            llama_memory_seq_rm(memory, seq_id, -1, -1);
        }
        llama_memory_seq_rm(memory, 0, system_prompt_position, -1);
        current_position = system_prompt_position;
        reset_short_term_states();
        if (g_sampler != nullptr) {
            common_sampler_reset(g_sampler);
        }
        const bool keep_system_message =
                !chat_msgs.empty() && chat_msgs.front().role == ROLE_SYSTEM;
        chat_msgs.resize(keep_system_message ? 1 : 0);
    }
};

static std::string gameocr_format_independent_user_prompt(const std::string & prompt) {
    if (!common_chat_templates_was_explicit(g_chat_templates.get())) {
        return prompt;
    }

    std::vector<common_chat_msg> base_messages;
    if (!chat_msgs.empty() && chat_msgs.front().role == ROLE_SYSTEM) {
        base_messages.push_back(chat_msgs.front());
    }
    common_chat_msg user_message;
    user_message.role = ROLE_USER;
    user_message.content = prompt;
    return common_chat_format_single(
            g_chat_templates.get(),
            base_messages,
            user_message,
            true,
            /* use_jinja */ false);
}

static jobjectArray gameocr_multi_fail(const char * reason, const int sequence_count) {
    __android_log_print(
            ANDROID_LOG_ERROR,
            "LocalLlmPerf",
            "MULTI failed reason=%s sequences=%d",
            reason,
            sequence_count);
    return nullptr;
}

}  // namespace

extern "C"
JNIEXPORT jobjectArray JNICALL
Java_com_gameocr_app_llm_LlamaMultiSequence_generateNative(
        JNIEnv * env,
        jobject /*unused*/,
        jobjectArray jprompts,
        jint predict_length) {
    const int64_t total_started_us = ggml_time_us();
    if (g_model == nullptr || g_context == nullptr || jprompts == nullptr) {
        return gameocr_multi_fail("model_not_ready", 0);
    }

    const int sequence_count = env->GetArrayLength(jprompts);
    if (sequence_count < 2 || sequence_count > GAMEOCR_MAX_PARALLEL_SEQUENCES) {
        return gameocr_multi_fail("invalid_sequence_count", sequence_count);
    }
    if (predict_length < 1) {
        return gameocr_multi_fail("invalid_predict_length", sequence_count);
    }
    if ((int) llama_n_seq_max(g_context) < sequence_count + 1) {
        return gameocr_multi_fail("context_sequence_capacity", sequence_count);
    }

    const bool has_chat_template =
            common_chat_templates_was_explicit(g_chat_templates.get());
    std::vector<gameocr_multi_sequence_state> sequences(sequence_count);
    int total_prompt_tokens = 0;
    int64_t required_kv_tokens = system_prompt_position;

    for (int index = 0; index < sequence_count; ++index) {
        auto * jprompt = static_cast<jstring>(env->GetObjectArrayElement(jprompts, index));
        if (jprompt == nullptr) {
            return gameocr_multi_fail("null_prompt", sequence_count);
        }
        const char * prompt_chars = env->GetStringUTFChars(jprompt, nullptr);
        if (prompt_chars == nullptr) {
            env->DeleteLocalRef(jprompt);
            return gameocr_multi_fail("prompt_utf8", sequence_count);
        }
        const std::string formatted_prompt =
                gameocr_format_independent_user_prompt(prompt_chars);
        env->ReleaseStringUTFChars(jprompt, prompt_chars);
        env->DeleteLocalRef(jprompt);

        auto & sequence = sequences[index];
        sequence.seq_id = index + 1;
        sequence.prompt_tokens = common_tokenize(
                g_context,
                formatted_prompt,
                has_chat_template,
                has_chat_template);
        if (sequence.prompt_tokens.empty()) {
            return gameocr_multi_fail("empty_prompt_tokens", sequence_count);
        }
        sequence.position = system_prompt_position + (int) sequence.prompt_tokens.size();
        total_prompt_tokens += (int) sequence.prompt_tokens.size();
        required_kv_tokens += (int64_t) sequence.prompt_tokens.size() + predict_length;
    }

    if (total_prompt_tokens > (int) llama_n_batch(g_context)) {
        return gameocr_multi_fail("prompt_batch_capacity", sequence_count);
    }
    if (required_kv_tokens + OVERFLOW_HEADROOM > llama_n_ctx(g_context)) {
        return gameocr_multi_fail("context_token_capacity", sequence_count);
    }

    auto memory = llama_get_memory(g_context);
    gameocr_multi_state_reset state_reset(memory);
    llama_memory_seq_rm(memory, 0, system_prompt_position, -1);
    current_position = system_prompt_position;
    reset_short_term_states();
    if (g_sampler != nullptr) {
        common_sampler_reset(g_sampler);
    }
    const bool keep_system_message =
            !chat_msgs.empty() && chat_msgs.front().role == ROLE_SYSTEM;
    chat_msgs.resize(keep_system_message ? 1 : 0);

    for (llama_seq_id seq_id = 1; seq_id <= GAMEOCR_MAX_PARALLEL_SEQUENCES; ++seq_id) {
        llama_memory_seq_rm(memory, seq_id, -1, -1);
    }
    for (auto & sequence : sequences) {
        if (system_prompt_position > 0) {
            llama_memory_seq_cp(memory, 0, sequence.seq_id, 0, system_prompt_position);
        }
    }

    gameocr_multi_resources resources;
    resources.batch = llama_batch_init(std::max(total_prompt_tokens, sequence_count), 0, 1);
    resources.batch_initialized = true;
    resources.samplers.reserve(sequence_count);
    for (auto & sequence : sequences) {
        sequence.sampler = new_sampler(DEFAULT_SAMPLER_TEMP);
        if (sequence.sampler == nullptr) {
            return gameocr_multi_fail("sampler_init", sequence_count);
        }
        resources.samplers.push_back(sequence.sampler);
    }

    llama_perf_context_reset(g_context);
    common_batch_clear(resources.batch);
    for (auto & sequence : sequences) {
        int32_t position = system_prompt_position;
        for (size_t token_index = 0; token_index < sequence.prompt_tokens.size(); ++token_index) {
            const bool want_logits = token_index + 1 == sequence.prompt_tokens.size();
            if (want_logits) {
                sequence.logit_index = resources.batch.n_tokens;
            }
            common_batch_add(
                    resources.batch,
                    sequence.prompt_tokens[token_index],
                    position++,
                    {sequence.seq_id},
                    want_logits);
        }
    }

    const int64_t prefill_started_us = ggml_time_us();
    if (llama_decode(g_context, resources.batch) != 0) {
        return gameocr_multi_fail("prefill_decode", sequence_count);
    }
    const int64_t prefill_finished_us = ggml_time_us();

    int64_t first_output_us = 0;
    int64_t generation_decode_us = 0;
    int generation_decode_calls = 0;
    int total_generated_tokens = 0;

    auto sample_next = [&](gameocr_multi_sequence_state & sequence, const int32_t logit_index) {
        const llama_token token =
                common_sampler_sample(sequence.sampler, g_context, logit_index);
        common_sampler_accept(sequence.sampler, token, true);
        sequence.generated_tokens += 1;
        total_generated_tokens += 1;

        if (llama_vocab_is_eog(llama_model_get_vocab(g_model), token)) {
            sequence.finished = true;
            return;
        }

        sequence.output += common_token_to_piece(g_context, token);
        if (first_output_us == 0) {
            first_output_us = ggml_time_us();
        }
        if (sequence.generated_tokens >= predict_length) {
            sequence.finished = true;
            return;
        }

        sequence.logit_index = resources.batch.n_tokens;
        common_batch_add(
                resources.batch,
                token,
                sequence.position++,
                {sequence.seq_id},
                true);
    };

    common_batch_clear(resources.batch);
    for (auto & sequence : sequences) {
        sample_next(sequence, sequence.logit_index);
    }

    while (resources.batch.n_tokens > 0) {
        const int64_t decode_started_us = ggml_time_us();
        if (llama_decode(g_context, resources.batch) != 0) {
            return gameocr_multi_fail("generation_decode", sequence_count);
        }
        generation_decode_us += ggml_time_us() - decode_started_us;
        generation_decode_calls += 1;

        common_batch_clear(resources.batch);
        for (auto & sequence : sequences) {
            if (!sequence.finished) {
                sample_next(sequence, sequence.logit_index);
            }
        }
    }

    const int64_t finished_us = ggml_time_us();
    std::vector<int> generated_per_sequence;
    generated_per_sequence.reserve(sequence_count);
    for (const auto & sequence : sequences) {
        generated_per_sequence.push_back(sequence.generated_tokens);
        if (!is_valid_utf8(sequence.output.c_str())) {
            return gameocr_multi_fail("invalid_output_utf8", sequence_count);
        }
    }

    __android_log_print(
            ANDROID_LOG_INFO,
            "LocalLlmPerf",
            "MULTI STATS sequences=%d promptTokens=%d generatedTokens=%d perSequence=%s "
            "initMs=%.1f prefillMs=%.1f firstTokenMs=%.1f decodeMs=%.1f "
            "decodeCalls=%d totalMs=%.1f",
            sequence_count,
            total_prompt_tokens,
            total_generated_tokens,
            join(generated_per_sequence, ",").c_str(),
            (prefill_started_us - total_started_us) / 1000.0,
            (prefill_finished_us - prefill_started_us) / 1000.0,
            first_output_us == 0 ? -1.0 : (first_output_us - total_started_us) / 1000.0,
            generation_decode_us / 1000.0,
            generation_decode_calls,
            (finished_us - total_started_us) / 1000.0);

    auto * string_class = env->FindClass("java/lang/String");
    if (string_class == nullptr) {
        return gameocr_multi_fail("string_class", sequence_count);
    }
    jobjectArray outputs = env->NewObjectArray(sequence_count, string_class, nullptr);
    env->DeleteLocalRef(string_class);
    if (outputs == nullptr) {
        return gameocr_multi_fail("output_array", sequence_count);
    }

    for (int index = 0; index < sequence_count; ++index) {
        jstring output = env->NewStringUTF(sequences[index].output.c_str());
        if (output == nullptr) {
            return gameocr_multi_fail("output_string", sequence_count);
        }
        env->SetObjectArrayElement(outputs, index, output);
        env->DeleteLocalRef(output);
    }
    return outputs;
}
