package ai.visionmirror

import ai.visionmirror.assistant.AssistantAction
import ai.visionmirror.assistant.AssistantIntents
import ai.visionmirror.audio.WakeWord
import ai.visionmirror.look.ColourNamer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AssistantTest {
    @Test fun nameIsHeardEvenWhenMisheard() {
        for (said in listOf("Adanna", "adana", "a donna", "Adonna, what is this", "hey adanna")) {
            assertNotNull(said, WakeWord.matches(said))
        }
    }

    @Test fun otherWordsDoNotWakeIt() {
        for (said in listOf("banana", "madonna", "hello there", "what is that", "")) {
            assertNull(said, WakeWord.matches(said))
        }
    }

    @Test fun whatFollowsTheNameIsTheRequest() {
        assertEquals("is my shirt tucked in", WakeWord.matches("Adanna, is my shirt tucked in")?.request)
        assertEquals("", WakeWord.matches("adanna")?.request)
        assertEquals("what colour is this", WakeWord.matches("Special request, what colour is this")?.request)
        assertEquals("", WakeWord.matches("special request")?.request)
    }

    @Test fun shortRequestsOpenToolsAndLongOnesAreQuestions() {
        assertEquals(AssistantAction.OpenRead, AssistantIntents.parse("read this", hasSession = false))
        assertEquals(AssistantAction.OpenColour, AssistantIntents.parse("what colour is this", hasSession = false))
        assertEquals(AssistantAction.OpenLook, AssistantIntents.parse("look around", hasSession = false))
        assertEquals(AssistantAction.OpenMirror, AssistantIntents.parse("take a photo", hasSession = false))
        // With a photo to ask about, "what colour is my shirt" is a question, not the colour tool.
        assertNull(AssistantIntents.parse("what colour is my shirt", hasSession = true))
        assertEquals(AssistantAction.OpenColour, AssistantIntents.parse("open colour", hasSession = true))
        assertNull(AssistantIntents.parse("is my collar folded over on the left side today", hasSession = false))
    }

    @Test fun coloursHaveEverydayNames() {
        assertEquals("navy blue", ColourNamer.name(20, 33, 63))
        assertEquals("white", ColourNamer.name(250, 250, 248))
        assertEquals("black", ColourNamer.name(8, 8, 10))
        assertEquals("red", ColourNamer.name(200, 40, 40))
        assertEquals("green", ColourNamer.name(46, 139, 61))
    }
}
