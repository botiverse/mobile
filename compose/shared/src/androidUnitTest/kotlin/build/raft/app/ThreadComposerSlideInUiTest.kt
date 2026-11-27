package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import build.raft.app.data.AppLocale
import build.raft.app.data.RaftAndroidSqlDatabaseContext
import build.raft.app.data.SharedMembersChannelSuggestionSlice
import build.raft.app.data.SharedMembersMemberSuggestionSlice
import build.raft.app.data.stringDescLocaleType
import build.raft.app.nav.LocalRaftStackPageInMotion
import build.raft.app.resources.StringDesc
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.ThreadComposerRestingHeight
import build.raft.app.thread.ThreadComposerRestingHeightPlaceholderTestTag
import build.raft.app.thread.ThreadComposerRoute
import build.raft.app.thread.dispatch
import build.raft.app.thread.threadComposerDraftKey
import build.raft.app.thread.toThreadTimelineSlice
import build.raft.app.threadComposerDraftKey
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 1.28 (artin 11-22): a conversation pushed onto the stack shows its composer while the push slide
 * runs, as in 1.27. #5320 held the editor back until the slide settled, so an empty strip slid in
 * and the input box popped in after the page.
 *
 * The real route ([ThreadComposerRoute]) with its draft loaded, under the stack's
 * [LocalRaftStackPageInMotion] = true (what the phone stack provides its top page until the push
 * settles): the composer is there, with its placeholder and an editor that takes text, and the
 * blank slot is not.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi")
class ThreadComposerSlideInUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun setUp() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
        ThreadComposerRestingHeight.clearForTest()
    }

    @AfterTest
    fun tearDown() {
        StringDesc.localeType = previousLocale
        ThreadComposerRestingHeight.clearForTest()
    }

    @Test
    fun theComposerIsThereWhileThePageSlidesIn() =
        runRobolectricComposeUiTest {
            setComposer(startedDraft())
            waitForIdle()

            onNodeWithTag(ThreadComposerRestingHeightPlaceholderTestTag).assertDoesNotExist()
            onNodeWithText(Placeholder, useUnmergedTree = true).assertIsDisplayed()
            onNode(hasSetTextAction(), useUnmergedTree = true).assertExists("the editor mounts while the page slides in")
        }

    /** The channel's draft session, started as the thread page starts it once it knows the conversation. */
    private fun startedDraft(): SharedThreadComposerDraftViewModel =
        SharedThreadComposerDraftViewModel().apply {
            dispatch(
                SharedThreadComposerDraftIntent.Start(
                    composer = RaftThreadComposerPayload(instanceId = ComposerId, seedText = ""),
                    draftKey = ComposerSurface.threadComposerDraftKey(),
                )
            )
        }

    private fun ComposeUiTest.setComposer(draft: SharedThreadComposerDraftViewModel) {
        RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
        val payload = RaftThreadScreenPayload(instanceId = ChannelId, status = "content")
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    CompositionLocalProvider(LocalRaftStackPageInMotion provides true) {
                        Box(Modifier.fillMaxWidth().testTag(SlotTag)) {
                            ThreadComposerRoute(
                                baseComposerState = RaftThreadComposerPayload(instanceId = ComposerId, placeholder = Placeholder),
                                composerDraftViewModel = draft,
                                renderedPayload = payload,
                                timelineSlice = payload.toThreadTimelineSlice(),
                                resolvedAuxiliaryThreadId = ChannelId,
                                surface = ComposerSurface,
                                memberSuggestionsFlow = flowOf(SharedMembersMemberSuggestionSlice()),
                                channelSuggestionsFlow = flowOf(SharedMembersChannelSuggestionSlice()),
                                agentStatusResolver = EmptyRaftAgentStatusResolver,
                                onEvent = { _, _, _, _ -> },
                                onDropFiles = {},
                                onSendRequest = { ThreadComposerSendAdmission.Rejected },
                                onKeyboardHeightChange = {},
                            )
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val ChannelId = "c1"
        const val ComposerId = "composer-c1"
        const val Placeholder = "Message #general"
        const val SlotTag = "composer-slot"
        val ComposerSurface = ThreadComposerSurface(channelId = ChannelId)
    }
}
