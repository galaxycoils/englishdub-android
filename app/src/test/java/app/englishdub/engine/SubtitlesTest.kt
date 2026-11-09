package app.englishdub.engine

import org.junit.Assert.*
import org.junit.Test

class SubtitlesTest {
    @Test fun `SRT handles BOM CRLF multiline text and markup`() {
        val result = Subtitles.parse("\uFEFF1\r\n00:01:02,300 --> 00:01:04,700\r\n<i>Hello</i>\r\nthere &amp; welcome.\r\n\r\n")
        assertEquals(listOf(TextCue(62300, 64700, "Hello there & welcome.")), result)
    }
    @Test fun `WebVTT accepts cue ids settings and minute timestamps`() {
        val result = Subtitles.parse("WEBVTT\n\nNOTE skip\ncomment\n\nintro\n01:02.300 --> 01:04.700 align:start\n<v Narrator>Hello</v>\n\n")
        assertEquals(TextCue(62300, 64700, "Hello"), result.single())
    }
    @Test fun `cues are ordered and invalid duration ignored`() {
        val result = Subtitles.parse("2\n00:00:03,000 --> 00:00:04,000\nSecond\n\n1\n00:00:01,000 --> 00:00:02,000\nFirst\n\n3\n00:00:05,000 --> 00:00:04,000\nBad")
        assertEquals(listOf("First", "Second"), result.map { it.text })
    }
    @Test(expected = IllegalArgumentException::class) fun `empty content fails clearly`() { Subtitles.parse("WEBVTT\n\n") }
    @Test(expected = IllegalArgumentException::class) fun `invalid timestamps fail`() { Subtitles.parse("00:72:00,000 --> 00:73:00,000\nBad") }
    @Test fun `overflowing speech queues without overlap or truncation`() {
        val first = DubTiming.place(TextCue(1000, 2000, "First"), 1500, 0)
        val second = DubTiming.place(TextCue(2200, 3000, "Second"), 1000, first.endMs)
        assertEquals(2500L, first.endMs); assertTrue(first.late)
        assertEquals(2500L, second.startMs); assertEquals(3500L, second.endMs); assertTrue(second.late)
    }
    @Test fun `clock resync threshold is strict`() {
        assertFalse(DubTiming.needsResync(1000, 1100)); assertTrue(DubTiming.needsResync(1000, 1101))
    }
    @Test fun `resampling retains a constant signal across chunks`() {
        val resampler = MonoResampler(48000)
        val output = mutableListOf<Float>()
        repeat(24000) { resampler.sample(0.5f) { output += it } }
        repeat(24000) { resampler.sample(0.5f) { output += it } }
        assertEquals(16000, output.size); assertTrue(output.all { it == 0.5f })
    }
    @Test fun `upsampling interpolates between frames`() {
        val output = mutableListOf<Float>(); val resampler = MonoResampler(8000)
        resampler.sample(0f) { output += it }; resampler.sample(1f) { output += it }
        assertEquals(listOf(0f, 0.5f, 1f), output)
    }
    @Test fun `findCueAt binary search returns matching cue and handles boundaries and offsets`() {
        val cues = listOf(
            app.englishdub.data.SavedCue("job", 0, startMs = 1000L, endMs = 3000L, text = "First", english = "First", spokenStartMs = 1000L, spokenEndMs = 2800L),
            app.englishdub.data.SavedCue("job", 1, startMs = 4000L, endMs = 6000L, text = "Second", english = "Second", spokenStartMs = 4100L, spokenEndMs = 5900L),
            app.englishdub.data.SavedCue("job", 2, startMs = 7000L, endMs = 9000L, text = "Third", english = "Third", spokenStartMs = 7000L, spokenEndMs = 8500L),
        )
        assertNull(Subtitles.findCueAt(emptyList(), 2000L))
        assertNull(Subtitles.findCueAt(cues, 500L))
        assertEquals("First", Subtitles.findCueAt(cues, 1000L)?.english)
        assertEquals("First", Subtitles.findCueAt(cues, 2000L)?.english)
        assertNull(Subtitles.findCueAt(cues, 3000L))
        assertNull(Subtitles.findCueAt(cues, 3500L))
        assertEquals("Second", Subtitles.findCueAt(cues, 5000L)?.english)
        assertNull(Subtitles.findCueAt(cues, 9500L))

        // Positive offset (+500ms): first cue active 1500..3500
        assertNull(Subtitles.findCueAt(cues, 1200L, subtitleOffsetMs = 500L))
        assertEquals("First", Subtitles.findCueAt(cues, 1500L, subtitleOffsetMs = 500L)?.english)
        assertEquals("First", Subtitles.findCueAt(cues, 3200L, subtitleOffsetMs = 500L)?.english)

        // Negative offset (-500ms): second cue active 3500..5500
        assertEquals("Second", Subtitles.findCueAt(cues, 3600L, subtitleOffsetMs = -500L)?.english)
        assertNull(Subtitles.findCueAt(cues, 5600L, subtitleOffsetMs = -500L))
    }
    @Test fun `findSpokenCueAt returns cue where audio is currently speaking`() {
        val cues = listOf(
            app.englishdub.data.SavedCue("job", 0, startMs = 1000L, endMs = 3000L, text = "First", spokenStartMs = 1200L, spokenEndMs = 2800L),
            app.englishdub.data.SavedCue("job", 1, startMs = 4000L, endMs = 6000L, text = "SubOnly", spokenStartMs = 4000L, spokenEndMs = 5500L, subtitlesOnly = true),
            app.englishdub.data.SavedCue("job", 2, startMs = 7000L, endMs = 9000L, text = "Third", spokenStartMs = 7000L, spokenEndMs = 8800L),
        )
        assertNull(Subtitles.findSpokenCueAt(cues, 1000L))
        assertEquals("First", Subtitles.findSpokenCueAt(cues, 1500L)?.text)
        assertEquals("First", Subtitles.findSpokenCueAt(cues, 2799L)?.text)
        assertNull(Subtitles.findSpokenCueAt(cues, 2800L))
        assertNull(Subtitles.findSpokenCueAt(cues, 4500L))
        assertEquals("Third", Subtitles.findSpokenCueAt(cues, 7500L)?.text)
    }
}
