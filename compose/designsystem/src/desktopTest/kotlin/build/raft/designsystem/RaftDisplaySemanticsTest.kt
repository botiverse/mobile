package build.raft.designsystem

import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.withStyle
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
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import build.raft.designsystem.components.LocalRaftPulseClock
import build.raft.designsystem.components.LocalRaftTooltipTouchTimer
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftCard
import build.raft.designsystem.components.RaftCardVariant
import build.raft.designsystem.components.RaftDescriptionList
import build.raft.designsystem.components.RaftDescriptionListDirection
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftPulseClock
import build.raft.designsystem.components.RaftSidebarChannelItem
import build.raft.designsystem.components.RaftSidebarItem
import build.raft.designsystem.components.RaftSidebarItemVariant
import build.raft.designsystem.components.raftSidebarItemIconSize
import build.raft.designsystem.components.RaftSidebarSectionHeader
import build.raft.designsystem.components.RaftReadOnlyField
import build.raft.designsystem.components.RaftSkeleton
import build.raft.designsystem.components.RaftUnreadCount
import build.raft.designsystem.theme.LocalRaftSurfaceColor
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.theme.RaftTypography
import build.raft.designsystem.tokens.RaftUiThemeColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
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

    @Test
    fun aSidebarSectionHeaderExpandsAndCollapses() =
        runComposeUiTest {
            var expanded by mutableStateOf(true)
            setContent(themed { RaftSidebarSectionHeader("Channels", expanded = expanded, onExpandedChange = { expanded = it }, count = 2) })
            onNodeWithText("Channels").assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Collapse))
            onNodeWithText("Channels").performClick()
            assertFalse(expanded)
            onNodeWithText("Channels").assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand))
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

    /** A styled name keeps its spans: a Japanese name keeps `ja` for its glyph forms. */
    @Test
    fun aSidebarRowKeepsTheLocaleOfAStyledTitle() =
        runComposeUiTest {
            val ja = LocaleList("ja")
            val name = buildAnnotatedString { withStyle(SpanStyle(localeList = ja)) { append("直子") } }
            setContent(themed { RaftSidebarItem(name, onClick = {}) })
            val text = onNodeWithText("直子").fetchSemanticsNode().config[SemanticsProperties.Text].single()
            assertTrue(text.spanStyles.any { it.item.localeList == ja }, "spans: ${text.spanStyles}")
        }

    /** Home's entry rows: a 20dp icon 12dp from the name; a place row: 16dp in a 19dp slot, 8dp. */
    @Test
    fun aSidebarEntryRowHasA20dpIcon12dpFromItsName() =
        runComposeUiTest {
            setContent(
                themed {
                    Column(Modifier.width(320.dp)) {
                        RaftSidebarItem(
                            "Search",
                            onClick = {},
                            variant = RaftSidebarItemVariant.Entry,
                            leading = { Box(Modifier.size(raftSidebarItemIconSize()).testTag("entry-icon")) },
                        )
                        RaftSidebarItem(
                            "general",
                            onClick = {},
                            leading = { Box(Modifier.size(raftSidebarItemIconSize()).testTag("place-icon")) },
                        )
                    }
                },
            )
            val d = density.density
            val entryIcon = onNodeWithTag("entry-icon", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val entryTitle = onNodeWithText("Search", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(20f, entryIcon.width / d, 0.5f)
            assertEquals(12f, (entryTitle.left - entryIcon.right) / d, 0.5f)
            val placeIcon = onNodeWithTag("place-icon", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val placeTitle = onNodeWithText("general", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(16f, placeIcon.width / d, 0.5f)
            // 1.5dp of the 19dp slot on each side of the 16dp icon, then gap-2.
            assertEquals(9.5f, (placeTitle.left - placeIcon.right) / d, 0.5f)
        }

    /** A DM's aside parts stand 6dp apart (web `SIDEBAR_ROW_TRAILING_CLASS`), a place row's 4dp. */
    @Test
    fun aSidebarPeerRowSpacesItsAsideBy6dp() =
        runComposeUiTest {
            setContent(
                themed {
                    Column(Modifier.width(320.dp)) {
                        RaftSidebarItem("Bugen", onClick = {}, variant = RaftSidebarItemVariant.Peer, trailing = {
                            Box(Modifier.size(10.dp).testTag("peer-a"))
                            Box(Modifier.size(10.dp).testTag("peer-b"))
                        })
                        RaftSidebarItem("general", onClick = {}, trailing = {
                            Box(Modifier.size(10.dp).testTag("place-a"))
                            Box(Modifier.size(10.dp).testTag("place-b"))
                        })
                    }
                },
            )
            fun gap(a: String, b: String): Float {
                val left = onNodeWithTag(a, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val right = onNodeWithTag(b, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                return (right.left - left.right) / density.density
            }
            assertEquals(6f, gap("peer-a", "peer-b"), 0.5f)
            assertEquals(4f, gap("place-a", "place-b"), 0.5f)
        }

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
    fun aSidebarRowWithoutALongClickHasNoLongClickAction() =
        runComposeUiTest {
            setContent(themed { RaftSidebarChannelItem("general", onClick = {}) })
            onNodeWithText("general").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
        }

    @Test
    fun aLongPressOnASidebarSectionHeaderOpensItsMenuWithoutToggling() =
        runComposeUiTest {
            var expanded by mutableStateOf(true)
            var longClicks = 0
            setContent(
                themed {
                    RaftSidebarSectionHeader(
                        "Channels",
                        expanded = expanded,
                        onExpandedChange = { expanded = it },
                        onLongClick = { longClicks++ },
                        onLongClickLabel = "Channels Options",
                    )
                },
            )
            onNodeWithText("Channels").performTouchInput { longClick() }
            assertEquals(1, longClicks)
            assertTrue(expanded)
            val action = onNodeWithText("Channels").fetchSemanticsNode().config.getOrNull(SemanticsActions.OnLongClick)
            assertNotNull(action)
            assertEquals("Channels Options", action.label)
            onNodeWithText("Channels").assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Collapse))
            onNodeWithText("Channels").performClick()
            assertFalse(expanded)
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

    /**
     * The tooltip adds no screen-reader stop of its own: in the unmerged tree there is one
     * node that merges its descendants or takes focus, and it is the labelled button.
     */
    @Test
    fun anIconButtonWithItsTooltipIsOneFocusStop() =
        runComposeUiTest {
            setContent(themed { RaftIconButton(onClick = {}, tooltip = "Add Reaction", variant = RaftButtonVariant.Ghost) {} })
            val focusStop =
                SemanticsMatcher("merges descendants or is focusable") {
                    it.config.isMergingSemanticsOfDescendants || SemanticsProperties.Focused in it.config
                }
            val stops = onAllNodes(focusStop, useUnmergedTree = true).fetchSemanticsNodes()
            assertEquals(1, stops.size, "focus stops: ${stops.map { it.config }}")
            val stop = stops.single().config
            assertEquals(listOf("Add Reaction"), stop.getOrNull(SemanticsProperties.ContentDescription))
            assertEquals(Role.Button, stop.getOrNull(SemanticsProperties.Role))
            assertTrue(SemanticsProperties.Focused in stop, "the button itself is focusable")
        }

    /**
     * A mouse keeps the tooltip open while it hovers (no 1.5s timer, unlike Material) and
     * closes it on leaving; a long press's tooltip asks for the 1.5s timer and closes when
     * it runs out. The timer is a gate the test releases, so no wall-clock waits.
     */
    @Test
    fun aHoveredTooltipStaysOpenUntilThePointerLeaves() =
        runComposeUiTest {
            val timers = mutableListOf<Long>()
            val timeUp = CompletableDeferred<Unit>()
            setContent(
                themed {
                    CompositionLocalProvider(
                        LocalRaftTooltipTouchTimer provides { millis -> timers += millis; timeUp.await() },
                    ) {
                        Box(Modifier.size(200.dp)) {
                            RaftIconButton(onClick = {}, tooltip = "Add Reaction", variant = RaftButtonVariant.Ghost, modifier = Modifier.align(Alignment.Center)) {}
                        }
                    }
                },
            )
            mainClock.autoAdvance = false
            val button = onNodeWithContentDescription("Add Reaction")
            button.performMouseInput { moveTo(center) }
            mainClock.advanceTimeBy(3_000)
            onNode(hasText("Add Reaction")).assertExists()
            assertEquals(emptyList(), timers, "a hovered tooltip must not start the long-press timer")
            // Off the button, below it (the bubble is above).
            button.performMouseInput { moveTo(bottomRight + Offset(30f, 30f)) }
            mainClock.advanceTimeBy(1_000)
            onNode(hasText("Add Reaction")).assertDoesNotExist()

            button.performTouchInput { longClick() }
            mainClock.advanceTimeBy(300)
            onNode(hasText("Add Reaction")).assertExists()
            assertEquals(listOf(1_500L), timers, "a long press starts the 1.5s timer")
            timeUp.complete(Unit)
            mainClock.advanceTimeBy(1_000)
            onNode(hasText("Add Reaction")).assertDoesNotExist()
        }

    /** With its own long-press action, a long press runs it (not the click) and opens no tooltip. */
    @Test
    fun aLongPressActionTakesTheLongPressFromTheTooltip() =
        runComposeUiTest {
            var clicks = 0
            var longClicks = 0
            setContent(
                themed {
                    RaftIconButton(
                        onClick = { clicks++ },
                        tooltip = "Add Reaction",
                        variant = RaftButtonVariant.Ghost,
                        onLongClick = { longClicks++ },
                        longClickLabel = "Pick Any Emoji",
                    ) {}
                },
            )
            mainClock.autoAdvance = false
            val button = onNodeWithContentDescription("Add Reaction")
            button.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            assertEquals("Pick Any Emoji", button.fetchSemanticsNode().config[SemanticsActions.OnLongClick].label)
            button.performTouchInput { longClick() }
            mainClock.advanceTimeBy(300)
            onNode(hasText("Add Reaction")).assertDoesNotExist()
            assertEquals(1, longClicks)
            assertEquals(0, clicks)
            button.performClick()
            assertEquals(1, clicks)
        }

    /** Without a tooltip the button is named by its content description, and a long press shows nothing. */
    @Test
    fun anIconButtonWithoutATooltipIsNamedByItsContentDescription() =
        runComposeUiTest {
            var clicks = 0
            setContent(themed { RaftIconButton(onClick = { clicks++ }, tooltip = null, contentDescription = "Remove Attachment") {} })
            mainClock.autoAdvance = false
            val button = onNodeWithContentDescription("Remove Attachment")
            button.performTouchInput { longClick() }
            mainClock.advanceTimeBy(300)
            onNode(hasText("Remove Attachment")).assertDoesNotExist()
            // Without a tooltip or onLongClick, releasing a long press is an ordinary click.
            clicks = 0
            button.performClick()
            assertEquals(1, clicks)
        }

    /** A 24dp button takes a tap 8dp above its box (inside the 44dp target). */
    @Test
    fun aSmallIconButtonTakesTapsInItsWidenedTarget() =
        runComposeUiTest {
            var clicks = 0
            setContent(
                themed {
                    Column(Modifier.padding(20.dp)) {
                        RaftIconButton(onClick = { clicks++ }, tooltip = "Remove", size = RaftIconButtonSize.Xs) {}
                    }
                },
            )
            onNodeWithContentDescription("Remove").performTouchInput { click(Offset(centerX, -8.dp.toPx())) }
            assertEquals(1, clicks, "a tap 8dp above the button clicks it")
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
