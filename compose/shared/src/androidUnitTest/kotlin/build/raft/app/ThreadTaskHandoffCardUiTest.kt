package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The task hand-off card on a real composition, through the timeline's system-line branch
 * ([ThreadSystemMessageRow]) as the message list calls it: a hand-off with a reason is the card
 * ("@otter → @xxchan", its time, the reason), any other assignment line stays the one-line notice,
 * and once the task came back the card says who gave it back (web `TaskHandoffCard.tsx`, slock
 * v1.21.64).
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h900dp")
class ThreadTaskHandoffCardUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun english() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun restore() {
        StringDesc.localeType = previousLocale
    }

    private fun ComposeUiTest.timeline(vararg lines: RaftThreadMessageItem, show: List<RaftThreadMessageItem> = lines.toList()) {
        setContent {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    ThreadTaskHandoffFixtures.WithTime {
                        Column(Modifier.fillMaxSize()) {
                            show.forEach { ThreadSystemMessageRow(message = it, timestampValue = it.timestampLabel, timeline = lines.toList()) }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun aHandOffWithAReasonIsACardNamingBothPartiesAlike() =
        runRobolectricComposeUiTest {
            val handoff = ThreadTaskHandoffFixtures.handoff
            timeline(handoff)
            onNodeWithTag(TaskHandoffCardTag).assertExists()
            onNodeWithText("@otter").assertExists()
            onNodeWithText("@xxchan").assertExists()
            onNodeWithText("→").assertExists()
            onNodeWithTag(TaskHandoffCardMetaTag, useUnmergedTree = true).assertExists()
            onNodeWithTag(TaskHandoffCardReasonTag, useUnmergedTree = true).assertTextEquals("Your call: ship Friday or Monday? The numbers are in the sheet.")
            // The one-line notice is not drawn.
            onNodeWithText(handoff.body, substring = true).assertDoesNotExist()
        }

    @Test
    fun anAssignmentWithoutAReasonStaysTheOneLineNotice() =
        runRobolectricComposeUiTest {
            val plain = ThreadTaskHandoffFixtures.line("p1", "2026-11-10T08:00:00.000Z", ThreadTaskHandoffFixtures.otter, ThreadTaskHandoffFixtures.xx)
            timeline(plain)
            onNodeWithTag(TaskHandoffCardTag).assertDoesNotExist()
            onNodeWithText(plain.body).assertExists()
        }

    @Test
    fun handedBackBySomeoneElseTheCardSaysWhoAndFades() =
        runRobolectricComposeUiTest {
            val f = ThreadTaskHandoffFixtures
            timeline(f.handoff, f.handedBack, show = listOf(f.handoff))
            onNodeWithTag(TaskHandoffCardReturnedTag).assertExists()
            val at = formatThreadTimeRevealLabel(f.handedBack.timestampLabel, f.timeContext)
            onNodeWithText("xxchan handed it back to Otter · $at").assertExists()
        }

    @Test
    fun takenBackByItsActorTheCardSaysSo() =
        runRobolectricComposeUiTest {
            val f = ThreadTaskHandoffFixtures
            timeline(f.handoff, f.tookBack, show = listOf(f.handoff))
            onNodeWithTag(TaskHandoffCardReturnedTag).assertExists()
            val at = formatThreadTimeRevealLabel(f.tookBack.timestampLabel, f.timeContext)
            onNodeWithText("Otter took it back · $at").assertExists()
        }
}
