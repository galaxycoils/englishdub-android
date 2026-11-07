package app.englishdub

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.englishdub.engine.AacTimeline
import app.englishdub.ui.PlayerActivity
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real decoder/AudioTrack regressions; retain all transition samples, not just stable ones. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackRecoveryTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val app get() = context.applicationContext as DubApplication

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        val failure = AtomicReference<Throwable?>()
        instrumentation.runOnMainSync {
            try { result.set(block()) } catch (error: Throwable) { failure.set(error) }
        }
        failure.get()?.let { throw it }
        return result.get()
    }

    private class Playback(val activity: PlayerActivity) {
        fun player(name: String) = activity.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.get(activity) as? ExoPlayer
        val video get() = player("video")!!
        val dub get() = player("dub")!!
        val rows = mutableListOf("elapsed_ms,phase,video_ms,dub_ms,drift_ms,video_playing,dub_playing,video_requested,dub_requested,video_state,dub_state,video_suppression,speed")
        fun sample(phase: String): Boolean {
            val v = player("video"); val d = player("dub")
            val vp = v?.currentPosition; val dp = d?.currentPosition
            rows += "${SystemClock.elapsedRealtime()},$phase,$vp,$dp,${if (vp != null && dp != null) kotlin.math.abs(vp - dp) else null},${v?.isPlaying},${d?.isPlaying},${v?.playWhenReady},${d?.playWhenReady},${v?.playbackState},${d?.playbackState},${v?.playbackSuppressionReason},${v?.playbackParameters?.speed}"
            return v?.isPlaying == true && d?.isPlaying == true
        }
    }

    private suspend fun withPlayback(label: String, id: String = "qa-offline-subtitles", block: suspend (Playback) -> Unit) {
        assumeTrue("Run offline preparation fixtures first", app.jobs.get(id)?.audioPath != null)
        val activity = instrumentation.startActivitySync(Intent(context, PlayerActivity::class.java)
            .putExtra("id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlayerActivity
        val playback = Playback(activity)
        try {
            withTimeout(30000) { while (!onMain { playback.sample("startup") }) delay(50) }
            onMain {
                for ((name, player) in listOf("video" to playback.video, "dub" to playback.dub)) {
                    player.addListener(object : Player.Listener {
                        override fun onEvents(player: Player, events: Player.Events) { playback.sample("event-$name") }
                        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                            playback.sample("discontinuity-$name-$reason")
                        }
                    })
                }
            }
            block(playback)
        } finally {
            onMain { activity.finish() }
            File(context.filesDir, "$label.csv").writeText(playback.rows.joinToString("\n"))
        }
    }

    private suspend fun stable(playback: Playback, phase: String) {
        withTimeout(15000) { while (!onMain { playback.sample("waiting-$phase") }) delay(50) }
        val first = onMain { playback.video.currentPosition }
        repeat(12) {
            onMain {
                assertTrue("Both players must remain playing during $phase", playback.sample("stable-$phase"))
                assertTrue("$phase drift exceeds 150 ms", kotlin.math.abs(playback.video.currentPosition - playback.dub.currentPosition) <= 150)
            }
            delay(50)
        }
        assertTrue("Video stalled during $phase", onMain { playback.video.currentPosition } > first + 300)
    }

    @Test fun stalledDubFreezesVideoAndRecoversToLatestSeek() = runBlocking {
        val opened = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            withPlayback("sync-buffering") { playback ->
                val path = app.jobs.get("qa-offline-subtitles")!!.audioPath!!
                val source = ProgressiveMediaSource.Factory(DataSource.Factory {
                    val delegate = DefaultDataSource.Factory(context).createDataSource()
                    object : DataSource by delegate {
                        override fun open(dataSpec: DataSpec): Long {
                            opened.countDown()
                            check(release.await(10, TimeUnit.SECONDS)) { "Test must release the stalled local source" }
                            return delegate.open(dataSpec)
                        }
                    }
                }).createMediaSource(MediaItem.fromUri(Uri.fromFile(File(path))))
                onMain { playback.dub.setMediaSource(source); playback.dub.prepare(); playback.sample("command-stall") }
                assertTrue("Dub source did not begin loading", opened.await(5, TimeUnit.SECONDS))
                delay(150)
                val frozen = onMain { playback.sample("buffering"); playback.video.currentPosition }
                delay(350)
                onMain {
                    playback.sample("buffering-held")
                    assertFalse("Video must wait for the buffering dub", playback.video.isPlaying)
                    assertTrue("Video clock ran ahead while dub buffered", playback.video.currentPosition - frozen <= 150)
                    playback.video.seekTo(2500)
                    playback.sample("command-seek-2500")
                    playback.video.seekTo(1000)
                    playback.video.setPlaybackSpeed(1.25f)
                    playback.video.play()
                    playback.sample("command-seek-1000-speed-resume")
                }
                release.countDown()
                stable(playback, "buffer-recovery")
                onMain {
                    assertEquals(1.25f, playback.dub.playbackParameters.speed, 0.001f)
                    assertTrue("Recovery must use the latest seek", playback.video.currentPosition in 1000..2300)
                }
            }
        } finally { release.countDown() }
    }

    @Test fun repeatedSeeksPauseAndDubToggleKeepClocksAligned() = runBlocking {
        withPlayback("sync-controls") { playback ->
            for (position in listOf(2000L, 500L, 2500L)) {
                onMain { playback.video.seekTo(position); playback.sample("command-seek-$position") }
                stable(playback, "seek-$position")
            }
            onMain { playback.video.pause(); playback.sample("command-pause") }
            delay(200)
            val paused = onMain { playback.video.currentPosition to playback.dub.currentPosition }
            delay(250)
            onMain {
                playback.sample("paused")
                assertFalse(playback.video.isPlaying); assertFalse(playback.dub.isPlaying)
                assertTrue(kotlin.math.abs(playback.video.currentPosition - paused.first) <= 50)
                assertTrue(kotlin.math.abs(playback.dub.currentPosition - paused.second) <= 50)
                fun toggle(view: View): Switch? = when (view) {
                    is Switch -> view
                    is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { toggle(view.getChildAt(it)) }
                    else -> null
                }
                val toggle = toggle(playback.activity.findViewById(android.R.id.content))!!
                toggle.isChecked = false
                playback.video.seekTo(1000)
                playback.video.play()
                playback.sample("command-original")
            }
            delay(300)
            onMain {
                playback.sample("original")
                assertTrue(playback.video.isPlaying); assertFalse(playback.dub.isPlaying)
                fun enable(view: View) {
                    if (view is Switch) view.isChecked = true
                    if (view is ViewGroup) for (i in 0 until view.childCount) enable(view.getChildAt(i))
                }
                enable(playback.activity.findViewById(android.R.id.content))
            }
            stable(playback, "dub-restored")
        }
    }

    @Test fun finalDubFinishesOverLastVideoFrame() = runBlocking {
        val original = app.jobs.get("qa-offline-subtitles")
        assumeTrue("Run offline preparation fixtures first", original?.audioPath != null)
        val id = "qa-playback-overflow"
        val audio = File(context.cacheDir, "$id.m4a")
        try {
            AacTimeline.encode(emptyList(), 8000, audio) { }
            app.jobs.save(original!!.copy(id = id, audioPath = audio.path))
            withPlayback("sync-overflow", id) { playback ->
                onMain { playback.video.seekTo(5400); playback.sample("command-near-end") }
                withTimeout(10000) {
                    while (!onMain { playback.sample("waiting-video-end"); playback.video.playbackState == Player.STATE_ENDED && playback.dub.isPlaying }) delay(50)
                }
                val atEnd = onMain { playback.video.currentPosition to playback.dub.currentPosition }
                delay(400)
                onMain {
                    playback.sample("dub-tail")
                    assertEquals(atEnd.first, playback.video.currentPosition)
                    assertTrue("Final speech was cut off", playback.dub.currentPosition > atEnd.second + 250)
                }
                withTimeout(10000) { while (!onMain { playback.sample("waiting-dub-end"); playback.dub.playbackState == Player.STATE_ENDED }) delay(50) }
            }
        } finally { app.jobs.delete(id); audio.delete() }
    }
}
