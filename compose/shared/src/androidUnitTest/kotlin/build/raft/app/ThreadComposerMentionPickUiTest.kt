package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import build.raft.app.data.RaftAndroidSqlDatabaseContext
import build.raft.app.data.SharedMembersLoadStatus
import build.raft.app.data.ThreadSendMessageMention
import build.raft.app.model.MemberKind
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #77: tapping a person or agent in the real @ suggestion panel records its id, so the send's
 * `mentions[]` names exactly that actor. A person and an agent share the handle `skyzh` here: the
 * case where an id-less `@skyzh` on `POST /v2/messages` would notify nobody. The composer's events
 * drive a real draft store the way `ThreadPage` does (`change` → Change, `selectMention` → SelectMention).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi")
class ThreadComposerMentionPickUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun tappingTheAgentAmongSameNamedSuggestionsSendsOnlyTheAgentsId() =
        pickAndExpect(tap = AGENT_NAME, expected = ThreadSendMessageMention(type = "agent", id = AGENT_ID, name = "skyzh"))

    @Test
    fun tappingThePersonSendsThePersonsId() =
        pickAndExpect(tap = HUMAN_NAME, expected = ThreadSendMessageMention(type = "user", id = HUMAN_ID, name = "skyzh"))

    private fun pickAndExpect(tap: String, expected: ThreadSendMessageMention) =
        runRobolectricComposeUiTest {
            RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
            val store = SharedThreadComposerDraftStore()
            store.dispatch(SharedThreadComposerDraftIntent.Start(RaftThreadComposerPayload(instanceId = "pick"), "send=c1"))
            store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "cc @sky"))
            setContent {
                RaftTheme(appearance = RaftAppearance.Light) {
                    val portal = remember { ThreadComposerSuggestionPortalState() }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        CompositionLocalProvider(LocalThreadComposerSuggestionPortal provides portal) {
                            ThreadComposerContent(
                                surface = ThreadComposerSurface(channelId = "c1"),
                                state =
                                    ThreadComposerUiState(
                                        instanceId = "pick",
                                        seedText = "cc @sky",
                                        suggestionsOpen = true,
                                        memberSuggestions =
                                            listOf(
                                                RaftComposerMemberItem(HUMAN_NAME, "skyzh", kind = MemberKind.Human, memberId = HUMAN_ID),
                                                RaftComposerMemberItem(AGENT_NAME, "skyzh", kind = MemberKind.Agent, memberId = AGENT_ID),
                                            ),
                                        memberSuggestionsStatus = SharedMembersLoadStatus.Loaded,
                                    ),
                                onEvent = { action, value, _, _ ->
                                    when (action) {
                                        "change" -> store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = value))
                                        ThreadComposerSelectMentionEvent ->
                                            decodeThreadComposerMentionEvent(value)?.let {
                                                store.dispatch(SharedThreadComposerDraftIntent.SelectMention(it))
                                            }
                                    }
                                },
                                onKeyboardHeightChange = {},
                                contentPaddingTop = 0.dp,
                                contentPaddingBottom = 0.dp,
                                attachmentPreviewEnabled = true,
                            )
                        }
                    }
                    ThreadComposerSuggestionPortalHost(portal)
                }
            }
            waitForIdle()

            onNodeWithText(tap, useUnmergedTree = true).performClick()
            waitForIdle()

            val state = store.state.value
            assertEquals("cc @skyzh ", state.seedText)
            assertEquals(listOf(expected), state.structuredMentions.visibleStructuredMentionsIn(state.seedText))
        }

    private companion object {
        const val HUMAN_NAME = "Skyzh Human"
        const val AGENT_NAME = "Skyzh Agent"
        const val HUMAN_ID = "1b2c3d4e-5f60-4718-8293-a4b5c6d7e8f9"
        const val AGENT_ID = "9a8b7c6d-5e4f-4a3b-9c2d-1e0f9a8b7c6d"
    }
}
