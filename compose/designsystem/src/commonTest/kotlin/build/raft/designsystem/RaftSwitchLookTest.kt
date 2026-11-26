package build.raft.designsystem

import androidx.compose.ui.graphics.Color
import build.raft.designsystem.components.RaftSwitch
import build.raft.designsystem.components.raftSwitchLook
import build.raft.designsystem.tokens.RaftOpacity
import build.raft.designsystem.tokens.RaftUiPalette
import build.raft.designsystem.tokens.RaftUiThemeColors
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `botiverse/raft-design#1462` parity: a disabled checked [RaftSwitch] keeps the on-state yellow
 * sticker look (including dark-mode die-cut edge and white knob) faded as a whole to
 * [RaftOpacity.Dimmed], while disabled unchecked stays flat gray.
 */
class RaftSwitchLookTest {
    @Test
    fun disabledCheckedKeepsOnStickerColorsFadedToDimmedInLightAndDark() {
        listOf(RaftUiThemeColors.RaftLight, RaftUiThemeColors.RaftDark).forEach { colors ->
            val enabledOn = raftSwitchLook(checked = true, enabled = true, colors = colors)
            val disabledOn = raftSwitchLook(checked = true, enabled = false, colors = colors)

            assertEquals(RaftUiPalette.selected, enabledOn.track)
            assertEquals(RaftUiPalette.stickerInk, enabledOn.trackEdge)
            assertEquals(colors.stickerEdge, enabledOn.dieCut)
            assertEquals(RaftUiPalette.stickerPaper, enabledOn.thumb)
            assertEquals(RaftUiPalette.stickerInk, enabledOn.thumbRing)
            assertEquals(1f, enabledOn.alpha)

            assertEquals(enabledOn.copy(alpha = RaftOpacity.Dimmed), disabledOn)
        }
    }

    @Test
    fun uncheckedStatesStayUnfadedWithEnabledVsDisabledEdges() {
        listOf(RaftUiThemeColors.RaftLight, RaftUiThemeColors.RaftDark).forEach { colors ->
            val enabledOff = raftSwitchLook(checked = false, enabled = true, colors = colors)
            assertEquals(colors.fillMuted, enabledOff.track)
            assertEquals(colors.lineStrong, enabledOff.trackEdge)
            assertEquals(Color.Transparent, enabledOff.dieCut)
            assertEquals(colors.layerCard, enabledOff.thumb)
            assertEquals(colors.lineStrong, enabledOff.thumbRing)
            assertEquals(1f, enabledOff.alpha)

            val disabledOff = raftSwitchLook(checked = false, enabled = false, colors = colors)
            assertEquals(colors.fillMuted, disabledOff.track)
            assertEquals(colors.foregroundDisabled, disabledOff.trackEdge)
            assertEquals(Color.Transparent, disabledOff.dieCut)
            assertEquals(colors.fillMuted, disabledOff.thumb)
            assertEquals(colors.foregroundDisabled, disabledOff.thumbRing)
            assertEquals(1f, disabledOff.alpha)
        }
    }
}
