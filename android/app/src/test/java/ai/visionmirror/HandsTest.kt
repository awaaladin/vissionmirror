package ai.visionmirror

import ai.visionmirror.assistant.AssistantAction
import ai.visionmirror.assistant.AssistantIntents
import ai.visionmirror.assistant.ClockAnswer
import ai.visionmirror.assistant.HandsFree
import ai.visionmirror.assistant.StopWords
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandsTest {
    @org.junit.Before fun loadVoiceCatalog() = TestCatalog.load()

    @Test fun stopWordsOnlyCountInShortUtterances() {
        assertTrue(StopWords.matches("stop"))
        assertTrue(StopWords.matches("Adanna stop"))
        assertTrue(StopWords.matches("be quiet"))
        assertTrue(StopWords.matches("shut up"))
        // Her own long sentence must never stop her.
        assertFalse(StopWords.matches("the bus stop is on the left of the road ahead of you"))
        assertFalse(StopWords.matches("you are wearing a navy shirt"))
    }

    @Test fun accessGrantedAndThatsAll() {
        assertEquals(HandsFree.Change.On, HandsFree.parse("access granted"))
        assertEquals(HandsFree.Change.On, HandsFree.parse("access"))
        assertEquals(HandsFree.Change.On, HandsFree.parse("take control"))
        assertEquals(HandsFree.Change.Off, HandsFree.parse("that's all"))
        assertEquals(HandsFree.Change.Off, HandsFree.parse("hands free off"))
        assertEquals(HandsFree.Change.Off, HandsFree.parse("goodbye"))
        assertNull(HandsFree.parse("is my shirt neat"))
    }

    @Test fun timeAndDateComeFromThePhone() {
        val now = ZonedDateTime.of(2026, 10, 17, 15, 42, 0, 0, ZoneId.of("Africa/Lagos"))
        assertEquals("It is 3:42 PM.", ClockAnswer.answer("what time is it", now))
        assertEquals("Today is Saturday, 17 October 2026.", ClockAnswer.answer("what is the date", now))
        assertNull(ClockAnswer.answer("what is that stain", now))
    }

    @Test fun screenAwareCommands() {
        assertEquals(AssistantAction.TakePhoto, AssistantIntents.parse("take it", false, "mirror"))
        assertEquals(AssistantAction.TakePhoto, AssistantIntents.parse("take the photo", false, "home"))
        assertEquals(AssistantAction.FlipCamera, AssistantIntents.parse("flip the camera", false, "mirror"))
        assertEquals(AssistantAction.LookAgain, AssistantIntents.parse("again", false, "look/read"))
        assertEquals(AssistantAction.GoBack, AssistantIntents.parse("go back", true, "result"))
        assertNull(AssistantIntents.parse("again", false, "home"))
    }
}
