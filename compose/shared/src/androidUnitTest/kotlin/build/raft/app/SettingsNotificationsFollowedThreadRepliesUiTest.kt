package build.raft.app

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.PushPreviewMode
import build.raft.app.data.RaftNotificationAccountSettings
import build.raft.app.data.RaftNotificationAccountSettingsPatch
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings › Notifications › Content, "Replies in threads I follow" (botiverse/slock #14677,
 * 9b284c6d, `followedThreadReplies`) on a real composition (AGENTS.md "Compose UI tests"): the row
 * after Sound exists only when the server sent the field, reads its value, and a flip sends only
 * that field.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h1200dp")
class SettingsNotificationsFollowedThreadRepliesUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private fun ComposeUiTest.showContent(settings: RaftNotificationAccountSettings, saved: MutableList<RaftNotificationAccountSettingsPatch>) =
        setContent {
            RaftDesignSystemTheme {
                SettingsNotificationContentSection(
                    settings = settings,
                    previewMode = PushPreviewMode.Full,
                    saving = false,
                    saveFailed = false,
                    onPreviewChange = {},
                    onSave = { saved += it }
                )
            }
        }

    @Test
    fun anOlderServerWithoutTheFieldShowsNoRow() =
        runRobolectricComposeUiTest {
            showContent(RaftNotificationAccountSettings(sound = true, followedThreadReplies = null), mutableListOf())
            onNodeWithTag(SettingsNotificationFollowedThreadRepliesTestTag).assertDoesNotExist()
        }

    @Test
    fun theRowShowsTheServerValueAndAFlipSendsOnlyThatField() =
        runRobolectricComposeUiTest {
            val saved = mutableListOf<RaftNotificationAccountSettingsPatch>()
            showContent(RaftNotificationAccountSettings(sound = true, followedThreadReplies = true, version = 4), saved)
            val switch = onNodeWithTag(SettingsNotificationFollowedThreadRepliesTestTag, useUnmergedTree = true)
            switch.assertExists()
            val toggle = onNode(isToggleable().and(hasAncestorTag(SettingsNotificationFollowedThreadRepliesTestTag)), useUnmergedTree = true)
            toggle.assertIsOn()
            toggle.performClick()
            runOnIdle {
                assertEquals(listOf(RaftNotificationAccountSettingsPatch(followedThreadReplies = false)), saved)
            }
        }

    @Test
    fun anOffValueReadsOff() =
        runRobolectricComposeUiTest {
            showContent(RaftNotificationAccountSettings(followedThreadReplies = false), mutableListOf())
            onNode(isToggleable().and(hasAncestorTag(SettingsNotificationFollowedThreadRepliesTestTag)), useUnmergedTree = true)
                .assertIsOff()
        }
}

private fun hasAncestorTag(tag: String) = androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag(tag))
