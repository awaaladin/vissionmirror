package ai.visionmirror

import ai.visionmirror.design.halo.HaloSpec
import ai.visionmirror.design.halo.HaloState
import ai.visionmirror.haptics.proximityIntervalMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HaloSpecTest {
    private val states = listOf(
        HaloState.Idle, HaloState.Guiding, HaloState.Speaking,
        HaloState.Listening, HaloState.Analysing, HaloState.Ready,
    )

    @Test fun guidingTightensAsFramingImproves() {
        val base = HaloSpec.base(HaloState.Guiding)
        val far = HaloSpec.live(HaloState.Guiding, base, 0f, 0f, reduceMotion = false)
        val near = HaloSpec.live(HaloState.Guiding, base, 1f, 0f, reduceMotion = false)
        assertTrue(near.radius < far.radius)
        assertTrue(near.glow > far.glow)
    }

    @Test fun reduceMotionKeepsBrightnessButNotSize() {
        for (s in states) {
            val base = HaloSpec.base(s)
            val live = HaloSpec.live(s, base, 1f, 1f, reduceMotion = true)
            assertEquals("radius must be static for $s", base.radius, live.radius, 0f)
        }
        val base = HaloSpec.base(HaloState.Speaking)
        assertTrue(HaloSpec.live(HaloState.Speaking, base, 1f, 0f, true).glow > base.glow)
    }

    @Test fun onlyListeningUsesQuartzAndRipples() {
        for (s in states) {
            val b = HaloSpec.base(s)
            assertEquals(s == HaloState.Listening, b.quartzMix == 1f)
            assertEquals(s == HaloState.Listening, b.ripples > 0f)
        }
    }

    @Test fun onlyAnalysingShimmers() {
        for (s in states) assertEquals(s == HaloState.Analysing, HaloSpec.base(s).shimmer > 0f)
    }

    @Test fun proximityTicksSpeedUpAsCloser() {
        assertTrue(proximityIntervalMs(1f) < proximityIntervalMs(0.5f))
        assertTrue(proximityIntervalMs(0.5f) < proximityIntervalMs(0f))
        assertEquals(700L, proximityIntervalMs(-3f))
    }
}
