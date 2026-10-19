package ai.visionmirror

import ai.visionmirror.guide.SceneChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneChangeTest {
    private fun grid(f: (Int) -> Int) = IntArray(SceneChange.COLUMNS * SceneChange.ROWS) { f(it).coerceIn(0, 255) }

    @Test fun theSameViewIsNotDifferent() {
        val a = grid { (it * 7) % 200 }
        assertEquals(0.0, SceneChange.difference(a, a.copyOf()), 0.0001)
    }

    @Test fun aLightSwitchedOnIsNotANewScene() {
        val a = grid { (it * 7) % 150 }
        val brighter = grid { (it * 7) % 150 + 60 }
        assertTrue(SceneChange.difference(a, brighter) < 0.02)
    }

    @Test fun aDifferentViewIsDifferent() {
        val a = grid { if (it % SceneChange.COLUMNS < 8) 20 else 220 }
        val b = grid { if (it % SceneChange.COLUMNS < 8) 220 else 20 }
        assertTrue(SceneChange.difference(a, b) > 0.5)
    }

    @Test fun whenToLookAgain() {
        // nothing seen yet: look
        assertTrue(SceneChange.shouldLook(null, 0, 20_000, 60_000))
        // a new view, but too soon after the last look
        assertFalse(SceneChange.shouldLook(0.5, 10_000, 20_000, 60_000))
        // a new view, and enough time has passed
        assertTrue(SceneChange.shouldLook(0.5, 25_000, 20_000, 60_000))
        // the same view: not yet, then after a minute anyway
        assertFalse(SceneChange.shouldLook(0.01, 40_000, 20_000, 60_000))
        assertTrue(SceneChange.shouldLook(0.01, 61_000, 20_000, 60_000))
    }
}
