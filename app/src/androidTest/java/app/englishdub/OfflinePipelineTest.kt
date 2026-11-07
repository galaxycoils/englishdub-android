package app.englishdub

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import app.englishdub.data.DubDatabase
import app.englishdub.data.SavedCue
import app.englishdub.data.DubJob
import app.englishdub.data.PronunciationEntry
import app.englishdub.data.PreservedRange
import app.englishdub.ui.MainActivity
import app.englishdub.engine.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class OfflinePipelineTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(name: String): File {
        val target = File(context.filesDir, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }
    private suspend fun runPreparation(id: String): DubJob {
        val app = context.applicationContext as DubApplication
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(android.content.Intent(context, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            PreparationService.start(context, PreparationService.PREPARE, id)
            withTimeout(120000) { PreparationService.status.first { !it.busy } }
            val result = app.jobs.get(id)!!
            assertEquals(result.message, "ready", result.state)
            return result
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    @Test fun englishAudioSkipsDubbingPreparation() = runBlocking {
        val app = context.applicationContext as DubApplication; app.recovery.await()
        val file = fixture("english-fixture.mp4")
        val id = "qa-english-audio"; app.jobs.delete(id)
        app.jobs.save(DubJob(id, "QA existing English audio", Uri.fromFile(file).toString(), sourceLanguage = "hi", mode = "speech"))
        val result = runPreparation(id)
        assertNull(result.audioPath); assertTrue(result.message.contains("English audio"))
    }
    @Test fun subtitlePreparationSavesPlayableEnglishDub() = runBlocking {
        val voice = try { OfflineVoice.create(context) } catch (error: IllegalStateException) {
            assumeTrue("Offline voice needed for end-to-end preparation: ${error.message}", false); return@runBlocking
        }
        voice.close()
        val app = context.applicationContext as DubApplication; app.recovery.await()
        val movie = fixture("offline-fixture.mp4")
        val subtitle = File(context.filesDir, "qa-subtitles.srt").apply { writeText("1\n00:00:01,000 --> 00:00:05,000\nThis is an offline English dub.\n") }
        val id = "qa-offline-subtitles"; app.jobs.delete(id); app.jobDirectory(id).deleteRecursively()
        app.jobs.save(DubJob(id, "QA offline English dub", Uri.fromFile(movie).toString(), Uri.fromFile(subtitle).toString()))
        val result = runPreparation(id)
        assertNotNull(result.audioPath); assertTrue(File(result.audioPath!!).length() > 0)
        assertTrue(AudioTools.duration(context, Uri.fromFile(File(result.audioPath))) >= 5900)
        assertTrue(app.jobs.cues(id).single().english!!.contains("offline"))
        assertFalse(File(app.jobDirectory(id), "cue-audio").exists())
    }
    @Test fun scenePreviewAndDialogueRebuildReuseSpeechAssetsAndPreserveDub() = runBlocking {
        val voice = try { OfflineVoice.create(context) } catch (error: IllegalStateException) {
            assumeTrue("Offline voice needed for preview and rebuild: ${error.message}", false); return@runBlocking
        }
        voice.close()
        val app = context.applicationContext as DubApplication; app.recovery.await()
        val movie = fixture("offline-fixture.mp4")
        val subtitle = File(context.filesDir, "qa-03-subtitles.srt").apply {
            writeText("1\n00:00:00,500 --> 00:00:02,500\nWelcome to Varanasi.\n\n2\n00:00:03,000 --> 00:00:05,500\nThis song is preserved.\n")
        }
        val id = "qa-03-preview-rebuild"
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(android.content.Intent(context, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            app.jobs.delete(id); app.jobDirectory(id).deleteRecursively()
            app.jobs.save(DubJob(id, "QA 0.3 Preview & Rebuild", Uri.fromFile(movie).toString(), Uri.fromFile(subtitle).toString()))
            // 1. Run 60-second scene preview
            PreparationService.start(context, PreparationService.PREVIEW, id, previewStartMs = 0L)
            withTimeout(120000) { PreparationService.status.first { !it.busy } }
            val afterPreview = app.jobs.get(id)!!
            assertEquals(afterPreview.previewMessage, "ready", afterPreview.previewState)
            assertNotNull(afterPreview.previewAudioPath)
            assertTrue(File(afterPreview.previewAudioPath!!).length() > 0)
            assertTrue(afterPreview.previewMessage.contains("(estimate)"))
            assertEquals(2, PreviewCueStore.load(app.jobDirectory(id)).size)
            // 2. Run full preparation
            PreparationService.start(context, PreparationService.PREPARE, id)
            withTimeout(120000) { PreparationService.status.first { !it.busy } }
            val afterPrep = app.jobs.get(id)!!
            assertEquals(afterPrep.message, "ready", afterPrep.state)
            assertNotNull(afterPrep.audioPath)
            // 3. Edit cue 0, add pronunciation override, preserve song range on cue 1, and run REBUILD
            app.jobs.updateCueOverride(id, 0, "Welcome to Varanasi friend.", 600L, 2600L, false)
            app.jobs.savePronunciation(PronunciationEntry(id, "Varanasi", "Va-ra-na-si"))
            app.jobs.savePreservedRange(PreservedRange(id, 2800L, 5800L))
            PreparationService.start(context, PreparationService.REBUILD, id)
            withTimeout(120000) { PreparationService.status.first { !it.busy } }
            val afterRebuild = app.jobs.get(id)!!
            assertEquals(afterRebuild.message, "ready", afterRebuild.state)
            val rebuiltCues = app.jobs.cues(id)
            assertEquals("Welcome to Varanasi friend.", rebuiltCues[0].effectiveEnglish())
            assertNotNull(rebuiltCues[0].pcmPath)
            assertTrue(rebuiltCues[0].pcmPath!!.endsWith(".pcm.gz"))
            assertNull("Song range cue must suppress synthesized speech", rebuiltCues[1].pcmPath)
            val srtOut = java.io.ByteArrayOutputStream()
            Subtitles.exportSrt(rebuiltCues, 0L, srtOut)
            assertTrue(srtOut.toString("UTF-8").contains("Welcome to Varanasi friend."))
        } finally {
            app.jobs.delete(id); app.jobDirectory(id).deleteRecursively(); subtitle.delete()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
    @Test fun installedOfflineVoiceProducesAudio() = runBlocking {
        val voice = try { OfflineVoice.create(context) } catch (error: IllegalStateException) {
            assumeTrue("No offline voice on this target: ${error.message}", false); return@runBlocking
        }
        val wav = File(context.cacheDir, "voice-fixture.wav")
        val pcm = File(context.cacheDir, "voice-fixture.pcm")
        try {
            voice.use { it.synthesize("This English voice works offline.", wav) }
            assertTrue(AudioTools.wavToPcm(wav, pcm) > 200)
            assertTrue(pcm.length() > 6400)
        } finally { wav.delete(); pcm.delete() }
    }
    @Test fun migrationPreservesVersionOneLibrary() = runBlocking {
        val name = "migration-fixture.db"; context.deleteDatabase(name)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE jobs (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, mediaUri TEXT NOT NULL, subtitleUri TEXT, sourceLanguage TEXT NOT NULL, mode TEXT NOT NULL, state TEXT NOT NULL, progress INTEGER NOT NULL, message TEXT NOT NULL, audioPath TEXT, durationMs INTEGER NOT NULL, decodedUntilMs INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE cues (jobId TEXT NOT NULL, `index` INTEGER NOT NULL, startMs INTEGER NOT NULL, endMs INTEGER NOT NULL, text TEXT NOT NULL, english TEXT, pcmPath TEXT, rate INTEGER NOT NULL, spokenStartMs INTEGER NOT NULL, spokenEndMs INTEGER NOT NULL, PRIMARY KEY(jobId, `index`), FOREIGN KEY(jobId) REFERENCES jobs(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("INSERT INTO jobs VALUES ('saved','My film','file:///film.mp4',NULL,'en','subtitles','ready',100,'Ready','/saved/english.m4a',3000,0,1234)")
            db.version = 1
        }
        val database = Room.databaseBuilder(context, DubDatabase::class.java, name).addMigrations(*DubDatabase.ALL_MIGRATIONS).build()
        try {
            val job = database.jobs().get("saved")!!
            assertEquals("My film", job.title); assertEquals("/saved/english.m4a", job.audioPath)
            assertNull(job.preparationSignature); assertNull(job.voiceSignature)
            assertEquals(-1, job.audioTrackIndex); assertEquals(0L, job.subtitleOffsetMs)
            assertTrue(job.chargingOnly); assertTrue(job.captionsEnabled)
        } finally { database.close(); context.deleteDatabase(name) }
    }
    @Test fun migrationPreservesVersionTwoLibraryAndPersistsV3Entities() = runBlocking {
        val name = "migration-v2-fixture.db"; context.deleteDatabase(name)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE jobs (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, mediaUri TEXT NOT NULL, subtitleUri TEXT, sourceLanguage TEXT NOT NULL, mode TEXT NOT NULL, state TEXT NOT NULL, progress INTEGER NOT NULL, message TEXT NOT NULL, audioPath TEXT, durationMs INTEGER NOT NULL, decodedUntilMs INTEGER NOT NULL, createdAt INTEGER NOT NULL, preparationSignature TEXT DEFAULT NULL, voiceSignature TEXT DEFAULT NULL)")
            db.execSQL("CREATE TABLE cues (jobId TEXT NOT NULL, `index` INTEGER NOT NULL, startMs INTEGER NOT NULL, endMs INTEGER NOT NULL, text TEXT NOT NULL, english TEXT, pcmPath TEXT, rate INTEGER NOT NULL, spokenStartMs INTEGER NOT NULL, spokenEndMs INTEGER NOT NULL, PRIMARY KEY(jobId, `index`), FOREIGN KEY(jobId) REFERENCES jobs(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("INSERT INTO jobs VALUES ('saved2','Hindi Classic','file:///hindi.mp4',NULL,'hi','subtitles','ready',100,'Ready','/saved2/english.m4a',5000,5000,5678,'sig-prep','sig-voice')")
            db.execSQL("INSERT INTO cues VALUES ('saved2',0,1000,2500,'नमस्ते','Hello','/saved2/0.pcm',16000,1000,1400)")
            db.version = 2
        }
        val database = Room.databaseBuilder(context, DubDatabase::class.java, name).addMigrations(*DubDatabase.ALL_MIGRATIONS).build()
        try {
            val dao = database.jobs()
            val job = dao.get("saved2")!!
            assertEquals("Hindi Classic", job.title)
            assertEquals("/saved2/english.m4a", job.audioPath)
            assertEquals("sig-prep", job.preparationSignature)
            assertEquals("sig-voice", job.voiceSignature)
            val cue = dao.cues("saved2").single()
            assertEquals("Hello", cue.effectiveEnglish())
            assertEquals("नमस्ते", cue.originalTranscript("subtitles"))
            dao.updateCueOverride("saved2", 0, "Hello friend", 1200L, 2600L, false)
            dao.savePronunciation(PronunciationEntry("saved2", "friend", "frend"))
            dao.savePreservedRange(PreservedRange("saved2", 3000L, 4500L))
            val updatedCue = dao.cues("saved2").single()
            assertEquals("Hello friend", updatedCue.effectiveEnglish())
            assertEquals(1200L, updatedCue.effectiveStartMs())
            assertEquals(1, dao.pronunciations("saved2").size)
            assertEquals(1, dao.preservedRanges("saved2").size)
        } finally { database.close(); context.deleteDatabase(name) }
    }
    @Test fun sourceIdentityIsStableAcrossDescriptorReopens() {
        val source = File(context.cacheDir, "identity-fixture").apply { writeBytes(ByteArray(131072) { (it % 127).toByte() }) }
        try {
            val first = PreparationIdentity.source(context, Uri.fromFile(source))
            assertNotNull(first); assertEquals(first, PreparationIdentity.source(context, Uri.fromFile(source)))
            source.appendText("changed")
            assertNotEquals(first, PreparationIdentity.source(context, Uri.fromFile(source)))
        } finally { source.delete() }
    }
    @Test fun aacTrackPreservesSilenceAndSpeechTimes() = runBlocking {
        val directory = File(context.cacheDir, "aac-fixture").apply { mkdirs() }
        try {
            val pcm = File(directory, "speech.pcm")
            val data = ByteBuffer.allocate(16000).order(ByteOrder.LITTLE_ENDIAN)
            repeat(8000) { i -> data.putShort((kotlin.math.sin(i * 2.0 * Math.PI * 440 / 16000) * 16000).toInt().toShort()) }
            pcm.writeBytes(data.array())
            val cue = SavedCue("test", 0, 1000, 1500, "tone", "tone", pcm.path, 16000, 1000, 1500)
            val output = File(directory, "dub.m4a")
            AacTimeline.encode(listOf(cue), 3000, output) { }
            assertTrue(output.length() > 0)
            assertTrue(AudioTools.duration(context, Uri.fromFile(output)) in 2900..3250)
            var quietEnergy = 0.0; var speechEnergy = 0.0; var quietCount = 0; var speechCount = 0
            AudioTools.decodeChunks(context, Uri.fromFile(output), 0) { start, audio, count, _ ->
                val samples = audio.order(ByteOrder.nativeOrder()).asFloatBuffer()
                for (i in 0 until count) {
                    val time = start + i * 1000L / 16000; val value = samples.get(i)
                    if (time in 200..800 || time in 2200..2800) { quietEnergy += value * value; quietCount++ }
                    if (time in 1100..1400) { speechEnergy += value * value; speechCount++ }
                }
            }
            assertTrue(quietCount > 0 && speechCount > 0)
            assertTrue(quietEnergy / quietCount < 0.002); assertTrue(speechEnergy / speechCount > 0.02)
        } finally { directory.deleteRecursively() }
    }
    @Test fun nativeCancellationReleasesVerifiedModel() = runBlocking {
        val model = verifiedModel()
        val buffer = ByteBuffer.allocateDirect(16000 * 4).order(ByteOrder.nativeOrder())
        WhisperBridge(model.path, 1).use { engine ->
            engine.abort()
            try { engine.translate(buffer, 16000, "en"); fail("An aborted inference must stop") }
            catch (error: IllegalStateException) { assertTrue(error.message.orEmpty().contains("stopped")) }
        }
    }
    private fun verifiedModel(): File {
        val model = File(context.filesDir, "fixture-model.bin")
        assumeTrue("Install the verified model fixture through adb before native smoke testing", model.length() == ModelStore.SIZE)
        val hash = java.security.MessageDigest.getInstance("SHA-256")
        model.inputStream().use { input ->
            val block = ByteArray(65536)
            while (true) { val n = input.read(block); if (n < 0) break; hash.update(block, 0, n) }
        }
        assertEquals("Fixture must match the verified production pack", ModelStore.SHA256, hash.digest().joinToString("") { "%02x".format(it) })
        return model
    }
    @LargeTest
    @Test fun nativeWhisperReadsReusableDirectBuffer() = runBlocking {
        val model = verifiedModel()
        val wave = File(context.cacheDir, "jfk.wav")
        val pcm = File(context.cacheDir, "jfk.pcm")
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("jfk.wav").use { input -> wave.outputStream().use { input.copyTo(it) } }
            AudioTools.wavToPcm(wave, pcm)
            val bytes = ByteBuffer.wrap(pcm.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val count = bytes.remaining() / 2
            val direct = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder())
            val floats = direct.asFloatBuffer(); repeat(count) { floats.put(bytes.short / 32768f) }
            WhisperBridge(model.path, 1).use { engine ->
                val text = engine.translate(direct, count, "en").joinToString(" ") { it.text }.lowercase()
                assertTrue(text.contains("country")); assertTrue(text.contains("americans"))
            }
        } finally { wave.delete(); pcm.delete() }
    }
}
