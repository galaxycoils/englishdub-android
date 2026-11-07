package app.englishdub.engine

import org.json.JSONArray
import java.io.Closeable
import kotlinx.coroutines.*

class WhisperBridge(modelPath: String) : Closeable {
    @Volatile private var handle = nativeOpen(modelPath)
    init { check(handle != 0L) { "Could not load the speech pack. Free some memory or download the pack again." } }
    suspend fun translate(samples: FloatArray, language: String): List<TextCue> = coroutineScope {
        val inference = async(Dispatchers.IO) {
            val output = try { nativeTranslate(handle, samples, language) }
                catch (e: Exception) { currentCoroutineContext().ensureActive(); throw e }
            currentCoroutineContext().ensureActive()
            val result = JSONArray(output)
            (0 until result.length()).map { i -> result.getJSONObject(i).let {
                TextCue(it.getLong("start"), it.getLong("end"), Subtitles.clean(it.getString("text")))
            } }.filter { it.text.isNotBlank() && it.endMs > it.startMs }
        }
        try { inference.await() }
        catch (e: CancellationException) { abort(); throw e }
    }
    @Synchronized fun abort() { if (handle != 0L) nativeAbort(handle) }
    @Synchronized override fun close() { if (handle != 0L) { nativeClose(handle); handle = 0 } }
    private external fun nativeOpen(path: String): Long
    private external fun nativeTranslate(handle: Long, samples: FloatArray, language: String): String
    private external fun nativeAbort(handle: Long)
    private external fun nativeClose(handle: Long)
    companion object { init { System.loadLibrary("englishdub") } }
}
