package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.markdown.RaftMarkdownBody
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * An @mention in message text opens its profile card (web MentionLink's PreviewCard) when a mouse
 * rests on it for 200ms; a finger never opens it; a click on the mention closes the card and still
 * runs the mention's own click (open the profile); plain words open nothing. Drives
 * [RaftMarkdownBody] under a [LocalRaftMemberPreviewCardSource], as the Thread message list.
 */
@OptIn(ExperimentalTestApi::class)
// Real text measurement: the legacy graphics mode lays glyphs out with next to no width, so
// no character's box would hold the pointer.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h600dp")
class MentionPreviewCardUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private class Calls {
        val resolved = mutableListOf<String>()
        val opened = mutableListOf<String>()
    }

    private fun ComposeUiTest.showBody(calls: Calls) {
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
        val source =
            RaftMemberPreviewCardSource(
                serverMembersStore = null,
                serverId = "server-1",
                agentStatusResolver = EmptyRaftAgentStatusResolver,
                memberAvatarResolver = EmptyRaftMemberAvatarResolver,
                resolveMention = { label ->
                    calls.resolved += label
                    if (label == "@ada") MemberDetailIdentity(MemberId("human-ada"), MemberKind.Human) else null
                },
                model = { identity, _, _ ->
                    RaftProfilePreviewCardModel(
                        memberId = identity.memberId.value,
                        kind = MemberKind.Human,
                        displayName = CARD_NAME,
                        handle = "ada",
                        description = "Designs the release checklists.",
                    )
                },
            )
        setContent {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager), LocalRaftMemberPreviewCardSource provides source) {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize().background(RaftColor.Canvas).padding(24.dp)) {
                        RaftMarkdownBody(
                            body = "Ask @ada about the release notes",
                            modifier = Modifier.width(420.dp),
                            isTagValid = { true },
                            onOpenTag = { calls.opened += it.label },
                        )
                    }
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.textNode() = onNode(hasText("Ask", substring = true), useUnmergedTree = true)

    private fun ComposeUiTest.textLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        textNode().fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single()
    }

    /** The middle of the [index]th character of the body's text, in the text node's px. */
    private fun ComposeUiTest.charCenter(index: Int): Offset = textLayout().getBoundingBox(index).center

    /** A character inside the rendered @ada mention. */
    private fun ComposeUiTest.mentionIndex(): Int = textLayout().layoutInput.text.text.indexOf("@ada") + 1

    private fun ComposeUiTest.cardShown(): Boolean =
        onAllNodes(hasText(CARD_NAME), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aMouseRestingOnTheMentionOpensItsCardAndLeavingClosesIt() =
        runRobolectricComposeUiTest {
            val calls = Calls()
            showBody(calls)
            mainClock.autoAdvance = false
            val at = charCenter(mentionIndex())
            textNode().performMouseInput { moveTo(at) }
            // Not before the 200ms rest.
            mainClock.advanceTimeBy(100)
            assertTrue(!cardShown(), "the card waits for the pointer to rest")
            mainClock.advanceTimeBy(300)
            assertTrue(cardShown(), "a mouse resting on @ada opens its card")
            assertEquals(listOf("@ada"), calls.resolved)
            // The pointer moves off the mention onto plain words (not onto the card, 6dp below):
            // closed after the close delay.
            textNode().performMouseInput { moveTo(charCenter(1)) }
            mainClock.advanceTimeBy(500)
            assertTrue(!cardShown(), "leaving the mention closes the card")
        }

    @Test
    fun plainWordsOpenNothing() =
        runRobolectricComposeUiTest {
            val calls = Calls()
            showBody(calls)
            mainClock.autoAdvance = false
            textNode().performMouseInput { moveTo(charCenter(1)) }
            mainClock.advanceTimeBy(1_000)
            assertTrue(!cardShown())
            assertTrue(calls.resolved.isEmpty(), "no mention is looked up until the pointer is on one")
        }

    @Test
    fun aFingerNeverOpensTheCardAndItsTapStillOpensTheProfile() =
        runRobolectricComposeUiTest {
            val calls = Calls()
            showBody(calls)
            val at = charCenter(mentionIndex())
            textNode().performTouchInput { down(at) }
            mainClock.advanceTimeBy(1_000)
            assertTrue(!cardShown(), "a finger held on the mention opens no card")
            textNode().performTouchInput { up() }
            mainClock.advanceTimeBy(1_000)
            assertTrue(!cardShown())
            assertEquals(listOf("@ada"), calls.opened, "the tap still opens the mention")
        }

    @Test
    fun clickingTheMentionClosesTheCardAndRunsItsClick() =
        runRobolectricComposeUiTest {
            val calls = Calls()
            showBody(calls)
            mainClock.autoAdvance = false
            val at = charCenter(mentionIndex())
            textNode().performMouseInput { moveTo(at) }
            mainClock.advanceTimeBy(400)
            assertTrue(cardShown())
            textNode().performMouseInput { click(at) }
            mainClock.advanceTimeBy(400)
            assertTrue(!cardShown(), "a click on the mention closes its card")
            assertEquals(listOf("@ada"), calls.opened, "and the mention still opens the profile")
        }

    private companion object {
        const val CARD_NAME = "Ada Lovelace"
    }
}
