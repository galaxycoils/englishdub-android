package app.englishdub.engine

import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class SpeechAsset(val file: File, val durationMs: Long)

class CueAudioCache(private val directory: File, private val voiceId: String) {
    init { directory.mkdirs() }
    suspend fun get(text: String, rate: Float, synthesize: suspend (File, Float) -> Unit): SpeechAsset {
        val key = PreparationIdentity.digest(text, voiceId, rate.toString(), PreparationIdentity.PIPELINE)
        val pcm = File(directory, "$key.pcm")
        val marker = File(directory, "$key.complete")
        if (!pcm.exists() || !marker.exists() || marker.readText() != pcm.length().toString() || pcm.length() % 2 != 0L) {
            val wav = File(directory, "$key.wav")
            val partial = File(directory, "$key.partial")
            try {
                synthesize(wav, rate)
                currentCoroutineContext().ensureActive()
                AudioTools.wavToPcm(wav, partial)
                currentCoroutineContext().ensureActive()
                check(partial.renameTo(pcm)) { "Could not save generated speech." }
                marker.writeText(pcm.length().toString())
            } finally { wav.delete(); partial.delete() }
        }
        require(pcm.length() > 0) { "The offline voice generated no audio." }
        return SpeechAsset(pcm, pcm.length() * 1000 / (AudioTools.RATE * 2))
    }
}
