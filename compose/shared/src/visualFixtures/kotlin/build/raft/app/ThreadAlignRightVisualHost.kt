package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import build.raft.app.compat.pager.Page

// task #800 rendering-evidence fixture: four right-aligned own-message cards
// separated by pure-red full-width markers so the connected test can segment
// screenshot bands and measure real bubble edges — long (cap reference),
// single-character (must hug End), the same single character in sending state
// (in-flow spinner, End edge must match), and failed (status row, End edge
// must match). Deterministic content, no network.
@Page("compose_thread_align_right_visual", supportInLocal = true)
internal class ComposeThreadAlignRightVisualPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ThreadAlignRightVisualScreen() }
    }
}

private fun threadAlignRightVisualFixtureMessage(
    id: String,
    body: String,
    deliveryState: String = ThreadMessageDeliverySent,
    pending: Boolean = false
): RaftThreadMessageItem =
    RaftThreadMessageItem(
        id = id,
        authorId = "member-own",
        authorName = "You",
        isOwn = true,
        avatarLabel = "Y",
        timestampLabel = "10:0${id.last()}",
        body = body,
        deliveryState = deliveryState,
        pendingSendContext =
            if (pending) {
                RaftThreadPendingSendContext(channelId = "channel-align-right", body = body)
            } else {
                null
            }
    )

// task #800 rendering-evidence fixture (oracle v3, LiBai/ZhangYi frozen bars):
// five right-aligned own-message cards between SIX pure-red markers. Geometry
// is measured ONLY through the fixture tints — body pure blue, status slot
// pure magenta, 82% cap container pure green, retry button pure yellow — so
// the connected test reads exactly the body/status/cap/retry geometry and
// never whole-card extremes. Band order: long cap reference / sent single
// char / sending / failed(+retry) / retry-returned-to-sending.
@Composable
internal fun ThreadAlignRightVisualScreen() {
    val fixtures =
        listOf(
            threadAlignRightVisualFixtureMessage(
                id = "align-right-long-1",
                // The fenced code block is a fill-width child, so the wrapped
                // body root deterministically expands to the FULL cap — the
                // >=0.98 cap-reach bar cannot flake on word-wrap line fill.
                body = "This deliberately long reference message wraps across multiple lines so its bubble reaches the width cap for the band.\n\n```\ncap reference block\n```"
            ),
            threadAlignRightVisualFixtureMessage(id = "align-right-short-2", body = "W"),
            threadAlignRightVisualFixtureMessage(
                id = "align-right-sending-3",
                body = "W",
                deliveryState = ThreadMessageDeliverySending,
                pending = true
            ),
            threadAlignRightVisualFixtureMessage(
                id = "align-right-failed-4",
                body = "W",
                deliveryState = ThreadMessageDeliveryFailed,
                pending = true
            ),
            // The retry affordance returns a failed message to the sending
            // state; this card IS that post-retry state re-rendered, so its
            // measured geometry must be 0px-equal to the sending band.
            threadAlignRightVisualFixtureMessage(
                id = "align-right-retry-5",
                body = "W",
                deliveryState = ThreadMessageDeliverySending,
                pending = true
            )
        )
    CompositionLocalProvider(
        LocalThreadMessageGeometryTints provides
            ThreadMessageGeometryTints(
                bodyTint = Color(0xFF0000FF),
                statusTint = Color(0xFFFF00FF),
                capTint = Color(0xFF00FF00),
                retryTint = Color(0xFFFFFF00)
            )
    ) {
        Column(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
            fixtures.forEach { message ->
                Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0xFFFF0000)))
                ThreadMessageCard(
                    message = message,
                    messageBodyTextStyle = raftMessageBodyTextStyle("md"),
                    alignOwnMessageRight = true,
                    onShowActions = {},
                    onOpenMember = {},
                    onOpenThread = {},
                    onOpenTask = {},
                    onOpenTaskStatusMenu = {},
                    onToggleReaction = {},
                    onOpenAttachment = {},
                    onOpenMarkdownTag = { _, _ -> },
                    onOpenMarkdownUrl = {}
                )
            }
            Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0xFFFF0000)))
        }
    }
}
