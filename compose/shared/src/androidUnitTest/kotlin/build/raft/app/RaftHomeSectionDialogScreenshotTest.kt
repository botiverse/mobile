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
 * The custom sidebar section dialog (web `SidebarSectionDialog` + `SidebarSectionEmojiPicker`):
 * the Name field with the emoji picker trigger at its start: smile-plus with no emoji (new
 * section), light and dark, and the section's emoji when it has one (edit). Verify / record as
 * [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class RaftHomeSectionDialogScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun newSectionLight() = capture("home.section-dialog.new", RaftAppAppearance.Light, sectionName = "", emoji = "", create = true)

    @Test
    fun newSectionDark() = capture("home.section-dialog.new.dark", RaftAppAppearance.Dark, sectionName = "", emoji = "", create = true)

    @Test
    fun editWithEmojiLight() =
        capture("home.section-dialog.edit", RaftAppAppearance.Light, sectionName = "Design lab", emoji = "\uD83D\uDE80", create = false)

    private fun capture(name: String, appearance: RaftAppAppearance, sectionName: String, emoji: String, create: Boolean) =
        RaftRobolectricScreenshot.capture(
            name,
            appearance,
            reportDir = "build/reports/home-screenshots",
            activities = activities,
            root = { dialogFittedToItsWindow() },
        ) {
            Page {
                RaftHomeSectionDialog(
                    title = if (create) "New section" else "Edit section",
                    submitLabel = if (create) "Create" else null,
                    nameLabel = "Name",
                    namePlaceholder = "My Project",
                    initialName = sectionName,
                    initialEmoji = emoji,
                    onDismiss = {},
                    onSubmit = { _, _ -> },
                    // A still capture: no caret blinking in the field.
                    autoFocus = false,
                )
            }
        }

    @Composable
    private fun Page(content: @Composable () -> Unit) {
        val pager =
            remember {
                object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
            }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) { RaftDesignSystemTheme { content() } }
    }

    /** As [RaftEmojiPickerScreenshotTest]: the dialog window, its content measured to the window. */
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
