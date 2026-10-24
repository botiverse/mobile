package build.raft.designsystem.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftPressedPreview
import build.raft.designsystem.components.RaftAvatar
import build.raft.designsystem.components.RaftAvatarType
import build.raft.designsystem.components.RaftMessageForwardedBundle
import build.raft.designsystem.components.RaftMessageForwardedBundleAttachment
import build.raft.designsystem.components.RaftMessageForwardedBundleAttachments
import build.raft.designsystem.components.RaftMessageForwardedBundleFooterMode
import build.raft.designsystem.components.RaftMessageForwardedBundleGallery
import build.raft.designsystem.components.RaftMessageForwardedBundleItem
import build.raft.designsystem.components.RaftMessageForwardedBundleItemTime
import build.raft.designsystem.components.RaftMessageQuotedPreview
import build.raft.designsystem.components.RaftMessageQuotedPreviewDefaults
import build.raft.designsystem.components.RaftMessageQuotedPreviewLoading
import build.raft.designsystem.components.RaftMessageQuotedPreviewUnavailable
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test

/**
 * raft-ui MessageQuotedPreview and MessageForwardedBundle as the web composes them (artin task
 * #20 lane I, gaps 17 and 18):
 *  - `message_quoted_preview`: a channel message, a direct-message reply in a thread with a
 *    role, an archived channel with attachments, pressed, narrow (the header wraps), loading
 *    and unavailable.
 *  - `message_forwarded_bundle`: a short bundle with a source and file chips (no footer), a
 *    long one that folds with a fade and its footer, and one with a gallery of three and of
 *    four, at a phone's width.
 */
class MessageQuotedForwardedScreenshotTest {
    private val appearances = listOf(RaftAppearance.Light, RaftAppearance.Dark)

    private fun capture(name: String, content: @Composable () -> Unit) =
        appearances.forEach { appearance ->
            RaftScreenshot.capture("${name}_${appearance.name.lowercase()}", appearance, content = content)
        }

    @Test
    fun messageQuotedPreview() =
        capture("message_quoted_preview") {
            Column(Modifier.width(380.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Caption("Channel message")
                RaftMessageQuotedPreview(
                    authorName = "Alpha",
                    text = "Rolled back the migration; the error rate is flat again. I'll keep watching the dashboard for an hour.",
                    channel = "launch-week",
                    avatar = { Face("Alpha", agent = true) },
                    time = { Time("Today · 8:24 AM") },
                    onClick = {},
                )
                Caption("Direct message, thread reply, with a role")
                RaftMessageQuotedPreview(
                    authorName = "Carol",
                    text = "Is the migration safe to run twice?",
                    channel = "Carol",
                    direct = true,
                    threadLabel = "Thread",
                    authorMeta = "Design",
                    avatar = { Face("Carol") },
                    time = { Time("May 4 · 3:16 PM") },
                    onClick = {},
                )
                Caption("Archived channel, attachments, pressed")
                CompositionLocalProvider(LocalRaftPressedPreview provides true) {
                    RaftMessageQuotedPreview(
                        authorName = "Dana",
                        text = "Release notes and the screenshots from the last run.",
                        channel = "old-launch",
                        archivedLabel = "Archived",
                        avatar = { Face("Dana") },
                        time = { Time("Mar 2 · 9:01 AM") },
                        attachments = listOf("2 files"),
                        onClick = {},
                    )
                }
                Caption("Narrow: the header wraps, the name truncates")
                Box(Modifier.width(240.dp)) {
                    RaftMessageQuotedPreview(
                        authorName = "A very long display name for a person",
                        text = "Short.",
                        channel = "launch-week",
                        threadLabel = "Thread",
                        avatar = { Face("Long") },
                        time = { Time("Today · 8:24 AM") },
                        onClick = {},
                    )
                }
                Caption("Loading, unavailable")
                RaftMessageQuotedPreviewLoading(label = "Loading quoted message")
                RaftMessageQuotedPreviewUnavailable(text = "Message unavailable")
            }
        }

    @Test
    fun messageForwardedBundle() =
        capture("message_forwarded_bundle") {
            Column(Modifier.width(358.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Caption("Short, with a source and files: no footer")
                RaftMessageForwardedBundle(
                    label = "Forwarded",
                    countLabel = "2 messages",
                    source = "from #launch-week",
                    footerLabel = "View all 2 messages",
                ) {
                    RaftMessageForwardedBundleItem(author = "@alpha", time = { RaftMessageForwardedBundleItemTime("8:24 AM") }) {
                        Body("Shipping the hotfix now.")
                    }
                    RaftMessageForwardedBundleItem(
                        author = "@carol",
                        time = { RaftMessageForwardedBundleItemTime("8:31 AM") },
                        attachments = {
                            RaftMessageForwardedBundleAttachments {
                                RaftMessageForwardedBundleAttachment("release-notes.md", meta = "· markdown", onClick = {})
                                RaftMessageForwardedBundleAttachment("a-very-long-file-name-for-the-build-output.log", meta = "· plain")
                            }
                        },
                    ) {
                        Body("Notes attached.")
                    }
                }
                Caption("Long: folds at 8 lines with a fade, footer always")
                RaftMessageForwardedBundle(
                    label = "Forwarded",
                    countLabel = "3 messages",
                    source = "from #launch-week · thread",
                    onOpenSource = {},
                    footerLabel = "View all 3 messages",
                    onFooterClick = {},
                    footerMode = RaftMessageForwardedBundleFooterMode.Always,
                    foldHeight = 24.dp * 8,
                ) {
                    RaftMessageForwardedBundleItem(author = "@alpha", time = { RaftMessageForwardedBundleItemTime("May 4 · 3:16 PM") }) {
                        Body(
                            "The rollout plan: first the canary, then a tenth of the fleet, then everyone. " +
                                "Each step waits for an hour of clean error rates before the next one starts, " +
                                "and any alert pages the on-call engineer and stops the rollout.",
                        )
                    }
                    RaftMessageForwardedBundleItem(author = "@carol", time = { RaftMessageForwardedBundleItemTime("May 4 · 3:20 PM") }) {
                        Body("Sounds right. Who is on call this week, and do they know the rollback steps?")
                    }
                    RaftMessageForwardedBundleItem(author = "@dana", time = { RaftMessageForwardedBundleItemTime("May 4 · 3:22 PM") }) {
                        Body("Me. Yes.")
                    }
                }
                Caption("Images: a row of three, then four as two and two")
                RaftMessageForwardedBundle(label = "Forwarded", countLabel = "message") {
                    RaftMessageForwardedBundleItem(
                        author = "@alpha",
                        time = { RaftMessageForwardedBundleItemTime("8:24 AM") },
                        attachments = {
                            RaftMessageForwardedBundleGallery(count = 3) { index -> Swatch(index) }
                            RaftMessageForwardedBundleGallery(count = 4) { index -> Swatch(index + 1) }
                        },
                    ) {
                        Body("Screenshots from the run.")
                    }
                }
            }
        }

    @Composable
    private fun Face(name: String, agent: Boolean = false) {
        RaftAvatar(
            type = if (agent) RaftAvatarType.Agent else RaftAvatarType.Human,
            size = RaftMessageQuotedPreviewDefaults.AvatarSize,
            initials = name.take(1),
        )
    }

    @Composable
    private fun Time(text: String) {
        RaftText(text, style = RaftMessageQuotedPreviewDefaults.TimeStyle, color = RaftMessageQuotedPreviewDefaults.TimeInk, maxLines = 1)
    }

    @Composable
    private fun Body(text: String) {
        RaftText(text, style = RaftTheme.typography.body, color = RaftTheme.colors.foreground)
    }

    @Composable
    private fun Swatch(index: Int) {
        val colors = listOf(Color(0xFF8EC9F0), Color(0xFFE9C98B), Color(0xFF9CC79A), Color(0xFFF0A5B8), Color(0xFFB9A8E6))
        Box(Modifier.fillMaxSize().background(colors[index % colors.size]))
    }

    @Composable
    private fun Caption(text: String) {
        RaftText(text = text, style = RaftTheme.typography.monoMeta, color = RaftTheme.colors.foregroundHint)
    }
}
