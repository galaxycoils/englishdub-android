package app.englishdub.ui

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.englishdub.DubApplication
import app.englishdub.data.DubJob
import app.englishdub.data.PreservedRange
import app.englishdub.data.SavedCue
import app.englishdub.engine.AudioTools
import app.englishdub.engine.DubTiming
import app.englishdub.engine.PreviewCueStore
import app.englishdub.engine.ResourcePolicy
import app.englishdub.engine.SongRangePolicy
import app.englishdub.engine.Subtitles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private val app get() = application as DubApplication
    private var video: ExoPlayer? = null
    private var dub: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var caption: TextView
    private lateinit var volumeLabel: TextView
    private lateinit var volumeSeek: SeekBar
    private lateinit var captionControlButton: Button
    private var job: DubJob? = null
    private var cues = emptyList<SavedCue>()
    private var preservedRanges = emptyList<PreservedRange>()
    private var selectedAudioOrdinal = -1
    private var audioOverrideApplied = false
    private var position = 0L
    private var hasExplicitPosition = false
    private var previewMode = false
    private var previewOffsetMs = 0L
    private var previewEndMs = Long.MAX_VALUE
    private var playOnStart = true
    private var english = true
    private var backgroundVolume = 0.15f
    private var captionsEnabled = true
    private var captionSizeSp = 16f
    private var active = false
    // Listener callbacks can be deferred while another listener event is dispatching.
    // A stack-scoped guard cannot identify our own pause/resume acknowledgements.
    private val pendingPlayChanges = ArrayDeque<Boolean>()
    private var waitingForDub = false
    private val handler = Handler(Looper.getMainLooper())
    private var seekAt = 0L
    private val tick = object : Runnable {
        override fun run() { synchronize(); scheduleTick() }
    }
    private fun scheduleTick() {
        handler.removeCallbacks(tick)
        val playing = active && english && dub != null && (video?.isPlaying == true || dub?.isPlaying == true)
        val delay = ResourcePolicy.pollingMs(playing, android.os.SystemClock.elapsedRealtime() - seekAt)
        if (delay != null) handler.postDelayed(tick, delay)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previewMode = intent.getBooleanExtra("preview", false)
        val startMsExtra = intent.getLongExtra("startMs", -1L)
        if (savedInstanceState != null) {
            position = savedInstanceState.getLong("position")
            hasExplicitPosition = true
            english = savedInstanceState.getBoolean("english", true)
            captionsEnabled = savedInstanceState.getBoolean("captionsEnabled", true)
            captionSizeSp = savedInstanceState.getFloat("captionSizeSp", 16f)
        } else if (startMsExtra >= 0L) {
            position = startMsExtra
            hasExplicitPosition = true
        }
        val prefs = getSharedPreferences("playback", MODE_PRIVATE)
        backgroundVolume = prefs.getFloat("backgroundVolume", 0.15f)
        val layout = column().apply { setBackgroundColor(canvasColor) }
        val title = label("EnglishDub", 18f, mint, true).apply { setPadding(dp(18), dp(8), dp(18), dp(8)) }
        layout.addView(title)
        playerView = PlayerView(this).apply { setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING) }
        layout.addView(playerView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val controls = column().apply { setPadding(dp(18), dp(8), dp(18), dp(14)) }
        caption = label("", captionSizeSp, ink).apply { contentDescription = "English captions" }
        controls.addView(caption)
        val toggle = Switch(this).apply {
            text = if (previewMode) "60s preview dub (toggle to compare original)" else "Prepared English dub"
            contentDescription = text
            setTextColor(ink); minHeight = dp(48); isChecked = english
            setOnCheckedChangeListener { _, checked -> english = checked; synchronize(force = true) }
        }
        controls.addView(toggle)
        volumeLabel = label("Original audio during speech · ${(backgroundVolume * 100).toInt()}%", 13f, muted)
        controls.addView(volumeLabel)
        volumeSeek = SeekBar(this).apply {
            max = 100; progress = (backgroundVolume * 100).toInt()
            contentDescription = "Original audio volume during English speech"
            minimumHeight = dp(48)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    backgroundVolume = progress / 100f
                    volumeLabel.text = "Original audio during speech · $progress%"
                    prefs.edit().putFloat("backgroundVolume", backgroundVolume).apply()
                    persistPreferences()
                    synchronize()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        controls.addView(volumeSeek)
        val actionRow = row()
        val replayButton = button("Replay last dialogue") {
            val master = video ?: return@button
            val target = DubTiming.replayTargetMs(cues, master.currentPosition, job?.subtitleOffsetMs ?: 0L)
            master.seekTo(target.coerceAtLeast(if (previewMode) previewOffsetMs else 0L))
            synchronize(force = true)
        }
        actionRow.addView(replayButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        captionControlButton = button(captionButtonText()) {
            val sizes = listOf(14f, 16f, 20f, 24f)
            if (!captionsEnabled) {
                captionsEnabled = true
                captionSizeSp = sizes.first()
            } else {
                val idx = sizes.indexOfFirst { kotlin.math.abs(it - captionSizeSp) < 0.5f }
                if (idx in 0 until sizes.lastIndex) {
                    captionSizeSp = sizes[idx + 1]
                } else {
                    captionsEnabled = false
                }
            }
            applyCaptionStyle()
            persistPreferences()
            synchronize()
        }
        actionRow.addView(captionControlButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(actionRow)
        val speedButton = button("Speed · 1×") { }
        speedButton.setOnClickListener {
            val speeds = listOf(1f, 1.25f, 1.5f, 2f)
            val current = video?.playbackParameters?.speed ?: 1f
            val speed = speeds[(speeds.indexOf(current) + 1) % speeds.size]
            video?.setPlaybackSpeed(speed); dub?.setPlaybackSpeed(speed); speedButton.text = "Speed · ${speed}×"
            speedButton.contentDescription = speedButton.text
        }
        controls.addView(speedButton)
        layout.addView(controls)
        setContentView(layout)
        ViewCompat.setOnApplyWindowInsetsListener(layout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()); view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        lifecycleScope.launch {
            val item = app.jobs.get(intent.getStringExtra("id").orEmpty())
            if (item == null) { finish(); return@launch }
            job = item
            val dbCues = app.jobs.cues(item.id)
            val previewCues = withContext(Dispatchers.IO) { PreviewCueStore.load(app.jobDirectory(item.id)) }
            cues = if (previewMode) previewCues.ifEmpty { dbCues } else dbCues.ifEmpty { previewCues }
            preservedRanges = app.jobs.preservedRanges(item.id)
            if (item.audioTrackIndex >= 0) {
                selectedAudioOrdinal = withContext(Dispatchers.IO) {
                    runCatching {
                        AudioTools.inspectTracks(this@PlayerActivity, Uri.parse(item.mediaUri))
                            .filter { it.kind == "audio" }
                            .indexOfFirst { it.index == item.audioTrackIndex }
                    }.getOrDefault(-1)
                }
            }
            title.text = if (previewMode) "${item.title} · 60s Preview" else item.title
            backgroundVolume = item.backgroundVolume
            volumeSeek.progress = (backgroundVolume * 100).toInt()
            volumeLabel.text = "Original audio during speech · ${(backgroundVolume * 100).toInt()}%"
            if (savedInstanceState == null) {
                captionsEnabled = item.captionsEnabled
                captionSizeSp = item.captionSizeSp.coerceIn(12f, 28f)
                if (previewMode) {
                    previewOffsetMs = item.previewStartMs
                    previewEndMs = if (item.durationMs > 0) minOf(item.durationMs, previewOffsetMs + 60_000L) else previewOffsetMs + 60_000L
                    position = previewOffsetMs
                } else if (!hasExplicitPosition && item.playbackPositionMs > 0L && (item.durationMs <= 15_000L || item.playbackPositionMs < item.durationMs - 2000L)) {
                    if (item.durationMs > 15_000L) position = item.playbackPositionMs
                }
            }
            applyCaptionStyle()
            val activeAudioPath = if (previewMode) item.previewAudioPath else item.audioPath
            val hasDubFile = activeAudioPath?.let { File(it).exists() } == true
            toggle.isEnabled = hasDubFile
            if (!hasDubFile) {
                toggle.isChecked = false
                toggle.text = "English audio preferred · original playback"
                caption.text = "Using the video’s available audio tracks."
            }
            if (active) openPlayers()
        }
    }

    private fun captionButtonText(): String =
        if (captionsEnabled) "Captions · On (${captionSizeSp.toInt()}sp)" else "Captions · Off"

    private fun applyCaptionStyle() {
        caption.textSize = captionSizeSp
        caption.visibility = if (captionsEnabled) View.VISIBLE else View.INVISIBLE
        if (::captionControlButton.isInitialized) {
            captionControlButton.text = captionButtonText()
            captionControlButton.contentDescription = captionControlButton.text
        }
    }

    private fun persistPreferences() {
        val item = job ?: return
        val currentPos = video?.currentPosition ?: position
        job = item.copy(
            backgroundVolume = backgroundVolume,
            captionsEnabled = captionsEnabled,
            captionSizeSp = captionSizeSp,
            playbackPositionMs = currentPos,
        )
        lifecycleScope.launch {
            app.jobs.updateViewingPreferences(item.id, backgroundVolume, captionsEnabled, captionSizeSp, currentPos, item.voiceName)
        }
    }

    private fun openPlayers() {
        if (video != null) return
        val item = job ?: return
        audioOverrideApplied = false
        seekAt = android.os.SystemClock.elapsedRealtime()
        video = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            setHandleAudioBecomingNoisy(true)
            if (item.audioTrackIndex == -1 && !item.overrideRecommendation) {
                trackSelectionParameters = trackSelectionParameters.buildUpon().setPreferredAudioLanguages("en", "eng").build()
            }
            setMediaItem(MediaItem.fromUri(Uri.parse(item.mediaUri)))
            seekTo(position); prepare()
            addListener(object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    if (!audioOverrideApplied && selectedAudioOrdinal >= 0) {
                        val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                        var seen = 0
                        for (group in audioGroups) {
                            for (trackIdx in 0 until group.length) {
                                if (seen == selectedAudioOrdinal) {
                                    audioOverrideApplied = true
                                    trackSelectionParameters = trackSelectionParameters.buildUpon()
                                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIdx))
                                        .build()
                                    return
                                }
                                seen++
                            }
                        }
                    }
                }
                override fun onEvents(player: Player, events: Player.Events) { synchronize(); scheduleTick() }
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) { seekAt = android.os.SystemClock.elapsedRealtime(); synchronize(force = true); scheduleTick() }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (pendingPlayChanges.firstOrNull() == playWhenReady) pendingPlayChanges.removeFirst()
                    else if (!playWhenReady) waitingForDub = false
                    if (playWhenReady) seekAt = android.os.SystemClock.elapsedRealtime()
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) seekAt = android.os.SystemClock.elapsedRealtime()
                }
                override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) { seekAt = android.os.SystemClock.elapsedRealtime() }
                override fun onPlayerError(error: PlaybackException) {
                    Toast.makeText(this@PlayerActivity, "This video cannot be played by your phone’s decoder. Try another file or audio track.", Toast.LENGTH_LONG).show()
                }
            })
        }
        playerView.player = video
        val dubPath = (if (previewMode) item.previewAudioPath else item.audioPath)?.takeIf { File(it).exists() }
        dubPath?.let { path ->
            dub = ExoPlayer.Builder(this).build().apply {
                setAudioAttributes(AudioAttributes.DEFAULT, false)
                setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
                seekTo((position - if (previewMode) previewOffsetMs else 0L).coerceAtLeast(0L))
                prepare()
                addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) { synchronize(); scheduleTick() }
                    override fun onPlayerError(error: PlaybackException) {
                        english = false; video?.volume = 1f
                        caption.text = "Saved dub could not be played. Original audio is active."
                    }
                })
            }
        }
        video?.playWhenReady = playOnStart
        handler.removeCallbacks(tick); handler.post(tick)
    }

    private var syncing = false
    private fun setMasterPlaying(playing: Boolean) {
        val master = video ?: return
        if (master.playWhenReady == playing) return
        pendingPlayChanges.addLast(playing)
        master.playWhenReady = playing
    }
    private fun synchronize(force: Boolean = false) {
        if (syncing) return
        val master = video ?: return
        playerView.keepScreenOn = master.isPlaying
        val secondary = dub ?: return
        syncing = true
        try {
            if (previewMode && master.currentPosition >= previewEndMs && master.isPlaying) {
                setMasterPlaying(false)
                secondary.pause()
            }
            if (!english) {
                secondary.pause(); master.volume = 1f; caption.text = "Original audio"
                if (waitingForDub) { waitingForDub = false; setMasterPlaying(true) }
                return
            }
            val offset = if (previewMode) previewOffsetMs else 0L
            val expectedDubPosition = (master.currentPosition - offset).coerceAtLeast(0L)
            val atVideoEnd = master.playbackState == Player.STATE_ENDED
            val drifted = master.isPlaying && !atVideoEnd && DubTiming.needsResync(expectedDubPosition, secondary.currentPosition)
            if (force || drifted) {
                val resume = master.playWhenReady || waitingForDub
                if (secondary.playWhenReady) secondary.pause()
                waitingForDub = resume
                setMasterPlaying(false)
                // Freeze the master first: decoder seek latency must not move the target away.
                secondary.seekTo(expectedDubPosition)
                seekAt = android.os.SystemClock.elapsedRealtime()
            }
            if (secondary.playbackParameters != master.playbackParameters) secondary.playbackParameters = master.playbackParameters
            val dubWaiting = secondary.playbackState == Player.STATE_BUFFERING
            if (dubWaiting && master.playWhenReady) {
                waitingForDub = true; setMasterPlaying(false)
            } else if (!dubWaiting && waitingForDub) {
                waitingForDub = false; setMasterPlaying(true)
            }
            val shouldPlay = master.playWhenReady && (master.isPlaying || atVideoEnd) && secondary.playbackState != Player.STATE_ENDED
            if (secondary.playWhenReady != shouldPlay) secondary.playWhenReady = shouldPlay
            if (atVideoEnd) playerView.keepScreenOn = secondary.isPlaying
            val time = if (atVideoEnd) secondary.currentPosition + offset else master.currentPosition
            val subtitleOffset = job?.subtitleOffsetMs ?: 0L
            val spokenCue = findSpokenCue(time)
            val displayCue = spokenCue ?: findEffectiveCue(time, subtitleOffset)
            val inPreservedSong = SongRangePolicy.isPreserved(time, preservedRanges)
            val preserveOriginalAudio = inPreservedSong || spokenCue == null || spokenCue.subtitlesOnly
            val volume = if (preserveOriginalAudio) 1f else backgroundVolume
            if (master.volume != volume) master.volume = volume
            val text = if (!captionsEnabled) "" else displayCue?.effectiveEnglish().orEmpty()
            if (caption.text.toString() != text) caption.text = text
        } finally { syncing = false }
    }

    private fun findSpokenCue(timeMs: Long): SavedCue? {
        var low = 0; var high = cues.lastIndex; var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (cues[mid].spokenStartMs <= timeMs) { found = mid; low = mid + 1 } else high = mid - 1
        }
        return cues.getOrNull(found)?.takeIf { !it.subtitlesOnly && it.spokenEndMs > it.spokenStartMs && timeMs < it.spokenEndMs }
    }

    private fun findEffectiveCue(timeMs: Long, subtitleOffsetMs: Long): SavedCue? =
        cues.firstOrNull { timeMs in it.effectiveStartMs(subtitleOffsetMs) until it.effectiveEndMs(subtitleOffsetMs) }

    override fun onStart() { super.onStart(); active = true; if (job != null) openPlayers() }
    override fun onStop() {
        active = false; handler.removeCallbacks(tick)
        video?.let {
            position = it.currentPosition
            playOnStart = it.playWhenReady || waitingForDub
            job?.let { item ->
                if (!previewMode) lifecycleScope.launch { app.jobs.savePlaybackPosition(item.id, position) }
            }
        }
        waitingForDub = false
        playerView.player = null
        val oldDub = dub; val oldVideo = video
        dub = null; video = null
        oldDub?.release(); oldVideo?.release()
        pendingPlayChanges.clear()
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("position", video?.currentPosition ?: position)
        outState.putBoolean("english", english)
        outState.putBoolean("captionsEnabled", captionsEnabled)
        outState.putFloat("captionSizeSp", captionSizeSp)
        super.onSaveInstanceState(outState)
    }
}

