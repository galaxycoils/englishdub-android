package app.englishdub.engine

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.io.Closeable
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OfflineTranslation(language: String) : Closeable {
    private val translator = Translation.getClient(TranslatorOptions.Builder()
        .setSourceLanguage(language).setTargetLanguage(TranslateLanguage.ENGLISH).build())
    suspend fun translate(text: String) = translator.translate(text).await()
    override fun close() = translator.close()
    companion object {
        val languages = linkedMapOf("en" to "English", "hi" to "Hindi", "ur" to "Urdu", "ta" to "Tamil")
        private val manager get() = RemoteModelManager.getInstance()
        suspend fun installed(): Set<String> = manager.getDownloadedModels(TranslateRemoteModel::class.java).await().map { it.language }.toSet()
        suspend fun download(language: String) {
            manager.download(TranslateRemoteModel.Builder(language).build(), DownloadConditions.Builder().requireWifi().build()).await()
        }
        suspend fun delete(language: String) = manager.deleteDownloadedModel(TranslateRemoteModel.Builder(language).build()).await()
    }
}

class OfflineVoice private constructor(private val tts: TextToSpeech) : Closeable {
    suspend fun synthesize(text: String, file: File, speed: Float = 1f) = withContext(Dispatchers.Main) {
        require(text.length <= TextToSpeech.getMaxSpeechInputLength()) { "A subtitle line is too long for the offline voice. Split it into shorter cues." }
        withTimeout(90_000) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val id = java.util.UUID.randomUUID().toString()
                tts.setSpeechRate(speed)
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) { if (utteranceId == id && continuation.isActive) continuation.resume(Unit) }
                    @Deprecated("Android callback")
                    override fun onError(utteranceId: String?) { if (utteranceId == id && continuation.isActive) continuation.resumeWithException(IllegalStateException("The offline voice could not generate speech. Check installed English voice data.")) }
                    override fun onError(utteranceId: String?, errorCode: Int) = onError(utteranceId)
                })
                continuation.invokeOnCancellation { tts.stop() }
                if (tts.synthesizeToFile(text, null, file, id) == TextToSpeech.ERROR && continuation.isActive) {
                    continuation.resumeWithException(IllegalStateException("Could not start offline speech generation."))
                }
            }
        }
    }
    override fun close() { tts.stop(); tts.shutdown() }
    companion object {
        suspend fun create(context: Context): OfflineVoice = withContext(Dispatchers.Main) {
            var engine: TextToSpeech? = null
            try {
                withTimeout(20_000) {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        engine = TextToSpeech(context.applicationContext) { status ->
                            if (continuation.isActive) {
                                if (status == TextToSpeech.SUCCESS) continuation.resume(Unit)
                                else continuation.resumeWithException(IllegalStateException("No text-to-speech engine is available. Install an offline English voice in Android settings."))
                            }
                        }
                    }
                }
                val tts = engine!!
                val voice = tts.voices?.filter {
                    it.locale.language == "en" && !it.isNetworkConnectionRequired && !it.features.orEmpty().contains("notInstalled")
                }?.sortedWith(compareBy({ it.locale.country != "US" }, { it.latency }, { -it.quality }))?.firstOrNull()
                    ?: error("No offline English voice is installed. Open Voice settings, download English voice data, then retry.")
                check(tts.setVoice(voice) == TextToSpeech.SUCCESS) { "Could not select the offline English voice." }
                OfflineVoice(tts)
            } catch (e: Throwable) { engine?.shutdown(); throw e }
        }
    }
}
