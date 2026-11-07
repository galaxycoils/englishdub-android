package app.englishdub

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.englishdub.engine.Subtitles
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubtitleCompatibilityTest {
    @Test fun androidPatternEngineAcceptsSubtitleMarkup() {
        val result = Subtitles.parse("1\n00:00:01,000 --> 00:00:02,000\n{\\an8}<i>Hello</i> வணக்கம் اردو\n")
        assertEquals("Hello வணக்கம் اردو", result.single().text)
    }
}
