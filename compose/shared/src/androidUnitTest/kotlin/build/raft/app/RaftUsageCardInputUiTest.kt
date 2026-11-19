package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.designsystem.components.LocalRaftCopyHandler
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftCopyHandler
import build.raft.designsystem.components.RaftPreviewCardState
import build.raft.designsystem.components.raftClickable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The usage card's button decides by the input, not the device (a phone with a mouse plugged in):
 * on a phone-sized layout a mouse resting on a usage line opens its card and a mouse click pins it,
 * while a finger's tap opens the usage sheet. The card detects no taps of its own, so a runtime
 * chip's long press copies its name and opens nothing; the trigger is a button for screen readers.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class RaftUsageCardInputUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val states = List(2) { RaftPreviewCardState() }
    private val sheets = mutableListOf<Int>()
    private val copied = mutableListOf<String>()

    private fun ComposeUiTest.showLines() {
        setContent {
            RaftDesignSystemTheme {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    states.indices.forEach { index ->
                        ComputerRuntimeUsagePreviewCard(
                            runtimeLabel = "Claude $index",
                            controller = null,
                            runtimeVersion = null,
                            onTap = { sheets += index },
                            state = states[index],
                        ) { onClick ->
                            Box(Modifier.fillMaxWidth().height(56.dp).testTag(lineTag(index)).raftClickable(onClick = { onClick?.invoke() })) {
                                BasicText("Claude $index")
                            }
                        }
                    }
                }
            }
        }
        mainClock.advanceTimeBy(1_000)
    }

    private fun ComposeUiTest.showChip() {
        setContent {
            RaftDesignSystemTheme {
                CompositionLocalProvider(LocalRaftCopyHandler provides RaftCopyHandler { copied += it }) {
                    Box(Modifier.fillMaxSize().padding(16.dp)) {
                        ComputerRuntimeUsagePreviewCard(
                            runtimeLabel = "Claude Code",
                            controller = null,
                            runtimeVersion = null,
                            onTap = { sheets += 0 },
                            state = states[0],
                        ) { onClick ->
                            // As the Computer's runtime chip: a badge button whose long press copies.
                            RaftBadge(
                                text = "Claude Code",
                                modifier = Modifier.testTag(ChipTag),
                                copyText = "Claude Code",
                                appearance = RaftBadgeAppearance.Soft,
                                variant = RaftBadgeVariant.Information,
                                onClick = onClick,
                            )
                        }
                    }
                }
            }
        }
        mainClock.advanceTimeBy(1_000)
    }

    @Test
    fun aMouseHoverOpensTheCardItsClickPinsItAndAFingersTapOpensTheSheet() = runRobolectricComposeUiTest {
        showLines()

        onNodeWithTag(lineTag(0)).performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(1_000)
        assertTrue(states[0].isOpen, "a mouse resting on the line opens its card")
        assertFalse(states[0].isPinned)

        onNodeWithTag(lineTag(0)).performMouseInput { click(center) }
        mainClock.advanceTimeBy(1_000)
        assertTrue(states[0].isPinned, "a mouse click pins the card")
        assertEquals(emptyList(), sheets, "a mouse opens no sheet")

        // The same phone, a finger on the other line.
        onNodeWithTag(lineTag(1)).performTouchInput { click() }
        mainClock.advanceTimeBy(1_000)
        assertEquals(listOf(1), sheets, "a finger's tap opens the usage sheet")
        assertFalse(states[1].isOpen || states[1].isPinned, "and no card")
    }

    @Test
    fun aLongPressOnTheChipCopiesAndOpensNothing() = runRobolectricComposeUiTest {
        showChip()

        onNodeWithTag(ChipTag).performTouchInput { longClick() }
        mainClock.advanceTimeBy(1_000)
        assertEquals(listOf("Claude Code"), copied, "the long press copies the runtime name")
        assertEquals(emptyList(), sheets, "and opens no sheet")
        assertFalse(states[0].isOpen || states[0].isPinned, "nor the card")

        onNodeWithTag(ChipTag).performTouchInput { click() }
        mainClock.advanceTimeBy(1_000)
        assertEquals(listOf(0), sheets, "a tap still opens the usage")
        assertEquals(listOf("Claude Code"), copied, "and copies nothing")
    }

    @Test
    fun theChipIsAButtonForScreenReaders() = runRobolectricComposeUiTest {
        showChip()

        onNodeWithTag(ChipTag)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHasClickAction()
    }

    private companion object {
        const val ChipTag = "usage-chip"

        fun lineTag(index: Int) = "usage-line-$index"
    }
}
