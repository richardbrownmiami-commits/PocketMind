#include <jni.h>
#include <llama.h>

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <limits>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

namespace {
constexpr size_t MAX_PERSIST_KV_BYTES = 64u * 1024u * 1024u;

struct ModelState {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    uint32_t n_ctx = 2048;
    int32_t n_threads = 2;
    bool session_ready = false;
};

std::mutex g_mutex;
std::once_flag g_backend_once;
std::map<jlong, ModelState> g_models;
jlong g_next_handle = 1;

void throw_java(JNIEnv * env, const char * message) {
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != nullptr) {
        env->ThrowNew(cls, message);
        env->DeleteLocalRef(cls);
    }
}

void append_utf8(std::string & output, uint32_t codepoint) {
    if (codepoint <= 0x7f) {
        output.push_back(static_cast<char>(codepoint));
    } else if (codepoint <= 0x7ff) {
        output.push_back(static_cast<char>(0xc0 | (codepoint >> 6)));
        output.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
    } else if (codepoint <= 0xffff) {
        output.push_back(static_cast<char>(0xe0 | (codepoint >> 12)));
        output.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3f)));
        output.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
    } else {
        output.push_back(static_cast<char>(0xf0 | (codepoint >> 18)));
        output.push_back(static_cast<char>(0x80 | ((codepoint >> 12) & 0x3f)));
        output.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3f)));
        output.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
    }
}

class StringCharsGuard {
public:
    StringCharsGuard(JNIEnv * env, jstring value, const jchar * chars)
        : env_(env), value_(value), chars_(chars) {}
    ~StringCharsGuard() {
        if (chars_ != nullptr) env_->ReleaseStringChars(value_, chars_);
    }
    StringCharsGuard(const StringCharsGuard &) = delete;
    StringCharsGuard & operator=(const StringCharsGuard &) = delete;

private:
    JNIEnv * env_;
    jstring value_;
    const jchar * chars_;
};

bool copy_string(JNIEnv * env, jstring value, std::string & output) {
    if (value == nullptr) return false;
    const jsize length = env->GetStringLength(value);
    const jchar * chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) return false;
    const StringCharsGuard chars_guard(env, value, chars);
    output.clear();
    output.reserve(static_cast<size_t>(length) * 3);
    for (jsize i = 0; i < length; ++i) {
        uint32_t codepoint = chars[i];
        if (codepoint >= 0xd800 && codepoint <= 0xdbff) {
            if (i + 1 < length && chars[i + 1] >= 0xdc00 && chars[i + 1] <= 0xdfff) {
                codepoint = 0x10000 + ((codepoint - 0xd800) << 10) + (chars[++i] - 0xdc00);
            } else {
                codepoint = 0xfffd;
            }
        } else if (codepoint >= 0xdc00 && codepoint <= 0xdfff) {
            codepoint = 0xfffd;
        }
        append_utf8(output, codepoint);
    }
    return true;
}

jstring to_java_utf8(JNIEnv * env, const std::string & value) {
    if (value.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        throw_java(env, "Generated text is too long");
        return nullptr;
    }
    jclass string_cls = env->FindClass("java/lang/String");
    if (string_cls == nullptr) return nullptr;
    jmethodID ctor = env->GetMethodID(string_cls, "<init>", "([BLjava/lang/String;)V");
    if (ctor == nullptr) {
        env->DeleteLocalRef(string_cls);
        return nullptr;
    }
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(value.size()));
    if (bytes == nullptr) {
        env->DeleteLocalRef(string_cls);
        return nullptr;
    }
    if (!value.empty()) {
        env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(value.size()),
                                reinterpret_cast<const jbyte *>(value.data()));
    }
    jstring charset = env->NewStringUTF("UTF-8");
    if (charset == nullptr) {
        env->DeleteLocalRef(bytes);
        env->DeleteLocalRef(string_cls);
        return nullptr;
    }
    jstring result = reinterpret_cast<jstring>(env->NewObject(string_cls, ctor, bytes, charset));
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(string_cls);
    return result;
}

void replace_all(std::string & text, const std::string & from, const std::string & to) {
    if (from.empty()) return;
    size_t at = 0;
    while ((at = text.find(from, at)) != std::string::npos) {
        text.replace(at, from.size(), to);
        at += to.size();
    }
}

std::string sanitize_untrusted(std::string text) {
    // Prevent user/page text from being parsed as chat-template control tokens.
    replace_all(text, "<|", "< |");
    replace_all(text, "</s>", "< /s>");
    replace_all(text, "[INST]", "[ INST]");
    replace_all(text, "[/INST]", "[/ INST]");
    replace_all(text, "<<SYS>>", "< <SYS>>");
    return text;
}

std::string format_one_message(const llama_model * model, const char * role,
                               const std::string & content, bool add_assistant) {
    const char * tmpl = llama_model_chat_template(model, nullptr);
    if (tmpl == nullptr) {
        if (std::string(role) == "system") return "System: " + content + "\n\n";
        return "\nUser: " + content + "\nAssistant:";
    }

    const llama_chat_message message[] = {{role, content.c_str()}};
    const size_t want = std::max<size_t>(512, content.size() * 4 + 256);
    if (want >= static_cast<size_t>(std::numeric_limits<int32_t>::max())) {
        if (std::string(role) == "system") return "System: " + content + "\n\n";
        return "\nUser: " + content + "\nAssistant:";
    }
    std::vector<char> buffer(want);
    int32_t written = llama_chat_apply_template(tmpl, message, 1, add_assistant,
                                                buffer.data(), static_cast<int32_t>(buffer.size()));
    if (written < 0) {
        if (std::string(role) == "system") return "System: " + content + "\n\n";
        return "\nUser: " + content + "\nAssistant:";
    }
    if (static_cast<size_t>(written) >= buffer.size()) {
        if (written == std::numeric_limits<int32_t>::max()) return {};
        buffer.resize(static_cast<size_t>(written) + 1);
        written = llama_chat_apply_template(tmpl, message, 1, add_assistant,
                                           buffer.data(), static_cast<int32_t>(buffer.size()));
        if (written < 0 || static_cast<size_t>(written) >= buffer.size()) return {};
    }
    return std::string(buffer.data(), static_cast<size_t>(written));
}

bool tokenize(const llama_vocab * vocab, const std::string & text, bool add_bos,
              bool remove_leading_bos, std::vector<llama_token> & tokens) {
    if (text.size() > static_cast<size_t>(std::numeric_limits<int32_t>::max() - 16)) return false;
    size_t capacity = std::max<size_t>(64, text.size() + 16);
    tokens.resize(capacity);
    int32_t count = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()),
                                   tokens.data(), static_cast<int32_t>(tokens.size()), false, true);
    if (count < 0 && count != std::numeric_limits<int32_t>::min()) {
        capacity = static_cast<size_t>(-count);
        if (capacity > static_cast<size_t>(std::numeric_limits<int32_t>::max())) return false;
        tokens.resize(capacity);
        count = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()),
                               tokens.data(), static_cast<int32_t>(tokens.size()), false, true);
    }
    if (count < 0 || static_cast<size_t>(count) > tokens.size()) return false;
    tokens.resize(static_cast<size_t>(count));

    const llama_token bos = llama_vocab_bos(vocab);
    if (remove_leading_bos && !tokens.empty() && tokens.front() == bos) tokens.erase(tokens.begin());
    if (add_bos && llama_vocab_get_add_bos(vocab) && (tokens.empty() || tokens.front() != bos)) {
        tokens.insert(tokens.begin(), bos);
    }
    return !tokens.empty();
}

llama_context * make_context(ModelState & state) {
    llama_context_params params = llama_context_default_params();
    params.n_ctx = state.n_ctx;
    params.n_batch = state.n_ctx;
    params.n_ubatch = std::min<uint32_t>(state.n_ctx, 256);
    params.n_threads = state.n_threads;
    params.n_threads_batch = state.n_threads;
    return llama_init_from_model(state.model, params);
}

uint32_t used_positions(llama_context * context) {
    const llama_pos pos = llama_memory_seq_pos_max(llama_get_memory(context), 0);
    return pos < 0 ? 0u : static_cast<uint32_t>(pos + 1);
}

void free_context(ModelState & state) {
    if (state.context != nullptr) {
        llama_free(state.context);
        state.context = nullptr;
    }
    state.session_ready = false;
}

bool seed_system(ModelState & state, const std::string & system_prompt) {
    const llama_vocab * vocab = llama_model_get_vocab(state.model);
    if (vocab == nullptr) return false;
    const std::string formatted = format_one_message(state.model, "system", system_prompt, false);
    std::vector<llama_token> tokens;
    if (formatted.empty() || !tokenize(vocab, formatted, true, false, tokens)) return false;
    if (tokens.size() >= state.n_ctx) return false;
    llama_batch batch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));
    if (llama_decode(state.context, batch) != 0) return false;
    state.session_ready = true;
    return true;
}

struct SamplerDeleter {
    void operator()(llama_sampler * sampler) const {
        if (sampler != nullptr) llama_sampler_free(sampler);
    }
};
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_pocketmind_NativeLlama_init(JNIEnv * env, jobject, jstring jmodel_path,
                                    jint n_ctx, jint n_threads) {
    try {
        std::string model_path;
        if (!copy_string(env, jmodel_path, model_path)) {
            if (!env->ExceptionCheck()) throw_java(env, "Invalid model path");
            return 0;
        }
        if (model_path.empty()) {
            throw_java(env, "Model path is empty");
            return 0;
        }

        std::lock_guard<std::mutex> lock(g_mutex);
        std::call_once(g_backend_once, [] { llama_backend_init(); });
        llama_model_params params = llama_model_default_params();
        params.n_gpu_layers = 0;
        params.lazy_mode = LLAMA_LAZY_MODE_OFF;
        llama_model * model = llama_model_load_from_file(model_path.c_str(), params);
        if (model == nullptr) {
            throw_java(env, "Could not load this GGUF model");
            return 0;
        }

        const jlong handle = g_next_handle++;
        ModelState state;
        state.model = model;
        state.n_ctx = static_cast<uint32_t>(std::clamp(n_ctx, 512, 4096));
        state.n_threads = std::clamp(n_threads, 1, 4);
        g_models.emplace(handle, state);
        return handle;
    } catch (...) {
        throw_java(env, "Native model initialization failed");
        return 0;
    }
}

// Returns 1 when a saved KV sequence was restored, 0 when a new session was seeded, -1 on error.
extern "C" JNIEXPORT jint JNICALL
Java_com_pocketmind_NativeLlama_startSession(JNIEnv * env, jobject, jlong handle,
                                            jstring jsystem_prompt, jbyteArray saved_state) {
    try {
        std::string system_prompt;
        if (!copy_string(env, jsystem_prompt, system_prompt)) {
            if (!env->ExceptionCheck()) throw_java(env, "Invalid session rules");
            return -1;
        }
        std::lock_guard<std::mutex> lock(g_mutex);
        auto it = g_models.find(handle);
        if (it == g_models.end() || it->second.model == nullptr) {
            throw_java(env, "Model is not loaded");
            return -1;
        }
        ModelState & state = it->second;
        free_context(state);
        state.context = make_context(state);
        if (state.context == nullptr) {
            throw_java(env, "Could not create the model context");
            return -1;
        }

        bool restored = false;
        if (saved_state != nullptr) {
            const jsize length = env->GetArrayLength(saved_state);
            if (length > 0 && static_cast<size_t>(length) <= MAX_PERSIST_KV_BYTES) {
                std::vector<uint8_t> bytes(static_cast<size_t>(length));
                env->GetByteArrayRegion(saved_state, 0, length, reinterpret_cast<jbyte *>(bytes.data()));
                if (env->ExceptionCheck()) return -1;
                const size_t loaded = llama_state_seq_set_data(state.context, bytes.data(), bytes.size(), 0);
                restored = loaded == bytes.size() && loaded > 0;
            }
        }
        if (restored) {
            state.session_ready = true;
            return 1;
        }

        free_context(state);
        state.context = make_context(state);
        if (state.context == nullptr || !seed_system(state, system_prompt)) {
            free_context(state);
            throw_java(env, "Could not start a fresh model session");
            return -1;
        }
        return 0;
    } catch (...) {
        throw_java(env, "Could not start the model session");
        return -1;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pocketmind_NativeLlama_generate(JNIEnv * env, jobject, jlong handle,
                                        jstring juser_prompt, jint n_predict, jint n_threads) {
    try {
        std::string user_prompt;
        if (!copy_string(env, juser_prompt, user_prompt)) {
            if (!env->ExceptionCheck()) throw_java(env, "Invalid prompt");
            return nullptr;
        }
        user_prompt = sanitize_untrusted(std::move(user_prompt));

        // The context remains alive between calls. Only the new user message is decoded;
        // previous turns and the stable system rules remain in the KV cache.
        std::lock_guard<std::mutex> lock(g_mutex);
        auto it = g_models.find(handle);
        if (it == g_models.end() || it->second.model == nullptr ||
            it->second.context == nullptr || !it->second.session_ready) {
            throw_java(env, "Model session is not ready");
            return nullptr;
        }
        ModelState & state = it->second;
        const llama_vocab * vocab = llama_model_get_vocab(state.model);
        if (vocab == nullptr) {
            throw_java(env, "Model vocabulary is unavailable");
            return nullptr;
        }

        const std::string formatted = format_one_message(state.model, "user", user_prompt, true);
        std::vector<llama_token> tokens;
        if (formatted.empty() || !tokenize(vocab, formatted, false, true, tokens)) {
            throw_java(env, "Could not tokenize the new message");
            return nullptr;
        }
        state.n_threads = std::clamp(n_threads, 1, 4);
        llama_set_n_threads(state.context, state.n_threads, state.n_threads);
        const uint32_t used = used_positions(state.context);
        if (used + tokens.size() + 1 >= state.n_ctx) {
            throw_java(env, "Conversation context is full; clear saved memory to start a fresh session");
            return nullptr;
        }
        llama_batch prompt_batch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));
        if (llama_decode(state.context, prompt_batch) != 0) {
            free_context(state);
            throw_java(env, "Could not evaluate the new message");
            return nullptr;
        }

        llama_sampler_chain_params chain_params = llama_sampler_chain_default_params();
        std::unique_ptr<llama_sampler, SamplerDeleter> sampler(llama_sampler_chain_init(chain_params));
        if (!sampler) {
            free_context(state);
            throw_java(env, "Could not initialize text sampling");
            return nullptr;
        }
        llama_sampler * top_k = llama_sampler_init_top_k(40);
        llama_sampler * top_p = llama_sampler_init_top_p(0.90f, 1);
        llama_sampler * temperature = llama_sampler_init_temp(0.75f);
        const uint32_t seed = static_cast<uint32_t>(
            std::chrono::steady_clock::now().time_since_epoch().count());
        llama_sampler * distribution = llama_sampler_init_dist(seed);
        if (top_k == nullptr || top_p == nullptr || temperature == nullptr || distribution == nullptr) {
            if (top_k != nullptr) llama_sampler_free(top_k);
            if (top_p != nullptr) llama_sampler_free(top_p);
            if (temperature != nullptr) llama_sampler_free(temperature);
            if (distribution != nullptr) llama_sampler_free(distribution);
            free_context(state);
            throw_java(env, "Could not initialize text sampling");
            return nullptr;
        }
        llama_sampler_chain_add(sampler.get(), top_k);
        llama_sampler_chain_add(sampler.get(), top_p);
        llama_sampler_chain_add(sampler.get(), temperature);
        llama_sampler_chain_add(sampler.get(), distribution);

        const int32_t requested = std::clamp(n_predict, 1, 512);
        const int32_t available = static_cast<int32_t>(state.n_ctx - used - tokens.size());
        const int32_t predict = std::max<int32_t>(1, std::min(requested, available));
        std::string output;
        std::vector<char> piece(256);
        for (int32_t i = 0; i < predict; ++i) {
            llama_token token = llama_sampler_sample(sampler.get(), state.context, -1);
            const bool end_turn = llama_vocab_is_eog(vocab, token);
            if (!end_turn) {
                int32_t piece_size = llama_token_to_piece(vocab, token, piece.data(),
                    static_cast<int32_t>(piece.size()), 0, false);
                if (piece_size < 0 && piece_size != std::numeric_limits<int32_t>::min()) {
                    const size_t needed = static_cast<size_t>(-piece_size);
                    if (needed <= 4096) {
                        piece.resize(needed);
                        piece_size = llama_token_to_piece(vocab, token, piece.data(),
                            static_cast<int32_t>(piece.size()), 0, false);
                    }
                }
                if (piece_size > 0 && static_cast<size_t>(piece_size) <= piece.size()) {
                    output.append(piece.data(), static_cast<size_t>(piece_size));
                }
            }
            // Keep the end-of-turn marker in the live KV cache for the next incremental message.
            llama_batch next_batch = llama_batch_get_one(&token, 1);
            if (llama_decode(state.context, next_batch) != 0) {
                free_context(state);
                throw_java(env, "Could not continue generation");
                return nullptr;
            }
            if (end_turn) break;
        }
        return to_java_utf8(env, output);
    } catch (...) {
        throw_java(env, "Local inference failed");
        return nullptr;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_pocketmind_NativeLlama_saveKvState(JNIEnv * env, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_models.find(handle);
    if (it == g_models.end() || it->second.context == nullptr || !it->second.session_ready) return nullptr;
    const size_t size = llama_state_seq_get_size(it->second.context, 0);
    if (size == 0 || size > MAX_PERSIST_KV_BYTES || size > static_cast<size_t>(std::numeric_limits<jsize>::max())) return nullptr;
    std::vector<uint8_t> bytes(size);
    const size_t copied = llama_state_seq_get_data(it->second.context, bytes.data(), bytes.size(), 0);
    if (copied != size) return nullptr;
    jbyteArray result = env->NewByteArray(static_cast<jsize>(size));
    if (result == nullptr) return nullptr;
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte *>(bytes.data()));
    return env->ExceptionCheck() ? nullptr : result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_pocketmind_NativeLlama_unload(JNIEnv *, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_models.find(handle);
    if (it == g_models.end()) return;
    free_context(it->second);
    if (it->second.model != nullptr) llama_model_free(it->second.model);
    g_models.erase(it);
}
