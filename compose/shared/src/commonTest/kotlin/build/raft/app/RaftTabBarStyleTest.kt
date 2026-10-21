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
    }

    @Test
    fun phoneBarIsTheFullWidthEdgeBar() {
        // stdrc 2026-10-21: back to the 58dp edge bar (mockup A, pre-#3340) with five slots.
        val spec = RaftBottomTabBarSpec
        assertEquals(58.dp, spec.BarHeight)
        assertEquals(7.dp, spec.TopPadding)
        assertEquals(5, AppShellPhoneBottomBarKeys.size)
        // Five equal slots on a 320dp phone still fit the 56dp pill and a >= 44dp target.
        val slotWidth = 320.dp / AppShellPhoneBottomBarKeys.size
        assertTrue(slotWidth >= spec.PillWidth, "slot width $slotWidth")
        // The tab cell (1dp seam + top padding + sticker slot + gap + label) fits the bar.
        assertTrue(
            1.dp + spec.TopPadding + spec.PillHeight + spec.LabelGap + spec.LabelLineHeight.value.dp <= spec.BarHeight
        )
        assertTrue(spec.BarHeight >= 44.dp)
    }

    @Test
    fun activityGlyphIsDrawnOnTheSameOpticalBoxAsTheOtherTabs() {
        // Lucide activity's ink spans 2..22 of the 24-unit viewBox; house / square-check-big /
        // search keep to 3..21. Scaled by 18/20 it lands on the same 3..21 box.
        val scale = raftBottomTabIconScale(RaftLucideIconName.Activity)
        assertEquals(0.9f, scale)
        assertEquals(3f, 12f - (12f - 2f) * scale, 0.001f)
        assertEquals(21f, 12f + (22f - 12f) * scale, 0.001f)
        listOf(
            RaftLucideIconName.House,
            RaftLucideIconName.Search,
            RaftLucideIconName.SquareCheckBig,
            RaftLucideIconName.Ellipsis,
        ).forEach { assertEquals(1f, raftBottomTabIconScale(it), "icon=$it") }
    }
}
