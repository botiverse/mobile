package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The hidden Developer entry: 7 taps on the Settings header or About's version row. */
class SettingsDeveloperUnlockTapsTest {
    @Test fun `the seventh tap unlocks and the count starts over`() {
        val taps = SettingsDeveloperUnlockTaps()
        assertEquals(List(6) { false } + true, List(7) { taps.tap() })
        assertEquals(List(6) { false } + true, List(7) { taps.tap() })
    }

    @Test fun `a reset forgets earlier taps`() {
        val taps = SettingsDeveloperUnlockTaps()
        repeat(6) { assertFalse(taps.tap()) }
        taps.reset()
        repeat(6) { assertFalse(taps.tap()) }
        assertTrue(taps.tap())
    }
}
