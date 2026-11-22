package build.raft.designsystem.vcase

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftFitToolbar
import build.raft.designsystem.components.RaftFitToolbarAction
import build.raft.designsystem.components.RaftFitToolbarChoice
import build.raft.designsystem.components.RaftFitToolbarOption
import build.raft.designsystem.components.RaftFitToolbarPrimary
import build.raft.designsystem.components.RaftFitToolbarScope
import build.raft.designsystem.components.RaftFitToolbarSearch
import build.raft.designsystem.components.RaftFitToolbarView
import build.raft.designsystem.components.RaftFitToolbarViewOption
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import dev.vcase.VCase

// [RaftFitToolbar] (raft-ui FitToolbar, raft-design responsive.md › The toolbar over a list)
// at 1024, 600 and 375dp, then at 375dp with text in its search.
// These replace FitToolbarScreenshotTest (fit_toolbar_members, fit_toolbar_members_fine,
// fit_toolbar_filters and fit_toolbar_filters_end_view, each _light/_dark).

/**
 * The Members toolbar on its Agents tab (scope with counts, search, Group, Invite human, More,
 * Create agent ▾), with a finger: at 1024 every label; at 600 Group and Invite as icons and
 * Create agent as +; at 375 the scope a Select and Group and Invite in ⋯ More. A typed search
 * keeps its field.
 */
@VCase("ds.fit-toolbar.members")
@Composable
fun FitToolbarMembers() {
    Widths { typed -> MembersToolbar(typed) }
}

/** [FitToolbarMembers] with a mouse on the wide scale (34dp controls, a 44dp line). */
@VCase("ds.fit-toolbar.members-fine", viewport = ["wide"], pointer = ["fine"])
@Composable
fun FitToolbarMembersFine() {
    Widths { typed -> MembersToolbar(typed) }
}

/**
 * A list toolbar with `wrap` (scope, search, three filters, Sort, New task): the filters go
 * icon-only, then move to a scrolling second line with their labels.
 */
@VCase("ds.fit-toolbar.filters")
@Composable
fun FitToolbarFilters() {
    Widths { typed -> FiltersToolbar(typed) }
}

/** The toolbar at each width, then at 375dp with text in its search. */
@Composable
private fun Widths(toolbar: @Composable (typed: Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(1024, 600, 375).forEach { width ->
            RaftText("${width}dp", style = RaftTheme.typography.caption, color = RaftTheme.colors.foregroundHint)
            Box(Modifier.width(width.dp).background(RaftTheme.colors.layerCanvas)) { toolbar(false) }
        }
        RaftText("375dp, a typed search", style = RaftTheme.typography.caption, color = RaftTheme.colors.foregroundHint)
        Box(Modifier.width(375.dp).background(RaftTheme.colors.layerCanvas)) { toolbar(true) }
    }
}

@Composable
private fun MembersToolbar(typed: Boolean = false) {
    RaftFitToolbar(
        scope =
            RaftFitToolbarScope(
                options =
                    listOf(
                        RaftFitToolbarOption("all", "All", 17),
                        RaftFitToolbarOption("humans", "Humans", 9),
                        RaftFitToolbarOption("agents", "Agents", 8),
                    ),
                selectedKey = "agents",
                onSelect = {},
                label = "Show",
            ),
        search = RaftFitToolbarSearch(value = if (typed) "vol" else "", onValueChange = {}, label = "Search members", clearLabel = "Clear search"),
        display =
            listOf(
                RaftFitToolbarChoice(
                    text = "No grouping",
                    icon = { RaftGlyphIcon(RaftGlyph.Cpu, size = 14.dp) },
                    options = listOf(RaftFitToolbarOption("none", "No grouping"), RaftFitToolbarOption("computer", "By computer")),
                    selectedKey = "none",
                    onSelect = {},
                    heading = "Group",
                    contentDescription = "Group agents: No grouping",
                ),
            ),
        secondary = listOf(RaftFitToolbarAction("Invite human", icon = { RaftGlyphIcon(RaftGlyph.AtSign, size = 18.dp) }, onClick = {})),
        primary =
            RaftFitToolbarPrimary(
                text = "Create agent",
                onClick = {},
                icon = { RaftGlyphIcon(RaftGlyph.Zap, size = 16.dp) },
                menuLabel = "More ways to add an agent",
                menu = { dismiss -> RaftMenuItem("Connect an external agent", onClick = dismiss) },
            ),
        moreLabel = "More",
    )
}

@Composable
private fun FiltersToolbar(typed: Boolean = false) {
    fun filter(text: String, glyph: RaftGlyph, applied: Boolean = false) =
        RaftFitToolbarChoice(
            text = text,
            icon = { RaftGlyphIcon(glyph, size = 14.dp) },
            options = listOf(RaftFitToolbarOption("any", "Any")),
            selectedKey = "any",
            onSelect = {},
            applied = applied,
        )
    RaftFitToolbar(
        scope =
            RaftFitToolbarScope(
                options = listOf(RaftFitToolbarOption("mine", "Mine", 4), RaftFitToolbarOption("all", "All", 31)),
                selectedKey = "mine",
                onSelect = {},
                label = "Show",
            ),
        search = RaftFitToolbarSearch(value = if (typed) "release" else "", onValueChange = {}, label = "Search tasks", clearLabel = "Clear search"),
        filters =
            listOf(
                filter("Status: In progress", RaftGlyph.CircleCheck, applied = true),
                filter("Assignee", RaftGlyph.AtSign),
                filter("Channel", RaftGlyph.Hash),
            ),
        display =
            listOf(
                RaftFitToolbarChoice(
                    text = "Newest",
                    icon = { RaftGlyphIcon(RaftGlyph.ArrowDownUp, size = 14.dp) },
                    options = listOf(RaftFitToolbarOption("newest", "Newest"), RaftFitToolbarOption("oldest", "Oldest")),
                    selectedKey = "newest",
                    onSelect = {},
                    heading = "Sort",
                    contentDescription = "Sort: Newest",
                ),
            ),
        primary = RaftFitToolbarPrimary(text = "New task", onClick = {}, icon = { RaftGlyphIcon(RaftGlyph.Plus, size = 16.dp) }),
        moreLabel = "More",
        wrap = true,
    )
}

@Composable
private fun FiltersEndViewToolbar(typed: Boolean = false) {
    fun filter(text: String, glyph: RaftGlyph, applied: Boolean = false) =
        RaftFitToolbarChoice(
            text = text,
            icon = { RaftGlyphIcon(glyph, size = 14.dp) },
            options = listOf(RaftFitToolbarOption("any", "Any")),
            selectedKey = "any",
            onSelect = {},
            applied = applied,
        )
    RaftFitToolbar(
        search = RaftFitToolbarSearch(value = if (typed) "gpu" else "", onValueChange = {}, label = "Search computers", clearLabel = "Clear search"),
        filters = listOf(filter("Online", RaftGlyph.CircleCheck, applied = true), filter("Runtime", RaftGlyph.Cpu)),
        filtersEnd = RaftFitToolbarAction("Clear all", icon = { RaftGlyphIcon(RaftGlyph.X, size = 16.dp) }, onClick = {}),
        display =
            listOf(
                RaftFitToolbarChoice(
                    text = "Sort: Name",
                    icon = { RaftGlyphIcon(RaftGlyph.ArrowDownUp, size = 14.dp) },
                    options = listOf(RaftFitToolbarOption("name", "Name"), RaftFitToolbarOption("memory", "Memory")),
                    selectedKey = "name",
                    onSelect = {},
                    heading = "Sort",
                    contentDescription = "Sort: Name, A to Z",
                ),
            ),
        view =
            RaftFitToolbarView(
                options =
                    listOf(
                        RaftFitToolbarViewOption("cards", "Cards") { RaftGlyphIcon(RaftGlyph.Cpu, size = 16.dp) },
                        RaftFitToolbarViewOption("list", "List") { RaftGlyphIcon(RaftGlyph.Thread, size = 16.dp) },
                    ),
                selectedKey = "cards",
                onSelect = {},
                label = "Computers view",
                heading = "View as",
            ),
        primary = RaftFitToolbarPrimary(text = "Add computer", onClick = {}, icon = { RaftGlyphIcon(RaftGlyph.Plus, size = 16.dp) }),
        moreLabel = "More",
        wrap = true,
    )
}
