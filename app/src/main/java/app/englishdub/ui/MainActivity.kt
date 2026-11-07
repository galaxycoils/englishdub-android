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
import kotlinx.coroutines.flow.debounce
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
    private var lastBusy = false
    private var storageBytes = 0L
    private var visibleCount = 20
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
        root = column().apply { setBackgroundColor(canvasColor); setPadding(dp(22), dp(12), dp(22), dp(20)) }
        scroll = ScrollView(this).apply { isFillViewport = true; addView(root) }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        lifecycleScope.launch {
            if (!PreparationService.status.value.busy) app.jobs.recoverInterrupted()
            refreshPacks()
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(app.jobs.observe(), PreparationService.status) { items, status -> items to status }.debounce(150).collect { (items, status) ->
                    jobs = items
                    if (lastBusy && !status.busy) refreshPacks()
                    lastBusy = status.busy
                    render()
                }
            }
        }
        render()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("subtitleFor", subtitleFor); outState.putBoolean("offline", offline); super.onSaveInstanceState(outState)
    }
    private fun render() {
        val y = scroll.scrollY
        root.removeAllViews()
        root.addView(label("ENGLISH, FIRST", 12f, mint, true))
        root.addView(label("EnglishDub", 34f, ink, true))
        root.addView(label(if (offline) "Set up once. Watch offline." else "Your films. An English voice.", 16f, muted))
        val tabs = row()
        tabs.addView(button("Library", !offline) { offline = false; render() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(8) })
        tabs.addView(button("Offline setup", offline) { offline = true; lifecycleScope.launch { refreshPacks(); render() } }, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.gap(18); root.addView(tabs)
        val status = PreparationService.status.value
        if (status.busy) root.addView(card().apply {
            addView(label(status.message, 16f, mint, true))
            addView(ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply { progress = status.progress })
            addView(button("Pause task") { PreparationService.pause(this@MainActivity) })
        }) else if (status.message.isNotBlank() && status.message != "Completed") root.addView(label(status.message, 14f, muted))
        if (offline) renderOffline() else renderLibrary()
        scroll.post { scroll.scrollTo(0, y) }
    }
    private fun renderLibrary() {
        root.addView(button("＋  Open a video", true) { videoPicker.launch(arrayOf("video/*")) })
        root.addView(button("Download a direct video link") { linkDialog() })
        root.gap(16)
        root.addView(label("YOUR LIBRARY", 12f, muted, true))
        if (jobs.isEmpty()) root.addView(card().apply {
            addView(label("A lighter way to watch", 21f, ink, true))
            addView(label("Open a film from your phone. Use English audio, turn subtitles into speech, or prepare a dub from the original dialogue.", 16f, muted))
            gap(8)
            addView(label("Hindi · Urdu · Tamil → English", 14f, mint))
            addView(label("One offline voice. No account. Optional AI packs.", 14f, muted))
        })
        jobs.take(visibleCount).forEach { job -> root.addView(card().apply {
            addView(label(job.state.uppercase(), 11f, mint, true))
            addView(label(job.title, 20f, ink, true))
            addView(label(job.message, 14f, muted))
            if (job.state in listOf("preparing", "downloading", "paused")) addView(ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply { progress = job.progress })
            if (!job.mediaUri.startsWith("http")) {
                addView(button(if (job.audioPath != null) "Watch in English" else "Watch · English audio preferred", true) {
                    if (PreparationService.status.value.busy) toast("Pause preparation before watching to keep playback light.")
                    else startActivity(Intent(this@MainActivity, PlayerActivity::class.java).putExtra("id", job.id))
                })
                if (job.state !in listOf("preparing", "downloading")) {
                    addView(button(if (job.state in listOf("paused", "failed")) "Resume preparation" else "Prepare English dub") {
                        if (job.state in listOf("paused", "failed")) startTask(PreparationService.PREPARE, job.id) else prepareDialog(job)
                    })
                    addView(button(if (job.subtitleUri == null) "Add SRT / WebVTT subtitles" else "Replace subtitle file") {
                        if (checkIdleUi()) { subtitleFor = job.id; subtitlePicker.launch(arrayOf("*/*")) }
                    })
                }
            } else if (job.state != "downloading") addView(button("Download / resume", true) { startTask(PreparationService.DOWNLOAD, job.id) })
            if (!PreparationService.status.value.busy) addView(button("Remove from library") {
                AlertDialog.Builder(this@MainActivity).setTitle("Remove this video?")
                    .setMessage("Remove its saved dub, preparation files, and any copy downloaded by EnglishDub. Your original local file stays on your phone.")
                    .setNegativeButton("Keep", null).setPositiveButton("Remove") { _, _ -> lifecycleScope.launch { guarded {
                        withContext(Dispatchers.IO) { app.jobDirectory(job.id).deleteRecursively() }; app.jobs.delete(job.id)
                    } } }.show()
            })
        }) }
        if (jobs.size > visibleCount) root.addView(button("Show more videos") { visibleCount += 20; render() })
        root.gap(18)
        root.addView(label("Direct downloadable videos only. Playback formats depend on your phone’s codecs.", 12f, muted))
    }

    private fun renderOffline() {
        root.addView(card().apply {
            addView(label("01  English voice", 20f, ink, true))
            addView(label("Use an installed English voice that works without a network connection.", 14f, muted))
            addView(button("Check offline English voice", true) { lifecycleScope.launch { guarded { OfflineVoice.create(this@MainActivity).use { toast("Offline English voice is ready.") } } } })
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
            addView(label("Keep storage light", 20f, ink, true))
            addView(label("Videos and prepared dubs: ${storageBytes / 1024 / 1024} MB\nAI models are unloaded before watching. EnglishDub does not upload your video or dialogue.", 14f, muted))
            addView(button("Clear completed preparation files") { lifecycleScope.launch { guarded {
                checkIdle(); withContext(Dispatchers.IO) {
                    jobs.filter { it.state == "ready" }.forEach { job -> app.jobDirectory(job.id).listFiles()?.filter { it.extension in listOf("pcm", "wav") }?.forEach { it.delete() } }
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
                if (speech) check(app.models.ready) { "Download the optional speech pack in Offline setup first." }
                else if (code != "en") check(code in installed) { "Download the ${OfflineTranslation.languages[code]} translation pack in Offline setup first." }
                app.jobs.clearCues(job.id)
                app.jobs.save(job.copy(mode = if (speech) "speech" else "subtitles", sourceLanguage = code,
                    state = "new", audioPath = null, decodedUntilMs = 0, progress = 0))
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
        try { installed = OfflineTranslation.installed() } catch (e: CancellationException) { throw e } catch (_: Exception) { installed = emptySet() }
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
