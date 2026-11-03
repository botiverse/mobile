package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
 * artin 11-03 (Compose 版, wide window): a two-column table with a short first column and a long
 * second one squeezed the second column to ~160dp, wrapping every cell into four or five lines
 * beside ~1400px of free message width. As on the web (`<table>` in an `overflow-x-auto` box), the
 * table now takes its content's width up to the message width and wraps cells only beyond it.
 * Wide: no cell wraps. Phone: the long column takes the remaining width and wraps there, the short
 * column and the header stay on one line. Latin only (the host has no CJK font). Reports land in
 * `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownTableWidthScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    @Config(sdk = [34], qualifiers = "w1000dp-h300dp-xxhdpi")
    fun wideMessageKeepsCellsOnOneLine() =
        RaftRobolectricScreenshot.capture("markdown.table-width.wide", RaftAppAppearance.Light, reportDir = "build/reports/home-screenshots", activities = activities) {
            TableMessage()
        }

    @Test
    @Config(sdk = [34], qualifiers = "w390dp-h500dp-xxhdpi")
    fun phoneWrapsOnlyTheLongColumn() =
        RaftRobolectricScreenshot.capture("markdown.table-width.phone", RaftAppAppearance.Light, reportDir = "build/reports/home-screenshots", activities = activities) {
            TableMessage()
        }

    @Composable
    private fun TableMessage() {
        val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
            RaftDesignSystemTheme {
                Column(Modifier.fillMaxSize().background(RaftColor.Canvas).padding(12.dp)) {
                    RaftMarkdownBody(
                        body = TableBody,
                        modifier = Modifier.fillMaxWidth(),
                        isTagValid = { true },
                    )
                }
            }
        }
    }

    private companion object {
        const val TableBody =
            "Token fields:\n\n" +
                "| Field | Meaning |\n" +
                "|---|---|\n" +
                "| scope | Which servers the token can reach; leave it empty to allow every server you belong to. |\n" +
                "| expiry | When the token stops working. After that date every request made with it is refused. |\n" +
                "| label | A name for your own records, shown in the token list and in the audit log. |"
    }
}
