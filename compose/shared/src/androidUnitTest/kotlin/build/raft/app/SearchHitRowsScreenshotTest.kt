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
import build.raft.app.data.InboxItem
import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Search hit stickers on the Home search rows, light and dark, with descender words ("Remy",
 * "query"): an entity row whose "@Remy" handle is one hit, and message rows whose snippet shows
 * the hit (the second is a CJK snippet). The
 * sticker covers the whole line box, so the descenders sit inside it. Verify / record as
 * [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h520dp-xxhdpi")
class SearchHitRowsScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test fun searchHitRowsLight() = capture("search.hit-rows", RaftAppAppearance.Light)

    @Test fun searchHitRowsDark() = capture("search.hit-rows.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) {
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply {
                        pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                    }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftTheme(appearance = if (appearance == RaftAppAppearance.Dark) RaftAppearance.Dark else RaftAppearance.Light) {
                    SearchHitRowsContent()
                }
            }
        }
    }
}

private fun entity(type: SearchEntityType, title: String, subtitle: String) =
    SearchEntityResult(
        key = "entity:$title",
        type = type,
        title = title,
        subtitle = subtitle,
        threadId = null,
        memberIdentity =
            MemberDetailIdentity(
                memberId = MemberId("member-$title"),
                kind = MemberKind.Agent,
                sourceServerId = ServerId("server-1"),
            ),
        conversationIdentity = null,
    )

private fun message(id: String, preview: String) =
    InboxItem(
        key = "channel:c:$id",
        kind = InboxItemKind.Channel,
        channelId = ChannelId("c"),
        channelName = "raft-botiverse",
        lastMessageSenderName = "AppFather",
        lastMessageSenderKind = MemberKind.Agent,
        lastMessagePreview = preview,
        lastMessageAt = "2026-10-23T16:31:00Z",
    )

@Composable
private fun SearchHitRowsContent() {
    Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
        SharedHomeSearchEntityRow(
            result = entity(SearchEntityType.Agent, "Remy", "Agent · @Remy"),
            query = "remy",
            agentStatusResolver = EmptyRaftAgentStatusResolver,
            onOpen = {},
        )
        SharedHomeSearchEntityRow(
            result = entity(SearchEntityType.Agent, "Query Bot", "Agent · @query-bot"),
            query = "query",
            agentStatusResolver = EmptyRaftAgentStatusResolver,
            showSeparator = true,
            onOpen = {},
        )
        SharedHomeSearchMessageResultRow(
            item = message("1", "Ask remy to query the release notes, then summarize what it found for the group."),
            query = "remy query",
            onOpen = {},
        )
        SharedHomeSearchMessageResultRow(
            item = message("2", "...对一下 app link，然后我看 remy 那儿也有一个类似的需求，后面再对一下"),
            query = "remy",
            showSeparator = true,
            onOpen = {},
        )
    }
}
