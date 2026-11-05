package build.raft.designsystem.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftMessageAttachmentAction
import build.raft.designsystem.components.RaftMessageAttachmentCard
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import kotlin.test.Test

/**
 * [RaftMessageAttachmentCard] (raft-ui MessageAttachmentCard), light and dark: a file with its
 * type chip, size and one action; with a comment count; a long name and kind (both truncate,
 * the size keeps its width); opening (the spinner in the actions' place); failed (the
 * `danger-strong` edge); two actions.
 */
class MessageAttachmentCardScreenshotTest {
    @Test
    fun messageAttachmentCard() =
        listOf(RaftAppearance.Light, RaftAppearance.Dark).forEach { appearance ->
            RaftScreenshot.capture("message_attachment_card_${appearance.name.lowercase()}", appearance) {
                Column(
                    Modifier.background(RaftTheme.colors.layerCanvas).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Labeled("Default") {
                            RaftMessageAttachmentCard(
                                title = "report.pdf",
                                meta = "application/pdf",
                                metaSize = "2.4 MB",
                                onClick = {},
                                typeBadge = { TypeChip("PDF") },
                            ) { Action() }
                        }
                        Labeled("Comments") {
                            RaftMessageAttachmentCard(
                                title = "brief.md",
                                meta = "text/markdown",
                                metaSize = "8 KB",
                                commentCount = 3,
                                onClick = {},
                                typeBadge = { TypeChip("MD") },
                            ) { Action() }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Labeled("Long name") {
                            RaftMessageAttachmentCard(
                                title = "quarterly-planning-notes-final-v3.docx",
                                meta = "application/vnd.openxmlformats",
                                metaSize = "120 KB",
                                onClick = {},
                            ) { Action() }
                        }
                        Labeled("Opening") {
                            RaftMessageAttachmentCard(
                                title = "report.pdf",
                                meta = "Downloading · 64%",
                                loading = true,
                                onClick = {},
                                typeBadge = { TypeChip("PDF") },
                            ) { Action() }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Labeled("Failed") {
                            RaftMessageAttachmentCard(
                                title = "report.pdf",
                                meta = "Couldn't open",
                                failed = true,
                                onClick = {},
                                typeBadge = { TypeChip("PDF") },
                            ) { Action() }
                        }
                        Labeled("Two actions") {
                            RaftMessageAttachmentCard(
                                title = "index.html",
                                meta = "text/html",
                                metaSize = "4 KB",
                                onClick = {},
                            ) {
                                Action(RaftGlyph.Copy)
                                Action()
                            }
                        }
                    }
                }
            }
        }

    @Composable
    private fun Action(glyph: RaftGlyph = RaftGlyph.ArrowDown) {
        RaftMessageAttachmentAction(onClick = {}, contentDescription = "Download") {
            RaftGlyphIcon(glyph, size = 16.dp)
        }
    }

    /** A stand-in for the app's file-type chip (the slot's content is the app's). */
    @Composable
    private fun TypeChip(text: String) {
        Box(
            Modifier.size(width = 28.dp, height = 14.dp).background(RaftTheme.colors.fillMuted, RoundedCornerShape(RaftRadius.Sticker)),
            contentAlignment = Alignment.Center,
        ) {
            RaftText(text, style = RaftTheme.typography.monoMeta, color = RaftTheme.colors.foregroundMuted, maxLines = 1)
        }
    }

    @Composable
    private fun Labeled(text: String, content: @Composable () -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RaftText(text = text, style = RaftTheme.typography.monoMeta, color = RaftTheme.colors.foregroundHint)
            content()
        }
    }
}
