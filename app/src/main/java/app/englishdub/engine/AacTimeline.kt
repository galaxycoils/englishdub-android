package app.englishdub.engine

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import app.englishdub.data.SavedCue
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Feed silence and individual speech files into one AAC track without a movie-sized PCM file. */
object AacTimeline {
    suspend fun encode(cues: List<SavedCue>, durationMs: Long, output: File, progress: suspend (Int) -> Unit) {
        val activeCues = cues.filter { !it.subtitlesOnly && !it.pcmPath.isNullOrBlank() && it.spokenEndMs > it.spokenStartMs }
        val codec = MediaCodec.createEncoderByType("audio/mp4a-latm")
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var speech: InputStream? = null
        try {
            val format = MediaFormat.createAudioFormat("audio/mp4a-latm", AudioTools.RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 48_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
            muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var track = -1
            var framesWritten = 0L
            val totalFrames = maxOf(durationMs, activeCues.maxOfOrNull { it.spokenEndMs } ?: 0) * AudioTools.RATE / 1000
            require(totalFrames > 0) { "The video duration is unknown." }
            var cueIndex = 0
            var openedCue = -1
            val info = MediaCodec.BufferInfo()
            var inputDone = false; var outputDone = false
            var lastPercent = -1
            val bytes = ByteArray(4096)
            val storageGate = WorkGate()
            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                if (storageGate.due(android.os.SystemClock.elapsedRealtime(), framesWritten * 2)) {
                    require(output.parentFile!!.usableSpace > 16 * 1024 * 1024) { "Storage is full. Free space and resume." }
                }
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        buffer.clear()
                        val count = minOf(buffer.remaining() / 2L, bytes.size / 2L, (totalFrames - framesWritten).coerceAtLeast(0)).toInt()
                        bytes.fill(0)
                        var filled = 0
                        while (filled < count) {
                            val frame = framesWritten + filled
                            while (cueIndex < activeCues.size && frame >= activeCues[cueIndex].spokenEndMs * AudioTools.RATE / 1000) {
                                speech?.close(); speech = null; cueIndex++; openedCue = -1
                            }
                            val cue = activeCues.getOrNull(cueIndex)
                            val cueStart = cue?.spokenStartMs?.times(AudioTools.RATE)?.div(1000) ?: totalFrames
                            if (cue == null || frame < cueStart) {
                                filled += minOf(count - filled, (cueStart - frame).toInt().coerceAtLeast(1)); continue
                            }
                            if (openedCue != cueIndex) {
                                speech?.close()
                                speech = CueAudioCache.openPcmStream(File(cue.pcmPath!!))
                                openedCue = cueIndex
                            }
                            val cueEnd = cue.spokenEndMs * AudioTools.RATE / 1000
                            val availableFrames = minOf(count - filled, (cueEnd - frame).toInt())
                            val readFrames = readPcmFrames(speech!!, bytes, filled * 2, availableFrames)
                            filled += if (readFrames <= 0) availableFrames else readFrames
                        }
                        buffer.put(bytes, 0, count * 2)
                        val flags = if (count == 0) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        codec.queueInputBuffer(index, 0, count * 2, framesWritten * 1_000_000 / AudioTools.RATE, flags)
                        framesWritten += count; inputDone = count == 0
                        val percent = (framesWritten * 100 / totalFrames).toInt()
                        if (percent != lastPercent) { progress(percent); lastPercent = percent }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat); muxer.start(); muxerStarted = true
                } else if (index >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val buffer = codec.getOutputBuffer(index)!!
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        check(muxerStarted); muxer.writeSampleData(track, buffer, info)
                    }
                    outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                }
            }
        } finally {
            speech?.close()
            try { codec.stop() } finally { codec.release() }
            try { if (muxerStarted) muxer?.stop() } finally { muxer?.release() }
        }
    }

    internal fun readPcmFrames(stream: InputStream, target: ByteArray, offsetBytes: Int, maxFrames: Int): Int {
        val wantBytes = maxFrames * 2
        var totalRead = 0
        while (totalRead < wantBytes) {
            val n = stream.read(target, offsetBytes + totalRead, wantBytes - totalRead)
            if (n < 0) break
            totalRead += n
        }
        if (totalRead % 2 != 0) target[offsetBytes + totalRead++] = 0
        return totalRead / 2
    }
}

