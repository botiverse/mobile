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
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Own messages just sent, light and dark: sending (left-aligned, then right-aligned) and failed
 * (left, then right). Captured ~2 s after composition, inside the 5 s sending delay
 * ([ThreadSendingIndicatorDelayMs]), so the sending rows draw no spinner yet (their slot stays
 * reserved) while the failed rows already show their failure line. Reports land in
 * `build/reports/home-screenshots/` (CI artifact `shared-home-screenshots`); verify / record as
 * [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h480dp-xxhdpi")
class ThreadSendingIndicatorScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun sendingRowsLight() = capture("thread.sending-own-message", RaftAppAppearance.Light)

    @Test
    fun sendingRowsDark() = capture("thread.sending-own-message.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme { SendingOwnRows() }
            }
        }
}

private fun ownLocalSend(id: String, body: String, deliveryState: String) =
    RaftThreadMessageItem(
        id = id,
        authorId = "user-alice",
        authorName = "Alice",
        authorKind = "human",
        isOwn = true,
        avatarLabel = "A",
        timestampLabel = "2026-10-21T16:40:00Z",
        body = body,
        deliveryState = deliveryState,
        pendingSendContext = RaftThreadPendingSendContext(channelId = "channel-sending", body = body),
    )

@Composable
private fun SendingOwnRows() {
    val rows =
        listOf(
            ownLocalSend("local-1", "On my way, five minutes out.", ThreadMessageDeliverySending) to false,
            ownLocalSend("local-2", "On my way, five minutes out.", ThreadMessageDeliverySending) to true,
            ownLocalSend("local-3", "Shared the deck in the launch doc.", ThreadMessageDeliveryFailed) to false,
            ownLocalSend("local-4", "Shared the deck in the launch doc.", ThreadMessageDeliveryFailed) to true,
        )
    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        rows.forEach { (message, alignRight) ->
            ThreadMessageCard(
                message = message,
                messageBodyTextStyle = raftMessageBodyTextStyle("md"),
                alignOwnMessageRight = alignRight,
                timestampLabel = "4:40 PM",
                onShowActions = {},
                onOpenMember = {},
                onOpenThread = {},
                onOpenTask = {},
                onOpenTaskStatusMenu = {},
                onToggleReaction = {},
                onOpenAttachment = {},
                onOpenMarkdownTag = { _, _ -> },
                onOpenMarkdownUrl = {},
            )
        }
    }
}
