package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Select Text sheet (web botiverse/slock #11467) on a phone, light and dark: title with ×,
 * the hint (long-press, no dragging), "sender · time", the message's Markdown source as plain
 * text in the platform text view, nothing selected, and Copy / Quote side by side. The window
 * caps the sheet at 82% of its height; the fixture draws the surface alone at the bottom of the
 * screen with that cap. Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h760dp-xxhdpi")
class ThreadMessageSelectTextSheetScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun sheetLight() = capture("thread.select-text.sheet", RaftAppAppearance.Light)

    @Test
    fun sheetDark() = capture("thread.select-text.sheet.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas), contentAlignment = Alignment.BottomCenter) {
                        ThreadMessageSelectTextSheetContent(
                            markdown = SheetMarkdown,
                            senderName = "Kirby",
                            timeLabel = "14:53",
                            textStyle = rememberRaftMessageBodyTextStyle(null),
                            onDismiss = {},
                            onCopy = {},
                            onQuote = {},
                            inWindow = false,
                            modifier = Modifier.heightIn(max = (760 * 0.82f).dp),
                        )
                    }
                }
            }
        }
}

private val SheetMarkdown =
    """
    Select Text is in: long-press a message, pick **Select Text**, then
    - Copy takes the selection or the whole message
    - Quote puts `> …` in the composer

    See https://raft-design.botiverse.build/drawer
    """.trimIndent()
