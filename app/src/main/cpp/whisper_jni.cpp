#include <jni.h>
#include <whisper.h>
#include <atomic>
#include <sstream>
#include <string>
#include <vector>

struct Engine { whisper_context *context; int threads; std::string vad_path; std::atomic<bool> abort{false}; };
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
Java_app_englishdub_engine_WhisperBridge_nativeOpen(JNIEnv *env, jobject, jstring path, jint threads, jstring vad_path) {
    const char *str = env->GetStringUTFChars(path, nullptr);
    auto params = whisper_context_default_params(); params.use_gpu = false;
    auto *ctx = whisper_init_from_file_with_params(str, params);
    env->ReleaseStringUTFChars(path, str);
    std::string vad;
    if (vad_path) { const char *value = env->GetStringUTFChars(vad_path, nullptr); vad = value; env->ReleaseStringUTFChars(vad_path, value); }
    return ctx ? reinterpret_cast<jlong>(new Engine{ctx, threads < 2 ? 1 : 2, vad}) : 0;
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_englishdub_engine_WhisperBridge_nativeTranslate(JNIEnv *env, jobject, jlong handle, jobject audio, jint count, jstring language) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    auto *samples = static_cast<float *>(env->GetDirectBufferAddress(audio));
    if (!samples || count <= 0 || env->GetDirectBufferCapacity(audio) < static_cast<jlong>(count) * sizeof(float)) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Invalid speech buffer."); return nullptr;
    }
    const char *lang = env->GetStringUTFChars(language, nullptr);
    auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = engine->threads; params.translate = true; params.language = lang;
    params.no_context = true; params.print_progress = false; params.print_realtime = false;
    params.print_timestamps = false; params.print_special = false; params.suppress_nst = true;
    params.abort_callback = [](void *data) { return static_cast<Engine *>(data)->abort.load(); };
    params.abort_callback_user_data = engine;
    if (!engine->vad_path.empty()) {
        params.vad = true; params.vad_model_path = engine->vad_path.c_str();
        params.vad_params.threshold = 0.35f; params.vad_params.speech_pad_ms = 200;
    }
    const int result = whisper_full(engine->context, params, samples, count);
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
    json << ']'; const auto output = json.str();
    auto bytes = env->NewByteArray(static_cast<jsize>(output.size()));
    if (bytes) env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(output.size()), reinterpret_cast<const jbyte *>(output.data()));
    return bytes;
}
extern "C" JNIEXPORT void JNICALL
Java_app_englishdub_engine_WhisperBridge_nativeAbort(JNIEnv *, jobject, jlong handle) {
    reinterpret_cast<Engine *>(handle)->abort.store(true);
}
extern "C" JNIEXPORT void JNICALL
Java_app_englishdub_engine_WhisperBridge_nativeClose(JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<Engine *>(handle); whisper_free(engine->context); delete engine;
}
