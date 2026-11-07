package app.englishdub.engine

import org.junit.Assert.*
import org.junit.Test

class ResourcePolicyTest {
    @Test fun `storage checks are bounded by time or bytes`() {
        val gate = WorkGate()
        assertTrue(gate.due(0)); repeat(1000) { assertFalse(gate.due(500, it.toLong())) }
        assertTrue(gate.due(1000, 1000)); assertTrue(gate.due(1001, 4L * 1024 * 1024 + 1000))
        assertFalse(gate.due(1500, 4L * 1024 * 1024 + 1001))
    }
    @Test fun `paused playback never schedules polling`() {
        assertNull(ResourcePolicy.pollingMs(false, 0)); assertNull(ResourcePolicy.pollingMs(false, 5000))
        assertEquals(100L, ResourcePolicy.pollingMs(true, 1999)); assertEquals(250L, ResourcePolicy.pollingMs(true, 2000))
    }
    @Test fun `budget devices have one recognition thread`() {
        assertEquals(1, ResourcePolicy.inferenceThreads(4L * 1024 * 1024 * 1024))
        assertEquals(2, ResourcePolicy.inferenceThreads(6L * 1024 * 1024 * 1024))
    }
    @Test fun `cache evicts least recently used entry`() {
        val cache = BoundedCache<String, String>(2)
        cache["a"] = "first"; cache["b"] = "second"; assertEquals("first", cache["a"])
        cache["c"] = "third"; assertFalse(cache.containsKey("b")); assertEquals(2, cache.size)
    }
    @Test fun `stale service completion cannot release new task`() {
        val admission = TaskAdmission()
        val first = admission.reserve(); assertTrue(admission.release(first))
        val next = admission.reserve(); assertFalse(admission.release(first)); assertTrue(admission.owns(next))
        assertTrue(admission.release(next))
    }
    @Test(expected = IllegalStateException::class) fun `pending launch blocks a duplicate task`() {
        val admission = TaskAdmission(); admission.reserve(); admission.reserve()
    }
    @Test fun `preparation signatures separate fields and invalidate changed inputs`() {
        assertNotEquals(PreparationIdentity.digest("ab", "c"), PreparationIdentity.digest("a", "bc"))
        assertEquals(PreparationIdentity.digest("film", "voice1"), PreparationIdentity.digest("film", "voice1"))
        assertNotEquals(PreparationIdentity.digest("film", "voice1"), PreparationIdentity.digest("film", "voice2"))
    }
    @Test fun `scratch estimate includes PCM AAC and free space reserve`() {
        assertEquals(1000L * 40 + 60000L * 6 + 16L * 1024 * 1024,
            ResourcePolicy.scratchEstimate(listOf(TextCue(0, 1000, "hello")), 60000))
    }
}
