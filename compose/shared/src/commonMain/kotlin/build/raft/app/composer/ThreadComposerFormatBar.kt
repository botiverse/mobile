package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftSlotIcon
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftDropdownMenu
import build.raft.designsystem.components.RaftEmbeddedControls
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftSeparator
import build.raft.designsystem.components.RaftSeparatorOrientation

internal const val ThreadComposerFormatBarTestTag = "composer-format-bar"
internal const val ThreadComposerCollapseButtonTestTag = "composer-collapse"
internal const val ThreadComposerFormatMoreTestTag = "composer-format-more"

internal fun threadComposerFormatToolTestTag(id: String): String = "composer-format-$id"

/** Breakpoint matching web `@[30rem]/composer-format` (480px) where block format tools sit inline vs fold into ⋯. */
internal val ThreadComposerFormatBarWideMinWidth: Dp = 480.dp

internal fun threadComposerFormatBarShowsInlineBlockTools(availableWidthDp: Dp): Boolean =
    availableWidthDp >= ThreadComposerFormatBarWideMinWidth

/**
 * The expanded composer's top row (botiverse/slock #16767 / #triage:#629, cindyz's option A):
 * Markdown formatting tools at the start (lists, quote, and code block fold into a ⋯ menu below
 * [ThreadComposerFormatBarWideMinWidth]), Collapse at the end whose tooltip shows `Esc` on hardware
 * keyboards (no literal `Esc` text on the row itself).
 */
@Composable
internal fun ThreadComposerFormatBar(
    enabled: Boolean,
    hardwareKeyboardConnected: Boolean,
    editorFocusRequester: FocusRequester,
    onApplyFormat: (ThreadComposerMarkdownTool) -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val toolbarLabel = stringResource(Res.string.composer_format_toolbar)
    val moreLabel = stringResource(Res.string.composer_format_more)
    val collapseLabel = stringResource(Res.string.composer_collapse)
    var moreMenuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier =
            modifier
                .composerToolbarBleed()
                .fillMaxWidth()
                .testTag(ThreadComposerFormatBarTestTag),
    ) {
        RaftEmbeddedControls {
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            ) {
                val showBlockToolsInline = threadComposerFormatBarShowsInlineBlockTools(maxWidth)
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier =
                            Modifier
                                .weight(1f)
                                .semantics { contentDescription = toolbarLabel },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        ThreadComposerInlineFormatGroups.forEachIndexed { index, group ->
                            if (index > 0) ThreadComposerFormatBarSeparator()
                            group.forEach { action ->
                                ThreadComposerFormatToolButton(
                                    action = action,
                                    enabled = enabled,
                                    onApplyFormat = onApplyFormat,
                                )
                            }
                        }
                        ThreadComposerFormatBarSeparator()
                        if (showBlockToolsInline) {
                            ThreadComposerBlockFormatActions.forEach { action ->
                                ThreadComposerFormatToolButton(
                                    action = action,
                                    enabled = enabled,
                                    onApplyFormat = onApplyFormat,
                                )
                            }
                        } else {
                            Box {
                                ComposerIconButton(
                                    icon = RaftLucideIconName.Ellipsis,
                                    enabled = enabled,
                                    modifier =
                                        Modifier
                                            .threadComposerSuggestionKeepsEditorFocus()
                                            .testTag(ThreadComposerFormatMoreTestTag),
                                    contentDescription = moreLabel,
                                    onClick = { moreMenuExpanded = true },
                                )
                                RaftDropdownMenu(
                                    expanded = moreMenuExpanded,
                                    onDismissRequest = { moreMenuExpanded = false },
                                    returnFocusTo = editorFocusRequester,
                                ) {
                                    ThreadComposerBlockFormatActions.forEach { action ->
                                        RaftMenuItem(
                                            text = stringResource(action.labelRes),
                                            onClick = {
                                                moreMenuExpanded = false
                                                onApplyFormat(action.tool)
                                            },
                                            modifier = Modifier.testTag(threadComposerFormatToolTestTag(action.id)),
                                            leadingIcon = { RaftSlotIcon(action.icon) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    ComposerIconButton(
                        icon = RaftLucideIconName.Minimize2,
                        enabled = enabled,
                        modifier =
                            Modifier
                                .threadComposerSuggestionKeepsEditorFocus()
                                .testTag(ThreadComposerCollapseButtonTestTag),
                        contentDescription = collapseLabel,
                        tooltipShortcut = if (hardwareKeyboardConnected) "Esc" else null,
                        onClick = onCollapse,
                    )
                }
            }
        }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(RaftColor.BorderSeam),
        )
    }
}

@Composable
private fun ThreadComposerFormatToolButton(
    action: ThreadComposerFormatAction,
    enabled: Boolean,
    onApplyFormat: (ThreadComposerMarkdownTool) -> Unit,
) {
    ComposerIconButton(
        icon = action.icon,
        enabled = enabled,
        modifier =
            Modifier
                .threadComposerSuggestionKeepsEditorFocus()
                .testTag(threadComposerFormatToolTestTag(action.id)),
        contentDescription = stringResource(action.labelRes),
        onClick = { onApplyFormat(action.tool) },
    )
}

@Composable
private fun ThreadComposerFormatBarSeparator() {
    RaftSeparator(
        modifier = Modifier.padding(horizontal = 4.dp).height(16.dp),
        orientation = RaftSeparatorOrientation.Vertical,
    )
}
