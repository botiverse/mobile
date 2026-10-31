package build.raft.designsystem.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftPressedPreview
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftFilePreview
import build.raft.designsystem.components.RaftFilePreviewKind
import build.raft.designsystem.components.RaftMessageEmbed
import build.raft.designsystem.components.RaftMessageEmbedFooter
import build.raft.designsystem.components.RaftMessageEmbedHeader
import build.raft.designsystem.components.raftGrayscale
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test

/**
 * raft-ui MessageEmbed and FilePreview as the web's Artifact card composes them
 * (`ArtifactMessageCard.tsx`):
 *  - `message_embed`: the file glyphs (document, code, media; with and without a corner badge),
 *    a display card, an interactive one pressed, and a dimmed (deleted) one, at a phone's width.
 */
class MessageEmbedScreenshotTest {
    private val appearances = listOf(RaftAppearance.Light, RaftAppearance.Dark)

    @Test
    fun messageEmbed() =
        appearances.forEach { appearance ->
            RaftScreenshot.capture("message_embed_${appearance.name.lowercase()}", appearance) {
                Column(Modifier.width(380.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        RaftFilePreview(RaftFilePreviewKind.Document)
                        RaftFilePreview(RaftFilePreviewKind.Document, badge = "PDF")
                        RaftFilePreview(RaftFilePreviewKind.Code, badge = "HTML")
                        RaftFilePreview(RaftFilePreviewKind.Media, badge = "IMG")
                        RaftFilePreview(RaftFilePreviewKind.Document, badge = "MD")
                    }
                    Card(title = "Q3 revenue report", kind = RaftFilePreviewKind.Code, badge = "HTML", onClick = {})
                    CompositionLocalProvider(LocalRaftPressedPreview provides true) {
                        Card(title = "Onboarding checklist", kind = RaftFilePreviewKind.Document, badge = "PDF", onClick = {})
                    }
                    RaftMessageEmbed(Modifier.raftGrayscale()) {
                        RaftMessageEmbedHeader {
                            RaftFilePreview(RaftFilePreviewKind.Document)
                            Column(Modifier.weight(1f)) {
                                RaftText("Launch plan draft", style = RaftTheme.typography.subhead.copy(fontWeight = FontWeight.Bold))
                                RaftText("Deleted by Martin yesterday", style = RaftTheme.typography.footnote, color = RaftTheme.colors.foregroundMuted)
                            }
                        }
                    }
                }
            }
        }

    @Composable
    private fun Card(title: String, kind: RaftFilePreviewKind, badge: String, onClick: (() -> Unit)?) {
        RaftMessageEmbed(onClick = onClick) {
            RaftMessageEmbedHeader {
                RaftFilePreview(kind, badge = badge)
                Column(Modifier.weight(1f)) {
                    RaftText(title, style = RaftTheme.typography.subhead.copy(fontWeight = FontWeight.Bold))
                    RaftText("6 pages", style = RaftTheme.typography.footnote, color = RaftTheme.colors.foregroundMuted)
                }
            }
            RaftMessageEmbedFooter {
                RaftBadge("proj-workspace", appearance = RaftBadgeAppearance.Outline, variant = RaftBadgeVariant.Muted)
                RaftText("Lagoon updated 2 hours ago", style = RaftTheme.typography.monoMeta, color = RaftTheme.colors.foregroundHint)
            }
        }
    }
}
