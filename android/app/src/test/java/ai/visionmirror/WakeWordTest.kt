package ai.visionmirror

import ai.visionmirror.assistant.ResumeWords
import ai.visionmirror.assistant.StopWords
import ai.visionmirror.audio.WakeWord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordTest {
    @Test fun herOwnSentenceContainingHerNameIsNotJustHerName() {
        assertTrue(WakeWord.isBareName("Adanna"))
        assertTrue(WakeWord.isBareName("hey adanna"))
        assertFalse(WakeWord.isBareName("I'm Adanna and I can help"))
        assertFalse(WakeWord.isBareName("hi I'm Adanna"))
    }

    @Test fun pauseAndGoOn() {
        assertTrue(StopWords.matches("pause"))
        assertTrue(StopWords.matches("hold on"))
        assertTrue(StopWords.matches("wait"))
        assertTrue(ResumeWords.matches("go on"))
        assertTrue(ResumeWords.matches("continue"))
        assertFalse(ResumeWords.matches("say continue to carry on with the rest of setup"))
    }
}
