package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import build.raft.app.data.AgentActivityActionCount
import build.raft.app.data.AgentActivityActionKind
import build.raft.app.data.AgentActivityTurn
import build.raft.app.data.AgentActivityTurnOutcome
import build.raft.app.data.AgentActivityTurnTrigger
import build.raft.app.data.AppLocale
import build.raft.app.data.RaftTimeZoneRuntime
import build.raft.app.data.stringDescLocaleType
import build.raft.app.members.MembersAgentRecentTurnBadgesTag
import build.raft.app.members.MembersAgentRecentTurnRows
import build.raft.app.members.MembersAgentRecentTurnTextTag
import build.raft.app.members.MembersAgentRecentTurnTimeTag
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.RaftTimeFormat12Hour
import build.raft.app.model.RaftTimeFormat24Hour
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.model.raftEpochDayForDate
import build.raft.app.resources.StringDesc
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Recent activity rows' layout and formatting on a real composition ([MembersAgentRecentTurnRows]):
 * - Shared time column across days ("00:18" above "Jun 18, 23:57"): every row's text starts at the
 *   same x, right of the widest time label, and no label is cut or wrapped.
 * - Web-aligned short date-time format on earlier days ("Nov 21, 11:23" / "11月21日 11:23"), clock
 *   only on today, respecting account `preferredTimezone` and per-instant DST offsets (#251).
 * - Compact status chips ("No message sent") sit on their own line below the summary text, aligned
 *   with the text column even on short rows (#251).
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h1500dp")
class MembersAgentRecentTurnRowsUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun remember() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
        RaftTimeZoneRuntime.resetForTest()
    }

    @AfterTest
    fun restore() {
        StringDesc.localeType = previousLocale
        RaftTimeZoneRuntime.resetForTest()
    }

    private fun ComposeUiTest.rows(
        turns: List<AgentActivityTurn>,
        timeContext: RaftTimeContext,
        fontScale: Float = 1f,
    ) {
        setContent {
            MigrationFixtureHost {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    // The card's inner width on a 390dp phone.
                    Box(Modifier.width(318.dp)) {
                        MembersAgentRecentTurnRows(
                            rows = turns,
                            timeContext = timeContext,
                        )
                    }
                }
            }
        }
    }

    private fun ComposeUiTest.rows(case: AgentRecentActivityCase, fontScale: Float = 1f, timeFormat: String? = null) {
        rows(
            turns = case.finished,
            timeContext = timeFormat?.let { case.timeContext.copy(timeFormat = it) } ?: case.timeContext,
            fontScale = fontScale,
        )
    }

    private fun ComposeUiTest.bounds(tag: String): List<Rect> =
        onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }

    private fun ComposeUiTest.assertOneTextColumn() {
        val times = bounds(MembersAgentRecentTurnTimeTag)
        val texts = bounds(MembersAgentRecentTurnTextTag)
        assertEquals(3, times.size, "times=$times")
        assertEquals(3, texts.size, "texts=$texts")
        // The labels differ in width (one has no date), and all start at the column's left.
        assertTrue(times.map { it.width }.distinct().size > 1, "the labels should differ in width: $times")
        assertEquals(1, times.map { it.left }.distinct().size, "times=$times")
        // Every text starts at one x, right of the widest label, and ends at the rows' right edge.
        assertEquals(1, texts.map { it.left }.distinct().size, "texts=$texts")
        assertEquals(1, texts.map { it.right }.distinct().size, "texts=$texts")
        assertTrue(texts.first().left > times.maxOf { it.right }, "times=$times texts=$texts")
        // Rows do not overlap: each starts below the one before, time and text on the same top.
        texts.zipWithNext { above, below -> assertTrue(below.top >= above.bottom, "texts=$texts") }
        times.zip(texts) { time, text -> assertTrue(time.top >= text.top && time.top < text.bottom, "times=$times texts=$texts") }
    }

    @Test
    fun textsStartAtOneXWhenTurnsCrossADay() =
        runRobolectricComposeUiTest {
            rows(agentRecentActivityAcrossDays())
            assertOneTextColumn()
            val timeNodes = onAllNodesWithTag(MembersAgentRecentTurnTimeTag, useUnmergedTree = true)
            timeNodes[0].assertTextEquals("00:18")
            timeNodes[1].assertTextEquals("Jun 18, 23:57")
            timeNodes[2].assertTextEquals("Jun 18, 23:41")
        }

    @Test
    fun recentTurnTimeMatchesWebShortDateTimeAcrossLocalesAndDstTimeZones() =
        runRobolectricComposeUiTest {
            val nov21Utc = requireNotNull(parseRaftTimestampEpochMillisOrNull("2026-11-21T16:23:00Z", 0))
            val jul21Utc = requireNotNull(parseRaftTimestampEpochMillisOrNull("2026-07-21T16:23:00Z", 0))
            val nov23TodayUtc = requireNotNull(parseRaftTimestampEpochMillisOrNull("2026-11-23T16:23:00Z", 0))
            val turns =
                listOf(
                    sampleTurn("today", nov23TodayUtc),
                    sampleTurn("nov21", nov21Utc),
                    sampleTurn("jul21", jul21Utc),
                )
            // Account timezone is America/New_York (EST = UTC-5 in Nov, EDT = UTC-4 in Jul).
            var activeContext by mutableStateOf(
                RaftTimeContext(
                    localOffsetMinutes = -5 * 60,
                    todayEpochDay = raftEpochDayForDate(2026, 11, 23),
                    timeFormat = RaftTimeFormat24Hour,
                    timeZoneId = "America/New_York",
                )
            )
            setContent {
                MigrationFixtureHost {
                    Box(Modifier.width(318.dp)) {
                        MembersAgentRecentTurnRows(
                            rows = turns,
                            timeContext = activeContext,
                        )
                    }
                }
            }
            val timeNodes = onAllNodesWithTag(MembersAgentRecentTurnTimeTag, useUnmergedTree = true)
            timeNodes[0].assertTextEquals("11:23")
            timeNodes[1].assertTextEquals("Nov 21, 11:23")
            // Cross-DST historical turn resolves EDT (UTC-4) -> 12:23 instead of EST 11:23.
            timeNodes[2].assertTextEquals("Jul 21, 12:23")

            StringDesc.localeType = StringDesc.LocaleType.Custom("zh-CN")
            waitForIdle()
            timeNodes[0].assertTextEquals("11:23")
            timeNodes[1].assertTextEquals("11月21日 11:23")
            timeNodes[2].assertTextEquals("7月21日 12:23")

            StringDesc.localeType = StringDesc.LocaleType.Custom("en")
            activeContext = activeContext.copy(timeFormat = RaftTimeFormat12Hour)
            waitForIdle()
            timeNodes[0].assertTextEquals("11:23 AM")
            timeNodes[1].assertTextEquals("Nov 21, 11:23 AM")
            timeNodes[2].assertTextEquals("Jul 21, 12:23 PM")
        }

    @Test
    fun noMessageSentChipSitsOnItsOwnLineBelowSummaryTextEvenOnShortRows() =
        runRobolectricComposeUiTest {
            val nov21Utc = requireNotNull(parseRaftTimestampEpochMillisOrNull("2026-11-21T16:23:00Z", 0))
            val jonyRow3 =
                AgentActivityTurn(
                    id = "jony-row-3",
                    startedAtEpochMillis = nov21Utc,
                    endedAtEpochMillis = nov21Utc + 3_000L,
                    outcome = AgentActivityTurnOutcome.Completed,
                    trigger =
                        AgentActivityTurnTrigger.Visible(
                            target = "#qa-227-thread-host",
                            channelId = "c1",
                            sender = "qa-test33",
                            messageId = "m1",
                            display =
                                AgentActivityTurnTrigger.Display.Channel(
                                    channelName = "qa-227-thread-host",
                                    senderName = "qa-test33",
                                    thread = false,
                                ),
                        ),
                    actions = listOf(AgentActivityActionCount(AgentActivityActionKind.RunCommand, 1)),
                    toolCalls = 1,
                    problems = emptyList(),
                    problemCount = 0,
                    messagesSent = 0,
                    lastReplyMessageId = null,
                )
            val shortRow =
                sampleTurn("short-no-msg", nov21Utc).copy(
                    trigger =
                        AgentActivityTurnTrigger.Visible(
                            target = "agent-event:1a2b3c4d",
                            channelId = null,
                            sender = null,
                            messageId = null,
                            appEvent = AgentActivityTurnTrigger.AppEvent(appName = "Stamp", summary = "CI"),
                        ),
                    actions = emptyList(),
                )
            val context =
                RaftTimeContext(
                    localOffsetMinutes = -5 * 60,
                    todayEpochDay = raftEpochDayForDate(2026, 11, 23),
                    timeFormat = RaftTimeFormat24Hour,
                    timeZoneId = "America/New_York",
                )
            rows(listOf(jonyRow3, shortRow), context)

            val texts = bounds(MembersAgentRecentTurnTextTag)
            val badgeRows = bounds(MembersAgentRecentTurnBadgesTag)
            assertEquals(2, texts.size)
            assertEquals(2, badgeRows.size)

            val ranCommandBounds = onNodeWithText("ran a command", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val noToolCallsBounds = onNodeWithText("No tool calls", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

            assertTrue(
                badgeRows[0].top >= ranCommandBounds.bottom,
                "Row 0 'No message sent' chip must be on its own line below 'ran a command': badges=${badgeRows[0]} actions=$ranCommandBounds",
            )
            assertEquals(texts[0].left, badgeRows[0].left, "Row 0 chip must align with text column")

            assertTrue(
                badgeRows[1].top >= noToolCallsBounds.bottom,
                "Short row 'No message sent' chip must be on its own line below 'No tool calls': badges=${badgeRows[1]} actions=$noToolCallsBounds",
            )
            assertEquals(texts[1].left, badgeRows[1].left, "Short row chip must align with text column")
        }

    private fun sampleTurn(id: String, startedAt: Long): AgentActivityTurn =
        AgentActivityTurn(
            id = id,
            startedAtEpochMillis = startedAt,
            endedAtEpochMillis = startedAt + 5_000L,
            outcome = AgentActivityTurnOutcome.Completed,
            trigger = null,
            actions = listOf(AgentActivityActionCount(AgentActivityActionKind.RunCommand, 1)),
            toolCalls = 1,
            problems = emptyList(),
            problemCount = 0,
            messagesSent = 0,
            lastReplyMessageId = null,
        )
}
