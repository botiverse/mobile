package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AnnouncementItem
import build.raft.app.data.AnnouncementPageItem
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * The announcement modal (web AnnouncementModal): one without its own label reads the default
 * "What's new" over its title (slock #12661); one with a label shows that label. Verify / record
 * as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class AnnouncementModalScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun defaultLabelLight() = capture("announcement.modal.default-label", RaftAppAppearance.Light, label = null)

    @Test
    fun defaultLabelDark() = capture("announcement.modal.default-label.dark", RaftAppAppearance.Dark, label = null)

    @Test
    fun ownLabelLight() = capture("announcement.modal.own-label", RaftAppAppearance.Light, label = "Release 1.19")

    private fun capture(name: String, appearance: RaftAppAppearance, label: String?) =
        RaftRobolectricScreenshot.capture(
            name,
            appearance,
            reportDir = "build/reports/home-screenshots",
            activities = activities,
            root = { activity -> dialogFittedToItsWindow() ?: activity.window.decorView },
        ) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    SharedAnnouncementModal(
                        announcement =
                            AnnouncementItem(
                                id = "visual-announcement",
                                title = "Raft 1.19",
                                body = "",
                                label = label,
                                pages =
                                    listOf(
                                        AnnouncementPageItem(
                                            title = "Move to section",
                                            body = "Pick where a channel or a direct message sits in your sidebar from its settings."
                                        )
                                    )
                            ),
                        onDismiss = {}
                    )
                }
            }
        }

    /** As [RaftHomeSectionDialogScreenshotTest]: the dialog window, its content measured to the window. */
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
