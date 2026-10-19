package ai.visionmirror

import ai.visionmirror.assistant.AssistantAction
import ai.visionmirror.assistant.AssistantIntents
import ai.visionmirror.audio.WakeWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AssistantTest {
    @org.junit.Before fun loadVoiceCatalog() = TestCatalog.load()

    @Test fun nameIsHeardEvenWhenMisheard() {
        for (said in listOf("Adanna", "adana", "a donna", "Adonna, what is this", "hey adanna")) {
            assertNotNull(said, WakeWord.matches(said))
        }
    }

    @Test fun bolaWakesItToo() {
        for (said in listOf("Bola", "hey bola", "bolla what time is it")) assertNotNull(said, WakeWord.matches(said))
        for (said in listOf("bowl", "cola", "ebola", "gola")) assertNull(said, WakeWord.matches(said))
    }

    @Test fun aNameInTheMiddleOfARequestKeepsTheWholeRequest() {
        assertEquals("switch to bola", WakeWord.matches("switch to Bola")?.request)
        assertEquals("what time is it", WakeWord.matches("Bola what time is it")?.request)
        assertEquals("is my shirt neat", WakeWord.matches("hey Adanna is my shirt neat")?.request)
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
        // From another screen this opens the mirror and takes the photo at once (see SessionViewModel.routeAction).
        assertEquals(AssistantAction.TakePhoto, AssistantIntents.parse("take a photo", hasSession = false))
        // With a photo to ask about, "what colour is my shirt" is a question, not the colour tool.
        assertNull(AssistantIntents.parse("what colour is my shirt", hasSession = true))
        assertNull(AssistantIntents.parse("is my collar folded over on the left side today", hasSession = false))
    }

    @Test fun takeAPictureOfMeIsDoneAtOnceFromAnywhere() {
        for (said in listOf("take a picture of me", "take a photo of me", "take a selfie", "take my picture", "photo of me", "snap me")) {
            for (screen in listOf(null, "home", "mirror", "look/read")) {
                assertEquals(said, AssistantAction.SnapSelfie, AssistantIntents.parse(said, hasSession = false, screen = screen))
            }
        }
    }

    @Test fun frontAndBackCameraAreCommandsOfTheirOwn() {
        assertEquals(AssistantAction.UseFrontCamera, AssistantIntents.parse("switch to the front camera", false, "look/read"))
        assertEquals(AssistantAction.UseFrontCamera, AssistantIntents.parse("front camera", false, "home"))
        assertEquals(AssistantAction.UseBackCamera, AssistantIntents.parse("use the back camera", false, "mirror"))
        assertEquals(AssistantAction.UseBackCamera, AssistantIntents.parse("change to rear camera", false, null))
    }

    @Test fun signInAndAccountOpenTheAccountScreen() {
        assertEquals(AssistantAction.OpenAccount, AssistantIntents.parse("sign in", false, "home"))
        assertEquals(AssistantAction.OpenAccount, AssistantIntents.parse("open my account", false, "home"))
    }
}
