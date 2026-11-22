package build.raft.designsystem.vcase

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftKbd
import build.raft.designsystem.components.RaftKeyGlyph
import build.raft.designsystem.components.RaftSearchPanel
import build.raft.designsystem.components.RaftSearchPanelFooter
import build.raft.designsystem.components.RaftSearchResultDescription
import build.raft.designsystem.components.RaftSearchResultIcon
import build.raft.designsystem.components.RaftSearchResultRow
import build.raft.designsystem.components.RaftSearchResultTitle
import build.raft.designsystem.components.RaftSearchSectionHeader
import build.raft.designsystem.components.RaftSearchShowAll
import build.raft.designsystem.components.RaftSearchTrigger
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.RaftTextFieldClearAction
import build.raft.designsystem.components.raftSearchPanelKeyHints
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import dev.vcase.VCase

// The web's desktop search panel ([RaftSearchPanel], web `SearchOverlay`) over a page: with
// nothing typed and nothing recent (the "Search everything" stand-in), with recent
// conversations, and with results (the "Search for" row selected by the keyboard, entities with
// Show All, messages); and the key hints ([RaftKbd] ↑ ↓ ↵ Esc ⌘K Ctrl+K, the footer, the top
// bar's trigger with its shortcut).
// These replace SearchPanelScreenshotTest (search_panel_empty, search_panel_recent,
// search_panel_results, key_hints, each _light/_dark).

@VCase("ds.search-panel.empty")
@Composable
fun SearchPanelEmpty() {
    Panel(query = "") {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RaftGlyphIcon(RaftGlyph.Search, size = 36.dp, color = RaftTheme.colors.foregroundHint)
            RaftText("Search everything", style = RaftTheme.typography.headline, color = RaftTheme.colors.foregroundMuted)
            RaftText(
                "Search channels, DMs, people, agents, and message history.",
                style = RaftTheme.typography.callout,
                color = RaftTheme.colors.foregroundHint,
            )
        }
    }
}

@VCase("ds.search-panel.recent")
@Composable
fun SearchPanelRecent() {
    Panel(query = "") {
        RaftSearchSectionHeader("Recent conversations", icon = RaftGlyph.Clock)
        Entity("design", "Channel", RaftGlyph.Hash, selected = true)
        Entity("android-artifacts", "Channel", RaftGlyph.Hash)
        Entity("artin", "Direct message", RaftGlyph.AtSign)
    }
}

@VCase("ds.search-panel.results")
@Composable
fun SearchPanelResults() {
    Panel(query = "android", filters = true) {
        RaftSearchResultRow(
            onClick = {},
            selected = true,
            leading = { RaftSearchResultIcon(RaftGlyph.Search) },
            description = { RaftSearchResultDescription("Open the full results page with filters and context preview") },
            trailing = { RaftKbd(RaftKeyGlyph.Return) },
        ) { RaftSearchResultTitle("Search for “android”") }
        RaftSearchSectionHeader("Server entities", count = 7)
        Entity("android-artifacts", "Channel", RaftGlyph.Hash, badge = "Channel")
        Entity("Android Developer 4", "@Android-Developer-4", RaftGlyph.AtSign, badge = "Agent")
        RaftSearchShowAll("Show All 7", onClick = {})
        RaftSearchSectionHeader("Messages", count = 2)
        Entity("Cindy", "Captured the Android visual artifact for review.", RaftGlyph.AtSign)
    }
}

@VCase("ds.key-hints.gallery")
@Composable
fun KeyHintsGallery() {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(
                RaftKeyGlyph.ArrowUp,
                RaftKeyGlyph.ArrowDown,
                RaftKeyGlyph.Return,
                RaftKeyGlyph.Escape,
                RaftKeyGlyph.searchShortcut(apple = true),
                RaftKeyGlyph.searchShortcut(apple = false),
            ).forEach { RaftKbd(it) }
        }
        RaftSearchPanelFooter(raftSearchPanelKeyHints(select = "Select", open = "Open"))
        RaftSearchTrigger(
            label = "Search",
            onClick = {},
            windowWidth = 1200.dp,
            shortcut = RaftKeyGlyph.searchShortcut(apple = true),
        )
        RaftSearchTrigger(
            label = "Search",
            onClick = {},
            windowWidth = 1200.dp,
            shortcut = RaftKeyGlyph.searchShortcut(apple = false),
        )
    }
}

@Composable
private fun Panel(
    query: String,
    filters: Boolean = false,
    width: Dp = 960.dp,
    height: Dp = 820.dp,
    body: @Composable () -> Unit,
) {
    Box(Modifier.size(width, height)) {
        // The page behind: a stand-in title the scrim dims.
        RaftText("# design", style = RaftTheme.typography.headline, modifier = Modifier.padding(24.dp))
        RaftSearchPanel(
            onDismissRequest = {},
            anchorTop = 8.dp,
            head = {
                RaftTextField(
                    value = query,
                    onValueChange = {},
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    placeholder = "Channels, people, messages…",
                    leading = { RaftGlyphIcon(RaftGlyph.Search, size = 18.dp, color = RaftTheme.colors.foregroundHint) },
                    trailing = { RaftKbd(RaftKeyGlyph.Escape) },
                    trailingAction = if (query.isNotEmpty()) ({ RaftTextFieldClearAction(onClear = {}) }) else null,
                )
            },
            filters =
                if (filters) {
                    {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf("From", "Scope", "Channel", "Any Time", "Relevant").forEach {
                                RaftBadge(it, appearance = RaftBadgeAppearance.Outline)
                            }
                        }
                    }
                } else {
                    null
                },
            footer = { RaftSearchPanelFooter(raftSearchPanelKeyHints(select = "Select", open = "Open")) },
        ) {
            Column(Modifier.fillMaxSize().padding(vertical = 8.dp)) { body() }
        }
    }
}

@Composable
private fun Entity(title: String, description: String, glyph: RaftGlyph, selected: Boolean = false, badge: String? = null) {
    RaftSearchResultRow(
        onClick = {},
        selected = selected,
        leading = { RaftSearchResultIcon(glyph) },
        badge = badge?.let { { RaftBadge(it, appearance = RaftBadgeAppearance.Outline) } },
        description = { RaftSearchResultDescription(description) },
    ) { RaftSearchResultTitle(title) }
}
