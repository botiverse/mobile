package build.raft.designsystem.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftFocusedPreview
import build.raft.designsystem.components.LocalRaftPressedPreview
import build.raft.designsystem.components.RaftComposerAttachment
import build.raft.designsystem.components.RaftComposerAttachmentFile
import build.raft.designsystem.components.RaftComposerAttachmentGap
import build.raft.designsystem.components.RaftComposerAttachmentImage
import build.raft.designsystem.components.RaftComposerAttachmentRemove
import build.raft.designsystem.components.RaftComposerAttachmentRemoveOverhang
import build.raft.designsystem.components.RaftComposerAttachmentOverlay
import build.raft.designsystem.components.RaftComposerAttachmentPhase
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test

/**
 * raft-ui Composer > attachments: a file tile (short and truncated title) and an image tile at
 * rest; the web's overlays on both in every phase (checking, uploading at 42% and before the
 * first byte with the bar at its 4% floor, finishing, failed with the mobile Retry), spinners
 * held still; and the remove sticker at rest, pressed, focused and disabled.
 */
class ComposerAttachmentScreenshotTest {
    private val appearances = listOf(RaftAppearance.Light, RaftAppearance.Dark)

    private fun capture(name: String, content: @Composable () -> Unit) =
        appearances.forEach { appearance ->
            RaftScreenshot.capture("${name}_${appearance.name.lowercase()}", appearance, content = content)
        }

    @Test
    fun composerAttachment() =
        capture("composer_attachment") {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Caption("Rest: file, long file, image")
                Tray {
                    FileTile("notes.pdf", "application/pdf · 1.2 MB")
                    FileTile("release-notes-october-final-v2.pdf", "application/pdf · 1.2 MB")
                    ImageTile()
                }
                Caption("Checking: file, image")
                PhaseRow(RaftComposerAttachmentPhase.Checking, null, "Checking file…")
                Caption("Uploading: 42%, first byte")
                PhaseRow(RaftComposerAttachmentPhase.Uploading, 42, "42%")
                PhaseRow(RaftComposerAttachmentPhase.Uploading, 0, "Uploading")
                Caption("Finishing: file, image")
                PhaseRow(RaftComposerAttachmentPhase.Finishing, 99, "Finishing upload…")
                Caption("Failed: file, image")
                PhaseRow(RaftComposerAttachmentPhase.Failed, null, "Upload failed", action = "Retry")
                Caption("Remove: rest, pressed, focused, disabled")
                Tray {
                    listOf("rest", "pressed", "focused", "disabled").forEach { state ->
                        CompositionLocalProvider(
                            LocalRaftPressedPreview provides (state == "pressed"),
                            LocalRaftFocusedPreview provides (state == "focused"),
                        ) {
                            RaftComposerAttachment {
                                RaftComposerAttachmentImage { Placeholder() }
                                RaftComposerAttachmentRemove(onClick = {}, contentDescription = "Remove", enabled = state != "disabled")
                            }
                        }
                    }
                }
            }
        }

    @Composable
    private fun Tray(content: @Composable () -> Unit) {
        Row(
            Modifier.padding(top = RaftComposerAttachmentRemoveOverhang, end = RaftComposerAttachmentRemoveOverhang),
            horizontalArrangement = Arrangement.spacedBy(RaftComposerAttachmentGap + 8.dp),
        ) { content() }
    }

    @Composable
    private fun PhaseRow(phase: RaftComposerAttachmentPhase, percent: Int?, label: String, action: String? = null) {
        val overlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {
            RaftComposerAttachmentOverlay(
                phase = phase,
                percent = percent,
                label = label,
                action = action,
                onAction = action?.let { { } },
                spinnerStillStep = 0,
            )
        }
        Tray {
            FileTile("ScreenRecording_10-24.mov", "video · 31 MB", overlay)
            ImageTile(placeholder = false, overlay = overlay)
        }
    }

    @Composable
    private fun FileTile(
        title: String,
        meta: String,
        overlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
    ) {
        RaftComposerAttachment {
            RaftComposerAttachmentFile(title = title, meta = meta, onClick = {}) { RaftGlyphIcon(RaftGlyph.Copy, size = 16.dp) }
            overlay()
            RaftComposerAttachmentRemove(onClick = {}, contentDescription = "Remove $title")
        }
    }

    @Composable
    private fun ImageTile(
        placeholder: Boolean = true,
        overlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
    ) {
        RaftComposerAttachment {
            RaftComposerAttachmentImage(onClick = {}) { if (placeholder) Placeholder() }
            overlay()
            RaftComposerAttachmentRemove(onClick = {}, contentDescription = "Remove image")
        }
    }

    @Composable
    private fun Placeholder() {
        RaftGlyphIcon(RaftGlyph.Search, size = 20.dp)
    }

    @Composable
    private fun Caption(text: String) {
        RaftText(text = text, style = RaftTheme.typography.monoMeta, color = RaftTheme.colors.foregroundHint)
    }
}
