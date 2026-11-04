package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import build.raft.app.base.BridgeModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.IModuleCreator
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.PagerManager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Mid-drag of the list's drag-to-see-times (ThreadMessageTimeReveal.kt), light and dark: the rows slid
 * the full 64dp gutter toward the start, avatar included, and each row's time at the end edge on its
 * first line. An author row, two compact continuation rows, then another author. Reports land in
 * `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h420dp-xxhdpi")
class ThreadMessageTimeRevealScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    private val livePager =
        object : Pager() {}.apply {
            pagerId = "thread-time-reveal-screenshot"
            pageData = PageData(JSONObject())
            registerModule(BridgeModule.MODULE_NAME, IModuleCreator { BridgeModule() })
        }

    @BeforeTest fun registerPager() = PagerManager.register(livePager)

    @AfterTest fun unregisterPager() = PagerManager.unregister(livePager)

    @Test fun midDragLight() = capture("thread.time-reveal.mid-drag", RaftAppAppearance.Light)

    @Test fun midDragDark() = capture("thread.time-reveal.mid-drag.dark", RaftAppAppearance.Dark)

    /** Reduced motion, fully revealed: rows still, the two continuation rows' times in their start gutter. */
    @Test fun reducedMotionLight() = capture("thread.time-reveal.reduced-motion", RaftAppAppearance.Light, reducedMotion = true)

    private fun capture(name: String, appearance: RaftAppAppearance, reducedMotion: Boolean = false) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val clock = remember { RaftTimeContextProvider(ScreenshotTimeContext) }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(livePager),
                LocalRaftTimeContextProvider provides clock,
            ) {
                RaftDesignSystemTheme {
                    val reveal = rememberThreadTimeRevealState(Unit, reducedMotion = reducedMotion)
                    LaunchedEffect(reveal) {
                        reveal.begin()
                        reveal.offset.snapTo(reveal.gutterPx)
                    }
                    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
                        ScreenshotRows.forEach { (message, continuation) ->
                            ThreadTimeRevealRow(
                                state = reveal,
                                timeLabel = formatThreadTimeRevealLabel(message.timestampLabel, ScreenshotTimeContext),
                                continuation = continuation,
                            ) {
                                ThreadMessageCard(
                                    message = message,
                                    messageBodyTextStyle = raftMessageBodyTextStyle("md"),
                                    compactConsecutiveMessages = true,
                                    suppressRepeatedAuthor = continuation,
                                    compactTimestampLabel =
                                        formatThreadCompactTimestampLabel(message.timestampLabel, ScreenshotTimeContext),
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
                }
            }
        }
}

private val ScreenshotTimeContext =
    RaftTimeContext(
        localOffsetMinutes = 8 * 60,
        todayEpochDay = raftEpochDayForDate(2026, 10, 22),
        nowEpochMillis = 1_792_635_000_000L,
    )

private fun screenshotMessage(id: String, author: String, at: String, body: String) =
    RaftThreadMessageItem(
        id = id,
        authorId = "user-${author.lowercase()}",
        authorName = author,
        authorKind = "human",
        avatarLabel = author.take(1),
        timestampLabel = at,
        body = body,
    )

private val ScreenshotRows =
    listOf(
        screenshotMessage("m-1", "Ada", "2026-10-22T01:02:00Z", "Staging is green again, the migration ran in 40s.") to false,
        screenshotMessage("m-2", "Ada", "2026-10-22T01:03:00Z", "I'll keep an eye on the error rate for an hour.") to true,
        screenshotMessage("m-3", "Ada", "2026-10-22T01:07:00Z", "Error rate is flat.") to true,
        screenshotMessage("m-4", "Grace", "2026-10-22T01:15:00Z", "Thanks! Shipping to production after lunch then.") to false,
    )
