package app.englishdub.engine

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

object ResourcePolicy {
    fun inferenceThreads(totalMemory: Long): Int = if (totalMemory <= 4L * 1024 * 1024 * 1024) 1 else 2
    fun pollingMs(playing: Boolean, elapsedSinceSeek: Long): Long? =
        if (!playing) null else if (elapsedSinceSeek < 2000) 100 else 250
    fun scratchEstimate(cues: List<TextCue>, movieDurationMs: Long): Long {
        val spokenMs = cues.sumOf { (it.endMs - it.startMs).coerceAtLeast(0) }
        return spokenMs * 40 + movieDurationMs * 6 + 16L * 1024 * 1024
    }
}

class BoundedCache<K, V>(private val capacity: Int = 128) : LinkedHashMap<K, V>(capacity, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > capacity
}
