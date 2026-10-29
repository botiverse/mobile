package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.emoji.RaftEmojiCatalog
import build.raft.app.emoji.RaftEmojiInMemoryPreferenceStore
import build.raft.app.emoji.RaftEmojiPickerPreferences
import build.raft.app.emoji.RaftEmojiSkinTone
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * The Add Reaction emoji picker (Web's emoji-picker-react set), light and dark: search and skin
 * tone, the category tabs, "Frequently Used" from stored picks, then Smileys & People; a search
 * for "heart"; the skin tone choices open with Medium chosen (the grid in that tone).
 * Filtered as on a device ([RaftEmojiCatalog.drawable] with the real `Paint.hasGlyph`): the
 * emoji font Robolectric bundles for this SDK is fixed, so the goldens stay stable and show what
 * an Android 14 phone shows, with no empty boxes. Reports land in `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class RaftEmojiPickerScreenshotTest {
    private val drawableCatalog by lazy { RaftEmojiCatalog.All.drawable() }
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun pickerLight() = picker("thread.emoji-picker", RaftAppAppearance.Light)

    @Test
    fun pickerDark() = picker("thread.emoji-picker.dark", RaftAppAppearance.Dark)

    @Test
    fun searchLight() = picker("thread.emoji-picker.search", RaftAppAppearance.Light, query = "heart")

    @Test
    fun skinToneLight() =
        picker("thread.emoji-picker.skin-tone", RaftAppAppearance.Light, tone = RaftEmojiSkinTone.Medium, toneMenuOpen = true)

    private fun picker(
        name: String,
        appearance: RaftAppAppearance,
        query: String = "",
        tone: RaftEmojiSkinTone = RaftEmojiSkinTone.Neutral,
        toneMenuOpen: Boolean = false,
    ) = RaftRobolectricScreenshot.capture(
        name,
        appearance,
        reportDir = "build/reports/home-screenshots",
        activities = activities,
        root = { dialogFittedToItsWindow() },
    ) {
        Page {
            val preferences = remember { fixturePreferences(tone) }
            RaftUnifiedDialog(title = "Add Reaction", onDismiss = {}, contentSpacing = 12.dp) {
                RaftEmojiPickerPanel(
                    preferences = preferences,
                    catalog = drawableCatalog,
                    onSelect = {},
                    initialQuery = query,
                    initialToneMenuOpen = toneMenuOpen,
                )
            }
        }
    }

    /** Stored picks: 👍 three times, 🎉 twice, then ❤️, 😂 and 👀 once (the row shows them by count). */
    private fun fixturePreferences(tone: RaftEmojiSkinTone): RaftEmojiPickerPreferences {
        val preferences = RaftEmojiPickerPreferences(RaftEmojiInMemoryPreferenceStore(), "fixture-owner")
        val catalog = RaftEmojiCatalog.All
        listOf("1f440", "1f602", "2764-fe0f", "1f389", "1f389", "1f44d", "1f44d", "1f44d").forEach { unified ->
            preferences.recordPick(requireNotNull(catalog.byUnified(unified)))
        }
        preferences.writeSkinTone(tone)
        return preferences
    }

    @Composable
    private fun Page(content: @Composable () -> Unit) {
        val pager =
            remember {
                object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
            }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) { RaftDesignSystemTheme { content() } }
    }

    /** As [TaskCreateFromMessageDialogScreenshotTest]: the dialog window, its content measured to the window. */
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
