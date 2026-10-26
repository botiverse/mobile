package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.InboxFilter
import build.raft.designsystem.components.RaftMenuSurface
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Activity toolbar's Filter (slock #11383, stdrc option A), light and dark: no filter (the
 * button alone), the menu open over All (All / Unread / Mentions, a separator, Pinned; the
 * current one checked) and an applied filter (Mentions) with its chip beside the button, Mark
 * all read at the row's end. The open menu is the real rows on the real surface, drawn in the
 * page under the button (in the app it is its own popup window, which the capture can't see).
 * Reports land in `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h300dp-xxhdpi")
class ActivityFilterBarScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun noFilterLight() = capture("screens.activity.filter.none", RaftAppAppearance.Light, InboxFilter.All)

    @Test
    fun noFilterDark() = capture("screens.activity.filter.none.dark", RaftAppAppearance.Dark, InboxFilter.All)

    @Test
    fun menuOpenLight() = capture("screens.activity.filter.menu", RaftAppAppearance.Light, InboxFilter.All, menuOpen = true)

    @Test
    fun menuOpenDark() = capture("screens.activity.filter.menu.dark", RaftAppAppearance.Dark, InboxFilter.All, menuOpen = true)

    @Test
    fun activeChipLight() = capture("screens.activity.filter.chip", RaftAppAppearance.Light, InboxFilter.Mentions)

    @Test
    fun activeChipDark() = capture("screens.activity.filter.chip.dark", RaftAppAppearance.Dark, InboxFilter.Mentions)

    private fun capture(name: String, appearance: RaftAppAppearance, filter: InboxFilter, menuOpen: Boolean = false) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
                        Column {
                            SharedActivityFilterBar(
                                selectedFilter = filter,
                                totalUnreadCount = 3,
                                onFilterSelected = {},
                                onMarkAllRead = {},
                            )
                        }
                        if (menuOpen) {
                            // RaftDropdownMenu's place: under the 32dp button (centred in the 53dp
                            // row, 16dp in), 6dp below it.
                            RaftMenuSurface(Modifier.offset(x = 16.dp, y = 48.dp)) {
                                ActivityFilterMenuRows(selectedFilter = filter, onPick = {})
                            }
                        }
                    }
                }
            }
        }
}
