package ai.visionmirror

import ai.visionmirror.assistant.AssistantAction
import ai.visionmirror.assistant.AssistantIntents
import ai.visionmirror.audio.WakeWord
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
        // Colours are described by Look around, which names them along with everything else it sees.
        assertEquals(AssistantAction.OpenLook, AssistantIntents.parse("what colour is this", hasSession = false))
        assertEquals(AssistantAction.OpenLook, AssistantIntents.parse("look around", hasSession = false))
        assertEquals(AssistantAction.OpenMirror, AssistantIntents.parse("take a photo", hasSession = false))
        // With a photo to ask about, "what colour is my shirt" is a question, not the colour tool.
        assertNull(AssistantIntents.parse("what colour is my shirt", hasSession = true))
        assertNull(AssistantIntents.parse("is my collar folded over on the left side today", hasSession = false))
    }
}
