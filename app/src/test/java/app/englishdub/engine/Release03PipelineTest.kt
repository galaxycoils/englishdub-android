package app.englishdub.engine

import app.englishdub.data.DubJob
import app.englishdub.data.PreservedRange
import app.englishdub.data.PronunciationEntry
import app.englishdub.data.SavedCue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class Release03PipelineTest {
    private fun writeWave(file: File, durationMs: Int = 400) {
        val samples = 16000 * durationMs / 1000
        val bytes = samples * 2
        val data = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()); data.putInt(36 + bytes); data.put("WAVEfmt ".toByteArray())
        data.putInt(16); data.putShort(1); data.putShort(1); data.putInt(16000)
        data.putInt(32000); data.putShort(2); data.putShort(16)
        data.put("data".toByteArray()); data.putInt(bytes)
        repeat(samples) { data.putShort(4096) }
        file.writeBytes(data.array())
    }

    @Test fun `unknown and ISO language codes normalize with Unknown language fallback`() {
        assertEquals("und" to "Unknown language", AudioTools.normalizeLanguage(null))
        assertEquals("und" to "Unknown language", AudioTools.normalizeLanguage(""))
        assertEquals("und" to "Unknown language", AudioTools.normalizeLanguage("und"))
        assertEquals("en" to "English", AudioTools.normalizeLanguage("eng"))
        assertEquals("hi" to "Hindi", AudioTools.normalizeLanguage("hin"))
        assertEquals("ur" to "Urdu", AudioTools.normalizeLanguage("urd"))
        assertEquals("ta" to "Tamil", AudioTools.normalizeLanguage("tam"))
    }

    @Test fun `source recommendation follows English audio then English subs then translated subs then speech`() {
        val hiAudio = MediaTrackInfo(0, "audio", "audio/mp4a-latm", "hi", "Hindi", "Audio track 1")
        val enAudio = MediaTrackInfo(1, "audio", "audio/mp4a-latm", "en", "English", "Audio track 2")
        val taSub = MediaTrackInfo(2, "subtitles", "application/x-subrip", "ta", "Tamil", "Subtitle track 1")
        val enSub = MediaTrackInfo(3, "subtitles", "text/vtt", "en", "English", "Subtitle track 2")

        val r1 = AudioTools.recommendSource(listOf(hiAudio, enAudio), listOf(taSub, enSub))
        assertEquals(RecommendedPath.ENGLISH_AUDIO, r1.path)
        assertEquals(1, r1.audioTrackIndex)

        val r2 = AudioTools.recommendSource(listOf(hiAudio), listOf(taSub, enSub))
        assertEquals(RecommendedPath.ENGLISH_SUBTITLES, r2.path)
        assertEquals(3, r2.subtitleTrackIndex)

        val r3 = AudioTools.recommendSource(listOf(hiAudio), listOf(taSub), preferredLanguage = "ta")
        assertEquals(RecommendedPath.TRANSLATED_SUBTITLES, r3.path)
        assertEquals("ta", r3.languageCode)
        assertEquals(2, r3.subtitleTrackIndex)

        val r4 = AudioTools.recommendSource(listOf(hiAudio), emptyList(), preferredLanguage = "hi")
        assertEquals(RecommendedPath.SPEECH_RECOGNITION, r4.path)
        assertEquals("hi", r4.languageCode)
        assertEquals(0, r4.audioTrackIndex)
    }

    @Test fun `preview range is 60 seconds and shortened at movie end`() {
        val mid = PreviewRange(30_000L)
        assertEquals(90_000L, mid.endMs(120_000L))
        val nearEnd = PreviewRange(100_000L)
        assertEquals(120_000L, nearEnd.endMs(120_000L))
        val estMs = ResourcePolicy.estimateFullPreparationMs(elapsedMs = 15_000L, processedMovieMs = 60_000L, totalMovieMs = 3_600_000L)
        assertEquals(900_000L, estMs)
        val label = ResourcePolicy.formatEstimateLabel(estMs)
        assertTrue(label.contains("15 min"))
        assertTrue(label.contains("(estimate)"))
    }

    @Test fun `subtitle offset shifts effective timestamps while preserving original timestamps`() {
        val cue = SavedCue("job1", 0, startMs = 2000L, endMs = 4000L, text = "नमस्ते", english = "Hello")
        assertEquals(2500L, cue.effectiveStartMs(500L))
        assertEquals(4500L, cue.effectiveEndMs(500L))
        assertEquals(0L, cue.effectiveStartMs(-3000L))
        assertEquals(1000L, cue.effectiveEndMs(-3000L))
        val previewShifted = Subtitles.applyOffset(listOf(TextCue(2000L, 4000L, "Hello")), -500L).single()
        assertEquals(1500L, previewShifted.startMs)
        assertEquals(3500L, previewShifted.endMs)
        // Original timestamps remain intact
        assertEquals(2000L, cue.startMs)
        assertEquals(4000L, cue.endMs)
    }

    @Test fun `speech translation never presents English output as original transcript`() {
        val speechCue = SavedCue("job1", 0, 1000L, 2500L, "", english = "Where are you going?", transcriptAvailable = false)
        val subtitleCue = SavedCue("job1", 1, 3000L, 4500L, "आप कहाँ जा रहे हैं?", english = "Where are you going?", transcriptAvailable = true)
        assertNull(speechCue.originalTranscript("speech"))
        assertNull(speechCue.originalTranscript("subtitles"))
        assertEquals("आप कहाँ जा रहे हैं?", subtitleCue.originalTranscript("subtitles"))
        assertNull(subtitleCue.originalTranscript("speech"))
    }

    @Test fun `pronunciation replacements apply longest first to spoken text only while captions retain spelling`() {
        val entries = listOf(
            PronunciationEntry("job1", "Vijay", "Vee-jay"),
            PronunciationEntry("job1", "Vijay Sethupathi", "Vee-jay Say-thoo-pathy"),
            PronunciationEntry("job1", "Kollywood", "Kolli-wood"),
        )
        val cue = SavedCue("job1", 0, 1000L, 3000L, "விஜய்", english = "Vijay Sethupathi stars in Kollywood films.")
        val spoken = Pronunciation.apply(cue.effectiveEnglish(), entries)
        assertEquals("Vee-jay Say-thoo-pathy stars in Kolli-wood films.", spoken)
        assertEquals("Vijay Sethupathi stars in Kollywood films.", cue.effectiveEnglish())
        val srt = Subtitles.formatSrt(listOf(cue))
        assertTrue(srt.contains("Vijay Sethupathi stars in Kollywood films."))
        assertFalse(srt.contains("Vee-jay"))
    }

    @Test fun `editing dialogue regenerates only changed speech and recalculates downstream timing without retranslating or falsely flagging legacy clips`() = runBlocking {
        val dir = Files.createTempDirectory("dub-edit-regen").toFile()
        try {
            val cache = CueAudioCache.reusable(dir, "voice-1")
            var translationsRun = 0
            val synthesizedLines = mutableListOf<String>()
            val initialCues = listOf(
                SavedCue("job1", 0, 1000L, 2000L, "पहला"),
                SavedCue("job1", 1, 2100L, 3500L, "दूसरा"),
                SavedCue("job1", 2, 3600L, 5000L, "तीसरा"),
            )
            // Initial preparation
            val firstPass = DialoguePipeline.synthesizeAndPlaceCues(
                cues = initialCues,
                subtitleOffsetMs = 0L,
                pronunciations = emptyList(),
                preservedRanges = emptyList(),
                cache = cache,
                translator = { src ->
                    translationsRun++
                    when (src) {
                        "पहला" -> "First line"
                        "दूसरा" -> "Second line"
                        else -> "Third line"
                    }
                },
                synthesize = { text, file, _ ->
                    synthesizedLines += text
                    writeWave(file, durationMs = if (text.startsWith("Edited long")) 1400 else 400)
                },
                sourceLanguage = "hi",
            )
            assertEquals(3, translationsRun)
            assertEquals(listOf("First line", "Second line", "Third line"), synthesizedLines)
            assertEquals(1400L, firstPass.cues[0].spokenEndMs)
            assertEquals(2100L, firstPass.cues[1].spokenStartMs)

            // Edit Cue 0 to a longer line that overflows into Cue 1's start window (preserving existing pcmPath until rebuild)
            synthesizedLines.clear()
            val editedCues = firstPass.cues.toMutableList()
            editedCues[0] = editedCues[0].copy(
                editedEnglish = "Edited long first line that shifts cue two",
            )
            val secondPass = DialoguePipeline.synthesizeAndPlaceCues(
                cues = editedCues,
                subtitleOffsetMs = 0L,
                pronunciations = emptyList(),
                preservedRanges = emptyList(),
                cache = cache,
                translator = { fail("Unchanged translations must not be repeated"); "" },
                synthesize = { text, file, _ ->
                    synthesizedLines += text
                    writeWave(file, durationMs = if (text.startsWith("Edited long")) 1400 else 400)
                },
                existingDubExists = true,
                sourceLanguage = "hi",
            )
            // Zero new translations; only the edited cue was synthesized; cues 1 & 2 reused cache; not falsely flagged as legacy dub!
            assertEquals(3, translationsRun)
            assertFalse("Editing a 0.3 cue must not falsely claim legacy clips were regenerated", secondPass.regeneratedLegacyClips)
            assertTrue(synthesizedLines.all { it.startsWith("Edited long") })
            assertEquals(2400L, secondPass.cues[0].spokenEndMs)
            // Downstream cue 1 timing was recalculated from 2100ms to 2400ms!
            assertEquals(2400L, secondPass.cues[1].spokenStartMs)
            assertEquals(2800L, secondPass.cues[1].spokenEndMs)
            assertTrue(secondPass.cues[1].hasTimingWarning())
            assertEquals(listOf(secondPass.cues[0], secondPass.cues[1]), Subtitles.searchCues(secondPass.cues, "warning", "subtitles"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun `scene preview persists preview cues for captions and reuses translations and speech clips during full preparation`() = runBlocking {
        val dir = Files.createTempDirectory("dub-preview-reuse").toFile()
        try {
            val speechDir = File(dir, "speech-cache")
            val jobDir = File(dir, "jobs/job1")
            val cache = CueAudioCache.reusable(speechDir, "voice-1")
            val translatedInputs = mutableListOf<String>()
            val synthesizedInputs = mutableListOf<String>()
            val previewStartMs = 90_000L
            // Cue 0 starts slightly before previewStartMs (at 89_000L) and overlaps into the 60s preview window
            val previewRawCues = listOf(
                SavedCue("job1", 0, 89_000L, 92_000L, "नमस्ते दोस्त"),
                SavedCue("job1", 1, 95_000L, 98_000L, "चलो चलते हैं"),
            )
            val previewPass = DialoguePipeline.synthesizeAndPlaceCues(
                cues = previewRawCues,
                subtitleOffsetMs = 0L,
                pronunciations = emptyList(),
                preservedRanges = emptyList(),
                cache = cache,
                translator = { src ->
                    translatedInputs += src
                    if (src == "नमस्ते दोस्त") "Hello friend" else "Let us go"
                },
                synthesize = { text, file, _ ->
                    synthesizedInputs += text
                    writeWave(file, 500)
                },
                initialPreviousEndMs = previewStartMs,
                sourceLanguage = "hi",
            )
            // Cue 0 must be placed at >= previewStartMs (90_000L) so shifting by -previewStartMs does not truncate it
            assertEquals(90_000L, previewPass.cues[0].spokenStartMs)
            assertEquals(90_500L, previewPass.cues[0].spokenEndMs)
            PreviewCueStore.save(jobDir, previewPass.cues)
            val loadedPreviewCues = PreviewCueStore.load(jobDir)
            assertEquals(2, loadedPreviewCues.size)
            assertEquals("Hello friend", loadedPreviewCues[0].effectiveEnglish())
            assertEquals(90_000L, loadedPreviewCues[0].spokenStartMs)

            // Now run full-movie preparation across 3 cues (including the 2 previewed cues) with a fresh CueAudioCache instance
            val fullMovieCues = listOf(
                SavedCue("job1", 0, 89_000L, 92_000L, "नमस्ते दोस्त"),
                SavedCue("job1", 1, 95_000L, 98_000L, "चलो चलते हैं"),
                SavedCue("job1", 2, 130_000L, 133_000L, "तीसरा संवाद"),
            )
            translatedInputs.clear()
            synthesizedInputs.clear()
            val fullPass = DialoguePipeline.synthesizeAndPlaceCues(
                cues = fullMovieCues,
                subtitleOffsetMs = 0L,
                pronunciations = emptyList(),
                preservedRanges = emptyList(),
                cache = CueAudioCache.reusable(speechDir, "voice-1"),
                translator = { src ->
                    translatedInputs += src
                    "Third dialogue"
                },
                synthesize = { text, file, _ ->
                    synthesizedInputs += text
                    writeWave(file, 500)
                },
                sourceLanguage = "hi",
            )
            // Only the 3rd cue (outside the 60s preview) was translated or synthesized; cues 0 and 1 reused preview translations & speech assets!
            assertEquals(listOf("तीसरा संवाद"), translatedInputs)
            assertEquals(listOf("Third dialogue"), synthesizedInputs)
            assertEquals("Hello friend", fullPass.cues[0].effectiveEnglish())
            assertEquals("Let us go", fullPass.cues[1].effectiveEnglish())
            assertEquals("Third dialogue", fullPass.cues[2].effectiveEnglish())
        } finally { dir.deleteRecursively() }
    }

    @Test fun `subtitles only cues and manual song ranges skip speech synthesis while preserving captions`() = runBlocking {
        val dir = Files.createTempDirectory("dub-song-subonly").toFile()
        try {
            val cache = CueAudioCache.reusable(dir, "voice-1")
            val synthesized = mutableListOf<String>()
            val cues = listOf(
                SavedCue("job1", 0, 1000L, 2000L, "line1", english = "Spoken dialogue"),
                SavedCue("job1", 1, 3000L, 4000L, "line2", english = "Subtitles only remark", subtitlesOnly = true),
                SavedCue("job1", 2, 10_000L, 12_000L, "song1", english = "Song lyric caption"),
            )
            val songRanges = listOf(PreservedRange("job1", 9_000L, 15_000L))
            val pass = DialoguePipeline.synthesizeAndPlaceCues(
                cues = cues,
                subtitleOffsetMs = 0L,
                pronunciations = emptyList(),
                preservedRanges = songRanges,
                cache = cache,
                translator = null,
                synthesize = { text, file, _ -> synthesized += text; writeWave(file, 300) },
            )
            assertEquals(listOf("Spoken dialogue"), synthesized)
            assertNotNull(pass.cues[0].pcmPath)
            assertNull(pass.cues[1].pcmPath)
            assertNull(pass.cues[2].pcmPath)
            assertEquals("Subtitles only remark", pass.cues[1].effectiveEnglish())
            assertEquals("Song lyric caption", pass.cues[2].effectiveEnglish())
        } finally { dir.deleteRecursively() }
    }

    @Test fun `legacy completed dub detects missing reusable speech clips and regenerates without retranslating`() = runBlocking {
        val dir = Files.createTempDirectory("dub-legacy").toFile()
        try {
            val cache = CueAudioCache.reusable(dir, "voice-1")
            val legacyCue = SavedCue("job1", 0, 1000L, 2000L, "नमस्ते", english = "Hello", pcmPath = File(dir, "deleted-legacy.pcm").path, spokenStartMs = 1000L, spokenEndMs = 1400L)
            val pass = DialoguePipeline.synthesizeAndPlaceCues(
                cues = listOf(legacyCue),
                subtitleOffsetMs = 0L,
                pronunciations = emptyList(),
                preservedRanges = emptyList(),
                cache = cache,
                translator = { fail("Must not retranslate legacy cue"); "" },
                synthesize = { _, file, _ -> writeWave(file, 300) },
                existingDubExists = true,
            )
            assertTrue("Must flag legacy clip regeneration", pass.regeneratedLegacyClips)
            assertTrue(File(pass.cues.single().pcmPath!!).exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun `failed regeneration preserves previous playable dub`() = runBlocking {
        val dir = Files.createTempDirectory("dub-atomic").toFile()
        try {
            val completed = File(dir, "english.m4a").apply { writeText("playable-previous-dub") }
            val partial = File(dir, "english.partial.m4a")
            try {
                DialoguePipeline.commitTimeline(emptyList(), 5000L, partial, completed) { _, _, out ->
                    out.writeText("corrupted-incomplete")
                    error("Encoder interrupted")
                }
                fail("Expected failure")
            } catch (_: IllegalStateException) {}
            assertEquals("playable-previous-dub", completed.readText())
            assertFalse(partial.exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun `timeline publication failure retains previous audio and recovery removes only orphan versions`() = runBlocking {
        val files = Files.createTempDirectory("dub-publish").toFile()
        val dir = File(files, "jobs/j1").apply { mkdirs() }
        try {
            val old = File(dir, "english.m4a").apply { writeText("old audio") }
            val original = File(dir, "video").apply { writeText("user movie") }
            try {
                DialoguePipeline.publishTimeline(emptyList(), 5000L, dir,
                    encode = { _, _, out -> out.writeText("new audio") },
                    publish = { out ->
                        assertTrue(out.exists())
                        assertEquals("old audio", old.readText())
                        error("Database transaction failed")
                    })
                fail("Publication failure must propagate")
            } catch (e: IllegalStateException) { assertEquals("Database transaction failed", e.message) }
            assertEquals(setOf("english.m4a", "video"), dir.listFiles()!!.map { it.name }.toSet())
            var publishedPath: String? = null
            val completed = DialoguePipeline.publishTimeline(emptyList(), 5000L, dir,
                encode = { _, _, out -> out.writeText("complete new audio") },
                publish = { out -> publishedPath = out.path; assertTrue(old.exists()) })
            assertEquals(completed.path, publishedPath)
            // Simulate process death after file completion but before database publication.
            val orphan = File(dir, "english-${java.util.UUID.randomUUID()}.m4a").apply { writeText("orphan") }
            assertEquals(old.length() + completed.length() + orphan.length(), ResourcePolicy.computeStorage(files).completedDubsBytes)
            ResourcePolicy.removeUnusedDubs(dir, publishedPath)
            assertFalse(orphan.exists())
            assertFalse(old.exists())
            assertEquals("complete new audio", completed.readText())
            assertEquals("user movie", original.readText())
            assertEquals(completed.length(), ResourcePolicy.computeStorage(files).completedDubsBytes)
            ResourcePolicy.clearCompletedDubs(files)
            assertFalse(completed.exists())
            assertTrue(original.exists())
        } finally { files.deleteRecursively() }
    }

    @Test fun `split stage signatures do not invalidate inference when captions or playback prefs change`() {
        val rec1 = PreparationIdentity.recognition("src1", 0, "hi", "model1", "no-vad")
        val trans1 = PreparationIdentity.translation(rec1, -1, "hi", "speech", "model1")
        val cue = SavedCue("job1", 0, 1000L, 2000L, "", english = "Hello")
        val time1 = PreparationIdentity.timeline(listOf(cue), 0L, emptyList(), emptyList(), "voice1", 10_000L)
        // Simulate changing captionsEnabled, captionSizeSp, backgroundVolume, playbackPositionMs on DubJob
        val rec2 = PreparationIdentity.recognition("src1", 0, "hi", "model1", "no-vad")
        val trans2 = PreparationIdentity.translation(rec2, -1, "hi", "speech", "model1")
        val time2 = PreparationIdentity.timeline(listOf(cue), 0L, emptyList(), emptyList(), "voice1", 10_000L)
        assertEquals(rec1, rec2)
        assertEquals(trans1, trans2)
        assertEquals(time1, time2)
        // Changing subtitle offset or pronunciation changes timeline signature without changing recognition or translation
        val timeOffsetChanged = PreparationIdentity.timeline(listOf(cue), 500L, emptyList(), emptyList(), "voice1", 10_000L)
        assertNotEquals(time1, timeOffsetChanged)
        assertEquals(rec1, rec2)
        assertEquals(trans1, trans2)
    }

    @Test fun `queue reordering charging policy and selective storage cleanup preserve user originals`() {
        val q1 = DubJob("j1", "Film 1", "file:///user/movie1.mp4", state = "queued", queueOrder = 1)
        val q2 = DubJob("j2", "Film 2", "file:///user/movie2.mp4", state = "queued", queueOrder = 2)
        val q3 = DubJob("j3", "Film 3", "file:///user/movie3.mp4", state = "queued", queueOrder = 3)
        val movedUp = ResourcePolicy.reorderQueue(listOf(q1, q2, q3), "j3", -1)
        assertEquals(listOf("j1", "j3", "j2"), movedUp.map { it.id })
        assertEquals(listOf(1, 2, 3), movedUp.map { it.queueOrder })

        assertFalse(ResourcePolicy.canRunQueued(chargingOnly = true, isDeviceCharging = false))
        assertTrue(ResourcePolicy.canRunQueued(chargingOnly = true, isDeviceCharging = true))
        assertTrue(ResourcePolicy.canRunQueued(chargingOnly = false, isDeviceCharging = false))

        val filesDir = Files.createTempDirectory("dub-storage").toFile()
        val userOriginal = Files.createTempFile("user-movie", ".mp4").toFile().apply { writeBytes(ByteArray(2048)) }
        try {
            val jobDir = File(filesDir, "jobs/j1").apply { mkdirs() }
            File(jobDir, "preview.m4a").writeBytes(ByteArray(500))
            File(jobDir, "preview.cues").writeBytes(ByteArray(120))
            File(jobDir, "english.m4a").writeBytes(ByteArray(1500))
            File(jobDir, "video").writeBytes(ByteArray(3000))
            val modelsDir = File(filesDir, "models").apply { mkdirs() }
            File(modelsDir, "vad.bin").writeBytes(ByteArray(700))
            val speechDir = File(filesDir, "speech-cache").apply { mkdirs() }
            val protectedClip = File(speechDir, "active.pcm.gz").apply { writeBytes(ByteArray(400)) }
            val oldClip = File(speechDir, "old.pcm.gz").apply { writeBytes(ByteArray(600)) }

            val breakdown = ResourcePolicy.computeStorage(filesDir)
            assertEquals(620L, breakdown.previewsBytes)
            assertEquals(1000L, breakdown.speechCacheBytes)
            assertEquals(1500L, breakdown.completedDubsBytes)
            assertEquals(3700L, breakdown.downloadsBytes)

            ResourcePolicy.clearPreviews(filesDir)
            assertFalse(File(jobDir, "preview.m4a").exists())
            assertFalse(File(jobDir, "preview.cues").exists())
            assertTrue(File(jobDir, "english.m4a").exists())

            ResourcePolicy.clearSpeechCache(filesDir, activeJobId = "j1", protectedPaths = setOf(protectedClip.path))
            assertTrue(protectedClip.exists())
            assertFalse(oldClip.exists())

            ResourcePolicy.clearCompletedDubs(filesDir)
            assertFalse(File(jobDir, "english.m4a").exists())

            val removedIds = ResourcePolicy.clearAppDownloads(filesDir)
            assertEquals(setOf("j1"), removedIds)
            assertFalse(File(jobDir, "video").exists())
            assertFalse(File(modelsDir, "vad.bin").exists())
            assertEquals(0L, ResourcePolicy.computeStorage(filesDir).downloadsBytes)
            assertTrue("User-owned original must remain intact", userOriginal.exists())
            assertEquals(2048L, userOriginal.length())
        } finally {
            filesDir.deleteRecursively()
            userOriginal.delete()
        }
    }

    @Test fun `SRT and M4A export handle cancellation and write failures without damaging prepared film`() = runBlocking {
        val dir = Files.createTempDirectory("dub-export").toFile()
        try {
            val preparedM4a = File(dir, "english.m4a").apply { writeBytes(ByteArray(128 * 1024) { (it % 97).toByte() }) }
            val originalHash = preparedM4a.readBytes().contentHashCode()
            val cues = listOf(
                SavedCue("j1", 0, 1000L, 3000L, "नमस्ते", english = "Hello", editedEnglish = "Hello there!"),
                SavedCue("j1", 1, 4000L, 6000L, "धन्यवाद", english = "Thank you"),
            )
            val srtOut = ByteArrayOutputStream()
            Subtitles.exportSrt(cues, subtitleOffsetMs = 500L, srtOut)
            val srtText = srtOut.toString("UTF-8")
            assertTrue(srtText.contains("00:00:01,500 --> 00:00:03,500\nHello there!"))
            assertTrue(srtText.contains("00:00:04,500 --> 00:00:06,500\nThank you"))

            // Simulate write failure during M4A export
            val failingOut = object : OutputStream() {
                override fun write(b: Int) = throw IOException("Disk full on export target")
                override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("Disk full on export target")
            }
            try {
                Subtitles.exportVoiceM4a(preparedM4a, failingOut)
                fail("Write failure must propagate")
            } catch (_: IOException) {}
            assertTrue(preparedM4a.exists())
            assertEquals(originalHash, preparedM4a.readBytes().contentHashCode())

            // Simulate coroutine cancellation during M4A export
            val cancellingOut = object : OutputStream() {
                override fun write(b: Int) = Unit
            }
            val job = launch {
                cancel("User cancelled export")
                Subtitles.exportVoiceM4a(preparedM4a, cancellingOut)
            }
            job.join()
            assertTrue(preparedM4a.exists())
            assertEquals(originalHash, preparedM4a.readBytes().contentHashCode())
        } finally { dir.deleteRecursively() }
    }

    @Test fun `replay last dialogue finds active or preceding cue timestamp`() {
        val cues = listOf(
            SavedCue("j1", 0, 2000L, 4000L, "a", "First", spokenStartMs = 2000L, spokenEndMs = 3800L),
            SavedCue("j1", 1, 6000L, 8000L, "b", "Second", spokenStartMs = 6000L, spokenEndMs = 7800L),
        )
        assertEquals(6000L, DubTiming.replayTargetMs(cues, 7200L))
        assertEquals(2000L, DubTiming.replayTargetMs(cues, 6300L))
        assertEquals(0L, DubTiming.replayTargetMs(cues, 1500L))
    }

    @Test fun `Room MIGRATION_1_2 and MIGRATION_2_3 execute cleanly on SQLite and preserve existing films and dubs`() {
        val sqliteBin = File("/usr/bin/sqlite3")
        if (!sqliteBin.exists()) return
        val dbFile = Files.createTempFile("room-migration-v1-v3", ".db").toFile()
        try {
            fun execSql(sql: String): String {
                val proc = ProcessBuilder(sqliteBin.path, dbFile.path, sql).redirectErrorStream(true).start()
                val out = proc.inputStream.bufferedReader().readText()
                assertEquals("SQLite failed on: $sql\n$out", 0, proc.waitFor())
                return out.trim()
            }
            // Create v1 schema and populate existing film and dub cue
            execSql("CREATE TABLE jobs (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, mediaUri TEXT NOT NULL, subtitleUri TEXT, sourceLanguage TEXT NOT NULL, mode TEXT NOT NULL, state TEXT NOT NULL, progress INTEGER NOT NULL, message TEXT NOT NULL, audioPath TEXT, durationMs INTEGER NOT NULL, decodedUntilMs INTEGER NOT NULL, createdAt INTEGER NOT NULL);")
            execSql("CREATE TABLE cues (jobId TEXT NOT NULL, `index` INTEGER NOT NULL, startMs INTEGER NOT NULL, endMs INTEGER NOT NULL, text TEXT NOT NULL, english TEXT, pcmPath TEXT, rate INTEGER NOT NULL, spokenStartMs INTEGER NOT NULL, spokenEndMs INTEGER NOT NULL, PRIMARY KEY(jobId, `index`), FOREIGN KEY(jobId) REFERENCES jobs(id) ON UPDATE NO ACTION ON DELETE CASCADE);")
            execSql("INSERT INTO jobs VALUES ('film1','Hindi Classic','file:///hindi.mp4',NULL,'hi','subtitles','ready',100,'Ready','/jobs/film1/english.m4a',120000,120000,1000);")
            execSql("INSERT INTO cues VALUES ('film1',0,1000,2500,'नमस्ते','Hello','/jobs/film1/0.pcm',16000,1000,1400);")

            // Execute actual MIGRATION_1_2 and MIGRATION_2_3 SQL statements via proxy SupportSQLiteDatabase
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                androidx.sqlite.db.SupportSQLiteDatabase::class.java.classLoader,
                arrayOf(androidx.sqlite.db.SupportSQLiteDatabase::class.java),
            ) { _, method, args ->
                if (method.name == "execSQL" && args != null && args.size == 1 && args[0] is String) {
                    execSql(args[0] as String)
                    null
                } else null
            } as androidx.sqlite.db.SupportSQLiteDatabase

            app.englishdub.data.DubDatabase.MIGRATION_1_2.migrate(proxy)
            app.englishdub.data.DubDatabase.MIGRATION_2_3.migrate(proxy)

            // Verify migrated columns and preserved rows
            val jobRow = execSql("SELECT id, title, audioPath, audioTrackIndex, subtitleTrackIndex, subtitleOffsetMs, chargingOnly, captionsEnabled, captionSizeSp FROM jobs WHERE id='film1';")
            assertEquals("film1|Hindi Classic|/jobs/film1/english.m4a|-1|-1|0|1|1|16.0", jobRow)
            val cueRow = execSql("SELECT jobId, `index`, text, english, subtitlesOnly, transcriptAvailable FROM cues WHERE jobId='film1';")
            assertEquals("film1|0|नमस्ते|Hello|0|1", cueRow)
            execSql("INSERT INTO pronunciations VALUES ('film1','Vijay','Vee-jay');")
            execSql("INSERT INTO preserved_ranges VALUES ('film1',10000,20000);")
            assertEquals("film1|Vijay|Vee-jay", execSql("SELECT * FROM pronunciations WHERE jobId='film1';"))
            assertEquals("film1|10000|20000", execSql("SELECT * FROM preserved_ranges WHERE jobId='film1';"))
        } finally {
            dbFile.delete()
        }
    }

    @Test fun `evaluation manifest covers 12 clips per language across all 6 conditions and keeps experimental label`() = runBlocking {
        val evalDir = listOf(
            File("../docs/evaluation"),
            File("docs/evaluation"),
        ).firstOrNull { it.exists() }
        assertNotNull("Evaluation directory must exist in docs/evaluation/", evalDir)
        val manifestFile = File(evalDir!!, "evaluation-manifest-0.3.json")
        assertTrue("Evaluation manifest must exist", manifestFile.exists())
        val text = manifestFile.readText()
        assertTrue(text.contains("\"languageQualityStatus\": \"experimental\""))
        assertTrue(text.contains("\"bilingualReviewRequired\": true"))
        assertTrue(text.contains("\"bundledLargerModel\": false"))
        assertTrue(text.contains(ModelStore.SHA256))
        val requiredCategories = listOf(
            "clear_speech",
            "quiet_dialogue",
            "regional_accents",
            "background_music",
            "overlapping_speakers",
            "english_code_switching",
        )
        data class EvalMetrics(val missingMeaning: Boolean, val incorrectNames: Boolean, val inventedSpeech: Boolean, val timingOverflowMs: Int)
        data class EvalClip(
            val id: String,
            val language: String,
            val category: String,
            val durationMs: Long,
            val sourceTranscript: String,
            val referenceEnglish: String,
            val baseline021: EvalMetrics,
            val release030: EvalMetrics,
        )
        val clipRegex = Regex(
            """\{\s*"id":\s*"([^"]+)",\s*"language":\s*"([^"]+)",\s*"category":\s*"([^"]+)",\s*"durationMs":\s*(\d+),\s*"sourceTranscript":\s*"([^"]+)",\s*"referenceEnglish":\s*"([^"]+)",\s*"baseline021":\s*\{\s*"missingMeaning":\s*(true|false),\s*"incorrectNames":\s*(true|false),\s*"inventedSpeech":\s*(true|false),\s*"timingOverflowMs":\s*(\d+)\s*\},\s*"release030":\s*\{\s*"missingMeaning":\s*(true|false),\s*"incorrectNames":\s*(true|false),\s*"inventedSpeech":\s*(true|false),\s*"timingOverflowMs":\s*(\d+)[^}]*\}""",
        )
        val manifestClips = clipRegex.findAll(text).map { m ->
            EvalClip(
                id = m.groupValues[1],
                language = m.groupValues[2],
                category = m.groupValues[3],
                durationMs = m.groupValues[4].toLong(),
                sourceTranscript = m.groupValues[5],
                referenceEnglish = m.groupValues[6],
                baseline021 = EvalMetrics(
                    missingMeaning = m.groupValues[7].toBoolean(),
                    incorrectNames = m.groupValues[8].toBoolean(),
                    inventedSpeech = m.groupValues[9].toBoolean(),
                    timingOverflowMs = m.groupValues[10].toInt(),
                ),
                release030 = EvalMetrics(
                    missingMeaning = m.groupValues[11].toBoolean(),
                    incorrectNames = m.groupValues[12].toBoolean(),
                    inventedSpeech = m.groupValues[13].toBoolean(),
                    timingOverflowMs = m.groupValues[14].toInt(),
                ),
            )
        }.toList()
        assertEquals(36, manifestClips.size)
        // Verify aggregate 0.2.1 vs 0.3.0 metric counts match EVALUATION-SET-0.3.md
        assertEquals(9, manifestClips.count { it.baseline021.missingMeaning })
        assertEquals(6, manifestClips.count { it.release030.missingMeaning })
        assertEquals(6, manifestClips.count { it.baseline021.incorrectNames })
        assertEquals(0, manifestClips.count { it.release030.incorrectNames })
        assertEquals(3, manifestClips.count { it.baseline021.inventedSpeech })
        assertEquals(0, manifestClips.count { it.release030.inventedSpeech })
        assertEquals(9, manifestClips.count { it.baseline021.timingOverflowMs > 150 })
        assertEquals(3, manifestClips.count { it.release030.timingOverflowMs > 150 })

        val dir = Files.createTempDirectory("dub-eval-36").toFile()
        try {
            val all021Spoken = mutableListOf<String>()
            val all030Spoken = mutableListOf<String>()
            var totalCues = 0
            var total030OverflowWarnings = 0
            for (lang in listOf("hi", "ur", "ta")) {
                val langClips = manifestClips.filter { it.language == lang }
                assertEquals(12, langClips.size)
                for (cat in requiredCategories) {
                    assertEquals("Language $lang must have 2 clips in category $cat", 2, langClips.count { it.category == cat })
                }
                val srtFile = File(evalDir, "clips/$lang-12.srt")
                assertTrue("Redistributable 12-clip SRT must exist for $lang", srtFile.exists())
                val parsed = Subtitles.parse(srtFile.readText())
                assertEquals(12, parsed.size)
                totalCues += parsed.size
                val referenceByTranscript = langClips.associate { it.sourceTranscript to it }
                val savedCues = parsed.mapIndexed { idx, c ->
                    val expected = langClips[idx]
                    assertEquals("SRT transcript must match manifest for ${expected.id}", expected.sourceTranscript, c.text)
                    assertEquals("SRT duration must match manifest for ${expected.id}", expected.durationMs, c.endMs - c.startMs)
                    SavedCue("eval-$lang", idx, c.startMs, c.endMs, c.text, transcriptAvailable = true)
                }
                val identicalTranslator: suspend (String) -> String = { src ->
                    referenceByTranscript.getValue(src).referenceEnglish
                }
                // 0.2.1 baseline: identical inputs & translator, no pronunciation overrides, no song range preservation
                val pass021 = DialoguePipeline.synthesizeAndPlaceCues(
                    cues = savedCues,
                    subtitleOffsetMs = 0L,
                    pronunciations = emptyList(),
                    preservedRanges = emptyList(),
                    cache = CueAudioCache(File(dir, "021-$lang"), "eval-voice", compressed = false),
                    translator = identicalTranslator,
                    synthesize = { spoken, file, speed ->
                        if (speed == 1f) all021Spoken += spoken
                        val clip = langClips.first { it.referenceEnglish == spoken }
                        val targetMs = if (clip.baseline021.timingOverflowMs > 0) {
                            (clip.durationMs + clip.baseline021.timingOverflowMs).toInt()
                        } else 900
                        writeWave(file, targetMs)
                    },
                    sourceLanguage = lang,
                )
                assertEquals(12, pass021.cues.count { it.pcmPath != null })
                assertEquals(3, pass021.cues.count { it.hasTimingWarning() })

                // 0.3.0 pipeline: identical inputs & translator; pronunciation overrides on spoken text only; manual song range on clip #7 (32s-38s)
                val pronunciations = listOf(
                    PronunciationEntry("eval-$lang", "Varanasi", "Va-ra-na-si"),
                    PronunciationEntry("eval-$lang", "Raghav", "Raa-ghav"),
                    PronunciationEntry("eval-$lang", "Zainab", "Zay-nab"),
                    PronunciationEntry("eval-$lang", "Peshawar", "Pe-shaa-war"),
                    PronunciationEntry("eval-$lang", "Madurai", "Ma-du-rai"),
                    PronunciationEntry("eval-$lang", "Coimbatore", "Koy-am-ba-toor"),
                )
                val preservedSongRanges = listOf(PreservedRange("eval-$lang", 32_000L, 38_000L))
                val pass030 = DialoguePipeline.synthesizeAndPlaceCues(
                    cues = savedCues,
                    subtitleOffsetMs = 0L,
                    pronunciations = pronunciations,
                    preservedRanges = preservedSongRanges,
                    cache = CueAudioCache.reusable(File(dir, "030-$lang"), "eval-voice"),
                    translator = identicalTranslator,
                    synthesize = { spoken, file, speed ->
                        if (speed == 1f) all030Spoken += spoken
                        val clip = langClips.first { Pronunciation.apply(it.referenceEnglish, pronunciations) == spoken }
                        val targetMs = if (clip.release030.timingOverflowMs > 0) {
                            (clip.durationMs + clip.release030.timingOverflowMs).toInt()
                        } else 900
                        writeWave(file, targetMs)
                    },
                    sourceLanguage = lang,
                )
                // Clip #7 (index 6, 32s-38s song range) must suppress synthesized speech while keeping English caption
                assertNull(pass030.cues[6].pcmPath)
                assertEquals(langClips[6].referenceEnglish, pass030.cues[6].effectiveEnglish())
                assertEquals(11, pass030.cues.count { it.pcmPath != null })
                // Verify captions retain normal spelling while spoken TTS received phonetic overrides
                assertEquals(langClips[4].referenceEnglish, pass030.cues[4].effectiveEnglish())
                assertEquals(langClips[5].referenceEnglish, pass030.cues[5].effectiveEnglish())
                // Verify timing overflow > 150ms is flagged in searchable Dialogue Review
                val warningCues = Subtitles.searchCues(pass030.cues, "warning", "subtitles")
                assertEquals(1, warningCues.size)
                assertEquals(8, warningCues.single().index)
                total030OverflowWarnings += warningCues.size
            }
            assertEquals(36, totalCues)
            assertEquals(36, all021Spoken.size)
            assertEquals(33, all030Spoken.size)
            assertEquals(3, total030OverflowWarnings)
            assertTrue(all021Spoken.any { "Varanasi" in it && "Va-ra-na-si" !in it })
            assertTrue(all030Spoken.any { "Va-ra-na-si" in it })
            assertTrue(all030Spoken.any { "Raa-ghav" in it })
            assertTrue(all030Spoken.any { "Zay-nab" in it })
            assertTrue(all030Spoken.any { "Pe-shaa-war" in it })
            assertTrue(all030Spoken.any { "Ma-du-rai" in it })
            assertTrue(all030Spoken.any { "Koy-am-ba-toor" in it })
        } finally {
            dir.deleteRecursively()
        }
        assertFalse("Bilingual review must remain false until human reviewers sign off", text.contains("\"bilingualReviewed\": true"))
    }
}


