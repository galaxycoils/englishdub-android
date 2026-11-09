package app.englishdub.engine

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class SpeechAsset(val file: File, val durationMs: Long)

class CueAudioCache(
    internal val directory: File,
    private val voiceId: String,
    private val compressed: Boolean = false,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private data class Entry(val audio: File, val marker: File, val bytes: Long)
    // One owner processes preparation at a time. Reconcile disk once per cache instance,
    // then update size and LRU incrementally instead of listing every file for every cue.
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var totalBytes = 0L
    init {
        directory.mkdirs()
        directory.listFiles().orEmpty()
            .filter { it.isFile && (it.name.endsWith(".pcm") || it.name.endsWith(".pcm.gz")) && !it.name.contains(".partial.") }
            .sortedBy { it.lastModified() }
            .forEach { record(it) }
    }

    private fun key(file: File) = file.name.removeSuffix(".pcm.gz").removeSuffix(".pcm")

    private fun record(audio: File) {
        val marker = File(directory, "${key(audio)}.complete")
        val entry = Entry(audio, marker, audio.length() + marker.length())
        entries.put(audio.name, entry)?.let { totalBytes -= it.bytes }
        totalBytes += entry.bytes
    }

    private fun touch(audio: File, marker: File) {
        record(audio)
        val now = System.currentTimeMillis()
        audio.setLastModified(now)
        marker.setLastModified(now)
    }

    suspend fun get(
        text: String,
        rate: Float,
        synthesize: suspend (File, Float) -> Unit,
    ): SpeechAsset = get(text, rate, emptySet(), synthesize)

    suspend fun get(
        text: String,
        rate: Float,
        protectedPaths: Set<String>,
        synthesize: suspend (File, Float) -> Unit,
    ): SpeechAsset {
        val key = PreparationIdentity.speech(text, voiceId, rate)
        val marker = File(directory, "$key.complete")
        if (!compressed) {
            val pcm = File(directory, "$key.pcm")
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
            touch(pcm, marker)
            evictIfNeeded(protectedPaths, pcm.path)
            return SpeechAsset(pcm, pcm.length() * 1000 / (AudioTools.RATE * 2))
        }

        val gz = File(directory, "$key.pcm.gz")
        val valid = readCompressedMarker(marker, gz)
        val pcmBytes = if (valid != null) {
            valid
        } else {
            val wav = File(directory, "$key.wav")
            val pcm = File(directory, "$key.partial.pcm")
            val partialGz = File(directory, "$key.partial.gz")
            try {
                synthesize(wav, rate)
                currentCoroutineContext().ensureActive()
                AudioTools.wavToPcm(wav, pcm)
                currentCoroutineContext().ensureActive()
                val rawLength = pcm.length()
                require(rawLength > 0 && rawLength % 2 == 0L) { "The offline voice generated no audio." }
                pcm.inputStream().buffered().use { input ->
                    GZIPOutputStream(BufferedOutputStream(partialGz.outputStream())).use { output ->
                        input.copyTo(output)
                    }
                }
                currentCoroutineContext().ensureActive()
                check(partialGz.renameTo(gz)) { "Could not save compressed speech clip." }
                marker.writeText("$rawLength:${gz.length()}")
                rawLength
            } finally { wav.delete(); pcm.delete(); partialGz.delete() }
        }
        touch(gz, marker)
        evictIfNeeded(protectedPaths, gz.path)
        return SpeechAsset(gz, pcmBytes * 1000 / (AudioTools.RATE * 2))
    }

    private fun readCompressedMarker(marker: File, gz: File): Long? {
        if (!gz.exists() || !marker.exists() || gz.length() <= 0L) return null
        val parts = marker.readText().trim().split(':')
        if (parts.size != 2) return null
        val pcmBytes = parts[0].toLongOrNull() ?: return null
        val gzBytes = parts[1].toLongOrNull() ?: return null
        return if (pcmBytes > 0 && pcmBytes % 2 == 0L && gz.length() == gzBytes) pcmBytes else null
    }

    fun evictIfNeeded(protectedPaths: Set<String> = emptySet(), currentPath: String? = null) {
        if (totalBytes <= maxBytes) return
        val protectedKeys = protectedPaths.map { key(File(it)) }.toHashSet()
        currentPath?.let { protectedKeys += key(File(it)) }
        val iterator = entries.entries.iterator()
        while (totalBytes > maxBytes && iterator.hasNext()) {
            val entry = iterator.next().value
            if (key(entry.audio) in protectedKeys) continue
            if (!entry.audio.exists() || entry.audio.delete()) {
                entry.marker.delete()
                totalBytes -= entry.bytes
                iterator.remove()
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 256L * 1024 * 1024

        fun reusable(directory: File, voiceId: String, maxBytes: Long = DEFAULT_MAX_BYTES): CueAudioCache =
            CueAudioCache(directory, voiceId, compressed = true, maxBytes = maxBytes)

        /** Open a reusable speech clip (compressed .pcm.gz or raw .pcm) for incremental decoding. */
        fun openPcmStream(file: File): InputStream =
            if (file.name.endsWith(".gz")) BufferedInputStream(GZIPInputStream(BufferedInputStream(file.inputStream(), 32768)), 32768)
            else BufferedInputStream(file.inputStream(), 32768)
    }
}

