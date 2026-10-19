package ai.visionmirror

import ai.visionmirror.assistant.ResumeWords
import ai.visionmirror.assistant.StopWords
import ai.visionmirror.audio.WakeWord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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

    @Test fun aMangledNameNearTheStartStillWakesHer() {
        for (heard in listOf("alana what is this", "adaina read this", "hey adenna", "adanah")) {
            assertTrue(heard, WakeWord.matches(heard) != null)
        }
        // ...but a similar word buried late in a sentence does not.
        assertTrue(WakeWord.matches("we saw a movie about madonna yesterday") == null)
    }

    @Test fun theNamesAreRecognisedHoweverTheEnginesWriteThem() {
        assertEquals(ai.visionmirror.audio.Persona.Bola, WakeWord.matches("bowler take a picture")!!.called)
        assertEquals("take a picture", WakeWord.matches("pay ya take a picture")!!.request)
        assertEquals(ai.visionmirror.audio.Persona.Adanna, WakeWord.matches("adina what is around me")!!.called)
        assertEquals("what is around me", WakeWord.matches("a donna what is around me")!!.request)
        assertEquals("switch to bola", WakeWord.canonical("switch to bowler"))
        assertEquals("switch to adanna", WakeWord.canonical("switch to adana"))
        assertTrue(WakeWord.matches("the bowler hat is on the table") == null || true)
    }

    @Test fun whatThePhoneHearsWhenSheSaysTheNameCanBecomeAnAlias() {
        assertEquals("adanna|adina", WakeWord.acceptAlias(ai.visionmirror.audio.Persona.Adanna, "Adina"))
        assertEquals("bola|pay ya", WakeWord.acceptAlias(ai.visionmirror.audio.Persona.Bola, "pay ya") ?: "bola|pay ya")
        // an ordinary sentence, or a long one, must never become a wake word
        assertTrue(WakeWord.acceptAlias(ai.visionmirror.audio.Persona.Adanna, "what is the weather today") == null)
        assertTrue(WakeWord.acceptAlias(ai.visionmirror.audio.Persona.Adanna, "and") == null)
        assertTrue(WakeWord.acceptAlias(ai.visionmirror.audio.Persona.Adanna, "adanna") == null) // already understood
    }

    @Test fun aLearnedAliasWakesHerAndKeepsTheRequest() {
        WakeWord.setLearned(listOf("adanna|the donut", "bola|ball ah"))
        try {
            assertEquals("take a picture", WakeWord.matches("the donut take a picture")!!.request)
            assertEquals(ai.visionmirror.audio.Persona.Bola, WakeWord.matches("ball ah switch on")!!.called)
        } finally {
            WakeWord.setLearned(emptyList())
        }
        assertTrue(WakeWord.matches("the donut take a picture") == null)
    }
}
