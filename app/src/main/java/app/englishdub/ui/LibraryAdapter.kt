package app.englishdub.ui

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.englishdub.data.DubJob

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
        private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
        private val buttons = linkedMapOf<String, android.widget.Button>()
        private var item: DubJob? = null
        init {
            card.addView(state); card.addView(title); card.addView(message); card.addView(progress)
            for (key in listOf("watch", "prepare", "subtitles", "download", "remove")) {
                val button = context.button(key, key == "watch" || key == "download") { item?.let { action(key, it) } }
                buttons[key] = button; card.addView(button)
            }
            card.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = context.dp(14) }
        }
        fun bind(row: LibraryRow) {
            val job = row.job; item = job
            state.text = job?.state?.uppercase() ?: "YOUR LIBRARY"
            title.text = job?.title ?: "A lighter way to watch"
            message.text = job?.message ?: "Open a film. Use English audio, read subtitles aloud, or prepare an English dub. Hindi · Urdu · Tamil → English."
            progress.visibility = if (job?.state in listOf("preparing", "downloading", "paused")) View.VISIBLE else View.GONE
            progress.progress = job?.progress ?: 0
            buttons.values.forEach { it.visibility = View.GONE; it.isEnabled = !row.busy }
            if (job == null) return
            val local = !job.mediaUri.startsWith("http")
            buttons["watch"]!!.apply { visibility = if (local) View.VISIBLE else View.GONE; text = if (job.audioPath != null) "Watch in English" else "Watch · English audio preferred" }
            val editable = job.state !in listOf("preparing", "downloading")
            buttons["prepare"]!!.apply { visibility = if (local && editable) View.VISIBLE else View.GONE; text = if (job.state in listOf("paused", "failed")) "Resume preparation" else "Prepare English dub" }
            buttons["subtitles"]!!.apply { visibility = if (local && editable) View.VISIBLE else View.GONE; text = if (job.subtitleUri == null) "Add SRT / WebVTT subtitles" else "Replace subtitle file" }
            buttons["download"]!!.apply { visibility = if (!local && editable) View.VISIBLE else View.GONE; text = "Download / resume" }
            buttons["remove"]!!.apply { visibility = if (!row.busy) View.VISIBLE else View.GONE; text = "Remove from library" }
        }
    }
}
