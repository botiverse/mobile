package build.raft.app

import build.raft.designsystem.theme.RaftTheme
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.components.RaftSlotIcon
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftPopoverSurface
import build.raft.designsystem.components.RaftToggleGroup
import build.raft.designsystem.components.RaftToggleGroupItem
import kotlin.math.exp

/*
 * The image editor's desktop chrome (artin, task #32). The editor itself — the stage, its drawing,
 * the pen / text / mosaic / crop gestures and the text field — is RaftFilePreviewComposerImageEditor,
 * the same on every platform; on a desktop / wide window it takes this chrome instead of the
 * phone's bottom bar:
 * - one top bar: Pen, Text, Mosaic, Crop, Rotate | Undo, Redo | Cancel, Done;
 * - the active tool's options (the phone's option rows) in a popover under the tool;
 * - wheel zoom about the pointer and Space + drag to pan, on the same stage;
 * - Esc cancels, Return is Done (Cmd/Ctrl+Z are the editor's own).
 */

internal val RaftComposerImageDesktopBackdrop = Color(0xF0000000) // raft-color: photo viewer backdrop, dark in every theme
// A head: 56 with a finger, 44 under a mouse or trackpad (sizing v12, raft-ui 0.65 desktop density).
private val RaftComposerImageDesktopBarHeight: Dp
    @Composable @ReadOnlyComposable
    get() = RaftTheme.controls.headHeight

/** Desktop chrome for [RaftFilePreviewComposerImageEditor]; [onCancel] is the top bar's Cancel and Esc. */
internal class RaftComposerImageEditorDesktopChrome(val onCancel: () -> Unit)

internal enum class RaftComposerImageDesktopKey { SpaceDown, SpaceUp, Cancel, Done }

/**
 * The desktop chrome's keys: Space (held: pan), Esc, Return. In the text tool Space and Return
 * belong to the text field.
 */
internal fun raftComposerImageDesktopKey(key: Key, down: Boolean, textTool: Boolean): RaftComposerImageDesktopKey? =
    when {
        key == Key.Escape && down -> RaftComposerImageDesktopKey.Cancel
        textTool -> null
        key == Key.Spacebar -> if (down) RaftComposerImageDesktopKey.SpaceDown else RaftComposerImageDesktopKey.SpaceUp
        (key == Key.Enter || key == Key.NumPadEnter) && down -> RaftComposerImageDesktopKey.Done
        else -> null
    }

/** The on-screen size of a canvas of [canvas] turned by [turns]. */
internal fun raftComposerImageTurnedSize(canvas: Size, turns: Int): Size =
    if (raftComposerImageTurnSwapsSides(turns)) Size(canvas.height, canvas.width) else canvas

/**
 * The desktop stage's extra gestures: the wheel zooms about the pointer, a drag with Space held
 * pans. [visibleSize]: the turned canvas in px.
 */
internal fun Modifier.raftComposerImageDesktopStageGestures(
    enabled: Boolean,
    spaceHeld: Boolean,
    view: () -> RaftComposerImageViewTransform,
    onView: (RaftComposerImageViewTransform) -> Unit,
    visibleSize: () -> Size,
): Modifier =
    this
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type != PointerEventType.Scroll) continue
                    val change = event.changes.firstOrNull() ?: continue
                    onView(
                        view().raftComposerImagePinch(
                            centroid = change.position,
                            zoomChange = exp(-change.scrollDelta.y * 0.12f),
                            panChange = Offset.Zero,
                            stageSize = Size(size.width.toFloat(), size.height.toFloat()),
                            visibleSize = visibleSize(),
                        )
                    )
                    change.consume()
                }
            }
        }
        .pointerInput(enabled, spaceHeld) {
            if (!enabled || !spaceHeld) return@pointerInput
            detectDragGestures { change, drag ->
                change.consume()
                onView(
                    view().raftComposerImagePinch(
                        centroid = Offset(size.width / 2f, size.height / 2f),
                        zoomChange = 1f,
                        panChange = drag,
                        stageSize = Size(size.width.toFloat(), size.height.toFloat()),
                        visibleSize = visibleSize(),
                    )
                )
            }
        }

/** The desktop top bar. [onToolBounds] reports each tool's x span (root px) for its popover. */
@Composable
internal fun RaftComposerImageDesktopTopBar(
    tool: RaftComposerImageEditorTool,
    enabled: Boolean,
    saving: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onSelectTool: (RaftComposerImageEditorTool) -> Unit,
    onRotate: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    onToolBounds: (RaftComposerImageEditorTool, Float, Float) -> Unit,
) {
    RaftDesignSystemTheme(forceDark = true) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(RaftComposerImageDesktopBarHeight)
                    .background(RaftComposerImageDesktopBackdrop)
                    .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RaftToggleGroup {
                    listOf(
                        Triple(RaftComposerImageEditorTool.Pencil, Res.string.file_preview_toolbar_pen, RaftLucideIconName.Pencil),
                        Triple(RaftComposerImageEditorTool.Text, Res.string.file_preview_toolbar_text, RaftLucideIconName.Type),
                        Triple(RaftComposerImageEditorTool.Mosaic, Res.string.file_preview_toolbar_mosaic, RaftLucideIconName.Mosaic),
                        Triple(RaftComposerImageEditorTool.Crop, Res.string.file_preview_toolbar_crop, RaftLucideIconName.Crop),
                    ).forEach { (item, label, icon) ->
                        RaftToggleGroupItem(
                            text = stringResource(label),
                            pressed = tool == item,
                            onPressedChange = { onSelectTool(item) },
                            enabled = enabled,
                            icon = { RaftSlotIcon(icon) },
                            modifier =
                                Modifier.onGloballyPositioned { coordinates ->
                                    val x = coordinates.positionInRoot().x
                                    onToolBounds(item, x, x + coordinates.size.width)
                                },
                        )
                    }
                }
                RaftIconButton(
                    onClick = onRotate,
                    tooltip = stringResource(Res.string.file_preview_toolbar_rotate),
                    variant = RaftButtonVariant.Ghost,
                    size = RaftIconButtonSize.Lg,
                    enabled = enabled,
                ) { RaftSlotIcon(RaftLucideIconName.RotateCw) }
                Box(modifier = Modifier.size(width = 1.dp, height = 24.dp).background(Color(0x40FFFFFF))) // raft-color: divider on the photo viewer's dark bar
                RaftIconButton(
                    onClick = onUndo,
                    tooltip = stringResource(Res.string.common_undo),
                    variant = RaftButtonVariant.Ghost,
                    size = RaftIconButtonSize.Lg,
                    enabled = canUndo,
                ) { RaftSlotIcon(RaftLucideIconName.Undo2) }
                RaftIconButton(
                    onClick = onRedo,
                    tooltip = stringResource(Res.string.common_redo),
                    variant = RaftButtonVariant.Ghost,
                    size = RaftIconButtonSize.Lg,
                    enabled = canRedo,
                ) { RaftSlotIcon(RaftLucideIconName.Redo2) }
            }
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RaftButton(text = stringResource(Res.string.common_cancel), onClick = onCancel, variant = RaftButtonVariant.Ghost, enabled = !saving)
                RaftButton(
                    text = stringResource(if (saving) Res.string.file_preview_editor_saving else Res.string.common_done),
                    onClick = onDone,
                    variant = RaftButtonVariant.Primary,
                    enabled = enabled,
                    loading = saving,
                    leadingIcon = { RaftSlotIcon(RaftLucideIconName.Check) },
                )
            }
        }
    }
}

/**
 * A tool's options under the tool: centred on [anchorLeft]..[anchorRight] (root px) just below
 * the top bar, kept inside the window, in the editor's dark chrome. The option rows draw with the
 * enclosing theme's colours (`RaftTheme.colors`), so they follow the popover, not the app.
 */
@Composable
internal fun RaftComposerImageDesktopToolPopover(
    anchorLeft: Float,
    anchorRight: Float,
    width: Dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    // Every tool's popover wears the editor's dark chrome (the top bar's forced-dark theme),
    // whatever the app's appearance: Pen, Text and Crop read as one family over the photo.
    RaftDesignSystemTheme(forceDark = true) {
        // The window width is known while composing (no measure-then-move frame).
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val windowWidthPx = with(density) { maxWidth.toPx() }
            val widthPx = with(density) { width.toPx() }
            val centre = (anchorLeft + anchorRight) / 2f
            val x = (centre - widthPx / 2f).coerceIn(8f, (windowWidthPx - widthPx - 8f).coerceAtLeast(8f))
            val y = with(density) { (RaftComposerImageDesktopBarHeight - 6.dp).toPx() }
            RaftPopoverSurface(
                width = width,
                contentPadding = PaddingValues(12.dp),
                modifier = Modifier.offset { IntOffset(x.toInt(), y.toInt()) },
                content = content,
            )
        }
    }
}
