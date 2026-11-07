package app.englishdub.engine

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

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
