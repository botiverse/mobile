package build.raft.designsystem

import build.raft.designsystem.components.RaftRiskTone
import build.raft.designsystem.components.RaftRowTone
import build.raft.designsystem.components.raftListRowInks
import build.raft.designsystem.tokens.RaftThemeId
import build.raft.designsystem.tokens.RaftUiThemeColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** raft-ui 0.87.0 ListRow `tone="warning" | "danger"`: an action's risk. */
class RaftListRowRiskTest {
    @Test
    fun aRiskColorsTheTitleAndIconOnlyInBothModes() {
        listOf(false, true).forEach { dark ->
            val colors = RaftUiThemeColors.of(RaftThemeId.Raft, dark)
            val plain = raftListRowInks(RaftRowTone.Default, enabled = true, colors = colors)
            val danger = raftListRowInks(RaftRowTone.Default, enabled = true, colors = colors, risk = RaftRiskTone.Danger)
            val warning = raftListRowInks(RaftRowTone.Default, enabled = true, colors = colors, risk = RaftRiskTone.Warning)
            assertEquals(colors.dangerStrong, danger.ink)
            assertEquals(colors.dangerStrong, danger.riskInk)
            assertEquals(colors.warningStrong, warning.ink)
            assertEquals(colors.warningStrong, warning.riskInk)
            // The description, the value and the chevron stay as on any row.
            assertEquals(plain.ink2, danger.ink2)
            assertEquals(plain.ink3, danger.ink3)
        }
    }

    @Test
    fun aDisplayOnlyOrDisabledRowTakesNoRisk() {
        val colors = RaftUiThemeColors.of(RaftThemeId.Raft, false)
        val display = raftListRowInks(RaftRowTone.Default, enabled = true, colors = colors, risk = RaftRiskTone.Danger, interactive = false)
        assertNull(display.riskInk)
        assertEquals(colors.foreground, display.ink)
        val disabled = raftListRowInks(RaftRowTone.Default, enabled = false, colors = colors, risk = RaftRiskTone.Danger)
        assertNull(disabled.riskInk)
        assertEquals(colors.foregroundDisabled, disabled.ink)
    }
}
