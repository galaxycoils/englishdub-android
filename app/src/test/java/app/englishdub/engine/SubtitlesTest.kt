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
}
