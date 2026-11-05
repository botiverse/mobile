package build.raft.designsystem.screenshot

import androidx.compose.foundation.background
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
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test

/**
 * [RaftEmptyState] (raft-ui EmptyState), light and dark: icon, title, description and an action;
 * title and description; the title alone; a long description cut at three lines; and one
 * centred in a taller box (`justify-center`).
 */
class EmptyStateScreenshotTest {
    @Test
    fun emptyState() =
        listOf(RaftAppearance.Light, RaftAppearance.Dark).forEach { appearance ->
            RaftScreenshot.capture("empty_state_${appearance.name.lowercase()}", appearance) {
                Column(Modifier.width(358.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Caption("Icon, title, description, action")
                    Surface {
                        RaftEmptyState(
                            title = "No saved messages",
                            description = "Save a message to find it here later.",
                            icon = { RaftGlyphIcon(RaftGlyph.Bookmark, size = 36.dp) },
                            actions = { RaftButton(text = "Browse channels", onClick = {}) },
                        )
                    }
                    Caption("Title and description")
                    Surface {
                        RaftEmptyState(title = "No results", description = "Try another word.")
                    }
                    Caption("Title only")
                    Surface { RaftEmptyState(title = "Nothing here yet") }
                    Caption("Long text: title one line, description three")
                    Surface {
                        RaftEmptyState(
                            title = "No agents have been added to this workspace so far",
                            description =
                                "Agents you add show up here with their status, their computer and the channels " +
                                    "they work in. Add one from the members page, or ask an admin to add one for you.",
                            icon = { RaftGlyphIcon(RaftGlyph.Search, size = 36.dp) },
                        )
                    }
                    Caption("Centred in a 320dp box")
                    Surface {
                        RaftEmptyState(
                            title = "No tasks",
                            description = "Tasks you create show up here.",
                            modifier = Modifier.height(320.dp),
                            icon = { RaftGlyphIcon(RaftGlyph.CircleCheck, size = 36.dp) },
                        )
                    }
                }
            }
        }

    @Composable
    private fun Surface(content: @Composable () -> Unit) {
        Column(Modifier.background(RaftTheme.colors.layerCanvas)) { content() }
    }

    @Composable
    private fun Caption(text: String) {
        RaftText(text = text, style = RaftTheme.typography.monoMeta, color = RaftTheme.colors.foregroundHint)
    }
}
