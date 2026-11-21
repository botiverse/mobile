package build.raft.designsystem.vcase

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftEmptyState
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import dev.vcase.VCase

/**
 * [RaftEmptyState] (raft-ui EmptyState): icon, title, description and an action; title and
 * description; the title alone; a long title and description, both wrapping in full; and one
 * centred in a taller box (`justify-center`).
 */
@VCase("ds.empty-state.gallery")
@Composable
fun EmptyStateGallery() {
    Column(Modifier.width(358.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Caption("Icon, title, description, action")
        CanvasSurface {
            RaftEmptyState(
                title = "No saved messages",
                description = "Save a message to find it here later.",
                icon = { RaftGlyphIcon(RaftGlyph.Bookmark, size = 36.dp) },
                actions = { RaftButton(text = "Browse channels", onClick = {}) },
            )
        }
        Caption("Title and description")
        CanvasSurface {
            RaftEmptyState(title = "No results", description = "Try another word.")
        }
        Caption("Title only")
        CanvasSurface { RaftEmptyState(title = "Nothing here yet") }
        Caption("Long text: title and description wrap")
        CanvasSurface {
            RaftEmptyState(
                title = "No agents have been added to this workspace so far",
                description =
                    "Agents you add show up here with their status, their computer and the channels " +
                        "they work in. Add one from the members page, or ask an admin to add one for you.",
                icon = { RaftGlyphIcon(RaftGlyph.Search, size = 36.dp) },
            )
        }
        Caption("Centred in a 320dp box")
        CanvasSurface {
            RaftEmptyState(
                title = "No tasks",
                description = "Tasks you create show up here.",
                modifier = Modifier.height(320.dp),
                icon = { RaftGlyphIcon(RaftGlyph.CircleCheck, size = 36.dp) },
            )
        }
    }
}
