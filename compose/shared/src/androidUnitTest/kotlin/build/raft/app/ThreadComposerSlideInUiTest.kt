package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.DpRect
import build.raft.app.data.AppLocale
import build.raft.app.data.RaftAndroidSqlDatabaseContext
import build.raft.app.data.SharedMembersChannelSuggestionSlice
import build.raft.app.data.SharedMembersMemberSuggestionSlice
import build.raft.app.data.stringDescLocaleType
import build.raft.app.nav.LocalRaftStackPageInMotion
import build.raft.app.resources.StringDesc
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 1.28: since #5320 a conversation pushed onto the stack mounts its composer's editor only once
 * the push slide settles, and the composer's slot showed an empty strip through the slide, the
 * input box popping in after it. The composer now slides in with the page as a picture of itself
 * at rest ([ThreadComposerAtRest]), and the editor still mounts after the slide.
 *
 * The real route ([ThreadComposerRoute]) with its draft loaded, under the stack's
 * [LocalRaftStackPageInMotion]: in motion its placeholder shows and nothing takes text; once
 * settled the live editor mounts with the composer at the same height and the placeholder where
 * it was. Real text metrics (native graphics), so the editor's own line counts.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
        // Process-wide; the blank slot reads it.
        ThreadComposerRestingHeight.clearForTest()
    }

    @AfterTest
    fun tearDown() {
        StringDesc.localeType = previousLocale
        ThreadComposerRestingHeight.clearForTest()
    }

    @Test
    fun theComposerSlidesInAtRestAndItsEditorMountsInPlaceOnceTheSlideSettles() =
        runRobolectricComposeUiTest {
            val inMotion = mutableStateOf(true)
            setComposer(inMotion, startedDraft(seedText = ""))

            // Sliding in: the composer at rest, its placeholder on show, nothing to type into yet.
            onNodeWithTag(ThreadComposerAtRestTestTag).assertIsDisplayed()
            onNodeWithTag(ThreadComposerRestingHeightPlaceholderTestTag).assertDoesNotExist()
            onNodeWithText(Placeholder, useUnmergedTree = true).assertIsDisplayed()
            onAllNodes(hasSetTextAction(), useUnmergedTree = true).assertCountEquals(0)
            val picture = onNodeWithTag(SlotTag).getBoundsInRoot()
            val picturePlaceholder = onNodeWithText(Placeholder, useUnmergedTree = true).getBoundsInRoot()

            runOnIdle { inMotion.value = false }

            // Settled: the live editor takes the picture's place.
            onNodeWithTag(ThreadComposerAtRestTestTag).assertDoesNotExist()
            onNode(hasSetTextAction(), useUnmergedTree = true).assertExists("the live editor mounts once the slide settles")
            onNodeWithText(Placeholder, useUnmergedTree = true).assertIsDisplayed()
            val live = onNodeWithTag(SlotTag).getBoundsInRoot()
            assertTrue(
                abs((live.bottom - live.top).value - (picture.bottom - picture.top).value) <= Tolerance,
                "the composer's height changed when the editor mounted: $picture -> $live",
            )
            assertSameBounds("the composer", picture, live)
            assertSameBounds("its placeholder", picturePlaceholder, onNodeWithText(Placeholder, useUnmergedTree = true).getBoundsInRoot())
        }

    @Test
    fun aDraftWithTextKeepsTheBlankSlotUntilTheEditorMounts() =
        runRobolectricComposeUiTest {
            val inMotion = mutableStateOf(true)
            setComposer(inMotion, startedDraft(seedText = "half a thought"))

            onNodeWithTag(ThreadComposerRestingHeightPlaceholderTestTag).assertExists()
            onNodeWithTag(ThreadComposerAtRestTestTag).assertDoesNotExist()
            onAllNodes(hasSetTextAction(), useUnmergedTree = true).assertCountEquals(0)

            runOnIdle { inMotion.value = false }

            onNodeWithTag(ThreadComposerRestingHeightPlaceholderTestTag).assertDoesNotExist()
            onNode(hasSetTextAction(), useUnmergedTree = true).assertExists("the live editor mounts once the slide settles")
        }

    /** The channel's draft session, started as the thread page starts it once it knows the conversation. */
    private fun startedDraft(seedText: String): SharedThreadComposerDraftViewModel =
        SharedThreadComposerDraftViewModel().apply {
            dispatch(
                SharedThreadComposerDraftIntent.Start(
                    composer = RaftThreadComposerPayload(instanceId = ComposerId, seedText = seedText),
                    draftKey = ComposerSurface.threadComposerDraftKey(),
                )
            )
        }

    private fun ComposeUiTest.setComposer(inMotion: MutableState<Boolean>, draft: SharedThreadComposerDraftViewModel) {
        RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
        val payload = RaftThreadScreenPayload(instanceId = ChannelId, status = "content")
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    // What the phone stack (RaftMobilePageStackDisplay) provides its top page until the push settles.
                    CompositionLocalProvider(LocalRaftStackPageInMotion provides inMotion.value) {
                        Box(Modifier.fillMaxWidth().testTag(SlotTag)) {
                            ThreadComposerRoute(
                                baseComposerState = RaftThreadComposerPayload(instanceId = ComposerId, placeholder = "Message #general"),
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

    private fun assertSameBounds(what: String, before: DpRect, after: DpRect) {
        val moved =
            listOf(after.left - before.left, after.top - before.top, after.right - before.right, after.bottom - before.bottom)
                .maxOf { abs(it.value) }
        assertTrue(moved <= Tolerance, "$what moved by ${moved}dp when the editor mounted: $before -> $after")
    }

    private companion object {
        const val ChannelId = "c1"
        const val ComposerId = "composer-c1"
        const val Placeholder = "Message #general"
        const val SlotTag = "composer-slot"
        const val Tolerance = 0.5f
        val ComposerSurface = ThreadComposerSurface(channelId = ChannelId)
    }
}
