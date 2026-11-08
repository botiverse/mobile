package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.markdown.ThreadMarkdownTag
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * artin: the app does not render the web's message-reference chip. All four forms in a message,
 * light and dark, as the web's yellow sticker with the literal target: `#triage msg=…`,
 * `#triage:short msg=…`, `dm:@Lagoon msg=…`, `dm:@Lagoon:short msg=…`. `#nope msg=…` names a channel
 * the reader does not have and stays plain text, `` `#triage msg=…` `` is code, and with an invalid
 * id only `#triage` is a chip. Plain `#triage` and `#triage:short` chips sit next to them.
 * Reports land in `build/reports/thread-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h900dp-xxhdpi")
class ThreadMessageRefChipsScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun messageRefChipsLight() = capture("thread.message-ref-chips", RaftAppAppearance.Light)

    @Test
    fun messageRefChipsDark() = capture("thread.message-ref-chips.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) {
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/thread-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme { MessageRefChipRows() }
            }
        }
    }
}

private fun isMessageRefChipValid(tag: ThreadMarkdownTag): Boolean =
    tag.isKnownThreadMarkdownTag(
        knownChannelNames = setOf("triage"),
        knownMentionNames = emptySet(),
        knownDirectMessagePeerNames = setOf("lagoon"),
    )

private val messageRefBodies =
    listOf(
        "看一下 #triage msg=2cebeb18 的讨论，还有 #triage:a8293493 msg=ed2eee8c。",
        "Ask dm:@Lagoon msg=ed2eee8c or dm:@Lagoon:1f1def75 msg=3d6b824f-d8c1-420e-8a04-7d21fee6fb1f about it.",
        "#nope msg=2cebeb18 is not a channel here, `#triage msg=2cebeb18` is code, #triage msg=! has an invalid id.",
        "#triage and #triage:a8293493 stay as they are when other text follows.",
    )

@Composable
private fun MessageRefChipRows() {
    val rows =
        messageRefBodies.mapIndexed { index, body ->
            RaftThreadMessageItem(
                id = "message-refs-$index",
                authorId = "user-artin",
                authorName = "artin",
                authorKind = "human",
                avatarLabel = "A",
                timestampLabel = "2026-11-06T09:4$index:00Z",
                body = body,
            )
        }
    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        rows.forEachIndexed { index, message ->
            val suppress = shouldSuppressCompactThreadAuthor(rows.getOrNull(index - 1), message, localOffsetMinutes = 0)
            ThreadMessageCard(
                message = message,
                messageBodyTextStyle = raftMessageBodyTextStyle("md"),
                highlightedMentions = emptySet(),
                compactConsecutiveMessages = true,
                suppressRepeatedAuthor = suppress,
                timestampLabel = "9:4$index AM",
                onShowActions = {},
                onOpenMember = {},
                onOpenThread = {},
                onOpenTask = {},
                onOpenTaskStatusMenu = {},
                onToggleReaction = {},
                onOpenAttachment = {},
                onOpenMarkdownTag = { _, _ -> },
                onOpenMarkdownUrl = {},
                isMarkdownTagValid = ::isMessageRefChipValid,
            )
        }
    }
}
