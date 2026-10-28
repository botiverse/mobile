package build.raft.designsystem.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftAvatar
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftAvatarType
import build.raft.designsystem.components.RaftDescriptionList
import build.raft.designsystem.components.RaftDescriptionListDirection
import build.raft.designsystem.components.RaftPickerTriggerButton
import build.raft.designsystem.components.RaftPickerTriggerSize
import build.raft.designsystem.components.RaftTaskStatus
import build.raft.designsystem.components.RaftTaskStatusBadge
import build.raft.designsystem.components.RaftTaskStatusBadgeSize
import build.raft.designsystem.components.RaftTimeline
import build.raft.designsystem.components.RaftTimelineDetail
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test

/**
 * The two raft-ui pieces of the task dialog's head (web `TaskProperties`, `HistoryTimeline`), light
 * and dark, in a 358dp phone column: the inline DescriptionList (facts on one wrapping row; a chip,
 * a picker trigger, plain text and a mono value; a long value cut to its line; 2x text) and
 * the Timeline (status pixels on a hairline; a neutral marker; title and time on one baseline; each
 * title and detail one line with a 12dp chevron when cut, one shown unfolded; the last item with
 * no line after it; right to left).
 */
class TaskHeadComponentsScreenshotTest {
    private val appearances = listOf(RaftAppearance.Light, RaftAppearance.Dark)

    @Test
    fun descriptionListInline() =
        appearances.forEach { appearance ->
            RaftScreenshot.capture("description_list_inline_${appearance.name.lowercase()}", appearance) {
                Column(
                    Modifier.width(358.dp).background(RaftTheme.colors.layerCanvas).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Caption("Inline: facts wrap onto the next line, 24dp apart")
                    RaftDescriptionList(direction = RaftDescriptionListDirection.Inline) {
                        Item("Status") { RaftTaskStatusBadge(RaftTaskStatus.InProgress, "In Progress", onClick = {}) }
                        Item("Assignee") {
                            RaftPickerTriggerButton(text = "@Lagoon", onClick = {}, hasValue = true, size = RaftPickerTriggerSize.Chip)
                        }
                        Item("Created by", "Artin")
                        Item("Created at", "2026-10-28 09:41", mono = true)
                    }
                    Caption("Inline: a long value is cut to its line (raft-ui truncates)")
                    RaftDescriptionList(direction = RaftDescriptionListDirection.Inline) {
                        Item("Source") {
                            RaftAvatar(RaftAvatarType.Human, RaftAvatarSize.Xs, initials = "A")
                            RaftText("Artin", style = RaftTheme.typography.callout)
                            RaftText(
                                "The app's task detail lacks a lot compared with web",
                                modifier = Modifier.weight(1f, fill = false),
                                style = RaftTheme.typography.callout,
                                color = RaftTheme.colors.foregroundMuted,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                        Item("Status") { RaftTaskStatusBadge(RaftTaskStatus.Done, "Done", size = RaftTaskStatusBadgeSize.Md) }
                    }
                    Caption("Inline at 2x text: a value is cut; with no room beside its label, it goes under it")
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, fontScale = 2f)
                    ) {
                        RaftDescriptionList(direction = RaftDescriptionListDirection.Inline) {
                            Item("Status") { RaftTaskStatusBadge(RaftTaskStatus.InReview, "In Review") }
                            Item("Earlier discussion", "4 replies")
                        }
                    }
                }
            }
        }

    @Test
    fun timeline() =
        appearances.forEach { appearance ->
            RaftScreenshot.capture("timeline_${appearance.name.lowercase()}", appearance) {
                Column(
                    Modifier.width(358.dp).background(RaftTheme.colors.layerCanvas).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Caption("Task history: status pixels, a neutral marker, the last item has no line")
                    RaftTimeline {
                        item("Created task", time = "Artin · 09:41", titleBold = true, taskStatus = RaftTaskStatus.Todo) {
                            RaftTimelineDetail("Task #7 · Status: Todo")
                        }
                        item("Changed assignee", time = "Artin · 09:42", titleBold = true) {
                            RaftTimelineDetail("Assigned to Lagoon")
                        }
                        item("Updated task", time = "Artin · 09:50", titleBold = true) {
                            RaftTimelineDetail("Title: Task detail → Task detail parity with the web and its history")
                        }
                        item("Changed status", time = "Lagoon · 10:05", titleBold = true, taskStatus = RaftTaskStatus.InProgress) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                RaftTaskStatusBadge(RaftTaskStatus.Todo, "Todo", size = RaftTaskStatusBadgeSize.Sm)
                                RaftTimelineDetail("→")
                                RaftTaskStatusBadge(RaftTaskStatus.InProgress, "In Progress", size = RaftTaskStatusBadgeSize.Sm)
                            }
                        }
                        item("Updated task", time = "Lagoon · 10-27 18:20", titleBold = true) {
                            RaftTimelineDetail(
                                "Description: — → Header, info rows, More menu, history and the composer placeholder, as the web dialog has them",
                                initiallyOpen = true,
                            )
                        }
                        item("Closed task", time = "Artin · 2025-12-31 23:59", titleBold = true, taskStatus = RaftTaskStatus.Closed)
                    }
                    Caption("Right to left: the axis on the start (right) side")
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl
                    ) {
                        RaftTimeline {
                            item("Created task", time = "Artin · 09:41", titleBold = true, taskStatus = RaftTaskStatus.Todo)
                            item("Closed task", time = "Artin · 09:50", titleBold = true, taskStatus = RaftTaskStatus.Closed)
                        }
                    }
                }
            }
        }

    @Composable
    private fun Caption(text: String) {
        RaftText(text = text, style = RaftTheme.typography.monoMeta, color = RaftTheme.colors.foregroundHint)
    }
}
