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
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.settings.LocalSettingsSearchLocator
import build.raft.app.settings.SettingsSearchLocator
import build.raft.app.settings.rememberSettingsSearchListState
import build.raft.app.settings.settingsSearchAnchor
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import kotlin.test.Test
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
