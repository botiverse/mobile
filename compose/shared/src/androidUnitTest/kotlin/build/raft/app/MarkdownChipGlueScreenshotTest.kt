package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import build.raft.app.markdown.ThreadMarkdownTagKind
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Reference chips never wrap inside (web `inline-flex … truncate`), and the glue that keeps them
 * whole never splits an emoji: a mention whose name carries a ZWJ sequence or a VS16 heart, a
 * hyphenated channel and a CJK thread ref, at three widths so each chip meets a line end. Plain
 * text around them still wraps after a hyphen and not after a slash (the web's breaks).
 * Reports land in `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h640dp-xxhdpi")
class MarkdownChipGlueScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun chipGlue() =
        RaftRobolectricScreenshot.capture("thread.chip-glue", RaftAppAppearance.Light, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    Column(
                        Modifier.fillMaxSize().background(RaftColor.Canvas).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        listOf(200.dp, 236.dp, 272.dp).forEach { width ->
                            RaftMarkdownBody(
                                body = chipGlueBody,
                                modifier = Modifier.width(width),
                                isTagValid = { true },
                                resolveTagLabel = { tag ->
                                    when {
                                        tag.kind != ThreadMarkdownTagKind.UserMention -> tag.label
                                        tag.label.equals("@dev", ignoreCase = true) -> "@Dev 👩\u200D💻"
                                        tag.label.equals("@lily", ignoreCase = true) -> "@Lily ❤\uFE0F"
                                        else -> tag.label
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
}

private val chipGlueBody =
    "Ping @dev and @lily about #visual-testing and the read-state fix in traversal/layout " +
        "before #对话流专修 ships."
