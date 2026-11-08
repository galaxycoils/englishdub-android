package app.englishdub.ui

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.englishdub.data.DubJob
import java.io.File

data class LibraryRow(val job: DubJob?, val busy: Boolean)

class LibraryAdapter(
    private val action: (String, DubJob) -> Unit,
) : ListAdapter<LibraryRow, LibraryAdapter.Holder>(
    object : DiffUtil.ItemCallback<LibraryRow>() {
        override fun areItemsTheSame(old: LibraryRow, new: LibraryRow) = old.job?.id == new.job?.id
        override fun areContentsTheSame(old: LibraryRow, new: LibraryRow) = old == new
    }
) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(parent.context.card())
    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(private val card: LinearLayout) : RecyclerView.ViewHolder(card) {
        private val context = card.context
        private val state = context.eyebrow("")
        private val title = context.label("", 20f, ink, true)
        private val message = context.label("", 14f, muted)
        private val previewInfo = context.label("", 13f, mint)
        private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            progressTintList = ColorStateList.valueOf(mint)
            progressBackgroundTintList = ColorStateList.valueOf(surfaceSubtle)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = context.dp(6)
                bottomMargin = context.dp(4)
            }
        }
        private val toolsDivider = context.divider()
        private val prepRow = context.row()
        private val editRow = context.row()
        private val footerRow = context.row()
        private val buttons = linkedMapOf<String, Button>()
        private var item: DubJob? = null

        init {
            card.addView(state)
            card.addView(title)
            card.addView(message)
            card.addView(previewInfo)
            card.addView(progress)

            fun makeBtn(key: String, primary: Boolean = false, destructive: Boolean = false): Button {
                val btn = context.button(key, primary = primary, destructive = destructive) {
                    item?.let { action(key, it) }
                }
                buttons[key] = btn
                return btn
            }

            // Primary playback / recovery actions
            card.addView(makeBtn("watch", primary = true))
            card.addView(makeBtn("watch_preview"))
            card.addView(makeBtn("resume", primary = true))
            card.addView(makeBtn("download", primary = true))

            // Secondary preparation & editing section
            card.addView(toolsDivider)
            card.addView(makeBtn("prepare"))

            val previewBtn = makeBtn("preview")
            val queueBtn = makeBtn("queue")
            prepRow.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = context.dp(8) }
            prepRow.addView(previewBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = context.dp(4) })
            prepRow.addView(queueBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = context.dp(4) })
            card.addView(prepRow)

            val reviewBtn = makeBtn("review")
            val subtitlesBtn = makeBtn("subtitles")
            editRow.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = context.dp(8) }
            editRow.addView(reviewBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = context.dp(4) })
            editRow.addView(subtitlesBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = context.dp(4) })
            card.addView(editRow)

            val exportBtn = makeBtn("export")
            val removeBtn = makeBtn("remove", destructive = true)
            footerRow.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = context.dp(8) }
            footerRow.addView(exportBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = context.dp(4) })
            footerRow.addView(removeBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(footerRow)

            card.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = context.dp(12) }
        }

        fun bind(row: LibraryRow) {
            val job = row.job
            item = job
            state.text = formatStateBadge(job)
            state.setTextColor(stateBadgeColor(job))
            title.text = job?.title ?: "A lighter way to watch"
            message.text = job?.message
                ?: "Open a film → choose track & language → preview a 60s scene → prepare English → review dialogue → watch or export. Supports Hindi, Urdu, and Tamil to English."

            val hasPreview = job?.previewAudioPath?.let { File(it).exists() } == true
            previewInfo.text = job?.previewMessage.orEmpty()
            previewInfo.visibility = if (job != null && job.previewMessage.isNotBlank()) View.VISIBLE else View.GONE
            progress.visibility = if (job?.state in listOf("preparing", "downloading", "paused", "queued")) View.VISIBLE else View.GONE
            progress.progress = job?.progress ?: 0

            buttons.values.forEach {
                it.visibility = View.GONE
                it.isEnabled = !row.busy
            }
            if (job == null) {
                toolsDivider.visibility = View.GONE
                prepRow.visibility = View.GONE
                editRow.visibility = View.GONE
                footerRow.visibility = View.GONE
                return
            }

            val local = !job.mediaUri.startsWith("http")
            val editable = job.state !in listOf("preparing", "downloading")
            val resumable = job.state in listOf("paused", "failed", "low_storage", "missing_pack", "missing_file", "queued")
            val canExport = local && editable && (job.audioPath != null || job.state == "ready")
            val canRemove = !row.busy

            buttons["watch"]!!.apply {
                visibility = if (local) View.VISIBLE else View.GONE
                text = if (job.audioPath != null) "Watch in English" else "Watch · English audio preferred"
                contentDescription = text
            }
            buttons["watch_preview"]!!.apply {
                visibility = if (local && hasPreview) View.VISIBLE else View.GONE
                text = "Watch 60s scene preview"
                contentDescription = "Watch 60s scene preview (compare original and English)"
            }
            buttons["resume"]!!.apply {
                visibility = if (local && editable && resumable) View.VISIBLE else View.GONE
                text = if (job.state == "queued") "Start queued preparation" else "Resume preparation"
                contentDescription = text
            }
            buttons["download"]!!.apply {
                visibility = if (!local && editable) View.VISIBLE else View.GONE
                text = "Download / resume"
                contentDescription = text
            }
            buttons["prepare"]!!.apply {
                visibility = if (local && editable) View.VISIBLE else View.GONE
                text = "Choose source & prepare English"
                contentDescription = text
            }
            buttons["preview"]!!.apply {
                visibility = if (local && editable) View.VISIBLE else View.GONE
                text = "Preview 60s"
                contentDescription = "Preview a 60-second scene"
            }
            buttons["queue"]!!.apply {
                visibility = if (local && editable) View.VISIBLE else View.GONE
                text = if (job.state == "queued") "Queued #${job.queueOrder}" else "Queue options"
                contentDescription = if (job.state == "queued") {
                    "Queue options (order #${job.queueOrder}, ${if (job.chargingOnly) "charging-only" else "battery allowed"})"
                } else {
                    "Add to preparation queue"
                }
            }
            buttons["review"]!!.apply {
                visibility = if (local && editable) View.VISIBLE else View.GONE
                text = "Review dialogue"
                contentDescription = "Review and edit dialogue, pronunciations, and song ranges"
            }
            buttons["subtitles"]!!.apply {
                visibility = if (local && editable) View.VISIBLE else View.GONE
                text = if (job.subtitleUri == null) "Add subtitles" else "Replace subtitles"
                contentDescription = if (job.subtitleUri == null) "Add SRT or WebVTT subtitles" else "Replace subtitle file"
            }
            buttons["export"]!!.apply {
                visibility = if (canExport) View.VISIBLE else View.GONE
                text = "Export"
                contentDescription = "Export English SRT subtitles or voice M4A"
            }
            buttons["remove"]!!.apply {
                visibility = if (canRemove) View.VISIBLE else View.GONE
                text = "Remove"
                contentDescription = "Remove from library"
                (layoutParams as? LinearLayout.LayoutParams)?.leftMargin =
                    if (canExport) context.dp(4) else 0
            }

            val showLocalTools = local && editable
            toolsDivider.visibility = if (showLocalTools || canRemove) View.VISIBLE else View.GONE
            prepRow.visibility = if (showLocalTools) View.VISIBLE else View.GONE
            editRow.visibility = if (showLocalTools) View.VISIBLE else View.GONE
            footerRow.visibility = if (canExport || canRemove) View.VISIBLE else View.GONE
        }

        private fun stateBadgeColor(job: DubJob?): Int {
            if (job == null) return mint
            return when (job.state) {
                "ready", "preparing", "downloading" -> mint
                "paused", "queued", "missing_pack", "low_storage" -> amber
                "failed", "missing_file" -> coral
                else -> mint
            }
        }

        private fun formatStateBadge(job: DubJob?): String {
            if (job == null) return "YOUR LIBRARY"
            return when (job.state) {
                "queued" -> "QUEUED · #${job.queueOrder} (${if (job.chargingOnly) "CHARGING ONLY" else "ANY POWER"})"
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

