package app.englishdub.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.englishdub.DubApplication
import app.englishdub.data.DubJob
import app.englishdub.data.PreservedRange
import app.englishdub.data.PronunciationEntry
import app.englishdub.data.SavedCue
import app.englishdub.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import java.io.File
import java.net.URI
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val app get() = application as DubApplication
    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView
    private var jobs = emptyList<DubJob>()
    private var offline = false
    private var subtitleFor: String? = null
    private var exportJobId: String? = null
    private var installed = emptySet<String>()
    private var voices = emptyList<VoiceOption>()
    private var packWarning = ""
    private var lastBusy = false
    private var storage = StorageBreakdown()
    private lateinit var library: RecyclerView
    private lateinit var libraryAdapter: LibraryAdapter
    private lateinit var libraryActions: LinearLayout
    private lateinit var libraryTabButton: Button
    private lateinit var offlineTabButton: Button
    private lateinit var statusCard: LinearLayout
    private lateinit var statusLabel: TextView
    private lateinit var statusProgress: ProgressBar
    private lateinit var pauseButton: Button

    private val videoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { persist(it); lifecycleScope.launch { guarded {
            val job = DubJob(UUID.randomUUID().toString(), fileName(it), it.toString())
            app.jobs.save(job)
        } } }
    }
    private val subtitlePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = subtitleFor
        if (uri != null && id != null) {
            persist(uri)
            lifecycleScope.launch { guarded {
                checkIdle()
                app.jobs.get(id)?.let { job ->
                    app.jobs.clearCues(id)
                    app.jobs.save(job.copy(
                        subtitleUri = uri.toString(),
                        mode = "subtitles",
                        state = "new",
                        progress = 0,
                        decodedUntilMs = 0,
                        recognitionSignature = null,
                        translationSignature = null,
                        message = "Subtitles added. Choose track, offset, or preview with Prepare.",
                    ))
                }
            } }
        }
    }
    private val srtExportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        val id = exportJobId
        if (uri != null && id != null) {
            lifecycleScope.launch { guarded {
                val job = app.jobs.get(id) ?: error("Film was removed.")
                val cues = app.jobs.cues(id)
                withContext(Dispatchers.IO) {
                    val out = contentResolver.openOutputStream(uri) ?: error("Could not open destination file.")
                    Subtitles.exportSrt(cues, job.subtitleOffsetMs, out)
                }
                toast("Exported corrected English subtitles (UTF-8 SRT).")
            } }
        }
    }
    private val m4aExportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/mp4")) { uri ->
        val id = exportJobId
        if (uri != null && id != null) {
            lifecycleScope.launch { guarded {
                val job = app.jobs.get(id) ?: error("Film was removed.")
                val audioFile = job.audioPath?.let(::File)?.takeIf { it.exists() }
                    ?: error("Prepared English voice track is not available.")
                withContext(Dispatchers.IO) {
                    val out = contentResolver.openOutputStream(uri) ?: error("Could not open destination file.")
                    Subtitles.exportVoiceM4a(audioFile, out)
                }
                toast("Exported generated English voice track (M4A, without original soundtrack).")
            } }
        }
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        subtitleFor = savedInstanceState?.getString("subtitleFor")
        exportJobId = savedInstanceState?.getString("exportJobId")
        offline = savedInstanceState?.getBoolean("offline") ?: false
        root = column().apply { setPadding(0, 0, 0, dp(20)) }
        scroll = ScrollView(this).apply { isFillViewport = true; addView(root); visibility = View.GONE }
        libraryAdapter = LibraryAdapter(::libraryAction)
        library = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = libraryAdapter
            itemAnimator = null
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(16))
        }
        val shell = column().apply {
            setBackgroundColor(canvasColor)
            setPadding(dp(20), dp(10), dp(20), dp(8))
        }
        shell.addView(eyebrow("OFFLINE FILM DUBBING · v0.3"))
        shell.addView(label("EnglishDub", 28f, ink, true))

        val tabs = row().apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(cardColor)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), borderSubtle)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(6)
            }
        }
        libraryTabButton = button("Library") { offline = false; render() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(2) }
        }
        offlineTabButton = button("Offline setup") {
            offline = true; render()
            lifecycleScope.launch { refreshPacks(); if (offline) render() }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(2) }
        }
        tabs.addView(libraryTabButton)
        tabs.addView(offlineTabButton)
        shell.addView(tabs)

        statusLabel = label("", 14f, mint, true)
        statusProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            progressTintList = android.content.res.ColorStateList.valueOf(mint)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(surfaceSubtle)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4); bottomMargin = dp(4) }
        }
        pauseButton = button("Pause task") { PreparationService.pause(this) }
        statusCard = card().apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4); bottomMargin = dp(4) }
            addView(statusLabel)
            addView(statusProgress)
            addView(pauseButton)
            visibility = View.GONE
        }
        shell.addView(statusCard)

        libraryActions = column().apply {
            val openBtn = button("＋ Open video", primary = true) { videoPicker.launch(arrayOf("video/*")) }.apply {
                contentDescription = "Open a video"
            }
            val linkBtn = button("Download URL") { linkDialog() }.apply {
                contentDescription = "Download a direct video link"
            }
            addTwoColumnRow(openBtn, linkBtn)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(4) }
        }
        shell.addView(libraryActions)

        val body = FrameLayout(this).apply { addView(library); addView(scroll) }
        shell.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(shell)
        ViewCompat.setOnApplyWindowInsetsListener(shell) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(20) + bars.left, dp(10) + bars.top, dp(20) + bars.right, dp(8) + bars.bottom)
            insets
        }
        lifecycleScope.launch {
            app.recovery.await()
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(app.jobs.observe(), PreparationService.status) { items, status -> items to status }.collect { (items, status) ->
                    jobs = items
                    updateStatus(status)
                    updateLibrary()
                    if (lastBusy && !status.busy && offline) { refreshPacks(); render() }
                    lastBusy = status.busy
                }
            }
        }
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("subtitleFor", subtitleFor)
        outState.putString("exportJobId", exportJobId)
        outState.putBoolean("offline", offline)
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        libraryTabButton.styleAsTab(!offline)
        offlineTabButton.styleAsTab(offline)
        scroll.visibility = if (offline) View.VISIBLE else View.GONE
        library.visibility = if (offline) View.GONE else View.VISIBLE
        libraryActions.visibility = if (offline) View.GONE else View.VISIBLE
        updateStatus(PreparationService.status.value)
        if (offline) {
            val y = scroll.scrollY
            root.removeAllViews(); renderOffline(); scroll.post { scroll.scrollTo(0, y) }
        } else updateLibrary()
    }

    private fun updateStatus(status: PreparationStatus) {
        val text = if (status.busy) status.message else status.message.takeUnless { it == "Completed" }.orEmpty()
        if (statusLabel.text.toString() != text) statusLabel.text = text
        statusLabel.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        statusProgress.visibility = if (status.busy) View.VISIBLE else View.GONE
        statusProgress.progress = status.progress
        pauseButton.visibility = if (status.busy) View.VISIBLE else View.GONE
        statusCard.visibility = if (text.isNotBlank() || status.busy) View.VISIBLE else View.GONE
    }

    private fun updateLibrary() {
        val busy = PreparationService.status.value.busy
        val rows = if (jobs.isEmpty()) listOf(LibraryRow(null, busy)) else jobs.map { LibraryRow(it, busy) }
        libraryAdapter.submitList(rows)
    }

    private fun libraryAction(action: String, job: DubJob) {
        if (!checkIdleUi()) return
        when (action) {
            "watch" -> startActivity(Intent(this, PlayerActivity::class.java).putExtra("id", job.id))
            "watch_preview" -> startActivity(Intent(this, PlayerActivity::class.java).putExtra("id", job.id).putExtra("preview", true))
            "resume" -> startTask(
                PreparationService.PREPARE,
                job.id,
                enforceCharging = (job.state == "queued" || job.queueOrder > 0) && job.chargingOnly,
            )
            "prepare" -> prepareDialog(job)
            "preview" -> previewDialog(job)
            "review" -> reviewDialog(job)
            "queue" -> queueDialog(job)
            "export" -> exportDialog(job)
            "subtitles" -> { subtitleFor = job.id; subtitlePicker.launch(arrayOf("*/*")) }
            "download" -> startTask(PreparationService.DOWNLOAD, job.id)
            "remove" -> AlertDialog.Builder(this).setTitle("Remove this video?")
                .setMessage("Remove its saved dub, preview, preparation files, and any copy downloaded by EnglishDub. Your original local file stays on your phone.")
                .setNegativeButton("Keep", null).setPositiveButton("Remove") { _, _ -> lifecycleScope.launch { guarded {
                    checkIdle(); withContext(Dispatchers.IO) { app.jobDirectory(job.id).deleteRecursively() }; app.jobs.delete(job.id)
                } } }.show()
        }
    }

    private fun renderOffline() {
        if (packWarning.isNotBlank()) root.addView(label(packWarning, 13f, amber))
        root.addView(card().apply {
            addView(eyebrow("REQUIRED · OFFLINE SPEECH"))
            addView(label("English voice", 20f, ink, true))
            addView(label("Select an installed offline English voice and listen to a short sample before dubbing.", 14f, muted))
            val checkBtn = button("Verify voice", primary = true) { lifecycleScope.launch { guarded {
                checkIdle(); toast("Checking offline speech generation…")
                withContext(Dispatchers.IO) { OfflineVoice.verify(this@MainActivity) }
                voices = withContext(Dispatchers.IO) { runCatching { OfflineVoice.availableVoices(this@MainActivity) }.getOrDefault(emptyList()) }
                toast("Offline English voice generated audio successfully.")
                if (offline) render()
            } } }.apply { contentDescription = "Check offline English voice" }
            val sampleBtn = button("Play sample") { lifecycleScope.launch { guarded {
                checkIdle()
                val preferred = getSharedPreferences("preparation", MODE_PRIVATE).getString("voiceName", null)
                OfflineVoice.playSample(this@MainActivity, preferred)
            } } }.apply { contentDescription = "Play short voice sample" }
            addTwoColumnRow(checkBtn, sampleBtn)
            if (voices.isNotEmpty()) {
                addView(divider())
                addView(label("Preferred offline voice (${voices.size} available):", 13f, muted))
                val prefVoice = getSharedPreferences("preparation", MODE_PRIVATE).getString("voiceName", null)
                val voiceLabels = voices.map { it.label }.toTypedArray()
                val initialIdx = voices.indexOfFirst { it.name == prefVoice }.coerceAtLeast(0)
                var userTouched = false
                addView(styledSpinner(voiceLabels, initialIdx) { pos ->
                    val chosen = voices[pos]
                    getSharedPreferences("preparation", MODE_PRIVATE).edit().putString("voiceName", chosen.name).apply()
                    if (userTouched) {
                        lifecycleScope.launch { guarded { OfflineVoice.playSample(this@MainActivity, chosen.name) } }
                    }
                    userTouched = true
                })
            }
            addView(button("Open Android voice settings") {
                try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                catch (_: Exception) { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)); toast("Search Settings for Text-to-speech.") }
            })
        })
        root.addView(card().apply {
            addView(eyebrow("OPTIONAL · SUBTITLE TRANSLATION", amber))
            addView(label("Subtitle translation packs", 20f, ink, true))
            addView(label("English subtitles need no translation pack. Hindi, Urdu, and Tamil packs are ~30 MB each (Wi-Fi); translation quality is experimental pending bilingual review.", 14f, muted))
            OfflineTranslation.languages.filterKeys { it != "en" }.forEach { (code, name) ->
                val isInstalled = code in installed
                addView(button(
                    if (isInstalled) "✓ $name installed · Remove" else "Download $name (~30 MB)",
                    destructive = isInstalled,
                ) {
                    if (isInstalled) lifecycleScope.launch { guarded { checkIdle(); OfflineTranslation.delete(code); refreshPacks(); render() } }
                    else startTask(PreparationService.TRANSLATION_PACK, code)
                })
            }
        })
        root.addView(card().apply {
            addView(eyebrow("OPTIONAL · NO SUBTITLES", amber))
            addView(label("Dialogue recognition", 20f, ink, true))
            addView(label("57 MB download for films without subtitles. Translates Hindi, Urdu, or Tamil speech into English on your phone. Language quality is experimental.", 14f, muted))
            val ready = app.models.ready
            addView(button(
                if (ready) "✓ Speech pack installed · Remove" else "Download speech pack (57 MB)",
                primary = !ready,
                destructive = ready,
            ) {
                if (ready) { if (checkIdleUi()) { app.models.delete(); lifecycleScope.launch { refreshPacks(); render() } } }
                else AlertDialog.Builder(this@MainActivity).setTitle("Download speech pack?").setMessage("Download 57 MB using your current internet connection? After setup, speech processing works offline.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Download") { _, _ -> startTask(PreparationService.SPEECH_PACK, "speech-pack") }.show()
            })
        })
        root.addView(card().apply {
            addView(eyebrow("OPTIONAL · VOICE ACTIVITY", amber))
            addView(label("Speech detector", 20f, ink, true))
            addView(label("Optional 0.9 MB detector. Skips non-dialogue segments, but remains off by default until Hindi, Urdu, and Tamil quality is verified.", 14f, muted))
            val vadReady = app.models.vadReady
            addView(button(
                if (vadReady) "✓ Detector installed · Remove" else "Download speech detector (0.9 MB)",
                destructive = vadReady,
            ) {
                if (vadReady) { if (checkIdleUi()) { app.models.deleteVad(); lifecycleScope.launch { refreshPacks(); render() } } }
                else startTask(PreparationService.VAD_PACK, "vad-pack")
            })
            val settings = getSharedPreferences("preparation", MODE_PRIVATE)
            addView(Switch(this@MainActivity).apply {
                text = "Use experimental speech detection"; setTextColor(ink); minHeight = dp(48)
                isEnabled = app.models.vadReady && !PreparationService.status.value.busy
                isChecked = settings.getBoolean("vad", false)
                setOnCheckedChangeListener { _, checked -> settings.edit().putBoolean("vad", checked).apply() }
            })
            if (app.models.hasPartial) addView(button("Delete incomplete speech download", destructive = true) { if (checkIdleUi()) { app.models.deletePartial(); render() } })
        })
        root.addView(card().apply {
            addView(eyebrow("STORAGE & CACHE"))
            addView(label("Keep storage light", 20f, ink, true))
            val mb = { b: Long -> "%.1f MB".format(java.util.Locale.US, b / (1024.0 * 1024.0)) }
            addView(label(
                "Total app storage: ${mb(storage.totalBytes)}\n" +
                    "• Scene previews: ${mb(storage.previewsBytes)}\n" +
                    "• Reusable speech clips (≤256 MiB LRU): ${mb(storage.speechCacheBytes)}\n" +
                    "• Completed English dubs: ${mb(storage.completedDubsBytes)}\n" +
                    "• App-owned downloads & packs: ${mb(storage.downloadsBytes)}\n" +
                    "Your original local video files are never modified or deleted.",
                14f, muted
            ))
            val clearPreviewsBtn = button("Clear previews") { lifecycleScope.launch { guarded {
                checkIdle()
                withContext(Dispatchers.IO) { ResourcePolicy.clearPreviews(app.filesDir) }
                jobs.forEach { if (it.previewAudioPath != null) app.jobs.previewState(it.id, "none", "", null, it.previewStartMs) }
                refreshPacks(); toast("Scene previews removed."); render()
            } } }.apply { contentDescription = "Remove scene previews" }
            val clearCacheBtn = button("Clear speech cache") { lifecycleScope.launch { guarded {
                checkIdle()
                withContext(Dispatchers.IO) { ResourcePolicy.clearSpeechCache(app.filesDir, PreparationService.status.value.id) }
                refreshPacks(); toast("Reusable speech clips cleared. Saved dubs are kept."); render()
            } } }.apply { contentDescription = "Clear reusable speech clips" }
            addTwoColumnRow(clearPreviewsBtn, clearCacheBtn)

            val clearDubsBtn = button("Remove dub audio", destructive = true) { lifecycleScope.launch { guarded {
                checkIdle()
                withContext(Dispatchers.IO) { ResourcePolicy.clearCompletedDubs(app.filesDir, PreparationService.status.value.id) }
                jobs.filter { it.audioPath != null }.forEach {
                    app.jobs.save(it.copy(audioPath = null, state = "new", message = "Completed dub removed. Translations and cues are kept."))
                }
                refreshPacks(); toast("Completed dub files removed. User originals and translations are kept."); render()
            } } }.apply { contentDescription = "Remove completed dub audio files" }
            val clearDownloadsBtn = button("Remove downloads", destructive = true) { lifecycleScope.launch { guarded {
                checkIdle()
                val removedIds = withContext(Dispatchers.IO) { ResourcePolicy.clearAppDownloads(app.filesDir, PreparationService.status.value.id) }
                jobs.filter { it.id in removedIds }.forEach {
                    app.jobs.state(it.id, "missing_file", "App-owned downloaded video was removed. Open a local file or download again.")
                }
                refreshPacks(); toast("App-owned downloads and packs removed. User-owned local files remain intact."); render()
            } } }.apply { contentDescription = "Remove app-owned downloads and packs" }
            addTwoColumnRow(clearDubsBtn, clearDownloadsBtn)
        })
    }

    private fun prepareDialog(job: DubJob) {
        if (!checkIdleUi()) return
        lifecycleScope.launch { guarded {
            val media = Uri.parse(job.mediaUri)
            val tracks = withContext(Dispatchers.IO) {
                runCatching { AudioTools.inspectTracks(this@MainActivity, media) }.getOrDefault(emptyList())
            }
            val availableVoices = withContext(Dispatchers.IO) {
                runCatching { OfflineVoice.availableVoices(this@MainActivity) }.getOrDefault(voices)
            }.also { if (it.isNotEmpty()) voices = it }
            val existingCues = app.jobs.cues(job.id).map { TextCue(it.startMs, it.endMs, it.effectiveEnglish()) }
            val audioTracks = tracks.filter { it.kind == "audio" }
            val subTracks = tracks.filter { it.kind == "subtitles" }
            val rec = AudioTools.recommendSource(
                audioTracks = audioTracks,
                subtitleTracks = subTracks,
                hasExternalSubtitles = job.subtitleUri != null,
                externalSubtitleLanguage = job.sourceLanguage.takeIf { it in listOf("en", "hi", "ur", "ta") } ?: "en",
                preferredLanguage = job.sourceLanguage.takeIf { it in listOf("hi", "ur", "ta") } ?: "hi",
            )
            val panel = column().apply { setPadding(dp(20), dp(12), dp(20), dp(12)) }
            panel.addView(label(rec.summary, 14f, mint, true))
            val trackSummary = buildString {
                append("Audio tracks: ")
                if (audioTracks.isEmpty()) append("Default") else append(audioTracks.joinToString { "#${it.index} ${it.label} [${it.languageLabel}]" })
                append("\nSubtitle tracks: ")
                if (subTracks.isEmpty() && job.subtitleUri == null) append("None (use Speech Recognition or add SRT)")
                else {
                    if (job.subtitleUri != null) append("External SRT/WebVTT ")
                    append(subTracks.joinToString { "#${it.index} ${it.label} [${it.languageLabel}]" })
                }
            }
            panel.addView(label(trackSummary, 13f, muted))
            panel.addView(divider())
            var offsetMs = job.subtitleOffsetMs
            fun formatOffsetSummary(offset: Long): String {
                val previewSuffix = if (existingCues.isNotEmpty()) {
                    val shifted = Subtitles.applyOffset(existingCues.take(1), offset).first()
                    " · First cue: ${Subtitles.formatClock(shifted.startMs)} → ${Subtitles.formatClock(shifted.endMs)}"
                } else ""
                return "Subtitle timing offset: ${offset} ms$previewSuffix"
            }
            val offsetLabel = label(formatOffsetSummary(offsetMs), 13f, ink, true)
            panel.addView(offsetLabel)
            val earlierBtn = button("−250 ms (Earlier)") { offsetMs -= 250L; offsetLabel.text = formatOffsetSummary(offsetMs) }
            val laterBtn = button("+250 ms (Later)") { offsetMs += 250L; offsetLabel.text = formatOffsetSummary(offsetMs) }
            panel.addTwoColumnRow(earlierBtn, laterBtn)

            var selectedAudioTrack = if (job.audioTrackIndex >= 0) job.audioTrackIndex else rec.audioTrackIndex
            if (audioTracks.isNotEmpty()) {
                panel.addView(label("Audio track (never switches silently):", 13f, muted))
                val audioButtons = mutableListOf<Pair<MediaTrackInfo, Button>>()
                audioTracks.forEach { track ->
                    val btn = button("${if (selectedAudioTrack == track.index) "✓ " else ""}${track.label} · ${track.languageLabel}") {
                        selectedAudioTrack = track.index
                        audioButtons.forEach { (t, b) -> b.text = "${if (selectedAudioTrack == t.index) "✓ " else ""}${t.label} · ${t.languageLabel}" }
                    }
                    audioButtons += track to btn
                    panel.addView(btn)
                }
            }
            var selectedSubtitleTrack = if (job.subtitleTrackIndex >= 0) job.subtitleTrackIndex else rec.subtitleTrackIndex
            if (subTracks.isNotEmpty() && job.subtitleUri == null) {
                panel.addView(label("Embedded subtitle track:", 13f, muted))
                val subButtons = mutableListOf<Pair<MediaTrackInfo, Button>>()
                subTracks.forEach { track ->
                    val btn = button("${if (selectedSubtitleTrack == track.index) "✓ " else ""}${track.label} · ${track.languageLabel}") {
                        selectedSubtitleTrack = track.index
                        subButtons.forEach { (t, b) -> b.text = "${if (selectedSubtitleTrack == t.index) "✓ " else ""}${t.label} · ${t.languageLabel}" }
                    }
                    subButtons += track to btn
                    panel.addView(btn)
                }
            }
            panel.addView(divider())
            var selectedVoiceName = job.voiceName ?: getSharedPreferences("preparation", MODE_PRIVATE).getString("voiceName", null)
            if (availableVoices.isNotEmpty()) {
                panel.addView(label("Offline English voice for this film:", 13f, muted))
                val voiceNames = availableVoices.map { it.label }.toTypedArray()
                val initialVoiceIdx = availableVoices.indexOfFirst { it.name == selectedVoiceName }.coerceAtLeast(0)
                selectedVoiceName = availableVoices[initialVoiceIdx].name
                panel.addView(styledSpinner(voiceNames, initialVoiceIdx) { pos ->
                    selectedVoiceName = availableVoices[pos].name
                })
            }
            panel.addView(button("Play short voice sample") {
                lifecycleScope.launch { guarded { OfflineVoice.playSample(this@MainActivity, selectedVoiceName) } }
            })
            val names = arrayOf(
                "English subtitles · lightest",
                "Hindi subtitles",
                "Urdu subtitles",
                "Tamil subtitles",
                "Hindi dialogue · speech pack",
                "Urdu dialogue · speech pack",
                "Tamil dialogue · speech pack",
                "Existing English audio (no AI dub)",
            )
            var selection = when {
                job.overrideRecommendation && job.mode == "english_audio" -> 7
                job.overrideRecommendation && job.mode == "speech" -> 4 + listOf("hi", "ur", "ta").indexOf(job.sourceLanguage).coerceAtLeast(0)
                job.overrideRecommendation && job.mode == "subtitles" -> listOf("en", "hi", "ur", "ta").indexOf(job.sourceLanguage).coerceAtLeast(0)
                rec.path == RecommendedPath.ENGLISH_AUDIO -> 7
                rec.path == RecommendedPath.ENGLISH_SUBTITLES -> 0
                rec.path == RecommendedPath.TRANSLATED_SUBTITLES -> listOf("en", "hi", "ur", "ta").indexOf(rec.languageCode).coerceAtLeast(1)
                else -> 4 + listOf("hi", "ur", "ta").indexOf(rec.languageCode).coerceAtLeast(0)
            }
            panel.addView(label("Preparation mode & source language:", 13f, muted))
            panel.addView(styledSpinner(names, selection) { pos ->
                selection = pos
                if (pos in 0..3 && job.subtitleUri == null) {
                    val lang = listOf("en", "hi", "ur", "ta")[pos]
                    subTracks.firstOrNull { it.languageCode == lang }?.let { selectedSubtitleTrack = it.index }
                }
            })
            val dialogScroll = ScrollView(this@MainActivity).apply { addView(panel) }
            AlertDialog.Builder(this@MainActivity).setTitle("Choose source & prepare English")
                .setView(dialogScroll)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Preview 60s") { _, _ -> lifecycleScope.launch { guarded {
                    checkIdle()
                    saveSourceSelection(job, selection, rec, selectedAudioTrack, selectedSubtitleTrack, offsetMs, selectedVoiceName, state = job.state)
                    previewDialog(app.jobs.get(job.id) ?: job)
                } } }
                .setPositiveButton("Prepare") { _, _ -> lifecycleScope.launch { guarded {
                    checkIdle()
                    saveSourceSelection(job, selection, rec, selectedAudioTrack, selectedSubtitleTrack, offsetMs, selectedVoiceName, state = "new")
                    startTask(PreparationService.PREPARE, job.id)
                } } }.show()
        } }
    }

    private suspend fun saveSourceSelection(
        job: DubJob,
        selection: Int,
        rec: SourceRecommendation,
        selectedAudioTrack: Int,
        selectedSubtitleTrack: Int,
        offsetMs: Long,
        voiceName: String?,
        state: String,
    ) {
        val mode = when {
            selection == 7 -> "english_audio"
            selection >= 4 -> "speech"
            else -> "subtitles"
        }
        val code = when {
            selection == 7 -> "en"
            selection >= 4 -> listOf("hi", "ur", "ta")[selection - 4]
            else -> listOf("en", "hi", "ur", "ta")[selection]
        }
        val recMode = when (rec.path) {
            RecommendedPath.ENGLISH_AUDIO -> "english_audio"
            RecommendedPath.SPEECH_RECOGNITION -> "speech"
            else -> "subtitles"
        }
        val effectiveSubTrack = if (mode == "subtitles" && job.subtitleUri == null) selectedSubtitleTrack else -1
        val overrideRec = mode != recMode || code != rec.languageCode || selectedAudioTrack != rec.audioTrackIndex || effectiveSubTrack != rec.subtitleTrackIndex
        val request = PreparationRequest(
            jobId = job.id,
            mode = mode,
            sourceLanguage = code,
            audioTrackIndex = selectedAudioTrack,
            subtitleTrackIndex = effectiveSubTrack,
            subtitleOffsetMs = offsetMs,
            voiceName = voiceName,
            overrideRecommendation = overrideRec,
            chargingOnly = job.chargingOnly,
        )
        app.jobs.configureSource(
            id = request.jobId,
            mode = request.mode,
            language = request.sourceLanguage,
            audioTrack = request.audioTrackIndex,
            subtitleTrack = request.subtitleTrackIndex,
            offsetMs = request.subtitleOffsetMs,
            voiceName = request.voiceName,
            overrideRec = request.overrideRecommendation,
            chargingOnly = request.chargingOnly,
            state = state,
            message = "Ready to prepare (${request.sourceLanguage} · ${request.mode}).",
        )
    }

    private fun previewDialog(job: DubJob) {
        if (!checkIdleUi()) return
        val box = column().apply { setPadding(dp(20), dp(8), dp(20), dp(4)) }
        val input = inputField("Start time (e.g. 01:30 or 00:00)").apply {
            setText(Subtitles.formatClock(job.previewStartMs))
            setSingleLine()
        }
        box.addView(input)
        AlertDialog.Builder(this).setTitle("Preview a 60-second scene")
            .setMessage("Prepare a 60-second scene using your selected track, language, voice, and subtitle offset (${job.subtitleOffsetMs} ms). Preview state stays separate from the full movie, and generated translations/speech clips are reused later.")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Prepare 60s") { _, _ -> lifecycleScope.launch { guarded {
                checkIdle()
                val startMs = Subtitles.parseTimestampOrSeconds(input.text.toString())
                startTask(PreparationService.PREVIEW, job.id, previewStartMs = startMs)
            } } }.show()
    }

    private fun reviewDialog(job: DubJob) {
        if (!checkIdleUi()) return
        lifecycleScope.launch { guarded {
            val dbCues = app.jobs.cues(job.id)
            val allCues = if (dbCues.isNotEmpty()) dbCues else withContext(Dispatchers.IO) { PreviewCueStore.load(app.jobDirectory(job.id)) }
            val pronunciations = app.jobs.pronunciations(job.id)
            val preservedRanges = app.jobs.preservedRanges(job.id)
            val legacyMissing = job.audioPath != null && allCues.any {
                !it.subtitlesOnly && it.spokenEndMs > it.spokenStartMs && (it.pcmPath == null || !File(it.pcmPath).exists())
            }
            val container = column().apply { setPadding(dp(20), dp(10), dp(20), dp(10)) }
            if (legacyMissing) {
                container.addView(label("Note: This earlier dub has no cached per-line speech clips on disk. On your first edit, missing reusable speech clips will be regenerated without repeating speech recognition or translation.", 13f, mint))
            }
            val searchInput = inputField("Search dialogue, mm:ss, or 'warning'…").apply { setSingleLine() }
            container.addView(searchInput)
            val pronBtn = button("Pronunciations (${pronunciations.size})") { pronunciationDialog(job) }
            val songBtn = button("Song ranges (${preservedRanges.size})") { songRangeDialog(job) }
            container.addTwoColumnRow(pronBtn, songBtn)

            var warningsOnly = false
            val emptyLabel = label("No matching dialogue cues. Prepare a scene preview or dub first.", 14f, muted).apply {
                setPadding(0, dp(14), 0, dp(8))
            }
            var displayedCues = emptyList<SavedCue>()
            val cueRecycler = RecyclerView(this@MainActivity).apply {
                layoutManager = LinearLayoutManager(this@MainActivity)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)).apply {
                    topMargin = dp(8)
                }
            }
            val cueAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                    val btn = parent.context.button("") {}.apply {
                        gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                    }
                    return object : RecyclerView.ViewHolder(btn) {}
                }
                override fun getItemCount(): Int = displayedCues.size
                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    val cue = displayedCues[position]
                    val start = cue.effectiveStartMs(job.subtitleOffsetMs)
                    val end = cue.effectiveEndMs(job.subtitleOffsetMs)
                    val warn = if (cue.hasTimingWarning(job.subtitleOffsetMs)) " · ⚠ Exceeds window" else ""
                    val subOnlyTag = if (cue.subtitlesOnly) " · [Subtitles only]" else ""
                    val orig = cue.originalTranscript(job.mode)?.let { "\nOriginal: $it" }.orEmpty()
                    val text = "#${cue.index + 1} (${Subtitles.formatClock(start)}–${Subtitles.formatClock(end)})$warn$subOnlyTag\n${cue.effectiveEnglish()}$orig"
                    (holder.itemView as Button).apply {
                        this.text = text
                        this.contentDescription = text
                        setOnClickListener { editCueDialog(job, cue) }
                    }
                }
            }
            cueRecycler.adapter = cueAdapter
            fun populateCues(query: String) {
                val searched = Subtitles.searchCues(allCues, query, job.mode, job.subtitleOffsetMs)
                displayedCues = if (warningsOnly) searched.filter { it.hasTimingWarning(job.subtitleOffsetMs) } else searched
                emptyLabel.visibility = if (displayedCues.isEmpty()) View.VISIBLE else View.GONE
                cueRecycler.visibility = if (displayedCues.isEmpty()) View.GONE else View.VISIBLE
                cueAdapter.notifyDataSetChanged()
            }
            val warningCount = allCues.count { it.hasTimingWarning(job.subtitleOffsetMs) }
            if (warningCount > 0) {
                val filterBtn = button("Filter: Timing warnings ($warningCount)") {}
                filterBtn.setOnClickListener {
                    warningsOnly = !warningsOnly
                    filterBtn.text = if (warningsOnly) "✓ Showing timing warnings ($warningCount)" else "Filter: Timing warnings ($warningCount)"
                    populateCues(searchInput.text?.toString().orEmpty())
                }
                container.addView(filterBtn)
            }
            populateCues("")
            searchInput.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { populateCues(s?.toString().orEmpty()) }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            container.addView(emptyLabel)
            container.addView(cueRecycler)
            val builder = AlertDialog.Builder(this@MainActivity).setTitle("Review & correct dialogue (${allCues.size} lines)")
                .setView(container)
                .setNegativeButton("Close", null)
            if (allCues.isNotEmpty()) {
                builder.setPositiveButton("Rebuild dub") { _, _ ->
                    if (checkIdleUi()) startTask(PreparationService.REBUILD, job.id)
                }
            }
            builder.show()
        } }
    }

    private fun editCueDialog(job: DubJob, cue: SavedCue) {
        val box = column().apply { setPadding(dp(20), dp(10), dp(20), dp(10)) }
        cue.originalTranscript(job.mode)?.let { box.addView(label("Original subtitle: $it", 13f, muted)) }
        val defaultStart = cue.effectiveStartMs(job.subtitleOffsetMs)
        val defaultEnd = cue.effectiveEndMs(job.subtitleOffsetMs)
        val englishInput = inputField("English dialogue").apply { setText(cue.effectiveEnglish()) }
        val startInput = inputField("Start (ms or mm:ss.SSS)").apply { setText(defaultStart.toString()); setSingleLine() }
        val endInput = inputField("End (ms or mm:ss.SSS)").apply { setText(defaultEnd.toString()); setSingleLine() }
        val subOnlySwitch = CheckBox(this).apply {
            text = "Subtitles only (keep original audio, no synthesized speech)"
            setTextColor(ink); minHeight = dp(48); isChecked = cue.subtitlesOnly
        }
        box.addView(label("English text (captions & speech):", 13f, mint, true)); box.addView(englishInput)
        box.addView(label("Effective start / end (ms or mm:ss):", 13f, mint, true))
        box.addTwoColumnRow(startInput, endInput)
        box.addView(subOnlySwitch)
        box.addView(button("Play this cue in player") {
            startActivity(Intent(this, PlayerActivity::class.java).putExtra("id", job.id).putExtra("startMs", cue.effectiveStartMs(job.subtitleOffsetMs)))
        })
        AlertDialog.Builder(this).setTitle("Edit line #${cue.index + 1}")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save & rebuild") { _, _ -> lifecycleScope.launch { guarded {
                checkIdle()
                val newText = englishInput.text.toString().trim()
                require(newText.isNotEmpty()) { "Dialogue text cannot be empty." }
                val newStart = Subtitles.parseTimestampOrSeconds(startInput.text.toString())
                val newEnd = Subtitles.parseTimestampOrSeconds(endInput.text.toString())
                require(newEnd > newStart) { "End time must be after start time." }
                val baseStart = (cue.startMs + job.subtitleOffsetMs).coerceAtLeast(0L)
                val baseEnd = maxOf(baseStart + 1L, cue.endMs + job.subtitleOffsetMs)
                val startOverride = if (newStart != baseStart || cue.startOverrideMs != null) newStart else null
                val endOverride = if (newEnd != baseEnd || cue.endOverrideMs != null) newEnd else null
                app.jobs.updateCueOverride(job.id, cue.index, newText, startOverride, endOverride, subOnlySwitch.isChecked)
                startTask(PreparationService.REBUILD, job.id)
            } } }.show()
    }

    private fun pronunciationDialog(job: DubJob) {
        lifecycleScope.launch { guarded {
            val entries = app.jobs.pronunciations(job.id)
            val box = column().apply { setPadding(dp(20), dp(10), dp(20), dp(10)) }
            box.addView(label("Pronunciation replacements apply to spoken audio only; captions keep normal spelling.", 13f, muted))
            val origInput = inputField("Written word/name (e.g. Vijay)").apply { setSingleLine() }
            val spokenInput = inputField("Spoken phonetics (e.g. Vee-jay)").apply { setSingleLine() }
            box.addView(origInput); box.addView(spokenInput)
            entries.forEach { entry ->
                box.addView(button("${entry.original} → ${entry.spoken} · Remove", destructive = true) {
                    lifecycleScope.launch {
                        app.jobs.deletePronunciation(job.id, entry.original)
                        toast("Removed pronunciation for ${entry.original}. Rebuild dub to apply.")
                    }
                })
            }
            AlertDialog.Builder(this@MainActivity).setTitle("Pronunciation replacements")
                .setView(box)
                .setNegativeButton("Close", null)
                .setPositiveButton("Add") { _, _ -> lifecycleScope.launch { guarded {
                    val from = origInput.text.toString().trim()
                    val to = spokenInput.text.toString().trim()
                    require(from.isNotEmpty() && to.isNotEmpty()) { "Enter both the written spelling and spoken pronunciation." }
                    app.jobs.savePronunciation(PronunciationEntry(job.id, from, to))
                    toast("Saved pronunciation: $from → $to. Rebuild dub to apply.")
                } } }.show()
        } }
    }

    private fun songRangeDialog(job: DubJob) {
        lifecycleScope.launch { guarded {
            val ranges = app.jobs.preservedRanges(job.id)
            val box = column().apply { setPadding(dp(20), dp(10), dp(20), dp(10)) }
            box.addView(label("Manual song ranges preserve original audio and show available English captions without synthesized speech. Automatic song detection and lyric translation are not claimed.", 13f, muted))
            val startInput = inputField("Start (e.g. 12:30)").apply { setSingleLine() }
            val endInput = inputField("End (e.g. 16:00)").apply { setSingleLine() }
            box.addTwoColumnRow(startInput, endInput)
            ranges.forEach { r ->
                box.addView(button("${Subtitles.formatClock(r.startMs)}–${Subtitles.formatClock(r.endMs)} · Remove", destructive = true) {
                    lifecycleScope.launch {
                        app.jobs.deletePreservedRange(job.id, r.startMs)
                        toast("Removed song range.")
                    }
                })
            }
            AlertDialog.Builder(this@MainActivity).setTitle("Manual song / preserved-audio ranges")
                .setView(box)
                .setNegativeButton("Close", null)
                .setPositiveButton("Add range") { _, _ -> lifecycleScope.launch { guarded {
                    val s = Subtitles.parseTimestampOrSeconds(startInput.text.toString())
                    val e = Subtitles.parseTimestampOrSeconds(endInput.text.toString())
                    require(e > s) { "Song end time must be after start time." }
                    app.jobs.savePreservedRange(PreservedRange(job.id, s, e))
                    toast("Added preserved song range ${Subtitles.formatClock(s)}–${Subtitles.formatClock(e)}.")
                } } }.show()
        } }
    }

    private fun queueDialog(job: DubJob) {
        if (!checkIdleUi()) return
        val box = column().apply { setPadding(dp(20), dp(10), dp(20), dp(10)) }
        box.addView(label("Queued films process one at a time within the active foreground session. Android imposes foreground-service time limits; if paused by Android or unplugged under charging-only mode, tap Resume to continue.", 13f, muted))
        var chargingOnly = job.chargingOnly
        val chargingCheck = CheckBox(this).apply {
            text = "Charging-only preparation (recommended default)"
            setTextColor(ink); minHeight = dp(48); isChecked = chargingOnly
            setOnCheckedChangeListener { _, checked -> chargingOnly = checked }
        }
        box.addView(chargingCheck)
        if (job.state == "queued") {
            val upBtn = button("Move earlier (↑)") { lifecycleScope.launch { guarded {
                val reordered = ResourcePolicy.reorderQueue(app.jobs.queued(), job.id, -1)
                reordered.forEach { app.jobs.save(it) }
                toast("Moved earlier in queue.")
            } } }
            val downBtn = button("Move later (↓)") { lifecycleScope.launch { guarded {
                val reordered = ResourcePolicy.reorderQueue(app.jobs.queued(), job.id, 1)
                reordered.forEach { app.jobs.save(it) }
                toast("Moved later in queue.")
            } } }
            box.addTwoColumnRow(upBtn, downBtn)
            box.addView(button("Remove from queue", destructive = true) { lifecycleScope.launch { guarded {
                app.jobs.updateQueue(job.id, 0, chargingOnly, "new", "Removed from preparation queue.")
            } } })
        }
        AlertDialog.Builder(this).setTitle("Preparation queue")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Queue later") { _, _ -> lifecycleScope.launch { guarded {
                val nextOrder = (app.jobs.queued().maxOfOrNull { it.queueOrder } ?: 0) + 1
                val order = if (job.state == "queued" && job.queueOrder > 0) job.queueOrder else nextOrder
                app.jobs.updateQueue(job.id, order, chargingOnly, "queued", "Queued (#$order · ${if (chargingOnly) "charging-only" else "battery allowed"}).")
            } } }
            .setPositiveButton("Start now") { _, _ -> lifecycleScope.launch { guarded {
                checkIdle()
                val nextOrder = (app.jobs.queued().maxOfOrNull { it.queueOrder } ?: 0) + 1
                val order = if (job.state == "queued" && job.queueOrder > 0) job.queueOrder else nextOrder
                app.jobs.updateQueue(job.id, order, chargingOnly, "queued", "Starting queued preparation…")
                val first = app.jobs.queued().firstOrNull() ?: job
                startTask(PreparationService.PREPARE, first.id, enforceCharging = first.chargingOnly)
            } } }.show()
    }

    private fun exportDialog(job: DubJob) {
        exportJobId = job.id
        val baseName = job.title.substringBeforeLast('.').ifBlank { "englishdub" }
        val options = arrayOf(
            "Export English subtitles (UTF-8 SRT · corrected text & effective timestamps)",
            "Export generated English voice track (M4A · voice only, without original soundtrack)",
        )
        AlertDialog.Builder(this).setTitle("Export results")
            .setItems(options) { _, which ->
                if (which == 0) srtExportPicker.launch("$baseName.en.srt")
                else m4aExportPicker.launch("$baseName.english-voice.m4a")
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun linkDialog() {
        if (!checkIdleUi()) return
        val box = column().apply { setPadding(dp(20), dp(8), dp(20), dp(4)) }
        val input = inputField("https://example.com/movie.mp4").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        box.addView(input)
        AlertDialog.Builder(this).setTitle("Download video").setMessage("Paste a direct downloadable media URL. The complete video is saved before dubbing.")
            .setView(box).setNegativeButton("Cancel", null).setPositiveButton("Download") { _, _ -> lifecycleScope.launch { guarded {
                checkIdle(); val url = input.text.toString().trim(); val parsed = URI(url)
                require(parsed.scheme in listOf("http", "https") && !parsed.host.isNullOrBlank()) { "Enter a valid HTTP or HTTPS video URL." }
                val name = parsed.path?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Downloaded video"
                val job = DubJob(UUID.randomUUID().toString(), name, url)
                app.jobs.save(job); startTask(PreparationService.DOWNLOAD, job.id)
            } } }.show()
    }

    private fun persist(uri: Uri) { try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { toast("This provider may require reopening the file after restarting your phone.") } }
    private fun fileName(uri: Uri): String = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Local video"

    private suspend fun refreshPacks() {
        try { installed = withContext(Dispatchers.IO) { withTimeout(5000) { OfflineTranslation.installed() } }; packWarning = "" }
        catch (_: TimeoutCancellationException) { installed = emptySet(); packWarning = "Translation pack status is unavailable. Voice and speech setup remain available." }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { installed = emptySet(); packWarning = "Translation pack status is unavailable. Voice and speech setup remain available." }
        voices = withContext(Dispatchers.IO) { runCatching { OfflineVoice.availableVoices(this@MainActivity) }.getOrDefault(voices) }
        storage = withContext(Dispatchers.IO) { ResourcePolicy.computeStorage(app.filesDir) }
    }

    private fun checkIdle() { check(!PreparationService.status.value.busy) { "Pause the current task first." } }
    private fun checkIdleUi(): Boolean { if (PreparationService.status.value.busy) { toast("Pause the current task first."); return false }; return true }

    private fun startTask(action: String, id: String, previewStartMs: Long = 0L, enforceCharging: Boolean = false) {
        try {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            PreparationService.start(this, action, id, previewStartMs, enforceCharging)
        } catch (e: Exception) { toast(e.message ?: "Could not start task.") }
    }

    private suspend fun guarded(block: suspend () -> Unit) { try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: "Could not finish. Please retry.") } }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

