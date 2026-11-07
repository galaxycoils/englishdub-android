package app.englishdub.engine

data class TextCue(val startMs: Long, val endMs: Long, val text: String)

object Subtitles {
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

    fun clean(text: String) = text.replace(Regex("<[^>]*>|\\{[^}]*}"), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun timestamp(value: String): Long {
        val parts = value.replace(',', '.').split(':')
        val seconds = parts.last().split('.')
        val hours = if (parts.size == 3) parts[0].toLong() else 0
        val minutes = parts[parts.size - 2].toLong()
        require(minutes < 60 && seconds[0].toInt() < 60) { "Invalid subtitle timestamp." }
        return ((hours * 60 + minutes) * 60 + seconds[0].toLong()) * 1000 + seconds[1].toLong()
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
}
