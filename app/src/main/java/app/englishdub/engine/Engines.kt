package app.englishdub.engine

import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer

interface DialogueRecognizer : Closeable {
    /** The caller retains this direct buffer, but must not modify it until this call finishes. */
    suspend fun translate(samples: ByteBuffer, count: Int, language: String): List<TextCue>
    fun abort()
}
interface TextTranslator : Closeable { suspend fun translate(text: String): String }
interface VoiceSynthesizer : Closeable {
    val identity: String
    suspend fun synthesize(text: String, file: File, speed: Float = 1f)
}
