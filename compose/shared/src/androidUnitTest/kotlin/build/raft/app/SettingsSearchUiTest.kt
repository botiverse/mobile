package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings search on real compositions: the field on top of the phone's Settings page puts the
 * results in the groups' place, only those this person can see; opening one hands over its id; No
 * match searches everywhere with the query. And a page opened at a setting further down a lazy list
 * than the screen reaches scrolls until the row is composed, then brings it into view.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsSearchUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Composable
    private fun InPage(content: @Composable () -> Unit) {
        val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
        // The app's root theme, as every page has it.
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) { RaftDesignSystemTheme(content) }
    }

    private val payload =
        RaftSettingsHomePayload(
            title = "Settings",
            items =
                listOf(
                    SettingsHomePageLinkPayload(SettingsDestinationAccount, "Account", section = "Personal"),
                    SettingsHomePageLinkPayload(SettingsNativeDetailPage.Mobile.key, "Mobile", section = "Personal"),
                    SettingsHomePageLinkPayload(SettingsDestinationBilling, "Plan & Billing", section = "Workspace"),
                    SettingsHomePageLinkPayload(SettingsDestinationAdministration, "Administration", section = "Workspace"),
                ),
        )

    /** With the clock held: let a write from outside composition recompose, then move the clock on. */
    private fun ComposeUiTest.advance(millis: Long = 500) {
        Snapshot.sendApplyNotifications()
        mainClock.advanceTimeBy(millis)
    }

    @Test
    fun resultsReplaceTheGroupsOpenTheirSettingAndNoMatchSearchesEverywhere() = runRobolectricComposeUiTest {
        val state = SettingsSearchState()
        val opened = mutableListOf<RaftSearchableSetting>()
        val everywhere = mutableListOf<String>()
        mainClock.autoAdvance = false
        setContent {
            InPage {
                SettingsHomeContent(
                    payload = payload,
                    search =
                        SettingsSearchHost(
                            state = state,
                            // An ordinary member: Billing's plan and Administration's blocks are not theirs.
                            context = RaftSettingsSearchContext(rowKeys = emptyList(), role = "member"),
                            serverName = "Acme",
                            active = true,
                            onOpen = { opened += it },
                            onSearchEverywhere = { everywhere += it },
                        ),
                    onSelect = {},
                )
            }
        }
        advance()
        // The field is there; the query is set on its state, so nothing takes focus.
        onNode(hasSetTextAction()).assertExists()
        state.query = "ENTER"
        advance()
        // Found by another word for it ("enter"), on the phone's Mobile page.
        onNodeWithTag(settingsSearchResultTag("send-with")).assertExists().performClick()
        advance()
        assertEquals(listOf("send-with" to SettingsNativeDetailPage.Mobile.key), opened.map { it.id to it.rowKey })

        // "invite" is Invite links' word, but invite links are an admin's: No match.
        state.query = "invite"
        advance()
        onNodeWithTag(settingsSearchResultTag("invite-links")).assertDoesNotExist()
        onNodeWithTag(SettingsSearchEmptyTag).assertExists()
        onNodeWithTag(SettingsSearchEverywhereTag).performClick()
        advance()
        assertEquals(listOf("invite"), everywhere)

        // With text there is a clear button; an empty field brings the groups back.
        onNodeWithTag(SettingsSearchClearTag).assertExists()
        state.query = ""
        advance()
        onNodeWithTag(SettingsSearchEmptyTag).assertDoesNotExist()
        onNodeWithTag(SettingsSearchClearTag).assertDoesNotExist()
    }

    @Test
    fun aPageOpenedAtASettingBelowTheScreenScrollsToItsRow() = runRobolectricComposeUiTest {
        val locator = SettingsSearchLocator("notification-sound")
        setContent {
            CompositionLocalProvider(LocalSettingsSearchLocator provides locator) {
                LazyColumn(state = rememberSettingsSearchListState()) {
                    items(40) { index ->
                        val anchored = index == 30
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .then(if (anchored) Modifier.settingsSearchAnchor("notification-sound").testTag("anchored") else Modifier),
                        )
                    }
                }
            }
        }
        waitForIdle()
        onNodeWithTag("anchored").assertIsDisplayed()
        assertTrue(locator.settled)
    }
}
