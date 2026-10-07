package ai.visionmirror

import ai.visionmirror.guidance.FaceObservation
import ai.visionmirror.guidance.FramingGuidance
import ai.visionmirror.guidance.Guidance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FramingGuidanceTest {
    private val g = FramingGuidance()

    // Face centred horizontally, a third of the way down, 20% of the frame tall: the target.
    private fun face(cx: Float = 0.5f, cy: Float = 0.32f, h: Float = 0.20f) =
        FaceObservation(cx, cy, h * 0.75f, h)

    @Test fun noFaceSaysSo() {
        val r = g.evaluate(null)
        assertEquals(Guidance.NoFace, r.guidance)
        assertEquals(0f, r.quality, 0f)
    }

    @Test fun targetIsPerfectWithFullQuality() {
        val r = g.evaluate(face())
        assertEquals(Guidance.Perfect, r.guidance)
        assertEquals(1f, r.quality, 0f)
    }

    @Test fun tooBigMeansStepBack_tooSmallMeansCloser() {
        assertEquals(Guidance.StepBack, g.evaluate(face(h = 0.35f)).guidance)
        assertEquals(Guidance.StepCloser, g.evaluate(face(h = 0.10f)).guidance)
    }

    @Test fun faceOnTheRightMeansMoveLeft_inThePreview() {
        // Preview is mirrored, so a face drawn on the right is her right: she should move left.
        assertEquals(Guidance.MoveLeft, g.evaluate(face(cx = 0.75f)).guidance)
        assertEquals(Guidance.MoveRight, g.evaluate(face(cx = 0.25f)).guidance)
    }

    @Test fun faceLowInFrameMeansLowerThePhone() {
        assertEquals(Guidance.LowerPhone, g.evaluate(face(cy = 0.55f)).guidance)
        assertEquals(Guidance.RaisePhone, g.evaluate(face(cy = 0.10f)).guidance)
    }

    @Test fun distanceIsFixedBeforeSideToSide() {
        // Both too close AND off-centre: fix distance first.
        assertEquals(Guidance.StepBack, g.evaluate(face(cx = 0.8f, h = 0.4f)).guidance)
    }

    @Test fun qualityRisesAsFramingImproves() {
        val far = g.evaluate(face(cx = 0.85f, cy = 0.6f, h = 0.4f)).quality
        val mid = g.evaluate(face(cx = 0.65f, cy = 0.4f, h = 0.26f)).quality
        val close = g.evaluate(face(cx = 0.56f, cy = 0.34f, h = 0.21f)).quality
        assertTrue("$far < $mid", far < mid)
        assertTrue("$mid < $close", mid < close)
    }

    @Test fun onlyPerfectReportsFullQuality() {
        assertTrue(g.evaluate(face(cx = 0.6f)).quality < 1f)
    }

    @Test fun boxToObservationMirrorsHorizontally() {
        // A face at the left of the raw (unmirrored) image appears on the right of the mirrored preview.
        val o = FaceObservation.fromBox(100f, 100f, 300f, 400f, 1000f, 1000f, mirror = true)
        assertEquals(0.8f, o.centerX, 1e-4f)
        assertEquals(0.25f, o.centerY, 1e-4f)
        assertEquals(0.3f, o.height, 1e-4f)
        val raw = FaceObservation.fromBox(100f, 100f, 300f, 400f, 1000f, 1000f, mirror = false)
        assertEquals(0.2f, raw.centerX, 1e-4f)
    }

    @Test fun largestFacePicksTheNearestPerson() {
        val big = face(h = 0.3f)
        val small = face(h = 0.1f)
        assertEquals(big, FaceObservation.largest(listOf(small, big)))
        assertEquals(null, FaceObservation.largest(emptyList()))
    }
}
