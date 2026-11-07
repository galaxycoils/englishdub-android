package app.englishdub.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

object Downloads {
    suspend fun fetch(url: String, target: File, progress: suspend (Int) -> Unit = {}) = withContext(Dispatchers.IO) {
        val uri = URI(url)
        require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank()) { "Enter a direct HTTP or HTTPS media URL." }
        val partial = File(target.path + ".part")
        val etagFile = File(target.path + ".etag")
        val offset = if (etagFile.exists()) partial.length() else 0L
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Accept-Encoding", "identity")
        if (offset > 0) {
            connection.setRequestProperty("Range", "bytes=$offset-")
            connection.setRequestProperty("If-Range", etagFile.readText())
        }
        try {
            val status = connection.responseCode
            require(status == 200 || status == 206) { "Download failed (HTTP $status). Use a direct downloadable link." }
            val append = status == 206 && offset > 0
            if (status == 206) require(connection.getHeaderField("Content-Range")?.startsWith("bytes $offset-") == true) { "Server returned an invalid download range. Retry the download." }
            val base = if (append) offset else 0L
            val expected = connection.contentLengthLong.takeIf { it > 0 }?.plus(base)
            val validator = connection.getHeaderField("ETag")?.takeUnless { it.startsWith("W/") }
                ?: connection.getHeaderField("Last-Modified")
            if (validator != null) etagFile.writeText(validator) else etagFile.delete()
            var total = base
            var lastPercent = -1
            connection.inputStream.use { input -> partial.outputStream(append).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    require(partial.parentFile!!.usableSpace > 16 * 1024 * 1024) { "Storage is full. Free some space and resume." }
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    total += count
                    val percent = expected?.let { ((total * 100) / it).toInt().coerceIn(0, 100) } ?: 0
                    if (percent != lastPercent) { progress(percent); lastPercent = percent }
                }
            } }
            require(expected == null || total == expected) { "Download interrupted. Resume to continue." }
            check(partial.renameTo(target)) { "Could not save download." }
            etagFile.delete()
        } finally { connection.disconnect() }
    }
    private fun File.outputStream(append: Boolean) = java.io.FileOutputStream(this, append)
}

class ModelStore(context: Context) {
    private val directory = File(context.filesDir, "models").apply { mkdirs() }
    val speechFile = File(directory, "ggml-base-q5_1.bin")
    val ready get() = speechFile.exists() && File(directory, "verified").exists() && speechFile.length() == SIZE
    suspend fun download(progress: suspend (Int) -> Unit) {
        Downloads.fetch(URL, speechFile, progress)
        withContext(Dispatchers.IO) {
            val digest = MessageDigest.getInstance("SHA-256")
            speechFile.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            val checksum = digest.digest().joinToString("") { "%02x".format(it) }
            if (checksum != SHA256) { speechFile.delete(); error("Speech pack failed verification. Download it again.") }
            File(directory, "verified").writeText(SHA256)
        }
    }
    fun delete() { directory.deleteRecursively(); directory.mkdirs() }
    companion object {
        const val SIZE = 59_707_625L
        const val URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin"
        const val SHA256 = "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"
    }
}
