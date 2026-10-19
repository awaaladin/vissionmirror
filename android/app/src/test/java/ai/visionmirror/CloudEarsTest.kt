package ai.visionmirror

import ai.visionmirror.audio.SpeechGate
import ai.visionmirror.audio.wavOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudEarsTest {
    private fun run(gate: SpeechGate, quiet: Int, loud: Int, then: Int): SpeechGate.Decision {
        var d = SpeechGate.Decision.Continue
        repeat(quiet) { if (d == SpeechGate.Decision.Continue) d = gate.feed(80.0) }
        repeat(loud) { if (d == SpeechGate.Decision.Continue) d = gate.feed(2500.0) }
        repeat(then) { if (d == SpeechGate.Decision.Continue) d = gate.feed(80.0) }
        return d
    }

    @Test fun finishesOnlyAfterAGenuinePauseFollowingSpeech() {
        // 0.5 s of room, 1.5 s of voice, then 1.2 s of quiet: she may just be taking a breath
        assertEquals(SpeechGate.Decision.Continue, run(SpeechGate(), 10, 30, 24))
        // ...but 2 s of quiet means she is done
        assertEquals(SpeechGate.Decision.Finished, run(SpeechGate(), 10, 30, 40))
    }

    @Test fun silenceForSevenSecondsIsNothingHeard() {
        assertEquals(SpeechGate.Decision.NothingHeard, run(SpeechGate(), 200, 0, 0))
    }

    @Test fun aBriefClickIsNotSpeech() {
        val gate = SpeechGate()
        repeat(10) { gate.feed(80.0) }
        gate.feed(3000.0) // one loud frame only
        repeat(10) { gate.feed(80.0) }
        assertTrue(!gate.started)
    }

    @Test fun aNoisyRoomRaisesTheBar() {
        val gate = SpeechGate()
        repeat(10) { gate.feed(500.0) } // loud room: threshold becomes about 1500
        repeat(5) { gate.feed(900.0) }  // louder than the room, but not speech
        assertTrue(!gate.started)
        repeat(4) { gate.feed(3000.0) }
        assertTrue(gate.started)
    }

    @Test fun wavHeaderDescribesTheSound() {
        val pcm = ByteArray(3200) { 1 }
        val wav = wavOf(pcm)
        assertEquals(44 + 3200, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("data", String(wav, 36, 4))
        assertEquals(3200, (wav[40].toInt() and 0xFF) or ((wav[41].toInt() and 0xFF) shl 8))
        assertEquals(16000, (wav[24].toInt() and 0xFF) or ((wav[25].toInt() and 0xFF) shl 8))
    }
}
