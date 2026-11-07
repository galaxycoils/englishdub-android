package app.englishdub.engine

import org.json.JSONArray
import java.io.Closeable
import kotlinx.coroutines.*

class WhisperBridge(modelPath: String, threads: Int = 2, vadPath: String? = null) : DialogueRecognizer {
    @Volatile private var handle = nativeOpen(modelPath, threads, vadPath)
    init { check(handle != 0L) { "Could not load the speech pack. Free some memory or download the pack again." } }
    override suspend fun translate(samples: java.nio.ByteBuffer, count: Int, language: String): List<TextCue> = coroutineScope {
        val inference = async(Dispatchers.IO) {
            val output = try { nativeTranslate(handle, samples, count, language) }
                catch (e: Exception) { currentCoroutineContext().ensureActive(); throw e }
            currentCoroutineContext().ensureActive()
            val result = JSONArray(String(output, Charsets.UTF_8))
            (0 until result.length()).map { i -> result.getJSONObject(i).let {
                TextCue(it.getLong("start"), it.getLong("end"), Subtitles.clean(it.getString("text")))
            } }.filter { it.text.isNotBlank() && it.endMs > it.startMs }
        }
        try { inference.await() }
        catch (e: CancellationException) { abort(); throw e }
    }
    @Synchronized override fun abort() { if (handle != 0L) nativeAbort(handle) }
    @Synchronized override fun close() { if (handle != 0L) { nativeClose(handle); handle = 0 } }
    private external fun nativeOpen(path: String, threads: Int, vadPath: String?): Long
    private external fun nativeTranslate(handle: Long, samples: java.nio.ByteBuffer, count: Int, language: String): ByteArray
    private external fun nativeAbort(handle: Long)
    private external fun nativeClose(handle: Long)
    companion object { init { System.loadLibrary("englishdub") } }
}
