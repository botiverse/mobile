package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.AppLocale
import build.raft.app.data.stringDescLocaleType
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

/**
 * Home's External section on a real composition (AGENTS.md "Compose UI tests"), as the web draws it
 * since botiverse/slock #15939 (SidebarChatSections `isEmptySectionHidden(…, alwaysHide)`): titled
 * External, drawn only while it holds a channel whatever "Hide empty sections" says, and not kept
 * for its "+" when there is none.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h900dp")
class HomeSidebarExternalSectionUiTest {
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

    private val general = RaftHomeConversationItem(id = "general", name = "general", kind = "channel")
    private val partners = RaftHomeConversationItem(id = "partners", name = "partners", kind = JointChannelKindWireValue)

    /** The shell as SettingsHomeScreen composes it, with the app's own labels and the "+" offered. */
    private fun ComposeUiTest.showHome(
        channels: () -> List<RaftHomeConversationItem>,
        hideEmptySections: () -> Boolean,
        onCreateExternalChannel: () -> Unit = {},
    ) =
        setContent {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                Box(Modifier.fillMaxSize()) {
                    RaftStructuralHomeShell(
                        currentServerName = "Raft",
                        currentServerId = "server-1",
                        channels = channels(),
                        hideEmptySections = hideEmptySections(),
                        contentRefreshEnabled = false,
                        selectedTabKey = "home",
                        showBottomBar = false,
                        searchAndActivityOnShellNav = true,
                        outerPadding = 0.dp,
                        labels = RaftHomeShellLabels.localized(),
                        onCreateJointChannelClick = onCreateExternalChannel,
                    )
                }
            }
        }

    @Test
    fun anEmptyExternalSectionIsNotDrawnWhateverHideEmptySectionsSays() =
        runRobolectricComposeUiTest {
            var hide by mutableStateOf(false)
            showHome(channels = { listOf(general) }, hideEmptySections = { hide })
            listOf(false, true).forEach { hidden ->
                runOnIdle { hide = hidden }
                // Empty sections follow the setting: an empty Pinned is drawn only with it off.
                onAllNodesWithText("Pinned").assertCountEquals(if (hidden) 0 else 1)
                onNodeWithText("Channels").assertExists()
                // External is not, either way, and not for its "+" either.
                onAllNodesWithText("External").assertCountEquals(0)
                onAllNodesWithContentDescription("Create external channel").assertCountEquals(0)
            }
        }

    @Test
    fun externalIsDrawnWithItsChannelsAndItsPlusAndGoesWithItsLastChannel() =
        runRobolectricComposeUiTest {
            var channels by mutableStateOf(listOf(general, partners))
            var creates = 0
            showHome(channels = { channels }, hideEmptySections = { false }, onCreateExternalChannel = { creates++ })
            onNodeWithText("External").assertExists()
            onNodeWithText("partners").assertExists()
            onNodeWithContentDescription("Create external channel").performClick()
            runOnIdle { assertEquals(1, creates) }

            runOnIdle { channels = listOf(general) }
            onAllNodesWithText("External").assertCountEquals(0)
            onAllNodesWithContentDescription("Create external channel").assertCountEquals(0)
        }
}
