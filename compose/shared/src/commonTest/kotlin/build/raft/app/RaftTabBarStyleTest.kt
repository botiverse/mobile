package build.raft.app

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import androidx.compose.ui.text.font.FontWeight
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RaftTabBarStyleTest {
    @Test
    fun settingsSectionTitleMatchesReactSidebarToken() {
        // raft-ui SidebarGroupLabel: text-footnote (13/18) bold, source case, no tracking.
        assertEquals(13.sp, RaftVerticalTabSectionTextStyle.fontSize)
        assertEquals(18.sp, RaftVerticalTabSectionTextStyle.lineHeight)
        assertEquals(0.sp, RaftVerticalTabSectionTextStyle.letterSpacing)
        assertEquals(RaftType.SectionLabel.fontFamily, RaftVerticalTabSectionTextStyle.fontFamily)
        assertEquals(RaftColor.TextBlack.copy(alpha = 0.4f), RaftVerticalTabSectionTextStyle.color)
        assertEquals(4.dp, RaftVerticalTabSectionBottomPadding)
    }

    @Test
    fun bottomTabBarMatchesApprovedMockupGeometry() {
        // /tmp/activity-tab-mockups/gen.js `.tabbar` / `.tab .pill` / `.tab .tb` / `.badge`.
        val spec = RaftBottomTabBarSpec
        assertEquals(56.dp, spec.PillWidth)
        assertEquals(32.dp, spec.PillHeight)
        assertEquals(9.dp, spec.PillRadius)
        assertEquals(2.dp, spec.PillBorder)
        assertEquals(23.dp, spec.IconSize)
        assertEquals(4.dp, spec.LabelGap)
        assertEquals(11.5.sp, spec.LabelFontSize)
        assertEquals(FontWeight.Bold, raftBottomTabLabelWeight(selected = true))
        assertEquals(FontWeight.SemiBold, raftBottomTabLabelWeight(selected = false))
        assertEquals(18.dp, spec.BadgeHeight)
        assertEquals(20.dp, spec.BadgeMinWidth)
        assertEquals(5.dp, spec.BadgeRadius)
        assertEquals(1.5.dp, spec.BadgeBorder)
        assertEquals(11.sp, spec.BadgeFontSize)
        // CSS `left:33px; top:-5px` inside the pill's 2px border = 35 / -3 from the outer corner.
        assertEquals(35.dp, spec.BadgeOffsetX)
        assertEquals((-3).dp, spec.BadgeOffsetY)
    }

    @Test
    fun bottomTabBadgeSitsOnPillCornerClearOfCenteredIcon() {
        val spec = RaftBottomTabBarSpec
        val iconRight = (spec.PillWidth + spec.IconSize) / 2
        val iconTop = (spec.PillHeight - spec.IconSize) / 2
        // Badge starts right of the icon's horizontal centre-line and straddles the pill top edge.
        assertTrue(spec.BadgeOffsetX > spec.PillWidth / 2)
        assertTrue(spec.BadgeOffsetY < 0.dp)
        assertTrue(spec.BadgeOffsetX + spec.BadgeMinWidth > spec.PillWidth - spec.PillBorder)
        // The badge's bottom edge stays in the icon's top band, leaving the icon readable.
        assertTrue(spec.BadgeOffsetY + spec.BadgeHeight < iconTop + spec.IconSize / 2)
        assertTrue(iconRight < spec.PillWidth)
        // The tab cell (sticker slot + gap + one label line) fits inside the pill's border.
        assertTrue(
            spec.PillHeight + spec.LabelGap + spec.LabelLineHeight.value.dp <= spec.BarHeight - spec.ContainerBorder * 2
        )
    }

    @Test
    fun phoneBarFollowsJasperSizes() {
        // Jasper 2026-10-20: pill and Search 56dp tall, tabs >= 44dp, 8dp gap, 16dp margins.
        val spec = RaftBottomTabBarSpec
        assertEquals(56.dp, spec.BarHeight)
        assertEquals(56.dp, spec.AccessorySize)
        assertEquals(44.dp, spec.MinTabTarget)
        assertEquals(8.dp, spec.SearchGap)
        assertEquals(16.dp, spec.SideMargin)
        // Four tabs keep a >= 44dp target on a 320dp phone.
        val pillWidth = 320.dp - spec.SideMargin * 2 - spec.SearchGap - spec.AccessorySize
        val tabWidth = (pillWidth - spec.PillInnerPadding * 2) / AppShellPhoneBottomTabKeys.size
        assertTrue(tabWidth >= spec.MinTabTarget, "tab width $tabWidth")
        // The tab cell fills the pill's height, so its hit area is >= 44dp tall too.
        assertTrue(spec.BarHeight >= spec.MinTabTarget)
    }
}
