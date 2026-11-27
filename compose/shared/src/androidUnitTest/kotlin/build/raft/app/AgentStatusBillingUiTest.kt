package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.stringDescLocaleType
import build.raft.app.members.MembersAgentBillingBannerTestTags
import build.raft.app.members.MembersAgentRuntimeErrorBanner
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
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
 * The agent page on a real composition (AGENTS.md "Compose UI tests"), against the web behaviour
 * tests (botiverse/slock #13460 agentRuntimeErrorBanner.behavior.test.tsx: the status text and
 * Change model; #13435 agentBillingBanner.behavior.test.tsx: the billing banner, its one action,
 * or who can fix it).
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h900dp")
class AgentStatusBillingUiTest {
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

    private companion object {
        /** 2026-06-25 06:00 UTC. */
        const val Now = 1_782_367_200_000L
    }

    private val agent = MemberProfile(id = MemberId("agent-1"), displayName = "Ruby", handle = "ruby", kind = MemberKind.Agent)

    private fun ComposeUiTest.showBillingBanner(canViewPrivate: Boolean, onOpenModelSettings: (() -> Unit)?, onCopyInfo: () -> Unit = {}) {
        setContent {
            RaftDesignSystemTheme {
                Column(Modifier.fillMaxSize()) {
                    MembersAgentRuntimeErrorBanner(
                        errorText = agentRuntimeErrorDisplayText(AgentRuntimeErrorDisplay.Known(AgentRuntimeErrorKind.BillingExhausted)),
                        canViewPrivate = canViewPrivate,
                        showViewLogs = false,
                        onViewLogs = {},
                        onCopyInfo = onCopyInfo,
                        billingAgentName = "Ruby",
                        onOpenModelSettings = onOpenModelSettings,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    @Test
    fun withoutTheRightTheBillingBannerSaysWhoCanFixIt() =
        runRobolectricComposeUiTest {
            showBillingBanner(canViewPrivate = false, onOpenModelSettings = null)
            // Catalog copy for every viewer, never the generic "agent error" fallback.
            onNodeWithText("Model service balance too low").assertExists()
            onNodeWithText("Ask a server admin to fix this.").assertExists()
            onAllNodesWithTag(MembersAgentBillingBannerTestTags.ModelSettings).assertCountEquals(0)
            onAllNodesWithText("Copy Info", ignoreCase = true).assertCountEquals(0)
        }
}
