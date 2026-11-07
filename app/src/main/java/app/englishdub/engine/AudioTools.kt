package app.englishdub.engine

import android.content.Context
import android.media.*
import android.net.Uri
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.roundToInt

/** Stateful linear resampling; memory use is independent of video length. */
class MonoResampler(@PublishedApi internal val inputRate: Int, @PublishedApi internal val outputRate: Int = 16000) {
    @PublishedApi internal var inputIndex = -1L
    @PublishedApi internal var nextOutput = 0.0
    @PublishedApi internal var previous = 0f
    inline fun sample(value: Float, emit: (Float) -> Unit) {
        inputIndex++
        while (nextOutput <= inputIndex) {
            val fraction = (nextOutput - (inputIndex - 1)).coerceIn(0.0, 1.0).toFloat()
            emit(if (inputIndex == 0L) value else previous + (value - previous) * fraction)
            nextOutput += inputRate.toDouble() / outputRate
        }
        previous = value
    }
}

object AudioTools {
    const val RATE = 16000
    val SUPPORTED_SUBTITLE_MIMES = setOf("application/x-subrip", "text/vtt", "text/3gpp")
    private val LANGUAGE_NAMES = mapOf("en" to "English", "hi" to "Hindi", "ur" to "Urdu", "ta" to "Tamil")

    fun normalizeLanguage(raw: String?): Pair<String, String> {
        val clean = raw?.trim()?.lowercase().orEmpty()
        if (clean.isEmpty() || clean in setOf("und", "unknown", "mul", "zxx", "none")) {
            return "und" to "Unknown language"
        }
        val code = when {
            clean == "en" || clean == "eng" || clean.startsWith("en-") || clean.startsWith("en_") -> "en"
            clean == "hi" || clean == "hin" || clean.startsWith("hi-") || clean.startsWith("hi_") -> "hi"
            clean == "ur" || clean == "urd" || clean.startsWith("ur-") || clean.startsWith("ur_") -> "ur"
            clean == "ta" || clean == "tam" || clean.startsWith("ta-") || clean.startsWith("ta_") -> "ta"
            else -> clean.substringBefore('-').substringBefore('_')
        }
        val label = LANGUAGE_NAMES[code] ?: java.util.Locale.forLanguageTag(code).getDisplayLanguage(java.util.Locale.ENGLISH)
            .takeIf { it.isNotBlank() && it.lowercase() != code && it.lowercase() != "und" }
            ?: "Unknown language"
        return (if (label == "Unknown language") "und" else code) to label
    }

    fun recommendSource(
        audioTracks: List<MediaTrackInfo>,
        subtitleTracks: List<MediaTrackInfo>,
        hasExternalSubtitles: Boolean = false,
        externalSubtitleLanguage: String = "en",
        preferredLanguage: String = "hi",
    ): SourceRecommendation {
        val englishAudio = audioTracks.firstOrNull { it.languageCode == "en" }
        if (englishAudio != null) {
            return SourceRecommendation(
                path = RecommendedPath.ENGLISH_AUDIO,
                languageCode = "en",
                audioTrackIndex = englishAudio.index,
                summary = "Recommended: Existing English audio (${englishAudio.label})",
            )
        }
        val preferredAudioTrack = audioTracks.firstOrNull { it.languageCode == preferredLanguage }
            ?: audioTracks.firstOrNull { it.languageCode in listOf("hi", "ur", "ta") }
            ?: audioTracks.firstOrNull()
        val defaultAudioIndex = preferredAudioTrack?.index ?: -1
        if (hasExternalSubtitles && externalSubtitleLanguage == "en") {
            return SourceRecommendation(
                path = RecommendedPath.ENGLISH_SUBTITLES,
                languageCode = "en",
                audioTrackIndex = defaultAudioIndex,
                summary = "Recommended: External English subtitles",
            )
        }
        val englishSub = subtitleTracks.firstOrNull { it.languageCode == "en" }
        if (englishSub != null) {
            return SourceRecommendation(
                path = RecommendedPath.ENGLISH_SUBTITLES,
                languageCode = "en",
                audioTrackIndex = defaultAudioIndex,
                subtitleTrackIndex = englishSub.index,
                summary = "Recommended: Embedded English subtitles (${englishSub.label})",
            )
        }
        if (hasExternalSubtitles && externalSubtitleLanguage in listOf("hi", "ur", "ta")) {
            val langName = LANGUAGE_NAMES[externalSubtitleLanguage] ?: externalSubtitleLanguage
            return SourceRecommendation(
                path = RecommendedPath.TRANSLATED_SUBTITLES,
                languageCode = externalSubtitleLanguage,
                audioTrackIndex = defaultAudioIndex,
                summary = "Recommended: Translate external $langName subtitles",
            )
        }
        val translatedSub = subtitleTracks.firstOrNull { it.languageCode == preferredLanguage }
            ?: subtitleTracks.firstOrNull { it.languageCode in listOf("hi", "ur", "ta") }
        if (translatedSub != null) {
            return SourceRecommendation(
                path = RecommendedPath.TRANSLATED_SUBTITLES,
                languageCode = translatedSub.languageCode,
                audioTrackIndex = defaultAudioIndex,
                subtitleTrackIndex = translatedSub.index,
                summary = "Recommended: Translate ${translatedSub.languageLabel} subtitles (${translatedSub.label})",
            )
        }
        val speechLang = preferredAudioTrack?.languageCode?.takeIf { it in listOf("hi", "ur", "ta") }
            ?: preferredLanguage.takeIf { it in listOf("hi", "ur", "ta") } ?: "hi"
        val langLabel = LANGUAGE_NAMES[speechLang] ?: "Hindi"
        return SourceRecommendation(
            path = RecommendedPath.SPEECH_RECOGNITION,
            languageCode = speechLang,
            audioTrackIndex = defaultAudioIndex,
            summary = "Recommended: Speech recognition ($langLabel${preferredAudioTrack?.let { " · ${it.label}" }.orEmpty()})",
        )
    }

    fun extractor(context: Context, uri: Uri): MediaExtractor = MediaExtractor().apply {
        try { setDataSource(context, uri, null) } catch (e: Throwable) { release(); throw e }
    }

    fun inspectTracks(context: Context, uri: Uri): List<MediaTrackInfo> {
        val extractor = extractor(context, uri)
        try {
            var audioNumber = 0
            var subtitleNumber = 0
            val tracks = mutableListOf<MediaTrackInfo>()
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                val rawLang = format.getString(MediaFormat.KEY_LANGUAGE)
                val (langCode, langLabel) = normalizeLanguage(rawLang)
                val rawTitle = try { format.getString("title") ?: format.getString("name") } catch (_: Exception) { null }
                if (mime.startsWith("audio/")) {
                    audioNumber++
                    val label = rawTitle?.takeIf { it.isNotBlank() } ?: "Audio track $audioNumber ($langLabel)"
                    tracks += MediaTrackInfo(index, "audio", mime, langCode, langLabel, label)
                } else if (mime in SUPPORTED_SUBTITLE_MIMES) {
                    subtitleNumber++
                    val label = rawTitle?.takeIf { it.isNotBlank() } ?: "Subtitle track $subtitleNumber ($langLabel)"
                    tracks += MediaTrackInfo(index, "subtitles", mime, langCode, langLabel, label)
                }
            }
            return tracks
        } finally { extractor.release() }
    }

    fun duration(context: Context, uri: Uri): Long {
        val metadata = MediaMetadataRetriever()
        try { metadata.setDataSource(context, uri); return metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0 }
        finally { metadata.release() }
    }

    fun hasEnglishAudio(context: Context, uri: Uri): Boolean =
        inspectTracks(context, uri).any { it.kind == "audio" && it.languageCode == "en" }

    fun embeddedSubtitles(context: Context, uri: Uri, language: String, subtitleTrackIndex: Int = -1): List<TextCue> {
        val extractor = extractor(context, uri)
        try {
            val track = if (subtitleTrackIndex >= 0) {
                require(subtitleTrackIndex < extractor.trackCount) { "Selected subtitle track ($subtitleTrackIndex) does not exist." }
                val mime = extractor.getTrackFormat(subtitleTrackIndex).getString(MediaFormat.KEY_MIME)
                require(mime in SUPPORTED_SUBTITLE_MIMES) { "Selected track ($subtitleTrackIndex) is not a supported text subtitle track." }
                subtitleTrackIndex
            } else {
                (0 until extractor.trackCount).firstOrNull {
                    val format = extractor.getTrackFormat(it)
                    val (code, _) = normalizeLanguage(format.getString(MediaFormat.KEY_LANGUAGE))
                    format.getString(MediaFormat.KEY_MIME) in SUPPORTED_SUBTITLE_MIMES && code == language
                } ?: error("No supported embedded text subtitles were found for this language. Add an SRT or WebVTT file, or use speech recognition.")
            }
            val mime = extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME)
            extractor.selectTrack(track)
            val buffer = ByteBuffer.allocate(256 * 1024)
            val samples = mutableListOf<Pair<Long, String>>()
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                require(size <= buffer.capacity()) { "Embedded subtitles are too large. Use an external SRT file." }
                val data = ByteArray(size); buffer.position(0); buffer.get(data)
                val text = if (mime == "text/3gpp" && size > 2) {
                    val count = ((data[0].toInt() and 255) shl 8) or (data[1].toInt() and 255)
                    String(data, 2, minOf(count, size - 2), Charsets.UTF_8)
                } else String(data, Charsets.UTF_8)
                val clean = Subtitles.clean(text)
                samples += extractor.sampleTime / 1000 to clean
                require(samples.size <= 100_000) { "Too many subtitle entries." }
                extractor.advance()
            }
            return samples.mapIndexedNotNull { i, (start, text) ->
                if (text.isBlank()) null else TextCue(start, samples.getOrNull(i + 1)?.first ?: start + 3000, text)
            }.filter { it.endMs > it.startMs }.also { require(it.isNotEmpty()) { "No readable subtitle dialogue found. Add an external SRT or WebVTT file." } }
        } finally { extractor.release() }
    }

    suspend fun decodeChunks(
        context: Context,
        uri: Uri,
        fromMs: Long,
        untilMs: Long = Long.MAX_VALUE,
        audioTrackIndex: Int = -1,
        emit: suspend (Long, ByteBuffer, Int, Double) -> Unit,
    ) {
        val extractor = extractor(context, uri)
        var codec: MediaCodec? = null
        try {
            val track = if (audioTrackIndex >= 0) {
                require(audioTrackIndex < extractor.trackCount) { "Selected audio track ($audioTrackIndex) is unavailable." }
                val mime = extractor.getTrackFormat(audioTrackIndex).getString(MediaFormat.KEY_MIME)
                require(mime?.startsWith("audio/") == true) { "Selected track ($audioTrackIndex) is not an audio track." }
                audioTrackIndex
            } else {
                (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                    ?: error("This video has no readable audio track.")
            }
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            if (fromMs > 0) extractor.seekTo(fromMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0); codec.start()
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var resampler = MonoResampler(sampleRate)
            val chunkSize = RATE * 30
            val chunk = FloatArray(chunkSize + 32)
            val direct = ByteBuffer.allocateDirect(chunkSize * 4).order(ByteOrder.nativeOrder())
            suspend fun emitBuffer(start: Long, count: Int) {
                direct.clear(); direct.asFloatBuffer().put(chunk, 0, count)
                var energy = 0.0
                for (i in 0 until count) energy += chunk[i] * chunk[i]
                emit(start, direct, count, kotlin.math.sqrt(energy / count))
            }
            var chunkCount = 0
            var chunkStart = fromMs
            var eosInput = false
            var eosOutput = false
            val info = MediaCodec.BufferInfo()
            suspend fun flushChunk() {
                emitBuffer(chunkStart, chunkSize)
                val remaining = chunkCount - chunkSize
                if (remaining > 0) chunk.copyInto(chunk, 0, chunkSize, chunkCount)
                chunkStart += 30_000; chunkCount = remaining
            }
            while (!eosOutput) {
                currentCoroutineContext().ensureActive()
                if (!eosInput) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val input = codec.getInputBuffer(index)!!
                        val sampleTimeMs = extractor.sampleTime / 1000
                        val n = if (sampleTimeMs >= untilMs) -1 else extractor.readSampleData(input, 0)
                        if (n < 0) { codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eosInput = true }
                        else { codec.queueInputBuffer(index, 0, n, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val output = codec.outputFormat
                    sampleRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    encoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) output.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    require(encoding in listOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT)) { "This audio decoder returned an unsupported PCM format." }
                    resampler = MonoResampler(sampleRate)
                } else if (index >= 0) {
                    val buffer = codec.getOutputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                    buffer.position(info.offset); buffer.limit(info.offset + info.size)
                    val bytes = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                    val frames = info.size / (bytes * channels)
                    // Preserve track offsets and gaps so the recognized cues retain video time.
                    val expectedFrames = (maxOf(fromMs * 1000, info.presentationTimeUs) - fromMs * 1000) * RATE / 1_000_000
                    val producedFrames = (chunkStart - fromMs) * RATE / 1000 + chunkCount
                    val gap = expectedFrames - producedFrames
                    if (info.size > 0 && gap > RATE / 100) {
                        repeat(gap.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) {
                            chunk[chunkCount++] = 0f
                            if (chunkCount >= chunkSize) flushChunk()
                        }
                        resampler = MonoResampler(sampleRate)
                    }
                    for (frame in 0 until frames) {
                        var mono = 0f
                        repeat(channels) { mono += if (bytes == 4) buffer.float else buffer.short / 32768f }
                        val timeMs = info.presentationTimeUs / 1000 + frame * 1000L / sampleRate
                        if (timeMs < fromMs) continue
                        if (timeMs >= untilMs) { eosOutput = true; break }
                        resampler.sample(mono / channels) { sample -> chunk[chunkCount++] = sample }
                        if (chunkCount >= chunkSize) flushChunk()
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eosOutput = true
                    codec.releaseOutputBuffer(index, false)
                }
            }
            if (chunkCount > 0) emitBuffer(chunkStart, chunkCount)
        } finally { try { codec?.stop() } finally { codec?.release(); extractor.release() } }
    }

    /** Convert a TTS WAV to fixed 16 kHz mono PCM on disk. */
    fun wavToPcm(wav: File, pcm: File): Long {
        RandomAccessFile(wav, "r").use { input ->
            require(input.readTag() == "RIFF") { "The offline voice did not produce WAV audio." }
            input.skipBytes(4); require(input.readTag() == "WAVE")
            var sampleRate = 0; var channels = 0; var bits = 0; var type = 0
            while (input.filePointer + 8 <= input.length()) {
                val tag = input.readTag(); val size = input.readLeInt().toLong() and 0xffffffffL
                val end = input.filePointer + size
                require(end <= input.length()) { "Voice audio is incomplete. Retry this line." }
                if (tag == "fmt ") {
                    type = input.readLeShort(); channels = input.readLeShort(); sampleRate = input.readLeInt()
                    input.skipBytes(6); bits = input.readLeShort()
                } else if (tag == "data") {
                    require(sampleRate > 0 && channels > 0 && type == 1 && bits == 16) { "The offline voice must support 16-bit PCM WAV output." }
                    val resampler = MonoResampler(sampleRate)
                    var frames = 0L
                    val dataStart = input.filePointer
                    DataInputStream(BufferedInputStream(wav.inputStream().apply { channel.position(dataStart) }, 64 * 1024)).use { audio ->
                      DataOutputStream(BufferedOutputStream(pcm.outputStream())).use { output ->
                        repeat((size / (2 * channels)).toInt()) {
                            var value = 0f; repeat(channels) { value += java.lang.Short.reverseBytes(audio.readShort()) / 32768f }
                            resampler.sample(value / channels) { sample ->
                                val value16 = (sample.coerceIn(-1f, 1f) * 32767).roundToInt()
                                output.writeByte(value16 and 255); output.writeByte((value16 shr 8) and 255); frames++
                            }
                        }
                      }
                    }
                    return frames * 1000 / RATE
                }
                input.seek(end + (size and 1))
            }
        }
        error("No audio was generated by the offline voice.")
    }
    private fun RandomAccessFile.readTag(): String = ByteArray(4).also { readFully(it) }.toString(Charsets.US_ASCII)
    private fun RandomAccessFile.readLeInt(): Int = Integer.reverseBytes(readInt())
    private fun RandomAccessFile.readLeShort(): Int = java.lang.Short.reverseBytes(readShort()).toInt() and 65535
}
