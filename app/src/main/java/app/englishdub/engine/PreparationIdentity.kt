package app.englishdub.engine

import android.content.Context
import android.net.Uri
import app.englishdub.data.PreservedRange
import app.englishdub.data.PronunciationEntry
import app.englishdub.data.SavedCue
import java.io.File
import java.security.MessageDigest

data class MediaTrackInfo(
    val index: Int,
    val kind: String,
    val mime: String,
    val languageCode: String,
    val languageLabel: String,
    val label: String,
)

enum class RecommendedPath {
    ENGLISH_AUDIO,
    ENGLISH_SUBTITLES,
    TRANSLATED_SUBTITLES,
    SPEECH_RECOGNITION,
}

data class SourceRecommendation(
    val path: RecommendedPath,
    val languageCode: String,
    val audioTrackIndex: Int = -1,
    val subtitleTrackIndex: Int = -1,
    val summary: String,
)

data class PreviewRange(val startMs: Long, val durationMs: Long = 60_000L) {
    init { require(startMs >= 0 && durationMs > 0) { "Invalid preview range." } }
    fun endMs(movieDurationMs: Long): Long =
        if (movieDurationMs > 0) minOf(movieDurationMs, startMs + durationMs) else startMs + durationMs
}

data class PreparationRequest(
    val jobId: String,
    val mode: String,
    val sourceLanguage: String,
    val audioTrackIndex: Int = -1,
    val subtitleTrackIndex: Int = -1,
    val subtitleOffsetMs: Long = 0L,
    val voiceName: String? = null,
    val previewRange: PreviewRange? = null,
    val overrideRecommendation: Boolean = false,
    val chargingOnly: Boolean = true,
) {
    val isPreview: Boolean get() = previewRange != null
}

object Pronunciation {
    /** Apply film-specific replacements to spoken text only; captions retain their normal spelling. */
    fun apply(text: String, entries: List<PronunciationEntry>): String {
        var result = text
        for (entry in entries.sortedByDescending { it.original.trim().length }) {
            val from = entry.original.trim()
            val to = entry.spoken.trim()
            if (from.isEmpty() || to.isEmpty() || from == to) continue
            val wordBoundary = from.first().isLetterOrDigit() && from.last().isLetterOrDigit()
            val regex = if (wordBoundary) Regex("\\b${Regex.escape(from)}\\b", RegexOption.IGNORE_CASE)
            else Regex(Regex.escape(from), RegexOption.IGNORE_CASE)
            result = result.replace(regex, Regex.escapeReplacement(to))
        }
        return result
    }
}

object SongRangePolicy {
    fun isPreserved(timeMs: Long, ranges: List<PreservedRange>): Boolean =
        ranges.any { it.endMs > it.startMs && timeMs in it.startMs until it.endMs }

    fun overlapsPreserved(startMs: Long, endMs: Long, ranges: List<PreservedRange>): Boolean =
        ranges.any { it.endMs > it.startMs && startMs < it.endMs && endMs > it.startMs }
}

object PreparationIdentity {
    const val PIPELINE = "englishdub-2"
    fun digest(vararg fields: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
        fields.forEach { field ->
            val bytes = field.toByteArray(Charsets.UTF_8)
            hash.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()); hash.update(bytes)
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    /** Stage 1: Speech recognition cache identity. Independent of translation, voice, offsets, captions, and playback prefs. */
    fun recognition(source: String, audioTrackIndex: Int, sourceLanguage: String, modelSha: String, vadSha: String): String =
        digest("recognition", source, audioTrackIndex.toString(), sourceLanguage, modelSha, vadSha, PIPELINE)

    /** Stage 2: Translation cache identity. Independent of voice, offsets, pronunciations, song ranges, captions, and playback prefs. */
    fun translation(sourceOrSubtitle: String, subtitleTrackIndex: Int, sourceLanguage: String, mode: String, engineId: String): String =
        digest("translation", sourceOrSubtitle, subtitleTrackIndex.toString(), sourceLanguage, mode, engineId, PIPELINE)

    /** Stage 3: Speech clip cache identity. Keyed by spoken text, voice identity, rate, and pipeline version. */
    fun speech(spokenText: String, voiceId: String, rate: Float): String =
        digest(spokenText, voiceId, rate.toString(), PIPELINE)

    /** Stage 4: Final timeline cache identity. Changing caption visibility/size or playback volume/position never invalidates this. */
    fun timeline(
        cues: List<SavedCue>,
        subtitleOffsetMs: Long,
        pronunciations: List<PronunciationEntry>,
        preservedRanges: List<PreservedRange>,
        voiceId: String,
        durationMs: Long,
    ): String {
        val cueState = cues.joinToString("|") { cue ->
            val start = cue.effectiveStartMs(subtitleOffsetMs)
            val end = cue.effectiveEndMs(subtitleOffsetMs)
            val silence = cue.subtitlesOnly || SongRangePolicy.overlapsPreserved(start, end, preservedRanges)
            val spoken = if (silence) "" else Pronunciation.apply(cue.effectiveEnglish(), pronunciations)
            "${cue.index}:$start:$end:$silence:$spoken"
        }
        return digest("timeline", cueState, voiceId, durationMs.toString(), PIPELINE)
    }

    /** Sample seekable source data; uncertain identities are deliberately not reusable. */
    fun source(context: Context, uri: Uri): String? { return try {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val size = descriptor.statSize
            if (size < 0) return null
            input.channel.let { channel ->
                val buffer = java.nio.ByteBuffer.allocate(65536)
                val hash = MessageDigest.getInstance("SHA-256")
                for (position in listOf(0L, (size - 65536).coerceAtLeast(0)).distinct()) {
                    channel.position(position); buffer.clear()
                    val count = channel.read(buffer)
                    if (count > 0) hash.update(buffer.array(), 0, count)
                }
                val sample = hash.digest().joinToString("") { "%02x".format(it) }
                val modified = if (uri.scheme == "file") File(uri.path!!).lastModified() else {
                    try { context.contentResolver.query(uri, arrayOf("last_modified"), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else 0 } ?: 0 }
                    catch (_: Exception) { 0 }
                }
                digest(uri.toString(), size.toString(), modified.toString(), sample)
            }
        }
    } catch (_: Exception) { null } }
}

