package ai.visionmirror

import ai.visionmirror.assistant.OwnSpeech
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnSpeechTest {
    private val own = OwnSpeech()

    @Test fun herOwnWordsHeardBackAreAnEcho() {
        own.remember("You are wearing a navy blue shirt with grey trousers.", now = 1_000)
        assertTrue(own.isEcho("wearing a navy blue shirt", now = 2_000))
        assertTrue(own.isEcho("you are wearing a navy blue shirt with grey trousers", now = 2_000))
    }

    @Test fun theUsersOwnWordsAreNot() {
        own.remember("You are wearing a navy blue shirt with grey trousers.", now = 1_000)
        assertFalse(own.isEcho("take the photo", now = 2_000))
        assertFalse(own.isEcho("what is the weather in abuja", now = 2_000))
    }

    @Test fun anEchoStopsCountingAfterHalfAMinute() {
        own.remember("You are wearing a navy blue shirt", now = 1_000)
        assertFalse(own.isEcho("wearing a navy blue shirt", now = 40_000))
    }

    @Test fun theUserCallingHerNameOrSayingStopAlwaysCounts() {
        own.remember("Hello, I'm Bola. Say stop and I will stop.", now = 1_000)
        assertFalse(own.isEcho("Bola", now = 2_000))
        assertFalse(own.isEcho("stop", now = 2_000))
        assertFalse(own.isEcho("hey bola", now = 2_000))
    }
}
