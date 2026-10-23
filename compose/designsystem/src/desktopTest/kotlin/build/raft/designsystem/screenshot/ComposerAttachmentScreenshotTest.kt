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
import build.raft.designsystem.components.RaftComposerAttachmentFailed
import build.raft.designsystem.components.RaftComposerAttachmentFile
import build.raft.designsystem.components.RaftComposerAttachmentGap
import build.raft.designsystem.components.RaftComposerAttachmentImage
import build.raft.designsystem.components.RaftComposerAttachmentRemove
import build.raft.designsystem.components.RaftComposerAttachmentRemoveOverhang
import build.raft.designsystem.components.RaftComposerAttachmentUploading
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test

/**
 * raft-ui Composer > attachments: a file tile (short and truncated title) and an image tile at
 * rest, uploading (42%, and before the first byte at the 4% floor; the bar inset 6dp), failed
 * (with the mobile "Tap to retry" line), and the remove sticker at rest, pressed, focused and
 * disabled.
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
                Caption("Uploading: 42%, first byte (4% floor), image")
                Tray {
                    FileTile("android-smoke-log.zip", "application/zip · 8.4 MB") {
                        RaftComposerAttachmentUploading(label = "42%", progress = 42, spinnerStillStep = 0)
                    }
                    FileTile("trace.json", "application/json") {
                        RaftComposerAttachmentUploading(label = "Uploading…", progress = 0, spinnerStillStep = 0)
                    }
                    ImageTile { RaftComposerAttachmentUploading(label = "73%", progress = 73, spinnerStillStep = 0) }
                }
                Caption("Failed: file, image")
                Tray {
                    FileTile("oversized-trace.jsonl", "application/json · 52 MB") {
                        RaftComposerAttachmentFailed(text = "Upload failed", detail = "Tap to retry", onClick = {})
                    }
                    ImageTile { RaftComposerAttachmentFailed(text = "Failed", detail = "Retry", onClick = {}) }
                }
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
    private fun ImageTile(overlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {}) {
        RaftComposerAttachment {
            RaftComposerAttachmentImage(onClick = {}) { Placeholder() }
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
