package ai.visionmirror

import ai.visionmirror.guidance.FaceObservation
import ai.visionmirror.guidance.Guidance
import ai.visionmirror.guidance.MirrorCoach
import ai.visionmirror.guidance.SpeechThrottle
import ai.visionmirror.guidance.StabilityTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorCoachTest {
    private val perfect = FaceObservation(0.5f, 0.32f, 0.15f, 0.20f)
    private val tooRight = FaceObservation(0.8f, 0.32f, 0.15f, 0.20f)

    @Test fun throttleAllowsOneInstructionPerGap() {
        val t = SpeechThrottle(minGapMs = 1_500)
        assertEquals("a", t.offer("a", 0))
        assertNull("too soon", t.offer("b", 1_000))
        assertEquals("b", t.offer("b", 1_500))
    }

    @Test fun throttleNeverRepeatsBackToBack_untilStuckForLong() {
        val t = SpeechThrottle(minGapMs = 1_500, repeatAfterMs = 8_000)
        assertEquals("a", t.offer("a", 0))
        assertNull(t.offer("a", 2_000))
        assertNull(t.offer("a", 7_900))
        assertEquals("a", t.offer("a", 8_000))
    }

    @Test fun stabilityNeedsAnUnbrokenStretch() {
        val s = StabilityTracker(requiredMs = 1_200)
        assertFalse(s.update(true, 0))
        assertFalse(s.update(true, 1_000))
        assertTrue(s.update(true, 1_200))
        assertFalse("a break resets the clock", s.update(false, 1_300))
        assertFalse(s.update(true, 1_400))
        assertFalse(s.update(true, 2_500))
        assertTrue(s.update(true, 2_600))
    }

    @Test fun coachSpeaksThenStaysQuietForTheSameInstruction() {
        val c = MirrorCoach()
        assertEquals(Guidance.MoveLeft.spoken, c.update(tooRight, 0).speak)
        assertNull(c.update(tooRight, 300).speak)
        assertNull(c.update(tooRight, 1_600).speak)
    }

    @Test fun coachReportsStableOnlyAfterGoodFramingHolds() {
        val c = MirrorCoach()
        assertFalse(c.update(perfect, 0).stable)
        assertFalse(c.update(perfect, 1_000).stable)
        assertTrue(c.update(perfect, 1_250).stable)
        assertFalse("losing the face resets it", c.update(null, 1_300).stable)
        assertFalse(c.update(perfect, 1_400).stable)
    }

    @Test fun closerCueFiresOnAClearStepUpOnly() {
        val c = MirrorCoach()
        c.update(FaceObservation(0.9f, 0.6f, 0.15f, 0.4f), 0) // poor
        val better = c.update(FaceObservation(0.6f, 0.36f, 0.15f, 0.22f), 2_000)
        assertTrue(better.closerCue)
        assertFalse(
            "no repeat without further progress",
            c.update(FaceObservation(0.6f, 0.36f, 0.15f, 0.22f), 4_000).closerCue,
        )
    }

    @Test fun resetStartsFresh() {
        val c = MirrorCoach()
        c.update(perfect, 0)
        c.update(perfect, 1_300)
        c.reset()
        assertFalse(c.update(perfect, 1_400).stable)
    }
}
