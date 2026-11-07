package app.englishdub.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavConversionTest {
    @Test fun `stereo WAV is downmixed and resampled with correct length`() {
        val directory = java.nio.file.Files.createTempDirectory("dub-wave").toFile()
        try {
            val input = File(directory, "voice.wav"); val output = File(directory, "voice.pcm")
            val samples = 4800
            val dataBytes = samples * 4
            val buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put("RIFF".toByteArray()); buffer.putInt(36 + dataBytes); buffer.put("WAVEfmt ".toByteArray())
            buffer.putInt(16); buffer.putShort(1); buffer.putShort(2); buffer.putInt(48000)
            buffer.putInt(48000 * 4); buffer.putShort(4); buffer.putShort(16)
            buffer.put("data".toByteArray()); buffer.putInt(dataBytes)
            repeat(samples) { buffer.putShort(16384); buffer.putShort(0) }
            input.writeBytes(buffer.array())
            assertEquals(100L, AudioTools.wavToPcm(input, output))
            assertEquals(3200L, output.length())
            RandomAccessFile(output, "r").use { assertEquals(8192, java.lang.Short.reverseBytes(it.readShort()).toInt()) }
        } finally { directory.deleteRecursively() }
    }
    @Test(expected = IllegalArgumentException::class) fun `truncated WAV is rejected`() {
        val directory = java.nio.file.Files.createTempDirectory("dub-bad-wave").toFile()
        try {
            val input = File(directory, "bad.wav"); input.writeText("not a wav file")
            AudioTools.wavToPcm(input, File(directory, "output.pcm"))
        } finally { directory.deleteRecursively() }
    }
}
