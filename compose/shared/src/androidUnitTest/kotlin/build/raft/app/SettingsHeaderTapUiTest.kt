package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The hidden Developer entry's taps on real compositions: the Settings header (with and without the
 * phone's Back) and About's version row, which is drawn as a non-control row ([RaftSharedSettingsRow.hiddenTap]).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsHeaderTapUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Composable
    private fun InPage(content: @Composable () -> Unit) {
        val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager), content = content)
    }

    private fun headerTaps(withBack: Boolean): Int {
        var taps = 0
        runRobolectricComposeUiTest {
            setContent {
                InPage {
                    SettingsHomeContent(
                        payload = RaftSettingsHomePayload(title = "SettingsTitle"),
                        onHeaderClick = { taps++ },
                        onBack = if (withBack) ({}) else null,
                        onSelect = {},
                    )
                }
            }
            repeat(3) { onNodeWithText("SettingsTitle").performClick() }
            waitForIdle()
        }
        return taps
    }

    @Test
    fun titleTapReachesHeaderWithoutBack() = assertEquals(3, headerTaps(withBack = false))

    @Test
    fun titleTapReachesHeaderOnPushedPage() = assertEquals(3, headerTaps(withBack = true))

    @Test
    fun hiddenTapRowReachesItsActionWhileOtherInfoRowsDoNot() {
        val actions = mutableListOf<String>()
        runRobolectricComposeUiTest {
            setContent {
                InPage {
                    SharedSettingsSectionContent(
                        RaftSharedSettingsPayload(
                            sections =
                                listOf(
                                    RaftSharedSettingsSection(
                                        title = "Application",
                                        rows =
                                            listOf(
                                                RaftSharedSettingsRow(SETTINGS_ABOUT_VERSION_ROW, title = "VersionRow", value = "1.0", copyValue = "1.0", hiddenTap = true),
                                                RaftSharedSettingsRow("plainCopy", title = "CommitRow", value = "abc", copyValue = "abc"),
                                                RaftSharedSettingsRow("hiddenNoCopy", title = "HiddenNoCopyRow", hiddenTap = true),
                                            )
                                    )
                                )
                        )
                    ) { action, _ -> actions += action }
                }
            }
            repeat(2) { onNodeWithText("VersionRow").performClick() }
            onNodeWithText("CommitRow").performClick()
            onNodeWithText("HiddenNoCopyRow").performClick()
            waitForIdle()
        }
        assertEquals(listOf(SETTINGS_ABOUT_VERSION_ROW, SETTINGS_ABOUT_VERSION_ROW, "hiddenNoCopy"), actions)
    }
}
