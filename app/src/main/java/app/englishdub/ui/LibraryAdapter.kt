package app.englishdub.ui

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.englishdub.data.DubJob
import java.io.File

data class LibraryRow(val job: DubJob?, val busy: Boolean)

class LibraryAdapter(private val action: (String, DubJob) -> Unit) : ListAdapter<LibraryRow, LibraryAdapter.Holder>(object : DiffUtil.ItemCallback<LibraryRow>() {
    override fun areItemsTheSame(old: LibraryRow, new: LibraryRow) = old.job?.id == new.job?.id
    override fun areContentsTheSame(old: LibraryRow, new: LibraryRow) = old == new
}) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(parent.context.card())
    override fun onBindViewHolder(holder: Holder, position: Int) { holder.bind(getItem(position)) }
    inner class Holder(private val card: LinearLayout) : RecyclerView.ViewHolder(card) {
        private val context = card.context
        private val state = context.label("", 11f, mint, true)
        private val title = context.label("", 20f, ink, true)
        private val message = context.label("", 14f, muted)
        private val previewInfo = context.label("", 13f, mint)
        private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
        private val buttons = linkedMapOf<String, android.widget.Button>()
        private var item: DubJob? = null
        init {
            card.addView(state); card.addView(title); card.addView(message); card.addView(previewInfo); card.addView(progress)
            val keys = listOf("watch", "watch_preview", "resume", "prepare", "preview", "review", "queue", "export", "subtitles", "download", "remove")
            for (key in keys) {
                val button = context.button(key, key == "watch" || key == "download" || key == "resume") { item?.let { action(key, it) } }
                buttons[key] = button; card.addView(button)
            }
            card.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = context.dp(14) }
        }
        fun bind(row: LibraryRow) {
            val job = row.job; item = job
            state.text = formatStateBadge(job)
            title.text = job?.title ?: "A lighter way to watch"
            message.text = job?.message ?: "Open a film → choose track and language → preview a 60s scene → prepare English → review dialogue → watch or export. Hindi · Urdu · Tamil → English."
            val hasPreview = job?.previewAudioPath?.let { File(it).exists() } == true
            previewInfo.text = job?.previewMessage.orEmpty()
            previewInfo.visibility = if (job != null && job.previewMessage.isNotBlank()) View.VISIBLE else View.GONE
            progress.visibility = if (job?.state in listOf("preparing", "downloading", "paused", "queued")) View.VISIBLE else View.GONE
            progress.progress = job?.progress ?: 0
            buttons.values.forEach { it.visibility = View.GONE; it.isEnabled = !row.busy }
            if (job == null) return
            val local = !job.mediaUri.startsWith("http")
            val editable = job.state !in listOf("preparing", "downloading")
            val resumable = job.state in listOf("paused", "failed", "low_storage", "missing_pack", "missing_file", "queued")
            buttons["watch"]!!.apply {
                visibility = if (local) View.VISIBLE else View.GONE
                text = if (job.audioPath != null) "Watch in English" else "Watch · English audio preferred"
                contentDescription = text
            }
            buttons["watch_preview"]!!.apply {
                visibility = if (local && hasPreview) View.VISIBLE else View.GONE
                text = "Watch 60s scene preview (compare original / English)"
                contentDescription = text
            }
            buttons["resume"]!!.apply {
                visibility = if (local && editable && resumable) View.VISIBLE else View.GONE
                text = if (job.state == "queued") "Start queued preparation" else "Resume preparation"
                contentDescription = text
            }
            buttons["prepare"]!!.apply {
                visibility = if (local && editable) View.VISIBLE else View.GONE
                text = "Choose source & prepare English"
                contentDescription = text
            }
            buttons["preview"]!!.apply {
                visibility = if (local && editable) View.VISIBLE else View.GONE
                text = "Preview a 60-second scene"
                contentDescription = text
            }
            buttons["review"]!!.apply {
                visibility = if (local && editable) View.VISIBLE else View.GONE
                text = "Review & edit dialogue · pronunciations · songs"
                contentDescription = text
            }
            buttons["queue"]!!.apply {
                visibility = if (local && editable) View.VISIBLE else View.GONE
                text = if (job.state == "queued") "Queue options (order #${job.queueOrder} · ${if (job.chargingOnly) "charging-only" else "battery allowed"})" else "Add to preparation queue"
                contentDescription = text
            }
            buttons["export"]!!.apply {
                visibility = if (local && editable && (job.audioPath != null || job.state == "ready")) View.VISIBLE else View.GONE
                text = "Export English SRT or voice M4A"
                contentDescription = text
            }
            buttons["subtitles"]!!.apply {
                visibility = if (local && editable) View.VISIBLE else View.GONE
                text = if (job.subtitleUri == null) "Add SRT / WebVTT subtitles" else "Replace subtitle file"
                contentDescription = text
            }
            buttons["download"]!!.apply {
                visibility = if (!local && editable) View.VISIBLE else View.GONE
                text = "Download / resume"
                contentDescription = text
            }
            buttons["remove"]!!.apply {
                visibility = if (!row.busy) View.VISIBLE else View.GONE
                text = "Remove from library"
                contentDescription = text
            }
        }
        private fun formatStateBadge(job: DubJob?): String {
            if (job == null) return "YOUR LIBRARY"
            return when (job.state) {
                "queued" -> "WAITING IN QUEUE (#${job.queueOrder} · ${if (job.chargingOnly) "CHARGING ONLY" else "ANY POWER"})"
                "preparing" -> "RUNNING · PREPARING"
                "downloading" -> "RUNNING · DOWNLOADING"
                "paused" -> "PAUSED · TAP RESUME"
                "missing_file" -> "MISSING OR INACCESSIBLE FILE"
                "missing_pack" -> "MISSING OFFLINE PACK"
                "low_storage" -> "LOW STORAGE"
                "failed" -> "FAILED"
                "ready" -> "COMPLETED · READY"
                else -> job.state.uppercase()
            }
        }
    }
}

