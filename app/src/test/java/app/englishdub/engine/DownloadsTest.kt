package app.englishdub.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference

class DownloadsTest {
    @Test fun `validated partial download resumes at the requested offset`() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("dub-download").toFile()
        val server = TestServer(206, "def", mapOf("Content-Range" to "bytes 3-5/6", "ETag" to "\"version1\"")) { headers ->
            assertEquals("bytes=3-", headers["range"])
            assertEquals("\"version1\"", headers["if-range"])
        }
        try {
            val file = File(directory, "video")
            File(file.path + ".part").writeText("abc")
            File(file.path + ".etag").writeText("\"version1\"")
            Downloads.fetch(server.url, file)
            server.assertRequest()
            assertEquals("abcdef", file.readText())
            assertFalse(File(file.path + ".part").exists()); assertFalse(File(file.path + ".etag").exists())
        } finally { server.close(); directory.deleteRecursively() }
    }
    @Test fun `server rejecting range replaces partial instead of appending`() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("dub-restart").toFile()
        val server = TestServer(200, "newest", mapOf("ETag" to "\"version2\""))
        try {
            val file = File(directory, "video")
            File(file.path + ".part").writeText("old")
            File(file.path + ".etag").writeText("\"version1\"")
            Downloads.fetch(server.url, file)
            server.assertRequest()
            assertEquals("newest", file.readText())
        } finally { server.close(); directory.deleteRecursively() }
    }
    @Test fun `partial file without validator restarts safely`() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("dub-unvalidated").toFile()
        val server = TestServer(200, "safe") { headers -> assertNull(headers["range"]) }
        try {
            val file = File(directory, "video"); File(file.path + ".part").writeText("stale")
            Downloads.fetch(server.url, file)
            server.assertRequest()
            assertEquals("safe", file.readText())
        } finally { server.close(); directory.deleteRecursively() }
    }

    private class TestServer(status: Int, body: String, headers: Map<String, String> = emptyMap(), check: (Map<String, String>) -> Unit = {}) : AutoCloseable {
        private val socket = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        private val failure = AtomicReference<Throwable?>()
        val url get() = "http://127.0.0.1:${socket.localPort}/video"
        private val thread = Thread {
            try { socket.accept().use { client ->
                client.soTimeout = 5000
                val reader = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                reader.readLine()
                val requestHeaders = mutableMapOf<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    requestHeaders[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                }
                try { check(requestHeaders) } catch (e: Throwable) { failure.set(e) }
                val response = "HTTP/1.1 $status OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n" +
                    headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n$body"
                client.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
            } } catch (e: Throwable) { failure.set(e) }
        }.apply { isDaemon = true; start() }
        fun assertRequest() { thread.join(5000); check(!thread.isAlive); failure.get()?.let { throw it } }
        override fun close() { socket.close(); thread.join(1000) }
    }
}
