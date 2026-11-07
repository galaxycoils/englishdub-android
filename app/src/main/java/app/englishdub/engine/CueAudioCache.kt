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
    init { directory.mkdirs() }

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
            touch(key, pcm, marker)
            evictIfNeeded(protectedPaths + pcm.path)
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
        touch(key, gz, marker)
        evictIfNeeded(protectedPaths + gz.path)
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

    fun evictIfNeeded(protectedPaths: Set<String> = emptySet()) {
        val protectedNames = protectedPaths.flatMap { path ->
            val f = File(path)
            val base = f.name.removeSuffix(".pcm.gz").removeSuffix(".pcm")
            listOf(f.name, "$base.complete", "$base.pcm", "$base.pcm.gz")
        }.toSet()
        val files = directory.listFiles()?.filter {
            it.isFile && (it.name.endsWith(".pcm") || it.name.endsWith(".pcm.gz"))
        }.orEmpty()
        var totalBytes = directory.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        if (totalBytes <= maxBytes) return
        val candidates = files.filter { it.name !in protectedNames && it.path !in protectedPaths }
            .sortedWith(compareBy({ accessOrder(it.name.removeSuffix(".pcm.gz").removeSuffix(".pcm")) }, { it.lastModified() }))
        for (file in candidates) {
            if (totalBytes <= maxBytes) break
            val base = file.name.removeSuffix(".pcm.gz").removeSuffix(".pcm")
            val marker = File(directory, "$base.complete")
            val freed = file.length() + (if (marker.exists()) marker.length() else 0L)
            if (file.delete()) {
                marker.delete()
                removeAccess(base)
                totalBytes -= freed
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 256L * 1024 * 1024
        private var sequence = 0L
        private val accessLog = HashMap<String, Long>()

        @Synchronized private fun touch(key: String, audio: File, marker: File) {
            accessLog[key] = ++sequence
            val now = System.currentTimeMillis()
            audio.setLastModified(now)
            marker.setLastModified(now)
        }

        @Synchronized private fun accessOrder(key: String): Long = accessLog[key] ?: 0L
        @Synchronized private fun removeAccess(key: String) { accessLog.remove(key) }

        fun reusable(directory: File, voiceId: String, maxBytes: Long = DEFAULT_MAX_BYTES): CueAudioCache =
            CueAudioCache(directory, voiceId, compressed = true, maxBytes = maxBytes)

        /** Open a reusable speech clip (compressed .pcm.gz or raw .pcm) for incremental decoding. */
        fun openPcmStream(file: File): InputStream =
            if (file.name.endsWith(".gz")) BufferedInputStream(GZIPInputStream(file.inputStream()))
            else BufferedInputStream(file.inputStream())
    }
}

