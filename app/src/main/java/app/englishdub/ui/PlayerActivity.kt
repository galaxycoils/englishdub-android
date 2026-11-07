package app.englishdub.ui

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.WindowManager
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
import app.englishdub.data.SavedCue
import app.englishdub.engine.DubTiming
import app.englishdub.engine.ResourcePolicy
import kotlinx.coroutines.launch
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private val app get() = application as DubApplication
    private var video: ExoPlayer? = null
    private var dub: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var caption: TextView
    private lateinit var volumeLabel: TextView
    private var job: DubJob? = null
    private var cues = emptyList<SavedCue>()
    private var position = 0L
    private var playOnStart = true
    private var english = true
    private var backgroundVolume = 0.15f
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
        position = savedInstanceState?.getLong("position") ?: 0
        english = savedInstanceState?.getBoolean("english") ?: true
        val prefs = getSharedPreferences("playback", MODE_PRIVATE)
        backgroundVolume = prefs.getFloat("backgroundVolume", 0.15f)
        val layout = column().apply { setBackgroundColor(canvasColor) }
        val title = label("EnglishDub", 18f, mint, true).apply { setPadding(dp(18), dp(8), dp(18), dp(8)) }
        layout.addView(title)
        playerView = PlayerView(this).apply { setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING) }
        layout.addView(playerView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val controls = column().apply { setPadding(dp(18), dp(8), dp(18), dp(14)) }
        caption = label("", 15f, muted)
        controls.addView(caption)
        val toggle = Switch(this).apply { text = "Prepared English dub"; setTextColor(ink); isChecked = english
            setOnCheckedChangeListener { _, checked -> english = checked; synchronize(force = true) } }
        controls.addView(toggle)
        volumeLabel = label("Original audio during speech · ${(backgroundVolume * 100).toInt()}%", 13f, muted)
        controls.addView(volumeLabel)
        controls.addView(SeekBar(this).apply {
            max = 100; progress = (backgroundVolume * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    backgroundVolume = progress / 100f
                    volumeLabel.text = "Original audio during speech · $progress%"
                    prefs.edit().putFloat("backgroundVolume", backgroundVolume).apply(); synchronize()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        })
        val speedButton = button("Speed · 1×") { }
        speedButton.setOnClickListener {
            val speeds = listOf(1f, 1.25f, 1.5f, 2f)
            val current = video?.playbackParameters?.speed ?: 1f
            val speed = speeds[(speeds.indexOf(current) + 1) % speeds.size]
            video?.setPlaybackSpeed(speed); dub?.setPlaybackSpeed(speed); speedButton.text = "Speed · ${speed}×"
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
            job = item; cues = app.jobs.cues(item.id); title.text = item.title
            toggle.isEnabled = item.audioPath != null
            if (item.audioPath == null) { toggle.isChecked = false; toggle.text = "English audio preferred · original playback"; caption.text = "Using the video’s available audio tracks." }
            if (active) openPlayers()
        }
    }

    private fun openPlayers() {
        if (video != null) return
        val item = job ?: return
        seekAt = android.os.SystemClock.elapsedRealtime()
        video = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            setHandleAudioBecomingNoisy(true)
            trackSelectionParameters = trackSelectionParameters.buildUpon().setPreferredAudioLanguages("en", "eng").build()
            setMediaItem(MediaItem.fromUri(Uri.parse(item.mediaUri)))
            seekTo(position); prepare()
            addListener(object : Player.Listener {
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
        item.audioPath?.takeIf { File(it).exists() }?.let { path ->
            dub = ExoPlayer.Builder(this).build().apply {
                setAudioAttributes(AudioAttributes.DEFAULT, false)
                setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
                seekTo(position); prepare()
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
            if (!english) {
                secondary.pause(); master.volume = 1f; caption.text = "Original audio"
                if (waitingForDub) { waitingForDub = false; setMasterPlaying(true) }
                return
            }
            val atVideoEnd = master.playbackState == Player.STATE_ENDED
            val drifted = master.isPlaying && !atVideoEnd && DubTiming.needsResync(master.currentPosition, secondary.currentPosition)
            if (force || drifted) {
                val resume = master.playWhenReady || waitingForDub
                if (secondary.playWhenReady) secondary.pause()
                waitingForDub = resume
                setMasterPlaying(false)
                // Freeze the master first: decoder seek latency must not move the target away.
                secondary.seekTo(master.currentPosition)
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
            val time = if (atVideoEnd) secondary.currentPosition else master.currentPosition
            var low = 0; var high = cues.lastIndex; var found = -1
            while (low <= high) { val mid = (low + high) ushr 1; if (cues[mid].spokenStartMs <= time) { found = mid; low = mid + 1 } else high = mid - 1 }
            val cue = cues.getOrNull(found)?.takeIf { time < it.spokenEndMs }
            val volume = if (cue == null) 1f else backgroundVolume
            if (master.volume != volume) master.volume = volume
            val text = cue?.english.orEmpty()
            if (caption.text.toString() != text) caption.text = text
        } finally { syncing = false }
    }
    override fun onStart() { super.onStart(); active = true; if (job != null) openPlayers() }
    override fun onStop() {
        active = false; handler.removeCallbacks(tick)
        video?.let { position = it.currentPosition; playOnStart = it.playWhenReady || waitingForDub }
        waitingForDub = false
        playerView.player = null
        val oldDub = dub; val oldVideo = video
        dub = null; video = null
        oldDub?.release(); oldVideo?.release()
        pendingPlayChanges.clear()
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("position", video?.currentPosition ?: position); outState.putBoolean("english", english); super.onSaveInstanceState(outState)
    }
}
