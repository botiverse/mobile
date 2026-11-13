package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.FeedbackAuthorType
import build.raft.app.data.FeedbackClient
import build.raft.app.data.FeedbackComment
import build.raft.app.data.FeedbackTicketDetail
import build.raft.app.data.FeedbackTicketDetailState
import build.raft.app.data.FeedbackLatestComment
import build.raft.app.data.FeedbackTicket
import build.raft.app.data.FeedbackTicketKind
import build.raft.app.data.FeedbackTicketStatus
import build.raft.app.data.FeedbackTicketsListPhase
import build.raft.app.data.FeedbackTicketsListState
import build.raft.app.data.SettingsFeedbackTicketsState
import build.raft.app.model.RaftTimeContext
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings › Feedback on a 390dp phone. The ticket list with the agent tip at its top (web
 * FeedbackAgentHint), and a ticket filed on another client (design settings.md "Feedback", slock
 * #14722): an outline muted badge with the client's icon and name after the first message's time.
 *
 * The ticket list on a 390dp phone with the agent tip at its top (web
 * FeedbackAgentHint, botiverse/slock 0d4ec9fd4f58 #14053): a small info Banner, one line with a bold
 * lead, no button and no ×, above New Feedback. Light and dark in English, and Simplified Chinese.
 * The production screen with fixed state; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h420dp-xxhdpi")
class SettingsFeedbackTicketsScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun agentHintLight() = captureFeedbackAgentHint("settings.feedback-tickets.agent-hint", RaftAppAppearance.Light, activities)

    @Test
    fun agentHintDark() = captureFeedbackAgentHint("settings.feedback-tickets.agent-hint.dark", RaftAppAppearance.Dark, activities)

    /** A ticket filed on Raft Desktop, opened in the Android app: the client after the first message's time. */
    @Test
    fun filedFromLight() = captureFeedbackFiledFrom("settings.feedback-tickets.filed-from", RaftAppAppearance.Light, activities)

    @Test
    fun filedFromDark() = captureFeedbackFiledFrom("settings.feedback-tickets.filed-from.dark", RaftAppAppearance.Dark, activities)
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-w390dp-h420dp-xxhdpi")
class SettingsFeedbackTicketsZhCnScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun agentHint() = captureFeedbackAgentHint("settings.feedback-tickets.agent-hint.zh-CN", RaftAppAppearance.Light, activities)

    @Test
    fun filedFrom() = captureFeedbackFiledFrom("settings.feedback-tickets.filed-from.zh-CN", RaftAppAppearance.Light, activities)
}

private const val Now = 1_762_700_000_000L

private fun captureFeedbackAgentHint(name: String, appearance: RaftAppAppearance, activities: RobolectricComposeActivityRule) =
    RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
        val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
            RaftDesignSystemTheme { AgentHintList() }
        }
    }

@Composable
private fun AgentHintList() {
    val ticket =
        FeedbackTicket(
            id = "11111111-2222-4333-8444-555555555555",
            kind = FeedbackTicketKind.Bug,
            status = FeedbackTicketStatus.InProgress,
            closureReason = null,
            message = "Search is slow\nIt takes seconds to show results",
            createdAt = Now - 3_600_000L,
            updatedAt = Now - 600_000L,
            unread = false,
            unreadCount = 0,
            attachmentCount = 0,
            commentCount = 1,
            latestComment = FeedbackLatestComment(FeedbackAuthorType.Staff, "Looking into it", Now - 600_000L),
        )
    SettingsFeedbackTicketsScreen(
        state =
            SettingsFeedbackTicketsState(
                list = FeedbackTicketsListState(phase = FeedbackTicketsListPhase.Ready, tickets = listOf(ticket)),
                showAgentHint = true,
            ),
        timeContext = RaftTimeContext(localOffsetMinutes = 0, todayEpochDay = Now / 86_400_000L, nowEpochMillis = Now),
        actions = SettingsFeedbackTicketsActions(),
    )
}

private fun captureFeedbackFiledFrom(name: String, appearance: RaftAppAppearance, activities: RobolectricComposeActivityRule) =
    RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
        val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
            RaftDesignSystemTheme { FiledFromTicket() }
        }
    }

@Composable
private fun FiledFromTicket() {
    val ticket =
        FeedbackTicket(
            id = "11111111-2222-4333-8444-555555555555",
            kind = FeedbackTicketKind.Bug,
            status = FeedbackTicketStatus.InProgress,
            closureReason = null,
            message = "Search is slow\nIt takes seconds to show results",
            createdAt = Now - 3_600_000L,
            updatedAt = Now - 600_000L,
            unread = false,
            unreadCount = 0,
            attachmentCount = 0,
            commentCount = 1,
            source = "web",
            client = FeedbackClient.Desktop,
        )
    val detail =
        FeedbackTicketDetail(
            ticket = ticket,
            comments = listOf(FeedbackComment("c1", FeedbackAuthorType.Staff, "Looking into it", Now - 600_000L)),
            attachments = emptyList(),
            nextCommentCursor = null,
            unreadTotal = 0,
        )
    SettingsFeedbackTicketsScreen(
        state =
            SettingsFeedbackTicketsState(
                list = FeedbackTicketsListState(phase = FeedbackTicketsListPhase.Ready, tickets = listOf(ticket)),
                detail = FeedbackTicketDetailState(ticketId = ticket.id, summary = ticket, detail = detail, loading = false),
            ),
        timeContext = RaftTimeContext(localOffsetMinutes = 0, todayEpochDay = Now / 86_400_000L, nowEpochMillis = Now),
        actions = SettingsFeedbackTicketsActions(),
        ownClient = FeedbackClient.Android,
        replyComposer = { _, _, _, _, _, _ -> },
    )
}
