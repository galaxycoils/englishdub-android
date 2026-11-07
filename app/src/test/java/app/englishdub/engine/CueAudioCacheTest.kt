package app.englishdub.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class CueAudioCacheTest {
    private fun wave(file: File) {
        val bytes = 3200
        val data = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()); data.putInt(36 + bytes); data.put("WAVEfmt ".toByteArray())
        data.putInt(16); data.putShort(1); data.putShort(1); data.putInt(16000)
        data.putInt(32000); data.putShort(2); data.putShort(16)
        data.put("data".toByteArray()); data.putInt(bytes)
        repeat(bytes / 2) { data.putShort(8192) }
        file.writeBytes(data.array())
    }
    @Test fun `completed repeated speech is reused after reopening the cache`() = runBlocking {
        val directory = Files.createTempDirectory("dub-cache").toFile()
        try {
            var generated = 0
            val synthesize: suspend (File, Float) -> Unit = { file, _ -> generated++; wave(file) }
            val first = CueAudioCache(directory, "voice").get("Hello", 1f, synthesize)
            val resumed = CueAudioCache(directory, "voice").get("Hello", 1f, synthesize)
            assertEquals(1, generated); assertEquals(first, resumed)
            assertEquals(100L, resumed.durationMs)
        } finally { directory.deleteRecursively() }
    }
    @Test fun `voice and rate changes generate separate speech assets`() = runBlocking {
        val directory = Files.createTempDirectory("dub-cache-identity").toFile()
        try {
            var generated = 0
            val synthesize: suspend (File, Float) -> Unit = { file, _ -> generated++; wave(file) }
            val original = CueAudioCache(directory, "voice-a").get("Hello", 1f, synthesize)
            val voice = CueAudioCache(directory, "voice-b").get("Hello", 1f, synthesize)
            val rate = CueAudioCache(directory, "voice-a").get("Hello", 1.25f, synthesize)
            assertEquals(3, generated)
            assertNotEquals(original.file, voice.file); assertNotEquals(original.file, rate.file)
        } finally { directory.deleteRecursively() }
    }
    @Test fun `incomplete PCM and failed synthesis cannot become cache hits`() = runBlocking {
        val directory = Files.createTempDirectory("dub-cache-partial").toFile()
        try {
            val key = PreparationIdentity.digest("Hello", "voice", "1.0", PreparationIdentity.PIPELINE)
            File(directory, "$key.pcm").writeBytes(byteArrayOf(1, 2, 3))
            val cache = CueAudioCache(directory, "voice")
            try {
                cache.get("Hello", 1f) { file, _ -> file.writeText("incomplete"); error("interrupted") }
                fail("Synthesis failure must propagate")
            } catch (error: IllegalStateException) { assertEquals("interrupted", error.message) }
            assertFalse(File(directory, "$key.complete").exists())
            assertFalse(File(directory, "$key.wav").exists())
            val recovered = cache.get("Hello", 1f) { file, _ -> wave(file) }
            assertEquals(3200L, recovered.file.length()); assertEquals(100L, recovered.durationMs)
            assertEquals("3200", File(directory, "$key.complete").readText())
        } finally { directory.deleteRecursively() }
    }
    @Test fun `compressed reusable speech clips decode incrementally with exact samples`() = runBlocking {
        val directory = Files.createTempDirectory("dub-cache-gz").toFile()
        try {
            var generated = 0
            val cache = CueAudioCache.reusable(directory, "voice-en")
            val first = cache.get("Namaste friend", 1f) { file, _ -> generated++; wave(file) }
            val second = cache.get("Namaste friend", 1f) { file, _ -> generated++; wave(file) }
            assertEquals(1, generated)
            assertEquals(first, second)
            assertTrue(first.file.name.endsWith(".pcm.gz"))
            assertTrue(first.file.length() in 1..3199)
            assertEquals(100L, first.durationMs)
            val decoded = ByteArray(4096)
            val frames = CueAudioCache.openPcmStream(first.file).use { stream ->
                AacTimeline.readPcmFrames(stream, decoded, 0, 2000)
            }
            assertEquals(1600, frames)
            val sample0 = ByteBuffer.wrap(decoded, 0, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
            assertEquals(8192, sample0)
        } finally { directory.deleteRecursively() }
    }
    @Test fun `LRU eviction respects byte cap and protects active job assets`() = runBlocking {
        val directory = Files.createTempDirectory("dub-cache-lru").toFile()
        try {
            val cache = CueAudioCache(directory, "voice", compressed = false, maxBytes = 7000L)
            val a = cache.get("Line A", 1f) { file, _ -> wave(file) }
            val b = cache.get("Line B", 1f) { file, _ -> wave(file) }
            // Access Line A so Line B becomes least-recently-used, then protect Line B as an active-job asset
            cache.get("Line A", 1f) { file, _ -> wave(file) }
            val c = cache.get("Line C", 1f, setOf(b.file.path)) { file, _ -> wave(file) }
            assertTrue("Protected active-job clip B must survive eviction", b.file.exists())
            assertTrue("Newly synthesized clip C must exist", c.file.exists())
            assertFalse("Unprotected clip A must be evicted to stay within cap", a.file.exists())
        } finally { directory.deleteRecursively() }
    }
}

