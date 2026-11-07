package app.englishdub.engine

import app.englishdub.data.DubJob
import java.io.File

/** Time is injected so throttling can be tested without waiting or Android framework mocks. */
class WorkGate(private val intervalMs: Long = 1000, private val byteInterval: Long = 4L * 1024 * 1024) {
    private var lastTime = Long.MIN_VALUE
    private var lastBytes = 0L
    fun due(now: Long, bytes: Long = 0): Boolean {
        if (lastTime == Long.MIN_VALUE || now - lastTime >= intervalMs || bytes - lastBytes >= byteInterval) {
            lastTime = now; lastBytes = bytes; return true
        }
        return false
    }
}

data class StorageBreakdown(
    val previewsBytes: Long = 0L,
    val speechCacheBytes: Long = 0L,
    val completedDubsBytes: Long = 0L,
    val downloadsBytes: Long = 0L,
) {
    val totalBytes: Long get() = previewsBytes + speechCacheBytes + completedDubsBytes + downloadsBytes
}

object ResourcePolicy {
    fun inferenceThreads(totalMemory: Long): Int = if (totalMemory <= 4L * 1024 * 1024 * 1024) 1 else 2
    fun pollingMs(playing: Boolean, elapsedSinceSeek: Long): Long? =
        if (!playing) null else if (elapsedSinceSeek < 2000) 100 else 250
    fun scratchEstimate(cues: List<TextCue>, movieDurationMs: Long): Long {
        val spokenMs = cues.sumOf { (it.endMs - it.startMs).coerceAtLeast(0) }
        return spokenMs * 40 + movieDurationMs * 6 + 16L * 1024 * 1024
    }

    /** Estimate full movie preparation time from measured scene/progress duration; always mark as an estimate. */
    fun estimateFullPreparationMs(elapsedMs: Long, processedMovieMs: Long, totalMovieMs: Long): Long {
        if (elapsedMs <= 0L || processedMovieMs <= 0L || totalMovieMs <= 0L) return 0L
        return (elapsedMs.toDouble() * totalMovieMs.toDouble() / processedMovieMs.toDouble()).toLong().coerceAtLeast(1000L)
    }

    fun formatEstimateLabel(estimatedMs: Long): String {
        if (estimatedMs <= 0L) return "Full preparation time: calculating (estimate)"
        val minutes = ((estimatedMs + 30_000L) / 60_000L).coerceAtLeast(1L)
        return if (minutes >= 60L) {
            val hours = minutes / 60L
            val rem = minutes % 60L
            "Estimated full preparation: ~${hours}h ${rem}m (estimate)"
        } else {
            "Estimated full preparation: ~$minutes min (estimate)"
        }
    }

    fun canRunQueued(chargingOnly: Boolean, isDeviceCharging: Boolean): Boolean =
        !chargingOnly || isDeviceCharging

    fun reorderQueue(queued: List<DubJob>, targetId: String, delta: Int): List<DubJob> {
        val mutable = queued.sortedWith(compareBy({ it.queueOrder }, { it.createdAt })).toMutableList()
        val index = mutable.indexOfFirst { it.id == targetId }
        if (index < 0) return mutable
        val next = (index + delta).coerceIn(0, mutable.lastIndex)
        if (next != index) {
            val item = mutable.removeAt(index)
            mutable.add(next, item)
        }
        return mutable.mapIndexed { order, job -> job.copy(queueOrder = order + 1) }
    }

    fun computeStorage(filesDir: File): StorageBreakdown {
        val jobsRoot = File(filesDir, "jobs")
        val speechRoot = File(filesDir, "speech-cache")
        val modelsRoot = File(filesDir, "models")
        var previews = 0L
        var speechCache = if (speechRoot.exists()) speechRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
        var completedDubs = 0L
        var downloads = if (modelsRoot.exists()) modelsRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
        if (jobsRoot.exists()) {
            jobsRoot.listFiles()?.filter { it.isDirectory }?.forEach { jobDir ->
                jobDir.walkTopDown().filter { it.isFile }.forEach { f ->
                    when {
                        f.name.startsWith("preview.") -> previews += f.length()
                        f.name == "english.m4a" || f.name == "english.partial.m4a" -> completedDubs += f.length()
                        f.name.startsWith("video") -> downloads += f.length()
                        f.parentFile?.name == "cue-audio" || f.extension in setOf("pcm", "wav", "gz", "complete", "cache") -> speechCache += f.length()
                    }
                }
            }
        }
        return StorageBreakdown(previews, speechCache, completedDubs, downloads)
    }

    fun clearPreviews(filesDir: File) {
        val jobsRoot = File(filesDir, "jobs")
        if (!jobsRoot.exists()) return
        jobsRoot.listFiles()?.filter { it.isDirectory }?.forEach { jobDir ->
            File(jobDir, "preview.m4a").delete()
            File(jobDir, "preview.partial.m4a").delete()
            File(jobDir, "preview.cues").delete()
            File(jobDir, "preview.cues.tmp").delete()
        }
    }

    fun clearSpeechCache(filesDir: File, activeJobId: String? = null, protectedPaths: Set<String> = emptySet()) {
        val protectedNames = protectedPaths.flatMap { path ->
            val f = File(path)
            val base = f.name.removeSuffix(".pcm.gz").removeSuffix(".pcm")
            listOf(f.name, "$base.complete", "$base.pcm", "$base.pcm.gz")
        }.toSet()
        val speechRoot = File(filesDir, "speech-cache")
        if (speechRoot.exists()) {
            speechRoot.listFiles()?.filter { it.isFile && it.name != "translations.cache" && it.name !in protectedNames && it.path !in protectedPaths }?.forEach { it.delete() }
        }
        val jobsRoot = File(filesDir, "jobs")
        if (jobsRoot.exists()) {
            jobsRoot.listFiles()?.filter { it.isDirectory && it.name != activeJobId }?.forEach { jobDir ->
                File(jobDir, "cue-audio").deleteRecursively()
                jobDir.listFiles()?.filter { it.extension in setOf("pcm", "wav") }?.forEach { it.delete() }
            }
        }
    }

    fun clearCompletedDubs(filesDir: File, activeJobId: String? = null) {
        val jobsRoot = File(filesDir, "jobs")
        if (!jobsRoot.exists()) return
        jobsRoot.listFiles()?.filter { it.isDirectory && it.name != activeJobId }?.forEach { jobDir ->
            File(jobDir, "english.m4a").delete()
            File(jobDir, "english.partial.m4a").delete()
        }
    }

    /** Remove only app-owned downloads (downloaded videos and models); never delete user-owned local files. */
    fun clearAppDownloads(filesDir: File, activeJobId: String? = null): Set<String> {
        val removedVideoJobIds = mutableSetOf<String>()
        val jobsRoot = File(filesDir, "jobs")
        if (jobsRoot.exists()) {
            jobsRoot.listFiles()?.filter { it.isDirectory && it.name != activeJobId }?.forEach { jobDir ->
                val videoFile = File(jobDir, "video")
                if (videoFile.exists() && videoFile.delete()) removedVideoJobIds += jobDir.name
                File(jobDir, "video.part").delete()
                File(jobDir, "video.etag").delete()
            }
        }
        if (activeJobId == null) {
            File(filesDir, "models").deleteRecursively()
        }
        return removedVideoJobIds
    }
}

class BoundedCache<K, V>(private val capacity: Int = 128) : LinkedHashMap<K, V>(capacity, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > capacity
}

