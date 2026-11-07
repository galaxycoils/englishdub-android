package app.englishdub.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.englishdub.DubApplication
import app.englishdub.data.DubJob
import app.englishdub.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import android.view.View
import android.widget.FrameLayout
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
    private var installed = emptySet<String>()
    private var packWarning = ""
    private var lastBusy = false
    private var storageBytes = 0L
    private lateinit var library: RecyclerView
    private lateinit var libraryAdapter: LibraryAdapter
    private lateinit var libraryActions: LinearLayout
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
                    File(app.jobDirectory(id), "english.m4a").delete()
                    app.jobs.save(job.copy(subtitleUri = uri.toString(), mode = "subtitles", state = "new", audioPath = null, progress = 0, decodedUntilMs = 0, message = "Subtitles added. Choose their language with Prepare dub."))
                }
            } }
        }
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        subtitleFor = savedInstanceState?.getString("subtitleFor")
        offline = savedInstanceState?.getBoolean("offline") ?: false
        root = column().apply { setPadding(0, 0, 0, dp(20)) }
        scroll = ScrollView(this).apply { isFillViewport = true; addView(root); visibility = View.GONE }
        libraryAdapter = LibraryAdapter(::libraryAction)
        library = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@MainActivity); adapter = libraryAdapter; itemAnimator = null }
        val shell = column().apply { setBackgroundColor(canvasColor); setPadding(dp(22), dp(12), dp(22), dp(10)) }
        shell.addView(label("ENGLISH, FIRST", 12f, mint, true)); shell.addView(label("EnglishDub", 30f, ink, true))
        val tabs = row()
        tabs.addView(button("Library") { offline = false; render() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(8) })
        tabs.addView(button("Offline setup") {
            offline = true; render()
            lifecycleScope.launch { refreshPacks(); if (offline) render() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        shell.addView(tabs)
        statusLabel = label("", 14f, mint)
        statusProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        pauseButton = button("Pause task") { PreparationService.pause(this) }
        shell.addView(statusLabel); shell.addView(statusProgress); shell.addView(pauseButton)
        libraryActions = column().apply {
            addView(button("＋  Open a video", true) { videoPicker.launch(arrayOf("video/*")) })
            addView(button("Download a direct video link") { linkDialog() })
        }
        shell.addView(libraryActions)
        val body = FrameLayout(this).apply { addView(library); addView(scroll) }
        shell.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(shell)
        ViewCompat.setOnApplyWindowInsetsListener(shell) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(22) + bars.left, dp(12) + bars.top, dp(22) + bars.right, dp(10) + bars.bottom); insets
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
        outState.putString("subtitleFor", subtitleFor); outState.putBoolean("offline", offline); super.onSaveInstanceState(outState)
    }
    private fun render() {
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
            "prepare" -> if (job.state in listOf("paused", "failed")) startTask(PreparationService.PREPARE, job.id) else prepareDialog(job)
            "subtitles" -> { subtitleFor = job.id; subtitlePicker.launch(arrayOf("*/*")) }
            "download" -> startTask(PreparationService.DOWNLOAD, job.id)
            "remove" -> AlertDialog.Builder(this).setTitle("Remove this video?")
                .setMessage("Remove its saved dub, preparation files, and any copy downloaded by EnglishDub. Your original local file stays on your phone.")
                .setNegativeButton("Keep", null).setPositiveButton("Remove") { _, _ -> lifecycleScope.launch { guarded {
                    checkIdle(); withContext(Dispatchers.IO) { app.jobDirectory(job.id).deleteRecursively() }; app.jobs.delete(job.id)
                } } }.show()
        }
    }

    private fun renderOffline() {
        if (packWarning.isNotBlank()) root.addView(label(packWarning, 13f, muted))
        root.addView(card().apply {
            addView(label("01  English voice", 20f, ink, true))
            addView(label("Use an installed English voice that works without a network connection.", 14f, muted))
            addView(button("Check offline English voice", true) { lifecycleScope.launch { guarded {
                checkIdle(); toast("Checking offline speech generation…")
                withContext(Dispatchers.IO) { OfflineVoice.verify(this@MainActivity) }
                toast("Offline English voice generated audio successfully.")
            } } })
            addView(button("Open Android voice settings") {
                try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                catch (_: Exception) { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)); toast("Search Settings for Text-to-speech.") }
            })
        })
        root.addView(card().apply {
            addView(label("02  Subtitle translation", 20f, ink, true))
            addView(label("English subtitles need no translation pack. Other packs are about 30 MB each; downloads wait for Wi-Fi.", 14f, muted))
            OfflineTranslation.languages.filterKeys { it != "en" }.forEach { (code, name) ->
                addView(button(if (code in installed) "$name · installed · remove" else "Download $name") {
                    if (code in installed) lifecycleScope.launch { guarded { checkIdle(); OfflineTranslation.delete(code); refreshPacks(); render() } }
                    else startTask(PreparationService.TRANSLATION_PACK, code)
                })
            }
        })
        root.addView(card().apply {
            addView(label("03  Dialogue recognition", 20f, ink, true))
            addView(label("Optional · 57 MB download\nFor films without subtitles. Translates Hindi, Urdu, or Tamil speech into English on your phone. Preparation can be slow, and film-dialogue accuracy varies.", 14f, muted))
            addView(button(if (app.models.ready) "Speech pack installed · remove" else "Download speech pack", true) {
                if (app.models.ready) { if (checkIdleUi()) { app.models.delete(); render() } }
                else AlertDialog.Builder(this@MainActivity).setTitle("Download speech pack?").setMessage("Download 57 MB using your current internet connection? After setup, speech processing works offline.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Download") { _, _ -> startTask(PreparationService.SPEECH_PACK, "speech-pack") }.show()
            })
        })
        root.addView(card().apply {
            addView(label("Speech detection · experimental", 18f, ink, true))
            addView(label("Optional 0.9 MB detector. It can skip non-dialogue, but remains off by default until Hindi, Urdu, and Tamil quality is verified.", 14f, muted))
            addView(button(if (app.models.vadReady) "Speech detector installed · remove" else "Download speech detector") {
                if (app.models.vadReady) { if (checkIdleUi()) { app.models.deleteVad(); render() } }
                else startTask(PreparationService.VAD_PACK, "vad-pack")
            })
            val settings = getSharedPreferences("preparation", MODE_PRIVATE)
            addView(Switch(this@MainActivity).apply {
                text = "Use experimental speech detection"; setTextColor(ink)
                isEnabled = app.models.vadReady && !PreparationService.status.value.busy
                isChecked = settings.getBoolean("vad", false)
                setOnCheckedChangeListener { _, checked -> settings.edit().putBoolean("vad", checked).apply() }
            })
            if (app.models.hasPartial) addView(button("Delete incomplete speech download") { if (checkIdleUi()) { app.models.deletePartial(); render() } })
        })
        root.addView(card().apply {
            addView(label("Keep storage light", 20f, ink, true))
            addView(label("Videos and prepared dubs: ${storageBytes / 1024 / 1024} MB\nAI models are unloaded before watching. EnglishDub does not upload your video or dialogue.", 14f, muted))
            addView(button("Clear completed preparation files") { lifecycleScope.launch { guarded {
                checkIdle(); withContext(Dispatchers.IO) {
                    jobs.filter { it.state == "ready" }.forEach { job ->
                        File(app.jobDirectory(job.id), "cue-audio").deleteRecursively()
                        app.jobDirectory(job.id).listFiles()?.filter { it.extension in listOf("pcm", "wav") }?.forEach { it.delete() }
                    }
                }; refreshPacks(); toast("Temporary preparation files cleared. Saved dubs are kept."); render()
            } } })
        })
    }

    private fun prepareDialog(job: DubJob) {
        if (!checkIdleUi()) return
        val names = arrayOf("English subtitles · lightest", "Hindi subtitles", "Urdu subtitles", "Tamil subtitles", "Hindi dialogue · speech pack", "Urdu dialogue · speech pack", "Tamil dialogue · speech pack")
        var selection = 0
        AlertDialog.Builder(this).setTitle("Prepare an English dub")
            .setSingleChoiceItems(names, 0) { _, which -> selection = which }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Prepare") { _, _ -> lifecycleScope.launch { guarded {
                checkIdle()
                val speech = selection >= 4
                val code = if (speech) listOf("hi", "ur", "ta")[selection - 4] else listOf("en", "hi", "ur", "ta")[selection]
                app.jobs.request(job.id, if (speech) "speech" else "subtitles", code)
                startTask(PreparationService.PREPARE, job.id)
            } } }.show()
    }
    private fun linkDialog() {
        if (!checkIdleUi()) return
        val input = EditText(this).apply { hint = "https://example.com/movie.mp4"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI; setSingleLine() }
        AlertDialog.Builder(this).setTitle("Download video").setMessage("Paste a direct downloadable media URL. The complete video is saved before dubbing.")
            .setView(input).setNegativeButton("Cancel", null).setPositiveButton("Download") { _, _ -> lifecycleScope.launch { guarded {
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
        storageBytes = withContext(Dispatchers.IO) { File(app.filesDir, "jobs").walkTopDown().filter { it.isFile }.sumOf { it.length() } }
    }
    private fun checkIdle() { check(!PreparationService.status.value.busy) { "Pause the current task first." } }
    private fun checkIdleUi(): Boolean { if (PreparationService.status.value.busy) { toast("Pause the current task first."); return false }; return true }
    private fun startTask(action: String, id: String) {
        try {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            PreparationService.start(this, action, id)
        } catch (e: Exception) { toast(e.message ?: "Could not start task.") }
    }
    private suspend fun guarded(block: suspend () -> Unit) { try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: "Could not finish. Please retry.") } }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
