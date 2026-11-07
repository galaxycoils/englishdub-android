#include <jni.h>
#include <whisper.h>
#include <atomic>
#include <sstream>
#include <string>
#include <vector>

struct Engine { whisper_context *context; std::atomic<bool> abort{false}; };
static std::string escaped(const char *text) {
    std::string out;
    for (const unsigned char ch : std::string(text)) {
        if (ch == '"' || ch == '\\') { out += '\\'; out += ch; }
        else if (ch == '\n') out += "\\n";
        else if (ch == '\r') out += "\\r";
        else if (ch == '\t') out += "\\t";
        else if (ch >= 0x20) out += ch;
    }
    return out;
}
extern "C" JNIEXPORT jlong JNICALL
Java_app_englishdub_engine_WhisperBridge_nativeOpen(JNIEnv *env, jobject, jstring path) {
    const char *str = env->GetStringUTFChars(path, nullptr);
    auto params = whisper_context_default_params(); params.use_gpu = false;
    auto *ctx = whisper_init_from_file_with_params(str, params);
    env->ReleaseStringUTFChars(path, str);
    return ctx ? reinterpret_cast<jlong>(new Engine{ctx}) : 0;
}
extern "C" JNIEXPORT jstring JNICALL
Java_app_englishdub_engine_WhisperBridge_nativeTranslate(JNIEnv *env, jobject, jlong handle, jfloatArray audio, jstring language) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    const int count = env->GetArrayLength(audio);
    std::vector<float> samples(count);
    env->GetFloatArrayRegion(audio, 0, count, samples.data());
    const char *lang = env->GetStringUTFChars(language, nullptr);
    auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = 2; params.translate = true; params.language = lang;
    params.no_context = true; params.print_progress = false; params.print_realtime = false;
    params.print_timestamps = false; params.print_special = false; params.suppress_nst = true;
    params.abort_callback = [](void *data) { return static_cast<Engine *>(data)->abort.load(); };
    params.abort_callback_user_data = engine;
    const int result = whisper_full(engine->context, params, samples.data(), count);
    env->ReleaseStringUTFChars(language, lang);
    if (result != 0) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Speech recognition stopped or failed. Resume to retry this segment.");
        return nullptr;
    }
    std::ostringstream json; json << '[';
    const int size = whisper_full_n_segments(engine->context);
    for (int i = 0; i < size; ++i) {
        if (i) json << ',';
        json << "{\"start\":" << whisper_full_get_segment_t0(engine->context, i) * 10
             << ",\"end\":" << whisper_full_get_segment_t1(engine->context, i) * 10
             << ",\"text\":\"" << escaped(whisper_full_get_segment_text(engine->context, i)) << "\"}";
    }
    json << ']'; return env->NewStringUTF(json.str().c_str());
}
extern "C" JNIEXPORT void JNICALL
Java_app_englishdub_engine_WhisperBridge_nativeAbort(JNIEnv *, jobject, jlong handle) {
    reinterpret_cast<Engine *>(handle)->abort.store(true);
}
extern "C" JNIEXPORT void JNICALL
Java_app_englishdub_engine_WhisperBridge_nativeClose(JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<Engine *>(handle); whisper_free(engine->context); delete engine;
}
