package build.raft.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import build.raft.designsystem.components.LocalRaftPulseClock
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftCard
import build.raft.designsystem.components.RaftCardVariant
import build.raft.designsystem.components.RaftDescriptionList
import build.raft.designsystem.components.RaftDescriptionListDirection
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftPulseClock
import build.raft.designsystem.components.RaftSkeleton
import build.raft.designsystem.components.RaftUnreadCount
import build.raft.designsystem.theme.LocalRaftSurfaceColor
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftUiThemeColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap

/** What screen readers and keyboards get from the display components. */
@OptIn(ExperimentalTestApi::class)
class RaftDisplaySemanticsTest {
    private fun themed(content: @Composable () -> Unit): @Composable () -> Unit = {
        RaftTheme(appearance = RaftAppearance.Dark, systemDark = false, content = content)
    }

    @Test
    fun aSelectedRowIsSelectedInSemantics() =
        runComposeUiTest {
            setContent(
                themed {
                    RaftListRowGroup {
                        RaftListRow("general", onClick = {}, selected = true)
                        RaftListRow("random", onClick = {}, divider = true)
                    }
                },
            )
            onNodeWithText("general").assertIsSelected()
            onNodeWithText("random").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
        }

    @Test
    fun horizontalFactsReadTermAndValueTogether() =
        runComposeUiTest {
            setContent(
                themed {
                    Box(Modifier.width(700.dp)) {
                        RaftDescriptionList(direction = RaftDescriptionListDirection.Horizontal) {
                            Item("Runtime") { RaftBadge("Claude Code") }
                            Item("Agent ID", "agt_7f3k29xq", mono = true)
                        }
                    }
                },
            )
            onNodeWithText("Runtime").assert(hasText("Claude Code"))
            onNodeWithText("Agent ID").assert(hasText("agt_7f3k29xq"))
        }

    @Test
    fun horizontalListLaysOutWithNoWidthLimit() =
        runComposeUiTest {
            setContent(
                themed {
                    Row(Modifier.width(300.dp).horizontalScroll(rememberScrollState())) {
                        RaftDescriptionList(direction = RaftDescriptionListDirection.Horizontal) {
                            Item("Workspace path", "/home/wug/work/a-very-long-path/that-does-not-fit", mono = true)
                        }
                    }
                },
            )
            onNodeWithText("Workspace path").assert(hasText("/home/wug/work/a-very-long-path/that-does-not-fit"))
        }

    @Test
    fun unreadCountSaysHowManyByDefault() =
        runComposeUiTest {
            setContent(themed { RaftUnreadCount(120) })
            onNodeWithContentDescription("120 unread").assertExists()
        }

    @Test
    fun optionCardIsOneRadioTarget() =
        runComposeUiTest {
            var picked by mutableStateOf(false)
            setContent(
                themed {
                    RaftCard(
                        variant = RaftCardVariant.Option,
                        title = "A cloud computer",
                        description = "Always on.",
                        onClick = { picked = true },
                        selected = picked,
                    )
                },
            )
            val card = onNodeWithText("A cloud computer")
            card.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            card.assert(hasText("Always on."))
            card.performClick()
            assertTrue(picked)
            card.assertIsSelected()
        }

    @Test
    fun aBusyButtonKeepsFocusButIgnoresTaps() =
        runComposeUiTest {
            var clicks = 0
            setContent(themed { RaftButton("Create", onClick = { clicks++ }, loading = true, loadingLabel = "Creating…") })
            val button = onNodeWithText("Create")
            button.assertIsNotEnabled()
            button.requestFocus()
            button.assertIsFocused()
            button.performClick()
            assertEquals(0, clicks)
        }

    @Test
    fun cardsAndRowsProvideTheirSurface() =
        runComposeUiTest {
            val dark = RaftUiThemeColors.RaftDark
            var canvas = Color.Unspecified
            var card = Color.Unspecified
            var row = Color.Unspecified
            setContent(
                themed {
                    canvas = LocalRaftSurfaceColor.current
                    RaftCard(title = "Card") { card = LocalRaftSurfaceColor.current }
                    RaftListRowGroup { RaftListRow("Row", leading = { row = LocalRaftSurfaceColor.current }) }
                },
            )
            waitForIdle()
            assertEquals(dark.layerCanvas, canvas)
            assertEquals(dark.layerCard, card)
            assertEquals(dark.layerCard, row)
        }

    /** Skeletons shown at different moments pulse in phase, from one loop that stops with the last. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun skeletonsPulseTogetherFromOneSharedLoop() {
        var second by mutableStateOf(false)
        var shown by mutableStateOf(true)
        var clock: RaftPulseClock? = null
        ImageComposeScene(width = 60, height = 20, density = Density(1f)) {
            RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                clock = LocalRaftPulseClock.current
                Row(Modifier.background(Color.White)) {
                    if (shown) RaftSkeleton(Modifier.size(20.dp))
                    Box(Modifier.size(20.dp))
                    if (shown && second) RaftSkeleton(Modifier.size(20.dp))
                }
            }
        }.use { scene ->
            scene.render(0L)
            scene.render(300_000_000L)
            second = true
            scene.render(400_000_000L)
            val image = Bitmap.makeFromImage(scene.render(700_000_000L))
            val first = image.getColor(10, 10)
            assertTrue(first != -1, "the skeleton is drawn")
            assertEquals(first, image.getColor(50, 10), "both skeletons at the same opacity")
            assertNotNull(clock)
            assertTrue(clock!!.running)
            shown = false
            scene.render(800_000_000L)
            assertFalse(clock!!.running, "the loop stops when no skeleton is shown")
        }
    }
}
