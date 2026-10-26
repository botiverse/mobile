package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import build.raft.app.data.InboxItem
import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberKind
import build.raft.app.model.MessageId
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.RaftTimeFormat12Hour
import build.raft.app.model.raftEpochDayForDate
import build.raft.designsystem.components.LocalRaftSpinnerStillStep
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Task #80 (artin): what a page opened from Activity shows before its load answers, built from
 * Activity rows through [threadActivityOpenSeedMessages] and drawn by the list's own row branch.
 *  - `thread.stand-in-loading`: the list top says older messages are loading (spinner frozen on its
 *    first step, revealed), above the row's message; its human sender is named from the member
 *    directory although the row carries no name.
 *  - `thread.system-message-seed`: "@Jasper joined this channel." (sender id `system`) is the muted
 *    centered system line, not a chat message from "system" with an "S" face.
 *  - `thread.activity-seed-truncated` (artin 10-26): a row whose preview the server cut at 140
 *    characters shows that text with its "…", and the small muted loading spinner (frozen on its
 *    first step) below it, below a short message that needs none.
 * Light and dark; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h320dp-xxhdpi")
class ThreadActivityOpenSeedScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test fun standInLoadingLight() = capture("thread.stand-in-loading", RaftAppAppearance.Light) { StandInLoadingRows() }

    @Test fun standInLoadingDark() = capture("thread.stand-in-loading.dark", RaftAppAppearance.Dark) { StandInLoadingRows() }

    @Test fun systemMessageLight() = capture("thread.system-message-seed", RaftAppAppearance.Light) { SystemMessageRows() }

    @Test fun systemMessageDark() = capture("thread.system-message-seed.dark", RaftAppAppearance.Dark) { SystemMessageRows() }

    @Test fun truncatedPreviewLight() = capture("thread.activity-seed-truncated", RaftAppAppearance.Light) { TruncatedPreviewRows() }

    @Test fun truncatedPreviewDark() = capture("thread.activity-seed-truncated.dark", RaftAppAppearance.Dark) { TruncatedPreviewRows() }

    private fun capture(name: String, appearance: RaftAppAppearance, rows: @Composable () -> Unit) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            val clock =
                remember {
                    RaftTimeContextProvider(
                        RaftTimeContext(
                            localOffsetMinutes = 8 * 60,
                            todayEpochDay = raftEpochDayForDate(2026, 10, 24),
                            nowEpochMillis = SeedNowEpochMillis,
                            // The user's 12-hour preference: chat and system rows both follow it.
                            timeFormat = RaftTimeFormat12Hour,
                        ),
                    )
                }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalRaftTimeContextProvider provides clock,
            ) {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize().background(RaftColor.Canvas).padding(top = RaftAppSpacing.Sm)) { rows() }
                }
            }
        }
}

// 2026-10-24T07:29:00Z, artin's screenshot time (15:29 at UTC+8).
private const val SeedNowEpochMillis = 1_792_826_940_000L

private fun seedFrom(vararg items: InboxItem): List<RaftThreadMessageItem> =
    threadActivityOpenSeedMessages(
        ThreadActivityOpenSeedSource(ownerServerId = "server-1", ownerUserId = "u-artin", items = items.toList()),
        RaftThreadScreenPayload(
            instanceId = "shared-shell-thread-channel-1",
            title = "proj-mobile",
            surfaceKind = RaftThreadSurfaceKind.Channel,
            sourceThreadId = "channel-1",
            status = "loading",
        ).resolveThreadRouteIdentity(),
        serverId = "server-1",
        principalId = "u-artin",
        memberNames = mapOf("u-jasper" to "Jasper"),
    )

private fun channelRow(messageId: String, preview: String, at: String, senderId: String) =
    InboxItem(
        key = "channel-1-$messageId",
        kind = InboxItemKind.Channel,
        channelId = ChannelId("channel-1"),
        channelName = "proj-mobile",
        lastMessageId = MessageId(messageId),
        lastMessagePreview = preview,
        lastMessageAt = at,
        lastMessageSenderId = senderId,
        // Rows the server sends without a sender name (done rows, thread rows): never the raw id.
        lastMessageSenderName = null,
        lastMessageSenderKind = MemberKind.Human,
    )

private val humanSeed =
    seedFrom(channelRow("m-1", "Swipe left is done and mute; swipe right should be a read button.", "2026-10-24T06:45:00Z", "u-jasper"))
        .single()

private val systemSeed =
    seedFrom(channelRow("sys-1", "@Jasper joined this channel.", "2026-10-24T07:13:00Z", "system")).single()

// What the server sends for a longer message: its first 140 characters, then "…".
private val truncatedSeed =
    seedFrom(
        channelRow(
            "m-2",
            ("Checked the Activity open path on the alpha: the thread page shows the row preview first, " +
                "then swaps in the full message once the channel load answers.").take(140) + "…",
            "2026-10-24T07:02:00Z",
            "u-jasper",
        )
    ).single()

@Composable
private fun TruncatedPreviewRows() {
    CompositionLocalProvider(LocalRaftSpinnerStillStep provides 0) {
        SeedMessageRow(humanSeed)
        SeedMessageRow(truncatedSeed)
    }
}

@Composable
private fun StandInLoadingRows() {
    val payload =
        RaftThreadScreenPayload(sourceThreadId = "channel-1", status = "loading").withActivityOpenSeed(listOf(humanSeed))
    ThreadHistoryStatusRow(threadOlderMessagesStatusSpec(payload), loadingRevealDelayMs = 0L, spinnerStillStep = 0)
    SeedMessageRow(humanSeed)
}

@Composable
private fun SystemMessageRows() {
    SeedMessageRow(humanSeed)
    SeedMessageRow(systemSeed)
}

/**
 * The message list's row branch: a system message is the system line, anything else the card. Both
 * get the message's own wire timestamp, as the list passes it, so both format it through the same
 * conversation time formatter and the user's 12/24-hour preference.
 */
@Composable
private fun SeedMessageRow(message: RaftThreadMessageItem) {
    if (message.isSystemMessage()) {
        ThreadSystemMessageRow(message = message, timestampValue = message.timestampLabel)
    } else {
        ThreadMessageCard(
            message = message,
            messageBodyTextStyle = raftMessageBodyTextStyle("md"),
            compactConsecutiveMessages = true,
            suppressRepeatedAuthor = false,
            timestampLabel = message.timestampLabel,
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
