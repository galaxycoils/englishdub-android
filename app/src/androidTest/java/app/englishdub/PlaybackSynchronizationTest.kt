package app.englishdub

import android.content.Intent
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.englishdub.ui.PlayerActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class PlaybackSynchronizationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        val failure = AtomicReference<Throwable?>()
        instrumentation.runOnMainSync { try { result.set(block()) } catch (error: Throwable) { failure.set(error) } }
        failure.get()?.let { throw it }
        return result.get()
    }
    @Test fun preparedDubTracksPlaybackPauseSeekAndSpeed() = runBlocking {
        val context = instrumentation.targetContext
        val app = context.applicationContext as DubApplication
        assumeTrue("Run offline preparation fixtures first", app.jobs.get("qa-offline-subtitles")?.audioPath != null)
        val activity = instrumentation.startActivitySync(Intent(context, PlayerActivity::class.java).putExtra("id", "qa-offline-subtitles").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlayerActivity
        fun player(name: String) = activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(activity) as? ExoPlayer
        val samples = mutableListOf("phase,video_ms,dub_ms,video_playing,dub_playing,video_requested,dub_requested,video_state,dub_state")
        fun sample(phase: String): Boolean = onMain {
            val v = player("video"); val d = player("dub")
            samples += "$phase,${v?.currentPosition},${d?.currentPosition},${v?.isPlaying},${d?.isPlaying},${v?.playWhenReady},${d?.playWhenReady},${v?.playbackState},${d?.playbackState}"
            v?.isPlaying == true && d?.isPlaying == true
        }
        suspend fun ready() = withTimeout(30000) {
            while (!sample("waiting")) delay(100)
        }
        try {
            ready(); delay(400)
            val first = onMain { player("video")!!.currentPosition }
            var maximumDrift = 0L
            repeat(8) {
                val positions = onMain { player("video")!!.currentPosition to player("dub")!!.currentPosition }
                sample("playback")
                val drift = kotlin.math.abs(positions.first - positions.second)
                maximumDrift = maxOf(maximumDrift, drift); delay(100)
            }
            assertTrue("Video clock did not advance", onMain { player("video")!!.currentPosition } >= first + 300)
            assertTrue("Observed drift $maximumDrift ms", maximumDrift <= 150)
            onMain { player("video")!!.pause() }; delay(200)
            assertFalse(onMain { player("dub")!!.isPlaying })
            onMain { player("video")!!.seekTo(2000); player("video")!!.setPlaybackSpeed(1.25f); player("video")!!.play() }
            ready(); delay(400)
            onMain {
                assertEquals(1.25f, player("dub")!!.playbackParameters.speed, 0.001f)
                assertTrue(kotlin.math.abs(player("video")!!.currentPosition - player("dub")!!.currentPosition) <= 150)
            }
        } finally {
            java.io.File(context.filesDir, "sync-samples.csv").writeText(samples.joinToString("\n"))
            onMain { activity.finish() }
        }
    }
}
