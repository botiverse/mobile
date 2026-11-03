package build.raft.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.components.RaftSlotIcon
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftPopoverSurface
import build.raft.designsystem.components.RaftSegment
import build.raft.designsystem.components.RaftSegmentedControl
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.RaftToggleGroup
import build.raft.designsystem.components.RaftToggleGroupItem
import build.raft.designsystem.text.RaftText
import kotlin.math.exp

/*
 * The desktop image editor (artin, task #32): Edit on an image in the conversation's image viewer
 * opens it over the window like the viewer (dark backdrop, the picture centred) with one top bar:
 * Pen, Mosaic, Crop, Rotate | Undo, Redo | Cancel, Done. A tool's options (pen widths and colours;
 * crop shapes, Reset, Apply) open in a small popover under that tool. The wheel zooms about the
 * pointer; a drag pans when no tool is active or while Space is held. Cmd/Ctrl+Z undoes (Shift
 * redoes), Esc cancels, Return is Done.
 *
 * The edit model, drawing and full-resolution export are the phone editor's
 * (RaftComposerImageEditorContent, drawRaftComposerImageDrawOperations, renderRaftComposerImageExport);
 * only the chrome differs. Text stays phone-only for now.
 */

private val RaftComposerImageDesktopBackdrop = Color(0xF0000000) // raft-color: photo viewer backdrop, dark in every theme
private val RaftComposerImageDesktopBarHeight = 56.dp
private val RaftComposerImageDesktopStagePadding = 48.dp

/** The desktop editor's chrome state; the edits themselves are [RaftComposerImageEditorState]. */
internal class RaftComposerImageDesktopEditorUi(
    tool: RaftComposerImageEditorTool? = null,
    penColor: Color = RaftComposerImageEditorDefaultPenColor,
    penWidth: RaftComposerImagePenWidth = RaftComposerImagePenWidth.Thin,
    cropAspect: RaftComposerImageCropAspect = RaftComposerImageCropAspect.Free,
    transientCrop: RaftComposerImageCropRect? = null,
) {
    /** The active tool; null: none (a drag pans). */
    var tool by mutableStateOf(tool)
    var penColor by mutableStateOf(penColor)
    var penWidth by mutableStateOf(penWidth)
    var cropAspect by mutableStateOf(cropAspect)
    var transientCrop by mutableStateOf(transientCrop)
    var view by mutableStateOf(RaftComposerImageViewTransform.Identity)
}

/** Unrotated canvas size (px) of [image] under [content] on a stage of [stagePx]; [cropping]: the whole picture. */
internal fun raftComposerImageDesktopCanvasPx(
    image: ImageBitmap?,
    content: RaftComposerImageEditorContent,
    stagePx: Size,
    cropping: Boolean = false,
): Size? {
    val bitmap = image ?: return null
    val crop = if (cropping) RaftComposerImageCropRect.Uncropped else content.cropRect
    return raftComposerImageEditorCanvasSize(bitmap.width, bitmap.height, stagePx.width, stagePx.height, crop, content.rotationQuarterTurns)
}

/**
 * [onDone] gets the committed canvas size in px (the coordinate space of the edits), which the
 * export scales to the photo's resolution.
 */
@Composable
internal fun RaftComposerImageDesktopEditor(
    image: ImageBitmap?,
    state: RaftComposerImageEditorState,
    ui: RaftComposerImageDesktopEditorUi,
    saving: Boolean,
    onCancel: () -> Unit,
    onDone: (canvasSizePx: IntSize) -> Unit,
    modifier: Modifier = Modifier,
    failed: Boolean = false,
) {
    val focusRequester = remember { FocusRequester() }
    var spaceHeld by remember { mutableStateOf(false) }
    val toolBounds = remember { mutableStateMapOf<RaftComposerImageEditorTool, Pair<Float, Float>>() }
    val activeStroke = remember { mutableStateListOf<Offset>() }
    val activeMosaic = remember { mutableStateOf<Set<RaftComposerImageMosaicTile>>(emptySet()) }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    RaftDesignSystemTheme(forceDark = true) {
        BoxWithConstraints(
            modifier =
                modifier
                    .fillMaxSize()
                    .background(RaftComposerImageDesktopBackdrop)
                    .focusRequester(focusRequester)
                    .focusable()
        ) {
            val density = LocalDensity.current
            val stagePx =
                with(density) {
                    Size(
                        (maxWidth - RaftComposerImageDesktopStagePadding * 2).toPx(),
                        (maxHeight - RaftComposerImageDesktopBarHeight - RaftComposerImageDesktopStagePadding).toPx(),
                    )
                }
            val content = state.history.content
            val cropping = ui.tool == RaftComposerImageEditorTool.Crop
            fun committedCanvas(): IntSize? =
                raftComposerImageDesktopCanvasPx(image, state.history.content, stagePx)?.let {
                    IntSize(it.width.toInt().coerceAtLeast(1), it.height.toInt().coerceAtLeast(1))
                }
            fun done() {
                if (saving || image == null) return
                committedCanvas()?.let(onDone)
            }
            fun selectTool(next: RaftComposerImageEditorTool) {
                ui.tool = if (ui.tool == next) null else next
                if (ui.tool == RaftComposerImageEditorTool.Crop) {
                    ui.transientCrop = state.history.content.cropRect
                    ui.view = RaftComposerImageViewTransform.Identity
                }
            }
            fun applyCrop() {
                val bitmap = image ?: return
                val crop = ui.transientCrop ?: return
                state.update(
                    state.history.apply(
                        raftApplyCropToContent(state.history.content, crop, bitmap.width, bitmap.height, stagePx.width, stagePx.height)
                    )
                )
                ui.tool = null
            }
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .onPreviewKeyEvent { event ->
                            if (event.key == Key.Spacebar) {
                                spaceHeld = event.type == KeyEventType.KeyDown
                                return@onPreviewKeyEvent true
                            }
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when {
                                event.key == Key.Escape -> onCancel()
                                event.key == Key.Enter || event.key == Key.NumPadEnter -> done()
                                else ->
                                    when (
                                        raftComposerImageEditorShortcut(
                                            event.key,
                                            command = event.isMetaPressed || event.isCtrlPressed,
                                            shift = event.isShiftPressed,
                                        )
                                    ) {
                                        RaftComposerImageEditorShortcut.Undo -> state.undo()
                                        RaftComposerImageEditorShortcut.Redo -> state.redo()
                                        null -> return@onPreviewKeyEvent false
                                    }
                            }
                            true
                        }
            ) {
                // The stage: wheel zoom, pan, and the picture.
                Box(
                    modifier =
                        Modifier
                            .padding(
                                top = RaftComposerImageDesktopBarHeight,
                                start = RaftComposerImageDesktopStagePadding,
                                end = RaftComposerImageDesktopStagePadding,
                                bottom = RaftComposerImageDesktopStagePadding,
                            )
                            .fillMaxSize()
                            .clipToBounds()
                            .pointerInput(image) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        if (event.type != PointerEventType.Scroll) continue
                                        val change = event.changes.firstOrNull() ?: continue
                                        val canvas = raftComposerImageDesktopCanvasPx(image, state.history.content, stagePx, ui.tool == RaftComposerImageEditorTool.Crop) ?: continue
                                        if (ui.tool == RaftComposerImageEditorTool.Crop) continue
                                        ui.view =
                                            ui.view.raftComposerImagePinch(
                                                centroid = change.position,
                                                zoomChange = exp(-change.scrollDelta.y * 0.12f),
                                                panChange = Offset.Zero,
                                                stageSize = Size(size.width.toFloat(), size.height.toFloat()),
                                                visibleSize = raftComposerImageTurnedSize(canvas, state.history.content.rotationQuarterTurns),
                                            )
                                        change.consume()
                                    }
                                }
                            }
                            .pointerInput(image, ui.tool, spaceHeld) {
                                if (ui.tool != null && !spaceHeld) return@pointerInput
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    val canvas = raftComposerImageDesktopCanvasPx(image, state.history.content, stagePx) ?: return@detectDragGestures
                                    ui.view =
                                        ui.view.raftComposerImagePinch(
                                            centroid = Offset(size.width / 2f, size.height / 2f),
                                            zoomChange = 1f,
                                            panChange = drag,
                                            stageSize = Size(size.width.toFloat(), size.height.toFloat()),
                                            visibleSize = raftComposerImageTurnedSize(canvas, state.history.content.rotationQuarterTurns),
                                        )
                                }
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    val canvas = raftComposerImageDesktopCanvasPx(image, content, stagePx, cropping)
                    if (image != null && canvas != null) {
                        val shownCrop = if (cropping) RaftComposerImageCropRect.Uncropped else content.cropRect
                        val view = ui.view
                        val drawingEnabled = !spaceHeld && !saving
                        var cropHandle by remember { mutableStateOf(RaftCropDragHandle.None) }
                        Box(
                            modifier =
                                Modifier
                                    .requiredSize(with(density) { canvas.width.toDp() }, with(density) { canvas.height.toDp() })
                                    .graphicsLayer {
                                        // Pointer input below arrives in the unrotated canvas's coordinates.
                                        rotationZ = content.rotationQuarterTurns * 90f
                                        scaleX = view.zoom
                                        scaleY = view.zoom
                                        translationX = view.pan.x
                                        translationY = view.pan.y
                                    }
                                    .pointerInput(ui.tool, drawingEnabled, ui.penColor, ui.penWidth, ui.cropAspect) {
                                        val tool = ui.tool ?: return@pointerInput
                                        if (!drawingEnabled) return@pointerInput
                                        var last = Offset.Zero
                                        detectDragGestures(
                                            onDragStart = { start ->
                                                last = start
                                                when (tool) {
                                                    RaftComposerImageEditorTool.Pencil -> {
                                                        activeStroke.clear()
                                                        activeStroke.add(start)
                                                    }
                                                    RaftComposerImageEditorTool.Mosaic ->
                                                        activeMosaic.value = raftComposerImageMosaicTilesAt(start)
                                                    RaftComposerImageEditorTool.Crop ->
                                                        cropHandle =
                                                            raftCropDetectHandle(
                                                                start,
                                                                ui.transientCrop ?: state.history.content.cropRect,
                                                                Size(size.width.toFloat(), size.height.toFloat()),
                                                                hitRadius = 14f * density.density,
                                                            )
                                                    RaftComposerImageEditorTool.Text -> Unit
                                                }
                                            },
                                            onDragEnd = {
                                                when (tool) {
                                                    RaftComposerImageEditorTool.Pencil ->
                                                        if (activeStroke.isNotEmpty()) {
                                                            val points = if (activeStroke.size == 1) listOf(activeStroke[0], activeStroke[0]) else activeStroke.toList()
                                                            state.update(
                                                                state.history.apply(
                                                                    state.history.content.raftComposerImageAppendStroke(
                                                                        RaftComposerImageStroke(points = points, color = ui.penColor, width = ui.penWidth.px)
                                                                    )
                                                                )
                                                            )
                                                        }
                                                    RaftComposerImageEditorTool.Mosaic ->
                                                        if (activeMosaic.value.isNotEmpty()) {
                                                            state.update(raftComposerImageCommitMosaicGesture(state.history, activeMosaic.value))
                                                        }
                                                    else -> Unit
                                                }
                                                activeStroke.clear()
                                                activeMosaic.value = emptySet()
                                                cropHandle = RaftCropDragHandle.None
                                            },
                                            onDragCancel = {
                                                activeStroke.clear()
                                                activeMosaic.value = emptySet()
                                                cropHandle = RaftCropDragHandle.None
                                            },
                                        ) { change, drag ->
                                            change.consume()
                                            when (tool) {
                                                RaftComposerImageEditorTool.Pencil -> activeStroke.add(change.position)
                                                RaftComposerImageEditorTool.Mosaic -> {
                                                    val tiles = activeMosaic.value.toMutableSet()
                                                    raftComposerImageAccumulateMosaicTilesAlongLine(tiles, last, change.position)
                                                    activeMosaic.value = tiles
                                                }
                                                RaftComposerImageEditorTool.Crop ->
                                                    if (cropHandle != RaftCropDragHandle.None) {
                                                        val bitmap = image
                                                        ui.transientCrop =
                                                            raftCropApplyDragWithAspect(
                                                                handle = cropHandle,
                                                                cropRect = ui.transientCrop ?: state.history.content.cropRect,
                                                                dragAmount = drag,
                                                                canvasSize = Size(size.width.toFloat(), size.height.toFloat()),
                                                                ratio = ui.cropAspect.pixelRatio(bitmap.width, bitmap.height),
                                                                imageWidth = bitmap.width,
                                                                imageHeight = bitmap.height,
                                                            )
                                                    }
                                                RaftComposerImageEditorTool.Text -> Unit
                                            }
                                            last = change.position
                                        }
                                    }
                        ) {
                            key(content, shownCrop, cropping) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    drawRaftComposerBackgroundImage(image = image, cropRect = shownCrop)
                                    if (!cropping) {
                                        drawRaftComposerImageDrawOperations(image = image, content = content, cropRect = shownCrop)
                                    }
                                }
                            }
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                if (cropping) {
                                    drawRaftCropOverlay(
                                        cropRect = ui.transientCrop ?: content.cropRect,
                                        canvasSize = size,
                                        density = density.density,
                                    )
                                } else {
                                    drawRaftComposerImageMosaic(image = image, tiles = activeMosaic.value, cropRect = shownCrop)
                                    activeStroke.toList().windowed(2).forEach { (a, b) ->
                                        drawLine(color = ui.penColor, start = a, end = b, strokeWidth = ui.penWidth.px, cap = StrokeCap.Round)
                                    }
                                }
                            }
                        }
                    } else if (failed) {
                        RaftText(text = stringResource(Res.string.file_preview_image_full_load_failed))
                    } else {
                        RaftSpinner()
                    }
                }
                RaftComposerImageDesktopToolbar(
                    tool = ui.tool,
                    enabled = image != null && !saving,
                    saving = saving,
                    canUndo = state.history.canUndo && !saving,
                    canRedo = state.history.canRedo && !saving,
                    onSelectTool = ::selectTool,
                    onRotate = {
                        val bitmap = image
                        if (bitmap != null) {
                            state.update(
                                state.history.apply(
                                    raftApplyRotateToContent(state.history.content, bitmap.width, bitmap.height, stagePx.width, stagePx.height)
                                )
                            )
                            ui.view = RaftComposerImageViewTransform.Identity
                        }
                    },
                    onUndo = state::undo,
                    onRedo = state::redo,
                    onCancel = onCancel,
                    onDone = ::done,
                    onToolBounds = { tool, left, right -> toolBounds[tool] = left to right },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
                val activeTool = ui.tool
                val anchor = activeTool?.let { toolBounds[it] }
                if (anchor != null && (activeTool == RaftComposerImageEditorTool.Pencil || activeTool == RaftComposerImageEditorTool.Crop)) {
                    // The tool's options in a popover under the tool, kept inside the window.
                    var popoverWidthPx by remember(activeTool) { mutableStateOf(0) }
                    val windowWidthPx = with(density) { maxWidth.toPx() }
                    val centre = (anchor.first + anchor.second) / 2f
                    val x = (centre - popoverWidthPx / 2f).coerceIn(8f, (windowWidthPx - popoverWidthPx - 8f).coerceAtLeast(8f))
                    val y = with(density) { (RaftComposerImageDesktopBarHeight - 6.dp).toPx() }
                    RaftPopoverSurface(
                        width = null,
                        contentPadding = PaddingValues(12.dp),
                        modifier =
                            Modifier
                                .offset { IntOffset(x.toInt(), y.toInt()) }
                                .onGloballyPositioned { popoverWidthPx = it.size.width },
                    ) {
                        if (activeTool == RaftComposerImageEditorTool.Pencil) {
                            RaftComposerImageDesktopPenOptions(ui)
                        } else {
                            RaftComposerImageDesktopCropOptions(
                                ui = ui,
                                onSelectAspect = { aspect ->
                                    ui.cropAspect = aspect
                                    val bitmap = image
                                    val ratio = bitmap?.let { aspect.pixelRatio(it.width, it.height) }
                                    if (bitmap != null && ratio != null) {
                                        ui.transientCrop =
                                            raftCropRectForAspect(ui.transientCrop ?: state.history.content.cropRect, ratio, bitmap.width, bitmap.height)
                                    }
                                },
                                onReset = {
                                    ui.cropAspect = RaftComposerImageCropAspect.Free
                                    ui.transientCrop = RaftComposerImageCropRect.Uncropped
                                },
                                onApply = ::applyCrop,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The on-screen size of a canvas of [canvas] turned by [turns]. */
internal fun raftComposerImageTurnedSize(canvas: Size, turns: Int): Size =
    if (raftComposerImageTurnSwapsSides(turns)) Size(canvas.height, canvas.width) else canvas

@Composable
private fun RaftComposerImageDesktopToolbar(
    tool: RaftComposerImageEditorTool?,
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
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth().height(RaftComposerImageDesktopBarHeight).padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RaftToggleGroup {
                listOf(
                    Triple(RaftComposerImageEditorTool.Pencil, Res.string.file_preview_toolbar_pen, RaftLucideIconName.Pencil),
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
            RaftComposerImageDesktopDivider()
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

@Composable
private fun RaftComposerImageDesktopDivider() {
    Box(modifier = Modifier.size(width = 1.dp, height = 24.dp).background(Color(0x40FFFFFF))) // raft-color: divider on the photo viewer's dark bar
}

@Composable
private fun RaftComposerImageDesktopPenOptions(ui: RaftComposerImageDesktopEditorUi) {
    val penLabel = stringResource(Res.string.file_preview_toolbar_pen)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        RaftComposerImagePenWidth.entries.forEach { choice ->
            RaftFilePreviewPenWidthChoice(
                dotSize = if (choice == RaftComposerImagePenWidth.Thin) 6.dp else 13.dp,
                selected = ui.penWidth == choice,
                enabled = true,
                label =
                    stringResource(
                        if (choice == RaftComposerImagePenWidth.Thin) Res.string.file_preview_pen_width_thin else Res.string.file_preview_pen_width_thick
                    ),
                onClick = { ui.penWidth = choice },
            )
        }
        RaftComposerImageDesktopDivider()
        RaftComposerImageEditorPenColors.forEachIndexed { index, color ->
            RaftFilePreviewColorSwatch(
                color = color,
                selected = ui.penColor == color,
                enabled = true,
                label = "$penLabel ${index + 1}",
                onClick = { ui.penColor = color },
            )
        }
    }
}

@Composable
private fun RaftComposerImageDesktopCropOptions(
    ui: RaftComposerImageDesktopEditorUi,
    onSelectAspect: (RaftComposerImageCropAspect) -> Unit,
    onReset: () -> Unit,
    onApply: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        RaftSegmentedControl {
            RaftComposerImageCropAspect.entries.forEach { choice ->
                RaftSegment(
                    text =
                        stringResource(
                            when (choice) {
                                RaftComposerImageCropAspect.Free -> Res.string.file_preview_crop_aspect_free
                                RaftComposerImageCropAspect.Square -> Res.string.file_preview_crop_aspect_square
                                RaftComposerImageCropAspect.Original -> Res.string.file_preview_crop_aspect_original
                            }
                        ),
                    selected = ui.cropAspect == choice,
                    onClick = { onSelectAspect(choice) },
                )
            }
        }
        RaftButton(text = stringResource(Res.string.file_preview_crop_reset), onClick = onReset, variant = RaftButtonVariant.Ghost)
        RaftButton(text = stringResource(Res.string.file_preview_crop_apply), onClick = onApply)
    }
}
