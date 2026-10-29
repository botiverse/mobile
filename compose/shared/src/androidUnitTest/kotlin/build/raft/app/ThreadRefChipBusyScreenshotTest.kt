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
import build.raft.app.markdown.ThreadMarkdownTagKind
import build.raft.app.markdown.toRaftMarkdownInlineContentIdPrefix
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * artin 10-29: a tapped reference chip is busy until its navigation is dispatched — a spinner in
 * place of its leading glyph and the chip dimmed (raft-ui `--opacity-dimmed`). The same message
 * idle, then with its thread ref busy, light and dark. Reports land in
 * `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h200dp-xxhdpi")
class ThreadRefChipBusyScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun idleLight() = capture("thread.ref-chip-busy.idle", RaftAppAppearance.Light, busyRef = null)

    @Test
    fun busyLight() = capture("thread.ref-chip-busy", RaftAppAppearance.Light, busyRef = BusyThreadRef)

    @Test
    fun busyDark() = capture("thread.ref-chip-busy.dark", RaftAppAppearance.Dark, busyRef = BusyThreadRef)

    @Test
    fun busyChannelLight() = capture("thread.ref-chip-busy.channel", RaftAppAppearance.Light, busyRef = BusyChannelRef)

    private fun capture(name: String, appearance: RaftAppAppearance, busyRef: String?) {
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme { BusyRefRow(busyRef) }
            }
        }
    }
}

private const val BusyThreadRef = "#proj-mobile:808b5170"
private const val BusyChannelRef = "#wg-mobile"

private val busyRefBody = "看下 $BusyThreadRef 和 $BusyChannelRef，还有 dm:@Lagoon:34fd5253 的讨论。"

private fun isBusyRefValid(tag: ThreadMarkdownTag): Boolean =
    when (tag.kind) {
        ThreadMarkdownTagKind.Channel, ThreadMarkdownTagKind.ChannelThread ->
            tag.channelName.orEmpty().lowercase() in setOf("proj-mobile", "wg-mobile")
        ThreadMarkdownTagKind.DirectMessageThread -> tag.directMessagePeerName.equals("Lagoon", ignoreCase = true)
        else -> false
    }

@Composable
private fun BusyRefRow(busyRef: String?) {
    val message =
        RaftThreadMessageItem(
            id = "ref-chip-busy",
            authorId = "user-artin",
            authorName = "artin",
            authorKind = "human",
            avatarLabel = "A",
            timestampLabel = "2026-10-29T09:41:00Z",
            body = busyRefBody,
        )
    // The chip's action id: the row's markdown scope and the ref's offset in the body.
    val busyActionId =
        busyRef?.let { ref ->
            val scope = message.threadMarkdownRenderSignature().inlineContentScope
            "${scope.toRaftMarkdownInlineContentIdPrefix()}:${busyRefBody.indexOf(ref)}"
        }
    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        ThreadMessageCard(
            message = message,
            messageBodyTextStyle = raftMessageBodyTextStyle("md"),
            timestampLabel = "9:41 AM",
            activeMarkdownTagKey = busyActionId,
            onShowActions = {},
            onOpenMember = {},
            onOpenThread = {},
            onOpenTask = {},
            onOpenTaskStatusMenu = {},
            onToggleReaction = {},
            onOpenAttachment = {},
            onOpenMarkdownTag = { _, _ -> },
            onOpenMarkdownUrl = {},
            isMarkdownTagValid = ::isBusyRefValid,
        )
    }
}
