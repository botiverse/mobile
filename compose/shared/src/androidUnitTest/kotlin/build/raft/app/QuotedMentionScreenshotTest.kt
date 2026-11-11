package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import build.raft.app.markdown.RaftMarkdownBody
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * botiverse/slock #14349: an `@handle` inside a block quote is someone else's words. The quoted
 * `@ada` and the reader's quoted `@artin` are drawn as quote text (no chip, no yellow sticker);
 * the `#general` chip in the quote stays; the reply's `@ada` after the quote and the unquoted line
 * that ends the next quote are mention chips. Latin only (the host has no CJK font). Reports land
 * in `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h400dp-xxhdpi")
class QuotedMentionScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun quotedMentionsAreText() =
        RaftRobolectricScreenshot.capture("markdown.quoted-mention", RaftAppAppearance.Light, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize().background(RaftColor.Canvas).padding(12.dp)) {
                        RaftMarkdownBody(
                            body = "> @ada asked @artin to check #general\n\n@ada done, see above.\n\n> quoted @ada\n@artin after the quote",
                            modifier = Modifier.width(360.dp),
                            highlightedMentions = setOf("artin"),
                            isTagValid = { true },
                        )
                    }
                }
            }
        }
}
