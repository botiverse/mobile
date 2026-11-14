package build.raft.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import build.raft.app.data.AppLocale
import build.raft.app.data.RaftMinBuildGateDecision
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The minimum build gate layer on a real composition ([RaftMinBuildGateFrame]): a block takes every
 * touch and hides the page from accessibility, keeps Update / Send feedback / Sign out working, and
 * goes away when the decision does (flag off); a banner leaves the page usable and dismisses.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class RaftMinBuildGateUiTest {
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

    private val url = "https://example.test/raft.apk"

    @Test
    fun blockTakesEveryTouchUntilTheDecisionLifts() = runRobolectricComposeUiTest {
        var decision by mutableStateOf<RaftMinBuildGateDecision>(RaftMinBuildGateDecision.Block(12_000_000L, 12_600_000L, url))
        var pageTaps = 0
        val opened = mutableListOf<String>()
        var feedback = 0
        var signOuts = 0
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                RaftMinBuildGateFrame(
                    decision = decision,
                    dismissedBannerThreshold = null,
                    signingOut = false,
                    onUpdate = { opened += it },
                    onSendFeedback = { feedback++ },
                    onSignOut = { signOuts++ },
                    onDismissBanner = {},
                ) {
                    Box(Modifier.fillMaxSize().testTag(PAGE).clickable { pageTaps++ })
                }
            }
        }
        waitForIdle()

        onNodeWithTag(RAFT_MIN_BUILD_GATE_BLOCK_TAG).assertExists()
        // Hidden from accessibility services while blocked.
        onNodeWithTag(PAGE).assertDoesNotExist()
        onNodeWithTag(RAFT_MIN_BUILD_GATE_BLOCK_TAG).performTouchInput { click(Offset(8f, 8f)) }
        waitForIdle()
        assertEquals(0, pageTaps, "a touch on the block reached the page")

        onNodeWithText("Please update to the latest version").assertExists()
        onNodeWithText("Update").performClick()
        onNodeWithText("Send feedback").performClick()
        onNodeWithText("Sign out").performClick()
        waitForIdle()
        assertEquals(listOf(url), opened)
        assertEquals(1, feedback)
        assertEquals(1, signOuts)

        // Flag off: the block goes and the page is usable again.
        runOnIdle { decision = RaftMinBuildGateDecision.None }
        onNodeWithTag(RAFT_MIN_BUILD_GATE_BLOCK_TAG).assertDoesNotExist()
        onNodeWithTag(PAGE).performClick()
        waitForIdle()
        assertEquals(1, pageTaps)
    }

    @Test
    fun bannerLeavesThePageUsableAndDismisses() = runRobolectricComposeUiTest {
        val threshold = 12_000_000L
        var dismissed by mutableStateOf<Long?>(null)
        var pageTaps = 0
        val opened = mutableListOf<String>()
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                RaftMinBuildGateFrame(
                    decision = RaftMinBuildGateDecision.Banner(threshold, url),
                    dismissedBannerThreshold = dismissed,
                    signingOut = false,
                    onUpdate = { opened += it },
                    onSendFeedback = {},
                    onSignOut = {},
                    onDismissBanner = { dismissed = it },
                ) {
                    Box(Modifier.fillMaxSize().testTag(PAGE).clickable { pageTaps++ })
                }
            }
        }
        waitForIdle()

        onNodeWithTag(RAFT_MIN_BUILD_GATE_BANNER_TAG).assertExists()
        onNodeWithTag(PAGE).performClick()
        onNodeWithText("Update").performClick()
        waitForIdle()
        assertEquals(1, pageTaps)
        assertEquals(listOf(url), opened)

        onNodeWithText("Dismiss").performClick()
        waitForIdle()
        assertEquals(threshold, dismissed)
        onNodeWithTag(RAFT_MIN_BUILD_GATE_BANNER_TAG).assertDoesNotExist()
    }

    private companion object {
        const val PAGE = "page"
    }
}
