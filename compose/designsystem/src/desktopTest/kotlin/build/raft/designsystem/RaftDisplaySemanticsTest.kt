package build.raft.designsystem

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftWorkspaceButton
import build.raft.designsystem.components.RaftWorkspaceButtonDefaults
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftDescriptionList
import build.raft.designsystem.components.RaftDescriptionListDirection
import build.raft.designsystem.components.RaftSidebarChannelItem
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftSidebarSectionAction
import build.raft.designsystem.components.RaftReadOnlyField
import build.raft.designsystem.theme.LocalRaftMisuse
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.theme.RaftTypography
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** What screen readers and keyboards get from the display components. */
@OptIn(ExperimentalTestApi::class)
class RaftDisplaySemanticsTest {
    private fun themed(content: @Composable () -> Unit): @Composable () -> Unit = {
        RaftTheme(appearance = RaftAppearance.Dark, systemDark = false, content = content)
    }

    @Test
    fun aSidebarRowIsAButtonAndSaysWhereYouAre() =
        runComposeUiTest {
            setContent(
                themed {
                    Column {
                        RaftSidebarChannelItem("general", onClick = {}, selected = true)
                        RaftSidebarChannelItem("random", onClick = {}, unreadCount = 3, threadCount = 2)
                    }
                },
            )
            onNodeWithText("general").assertIsSelected()
            onNodeWithText("general").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            onNodeWithText("random").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
            onNodeWithText("random").assert(hasContentDescription("3 unread, 2 threads with new replies"))
        }

    /** Press and hold opens the row's menu (raft-design ContextMenu) and does not also open the row. */
    @Test
    fun aLongPressOnASidebarRowOpensItsMenuWithoutClicking() =
        runComposeUiTest {
            var clicks = 0
            var longClicks = 0
            setContent(
                themed {
                    RaftSidebarChannelItem(
                        "general",
                        onClick = { clicks++ },
                        onLongClick = { longClicks++ },
                        onLongClickLabel = "Channel Options",
                    )
                },
            )
            onNodeWithText("general").performTouchInput { longClick() }
            assertEquals(1, longClicks)
            assertEquals(0, clicks)
            onNodeWithText("general").performClick()
            assertEquals(1, clicks)
            assertEquals(1, longClicks)
            val action = onNodeWithText("general").fetchSemanticsNode().config.getOrNull(SemanticsActions.OnLongClick)
            assertNotNull(action)
            assertEquals("Channel Options", action.label)
        }

    /** The mark after a long name stays whole and inside the row; the name truncates. */
    @Test
    fun aSidebarRowKeepsItsTitleTrailingWhenTheNameTruncates() =
        runComposeUiTest {
            setContent(
                themed {
                    Box(Modifier.width(240.dp)) {
                        RaftSidebarChannelItem(
                            "a-channel-name-long-enough-to-run-past-the-end-of-the-row",
                            onClick = {},
                            unreadCount = 3,
                            titleTrailing = { Box(Modifier.size(16.dp).testTag("mark")) },
                        )
                    }
                },
            )
            val mark = onNodeWithTag("mark", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(16f, mark.width / density.density, 0.5f)
            assertTrue(mark.right / density.density < 240f, "mark ends at ${mark.right / density.density}dp")
        }

    private fun androidx.compose.ui.test.ComposeUiTest.boundsOf(tag: String) =
        onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun androidx.compose.ui.test.ComposeUiTest.textBounds(text: String) =
        onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    /** The app's UI language reaches every type style; by default there is none. */
    @Test
    fun theThemePutsTheUiLanguageOnEveryTypeStyle() =
        runComposeUiTest {
            val ko = LocaleList("ko")
            var themed: RaftTypography? = null
            var plain: RaftTypography? = null
            setContent {
                Column {
                    RaftTheme(appearance = RaftAppearance.Light, systemDark = false, localeList = ko) { themed = RaftTheme.typography }
                    RaftTheme(appearance = RaftAppearance.Light, systemDark = false) { plain = RaftTheme.typography }
                }
            }
            waitForIdle()
            val withLocale = assertNotNull(themed)
            val without = assertNotNull(plain)
            listOf(withLocale.body, withLocale.footnote, withLocale.headline, withLocale.monoMeta, withLocale.field).forEach {
                assertEquals(ko, it.localeList)
            }
            assertEquals(null, without.body.localeList)
            assertEquals(null, without.monoMeta.localeList)
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

    /** The tooltip names an icon button for screen readers; a tap clicks it. */
    @Test
    fun anIconButtonIsNamedByItsTooltip() =
        runComposeUiTest {
            var clicks = 0
            setContent(themed { RaftIconButton(onClick = { clicks++ }, tooltip = "Add Reaction", variant = RaftButtonVariant.Ghost) {} })
            // The bubble is not there until a long press or a hover.
            onNode(hasText("Add Reaction")).assertDoesNotExist()
            onNodeWithContentDescription("Add Reaction")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .performClick()
            assertEquals(1, clicks)
        }

    /** A long press opens the tooltip bubble and does not also click. */
    @Test
    fun aLongPressOnAnIconButtonShowsItsTooltipWithoutClicking() =
        runComposeUiTest {
            var clicks = 0
            setContent(themed { RaftIconButton(onClick = { clicks++ }, tooltip = "Add Reaction", variant = RaftButtonVariant.Ghost) {} })
            mainClock.autoAdvance = false
            onNodeWithContentDescription("Add Reaction").performTouchInput { longClick() }
            mainClock.advanceTimeBy(300)
            onNode(hasText("Add Reaction")).assertExists()
            assertEquals(0, clicks)
        }

    /** A nameless icon button doesn't crash: it tells [LocalRaftMisuse], silent unless provided. */
    @Test
    fun aNamelessIconButtonReportsMisuseInsteadOfCrashing() =
        runComposeUiTest {
            val reports = mutableListOf<String>()
            setContent(
                themed {
                    Column {
                        RaftIconButton(onClick = {}, tooltip = null, modifier = Modifier.testTag("silent")) {}
                        CompositionLocalProvider(LocalRaftMisuse provides { reports += it }) {
                            RaftIconButton(onClick = {}, tooltip = null, modifier = Modifier.testTag("reported")) {}
                        }
                    }
                },
            )
            onNodeWithTag("silent").assertExists()
            onNodeWithTag("reported").assertExists()
            assertEquals(listOf("RaftIconButton needs a name: pass tooltip or contentDescription"), reports.distinct())
        }

    /** The sidebar section control's 24dp button takes a tap 6dp above its box (inside the 44dp target). */
    @Test
    fun aSmallIconButtonTakesTapsInItsWidenedTarget() =
        runComposeUiTest {
            var clicks = 0
            setContent(
                themed {
                    Column(Modifier.padding(20.dp)) {
                        RaftSidebarSectionAction(RaftGlyph.Plus, tooltip = "Create Channel", onClick = { clicks++ })
                    }
                },
            )
            onNodeWithContentDescription("Create Channel").performTouchInput { click(Offset(centerX, -6.dp.toPx())) }
            assertEquals(1, clicks, "a tap 6dp above the button clicks it")
        }

    /** A read-only value is one 44dp line unless it may wrap; then the box grows by its lines. */
    @Test
    fun aMultiLineReadOnlyFieldGrowsWithItsLines() =
        runComposeUiTest {
            val long = "Reviews pull requests in #design and posts a summary of every change that touches the tokens."
            setContent(
                themed {
                    Column(Modifier.width(200.dp)) {
                        RaftReadOnlyField(long, Modifier.testTag("one"))
                        RaftReadOnlyField(long, Modifier.testTag("wrapped"), maxLines = Int.MAX_VALUE)
                    }
                },
            )
            fun height(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.height / density.density
            assertEquals(44f, height("one"), 0.5f)
            assertTrue(height("wrapped") >= 44f + 20f * 2, "wrapped is ${height("wrapped")}dp")
        }

    private fun androidx.compose.ui.test.ComposeUiTest.setWorkspaceButtonContent(name: String, width: Int = 360) {
        setContent(
            themed {
                Row(Modifier.width(width.dp)) {
                    RaftWorkspaceButton(
                        name = name,
                        onClick = {},
                        modifier = Modifier.testTag("button"),
                        avatar = { Box(Modifier.size(RaftWorkspaceButtonDefaults.AvatarSize).testTag("avatar")) },
                    )
                }
            },
        )
    }

    /** A long name stops at 200dp; the chevron keeps its room. */
    @Test
    fun aWorkspaceButtonCapsItsNameAt200dpAndKeepsTheChevron() =
        runComposeUiTest {
            val long = "The Long Name Of A Server That Does Not Fit Anywhere"
            setWorkspaceButtonContent(long)
            val d = density.density
            assertEquals(200f, textBounds(long).width / d, 0.5f)
            assertEquals(31.5f, (boundsOf("button").right - textBounds(long).right) / d, 0.5f)
        }
}
