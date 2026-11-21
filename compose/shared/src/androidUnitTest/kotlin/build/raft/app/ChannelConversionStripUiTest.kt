package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
import build.raft.designsystem.components.LocalRaftSpinnerStillStep
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The conversion strip on a phone (web botiverse/slock 922c8de664
 * `channelConversionStrip.behavior.test.tsx`): one title and percentage; View opens the five steps
 * (a bottom sheet here, the web's popover) with the current one under way; Cancel asks before it
 * cancels; a failure is a notice with Retry and no strip; zh-CN copy is the web's.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h800dp-xxhdpi")
class ChannelConversionStripUiTest {
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

    private val running =
        ChannelConversionStripModel(
            showsStrip = true,
            stepIndex = 1,
            percent = 45,
            paused = false,
            statusNotice = null,
            failed = false,
            error = null,
            errorTraceId = null,
            offersCancel = true,
            offersRetry = false,
            busy = null,
        )

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
        // The spinners hold still: an endless animation never lets the test go idle.
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager), LocalRaftSpinnerStillStep provides 0) {
            RaftDesignSystemTheme { content() }
        }
    }

    private fun ComposeUiTest.awaitTag(tag: String) =
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.awaitText(text: String) =
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun viewShowsTheFiveStepsAndCancelAsksBeforeItCancels() =
        runRobolectricComposeUiTest {
            var cancels = 0
            setContent {
                Host {
                    ChannelConversionStatus(running, channelName = "lobby", onRetry = {}, onCancel = { cancels += 1 }, onCheckStatus = {}, stepsInPopover = false)
                }
            }
            onNodeWithTag(ChannelConversionTestTags.StripTitle, useUnmergedTree = true).assertTextEquals("Connecting with people outside…")
            onNodeWithTag(ChannelConversionTestTags.StripPercent, useUnmergedTree = true).assertTextEquals("45%")
            onAllNodesWithTag(ChannelConversionTestTags.Stages).assertCountEquals(0)

            onNodeWithTag(ChannelConversionTestTags.StripView).performClick()
            awaitTag(ChannelConversionTestTags.StagePrefix + "preserve")
            mapOf(
                "prepare" to "Prepare the channel, Completed step",
                "preserve" to "Preserving messages, files, threads, and tasks…, Current step, in progress",
                "verify" to "Verify history, Pending step",
                "access" to "Update server access, Pending step",
                "done" to "Done, Pending step",
            ).forEach { (step, description) ->
                onNodeWithTag(ChannelConversionTestTags.StagePrefix + step).assertContentDescriptionEquals(description)
            }

            onNodeWithTag(ChannelConversionTestTags.Cancel).performClick()
            awaitText("Cancel connecting?")
            assertEquals(0, cancels, "nothing is canceled before the confirmation")
            awaitText("#lobby goes back to how it was before.")
            onAllNodesWithText("Keep connecting", useUnmergedTree = true).assertCountEquals(1)
            onNodeWithTag(ChannelConversionTestTags.CancelConfirm).performClick()
            assertEquals(1, cancels)
        }

    @Test
    fun aFailureIsANoticeWithRetryAndNoStrip() =
        runRobolectricComposeUiTest {
            var retries = 0
            val failed =
                running.copy(
                    showsStrip = false,
                    failed = true,
                    error = ChannelConversionErrorText.Server("Verification failed"),
                    offersRetry = true,
                    offersCancel = false,
                )
            setContent {
                Host { ChannelConversionStatus(failed, channelName = "lobby", onRetry = { retries += 1 }, onCancel = {}, onCheckStatus = {}, stepsInPopover = false) }
            }
            awaitText("Couldn't finish connecting outside. Verification failed")
            onAllNodesWithTag(ChannelConversionTestTags.Strip).assertCountEquals(0)
            onAllNodesWithTag(ChannelConversionTestTags.Cancel).assertCountEquals(0)
            onNodeWithTag(ChannelConversionTestTags.Retry).performClick()
            assertEquals(1, retries)
        }

    @Test
    fun inChineseTheStripAndTheConfirmationAreTheWebsWords() =
        runRobolectricComposeUiTest {
            StringDesc.localeType = AppLocale.SimplifiedChinese.stringDescLocaleType
            val longName = "产品设计与工程协作讨论频道-二零二六年第四季度跨团队发布计划"
            setContent {
                Host { ChannelConversionStatus(running, channelName = longName, onRetry = {}, onCancel = {}, onCheckStatus = {}, stepsInPopover = false) }
            }
            onNodeWithTag(ChannelConversionTestTags.StripTitle, useUnmergedTree = true).assertTextEquals("正在连接外部…")
            onNodeWithTag(ChannelConversionTestTags.StripView).performClick()
            awaitTag(ChannelConversionTestTags.StagePrefix + "preserve")
            onNodeWithTag(ChannelConversionTestTags.StagePrefix + "preserve").assertContentDescriptionEquals("正在保留消息、文件、消息列和任务…，当前步骤，正在进行")
            onNodeWithTag(ChannelConversionTestTags.Cancel).performClick()
            awaitText("#$longName 将恢复到连接前的样子。")
        }
}
