package app.englishdub.engine

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import app.englishdub.DubApplication
import app.englishdub.R
import app.englishdub.data.SavedCue
import app.englishdub.ui.MainActivity
import kotlinx.coroutines.*
import java.io.File

class PreparationService : Service() {
    private val app get() = application as DubApplication
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null
    @Volatile private var whisper: WhisperBridge? = null
    private var activeMode = ""
    private var ticket = 0L
    private var progressGate = WorkGate()
    private var lastStage = ""
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 29) {
            thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
                if (status >= PowerManager.THERMAL_STATUS_SEVERE && activeMode == PREPARE) cancel("The phone is too warm. Let it cool, then tap Resume.")
            }
            getSystemService(PowerManager::class.java).addThermalStatusListener(mainExecutor, thermalListener!!)
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun cancel(reason: String) { whisper?.abort(); running?.cancel(CancellationException(reason)) }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == PAUSE) {
            cancel("Preparation paused. Tap Resume to continue.")
            if (running == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val id = intent?.getStringExtra("id") ?: run { stopSelfResult(startId); return START_NOT_STICKY }
        val requestTicket = intent.getLongExtra("ticket", -1)
        if (!PreparationCoordinator.owns(requestTicket)) { stopSelfResult(startId); return START_NOT_STICKY }
        if (running != null) return START_NOT_STICKY
        ticket = requestTicket; activeMode = intent.action.orEmpty()
        val mode = activeMode
        progressGate = WorkGate(); lastStage = ""
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Offline dubbing", NotificationManager.IMPORTANCE_LOW))
        val type = if (Build.VERSION.SDK_INT >= 35 && mode == PREPARE) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            else if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification("Starting…", 0), type)
        else startForeground(NOTIFICATION, notification("Starting…", 0))
        running = scope.launch {
            val lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EnglishDub:preparation")
            var result = "Completed"
            try {
                app.recovery.await()
                lock.acquire(6 * 60 * 60 * 1000L)
                withTimeout(5 * 60 * 60 * 1000L + 55 * 60 * 1000L) {
                    when (mode) {
                        SPEECH_PACK -> app.models.download { publish(id, "Downloading speech pack", it) }
                        VAD_PACK -> app.models.downloadVad { publish(id, "Downloading speech detector", it) }
                        TRANSLATION_PACK -> { publish(id, "Downloading ${OfflineTranslation.languages[id]} over Wi-Fi", 0); OfflineTranslation.download(id) }
                        DOWNLOAD -> downloadVideo(id)
                        PREPARE -> prepare(id)
                        else -> error("Unknown task.")
                    }
                }
            } catch (e: Exception) {
                result = if (e is TimeoutCancellationException) "This long-running session was paused. Tap Resume to continue."
                    else e.message ?: "Could not finish. Check the file, offline packs, and available storage."
                withContext(NonCancellable) {
                    if (mode in listOf(PREPARE, DOWNLOAD)) app.jobs.state(id, if (e is CancellationException || e is PausedException) "paused" else "failed", result)
                }
            } finally {
                whisper = null
                if (lock.isHeld) lock.release()
                withContext(Dispatchers.Main + NonCancellable) {
                    running = null; activeMode = ""
                    stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(startId)
                    PreparationCoordinator.finish(requestTicket, result)
                }
            }
        }
        return START_NOT_STICKY
    }
    private suspend fun downloadVideo(id: String) {
        val job = app.jobs.get(id) ?: error("Video was removed.")
        app.jobs.state(id, "downloading", "Downloading video")
        val file = File(app.jobDirectory(id), "video")
        Downloads.fetch(job.mediaUri, file) { publish(id, "Downloading video · $it%", it, true) }
        app.jobs.get(id)?.let { app.jobs.save(it.copy(mediaUri = Uri.fromFile(file).toString(), state = "new", progress = 0, message = "Downloaded. Add subtitles or prepare a dub.")) }
    }
    private suspend fun prepare(id: String) {
        var job = app.jobs.get(id) ?: error("Video was removed.")
        require(!job.mediaUri.startsWith("http")) { "Download the video before preparing its dub." }
        val media = Uri.parse(job.mediaUri)
        app.jobs.state(id, "preparing", "Checking available audio")
        if (AudioTools.hasEnglishAudio(this, media)) {
            app.jobs.get(id)?.let { app.jobs.save(it.copy(state = "ready", audioPath = null, progress = 100, message = "English audio is available. Ready to watch.")) }
            return
        }
        val duration = AudioTools.duration(this, media)
        require(duration > 0) { "Could not read video duration. Try a supported file format." }
        if (job.mode == "speech") require(app.models.ready) { "Download the optional speech pack in Offline setup first." }
        else if (job.sourceLanguage != "en") require(job.sourceLanguage in OfflineTranslation.installed()) { "Download the ${OfflineTranslation.languages[job.sourceLanguage]} translation pack in Offline setup first." }
        val directory = app.jobDirectory(id)
        require(directory.usableSpace > 32 * 1024 * 1024) { "Free at least 32 MB of storage and retry." }
        val subtitleText = job.subtitleUri?.takeIf { job.mode != "speech" }?.let { readSubtitles(Uri.parse(it)) }
        val source = PreparationIdentity.source(this, media) ?: java.util.UUID.randomUUID().toString()
        val useVad = job.mode == "speech" && app.models.vadReady && getSharedPreferences("preparation", MODE_PRIVATE).getBoolean("vad", false)
        val signature = PreparationIdentity.digest(source, subtitleText.orEmpty(), job.sourceLanguage, job.mode,
            if (job.mode == "speech") ModelStore.SHA256 else "mlkit-17.0.3", if (useVad) ModelStore.VAD_SHA256 else "no-vad", PreparationIdentity.PIPELINE)
        if (job.preparationSignature != signature) {
            clearScratch(directory)
            app.database.withTransaction {
                app.jobs.clearCues(id)
                job = job.copy(preparationSignature = signature, voiceSignature = null, decodedUntilMs = 0, audioPath = null, durationMs = duration, state = "preparing")
                app.jobs.save(job)
            }
        }
        var cues = app.jobs.cues(id)
        if (job.mode == "speech" && job.decodedUntilMs < duration - 100) {
            val memory = ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
            val engine = WhisperBridge(app.models.speechFile.path, ResourcePolicy.inferenceThreads(memory.totalMem), if (useVad) app.models.vadFile.path else null)
            whisper = engine
            try {
                AudioTools.decodeChunks(this, media, job.decodedUntilMs) { start, buffer, count, rms ->
                    checkThermal()
                    publish(id, "Translating dialogue · ${start / 60000}/${duration / 60000} min", (start * 45 / duration).toInt(), true)
                    val recognized = if (rms < 0.002) emptyList() else engine.translate(buffer, count, job.sourceLanguage)
                    currentCoroutineContext().ensureActive()
                    val next = minOf(duration, start + count * 1000L / AudioTools.RATE)
                    val valid = recognized.map { it.copy(startMs = start + it.startMs, endMs = minOf(next, start + it.endMs)) }.filter { it.endMs > it.startMs }
                    val saved = valid.mapIndexed { index, cue -> SavedCue(id, cues.size + index, cue.startMs, cue.endMs, cue.text, english = cue.text) }
                    app.database.withTransaction { app.jobs.saveCues(saved); app.jobs.decoded(id, next) }
                    cues = cues + saved
                }
            } finally { whisper = null; engine.close() }
        } else if (job.mode != "speech" && cues.isEmpty()) {
            val parsed = subtitleText?.let { Subtitles.parse(it) } ?: AudioTools.embeddedSubtitles(this, media, job.sourceLanguage)
            cues = parsed.mapIndexed { index, cue -> SavedCue(id, index, cue.startMs, cue.endMs, cue.text) }
            app.jobs.saveCues(cues)
        }
        require(cues.isNotEmpty()) { "No dialogue was found. Add readable subtitles or try clearer audio." }
        val estimate = ResourcePolicy.scratchEstimate(cues.map { TextCue(it.startMs, it.endMs, it.text) }, duration)
        val existingPcm = directory.walkTopDown().filter { it.isFile && it.extension == "pcm" }.sumOf { it.length() }
        require(directory.usableSpace > (estimate - existingPcm).coerceAtLeast(16L * 1024 * 1024)) { "Preparation needs about ${(estimate - existingPcm).coerceAtLeast(0) / 1024 / 1024} MB more free storage. Free space and resume." }
        val translator = if (job.mode != "speech" && job.sourceLanguage != "en") OfflineTranslation(job.sourceLanguage) else null
        var warnings = 0
        try {
            OfflineVoice.create(this).use { voice ->
                val current = app.jobs.get(id)!!
                if (current.voiceSignature != voice.identity) {
                    app.jobs.invalidateVoice(id)
                    app.jobs.save(current.copy(voiceSignature = voice.identity))
                    cues = app.jobs.cues(id)
                }
                val cache = CueAudioCache(File(directory, "cue-audio"), voice.identity)
                val translations = BoundedCache<String, String>()
                var previousEnd = 0L
                for ((index, saved) in cues.withIndex()) {
                    currentCoroutineContext().ensureActive(); checkThermal()
                    var cue = saved
                    if (cue.pcmPath == null || !File(cue.pcmPath).exists()) {
                        val english = cue.english ?: translations[cue.text] ?: (translator?.translate(cue.text) ?: cue.text).also { translations[cue.text] = it }
                        if (cue.english == null) { cue = cue.copy(english = english); app.jobs.saveCue(cue) }
                        var asset = cache.get(english, 1f) { file, speed -> voice.synthesize(english, file, speed) }
                        val available = maxOf(1, cue.endMs - maxOf(cue.startMs, previousEnd))
                        if (asset.durationMs > available * 1.1) {
                            val rate = ((asset.durationMs.toFloat() / available).coerceIn(1f, 1.25f) * 100).toInt() / 100f
                            asset = cache.get(english, rate) { file, speed -> voice.synthesize(english, file, speed) }
                        }
                        val timing = DubTiming.place(TextCue(cue.startMs, cue.endMs, english), asset.durationMs, previousEnd)
                        cue = cue.copy(pcmPath = asset.file.path, rate = AudioTools.RATE, spokenStartMs = timing.startMs, spokenEndMs = timing.endMs)
                        app.jobs.saveCue(cue)
                    }
                    if (cue.spokenEndMs > cue.endMs + 150 || cue.spokenStartMs > cue.startMs + 150) warnings++
                    previousEnd = cue.spokenEndMs
                    publish(id, "Generating English voice · ${index + 1}/${cues.size}", 45 + (index + 1) * 40 / cues.size, true)
                }
            }
        } finally { translator?.close() }
        val finalCues = app.jobs.cues(id)
        val partial = File(directory, "english.partial.m4a")
        AacTimeline.encode(finalCues, duration, partial) { checkThermal(); publish(id, "Saving English dub", 85 + it * 15 / 100, true) }
        val completed = File(directory, "english.m4a")
        check(partial.renameTo(completed)) { "Could not commit English audio. Free space and retry." }
        app.jobs.get(id)?.let { app.jobs.save(it.copy(state = "ready", progress = 100, audioPath = completed.path,
            message = if (warnings == 0) "English dub ready. Works offline." else "English dub ready. $warnings lines exceed their original timing; complete speech is preserved.")) }
        clearScratch(directory)
    }
    private suspend fun readSubtitles(uri: Uri): String = contentResolver.openInputStream(uri)?.use { input ->
        val output = java.io.ByteArrayOutputStream(); val bytes = ByteArray(8192)
        while (true) {
            currentCoroutineContext().ensureActive(); val n = input.read(bytes); if (n < 0) break
            require(output.size() + n <= 8 * 1024 * 1024) { "Subtitle file exceeds 8 MB." }; output.write(bytes, 0, n)
        }
        output.toString("UTF-8")
    } ?: error("Subtitle file is no longer accessible. Add it again.")
    private fun clearScratch(directory: File) {
        File(directory, "cue-audio").deleteRecursively()
        directory.listFiles()?.filter { it.extension in listOf("pcm", "wav") }?.forEach { it.delete() }
    }
    private suspend fun publish(id: String, message: String, progress: Int, persist: Boolean = false) {
        val stage = message.substringBefore('·')
        if (progressGate.due(SystemClock.elapsedRealtime()) || stage != lastStage || progress == 100) {
            lastStage = stage
            if (persist) app.jobs.progress(id, progress, message)
            PreparationCoordinator.report(ticket, PreparationStatus(true, id, message, progress))
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(message, progress))
        }
    }
    private fun checkThermal() {
        if (Build.VERSION.SDK_INT >= 29 && getSystemService(PowerManager::class.java).currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) throw PausedException("The phone is too warm. Let it cool, then tap Resume.")
    }
    private fun notification(message: String, progress: Int): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pause = PendingIntent.getService(this, 1, Intent(this, PreparationService::class.java).setAction(PAUSE), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_app).setContentTitle("EnglishDub").setContentText(message)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setProgress(100, progress, progress == 0).addAction(0, "Pause", pause).build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) { cancel("Android paused this long task. Tap Resume to continue."); stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(startId) }
    override fun onDestroy() {
        cancel("Preparation interrupted. Tap Resume to continue."); scope.cancel()
        if (Build.VERSION.SDK_INT >= 29) thermalListener?.let { getSystemService(PowerManager::class.java).removeThermalStatusListener(it) }
        super.onDestroy()
    }
    private class PausedException(message: String) : Exception(message)
    companion object {
        const val PREPARE = "prepare"; const val DOWNLOAD = "download"; const val SPEECH_PACK = "speech-pack"
        const val VAD_PACK = "vad-pack"; const val TRANSLATION_PACK = "translation-pack"; const val PAUSE = "pause"
        private const val CHANNEL = "preparation"; private const val NOTIFICATION = 40
        val status get() = PreparationCoordinator.status
        fun start(context: Context, action: String, id: String) {
            val ticket = PreparationCoordinator.reserve(id)
            try { ContextCompat.startForegroundService(context, Intent(context, PreparationService::class.java).setAction(action).putExtra("id", id).putExtra("ticket", ticket)) }
            catch (e: Exception) { PreparationCoordinator.finish(ticket, e.message.orEmpty()); throw e }
        }
        fun pause(context: Context) { if (status.value.busy) context.startService(Intent(context, PreparationService::class.java).setAction(PAUSE)) }
    }
}
