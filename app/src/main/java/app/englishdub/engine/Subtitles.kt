package app.englishdub.engine

import app.englishdub.data.SavedCue
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class TextCue(val startMs: Long, val endMs: Long, val text: String)

object Subtitles {
    private val markup = Regex("<[^>]*>|\\{[^}]*\\}")
    private val whitespace = Regex("\\s+")
    private val timing = Regex("(?m)^\\s*((?:\\d+:)?\\d{2}:\\d{2}[.,]\\d{3})\\s*-->\\s*((?:\\d+:)?\\d{2}:\\d{2}[.,]\\d{3})[^\\n]*\\n")
    fun parse(input: String): List<TextCue> {
        val normalized = input.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val blocks = normalized.split(Regex("\\n[ \\t]*\\n"))
        val result = blocks.flatMap { block ->
            if (block.trimStart().startsWith("NOTE") || block.trimStart().startsWith("STYLE") || block.trimStart().startsWith("REGION")) emptyList()
            else timing.findAll(block).mapNotNull { match ->
                val start = timestamp(match.groupValues[1])
                val end = timestamp(match.groupValues[2])
                val text = clean(block.substring(match.range.last + 1))
                if (end > start && text.isNotBlank()) TextCue(start, end, text) else null
            }.toList()
        }.sortedBy { it.startMs }
        require(result.isNotEmpty()) { "No readable SRT or WebVTT dialogue found. Choose a text subtitle file." }
        require(result.size <= 20_000) { "This subtitle file has too many entries. Split it into files with fewer than 20,000 cues." }
        return result
    }

    fun clean(text: String) = text.replace(markup, "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace(whitespace, " ").trim()

    /** Preview shifted subtitle timestamps while preserving original cue timestamps. */
    fun applyOffset(cues: List<TextCue>, offsetMs: Long): List<TextCue> = cues.map { cue ->
        val start = (cue.startMs + offsetMs).coerceAtLeast(0L)
        val end = maxOf(start + 1L, cue.endMs + offsetMs)
        cue.copy(startMs = start, endMs = end)
    }

    /** Binary search for a cue covering timeMs (effectiveStartMs <= timeMs < effectiveEndMs). O(log N). */
    fun findCueAt(cues: List<SavedCue>, timeMs: Long, subtitleOffsetMs: Long = 0L): SavedCue? {
        if (cues.isEmpty()) return null
        var low = 0
        var high = cues.lastIndex
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (cues[mid].effectiveStartMs(subtitleOffsetMs) <= timeMs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        val candidate = cues.getOrNull(found) ?: return null
        return if (timeMs < candidate.effectiveEndMs(subtitleOffsetMs)) candidate else null
    }

    /** Binary search for spoken audio cue covering timeMs (spokenStartMs <= timeMs < spokenEndMs). O(log N). */
    fun findSpokenCueAt(cues: List<SavedCue>, timeMs: Long): SavedCue? {
        if (cues.isEmpty()) return null
        var low = 0
        var high = cues.lastIndex
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (cues[mid].spokenStartMs <= timeMs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        val candidate = cues.getOrNull(found) ?: return null
        return if (!candidate.subtitlesOnly && candidate.spokenEndMs > candidate.spokenStartMs && timeMs < candidate.spokenEndMs) candidate else null
    }

    fun searchCues(cues: List<SavedCue>, query: String, mode: String, offsetMs: Long = 0L): List<SavedCue> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return cues
        val lower = trimmed.lowercase()
        val matchWarning = lower in setOf("warning", "overflow", "exceeds", "late", "⚠")
        val matchSubOnly = lower == "subtitles only" || lower == "subtitles-only"
        return cues.filter { cue ->
            (matchWarning && cue.hasTimingWarning(offsetMs)) ||
                (matchSubOnly && cue.subtitlesOnly) ||
                cue.effectiveEnglish().contains(trimmed, ignoreCase = true) ||
                cue.originalTranscript(mode)?.contains(trimmed, ignoreCase = true) == true ||
                formatClock(cue.effectiveStartMs(offsetMs)).contains(trimmed, ignoreCase = true) ||
                "#${cue.index + 1}".equals(trimmed, ignoreCase = true)
        }
    }

    /** Export corrected English captions and effective subtitle timestamps as UTF-8 SRT. */
    fun formatSrt(cues: List<SavedCue>, subtitleOffsetMs: Long = 0L): String {
        val valid = cues.filter { it.effectiveEnglish().isNotBlank() }.sortedBy { it.effectiveStartMs(subtitleOffsetMs) }
        require(valid.isNotEmpty()) { "No English dialogue is available to export." }
        return buildString {
            valid.forEachIndexed { i, cue ->
                val start = cue.effectiveStartMs(subtitleOffsetMs)
                val end = cue.effectiveEndMs(subtitleOffsetMs)
                append(i + 1).append('\n')
                append(formatSrtTimestamp(start)).append(" --> ").append(formatSrtTimestamp(end)).append('\n')
                append(cue.effectiveEnglish()).append("\n\n")
            }
        }
    }

    suspend fun exportSrt(cues: List<SavedCue>, subtitleOffsetMs: Long, output: OutputStream) {
        val bytes = formatSrt(cues, subtitleOffsetMs).toByteArray(Charsets.UTF_8)
        output.use { stream ->
            var offset = 0
            while (offset < bytes.size) {
                currentCoroutineContext().ensureActive()
                val chunk = minOf(8192, bytes.size - offset)
                stream.write(bytes, offset, chunk)
                offset += chunk
            }
            stream.flush()
        }
    }

    /** Export the completed English speech M4A voice track without modifying the prepared dub. */
    suspend fun exportVoiceM4a(dubAudioFile: File, output: OutputStream) {
        require(dubAudioFile.exists() && dubAudioFile.length() > 0L) { "Prepared English voice track is not available yet." }
        dubAudioFile.inputStream().use { input ->
            copyCancellable(input, output)
        }
    }

    suspend fun copyCancellable(input: InputStream, output: OutputStream) {
        output.use { out ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
            }
            out.flush()
        }
    }

    fun formatSrtTimestamp(ms: Long): String {
        val clamped = ms.coerceAtLeast(0L)
        val hours = clamped / 3_600_000
        val minutes = (clamped % 3_600_000) / 60_000
        val seconds = (clamped % 60_000) / 1000
        val millis = clamped % 1000
        return "%02d:%02d:%02d,%03d".format(java.util.Locale.US, hours, minutes, seconds, millis)
    }

    fun formatClock(ms: Long): String {
        val clamped = ms.coerceAtLeast(0L)
        val totalSeconds = clamped / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(java.util.Locale.US, hours, minutes, seconds)
        else "%02d:%02d".format(java.util.Locale.US, minutes, seconds)
    }

    fun parseTimestampOrSeconds(input: String): Long {
        val trimmed = input.trim()
        require(trimmed.isNotEmpty()) { "Enter a valid timestamp." }
        if (':' in trimmed) return timestamp(if ('.' in trimmed || ',' in trimmed) trimmed else "$trimmed.000")
        val ms = trimmed.toLongOrNull() ?: error("Invalid timestamp.")
        require(ms >= 0) { "Timestamp must not be negative." }
        return ms
    }

    private fun timestamp(value: String): Long {
        val parts = value.replace(',', '.').split(':')
        val seconds = parts.last().split('.')
        val hours = if (parts.size == 3) parts[0].toLong() else 0
        val minutes = parts[parts.size - 2].toLong()
        require(minutes < 60 && seconds[0].toInt() < 60) { "Invalid subtitle timestamp." }
        val millis = seconds.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLong() ?: 0L
        return ((hours * 60 + minutes) * 60 + seconds[0].toLong()) * 1000 + millis
    }
}

object DubTiming {
    data class Placement(val startMs: Long, val endMs: Long, val late: Boolean)
    fun place(cue: TextCue, speechDurationMs: Long, previousEndMs: Long): Placement {
        val start = maxOf(cue.startMs, previousEndMs)
        val end = start + speechDurationMs
        return Placement(start, end, start - cue.startMs > 150 || end > cue.endMs + 150)
    }
    fun needsResync(videoMs: Long, dubMs: Long) = kotlin.math.abs(videoMs - dubMs) > 100

    /** Find the start of the active or most recent dialogue line for "Replay last dialogue". */
    fun replayTargetMs(cues: List<SavedCue>, currentPositionMs: Long, subtitleOffsetMs: Long = 0L): Long {
        if (cues.isEmpty()) return (currentPositionMs - 5_000L).coerceAtLeast(0L)
        val starts = cues.map {
            if (it.spokenEndMs > it.spokenStartMs) it.spokenStartMs else it.effectiveStartMs(subtitleOffsetMs)
        }.sorted()
        val eligible = starts.filter { it <= currentPositionMs - 600L }
        return eligible.lastOrNull() ?: starts.firstOrNull { it <= currentPositionMs } ?: 0L
    }
}

