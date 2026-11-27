package build.raft.app

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import build.raft.app.data.AppLocale
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
import build.raft.app.thread.ThreadMessageAvatar
import build.raft.app.thread.ThreadMessageAvatarTestTag
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A message's sender avatar on a real composition (AGENTS.md "Compose UI tests"): VoiceOver / TalkBack
 * and UI automation reach it as a button that opens the author's profile, with "Mention …" (the long
 * press) as an action, apart from the sender's name next to it, which mentions.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h900dp")
class ThreadMessageAvatarAccessibilityUiTest {
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

    private val events = mutableListOf<String>()

    private fun ComposeUiTest.showAvatar(profileActions: Boolean = true) {
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                ThreadMessageAvatar(
                    interactionIdentity = "message-1",
                    avatarLabel = "Ada Lovelace",
                    avatarKind = RaftAvatarKind.Human,
                    avatarWireValue = null,
                    status = null,
                    onClick = { events += "profile" },
                    onLongPress = { events += "mention" },
                    profileActions = profileActions,
                )
            }
        }
        waitForIdle()
    }

    @Test
    fun theAvatarIsAButtonThatOpensTheProfileAndOffersMentionAsAnAction() = runRobolectricComposeUiTest {
        showAvatar()
        val avatar = onNodeWithTag(ThreadMessageAvatarTestTag)
        val config = avatar.fetchSemanticsNode().config
        assertEquals(Role.Button, config.getOrNull(SemanticsProperties.Role))
        assertEquals("View profile of Ada Lovelace", config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull())
        val actions = config[SemanticsActions.CustomActions]
        assertEquals(listOf("Mention Ada Lovelace"), actions.map { it.label })

        // The screen reader's activate: the profile, as a tap.
        avatar.performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("profile"), events)
        // The custom action: the long press's mention.
        assertTrue(actions.single().action())
        assertEquals(listOf("profile", "mention"), events)
        // A finger's tap still opens the profile.
        avatar.performClick()
        waitForIdle()
        assertEquals(listOf("profile", "mention", "profile"), events)
    }
}
