package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * The upgrade dialog (#proj-mobile #54/#63, web `UpgradeDialog.tsx`): the history limit for an
 * owner (phone sheet and wide card, light and dark), a member's wording, the wait after the web
 * opened, a Pro server out of seats (Add seats), and iOS's limit notice (no upgrade, no link).
 * Reports land in `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class RaftUpgradeDialogScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val owner =
        RaftUpgradeRequest(
            gate = RaftUpgradeGate.History30d,
            mode = RaftUpgradeMode.Upgrade,
            pagerId = "fixture",
            serverId = "server-1",
            serverName = "Acme",
            owner = true,
            webUrl = "https://raft.build/s/acme/settings/billing?upgrade=history_30d&upgradeCanceled=1&lang=en",
        )

    @Test fun historyLight() = capture("upgrade.dialog.history", RaftAppAppearance.Light, owner)

    @Test fun historyDark() = capture("upgrade.dialog.history.dark", RaftAppAppearance.Dark, owner)

    @Test
    @Config(qualifiers = "w1280dp-h800dp-mdpi")
    fun historyWideLight() = capture("upgrade.dialog.history.wide", RaftAppAppearance.Light, owner)

    @Test
    @Config(qualifiers = "w1280dp-h800dp-mdpi")
    fun historyWideDark() = capture("upgrade.dialog.history.wide.dark", RaftAppAppearance.Dark, owner)

    @Test
    fun memberFileSizeLight() =
        capture("upgrade.dialog.member-file-size", RaftAppAppearance.Light, owner.copy(gate = RaftUpgradeGate.UploadFileSize, owner = false))

    @Test
    fun waitingLight() = capture("upgrade.dialog.waiting", RaftAppAppearance.Light, owner.copy(waitingForWeb = true))

    @Test
    fun addSeatsLight() =
        capture("upgrade.dialog.add-seats", RaftAppAppearance.Light, owner.copy(gate = RaftUpgradeGate.SeatLimitInvite, mode = RaftUpgradeMode.AddSeats))

    @Test
    fun addSeatsDark() =
        capture("upgrade.dialog.add-seats.dark", RaftAppAppearance.Dark, owner.copy(gate = RaftUpgradeGate.SeatLimitInvite, mode = RaftUpgradeMode.AddSeats))

    @Test
    @Config(qualifiers = "w1280dp-h800dp-mdpi")
    fun addSeatsWideLight() =
        capture("upgrade.dialog.add-seats.wide", RaftAppAppearance.Light, owner.copy(gate = RaftUpgradeGate.SeatLimitInvite, mode = RaftUpgradeMode.AddSeats))

    @Test
    fun iosLimitNoticeLight() =
        capture("upgrade.dialog.ios-limit", RaftAppAppearance.Light, owner.copy(mode = RaftUpgradeMode.LimitNotice))

    @Test
    fun iosLimitNoticeDark() =
        capture("upgrade.dialog.ios-limit.dark", RaftAppAppearance.Dark, owner.copy(mode = RaftUpgradeMode.LimitNotice))

    private fun capture(name: String, appearance: RaftAppAppearance, request: RaftUpgradeRequest) =
        RaftRobolectricScreenshot.capture(
            name,
            appearance,
            reportDir = "build/reports/home-screenshots",
            activities = activities,
            root = { dialogFittedToItsWindow() },
        ) {
            Page { RaftUpgradeDialog(request = request, onOpenWeb = {}, onDismiss = {}) }
        }

    @Composable
    private fun Page(content: @Composable () -> Unit) {
        val pager =
            remember {
                object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
            }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) { RaftDesignSystemTheme { content() } }
    }

    /** The dialog window with its compose content measured to the window (TaskCreateFromMessageDialogScreenshotTest). */
    private fun dialogFittedToItsWindow(): android.view.View? {
        val decor = ShadowDialog.getLatestDialog()?.window?.decorView ?: return null
        val content = findCompose(decor) ?: return decor
        val parent = content.parent as? android.view.View ?: return decor
        val exactly = android.view.View.MeasureSpec.EXACTLY
        content.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(parent.width, exactly),
            android.view.View.MeasureSpec.makeMeasureSpec(parent.height, exactly),
        )
        content.layout(0, 0, parent.width, parent.height)
        return decor
    }

    private fun findCompose(view: android.view.View): android.view.View? {
        if (view is androidx.compose.ui.platform.ViewRootForTest) return view
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) findCompose(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
