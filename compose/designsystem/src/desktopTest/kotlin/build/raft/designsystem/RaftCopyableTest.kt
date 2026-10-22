package build.raft.designsystem

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import build.raft.designsystem.components.LocalRaftCopyHandler
import build.raft.designsystem.components.RaftCopyHandler
import build.raft.designsystem.components.RaftDescriptionList
import build.raft.designsystem.components.raftCopyable
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftStrings
import build.raft.designsystem.theme.RaftStrings
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class RaftCopyableTest {
    private val copyAction =
        SemanticsMatcher("has a custom Copy action") { node ->
            node.copyActions().any { it.label == "Kopieren" }
        }

    @Test
    fun longPressCopiesThePlainValueAndExposesTheCopyAction() =
        runComposeUiTest {
            val copied = mutableListOf<String>()
            setContent {
                RaftTheme {
                    CompositionLocalProvider(
                        LocalRaftCopyHandler provides RaftCopyHandler { copied += it },
                        LocalRaftStrings provides RaftStrings(copy = "Kopieren"),
                    ) {
                        RaftText("mac-studio-2", modifier = Modifier.testTag("value").raftCopyable("mac-studio-2"))
                    }
                }
            }
            val value = onNodeWithTag("value")
            value.assert(copyAction)
            // Plain text: no click action, so a fact still doesn't read as a button.
            value.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))

            value.performTouchInput { longClick() }
            waitForIdle()
            assertEquals(listOf("mac-studio-2"), copied)

            val custom = value.fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == "Kopieren" }
            runOnIdle { custom.action() }
            assertEquals(listOf("mac-studio-2", "mac-studio-2"), copied)
        }

    @Test
    fun aTappableValueStillTapsAndTheLongPressCopies() =
        runComposeUiTest {
            val copied = mutableListOf<String>()
            var taps = 0
            setContent {
                RaftTheme {
                    CompositionLocalProvider(LocalRaftCopyHandler provides RaftCopyHandler { copied += it }) {
                        RaftText(
                            "mac-studio-2",
                            modifier = Modifier.testTag("link").raftCopyable("mac-studio-2", onClick = { taps += 1 }),
                        )
                    }
                }
            }
            val link = onNodeWithTag("link")
            link.performClick()
            waitForIdle()
            assertEquals(1, taps)
            assertEquals(emptyList(), copied)

            link.performTouchInput { longClick() }
            waitForIdle()
            assertEquals(1, taps)
            assertEquals(listOf("mac-studio-2"), copied)
            link.assert(
                SemanticsMatcher("has a custom Copy action") { node ->
                    node.copyActions().any { it.label == "Copy" }
                },
            )
        }

    @Test
    fun descriptionValuesCopyTheValueNotTheTerm() =
        runComposeUiTest {
            val copied = mutableListOf<String>()
            setContent {
                RaftTheme {
                    CompositionLocalProvider(LocalRaftCopyHandler provides RaftCopyHandler { copied += it }) {
                        RaftDescriptionList {
                            Item("OS", "darwin")
                            Item("Status", "Online", copyable = false)
                        }
                    }
                }
            }
            onNodeWithText("darwin", useUnmergedTree = true).performTouchInput { longClick() }
            onNodeWithText("Online", useUnmergedTree = true).performTouchInput { longClick() }
            waitForIdle()
            assertEquals(listOf("darwin"), copied)
            // The fact is one screen-reader item; it carries the value's Copy action.
            val fact = onNodeWithText("darwin").fetchSemanticsNode()
            runOnIdle { fact.config[SemanticsActions.CustomActions].single { it.label == "Copy" }.action() }
            assertEquals(listOf("darwin", "darwin"), copied)
        }
}

private fun SemanticsNode.copyActions(): List<CustomAccessibilityAction> =
    if (SemanticsActions.CustomActions in config) config[SemanticsActions.CustomActions] else emptyList()
