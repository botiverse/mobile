package build.raft.app

import build.raft.app.settings.SettingsDeveloperUnlockTaps
import kotlin.test.Test
import kotlin.test.assertEquals

/** The hidden Developer entry: 7 taps on the Settings header or About's version row. */
class SettingsDeveloperUnlockTapsTest {
    @Test fun `the seventh tap unlocks and the count starts over`() {
        val taps = SettingsDeveloperUnlockTaps()
        assertEquals(List(6) { false } + true, List(7) { taps.tap() })
        assertEquals(List(6) { false } + true, List(7) { taps.tap() })
    }
}
