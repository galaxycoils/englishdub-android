package app.englishdub.engine

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import app.englishdub.DubApplication
import app.englishdub.R
import app.englishdub.data.DubJob
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
    private var activeChargingOnly = false
    private var ticket = 0L
    private var progressGate = WorkGate()
    private var lastStage = ""
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_POWER_DISCONNECTED && activeChargingOnly && activeMode == PREPARE) {
                cancel("Paused: unplugged under charging-only mode. Plug in or override charging-only, then tap Resume.")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 29) {
            thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
                if (status >= PowerManager.THERMAL_STATUS_SEVERE && activeMode in setOf(PREPARE, PREVIEW, REBUILD)) {
                    cancel("The phone is too warm. Let it cool, then tap Resume.")
                }
            }
            getSystemService(PowerManager::class.java).addThermalStatusListener(mainExecutor, thermalListener!!)
        }
        registerReceiver(powerReceiver, IntentFilter(Intent.ACTION_POWER_DISCONNECTED))
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
        val previewStartMs = intent.getLongExtra("previewStartMs", 0L)
        val enforceCharging = intent.getBooleanExtra("enforceCharging", false)
        progressGate = WorkGate(); lastStage = ""
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Offline dubbing", NotificationManager.IMPORTANCE_LOW))
        val mediaProcessing = mode in setOf(PREPARE, PREVIEW, REBUILD)
        val type = if (Build.VERSION.SDK_INT >= 35 && mediaProcessing) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            else if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification("Starting…", 0), type)
        else startForeground(NOTIFICATION, notification("Starting…", 0))
        running = scope.launch {
            val lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EnglishDub:preparation")
            var result = "Completed"
            var currentId = id
            try {
                app.recovery.await()
                lock.acquire(6 * 60 * 60 * 1000L)
                withTimeout(5 * 60 * 60 * 1000L + 55 * 60 * 1000L) {
                    when (mode) {
                        AUTO_SETUP -> result = autoSetup(currentId)
                        SPEECH_PACK -> app.models.download { publish(currentId, "Downloading speech pack", it) }
                        VAD_PACK -> app.models.downloadVad { publish(currentId, "Downloading speech detector", it) }
                        TRANSLATION_PACK -> { publish(currentId, "Downloading ${OfflineTranslation.languages[currentId]} over Wi-Fi", 0); OfflineTranslation.download(currentId) }
                        DOWNLOAD -> downloadVideo(currentId)
                        PREVIEW -> preparePreview(currentId, previewStartMs)
                        REBUILD -> prepare(currentId, rebuildOnly = true)
                        PREPARE -> {
                            activeChargingOnly = enforceCharging && (app.jobs.get(currentId)?.chargingOnly == true)
                            checkChargingIfRequired()
                            prepare(currentId, rebuildOnly = false)
                            val visited = mutableSetOf(currentId)
                            while (currentCoroutineContext().isActive) {
                                val nextQueued = app.jobs.queued().firstOrNull { it.id !in visited } ?: break
                                visited += nextQueued.id
                                currentId = nextQueued.id
                                activeChargingOnly = nextQueued.chargingOnly
                                if (!ResourcePolicy.canRunQueued(nextQueued.chargingOnly, isDeviceCharging())) {
                                    app.jobs.state(currentId, "paused", "Queued film paused: waiting for charger (charging-only mode).")
                                    result = "Paused: waiting for charger."
                                    continue
                                }
                                checkThermal()
                                try {
                                    prepare(currentId, rebuildOnly = false)
                                } catch (itemError: Exception) {
                                    if (itemError is CancellationException || itemError is PausedException || itemError is TimeoutCancellationException) throw itemError
                                    val failureState = classifyFailureState(itemError)
                                    val msg = itemError.message ?: "Could not finish this queued film."
                                    app.jobs.state(currentId, failureState, msg)
                                    if (failureState == "low_storage") throw itemError
                                }
                            }
                        }
                        else -> error("Unknown task.")
                    }
                }
            } catch (e: Exception) {
                result = if (e is TimeoutCancellationException) "This long-running session was paused. Tap Resume to continue."
                    else e.message ?: "Could not finish. Check the file, offline packs, and available storage."
                withContext(NonCancellable) {
                    if (mode == PREVIEW) {
                        val existing = app.jobs.get(currentId)
                        app.jobs.previewState(currentId, if (e is CancellationException || e is PausedException) "paused" else "failed", result, existing?.previewAudioPath, previewStartMs)
                    } else if (mode == REBUILD) {
                        val existing = app.jobs.get(currentId)
                        val hasPreviousDub = existing?.audioPath?.let { File(it).exists() } == true
                        if (hasPreviousDub) {
                            app.jobs.state(currentId, "ready", "Update did not finish ($result). Previous playable English dub is still ready.")
                        } else {
                            app.jobs.state(currentId, classifyFailureState(e), result)
                        }
                    } else if (mode in setOf(PREPARE, DOWNLOAD)) {
                        val existing = app.jobs.get(currentId)
                        val hasPreviousDub = mode == PREPARE && existing?.audioPath?.let { File(it).exists() } == true
                        val failureState = if (hasPreviousDub) "ready" else classifyFailureState(e)
                        val msg = if (hasPreviousDub) "Update did not finish ($result). Previous playable English dub is still ready." else result
                        app.jobs.state(currentId, failureState, msg)
                    }
                }
            } finally {
                whisper = null
                activeChargingOnly = false
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

    private fun classifyFailureState(e: Exception): String {
        if (e is CancellationException || e is PausedException) return "paused"
        val msg = e.message.orEmpty().lowercase()
        return when {
            "storage" in msg || "free space" in msg || "free at least" in msg -> "low_storage"
            "pack" in msg || "offline setup" in msg || "offline english voice" in msg -> "missing_pack"
            "no longer accessible" in msg || "removed" in msg || "permission" in msg || "no readable" in msg -> "missing_file"
            else -> "failed"
        }
    }

    private suspend fun autoSetup(id: String): String {
        publish(id, "Inspecting phone hardware…", 5)
        val profile = HardwareProfile.detect(this)
        val allJobs = app.jobs.all()
        val installedTrans = runCatching { OfflineTranslation.installed() }.getOrDefault(emptySet())
        val plan = HardwareProfile.buildSetupPlan(
            profile = profile,
            speechReady = app.models.ready,
            vadReady = app.models.vadReady,
            installedTranslations = installedTrans,
            jobs = allJobs,
        )
        val thermalSevere = Build.VERSION.SDK_INT >= 29 &&
            getSystemService(PowerManager::class.java).currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
        val admission = HardwareProfile.checkAdmission(
            freeStorageBytes = profile.freeStorageBytes,
            requiredDownloadBytes = plan.requiredDownloadBytes,
            batteryPercent = readBatteryPercent(),
            isCharging = isDeviceCharging(),
            thermalSevere = thermalSevere,
        )
        require(admission.allowed) { admission.reason ?: "Could not start auto-setup." }

        val prefs = getSharedPreferences("preparation", MODE_PRIVATE)
        val bestVoice = runCatching { OfflineVoice.availableVoices(this).firstOrNull()?.name }.getOrNull()
        publish(id, "Tuning ${profile.tier.label} settings (${profile.recommendedThreads} threads)…", 10)

        if (plan.downloadVadPack && !app.models.vadReady) {
            checkEnvironment()
            app.models.downloadVad { pct ->
                publish(id, "Downloading speech detector · $pct%", 10 + pct * 15 / 100)
            }
        }
        if (plan.downloadSpeechPack && !app.models.ready) {
            checkEnvironment()
            app.models.download { pct ->
                publish(id, "Downloading speech pack (57 MB) · $pct%", 25 + pct * 60 / 100)
            }
        }
        for ((index, code) in plan.translationPacksToDownload.withIndex()) {
            checkEnvironment()
            val langName = OfflineTranslation.languages[code] ?: code
            val baseProgress = 85 + (index * 10 / plan.translationPacksToDownload.size.coerceAtLeast(1))
            publish(id, "Downloading $langName translation pack…", baseProgress)
            runCatching { withTimeout(25_000) { OfflineTranslation.download(code) } }
        }

        HardwareProfile.applyOptimalSettings(
            prefs = prefs,
            profile = profile,
            vadVerified = app.models.vadReady,
            selectedVoiceName = bestVoice,
        )

        val updatedTrans = runCatching { OfflineTranslation.installed() }.getOrDefault(installedTrans)
        app.jobs.all().filter { it.state == "missing_pack" }.forEach { job ->
            val packReady = when {
                job.mode == "speech" -> app.models.ready
                job.sourceLanguage == "en" -> true
                else -> job.sourceLanguage in updatedTrans
            }
            if (packReady) {
                app.jobs.state(job.id, "new", "Offline packs ready (${profile.tier.label}). Tap Prepare to dub.")
            }
        }

        val threadWord = if (profile.recommendedThreads == 1) "thread" else "threads"
        publish(id, "Auto-setup complete · ${profile.tier.label}", 100)
        return "Auto-setup complete (${profile.tier.label} · ${profile.recommendedThreads} $threadWord). Ready to watch and dub."
    }

    private suspend fun downloadVideo(id: String) {
        val job = app.jobs.get(id) ?: error("Video was removed.")
        app.jobs.state(id, "downloading", "Downloading video")
        val file = File(app.jobDirectory(id), "video")
        Downloads.fetch(job.mediaUri, file) { publish(id, "Downloading video · $it%", it, true) }
        app.jobs.get(id)?.let { app.jobs.save(it.copy(mediaUri = Uri.fromFile(file).toString(), state = "new", progress = 0, message = "Downloaded. Add subtitles or prepare a dub.")) }
    }

    private suspend fun preparePreview(id: String, requestedStartMs: Long) {
        val job = app.jobs.get(id) ?: error("Video was removed.")
        require(!job.mediaUri.startsWith("http")) { "Download the video before preparing a scene preview." }
        val media = Uri.parse(job.mediaUri)
        val duration = AudioTools.duration(this, media)
        require(duration > 0) { "Could not read video duration. Try a supported file format." }
        val startMs = requestedStartMs.coerceIn(0L, (duration - 1000L).coerceAtLeast(0L))
        val range = PreviewRange(startMs, 60_000L)
        val endMs = range.endMs(duration)
        val windowMs = (endMs - startMs).coerceAtLeast(1000L)
        app.jobs.previewState(id, "preparing", "Preparing ${windowMs / 1000}s scene preview…", job.previewAudioPath, startMs)
        publish(id, "Preparing scene preview · ${Subtitles.formatClock(startMs)}", 10)
        if (job.mode == "speech") require(app.models.ready) { "Download the optional speech pack in Offline setup first." }
        else if (job.sourceLanguage != "en") require(job.sourceLanguage in OfflineTranslation.installed()) { "Download the ${OfflineTranslation.languages[job.sourceLanguage]} translation pack in Offline setup first." }
        val directory = app.jobDirectory(id)
        require(directory.usableSpace > 24 * 1024 * 1024) { "Free at least 24 MB of storage and retry." }
        val prefs = getSharedPreferences("preparation", MODE_PRIVATE)
        val startedAt = SystemClock.elapsedRealtime()
        val rawCues: List<SavedCue> = if (job.mode == "speech") {
            val useVad = app.models.vadReady && prefs.getBoolean("vad", false)
            val threads = HardwareProfile.resolveInferenceThreads(this, prefs)
            val engine = WhisperBridge(app.models.speechFile.path, threads, if (useVad) app.models.vadFile.path else null)
            whisper = engine
            val recognizedCues = mutableListOf<SavedCue>()
            try {
                AudioTools.decodeChunks(this, media, startMs, endMs, job.audioTrackIndex) { chunkStart, buffer, count, rms ->
                    checkEnvironment()
                    publish(id, "Previewing dialogue · ${Subtitles.formatClock(chunkStart)}", 30)
                    val recognized = if (rms < 0.002) emptyList() else engine.translate(buffer, count, job.sourceLanguage)
                    currentCoroutineContext().ensureActive()
                    val next = minOf(endMs, chunkStart + count * 1000L / AudioTools.RATE)
                    recognized.map { it.copy(startMs = chunkStart + it.startMs, endMs = minOf(next, chunkStart + it.endMs)) }
                        .filter { it.endMs > it.startMs }
                        .forEach { cue ->
                            recognizedCues += SavedCue(id, recognizedCues.size, cue.startMs, cue.endMs, "", english = cue.text, transcriptAvailable = false)
                        }
                }
            } finally { whisper = null; engine.close() }
            recognizedCues
        } else {
            val subtitleText = job.subtitleUri?.let { readSubtitles(Uri.parse(it)) }
            val parsed = subtitleText?.let { Subtitles.parse(it) }
                ?: AudioTools.embeddedSubtitles(this, media, job.sourceLanguage, job.subtitleTrackIndex)
            parsed.mapIndexed { index, cue -> SavedCue(id, index, cue.startMs, cue.endMs, cue.text, transcriptAvailable = true) }
                .filter { it.effectiveEndMs(job.subtitleOffsetMs) > startMs && it.effectiveStartMs(job.subtitleOffsetMs) < endMs }
        }
        require(rawCues.isNotEmpty()) { "No dialogue found in this ${windowMs / 1000}-second scene (${Subtitles.formatClock(startMs)}–${Subtitles.formatClock(endMs)}). Try another starting position." }
        val pronunciations = app.jobs.pronunciations(id)
        val preservedRanges = app.jobs.preservedRanges(id)
        val translator = if (job.mode != "speech" && job.sourceLanguage != "en") OfflineTranslation(job.sourceLanguage) else null
        val placedPreviewCues: List<SavedCue>
        val shiftedForPreview: List<SavedCue>
        try {
            OfflineVoice.create(this, job.voiceName).use { voice ->
                val cache = CueAudioCache.reusable(app.speechCacheDir, voice.identity, HardwareProfile.resolveSpeechCacheBytes(this, prefs))
                val pass = DialoguePipeline.synthesizeAndPlaceCues(
                    cues = rawCues,
                    subtitleOffsetMs = job.subtitleOffsetMs,
                    pronunciations = pronunciations,
                    preservedRanges = preservedRanges,
                    cache = cache,
                    translator = translator?.let { t -> { text -> t.translate(text) } },
                    synthesize = { text, file, speed -> voice.synthesize(text, file, speed) },
                    existingDubExists = false,
                    initialPreviousEndMs = startMs,
                    sourceLanguage = job.sourceLanguage,
                    checkEnvironment = ::checkEnvironment,
                ) { i, _ ->
                    publish(id, "Generating preview voice · ${i + 1}/${rawCues.size}", 45 + (i + 1) * 40 / rawCues.size)
                }
                placedPreviewCues = pass.cues
                shiftedForPreview = pass.cues.map { cue ->
                    val s = (cue.spokenStartMs - startMs).coerceAtLeast(0L)
                    val e = (cue.spokenEndMs - startMs).coerceAtLeast(s)
                    cue.copy(spokenStartMs = s, spokenEndMs = e)
                }
            }
        } finally { translator?.close() }
        val previewPartial = File(directory, "preview.partial.m4a")
        val previewOut = File(directory, "preview.m4a")
        DialoguePipeline.commitTimeline(shiftedForPreview, windowMs, previewPartial, previewOut) { timelineCues, dur, out ->
            AacTimeline.encode(timelineCues, dur, out) { checkEnvironment(); publish(id, "Saving scene preview", 85 + it * 15 / 100) }
        }
        PreviewCueStore.save(directory, placedPreviewCues)
        val elapsedMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(500L)
        val estimatedTotalMs = ResourcePolicy.estimateFullPreparationMs(elapsedMs, windowMs, duration)
        val estimateText = ResourcePolicy.formatEstimateLabel(estimatedTotalMs)
        app.jobs.previewState(id, "ready", "Scene preview ready (${Subtitles.formatClock(startMs)}–${Subtitles.formatClock(endMs)}) · $estimateText", previewOut.path, startMs)
    }

    private suspend fun prepare(id: String, rebuildOnly: Boolean) {
        var job = app.jobs.get(id) ?: error("Video was removed.")
        require(!job.mediaUri.startsWith("http")) { "Download the video before preparing its dub." }
        val media = Uri.parse(job.mediaUri)
        val previousAudioPath = job.audioPath?.takeIf { File(it).exists() }
        app.jobs.state(id, "preparing", if (rebuildOnly) "Updating edited dialogue" else "Checking available audio")
        if (!rebuildOnly && (job.mode == "english_audio" || (!job.overrideRecommendation && job.audioTrackIndex == -1 && AudioTools.hasEnglishAudio(this, media)))) {
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
        val prefs = getSharedPreferences("preparation", MODE_PRIVATE)
        val useVad = job.mode == "speech" && app.models.vadReady && prefs.getBoolean("vad", false)
        val recSig = if (job.mode == "speech") {
            PreparationIdentity.recognition(source, job.audioTrackIndex, job.sourceLanguage, ModelStore.SHA256, if (useVad) ModelStore.VAD_SHA256 else "no-vad")
        } else {
            PreparationIdentity.digest(source, subtitleText.orEmpty(), job.subtitleTrackIndex.toString(), job.mode, PreparationIdentity.PIPELINE)
        }
        val transSig = PreparationIdentity.translation(
            recSig,
            job.subtitleTrackIndex,
            job.sourceLanguage,
            job.mode,
            if (job.mode == "speech") ModelStore.SHA256 else "mlkit-17.0.3",
        )
        val combinedSignature = PreparationIdentity.digest(recSig, transSig)
        if (!rebuildOnly && job.preparationSignature != null && job.preparationSignature != combinedSignature && job.recognitionSignature != recSig) {
            clearScratch(directory)
            app.database.withTransaction {
                app.jobs.clearCues(id)
                job = job.copy(
                    preparationSignature = combinedSignature,
                    recognitionSignature = recSig,
                    translationSignature = transSig,
                    voiceSignature = null,
                    timelineSignature = null,
                    decodedUntilMs = 0,
                    durationMs = duration,
                    state = "preparing",
                )
                app.jobs.save(job)
            }
        } else if (!rebuildOnly && job.recognitionSignature == recSig && job.translationSignature != null && job.translationSignature != transSig) {
            app.database.withTransaction {
                app.jobs.invalidateTranslation(id)
                job = job.copy(
                    preparationSignature = combinedSignature,
                    recognitionSignature = recSig,
                    translationSignature = transSig,
                    timelineSignature = null,
                    durationMs = duration,
                    state = "preparing",
                )
                app.jobs.save(job)
            }
        } else if (job.recognitionSignature != recSig && !rebuildOnly && app.jobs.cues(id).isEmpty()) {
            job = job.copy(
                preparationSignature = combinedSignature,
                recognitionSignature = recSig,
                translationSignature = transSig,
                durationMs = duration,
                state = "preparing",
            )
            app.jobs.save(job)
        } else if (job.durationMs != duration || job.preparationSignature == null || job.recognitionSignature == null || job.translationSignature == null) {
            job = job.copy(
                preparationSignature = combinedSignature,
                recognitionSignature = recSig,
                translationSignature = transSig,
                durationMs = duration,
                state = "preparing",
            )
            app.jobs.save(job)
        }
        val startedAt = SystemClock.elapsedRealtime()
        var cues = app.jobs.cues(id)
        if (!rebuildOnly && job.mode == "speech" && job.decodedUntilMs < duration - 100) {
            val threads = HardwareProfile.resolveInferenceThreads(this, prefs)
            val engine = WhisperBridge(app.models.speechFile.path, threads, if (useVad) app.models.vadFile.path else null)
            whisper = engine
            try {
                AudioTools.decodeChunks(this, media, job.decodedUntilMs, Long.MAX_VALUE, job.audioTrackIndex) { start, buffer, count, rms ->
                    checkEnvironment()
                    val elapsed = SystemClock.elapsedRealtime() - startedAt
                    val est = if (start > 0) " · ${ResourcePolicy.formatEstimateLabel(ResourcePolicy.estimateFullPreparationMs(elapsed, start, duration))}" else ""
                    publish(id, "Translating dialogue · ${start / 60000}/${duration / 60000} min$est", (start * 45 / duration).toInt(), true)
                    val recognized = if (rms < 0.002) emptyList() else engine.translate(buffer, count, job.sourceLanguage)
                    currentCoroutineContext().ensureActive()
                    val next = minOf(duration, start + count * 1000L / AudioTools.RATE)
                    val valid = recognized.map { it.copy(startMs = start + it.startMs, endMs = minOf(next, start + it.endMs)) }.filter { it.endMs > it.startMs }
                    val saved = valid.mapIndexed { index, cue ->
                        SavedCue(id, cues.size + index, cue.startMs, cue.endMs, "", english = cue.text, transcriptAvailable = false)
                    }
                    app.database.withTransaction { app.jobs.saveCues(saved); app.jobs.decoded(id, next) }
                    cues = cues + saved
                }
            } finally { whisper = null; engine.close() }
        } else if (job.mode != "speech" && cues.isEmpty()) {
            val parsed = subtitleText?.let { Subtitles.parse(it) }
                ?: AudioTools.embeddedSubtitles(this, media, job.sourceLanguage, job.subtitleTrackIndex)
            cues = parsed.mapIndexed { index, cue -> SavedCue(id, index, cue.startMs, cue.endMs, cue.text, transcriptAvailable = true) }
            app.jobs.saveCues(cues)
        }
        require(cues.isNotEmpty()) { "No dialogue was found. Add readable subtitles or try clearer audio." }
        val estimate = ResourcePolicy.scratchEstimate(cues.map { TextCue(it.startMs, it.endMs, it.effectiveEnglish()) }, duration)
        val existingPcm = cues.mapNotNull { it.pcmPath }.distinct().map(::File).filter { it.exists() }.sumOf { it.length() }
        require(directory.usableSpace > (estimate - existingPcm).coerceAtLeast(16L * 1024 * 1024)) { "Preparation needs about ${(estimate - existingPcm).coerceAtLeast(0) / 1024 / 1024} MB more free storage. Free space and resume." }
        val pronunciations = app.jobs.pronunciations(id)
        val preservedRanges = app.jobs.preservedRanges(id)
        val translator = if (job.mode != "speech" && job.sourceLanguage != "en") OfflineTranslation(job.sourceLanguage) else null
        var warnings = 0
        var regeneratedLegacy = false
        var voiceId = ""
        try {
            OfflineVoice.create(this, job.voiceName).use { voice ->
                voiceId = voice.identity
                val current = app.jobs.get(id)!!
                if (current.voiceSignature != voice.identity) {
                    if (previousAudioPath == null) {
                        app.jobs.invalidateVoice(id)
                        cues = app.jobs.cues(id)
                    } else {
                        cues = cues.map { it.copy(pcmPath = null, rate = 0, spokenStartMs = 0, spokenEndMs = 0) }
                    }
                }
                val cache = CueAudioCache.reusable(app.speechCacheDir, voice.identity, HardwareProfile.resolveSpeechCacheBytes(this, prefs))
                val pass = DialoguePipeline.synthesizeAndPlaceCues(
                    cues = cues,
                    subtitleOffsetMs = job.subtitleOffsetMs,
                    pronunciations = pronunciations,
                    preservedRanges = preservedRanges,
                    cache = cache,
                    translator = translator?.let { t -> { text -> t.translate(text) } },
                    synthesize = { text, file, speed -> voice.synthesize(text, file, speed) },
                    existingDubExists = previousAudioPath != null,
                    sourceLanguage = job.sourceLanguage,
                    checkEnvironment = ::checkEnvironment,
                ) { index, updatedCue ->
                    if (previousAudioPath == null) app.jobs.saveCue(updatedCue)
                    publish(id, "Generating English voice · ${index + 1}/${cues.size}", 45 + (index + 1) * 40 / cues.size, true)
                }
                cues = pass.cues
                warnings = pass.warnings
                regeneratedLegacy = pass.regeneratedLegacyClips
            }
        } finally { translator?.close() }
        val partial = File(directory, "english.partial.m4a")
        val completed = File(directory, "english.m4a")
        DialoguePipeline.commitTimeline(cues, duration, partial, completed) { timelineCues, dur, out ->
            AacTimeline.encode(timelineCues, dur, out) { checkEnvironment(); publish(id, "Saving English dub", 85 + it * 15 / 100, true) }
        }
        val timelineSig = PreparationIdentity.timeline(cues, job.subtitleOffsetMs, pronunciations, preservedRanges, voiceId, duration)
        val legacyNote = if (regeneratedLegacy) " Missing reusable speech clips from an earlier dub were regenerated." else ""
        val readyMessage = if (warnings == 0) {
            "English dub ready. Works offline.$legacyNote"
        } else {
            "English dub ready. $warnings lines exceed their original timing; complete speech is preserved.$legacyNote"
        }
        app.database.withTransaction {
            app.jobs.saveCues(cues)
            app.jobs.get(id)?.let {
                app.jobs.save(it.copy(
                    state = "ready",
                    progress = 100,
                    audioPath = completed.path,
                    voiceSignature = voiceId,
                    timelineSignature = timelineSig,
                    message = readyMessage,
                ))
            }
        }
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

    private fun readBatteryPercent(): Int {
        val battery = getSystemService(BatteryManager::class.java)
        val capacity = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        if (capacity in 1..100) return capacity
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return 100
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else 100
    }

    private fun isDeviceCharging(): Boolean {
        val battery = getSystemService(BatteryManager::class.java)
        if (battery != null && battery.isCharging) return true
        val status = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun checkChargingIfRequired() {
        if (activeChargingOnly && !isDeviceCharging()) {
            throw PausedException("Paused: unplugged under charging-only mode. Plug in or override charging-only, then tap Resume.")
        }
    }

    private fun checkThermal() {
        if (Build.VERSION.SDK_INT >= 29 && getSystemService(PowerManager::class.java).currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
            throw PausedException("The phone is too warm. Let it cool, then tap Resume.")
        }
    }

    private fun checkEnvironment() {
        checkThermal()
        checkChargingIfRequired()
    }

    private fun notification(message: String, progress: Int): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pause = PendingIntent.getService(this, 1, Intent(this, PreparationService::class.java).setAction(PAUSE), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_app).setContentTitle("EnglishDub").setContentText(message)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setProgress(100, progress, progress == 0).addAction(0, "Pause", pause).build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        cancel("Android paused this long task. Tap Resume to continue.")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(startId)
    }

    override fun onDestroy() {
        cancel("Preparation interrupted. Tap Resume to continue."); scope.cancel()
        if (Build.VERSION.SDK_INT >= 29) thermalListener?.let { getSystemService(PowerManager::class.java).removeThermalStatusListener(it) }
        try { unregisterReceiver(powerReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private class PausedException(message: String) : Exception(message)

    companion object {
        const val PREPARE = "prepare"
        const val PREVIEW = "preview"
        const val REBUILD = "rebuild"
        const val DOWNLOAD = "download"
        const val AUTO_SETUP = "auto-setup"
        const val SPEECH_PACK = "speech-pack"
        const val VAD_PACK = "vad-pack"
        const val TRANSLATION_PACK = "translation-pack"
        const val PAUSE = "pause"
        private const val CHANNEL = "preparation"
        private const val NOTIFICATION = 40
        val status get() = PreparationCoordinator.status

        fun start(context: Context, action: String, id: String, previewStartMs: Long = 0L, enforceCharging: Boolean = false) {
            val ticket = PreparationCoordinator.reserve(id)
            try {
                val intent = Intent(context, PreparationService::class.java)
                    .setAction(action)
                    .putExtra("id", id)
                    .putExtra("ticket", ticket)
                    .putExtra("previewStartMs", previewStartMs)
                    .putExtra("enforceCharging", enforceCharging)
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                PreparationCoordinator.finish(ticket, e.message.orEmpty())
                throw e
            }
        }

        fun pause(context: Context) {
            if (status.value.busy) context.startService(Intent(context, PreparationService::class.java).setAction(PAUSE))
        }
    }
}

