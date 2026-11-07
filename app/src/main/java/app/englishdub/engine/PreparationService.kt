package app.englishdub.engine

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import app.englishdub.DubApplication
import app.englishdub.R
import app.englishdub.data.DubJob
import app.englishdub.data.SavedCue
import app.englishdub.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class PreparationStatus(val busy: Boolean = false, val id: String? = null, val message: String = "", val progress: Int = 0)

class PreparationService : Service() {
    private val app get() = application as DubApplication
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null
    @Volatile private var whisper: WhisperBridge? = null
    private var activeId: String? = null
    private var notificationAt = 0L
    private var activeMode: String = ""
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 29) {
            thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
                if (status >= PowerManager.THERMAL_STATUS_SEVERE && activeMode == PREPARE) {
                    whisper?.abort()
                    running?.cancel(CancellationException("The phone is too warm. Let it cool, then tap Resume."))
                }
            }
            getSystemService(PowerManager::class.java).addThermalStatusListener(mainExecutor, thermalListener!!)
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == PAUSE) {
            whisper?.abort(); running?.cancel(CancellationException("Preparation paused. Tap Resume to continue."))
            return START_NOT_STICKY
        }
        if (running?.isActive == true) return START_NOT_STICKY
        val id = intent?.getStringExtra("id") ?: return START_NOT_STICKY
        activeId = id
        val mode = intent.action.orEmpty()
        activeMode = mode
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= 35 && mode == PREPARE) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            else if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        val notification = notification("Starting…", 0)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, type) else startForeground(NOTIFICATION, notification)
        mutableStatus.value = PreparationStatus(true, id, "Starting…")
        running = scope.launch {
            val wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EnglishDub:preparation")
            try {
                wakeLock.acquire(6 * 60 * 60 * 1000L)
                withTimeout(5 * 60 * 60 * 1000L + 55 * 60 * 1000L) { when (mode) {
                    SPEECH_PACK -> app.models.download { report(id, "Downloading speech pack", it) }
                    TRANSLATION_PACK -> { report(id, "Downloading ${OfflineTranslation.languages[id]} pack over Wi-Fi", 0); OfflineTranslation.download(id) }
                    DOWNLOAD -> downloadVideo(id)
                    PREPARE -> prepare(id)
                } }
                mutableStatus.value = PreparationStatus(message = "Completed")
            } catch (e: Throwable) {
                val message = if (e is TimeoutCancellationException) "This long-running session was paused. Tap Resume to continue from saved progress."
                    else e.message ?: "Could not finish. Check your offline packs, video format, and available storage."
                withContext(NonCancellable) {
                    if (mode == PREPARE || mode == DOWNLOAD) app.jobs.get(id)?.let {
                        app.jobs.save(it.copy(state = if (e is CancellationException || e is PausedException) "paused" else "failed", message = message))
                    }
                    mutableStatus.value = PreparationStatus(message = message)
                }
            } finally {
                whisper = null
                if (wakeLock.isHeld) wakeLock.release()
                withContext(Dispatchers.Main + NonCancellable) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun downloadVideo(id: String) {
        val job = app.jobs.get(id) ?: return
        val file = File(app.jobDirectory(id), "video")
        app.jobs.save(job.copy(state = "downloading"))
        Downloads.fetch(job.mediaUri, file) { percent ->
            app.jobs.save(job.copy(state = "downloading", progress = percent, message = "Downloading video · $percent%"))
            report(id, "Downloading video", percent)
        }
        app.jobs.save(job.copy(mediaUri = Uri.fromFile(file).toString(), state = "new", progress = 0, message = "Downloaded. Add subtitles or prepare an English dub."))
    }

    private suspend fun prepare(id: String) {
        var job = app.jobs.get(id) ?: return
        require(!job.mediaUri.startsWith("http")) { "Download this video before preparing its dub." }
        val directory = app.jobDirectory(id)
        require(directory.usableSpace >= 100 * 1024 * 1024) { "At least 100 MB of free storage is needed. Free space and retry." }
        val media = Uri.parse(job.mediaUri)
        val duration = AudioTools.duration(this, media)
        require(duration > 0) { "Could not read this video. Try a file format supported by your phone." }
        job = job.copy(state = "preparing", durationMs = duration, message = "Checking audio tracks")
        app.jobs.save(job)
        if (AudioTools.hasEnglishAudio(this, media)) {
            app.jobs.save(job.copy(state = "ready", progress = 100, message = "An English audio track is available. Ready to watch."))
            return
        }
        var cues = app.jobs.cues(id)
        if (job.mode == "speech") {
            require(app.models.ready) { "Download the optional speech pack in Offline setup first." }
            if (job.decodedUntilMs < duration - 100) {
                val engine = WhisperBridge(app.models.speechFile.path)
                whisper = engine
                try {
                    AudioTools.decodeChunks(this, media, job.decodedUntilMs) { start, samples ->
                        checkThermal()
                        val percent = (start * 45 / duration).toInt()
                        reportJob(id, "Translating dialogue · ${start / 60000} / ${duration / 60000} min", percent)
                        // Avoid interpreting silence as dialogue.
                        val rms = kotlin.math.sqrt(samples.fold(0.0) { sum, sample -> sum + sample * sample } / samples.size)
                        val recognized = if (rms < 0.002) emptyList() else engine.translate(samples, job.sourceLanguage)
                        currentCoroutineContext().ensureActive()
                        val next = minOf(duration, start + samples.size * 1000L / AudioTools.RATE)
                        val validCues = recognized.map { it.copy(startMs = start + it.startMs, endMs = minOf(next, start + it.endMs)) }
                            .filter { it.endMs > it.startMs }
                        val newCues = validCues.mapIndexed { index, cue -> SavedCue(id, cues.size + index,
                            cue.startMs, cue.endMs, cue.text, english = cue.text) }
                        app.database.withTransaction {
                            app.jobs.saveCues(newCues)
                            job = job.copy(decodedUntilMs = next)
                            val current = app.jobs.get(id)!!
                            app.jobs.save(current.copy(decodedUntilMs = next))
                        }
                        cues = cues + newCues
                    }
                } finally { whisper = null; engine.close() }
            }
        } else if (cues.isEmpty()) {
            val parsed = if (job.subtitleUri != null) {
                contentResolver.openInputStream(Uri.parse(job.subtitleUri))!!.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer); if (count < 0) break
                        require(output.size() + count <= 8 * 1024 * 1024) { "Subtitle file exceeds the 8 MB limit." }
                        output.write(buffer, 0, count)
                    }
                    Subtitles.parse(output.toString("UTF-8"))
                }
            } else AudioTools.embeddedSubtitles(this, media, job.sourceLanguage)
            cues = parsed.mapIndexed { index, cue -> SavedCue(id, index, cue.startMs, cue.endMs, cue.text) }
            app.jobs.saveCues(cues)
        }
        require(cues.isNotEmpty()) { "No dialogue was found. Try subtitles or a clearer audio track." }
        if (job.mode != "speech" && job.sourceLanguage != "en") {
            require(job.sourceLanguage in OfflineTranslation.installed()) { "Download the ${OfflineTranslation.languages[job.sourceLanguage]} translation pack in Offline setup first." }
        }
        val translator = if (job.mode != "speech" && job.sourceLanguage != "en") OfflineTranslation(job.sourceLanguage) else null
        try {
            OfflineVoice.create(this).use { voice ->
                var previousEnd = 0L
                var warnings = 0
                cues.forEachIndexed { index, saved ->
                    currentCoroutineContext().ensureActive(); checkThermal()
                    var cue = saved
                    if (cue.pcmPath == null || !File(cue.pcmPath).exists()) {
                        val english = cue.english ?: translator?.translate(cue.text) ?: cue.text
                        val wav = File(directory, "cue-${cue.index}.wav")
                        val pcm = File(directory, "cue-${cue.index}.pcm")
                        voice.synthesize(english, wav)
                        var speechDuration = AudioTools.wavToPcm(wav, pcm)
                        val available = maxOf(1, cue.endMs - maxOf(cue.startMs, previousEnd))
                        if (speechDuration > available * 1.1) {
                            val rate = (speechDuration.toFloat() / available).coerceIn(1f, 1.25f)
                            voice.synthesize(english, wav, rate)
                            speechDuration = AudioTools.wavToPcm(wav, pcm)
                        }
                        wav.delete()
                        val timing = DubTiming.place(TextCue(cue.startMs, cue.endMs, english), speechDuration, previousEnd)
                        cue = cue.copy(english = english, pcmPath = pcm.path, rate = AudioTools.RATE,
                            spokenStartMs = timing.startMs, spokenEndMs = timing.endMs)
                        app.jobs.saveCue(cue)
                    }
                    if (cue.spokenEndMs > cue.endMs + 150 || cue.spokenStartMs > cue.startMs + 150) warnings++
                    previousEnd = cue.spokenEndMs
                    reportJob(id, "Generating English voice · ${index + 1}/${cues.size}", 45 + ((index + 1) * 40 / cues.size))
                }
                val finalCues = app.jobs.cues(id)
                val output = File(directory, "english.partial.m4a")
                AacTimeline.encode(finalCues, duration, output) { percent ->
                    checkThermal(); reportJob(id, "Saving English dub", 85 + percent * 15 / 100)
                }
                val completed = File(directory, "english.m4a")
                check(output.renameTo(completed)) { "Could not save English audio. Free space and retry." }
                val current = app.jobs.get(id)!!
                app.jobs.save(current.copy(state = "ready", progress = 100, audioPath = completed.path,
                    message = if (warnings == 0) "English dub ready. Works offline." else "English dub ready. $warnings lines run beyond their subtitle timing; complete speech is preserved."))
                // Checkpoints are needed only until the final AAC track is committed.
                finalCues.forEach { it.pcmPath?.let { path -> File(path).delete() } }
            }
        } finally { translator?.close() }
    }

    private suspend fun reportJob(id: String, message: String, progress: Int) {
        app.jobs.get(id)?.let { app.jobs.save(it.copy(state = "preparing", message = message, progress = progress)) }
        report(id, message, progress)
    }
    private fun report(id: String, message: String, progress: Int) {
        mutableStatus.value = PreparationStatus(true, id, message, progress)
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - notificationAt >= 1000 || progress == 100) {
            notificationAt = now
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(message, progress))
        }
    }
    private fun checkThermal() {
        if (Build.VERSION.SDK_INT >= 29 && getSystemService(PowerManager::class.java).currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE)
            throw PausedException("The phone is too warm. Let it cool, then tap Resume.")
    }
    private fun createChannel() { getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Offline dubbing", NotificationManager.IMPORTANCE_LOW)) }
    private fun notification(message: String, progress: Int): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pause = PendingIntent.getService(this, 1, Intent(this, PreparationService::class.java).setAction(PAUSE), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_app).setContentTitle("EnglishDub")
            .setContentText(message).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(100, progress, progress == 0).addAction(0, "Pause", pause).build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        whisper?.abort(); running?.cancel(CancellationException("Android paused this long-running task. Tap Resume to continue."))
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() {
        whisper?.abort(); scope.cancel()
        if (Build.VERSION.SDK_INT >= 29) thermalListener?.let { getSystemService(PowerManager::class.java).removeThermalStatusListener(it) }
        super.onDestroy()
    }
    private class PausedException(message: String) : Exception(message)
    companion object {
        const val PREPARE = "prepare"
        const val DOWNLOAD = "download"
        const val SPEECH_PACK = "speech-pack"
        const val TRANSLATION_PACK = "translation-pack"
        const val PAUSE = "pause"
        private const val CHANNEL = "preparation"
        private const val NOTIFICATION = 40
        private val mutableStatus = MutableStateFlow(PreparationStatus())
        val status = mutableStatus.asStateFlow()
        @Synchronized fun start(context: Context, action: String, id: String) {
            check(!status.value.busy) { "Pause the current task before starting another." }
            mutableStatus.value = PreparationStatus(true, id, "Starting…")
            try {
                ContextCompat.startForegroundService(context, Intent(context, PreparationService::class.java).setAction(action).putExtra("id", id))
            } catch (e: Exception) { mutableStatus.value = PreparationStatus(message = e.message.orEmpty()); throw e }
        }
        fun pause(context: Context) { context.startService(Intent(context, PreparationService::class.java).setAction(PAUSE)) }
    }
}
