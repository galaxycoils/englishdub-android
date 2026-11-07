package app.englishdub.engine

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class VoiceOption(val name: String, val label: String, val localeTag: String)

class OfflineTranslation(language: String) : TextTranslator {
    private val translator = Translation.getClient(TranslatorOptions.Builder()
        .setSourceLanguage(language).setTargetLanguage(TranslateLanguage.ENGLISH).build())
    override suspend fun translate(text: String) = translator.translate(text).await()
    override fun close() = translator.close()
    companion object {
        val languages = linkedMapOf("en" to "English", "hi" to "Hindi", "ur" to "Urdu", "ta" to "Tamil")
        private val manager get() = RemoteModelManager.getInstance()
        suspend fun installed(): Set<String> = try {
            withTimeout(10000) { manager.getDownloadedModels(TranslateRemoteModel::class.java).await().map { it.language }.toSet() }
        } catch (_: TimeoutCancellationException) { error("Translation pack status is unavailable. Check Offline setup and retry.") }
        suspend fun download(language: String) {
            manager.download(TranslateRemoteModel.Builder(language).build(), DownloadConditions.Builder().requireWifi().build()).await()
        }
        suspend fun delete(language: String) = manager.deleteDownloadedModel(TranslateRemoteModel.Builder(language).build()).await()
    }
}

class OfflineVoice private constructor(
    private val tts: TextToSpeech,
    private val engineVersion: Long,
    val selectedVoiceName: String,
) : VoiceSynthesizer {
    override val identity: String get() = PreparationIdentity.digest(tts.defaultEngine.orEmpty(), selectedVoiceName, engineVersion.toString(), PreparationIdentity.PIPELINE)
    override suspend fun synthesize(text: String, file: File, speed: Float) = withContext(Dispatchers.Main) {
        require(text.length <= TextToSpeech.getMaxSpeechInputLength()) { "A subtitle line is too long for the offline voice. Split it into shorter cues." }
        try { withTimeout(90_000) {
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
        } } catch (_: TimeoutCancellationException) {
            tts.stop()
            error("The offline voice stopped responding. Check Android voice settings or select another installed offline English engine, then resume.")
        }
    }

    suspend fun speakSample(sampleText: String = "This offline English voice is ready for your film.") = withContext(Dispatchers.Main) {
        withTimeout(15_000) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val id = java.util.UUID.randomUUID().toString()
                tts.setSpeechRate(1f)
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) { if (utteranceId == id && continuation.isActive) continuation.resume(Unit) }
                    @Deprecated("Android callback")
                    override fun onError(utteranceId: String?) { if (utteranceId == id && continuation.isActive) continuation.resumeWithException(IllegalStateException("Could not play voice sample.")) }
                    override fun onError(utteranceId: String?, errorCode: Int) = onError(utteranceId)
                })
                continuation.invokeOnCancellation { tts.stop() }
                if (tts.speak(sampleText, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.ERROR && continuation.isActive) {
                    continuation.resumeWithException(IllegalStateException("Could not start voice sample."))
                }
            }
        }
    }

    override fun close() { tts.stop(); tts.shutdown() }
    companion object {
        private fun eligibleVoices(tts: TextToSpeech): List<Voice> =
            tts.voices?.filter {
                it.locale.language == "en" && !it.isNetworkConnectionRequired && !it.features.orEmpty().contains("notInstalled")
            }?.sortedWith(compareBy({ it.locale.country != "US" }, { it.latency }, { -it.quality }, { it.name })).orEmpty()

        suspend fun availableVoices(context: Context): List<VoiceOption> = withContext(Dispatchers.Main) {
            create(context).use { voice ->
                eligibleVoices(voice.tts).map { v ->
                    val country = v.locale.displayCountry.takeIf { it.isNotBlank() } ?: v.locale.toLanguageTag()
                    VoiceOption(v.name, "${v.name} ($country · offline)", v.locale.toLanguageTag())
                }
            }
        }

        suspend fun playSample(context: Context, voiceName: String? = null) {
            create(context, voiceName).use { it.speakSample() }
        }

        suspend fun verify(context: Context, voiceName: String? = null) {
            val wav = File.createTempFile("voice-check", ".wav", context.cacheDir)
            val pcm = File.createTempFile("voice-check", ".pcm", context.cacheDir)
            try {
                withTimeout(20000) {
                    create(context, voiceName).use { it.synthesize("English voice check.", wav) }
                    check(AudioTools.wavToPcm(wav, pcm) > 0) { "The offline voice generated no audio." }
                }
            } catch (_: TimeoutCancellationException) {
                error("The offline voice did not respond. Select another installed offline English engine in Android voice settings.")
            } finally { wav.delete(); pcm.delete() }
        }

        suspend fun create(context: Context, preferredVoiceName: String? = null): OfflineVoice = withContext(Dispatchers.Main) {
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
                val voices = eligibleVoices(tts)
                val voice = (if (!preferredVoiceName.isNullOrBlank()) voices.firstOrNull { it.name == preferredVoiceName } else null)
                    ?: voices.firstOrNull()
                    ?: error("No offline English voice is installed. Open Voice settings, download English voice data, then retry.")
                check(tts.setVoice(voice) == TextToSpeech.SUCCESS) { "Could not select the offline English voice." }
                val engineVersion = try {
                    val info = context.packageManager.getPackageInfo(tts.defaultEngine, 0)
                    if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
                } catch (_: Exception) { 0L }
                OfflineVoice(tts, engineVersion, voice.name)
            } catch (e: Throwable) { engine?.shutdown(); throw e }
        }
    }
}

