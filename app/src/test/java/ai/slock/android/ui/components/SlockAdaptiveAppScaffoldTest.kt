package ai.slock.android.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class SlockAdaptiveAppScaffoldTest {
    @Test
    fun compactWidthUsesBottomBarSinglePaneMode() {
        val layout = slockAdaptiveAppLayoutInfo(width = 599.dp, height = 800.dp)

        assertEquals(SlockWindowLayoutMode.CompactSinglePane, layout.mode)
        assertEquals(SlockAdaptiveNavigationPlacement.BottomBar, layout.navigationPlacement)
    }

    @Test
    fun mediumWidthUsesNavigationRailListDetailMode() {
        val layout = slockAdaptiveAppLayoutInfo(width = 600.dp, height = 800.dp)

        assertEquals(SlockWindowLayoutMode.MediumListDetail, layout.mode)
        assertEquals(SlockAdaptiveNavigationPlacement.NavigationRail, layout.navigationPlacement)
    }

    @Test
    fun expandedWidthUsesNavigationRailThreePaneMode() {
        val layout = slockAdaptiveAppLayoutInfo(width = 840.dp, height = 900.dp)

        assertEquals(SlockWindowLayoutMode.ExpandedThreePane, layout.mode)
        assertEquals(SlockAdaptiveNavigationPlacement.NavigationRail, layout.navigationPlacement)
    }

    @Test
    fun wideWidthCanUseFloatingDockNavigation() {
        val layout =
            slockAdaptiveAppLayoutInfo(
                width = 840.dp,
                height = 900.dp,
                largeScreenNavigationStyle = SlockLargeScreenNavigationStyle.FloatingDock
            )

        assertEquals(SlockWindowLayoutMode.ExpandedThreePane, layout.mode)
        assertEquals(SlockAdaptiveNavigationPlacement.FloatingDock, layout.navigationPlacement)
    }

    @Test
    fun compactWidthKeepsBottomBarWhenFloatingDockIsSelected() {
        val layout =
            slockAdaptiveAppLayoutInfo(
                width = 599.dp,
                height = 800.dp,
                largeScreenNavigationStyle = SlockLargeScreenNavigationStyle.FloatingDock
            )

        assertEquals(SlockWindowLayoutMode.CompactSinglePane, layout.mode)
        assertEquals(SlockAdaptiveNavigationPlacement.BottomBar, layout.navigationPlacement)
    }

    @Test
    fun wideLayoutKeepsNavigationRailWhenPageNavigationIsHidden() {
        val layout = slockAdaptiveAppLayoutInfo(width = 840.dp, height = 900.dp)

        assertEquals(true, slockAdaptiveShowsNavigationRail(layout))
    }

    @Test
    fun compactLayoutHidesNavigationWhenPageNavigationIsHidden() {
        val layout = slockAdaptiveAppLayoutInfo(width = 599.dp, height = 800.dp)

        assertEquals(false, slockAdaptiveShowsNavigationRail(layout))
    }
}
