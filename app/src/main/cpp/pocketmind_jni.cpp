#include <jni.h>
#include <llama.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <string>
#include <sys/stat.h>
#include <vector>

namespace {
constexpr uint64_t kMaxModelBytes = 1200ULL * 1024ULL * 1024ULL;
llama_model *g_model = nullptr;
llama_context *g_context = nullptr;
std::atomic<bool> g_cancelled{false};
std::atomic<bool> g_generating{false};

void throwJava(JNIEnv *env, const char *message, const char *type = "java/lang/IllegalStateException") {
    jclass cls = env->FindClass(type);
    if (cls != nullptr) env->ThrowNew(cls, message);
}

std::string toUtf8(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const jsize length = env->GetStringLength(value);
    const jchar *chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string out;
    out.reserve(static_cast<size_t>(length) * 3);
    for (jsize i = 0; i < length; ++i) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < length && chars[i + 1] >= 0xDC00 && chars[i + 1] <= 0xDFFF) {
            cp = 0x10000 + ((cp - 0xD800) << 10) + (chars[++i] - 0xDC00);
        } else if (cp >= 0xD800 && cp <= 0xDFFF) {
            cp = 0xFFFD;
        }
        if (cp <= 0x7F) out.push_back(static_cast<char>(cp));
        else if (cp <= 0x7FF) {
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp <= 0xFFFF) {
            out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }
    env->ReleaseStringChars(value, chars);
    return out;
}

std::vector<jchar> utf8ToUtf16(const std::string &s) {
    std::vector<jchar> out;
    for (size_t i = 0; i < s.size();) {
        const uint8_t a = static_cast<uint8_t>(s[i]);
        uint32_t cp = 0xFFFD;
        size_t n = 1;
        if (a < 0x80) cp = a;
        else if ((a & 0xE0) == 0xC0 && i + 1 < s.size()) {
            cp = ((a & 0x1F) << 6) | (static_cast<uint8_t>(s[i + 1]) & 0x3F); n = 2;
        } else if ((a & 0xF0) == 0xE0 && i + 2 < s.size()) {
            cp = ((a & 0x0F) << 12) | ((static_cast<uint8_t>(s[i + 1]) & 0x3F) << 6) | (static_cast<uint8_t>(s[i + 2]) & 0x3F); n = 3;
        } else if ((a & 0xF8) == 0xF0 && i + 3 < s.size()) {
            cp = ((a & 0x07) << 18) | ((static_cast<uint8_t>(s[i + 1]) & 0x3F) << 12) | ((static_cast<uint8_t>(s[i + 2]) & 0x3F) << 6) | (static_cast<uint8_t>(s[i + 3]) & 0x3F); n = 4;
        }
        if (cp <= 0xFFFF) out.push_back(static_cast<jchar>(cp));
        else {
            cp -= 0x10000;
            out.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            out.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        }
        i += n;
    }
    return out;
}

jstring newJavaString(JNIEnv *env, const std::string &value) {
    const auto chars = utf8ToUtf16(value);
    return env->NewString(chars.data(), static_cast<jsize>(chars.size()));
}

bool abortCallback(void *) {
    return g_cancelled.load();
}

int tokenize(const llama_vocab *vocab, const std::string &text, std::vector<llama_token> &tokens, bool addSpecial) {
    const auto max = static_cast<int32_t>(std::max<size_t>(64, text.size() * 2 + 16));
    tokens.resize(static_cast<size_t>(max));
    int32_t count = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), tokens.data(), max, addSpecial, true);
    if (count < 0 && count != INT32_MIN) {
        tokens.resize(static_cast<size_t>(-count));
        count = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), tokens.data(), static_cast<int32_t>(tokens.size()), addSpecial, true);
    }
    if (count < 0) return count;
    tokens.resize(static_cast<size_t>(count));
    return count;
}

void deliver(JNIEnv *env, jobject sink, jmethodID method, const std::string &piece, int outputTokens) {
    if (env->ExceptionCheck()) return;
    jstring chunk = newJavaString(env, piece);
    if (chunk != nullptr) {
        env->CallVoidMethod(sink, method, chunk, static_cast<jint>(outputTokens));
        env->DeleteLocalRef(chunk);
    }
}

size_t completeUtf8PrefixLength(const std::string &bytes) {
    size_t i = 0;
    while (i < bytes.size()) {
        const uint8_t lead = static_cast<uint8_t>(bytes[i]);
        if (lead < 0x80) {
            ++i;
            continue;
        }
        size_t length = 1;
        if (lead >= 0xC2 && lead <= 0xDF) length = 2;
        else if (lead >= 0xE0 && lead <= 0xEF) length = 3;
        else if (lead >= 0xF0 && lead <= 0xF4) length = 4;
        else {
            ++i;
            continue;
        }
        if (i + length > bytes.size()) break;
        bool valid = true;
        for (size_t j = 1; j < length; ++j) {
            if ((static_cast<uint8_t>(bytes[i + j]) & 0xC0) != 0x80) {
                valid = false;
                break;
            }
        }
        i += valid ? length : 1;
    }
    return i;
}

bool decodeOne(llama_batch &batch, llama_token token, llama_pos position) {
    batch.n_tokens = 1;
    batch.token[0] = token;
    batch.pos[0] = position;
    batch.n_seq_id[0] = 1;
    batch.seq_id[0][0] = 0;
    batch.logits[0] = 1;
    return llama_decode(g_context, batch) == 0;
}

std::string formatPlainPrompt(const std::string &system, const std::string &user) {
    if (system.empty()) return user;
    return system + "\nUser question: " + user + "\nAssistant:";
}

std::string formatModelPrompt(const std::string &system, const std::string &user) {
    const char *tmpl = g_model == nullptr ? nullptr : llama_model_chat_template(g_model, nullptr);
    if (tmpl == nullptr) return formatPlainPrompt(system, user);

    std::vector<llama_chat_message> messages;
    if (!system.empty()) messages.push_back({"system", system.c_str()});
    if (!user.empty() || messages.empty()) messages.push_back({"user", user.c_str()});

    const size_t capacity = std::max<size_t>(256, (system.size() + user.size() + 64) * 2);
    std::vector<char> buffer(capacity);
    int32_t size = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true,
                                              buffer.data(), static_cast<int32_t>(buffer.size()));
    if (size < 0) return formatPlainPrompt(system, user);
    if (static_cast<size_t>(size) > buffer.size()) {
        buffer.resize(static_cast<size_t>(size) + 1);
        size = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true,
                                         buffer.data(), static_cast<int32_t>(buffer.size()));
    }
    if (size < 0 || static_cast<size_t>(size) > buffer.size()) return formatPlainPrompt(system, user);
    return std::string(buffer.data(), static_cast<size_t>(size));
}
}

extern "C" JNIEXPORT void JNICALL
Java_com_pocketmind_engine_LlamaEngine_nativeLoad(JNIEnv *env, jobject, jstring jpath, jint nCtx, jint nBatch, jint nUbatch, jint threads) {
    const std::string path = toUtf8(env, jpath);
    struct stat st{};
    if (path.empty() || stat(path.c_str(), &st) != 0 || st.st_size <= 0) {
        throwJava(env, "Model file is missing or unreadable", "java/lang/IllegalArgumentException"); return;
    }
    if (static_cast<uint64_t>(st.st_size) > kMaxModelBytes) {
        throwJava(env, "Model exceeds the 1.2 GB limit", "java/lang/IllegalArgumentException"); return;
    }
    if (nCtx < 128 || nCtx > 1024 || nBatch < 32 || nUbatch < 16 || nUbatch > nBatch || threads < 1 || threads > 8) {
        throwJava(env, "Invalid context, batch, or thread settings", "java/lang/IllegalArgumentException"); return;
    }
    if (g_context != nullptr) { llama_free(g_context); g_context = nullptr; }
    if (g_model != nullptr) { llama_model_free(g_model); g_model = nullptr; }
    llama_backend_init();
    auto modelParams = llama_model_default_params();
    modelParams.n_gpu_layers = 0;
    modelParams.load_mode = LLAMA_LOAD_MODE_MMAP;
    g_model = llama_model_load_from_file(path.c_str(), modelParams);
    if (g_model == nullptr) { throwJava(env, "llama.cpp could not load this GGUF model"); return; }
    auto contextParams = llama_context_default_params();
    contextParams.n_ctx = static_cast<uint32_t>(nCtx);
    contextParams.n_batch = static_cast<uint32_t>(std::min(nBatch, nCtx));
    contextParams.n_ubatch = static_cast<uint32_t>(std::min(nUbatch, nBatch));
    contextParams.n_threads = threads;
    contextParams.n_threads_batch = threads;
    contextParams.abort_callback = abortCallback;
    g_context = llama_init_from_model(g_model, contextParams);
    if (g_context == nullptr) {
        llama_model_free(g_model); g_model = nullptr;
        throwJava(env, "llama.cpp could not create an inference context");
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pocketmind_engine_LlamaEngine_nativeFormatPrompt(JNIEnv *env, jobject, jstring jsystem, jstring juser, jint templateId) {
    if (g_model == nullptr || g_context == nullptr) { throwJava(env, "Load a GGUF model before formatting its prompt"); return nullptr; }
    if (templateId < 0 || templateId > 4) { throwJava(env, "Unknown prompt template", "java/lang/IllegalArgumentException"); return nullptr; }

    const std::string system = toUtf8(env, jsystem);
    const std::string user = toUtf8(env, juser);
    std::string prompt;
    switch (templateId) {
        case 0:
            prompt = formatModelPrompt(system, user);
            break;
        case 1:
            prompt = formatPlainPrompt(system, user);
            break;
        case 2:
            if (!system.empty()) prompt += "<|im_start|>system\n" + system + "<|im_end|>\n";
            prompt += "<|im_start|>user\n" + user + "<|im_end|>\n<|im_start|>assistant\n";
            break;
        case 3:
            if (!system.empty()) prompt += "<|start_header_id|>system<|end_header_id|>\n\n" + system + "<|eot_id|>";
            prompt += "<|start_header_id|>user<|end_header_id|>\n\n" + user +
                      "<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n";
            break;
        case 4:
            prompt = "<bos><start_of_turn>user\n";
            if (!system.empty()) prompt += system + "\n\n";
            prompt += user + "<end_of_turn>\n<start_of_turn>model\n";
            break;
    }
    return newJavaString(env, prompt);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pocketmind_engine_LlamaEngine_nativeGenerate(JNIEnv *env, jobject, jstring jprompt, jint maxTokens, jstring jgrammar, jfloat temperature, jint topK, jfloat topP, jobject sink) {
    if (g_model == nullptr || g_context == nullptr) { throwJava(env, "Load a GGUF model first"); return nullptr; }
    if (maxTokens < 1 || maxTokens > 512 || sink == nullptr || !std::isfinite(temperature) || temperature < 0.1f || temperature > 2.0f ||
        topK < 1 || topK > 100 || !std::isfinite(topP) || topP < 0.05f || topP > 1.0f) {
        throwJava(env, "Invalid generation or sampler settings", "java/lang/IllegalArgumentException"); return nullptr;
    }
    bool expected = false;
    if (!g_generating.compare_exchange_strong(expected, true)) { throwJava(env, "A generation is already in progress"); return nullptr; }
    struct Reset { ~Reset() { g_generating.store(false); } } reset;
    g_cancelled.store(false);
    const std::string prompt = toUtf8(env, jprompt);
    const std::string grammar = toUtf8(env, jgrammar);
    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    std::vector<llama_token> tokens;
    int count = tokenize(vocab, prompt, tokens, true);
    if (count <= 0) { throwJava(env, "Could not tokenize the prompt"); return nullptr; }
    const uint32_t nCtx = llama_n_ctx(g_context);
    int generationLimit = std::min<int>(maxTokens, static_cast<int>(nCtx) - 1);
    if (generationLimit < 1) { throwJava(env, "Context has no room for generation"); return nullptr; }
    const int promptLimit = std::max(1, static_cast<int>(nCtx) - generationLimit);
    if (static_cast<int>(tokens.size()) > promptLimit) tokens.erase(tokens.begin(), tokens.end() - promptLimit);
    llama_memory_clear(llama_get_memory(g_context), true);

    llama_batch batch = llama_batch_init(std::min<int>(llama_n_batch(g_context), static_cast<int>(nCtx)), 0, 1);
    if (batch.token == nullptr) { throwJava(env, "Could not allocate a generation batch"); return nullptr; }
    bool ok = true;
    const int capacity = std::min<int>(llama_n_batch(g_context), static_cast<int>(nCtx));
    for (int offset = 0; offset < static_cast<int>(tokens.size()) && ok; offset += capacity) {
        const int n = std::min(capacity, static_cast<int>(tokens.size()) - offset);
        batch.n_tokens = n;
        for (int i = 0; i < n; ++i) {
            const int idx = offset + i;
            batch.token[i] = tokens[static_cast<size_t>(idx)];
            batch.pos[i] = idx;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = (idx == static_cast<int>(tokens.size()) - 1) ? 1 : 0;
        }
        ok = llama_decode(g_context, batch) == 0;
    }
    if (!ok) { llama_batch_free(batch); throwJava(env, "Prompt evaluation failed"); return nullptr; }

    jclass sinkClass = env->GetObjectClass(sink);
    jmethodID onToken = sinkClass == nullptr ? nullptr : env->GetMethodID(sinkClass, "onToken", "(Ljava/lang/String;I)V");
    if (onToken == nullptr) { llama_batch_free(batch); throwJava(env, "Token callback is unavailable"); return nullptr; }
    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (sampler == nullptr) { llama_batch_free(batch); throwJava(env, "Could not initialize sampler"); return nullptr; }
    if (!grammar.empty()) {
        llama_sampler *grammarSampler = llama_sampler_init_grammar(vocab, grammar.c_str(), "root");
        if (grammarSampler == nullptr) {
            llama_sampler_free(sampler); llama_batch_free(batch); throwJava(env, "Planner grammar is invalid", "java/lang/IllegalArgumentException"); return nullptr;
        }
        llama_sampler_chain_add(sampler, grammarSampler);
    }
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(topK));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(42));

    std::string generated;
    std::string pendingUtf8;
    int generatedTokenCount = 0;
    const int basePosition = static_cast<int>(tokens.size());
    for (int i = 0; i < generationLimit && !g_cancelled.load() && !env->ExceptionCheck(); ++i) {
        const llama_token token = llama_sampler_sample(sampler, g_context, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        ++generatedTokenCount;
        std::vector<char> piece(256);
        int nPiece = llama_token_to_piece(vocab, token, piece.data(), static_cast<int32_t>(piece.size()), 0, true);
        if (nPiece < 0) {
            piece.resize(static_cast<size_t>(-nPiece));
            nPiece = llama_token_to_piece(vocab, token, piece.data(), static_cast<int32_t>(piece.size()), 0, true);
        }
        std::string callbackText;
        if (nPiece > 0) {
            std::string bytes(piece.data(), static_cast<size_t>(nPiece));
            generated += bytes;
            pendingUtf8 += bytes;
            const size_t completeLength = completeUtf8PrefixLength(pendingUtf8);
            if (completeLength > 0) {
                callbackText = pendingUtf8.substr(0, completeLength);
                pendingUtf8.erase(0, completeLength);
            }
        }
        deliver(env, sink, onToken, callbackText, generatedTokenCount);
        if (env->ExceptionCheck()) break;
        if (!decodeOne(batch, token, basePosition + i)) {
            if (!g_cancelled.load()) throwJava(env, "Token evaluation failed");
            break;
        }
    }
    if (!pendingUtf8.empty()) deliver(env, sink, onToken, pendingUtf8, generatedTokenCount);
    llama_sampler_free(sampler);
    llama_batch_free(batch);
    if (env->ExceptionCheck()) return nullptr;
    return newJavaString(env, generated);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_pocketmind_engine_LlamaEngine_nativeCountTokens(JNIEnv *env, jobject, jstring jtext) {
    if (g_model == nullptr) { throwJava(env, "Load a GGUF model first"); return -1; }
    std::vector<llama_token> tokens;
    const int count = tokenize(llama_model_get_vocab(g_model), toUtf8(env, jtext), tokens, true);
    if (count < 0) { throwJava(env, "Text tokenization failed"); return -1; }
    return count;
}

extern "C" JNIEXPORT void JNICALL
Java_com_pocketmind_engine_LlamaEngine_nativeCancel(JNIEnv *, jobject) { g_cancelled.store(true); }

extern "C" JNIEXPORT void JNICALL
Java_com_pocketmind_engine_LlamaEngine_nativeUnload(JNIEnv *, jobject) {
    g_cancelled.store(true);
    if (g_context != nullptr) { llama_free(g_context); g_context = nullptr; }
    if (g_model != nullptr) { llama_model_free(g_model); g_model = nullptr; }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pocketmind_engine_LlamaEngine_nativeStats(JNIEnv *env, jobject) {
    if (g_model == nullptr || g_context == nullptr) return newJavaString(env, "unloaded");
    return newJavaString(env, std::string("loaded; context=") + std::to_string(llama_n_ctx(g_context)) + "; threads=" + std::to_string(llama_n_threads(g_context)));
}
