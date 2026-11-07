package app.englishdub.engine

import app.englishdub.data.PreservedRange
import app.englishdub.data.PronunciationEntry
import app.englishdub.data.SavedCue
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PreparationStatus(val busy: Boolean = false, val id: String? = null, val message: String = "", val progress: Int = 0)

/** One admission owner, including the interval before Android delivers onStartCommand. */
class TaskAdmission {
    private var sequence = 0L
    private var owner: Long? = null
    @Synchronized fun reserve(): Long { check(owner == null) { "Pause the current task first." }; return (++sequence).also { owner = it } }
    @Synchronized fun owns(ticket: Long) = owner == ticket
    @Synchronized fun release(ticket: Long): Boolean { if (owner != ticket) return false; owner = null; return true }
}

object PreparationCoordinator {
    private val admission = TaskAdmission()
    private val mutable = MutableStateFlow(PreparationStatus())
    val status = mutable.asStateFlow()
    @Synchronized fun reserve(id: String): Long = admission.reserve().also { mutable.value = PreparationStatus(true, id, "Starting…") }
    fun owns(ticket: Long) = admission.owns(ticket)
    @Synchronized fun report(ticket: Long, status: PreparationStatus) { if (admission.owns(ticket)) mutable.value = status }
    @Synchronized fun finish(ticket: Long, message: String) { if (admission.release(ticket)) mutable.value = PreparationStatus(message = message) }
}

data class CuePassResult(
    val cues: List<SavedCue>,
    val warnings: Int,
    val regeneratedLegacyClips: Boolean,
    val protectedPaths: Set<String>,
)

/** Persist 60s preview cues separately from full-movie DB cues so preview playback shows captions and ducks audio. */
object PreviewCueStore {
    private const val FILE_NAME = "preview.cues"
    fun save(jobDirectory: File, cues: List<SavedCue>) {
        jobDirectory.mkdirs()
        val file = File(jobDirectory, FILE_NAME)
        val tmp = File(jobDirectory, "$FILE_NAME.tmp")
        java.io.DataOutputStream(java.io.BufferedOutputStream(tmp.outputStream())).use { out ->
            out.writeInt(1)
            out.writeInt(cues.size)
            for (c in cues) {
                out.writeUTF(c.jobId)
                out.writeInt(c.index)
                out.writeLong(c.startMs)
                out.writeLong(c.endMs)
                out.writeUTF(c.text)
                out.writeBoolean(c.english != null)
                if (c.english != null) out.writeUTF(c.english)
                out.writeBoolean(c.pcmPath != null)
                if (c.pcmPath != null) out.writeUTF(c.pcmPath)
                out.writeInt(c.rate)
                out.writeLong(c.spokenStartMs)
                out.writeLong(c.spokenEndMs)
                out.writeBoolean(c.editedEnglish != null)
                if (c.editedEnglish != null) out.writeUTF(c.editedEnglish)
                out.writeBoolean(c.startOverrideMs != null)
                if (c.startOverrideMs != null) out.writeLong(c.startOverrideMs)
                out.writeBoolean(c.endOverrideMs != null)
                if (c.endOverrideMs != null) out.writeLong(c.endOverrideMs)
                out.writeBoolean(c.subtitlesOnly)
                out.writeBoolean(c.transcriptAvailable)
            }
        }
        tmp.renameTo(file)
    }

    fun load(jobDirectory: File): List<SavedCue> {
        val file = File(jobDirectory, FILE_NAME)
        if (!file.exists() || file.length() == 0L) return emptyList()
        return try {
            java.io.DataInputStream(java.io.BufferedInputStream(file.inputStream())).use { input ->
                val version = input.readInt()
                if (version != 1) return emptyList()
                val count = input.readInt().coerceIn(0, 20_000)
                List(count) {
                    val jobId = input.readUTF()
                    val index = input.readInt()
                    val startMs = input.readLong()
                    val endMs = input.readLong()
                    val text = input.readUTF()
                    val english = if (input.readBoolean()) input.readUTF() else null
                    val pcmPath = if (input.readBoolean()) input.readUTF() else null
                    val rate = input.readInt()
                    val spokenStartMs = input.readLong()
                    val spokenEndMs = input.readLong()
                    val editedEnglish = if (input.readBoolean()) input.readUTF() else null
                    val startOverrideMs = if (input.readBoolean()) input.readLong() else null
                    val endOverrideMs = if (input.readBoolean()) input.readLong() else null
                    val subtitlesOnly = input.readBoolean()
                    val transcriptAvailable = input.readBoolean()
                    SavedCue(
                        jobId = jobId,
                        index = index,
                        startMs = startMs,
                        endMs = endMs,
                        text = text,
                        english = english,
                        pcmPath = pcmPath,
                        rate = rate,
                        spokenStartMs = spokenStartMs,
                        spokenEndMs = spokenEndMs,
                        editedEnglish = editedEnglish,
                        startOverrideMs = startOverrideMs,
                        endOverrideMs = endOverrideMs,
                        subtitlesOnly = subtitlesOnly,
                        transcriptAvailable = transcriptAvailable,
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/** Disk-backed reusable translation cache so 60s preview translations are reused during full movie preparation. */
object TranslationStore {
    private const val FILE_NAME = "translations.cache"
    @Synchronized fun load(directory: File, sourceLanguage: String): MutableMap<String, String> {
        val map = BoundedCache<String, String>(2048)
        val file = File(directory, FILE_NAME)
        if (!file.exists()) return map
        try {
            java.io.DataInputStream(java.io.BufferedInputStream(file.inputStream())).use { input ->
                val count = input.readInt().coerceIn(0, 10_000)
                repeat(count) {
                    val lang = input.readUTF()
                    val src = input.readUTF()
                    val dst = input.readUTF()
                    if (lang == sourceLanguage) map[src] = dst
                }
            }
        } catch (_: Exception) {}
        return map
    }

    @Synchronized fun save(directory: File, sourceLanguage: String, entries: Map<String, String>) {
        if (entries.isEmpty()) return
        directory.mkdirs()
        val file = File(directory, FILE_NAME)
        val merged = LinkedHashMap<Pair<String, String>, String>()
        if (file.exists()) {
            try {
                java.io.DataInputStream(java.io.BufferedInputStream(file.inputStream())).use { input ->
                    val count = input.readInt().coerceIn(0, 10_000)
                    repeat(count) {
                        val lang = input.readUTF()
                        val src = input.readUTF()
                        val dst = input.readUTF()
                        merged[lang to src] = dst
                    }
                }
            } catch (_: Exception) {}
        }
        for ((src, dst) in entries) merged[sourceLanguage to src] = dst
        val capped = merged.entries.toList().takeLast(4096)
        val tmp = File(directory, "$FILE_NAME.tmp")
        try {
            java.io.DataOutputStream(java.io.BufferedOutputStream(tmp.outputStream())).use { out ->
                out.writeInt(capped.size)
                for ((k, v) in capped) {
                    out.writeUTF(k.first)
                    out.writeUTF(k.second)
                    out.writeUTF(v)
                }
            }
            tmp.renameTo(file)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}

object DialoguePipeline {
    suspend fun synthesizeAndPlaceCues(
        cues: List<SavedCue>,
        subtitleOffsetMs: Long,
        pronunciations: List<PronunciationEntry>,
        preservedRanges: List<PreservedRange>,
        cache: CueAudioCache,
        translator: (suspend (String) -> String)?,
        synthesize: suspend (String, File, Float) -> Unit,
        existingDubExists: Boolean = false,
        initialPreviousEndMs: Long = 0L,
        sourceLanguage: String = "en",
        checkEnvironment: () -> Unit = {},
        onCueUpdated: suspend (Int, SavedCue) -> Unit = { _, _ -> },
    ): CuePassResult {
        val translations = TranslationStore.load(cache.directory, sourceLanguage)
        var newTranslationsAdded = false
        val protectedPaths = cues.mapNotNull { it.pcmPath }.toMutableSet()
        val regeneratedLegacyClips = existingDubExists && cues.any { cue ->
            val start = cue.effectiveStartMs(subtitleOffsetMs)
            val end = cue.effectiveEndMs(subtitleOffsetMs)
            !cue.subtitlesOnly && !SongRangePolicy.overlapsPreserved(start, end, preservedRanges) &&
                cue.spokenEndMs > cue.spokenStartMs && (cue.pcmPath == null || !File(cue.pcmPath).exists())
        }
        val updated = ArrayList<SavedCue>(cues.size)
        var previousEnd = initialPreviousEndMs
        var warnings = 0
        try {
            for ((index, saved) in cues.withIndex()) {
                currentCoroutineContext().ensureActive()
                checkEnvironment()
                var cue = saved
                val effectiveStart = cue.effectiveStartMs(subtitleOffsetMs)
                val effectiveEnd = cue.effectiveEndMs(subtitleOffsetMs)
                if (cue.english == null) {
                    val translated = translations[cue.text]
                        ?: (translator?.invoke(cue.text) ?: cue.text).also {
                            translations[cue.text] = it
                            newTranslationsAdded = true
                        }
                    cue = cue.copy(english = translated)
                }
                val silent = cue.subtitlesOnly || SongRangePolicy.overlapsPreserved(effectiveStart, effectiveEnd, preservedRanges)
                if (silent) {
                    val next = cue.copy(pcmPath = null, rate = 0, spokenStartMs = effectiveStart, spokenEndMs = effectiveStart)
                    if (next != saved) onCueUpdated(index, next)
                    updated += next
                    continue
                }
                val captionText = cue.effectiveEnglish()
                val spokenText = Pronunciation.apply(captionText, pronunciations)
                var asset = cache.get(spokenText, 1f, protectedPaths) { file, speed -> synthesize(spokenText, file, speed) }
                protectedPaths += asset.file.path
                val available = maxOf(1L, effectiveEnd - maxOf(effectiveStart, previousEnd))
                if (asset.durationMs > available * 1.1) {
                    val rate = ((asset.durationMs.toFloat() / available).coerceIn(1f, 1.25f) * 100).toInt() / 100f
                    asset = cache.get(spokenText, rate, protectedPaths) { file, speed -> synthesize(spokenText, file, speed) }
                    protectedPaths += asset.file.path
                }
                val timing = DubTiming.place(TextCue(effectiveStart, effectiveEnd, captionText), asset.durationMs, previousEnd)
                cue = cue.copy(
                    pcmPath = asset.file.path,
                    rate = AudioTools.RATE,
                    spokenStartMs = timing.startMs,
                    spokenEndMs = timing.endMs,
                )
                if (cue != saved) onCueUpdated(index, cue)
                if (timing.late) warnings++
                previousEnd = timing.endMs
                updated += cue
            }
        } finally {
            if (newTranslationsAdded) TranslationStore.save(cache.directory, sourceLanguage, translations)
        }
        return CuePassResult(updated, warnings, regeneratedLegacyClips, protectedPaths)
    }

    /** Encode to a temporary file and commit atomically; failed regeneration preserves the previous playable dub. */
    suspend fun commitTimeline(
        cues: List<SavedCue>,
        durationMs: Long,
        partialFile: File,
        completedFile: File,
        encode: suspend (List<SavedCue>, Long, File) -> Unit,
    ) {
        try {
            encode(cues, durationMs, partialFile)
            currentCoroutineContext().ensureActive()
            check(partialFile.exists() && partialFile.length() > 0L) { "Generated timeline audio is empty." }
            check(partialFile.renameTo(completedFile)) { "Could not commit English audio. Free space and retry." }
        } finally {
            if (partialFile.exists()) partialFile.delete()
        }
    }
}

