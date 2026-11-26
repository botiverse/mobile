package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.UsageLimitAttentionMachine
import build.raft.app.data.UsageLimitAttentionNotification
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.resources.StringDesc
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h640dp-xxhdpi")
class HomeUsageLimitAttentionUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun englishLocale() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
    }

    @AfterTest
    fun restoreLocale() {
        StringDesc.localeType = previousLocale
    }

    private fun fixedTimeContext(): RaftTimeContext =
        RaftTimeContext(
            localOffsetMinutes = 0,
            nowEpochMillis = requireNotNull(parseRaftTimestampEpochMillisOrNull("2026-11-10T12:00:00.000Z", 0)),
        )

    private fun sampleWithReset(): UsageLimitAttentionNotification =
        UsageLimitAttentionNotification(
            id = "inc-claude",
            activatedAt = "2026-11-10T11:00:00.000Z",
            lastObservedAt = "2026-11-10T11:50:00.000Z",
            seenAt = null,
            machine = UsageLimitAttentionMachine(id = "machine-oak", name = "oak-linux"),
            provider = "claude",
            windowKey = "weekly",
            resetsAt = "2026-11-16T15:00:00.000Z",
            affectedAgentIds = listOf("a-1", "a-2"),
        )

    private fun sampleWithoutReset(): UsageLimitAttentionNotification =
        UsageLimitAttentionNotification(
            id = "inc-codex",
            activatedAt = "2026-11-10T10:00:00.000Z",
            lastObservedAt = "2026-11-10T11:45:00.000Z",
            seenAt = "2026-11-10T11:55:00.000Z",
            machine = UsageLimitAttentionMachine(id = "machine-mac", name = "Cindy MacBook"),
            provider = "codex",
            windowKey = "five_hour",
            resetsAt = null,
            affectedAgentIds = listOf("a-3"),
        )

    @Test
    fun rendersUsageLimitCardsHandlesTapAndDismissAndHidesEmptyStateWhenPopulated() =
        runRobolectricComposeUiTest {
            val timeContext = fixedTimeContext()
            var cards by mutableStateOf(listOf(sampleWithReset(), sampleWithoutReset()))
            val openedComputers = mutableListOf<String>()
            val dismissedIds = mutableListOf<String>()

            setContent {
                RaftDesignSystemTheme {
                    val items = cards.map { usageLimitAttentionNotificationItem(it, timeContext) }
                    Column {
                        HomeNotificationBellButton(
                            notifications = items,
                            open = true,
                            onToggle = {},
                            size = 44.dp,
                        )
                        HomeNotificationPopover(
                            notifications = items,
                            onView = { item -> item.targetId?.let(openedComputers::add) },
                            onDismiss = { item ->
                                dismissedIds += item.id
                                cards = cards.filterNot { "usage-limit:${it.id}" == item.id }
                            },
                        )
                    }
                }
            }

            // Two cards rendered, empty state hidden, unread dot shown on bell for unseen card.
            onNode(hasTestTag(HOME_NOTIFICATION_UNREAD_DOT_TAG)).assertExists()
            onNode(hasText("No notifications right now")).assertDoesNotExist()
            onNode(hasTestTag("home_notification_row_usage-limit:inc-claude")).assertExists()
            onNode(hasText("Claude used up")).assertExists()
            onNode(hasText("2 agents on oak-linux may pause. Back in 6 days.")).assertExists()
            onNode(hasTestTag("home_notification_row_usage-limit:inc-codex")).assertExists()
            onNode(hasText("Codex used up")).assertExists()
            onNode(hasText("1 agent on Cindy MacBook may pause until usage is available again.")).assertExists()

            // Tap primary View action on first card -> navigates to its Computer.
            onAllNodes(hasText("View"))[0].performClick()
            waitForIdle()
            assertEquals(listOf("machine-oak"), openedComputers)

            // Marking first card seen clears the bell unread dot while keeping both cards visible.
            runOnIdle {
                cards = cards.map { if (it.id == "inc-claude") it.copy(seenAt = "2026-11-10T12:00:00.000Z") else it }
            }
            waitForIdle()
            onNode(hasTestTag(HOME_NOTIFICATION_UNREAD_DOT_TAG)).assertDoesNotExist()
            onNode(hasTestTag("home_notification_row_usage-limit:inc-claude")).assertExists()

            // Dismiss both cards -> empty state appears and no usage-limit rows remain.
            onAllNodes(hasText("Dismiss"))[0].performClick()
            waitForIdle()
            assertEquals(listOf("usage-limit:inc-claude"), dismissedIds)
            onNode(hasTestTag("home_notification_row_usage-limit:inc-claude")).assertDoesNotExist()
            onNode(hasTestTag("home_notification_row_usage-limit:inc-codex")).assertExists()

            onNode(hasText("Dismiss")).performClick()
            waitForIdle()
            assertEquals(listOf("usage-limit:inc-claude", "usage-limit:inc-codex"), dismissedIds)
            onNode(hasTestTag("home_notification_row_usage-limit:inc-codex")).assertDoesNotExist()
            onNode(hasText("No notifications right now")).assertExists()
            onNode(hasText("all clear")).assertExists()
        }

    @Test
    fun emptyAttentionListRendersNoUsageLimitCardsAndNoUnreadDot() =
        runRobolectricComposeUiTest {
            setContent {
                RaftDesignSystemTheme {
                    Column {
                        HomeNotificationBellButton(
                            notifications = emptyList(),
                            open = true,
                            onToggle = {},
                            size = 44.dp,
                        )
                        HomeNotificationPopover(
                            notifications = emptyList(),
                            onView = {},
                            onDismiss = {},
                        )
                    }
                }
            }

            onNode(hasTestTag(HOME_NOTIFICATION_UNREAD_DOT_TAG)).assertDoesNotExist()
            onNode(hasText("No notifications right now")).assertExists()
            onNode(hasText("used up", substring = true)).assertDoesNotExist()
        }

    @Test
    fun simplifiedChineseCopyMatchesWebStagingWording() =
        runRobolectricComposeUiTest {
            StringDesc.localeType = StringDesc.LocaleType.Custom("zh-CN")
            val timeContext = fixedTimeContext()
            val cards = listOf(sampleWithReset(), sampleWithoutReset())

            setContent {
                RaftDesignSystemTheme {
                    val items = cards.map { usageLimitAttentionNotificationItem(it, timeContext) }
                    HomeNotificationPopover(
                        notifications = items,
                        onView = {},
                        onDismiss = {},
                    )
                }
            }

            onNode(hasText("Claude 已用完")).assertExists()
            onNode(hasText("oak-linux 上的 2 个 Agent 可能会暂停，6 天后恢复。")).assertExists()
            onNode(hasText("Codex 已用完")).assertExists()
            onNode(hasText("Cindy MacBook 上的 1 个 Agent 可能会暂停，直到额度恢复。")).assertExists()
        }
}
