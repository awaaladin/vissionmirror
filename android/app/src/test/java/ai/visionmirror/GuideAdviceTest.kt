package ai.visionmirror

import ai.visionmirror.guide.GuideAdvice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideAdviceTest {
    @Test fun uprightPhoneIsFineButOneFlatOnAPalmIsPointingAtTheFloor() {
        assertEquals(0f, GuideAdvice.pitchDegrees(0f, 9.8f, 0f), 1f)
        assertNull(GuideAdvice.posture(GuideAdvice.pitchDegrees(0f, 9.8f, 0.5f)))
        assertEquals(90f, GuideAdvice.pitchDegrees(0f, 0f, 9.8f), 1f)
        assertNotNull(GuideAdvice.posture(GuideAdvice.pitchDegrees(0f, 2f, 9.5f)))
    }

    @Test fun aCameraPointedAtTheCeilingIsNoticed() {
        assertTrue(GuideAdvice.posture(GuideAdvice.pitchDegrees(0f, 3f, -9f))!!.contains("up"))
    }

    @Test fun onlyRealDarknessIsReported() {
        assertNotNull(GuideAdvice.light(1f))
        assertNull(GuideAdvice.light(120f))
        assertNull(GuideAdvice.light(-1f)) // no reading yet
    }
}
