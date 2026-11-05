package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.HiddenHistory
import build.raft.app.data.HiddenHistoryMessage
import build.raft.app.data.RaftHiddenHistoryStore
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import build.raft.app.resources.*
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Free plan's history paywall (web `HistoryPaywall`, slock `tests/historyPaywall.behavior`):
 * at the start of a channel's 30-day window, the newest two hidden messages faded toward the top,
 * the card ("214 earlier messages are older than 30 days", Upgrade to Pro), then the first date
 * line "Last 30 days · …" and the shown history; and a DM whose every message is hidden, the
 * paywall on the composer with "No new messages in the last 30 days". Phone and wide, light and
 * dark. Reports land in `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h640dp-xxhdpi")
class ThreadHistoryPaywallScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @BeforeTest
    @AfterTest
    fun clearHiddenHistory() {
        // Robolectric tests share one JVM: the process-wide store must not leak between tests.
        RaftHiddenHistoryStore.clear()
    }

    @Test fun channelLight() = capture("thread.history-paywall", RaftAppAppearance.Light) { ChannelStart() }

    @Test fun channelDark() = capture("thread.history-paywall.dark", RaftAppAppearance.Dark) { ChannelStart() }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-mdpi")
    fun channelWideLight() = capture("thread.history-paywall.wide", RaftAppAppearance.Light) { ChannelStart() }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-mdpi")
    fun channelWideDark() = capture("thread.history-paywall.wide.dark", RaftAppAppearance.Dark) { ChannelStart() }

    @Test fun allHiddenLight() = capture("thread.history-paywall.all-hidden", RaftAppAppearance.Light) { AllHiddenDm() }

    @Test fun allHiddenDark() = capture("thread.history-paywall.all-hidden.dark", RaftAppAppearance.Dark) { AllHiddenDm() }

    private fun capture(name: String, appearance: RaftAppAppearance, content: @Composable () -> Unit) {
        runCatching { build.raft.app.image.raftInstallImageLoader() }
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            val clock = remember { RaftTimeContextProvider(PaywallClock) }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalRaftTimeContextProvider provides clock,
                LocalInspectionMode provides true,
            ) {
                RaftDesignSystemTheme { content() }
            }
        }
    }
}

private val PaywallClock =
    RaftTimeContext(
        localOffsetMinutes = 8 * 60,
        todayEpochDay = raftEpochDayForDate(2026, 10, 22),
        nowEpochMillis = 1_792_635_000_000L,
    )

private fun hiddenMessage(id: String, senderType: String, name: String, avatar: String?, createdAt: String, preview: String?) =
    HiddenHistoryMessage(
        id = id,
        senderType = senderType,
        senderId = "$senderType-$id",
        senderName = name,
        senderAvatarUrl = avatar,
        senderGravatarHash = null,
        createdAt = createdAt,
        preview = preview,
    )

private val ChannelHidden =
    HiddenHistory(
        hiddenCount = 214,
        hiddenCountCapped = false,
        parentHidden = false,
        latest =
            listOf(
                hiddenMessage("m-1", "user", "Lin Mo", null, "2026-09-20T08:00:00.000Z", "The final design is in Figma, please re…"),
                hiddenMessage("m-2", "agent", "Bugen", "pixel:random:bugen", "2026-09-21T09:30:00.000Z", null),
            ),
    )

private fun visibleRow(id: String, minute: Int, body: String) =
    RaftThreadMessageItem(
        id = id,
        authorId = "user-alice",
        authorName = "Alice",
        authorKind = "human",
        avatarLabel = "A",
        timestampLabel = "2026-10-20T02:$minute:00Z",
        body = body,
    )

@Composable
private fun ChannelStart() {
    val rows =
        listOf(
            visibleRow("v-1", 10, "Picking this up again: the launch checklist is in the pinned doc."),
            visibleRow("v-2", 12, "Ping me when the review is done."),
        )
    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        ThreadHistoryPaywall(
            scope = ThreadHistoryPaywallScope.Channel,
            hidden = ChannelHidden,
            allHidden = false,
            onUpgrade = {},
            timeContext = PaywallClock,
        )
        ThreadDateDivider(stringResource(Res.string.thread_history_paywall_recent_divider_with_date, ThreadHistoryPaywallDays, "Tuesday, October 20"))
        rows.forEachIndexed { index, message ->
            ThreadMessageCard(
                message = message,
                messageBodyTextStyle = raftMessageBodyTextStyle("md"),
                compactConsecutiveMessages = true,
                suppressRepeatedAuthor = shouldSuppressCompactThreadAuthor(rows.getOrNull(index - 1), message, localOffsetMinutes = 0),
                showReactionAddAction = true,
                timestampLabel = "10:${message.timestampLabel.substring(14, 16)}",
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

@Composable
private fun AllHiddenDm() {
    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        ThreadHistoryPaywallEmptyState(
            scope = ThreadHistoryPaywallScope.Dm,
            hidden =
                HiddenHistory(
                    hiddenCount = 13,
                    hiddenCountCapped = false,
                    parentHidden = false,
                    latest = listOf(hiddenMessage("d-1", "user", "Kirby", null, "2026-09-18T03:00:00.000Z", "**Sounds good**, see you Monday")),
                ),
            onUpgrade = {},
            timeContext = PaywallClock,
        )
    }
}
