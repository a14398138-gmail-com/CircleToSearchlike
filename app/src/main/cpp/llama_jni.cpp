// Minimal JNI bridge to llama.cpp for PLaMo 3 Translate prototyping.
// Shared by the standalone translate app and the CircleToSearchlike prototype.
// Java class: com.koike.llama.LlamaEngine
#include <jni.h>
#include <string>
#include <vector>
#include <chrono>
#include <algorithm>
#include <android/log.h>
#include "llama.h"

#define LOG_TAG "plamo_llama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Keep the tail of the llama.cpp log so the UI can show why a load failed.
static std::string g_last_log;
static void plamo_log_callback(enum ggml_log_level level, const char * text, void * /*user_data*/) {
    if (text == nullptr) return;
    g_last_log += text;
    if (g_last_log.size() > 4000) {
        g_last_log.erase(0, g_last_log.size() - 4000);
    }
    if (level == GGML_LOG_LEVEL_ERROR) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "%s", text);
    }
}

namespace {

struct Engine {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    int32_t n_ctx = 0;
    const llama_vocab * vocab = nullptr;
    int32_t last_prompt_tokens = 0;
    int32_t last_gen_tokens = 0;
    double last_prefill_ms = 0.0;
    double last_decode_ms = 0.0;
};

bool g_backend_ready = false;

double now_ms(std::chrono::steady_clock::time_point since) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - since).count();
}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text, bool add_special, bool parse_special) {
    int32_t n = llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, add_special, parse_special);
    std::vector<llama_token> out;
    if (n < 0) {
        out.resize(-n);
        llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), out.data(), (int32_t) out.size(), add_special, parse_special);
    } else {
        out.resize(n);
        llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), out.data(), n, add_special, parse_special);
    }
    return out;
}

std::string token_piece(const llama_vocab * vocab, llama_token tok) {
    char buf[256];
    int32_t n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, true);
    if (n < 0) {
        std::string big(-n, '\0');
        llama_token_to_piece(vocab, tok, big.data(), (int32_t) big.size(), 0, true);
        return big;
    }
    return std::string(buf, n);
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_koike_llama_LlamaEngine_nativeLoad(JNIEnv * env, jobject /*thiz*/, jstring path, jint n_threads, jint n_ctx) {
    g_last_log.clear();
    llama_log_set(plamo_log_callback, nullptr);
    if (!g_backend_ready) {
        llama_backend_init();
        g_backend_ready = true;
    }
    const char * p = env->GetStringUTFChars(path, nullptr);
    auto * e = new Engine();
    llama_model_params mp = llama_model_default_params();
    g_last_log += "[app] loading model file\n";
    e->model = llama_model_load_from_file(p, mp);
    env->ReleaseStringUTFChars(path, p);
    if (e->model == nullptr) {
        LOGE("failed to load model");
        delete e;
        return -2; // model file load failed (missing/corrupt/truncated)
    }
    g_last_log += "[app] model ok, creating context\n";
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) n_ctx;
    cp.n_batch = 1024;
    cp.n_ubatch = 1024;
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    cp.n_threads = n_threads;
    // Prefill is a short compute burst: throw every core at it (decode stays
    // on the performance cores via n_threads).
    cp.n_threads_batch = std::min(8, n_threads + 2);
    e->ctx = llama_init_from_model(e->model, cp);
    if (e->ctx == nullptr) {
        LOGE("failed to create context");
        llama_model_free(e->model);
        delete e;
        return -3; // context creation failed (KV cache / params)
    }
    e->n_ctx = n_ctx;
    e->vocab = llama_model_get_vocab(e->model);
    LOGI("model loaded, ctx=%d threads=%d", n_ctx, n_threads);
    return (jlong) (intptr_t) e;
}

static jstring translate_impl(JNIEnv * env, jlong handle, jstring prompt, jint max_tokens, jobject callback, jmethodID on_token_mid) {
    auto * e = (Engine *) (intptr_t) handle;
    if (e == nullptr || e->ctx == nullptr) return nullptr;
    const char * p = env->GetStringUTFChars(prompt, nullptr);
    std::string prompt_str(p);
    env->ReleaseStringUTFChars(prompt, p);

    // The prompt already contains the BOS special token as text; parse specials, add none.
    std::vector<llama_token> tokens = tokenize(e->vocab, prompt_str, false, true);
    if (tokens.empty()) return nullptr;

    llama_memory_t mem = llama_get_memory(e->ctx);
    llama_memory_clear(mem, true);

    const int32_t n_prompt = (int32_t) tokens.size();
    e->last_prompt_tokens = n_prompt;

    // Never let prompt + generation exceed the context: clamp generation to
    // the remaining budget and fail cleanly when the prompt alone is too long.
    int32_t budget = e->n_ctx - n_prompt - 8;
    if (budget <= 0) {
        LOGE("prompt too long: %d tokens vs ctx %d", n_prompt, e->n_ctx);
        return nullptr;
    }
    const int32_t max_gen = std::min(max_tokens, budget);

    // Prefill in chunks of n_ubatch-friendly size.
    // NOTE: llama_batch_get_one returns pos/n_seq_id/seq_id/logits == nullptr and
    // llama_decode assigns positions automatically from the KV cache state.
    // Never write to batch.pos here (it is null; doing so segfaults).
    auto t0 = std::chrono::steady_clock::now();
    const int32_t chunk = 1024;
    for (int32_t i = 0; i < n_prompt; i += chunk) {
        int32_t n = std::min(chunk, n_prompt - i);
        llama_batch batch = llama_batch_get_one(tokens.data() + i, n);
        if (llama_decode(e->ctx, batch) != 0) {
            LOGE("prefill decode failed at %d", i);
            return nullptr;
        }
    }
    e->last_prefill_ms = now_ms(t0);

    // Greedy decode loop.
    llama_sampler * smpl = llama_sampler_init_greedy();
    std::string out;
    std::string pending; // un-emitted bytes held back for UTF-8 boundary safety
    int32_t n_gen = 0;
    auto t1 = std::chrono::steady_clock::now();
    const llama_token tag_token = 16; // <|plamo:tag|> == EOT for plamo3
    for (int32_t i = 0; i < max_gen; i++) {
        llama_token tok = llama_sampler_sample(smpl, e->ctx, -1);
        if (llama_vocab_is_eog(e->vocab, tok) || tok == tag_token) break;
        std::string piece = token_piece(e->vocab, tok);
        out += piece;
        if (callback != nullptr && on_token_mid != nullptr && !piece.empty()) {
            // UTF-8 safety: a token piece can end in the middle of a
            // multi-byte sequence, and NewStringUTF on a split sequence is
            // invalid modified UTF-8. Buffer the bytes and only emit the
            // longest prefix that ends on a character boundary; the rest is
            // prepended to the next piece (and flushed after the loop).
            pending += piece;
            size_t valid = pending.size();
            // Walk back over trailing continuation bytes (10xxxxxx).
            size_t i = pending.size();
            while (i > 0 && (static_cast<unsigned char>(pending[i - 1]) & 0xC0) == 0x80) i--;
            if (i > 0) {
                unsigned char lead = static_cast<unsigned char>(pending[i - 1]);
                size_t need = (lead < 0x80) ? 1 : (lead < 0xE0) ? 2 : (lead < 0xF0) ? 3 : 4;
                size_t have = pending.size() - (i - 1);
                if (have < need) valid = i - 1; // incomplete trailing sequence: hold it
            } else if (!pending.empty()) {
                valid = 0; // only continuation bytes so far: hold everything
            }
            if (valid > 0) {
                std::string chunk = pending.substr(0, valid);
                pending.erase(0, valid);
                jstring js = env->NewStringUTF(chunk.c_str());
                env->CallVoidMethod(callback, on_token_mid, js);
                env->DeleteLocalRef(js);
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                    break;
                }
            }
        }
        n_gen++;
        llama_batch batch = llama_batch_get_one(&tok, 1);
        if (llama_decode(e->ctx, batch) != 0) {
            LOGE("decode failed");
            break;
        }
    }
    // Flush any held-back bytes (a truncated final sequence is emitted
    // as-is; the accumulated `out` returned below is authoritative).
    if (callback != nullptr && on_token_mid != nullptr && !pending.empty()) {
        jstring js = env->NewStringUTF(pending.c_str());
        env->CallVoidMethod(callback, on_token_mid, js);
        env->DeleteLocalRef(js);
        if (env->ExceptionCheck()) env->ExceptionClear();
        pending.clear();
    }
    e->last_decode_ms = now_ms(t1);
    e->last_gen_tokens = n_gen;
    llama_sampler_free(smpl);
    LOGI("prompt %d tok (%.1f ms), gen %d tok (%.1f ms)", n_prompt, e->last_prefill_ms, n_gen, e->last_decode_ms);
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_koike_llama_LlamaEngine_nativeTranslateStream(JNIEnv * env, jobject /*thiz*/, jlong handle, jstring prompt, jint max_tokens, jobject callback) {
    jmethodID mid = nullptr;
    if (callback != nullptr) {
        jclass cls = env->GetObjectClass(callback);
        mid = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
        env->DeleteLocalRef(cls);
        if (mid == nullptr) {
            env->ExceptionClear();
            return nullptr;
        }
    }
    return translate_impl(env, handle, prompt, max_tokens, callback, mid);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_koike_llama_LlamaEngine_nativeStats(JNIEnv * env, jobject /*thiz*/, jlong handle) {
    auto * e = (Engine *) (intptr_t) handle;
    if (e == nullptr) return env->NewStringUTF("0,0,0,0");
    char buf[128];
    snprintf(buf, sizeof(buf), "%d,%d,%.1f,%.1f", e->last_prompt_tokens, e->last_gen_tokens, e->last_prefill_ms, e->last_decode_ms);
    return env->NewStringUTF(buf);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_koike_llama_LlamaEngine_nativeLastError(JNIEnv * env, jobject /*thiz*/) {
    return env->NewStringUTF(g_last_log.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_koike_llama_LlamaEngine_nativeFree(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto * e = (Engine *) (intptr_t) handle;
    if (e == nullptr) return;
    if (e->ctx) llama_free(e->ctx);
    if (e->model) llama_model_free(e->model);
    delete e;
}
