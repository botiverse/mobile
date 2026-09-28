@file:OptIn(ExperimentalFoundationApi::class)

package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.tencent.kuikly.compose.coil3.AsyncImagePainter
import com.tencent.kuikly.compose.coil3.rememberAsyncImageBitmap
import com.tencent.kuikly.compose.extension.RefFunc
import com.tencent.kuikly.compose.extension.keyboardHeightChange
import com.tencent.kuikly.compose.extension.nativeRef
import com.tencent.kuikly.compose.extension.setProp
import com.tencent.kuikly.compose.coil3.rememberAsyncImagePainter
import com.tencent.kuikly.compose.foundation.ExperimentalFoundationApi
import com.tencent.kuikly.compose.foundation.Image
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.gestures.awaitEachGesture
import com.tencent.kuikly.compose.foundation.gestures.awaitFirstDown
import com.tencent.kuikly.compose.foundation.gestures.calculatePan
import com.tencent.kuikly.compose.foundation.gestures.calculateZoom
import com.tencent.kuikly.compose.foundation.gestures.detectTapGestures
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.absoluteOffset
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.lazy.LazyItemScope
import com.tencent.kuikly.compose.foundation.lazy.LazyListScope
import com.tencent.kuikly.compose.foundation.lazy.LazyListState
import com.tencent.kuikly.compose.foundation.shape.RoundedCornerShape
import com.tencent.kuikly.compose.foundation.text.BasicTextField
import com.tencent.kuikly.compose.ui.text.input.VisualTransformation
import com.tencent.kuikly.compose.foundation.text.KeyboardActions
import com.tencent.kuikly.compose.foundation.text.KeyboardOptions
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.clip
import com.tencent.kuikly.compose.ui.draw.clipToBounds
import com.tencent.kuikly.compose.ui.draw.scale
import com.tencent.kuikly.compose.ui.focus.FocusRequester
import com.tencent.kuikly.compose.ui.focus.focusRequester
import com.tencent.kuikly.compose.ui.focus.onFocusChanged
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.geometry.Rect
import com.tencent.kuikly.compose.ui.graphics.Brush
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.graphics.SolidColor
import com.tencent.kuikly.compose.ui.input.pointer.PointerId
import com.tencent.kuikly.compose.ui.input.pointer.PointerEventPass
import com.tencent.kuikly.compose.ui.input.pointer.pointerInput
import com.tencent.kuikly.compose.ui.input.pointer.positionChanged
import com.tencent.kuikly.compose.ui.input.pointer.positionChangedIgnoreConsumed
import com.tencent.kuikly.compose.ui.layout.ContentScale
import com.tencent.kuikly.compose.ui.layout.LayoutCoordinates
import com.tencent.kuikly.compose.ui.layout.boundsInRoot
import com.tencent.kuikly.compose.ui.layout.layout
import com.tencent.kuikly.compose.ui.layout.onGloballyPositioned
import com.tencent.kuikly.compose.ui.layout.onSizeChanged
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.platform.LocalDensity
import com.tencent.kuikly.compose.ui.platform.LocalFocusManager
import com.tencent.kuikly.compose.ui.semantics.Role
import com.tencent.kuikly.compose.ui.semantics.disabled
import com.tencent.kuikly.compose.ui.semantics.onLongClick
import com.tencent.kuikly.compose.ui.semantics.onClick
import com.tencent.kuikly.compose.ui.semantics.role
import com.tencent.kuikly.compose.ui.semantics.semantics
import com.tencent.kuikly.compose.ui.text.TextLayoutResult
import com.tencent.kuikly.compose.ui.text.TextRange
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.input.TextFieldValue
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.IntOffset
import com.tencent.kuikly.compose.ui.unit.IntSize
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.max
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.compose.ui.window.Dialog
import com.tencent.kuikly.compose.ui.window.DialogProperties
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.DeclarativeBaseView
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.event.EventHandlerFn
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.views.AutoHeightTextAreaView
import com.tencent.kuikly.core.views.KeyboardParams
import com.tencent.kuikly.core.views.ModalView
import com.tencent.kuikly.core.views.TextInputState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

fun Modifier.backgroundColor(color: Color): Modifier {
    return this.background(color)
}

/** Neutral fallback shown before or around image preview content. */
internal val SlockImagePreviewLetterboxColor: Color = SlockColor.SurfaceWhite

/** Thumbnail contract: preserve ratio while filling the fixed preview frame. */
internal val SlockImageThumbnailContentScale: ContentScale = ContentScale.Crop

internal data class SlockImagePreviewLoadingPresentation(
    val fallbackUnderlaySource: String?,
    val showLoadingOverlay: Boolean
)

internal data class SlockImagePreviewRenderIdentity(
    val source: String,
    val decodedWidth: Int,
    val decodedHeight: Int
)

internal fun slockImagePreviewRenderIdentity(
    source: String,
    decodedWidth: Int,
    decodedHeight: Int
): SlockImagePreviewRenderIdentity =
    SlockImagePreviewRenderIdentity(
        source = source,
        decodedWidth = decodedWidth.coerceAtLeast(0),
        decodedHeight = decodedHeight.coerceAtLeast(0)
    )

internal fun resolveSlockImagePreviewRenderIdentity(
    currentIdentity: SlockImagePreviewRenderIdentity?,
    source: String,
    decodedWidth: Int,
    decodedHeight: Int,
    activeSucceeded: Boolean
): SlockImagePreviewRenderIdentity {
    val normalizedSource = source.trim()
    if (currentIdentity != null && currentIdentity.source == normalizedSource) {
        if (activeSucceeded) {
            return currentIdentity
        }
        if (currentIdentity.decodedWidth > 0 && currentIdentity.decodedHeight > 0) {
            return currentIdentity
        }
    }
    return slockImagePreviewRenderIdentity(
        source = normalizedSource,
        decodedWidth = decodedWidth,
        decodedHeight = decodedHeight
    )
}

private class SlockImagePreviewIdentityHolder(
    var identity: SlockImagePreviewRenderIdentity
)

private class SlockImagePreviewSourceOutcome(
    var failed: Boolean = false
)

@Composable
internal fun SlockImagePreviewRenderSlot(
    identity: SlockImagePreviewRenderIdentity,
    content: @Composable () -> Unit
) {
    key(identity) {
        content()
    }
}

internal fun slockImagePreviewLoadingPresentation(
    activeSource: String?,
    fallbackSource: String?,
    showLoadingIndicator: Boolean,
    activeSucceeded: Boolean,
    activeFailed: Boolean,
    previousRenderedSource: String? = null,
    previousPresentableSource: String? = null
): SlockImagePreviewLoadingPresentation {
    val active = activeSource?.trim()?.takeIf { it.isNotBlank() }
    val fallback = fallbackSource?.trim()?.takeIf { it.isNotBlank() }
    val previous = previousRenderedSource?.trim()?.takeIf { it.isNotBlank() && it != active }
    // A departed source that was still loading when the swap happened never
    // reached Success, but its fetch/decode keeps running under the underlay
    // image; showing it (or at least suppressing the spinner) avoids the
    // second loading flash when hydration resolves faster than the first
    // decode (task #287).
    val previousPending =
        previousPresentableSource
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != active && it != previous }
    // Prefer the previous successfully rendered frame as the underlay during a
    // source swap: it is already decoded, so the surface keeps pixels on screen
    // while the new source is still loading instead of flashing blank.
    val underlay = (previous ?: previousPending ?: fallback)?.takeIf { it != active }
    val keepsContentVisible =
        previous != null || previousPending != null ||
            (fallback != null && !(active == fallback && activeFailed))
    return SlockImagePreviewLoadingPresentation(
        fallbackUnderlaySource = underlay.takeUnless { activeSucceeded },
        showLoadingOverlay =
            showLoadingIndicator &&
                !activeSucceeded &&
                !keepsContentVisible
    )
}

@Composable
fun SlockCachedImage(
    source: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    onImageStateChange: ((AsyncImagePainter.State) -> Unit)? = null
) {
    val painter = rememberAsyncImagePainter(source)
    if (onImageStateChange != null) {
        val imageState by painter.state.collectAsState()
        val stateCallback by rememberUpdatedState(onImageStateChange)
        LaunchedEffect(imageState) {
            stateCallback.invoke(imageState)
        }
    }
    Image(
        painter = painter,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale
    )
}

@Composable
internal fun SlockAspectFitImagePreviewSurface(
    source: String?,
    fallbackSource: String? = null,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    placeholderLabel: String = "IMG",
    backgroundColor: Color = SlockImagePreviewLetterboxColor,
    borderWidth: Dp = SlockStroke.Default,
    contentScale: ContentScale = ContentScale.Fit,
    zoomable: Boolean = false,
    showLoadingIndicator: Boolean = false,
    onImageStateChange: ((AsyncImagePainter.State) -> Unit)? = null
) {
    val normalizedSource = source?.trim()?.takeIf { it.isNotBlank() }
    val normalizedFallback = fallbackSource?.trim()?.takeIf { it.isNotBlank() && it != normalizedSource }
    var activeSource by remember(normalizedSource, normalizedFallback) { mutableStateOf(normalizedSource ?: normalizedFallback) }
    var imageState by remember(activeSource) { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
    // The last source that reached AsyncImagePainter.State.Success. Kept as the
    // underlay when `source`/`fallbackSource` changes so a resolved full-size
    // URL swaps in over the already-rendered thumbnail instead of flashing a
    // blank frame (task #282).
    var lastSucceededSource by remember { mutableStateOf<String?>(null) }
    // task #287: a source that departs while still loading (hydration resolved
    // before its first Success) is also a valid underlay — its fetch keeps
    // running under the new frame and the spinner must not flash a second
    // time. Sources that departed in Error are excluded so a dead frame is
    // never pinned underneath.
    var lastPresentableSource by remember { mutableStateOf<String?>(null) }
    val activeSourceOutcome = remember(activeSource) {
        SlockImagePreviewSourceOutcome()
    }
    DisposableEffect(activeSource) {
        val departed = activeSource
        val outcome = activeSourceOutcome
        onDispose {
            if (departed != null && !outcome.failed) {
                lastPresentableSource = departed
            }
        }
    }
    Box(
        modifier = modifier
            .background(backgroundColor)
            .border(borderWidth, SlockColor.BorderBlack),
        contentAlignment = Alignment.Center
    ) {
        if (activeSource != null) {
            val activeBitmap = rememberAsyncImageBitmap(activeSource)
            val currentSource = activeSource.orEmpty()
            val activeSucceeded = imageState is AsyncImagePainter.State.Success
            val identityHolder = remember(activeSource) {
                SlockImagePreviewIdentityHolder(
                    slockImagePreviewRenderIdentity(
                        source = currentSource,
                        decodedWidth = activeBitmap?.width ?: 0,
                        decodedHeight = activeBitmap?.height ?: 0
                    )
                )
            }
            val renderIdentity =
                resolveSlockImagePreviewRenderIdentity(
                    currentIdentity = identityHolder.identity,
                    source = currentSource,
                    decodedWidth = activeBitmap?.width ?: 0,
                    decodedHeight = activeBitmap?.height ?: 0,
                    activeSucceeded = activeSucceeded
                ).also { identityHolder.identity = it }
            val loadingPresentation =
                slockImagePreviewLoadingPresentation(
                    activeSource = activeSource,
                    fallbackSource = normalizedFallback,
                    showLoadingIndicator = showLoadingIndicator,
                    activeSucceeded = activeSucceeded,
                    activeFailed = imageState is AsyncImagePainter.State.Error,
                    previousRenderedSource = lastSucceededSource,
                    previousPresentableSource = lastPresentableSource
                )
            loadingPresentation.fallbackUnderlaySource?.let { underlaySource ->
                SlockCachedImage(
                    source = underlaySource,
                    contentDescription = contentDescription,
                    contentScale = contentScale,
                    modifier = Modifier.fillMaxSize()
                )
            }
            SlockImagePreviewRenderSlot(renderIdentity) {
                if (zoomable) {
                    SlockZoomableCachedImage(
                        source = activeSource.orEmpty(),
                        contentDescription = contentDescription,
                        modifier = Modifier.fillMaxSize(),
                        onImageStateChange = { state ->
                            imageState = state
                            onImageStateChange?.invoke(state)
                            if (state is AsyncImagePainter.State.Success) {
                                lastSucceededSource = activeSource
                            }
                            if (state is AsyncImagePainter.State.Error) {
                                activeSourceOutcome.failed = true
                                if (activeSource != normalizedFallback && normalizedFallback != null) {
                                    activeSource = normalizedFallback
                                }
                            }
                        }
                    )
                } else {
                    SlockCachedImage(
                        source = activeSource,
                        contentDescription = contentDescription,
                        contentScale = contentScale,
                        modifier = Modifier.fillMaxSize(),
                        onImageStateChange = { state ->
                            imageState = state
                            onImageStateChange?.invoke(state)
                            if (state is AsyncImagePainter.State.Success) {
                                lastSucceededSource = activeSource
                            }
                            if (state is AsyncImagePainter.State.Error) {
                                activeSourceOutcome.failed = true
                                if (activeSource != normalizedFallback && normalizedFallback != null) {
                                    activeSource = normalizedFallback
                                }
                            }
                        }
                    )
                }
            }
            if (loadingPresentation.showLoadingOverlay) {
                SlockImagePreviewLoadingOverlay(
                    failed = imageState is AsyncImagePainter.State.Error,
                    placeholderLabel = placeholderLabel,
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            Text(
                text = placeholderLabel,
                style = SlockType.Button.copy(fontSize = 11.sp, color = SlockColor.TextBlack),
                color = SlockColor.TextBlack,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SlockZoomableCachedImage(
    source: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    onImageStateChange: ((AsyncImagePainter.State) -> Unit)? = null
) {
    var scale by remember(source) { mutableStateOf(1f) }
    var pan by remember(source) { mutableStateOf(Offset.Zero) }
    var viewportSize by remember(source) { mutableStateOf(IntSize.Zero) }

    fun clampedPan(candidate: Offset, currentScale: Float): Offset {
        if (currentScale <= 1f || viewportSize.width == 0 || viewportSize.height == 0) {
            return Offset.Zero
        }
        val maxX = viewportSize.width * (currentScale - 1f) / 2f
        val maxY = viewportSize.height * (currentScale - 1f) / 2f
        return Offset(
            x = candidate.x.coerceIn(-maxX, maxX),
            y = candidate.y.coerceIn(-maxY, maxY)
        )
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { viewportSize = it }
            .pointerInput(source) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var pastTouchSlop = false
                    val touchSlop = viewConfiguration.touchSlop
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.any { it.isConsumed }) {
                            break
                        }
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        val shouldHandle =
                            zoomChange != 1f ||
                                scale > 1f
                        if (!pastTouchSlop && shouldHandle) {
                            val zoomMotion = abs(1f - zoomChange) * max(viewportSize.width, viewportSize.height)
                            val panMotion = panChange.getDistance()
                            pastTouchSlop = zoomMotion > touchSlop || panMotion > touchSlop
                        }
                        if (pastTouchSlop && shouldHandle) {
                            val nextScale = (scale * zoomChange).coerceIn(1f, 4f)
                            scale = nextScale
                            pan = clampedPan(pan + panChange, nextScale)
                            event.changes.forEach {
                                if (it.positionChanged()) {
                                    it.consume()
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center
    ) {
        SlockCachedImage(
            source = source,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            onImageStateChange = onImageStateChange,
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(pan.x.roundToInt(), pan.y.roundToInt()) }
                .scale(scale)
        )
    }
}

@Composable
private fun SlockImagePreviewLoadingOverlay(
    failed: Boolean,
    placeholderLabel: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        if (failed) {
            Text(
                text = placeholderLabel,
                style = SlockType.Button.copy(fontSize = 11.sp, color = SlockColor.TextBlack),
                color = SlockColor.TextBlack,
                maxLines = 1
            )
        } else {
            SlockHeaderRefreshIndicator()
        }
    }
}

fun Modifier.margin(
    start: Float = 0f,
    top: Float = 0f,
    end: Float = 0f,
    bottom: Float = 0f
): Modifier {
    val ntop = max(0f, top)
    return this.padding(
        start = start.dp,
        top = ntop.dp,
        end = end.dp,
        bottom = bottom.dp
    )
}

//@Deprecated(message = "Use Image(painter, contentDescription) instead")
//@Composable
//@NonRestartableComposable
//fun Image(
//    src: String? = null,
//    contentDescription: String? = null,
//    modifier: Modifier = Modifier,
//    alignment: Alignment = Alignment.Center,
//    contentScale: ContentScale = ContentScale.Fit,
//    alpha: Float = DefaultAlpha,
//    placeholderSrc: Any? = null,
//    borderRadius: Float = 0f,
//) {
//    val placeholder = when (placeholderSrc) {
//        is Painter -> placeholderSrc
//        is DrawableResource -> painterResource(placeholderSrc)
//        is String -> rememberAsyncImagePainter(placeholderSrc)
//        else -> null
//    }
//    val painter = if (src.isNullOrEmpty()) {
//        ColorPainter(Color.Transparent)
//    } else {
//        rememberAsyncImagePainter(src, placeholder = placeholder)
//    }
//    Image(
//        painter = painter,
//        contentDescription = contentDescription,
//        modifier = if (borderRadius > 0f) modifier.clip(RoundedCornerShape(borderRadius)) else modifier,
//        alignment = alignment,
//        contentScale = contentScale,
//        alpha = alpha,
//    )
//}

fun Modifier.margin(
    start: Dp = 0.dp,
    top: Dp = 0.dp,
    end: Dp = 0.dp,
    bottom: Dp = 0.dp
): Modifier {
    val ntop = max(0.dp, top)
    return this.padding(
        start = start,
        top = ntop,
        end = end,
        bottom = bottom
    )
}

fun Modifier.padding(
    start: Float = 0f,
    top: Float = 0f,
    end: Float = 0f,
    bottom: Float = 0f
): Modifier {
    return this.padding(
        start = start.dp,
        top = top.dp,
        end = end.dp,
        bottom = bottom.dp
    )
}

fun Modifier.height(height: Float = 0f): Modifier {
    return this.height(height.dp)
}

fun Modifier.width(width: Float = 0f): Modifier {
    return this.width(width.dp)
}

fun Modifier.absoluteOffset(x: Float, y: Float): Modifier {
    return this.absoluteOffset(x.dp, y.dp)
}

fun Modifier.offset(x: Float, y: Float): Modifier {
    return this.offset(x.dp, y.dp)
}

fun Modifier.offsetWithParentAdjustment(
    x: Dp = 0.dp,
    y: Dp = 0.dp
) = this.layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val xPx = x.roundToPx()
    val yPx = y.roundToPx()

    // 计算实际需要的宽度和高度
    val width = placeable.width + xPx
    val height = placeable.height + yPx

    layout(width, height) {
        placeable.placeRelative(xPx, yPx)
    }
}

fun LazyListScope.Hover(
    modifier: Modifier,
    key: Any? = null,
    hoverMarginTop: Dp = 0.dp,
    listState: LazyListState,
    content: @Composable LazyItemScope.() -> Unit
) {
    stickyHeaderWithMarginTop(
        key = key,
        hoverMarginTop = hoverMarginTop,
        listState = listState
    ) {
        Box(modifier = modifier) {
            content()
        }
    }
}

fun Modifier.borderRadius(radius: Dp) = clip(RoundedCornerShape(radius))

internal fun Modifier.slockSquareCorners(): Modifier =
    setProp(SlockSquareCornerBorderRadiusProp, SlockSquareCornerBorderRadiusValue)
        .setProp(SlockSquareCornerClipPathProp, "")

/**
 * Register-driven corner rounding on the same Kuikly borderRadius prop path as
 * [slockSquareCorners]. At [radius] == 0.dp this emits the exact same
 * "0.0,0.0,0.0,0.0" string as [slockSquareCorners], so brutal callers (whose
 * theme corner radius is [SlockRadius.None]) render byte-identically; the elegant
 * register passes its rounded radius and the same prop path rounds the corners.
 * Kept on the prop mechanism (not clip(RoundedCornerShape)) so migrating a
 * component from [slockSquareCorners] to this is a no-op in the brutal default.
 */
internal fun Modifier.slockThemedCorners(radius: Dp): Modifier {
    val r = radius.value
    return setProp(SlockSquareCornerBorderRadiusProp, "$r,$r,$r,$r")
        .setProp(SlockSquareCornerClipPathProp, "")
}

private const val SlockSquareCornerBorderRadiusProp = "borderRadius"
private const val SlockSquareCornerBorderRadiusValue = "0.0,0.0,0.0,0.0"
private const val SlockSquareCornerClipPathProp = "clipPath"

fun Color.changeAlpha(alpha: Float) = this.copy(alpha = alpha)

/**
 * 实现类似 iOS viewWillAppear 事件的 Modifier 扩展
 * @param onAppear 组件首次组合时触发的回调（仅执行一次）
 * @param key 可选控制触发条件的重组键值
 */
@Composable
fun Modifier.willAppear(onAppear: () -> Unit): Modifier {
    var hasAppeared by remember { mutableStateOf(false) }
    if (!hasAppeared) {
        return this.onGloballyPositioned {
            if (!hasAppeared) {
                hasAppeared = true
                onAppear()
            }
        }
    } else {
        return this
    }
}

/**
 * Observes one pointer sequence without consuming it.
 *
 * The callback stays current without restarting [pointerInput], and consumed
 * position deltas remain visible so a scrollable cannot hide the rest of a
 * physical drag after touch slop.
 */
@Composable
fun Modifier.touchListener(
    onTouchEvent: (type: TouchType, position: Offset) -> Unit
): Modifier =
    touchListenerWithConfiguration { type, position, _ ->
        onTouchEvent(type, position)
    }

/**
 * Clears the current input focus after a gesture starts outside every focused standard input.
 *
 * Scroll and clickable containers may consume their pointer changes during the Main pass, so the
 * shared host observes every change during Initial. An outside down only arms dismissal: a
 * tap, drag, or long press commits focus dismissal only at terminal pointer release. Deferring the
 * layout-affecting clear keeps IME relayout from canceling a descendant's in-flight gesture. The
 * host never consumes the gesture that owns the eventual child action or scroll.
 */
@Composable
internal fun Modifier.dismissInputFocusOnOutsidePointerDown(
    registry: SlockInputFocusOwnerRegistry,
    enabled: Boolean = true,
): Modifier {
    val currentFocusManager by rememberUpdatedState(LocalFocusManager.current)
    val currentEnabled by rememberUpdatedState(enabled)
    val hostAnchor = remember { SlockInputFocusRootAnchor() }
    val focusDismissScope = rememberCoroutineScope()
    return onGloballyPositioned(hostAnchor::update).pointerInput(registry) {
        awaitEachGesture {
            val touchTracker = SlockTouchSequenceTracker<PointerId>()
            val dismissTracker = SlockInputFocusDismissGestureTracker()
            var multiplePointers = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (
                    isNativeInputFocusDismissCancellation(
                        nativeEvent = event.nativeEvent,
                        hasTerminalChange = event.changes.any { it.previousPressed && !it.pressed }
                    )
                ) {
                    dismissTracker.cancel()
                    registry.invalidatePointerFocusClearCandidates()
                    awaitPointerEvent(PointerEventPass.Main)
                    return@awaitEachGesture
                }
                if (event.changes.count { it.pressed || it.previousPressed } > 1) {
                    multiplePointers = true
                    dismissTracker.cancel()
                    registry.blockMultiPointerFocusClear()
                }
                if (multiplePointers) {
                    if (event.changes.none { it.pressed }) {
                        awaitPointerEvent(PointerEventPass.Main)
                        registry.unblockMultiPointerFocusClear()
                        return@awaitEachGesture
                    }
                    continue
                }
                var terminalDismissToken: SlockInputFocusDismissToken? = null
                var sawTrackedUp = false
                event.changes.forEach { change ->
                    val type =
                        touchTracker.onChange(
                            pointerId = change.id,
                            pressed = change.pressed,
                            positionChanged = change.positionChangedIgnoreConsumed()
                        ) ?: return@forEach
                    if (type == TouchType.Down) {
                        // Initial reaches this stable page root before descendant/local Main.
                        // Starting a new epoch here invalidates delayed work from the previous
                        // gesture while giving both observers the same token for this gesture.
                        registry.beginPointerFocusClearSequence()
                    }
                    val dismissToken =
                        if (type == TouchType.Down && currentEnabled) {
                            hostAnchor.resolve(change.position)
                                ?.let(registry::outsideFocusToken)
                        } else {
                            null
                        }
                    dismissTracker.onTouch(
                        type = type,
                        dismissToken = dismissToken
                    )?.let { terminalDismissToken = it }
                    if (type == TouchType.Up) {
                        sawTrackedUp = true
                    }
                }
                if (sawTrackedUp) {
                    // Descendants finish tap/long-press handling first during Main. Clearing in
                    // Initial would allow IME relayout to detach or move that child mid-dispatch.
                    awaitPointerEvent(PointerEventPass.Main)
                    terminalDismissToken?.let { dismissToken ->
                        focusDismissScope.launch {
                            // Thread message body taps intentionally settle rich-text child
                            // arbitration on the next frame. Two frames keep that child-owned
                            // callback ahead of any blur-driven list relayout regardless of
                            // frame callback ordering.
                            withFrameNanos { }
                            withFrameNanos { }
                            if (registry.claimFocusClear(dismissToken)) {
                                currentFocusManager.clearFocus(force = true)
                                withFrameNanos { }
                                withFrameNanos { }
                                registry.releaseUnreconciledFocusClearClaim(dismissToken)
                            }
                        }
                    }
                    return@awaitEachGesture
                }
            }
        }
    }
}

internal class SlockInputFocusOwnerAnchor {
    private var coordinates: LayoutCoordinates? = null

    fun update(next: LayoutCoordinates) {
        coordinates = next
    }

    fun resolveBounds(): Rect? =
        coordinates
            ?.takeIf { it.isAttached }
            ?.boundsInRoot()
}

internal class SlockInputFocusOwnerRegistry {
    private val activeOwners = mutableMapOf<Any, () -> Rect?>()
    private val retainedInteractionRegions = mutableMapOf<Any, () -> Rect?>()
    private var focusOwnerGeneration: Long = 0L
    private var pointerSafetyGeneration: Long = 0L
    private var multiPointerFocusClearBlocked: Boolean = false
    private var claimedFocusClearToken: SlockInputFocusDismissToken? = null
    var hasActiveOwner by mutableStateOf(false)
        private set

    fun setFocused(owner: Any, focused: Boolean, bounds: (() -> Rect?)? = null) {
        if (focused) {
            val wasActive = activeOwners.containsKey(owner)
            activeOwners[owner] = bounds ?: { null }
            if (!wasActive) {
                advanceFocusOwnerGeneration()
            }
        } else {
            if (activeOwners.remove(owner) != null) {
                advanceFocusOwnerGeneration()
            }
        }
        hasActiveOwner = activeOwners.isNotEmpty()
    }

    /** Returns the current focused editor bounds in root coordinates, when still attached. */
    fun focusedBounds(): Rect? = activeOwners.values.firstNotNullOfOrNull { it() }

    fun shouldClearFocus(position: Offset): Boolean {
        if (activeOwners.isEmpty()) return false
        activeOwners.values.forEach { resolveBounds ->
            val bounds = resolveBounds() ?: return false
            if (bounds.containsInclusive(position)) {
                return false
            }
        }
        retainedInteractionRegions.values.forEach { resolveBounds ->
            val bounds = resolveBounds() ?: return@forEach
            if (bounds.containsInclusive(position)) return false
        }
        return true
    }

    fun retainFocusForInteraction(owner: Any, bounds: () -> Rect?) {
        retainedInteractionRegions[owner] = bounds
    }

    fun removeRetainedInteraction(owner: Any) {
        retainedInteractionRegions.remove(owner)
    }

    fun outsideFocusToken(position: Offset): SlockInputFocusDismissToken? {
        if (multiPointerFocusClearBlocked || !shouldClearFocus(position)) return null
        return SlockInputFocusDismissToken(
            focusOwnerGeneration = focusOwnerGeneration,
            pointerSafetyGeneration = pointerSafetyGeneration
        )
    }

    fun beginPointerFocusClearSequence() {
        if (!multiPointerFocusClearBlocked) {
            pointerSafetyGeneration += 1L
            claimedFocusClearToken = null
        }
    }

    fun claimFocusClear(token: SlockInputFocusDismissToken): Boolean {
        if (
            activeOwners.isEmpty() ||
            multiPointerFocusClearBlocked ||
            focusOwnerGeneration != token.focusOwnerGeneration ||
            pointerSafetyGeneration != token.pointerSafetyGeneration ||
            claimedFocusClearToken == token
        ) {
            return false
        }
        claimedFocusClearToken = token
        return true
    }

    fun releaseUnreconciledFocusClearClaim(token: SlockInputFocusDismissToken) {
        if (
            activeOwners.isNotEmpty() &&
            focusOwnerGeneration == token.focusOwnerGeneration &&
            pointerSafetyGeneration == token.pointerSafetyGeneration &&
            claimedFocusClearToken == token
        ) {
            claimedFocusClearToken = null
        }
    }

    fun blockMultiPointerFocusClear() {
        if (!multiPointerFocusClearBlocked) {
            multiPointerFocusClearBlocked = true
            pointerSafetyGeneration += 1L
            claimedFocusClearToken = null
        }
    }

    fun unblockMultiPointerFocusClear() {
        multiPointerFocusClearBlocked = false
    }

    fun invalidatePointerFocusClearCandidates() {
        pointerSafetyGeneration += 1L
        multiPointerFocusClearBlocked = false
        claimedFocusClearToken = null
    }

    /**
     * Cancels an outside-gesture dismissal that was armed before a descendant established a new
     * explicit editing intent. The current gesture may still finish normally, but its old token
     * must not clear the focus that the descendant just reasserted.
     */
    fun cancelPendingFocusDismissalForExplicitInputIntent() {
        pointerSafetyGeneration += 1L
        claimedFocusClearToken = null
    }

    fun remove(owner: Any) {
        if (activeOwners.remove(owner) != null) {
            advanceFocusOwnerGeneration()
        }
        hasActiveOwner = activeOwners.isNotEmpty()
    }

    private fun advanceFocusOwnerGeneration() {
        focusOwnerGeneration += 1L
        claimedFocusClearToken = null
    }
}

private fun Rect.containsInclusive(position: Offset): Boolean =
    position.x >= left &&
        position.x <= right &&
        position.y >= top &&
        position.y <= bottom

internal data class SlockInputFocusDismissToken(
    val focusOwnerGeneration: Long,
    val pointerSafetyGeneration: Long
)

private const val SlockKuiklyNativeCancelEvent = "cancel"

/**
 * Kuikly's `SuperTouchManager` tags a native touch-cancel as
 * the literal `cancel` event while normal physical Up remains untagged, including after a native
 * scroll gesture. A cancel terminates the Compose hit path but is not proof that the finger lifted,
 * so it must invalidate pending focus dismissal instead of being folded into [TouchType.Up].
 */
internal fun isNativeInputFocusDismissCancellation(
    nativeEvent: Any?,
    hasTerminalChange: Boolean
): Boolean = nativeEvent == SlockKuiklyNativeCancelEvent && hasTerminalChange

internal val LocalSlockInputFocusOwnerRegistry =
    compositionLocalOf<SlockInputFocusOwnerRegistry?> { null }

/**
 * Resolves focused-input geometry from the registry that is current where this resolver is
 * composed. Modal installs its own focus-dismiss host, so callers must acquire this resolver from
 * inside modal content rather than capture the page-level registry before entering [Modal].
 */
@Composable
internal fun rememberSlockFocusedInputBoundsResolver(): () -> Rect? {
    val currentRegistry = rememberUpdatedState(LocalSlockInputFocusOwnerRegistry.current)
    return remember { { currentRegistry.value?.focusedBounds() } }
}

/**
 * Marks a composed interaction surface as belonging to the currently focused input experience.
 *
 * A focused field remains the authority that enables global outside-focus dismissal. This modifier
 * only contributes another root-coordinate region where descendant taps and drags must preserve
 * that focus, such as a composer's toolbar or suggestion list. It does not consume pointer input,
 * request focus, or keep an unfocused field active.
 */
@Composable
internal fun Modifier.retainInputFocusForInteractions(): Modifier {
    val registry = LocalSlockInputFocusOwnerRegistry.current ?: return this
    val interactionOwner = remember { Any() }
    val interactionAnchor = remember { SlockInputFocusOwnerAnchor() }
    DisposableEffect(registry, interactionOwner) {
        registry.retainFocusForInteraction(interactionOwner, interactionAnchor::resolveBounds)
        onDispose { registry.removeRetainedInteraction(interactionOwner) }
    }
    return onGloballyPositioned(interactionAnchor::update)
}

/**
 * Arbitrates focus dismissal without changing layout during a stationary child gesture.
 *
 * [dismissToken] is sampled only for [TouchType.Down], after the caller resolves that down
 * into root coordinates and verifies it is outside the active input. Tap, drag, and long-press
 * sequences all dispatch once at terminal release, after descendants have finished their pointer
 * gesture. An inside-input gesture never arms, even if it later leaves the input bounds.
 */
internal class SlockInputFocusDismissGestureTracker {
    private var tracking: Boolean = false
    private var armedDismissToken: SlockInputFocusDismissToken? = null

    fun onTouch(
        type: TouchType,
        dismissToken: SlockInputFocusDismissToken? = null
    ): SlockInputFocusDismissToken? =
        when (type) {
            TouchType.Down -> {
                tracking = true
                armedDismissToken = dismissToken
                null
            }

            TouchType.Move -> null

            TouchType.Up -> {
                val token = armedDismissToken.takeIf { tracking }
                tracking = false
                armedDismissToken = null
                token
            }
        }

    fun cancel() {
        tracking = false
        armedDismissToken = null
    }
}

/**
 * Shared non-consuming outside-focus observer for native-backed scrollable chrome. Attaching
 * directly to the scroll node is required on Kuikly/iOS because the native scroll host does not
 * relay its consumed pointer stream to an ancestor Compose pointerInput. The local down is resolved
 * into root coordinates before checking every active input bound, so inputs nested inside the same
 * scroll node preserve cursor and selection behavior.
 */
@Composable
internal fun Modifier.dismissInputFocusOnScrollableChrome(): Modifier {
    val focusOwnerRegistry = LocalSlockInputFocusOwnerRegistry.current
    val currentFocusManager by rememberUpdatedState(LocalFocusManager.current)
    val scrollAnchor = remember { SlockInputFocusRootAnchor() }
    val focusDismissScope = rememberCoroutineScope()
    return onGloballyPositioned(scrollAnchor::update).pointerInput(focusOwnerRegistry) {
        val registry = focusOwnerRegistry ?: return@pointerInput
        awaitEachGesture {
            val touchTracker = SlockTouchSequenceTracker<PointerId>()
            val dismissTracker = SlockInputFocusDismissGestureTracker()
            var multiplePointers = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                if (
                    isNativeInputFocusDismissCancellation(
                        nativeEvent = event.nativeEvent,
                        hasTerminalChange = event.changes.any { it.previousPressed && !it.pressed }
                    )
                ) {
                    dismissTracker.cancel()
                    return@awaitEachGesture
                }
                if (event.changes.count { it.pressed || it.previousPressed } > 1) {
                    multiplePointers = true
                    dismissTracker.cancel()
                }
                var terminalDismissToken: SlockInputFocusDismissToken? = null
                var sawTrackedUp = false
                event.changes.forEach { change ->
                    val type =
                        touchTracker.onChange(
                            pointerId = change.id,
                            pressed = change.pressed,
                            positionChanged = change.positionChangedIgnoreConsumed()
                        ) ?: return@forEach
                    val dismissToken =
                        if (type == TouchType.Down) {
                            scrollAnchor.resolve(change.position)
                                ?.let(registry::outsideFocusToken)
                        } else {
                            null
                        }
                    dismissTracker.onTouch(
                        type = type,
                        dismissToken = dismissToken
                    )?.let { terminalDismissToken = it }
                    if (type == TouchType.Up) {
                        sawTrackedUp = true
                    }
                }
                if (sawTrackedUp) {
                    terminalDismissToken
                        ?.takeUnless { multiplePointers }
                        ?.let { dismissToken ->
                            focusDismissScope.launch {
                                withFrameNanos { }
                                withFrameNanos { }
                                if (registry.claimFocusClear(dismissToken)) {
                                    currentFocusManager.clearFocus(force = true)
                                    withFrameNanos { }
                                    withFrameNanos { }
                                    registry.releaseUnreconciledFocusClearClaim(dismissToken)
                                }
                            }
                        }
                    return@awaitEachGesture
                }
            }
        }
    }
}

/**
 * Installs the shared outside-gesture focus-dismiss boundary for an app, page, or modal root.
 * Inputs register themselves through [TextField], so adding a standard input never requires a
 * business-page tap handler or allowlist entry.
 */
@Composable
internal fun SlockInputFocusDismissHost(
    content: @Composable (Modifier) -> Unit
) {
    val registry = remember { SlockInputFocusOwnerRegistry() }
    CompositionLocalProvider(LocalSlockInputFocusOwnerRegistry provides registry) {
        content(
            Modifier.dismissInputFocusOnOutsidePointerDown(
                registry = registry,
                enabled = registry.hasActiveOwner
            )
        )
    }
}

/**
 * [touchListener] plus the platform drag threshold for consumers that must
 * distinguish deliberate scrolling from tap/long-press pointer jitter.
 */
@Composable
internal fun Modifier.touchListenerWithConfiguration(
    onTouchEvent: (type: TouchType, position: Offset, touchSlop: Float) -> Unit
): Modifier {
    val gestureOwner =
        rememberSlockGestureOwner(
            surface = "touch-listener",
            semanticIdentity = Unit,
            callbacks = SlockTouchListenerGestureCallbacks(onTouchEvent)
        )
    return this.slockGesturePointerInput(gestureOwner) { owner ->
        awaitEachGesture {
            val tracker = SlockTouchSequenceTracker<PointerId>()
            var gesture: SlockGestureSession<SlockTouchListenerGestureCallbacks>? = null

            while (true) {
                val event = awaitPointerEvent()
                event.changes.forEach { change ->
                    val type =
                        tracker.onChange(
                            pointerId = change.id,
                            pressed = change.pressed,
                            positionChanged = change.positionChangedIgnoreConsumed()
                        )
                    if (type != null) {
                        if (type == TouchType.Down) gesture = owner.beginGesture()
                        (gesture ?: owner.beginGesture()).currentCallbacksOrNull()?.onTouchEvent?.invoke(
                            type,
                            change.position,
                            viewConfiguration.touchSlop
                        )
                        if (type == TouchType.Up) {
                            return@awaitEachGesture
                        }
                    }
                }
            }
        }
    }
}

private data class SlockTouchListenerGestureCallbacks(
    val onTouchEvent: (type: TouchType, position: Offset, touchSlop: Float) -> Unit
)

enum class TouchType { Down, Move, Up }

/**
 * Tracks one pointer for [touchListener] without consuming the gesture.
 *
 * Release/cancel must win over position change: Android commonly reports a
 * final changed position on ACTION_UP. Treating that event as Move leaves the
 * old pointer id latched and makes every later gesture invisible.
 */
internal class SlockTouchSequenceTracker<T : Any> {
    private var currentPointerId: T? = null

    fun onChange(
        pointerId: T,
        pressed: Boolean,
        positionChanged: Boolean
    ): TouchType? {
        val trackedPointerId = currentPointerId
        return when {
            trackedPointerId == pointerId && !pressed -> {
                currentPointerId = null
                TouchType.Up
            }

            trackedPointerId == null && pressed -> {
                currentPointerId = pointerId
                TouchType.Down
            }

            trackedPointerId == pointerId && positionChanged -> TouchType.Move
            else -> null
        }
    }
}

private object SlockPressTokens {
    val ButtonOffset = 2.dp
}

private fun Modifier.slockPressedOffset(
    pressed: Boolean,
    pressOffset: Dp
): Modifier {
    return if (pressed && pressOffset > 0.dp) {
        this.offset(x = pressOffset, y = pressOffset)
    } else {
        this
    }
}

/**
 * Makes Slock's pointer-input button a distinct node in Kuikly's merged semantics tree.
 *
 * The custom button intentionally keeps its existing pointer gesture implementation for pressed
 * offset feedback and coordinate-aware callbacks. Without an explicit merging semantics boundary,
 * however, dialog-level click barriers absorb the button's descendant text into one giant native
 * accessibility element. iOS/XCUITest and screen readers then see the whole dialog as a single
 * button instead of independently focusable actions.
 */
internal fun Modifier.slockButtonAccessibility(
    enabled: Boolean,
    longClickLabel: String? = null,
    performLongClick: (() -> Unit)? = null,
    performClick: () -> Unit,
): Modifier =
    semantics(mergeDescendants = true) {
        role = Role.Button
        onClick {
            if (enabled) {
                performClick()
                true
            } else {
                false
            }
        }
        if (performLongClick != null) {
            onLongClick(label = longClickLabel) {
                if (enabled) {
                    performLongClick()
                    true
                } else {
                    false
                }
            }
        }
        if (!enabled) {
            disabled()
        }
    }

/**
 * 创建一个 Modal 实例。Modal 是一个自定义的模态窗口组件，用于在当前页面上显示一个浮动窗口。
 * 当模态窗口显示时，用户无法与背景页面进行交互，只能与模态窗口内的内容进行交互。
 * 模态窗口可以用于显示表单、提示信息、详细信息等场景。
 * 注：1.Modal容器尺寸和屏幕等大
 *    2.若想关闭模态，可直接通过if为false条件不创建Modal即可
 *  @param modifier 可不用设置size，因为默认固定和屏幕等大
 *  @param content 构建全屏模态下的UI内容
 */
@Composable
fun Modal(
    modifier: Modifier = Modifier,
    ref: RefFunc<ModalView>? = null,
    /* 物理键触发时调用该事件（目前仅支持鸿蒙平台） */
    onWillDismiss: EventHandlerFn? = null,
    content: (@Composable () -> Unit)
) {
    Dialog(
        onDismissRequest = { onWillDismiss?.invoke("") },
        properties = slockModalDialogProperties()
    ) {
        SlockInputFocusDismissHost { focusDismissModifier ->
            Box(modifier = modifier.then(focusDismissModifier).fillMaxSize()) {
                content()
            }
        }
    }
}

/**
 * Slock renders and handles its own dismiss scrim inside every modal.
 *
 * Kuikly's default `dismissOnClickOutside = true` adds another full-window clickable plus a no-op
 * clickable around the dialog content. Those framework nodes are harmless on Android's virtual
 * accessibility tree, but UIKit exposes the full-window native parent as an accessibility leaf and
 * hides the independent child actions. Disable the framework click wrappers and leave outside-tap
 * dismissal to [SlockModalDismissBarrier].
 */
internal fun slockModalDialogProperties(): DialogProperties =
    DialogProperties(
        dismissOnClickOutside = false,
        usePlatformDefaultWidth = false,
        scrimColor = Color.Transparent
    )

@Composable
fun Modifier.dragEnable(enable: Boolean): Modifier {
    return this.setProp("dragEnable", enable)
}

@Composable
internal fun Button(
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
    onClick2: ((SlockPopoverAnchor) -> Unit)? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    pressFeedback: Boolean = true,
    pressOffset: Dp = SlockPressTokens.ButtonOffset,
    clickIdentity: Any? = null,
    anchorOwnerIdentity: Any? = null,
    content: (@Composable () -> Unit) = {}
) {
    val resolvedAnchorOwnerIdentity = anchorOwnerIdentity ?: clickIdentity ?: "button"
    val rootAnchor =
        remember(resolvedAnchorOwnerIdentity) { SlockLiveRootAnchorRef(identity = resolvedAnchorOwnerIdentity) }
    var pressed by remember(resolvedAnchorOwnerIdentity) { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongClick by rememberUpdatedState(onLongClick)
    val currentEnabled by rememberUpdatedState(enabled)
    val hasLongClick = onLongClick != null
    val gestureOwner =
        rememberSlockGestureOwner(
            surface = "button",
            semanticIdentity = resolvedAnchorOwnerIdentity,
            callbacks =
                SlockButtonGestureCallbacks(
                    enabled = enabled,
                    pressFeedback = pressFeedback,
                    onClick = onClick,
                    onLongClick = onLongClick,
                    onClick2 = onClick2,
                    onPressedChange = { pressed = it },
                    rootAnchor = rootAnchor
                )
        )
    Box(
        modifier = Modifier
            .slockButtonAccessibility(
                enabled = enabled,
                performClick = { if (currentEnabled) currentOnClick() },
                longClickLabel = longClickLabel,
                performLongClick = currentOnLongClick?.let {
                    { if (currentEnabled) currentOnLongClick?.invoke() }
                },
            )
            .then(
                if (onClick2 != null) {
                    Modifier.slockCaptureLiveRootAnchor(rootAnchor)
                } else {
                    Modifier
                }
            )
            // `enabled` is read through the gesture owner's current slot. Keeping it
            // out of the pointer-input keys prevents Kuikly from tearing down
            // the tap handler when a dynamically-enabled button (for example
            // Undo/Clear after the first edit) transitions from false to true.
            .slockGesturePointerInput(gestureOwner, pressFeedback, hasLongClick) { owner ->
                var gesture: SlockGestureSession<SlockButtonGestureCallbacks>? = null
                var arbiter = SlockButtonGestureArbiter(hasLongClick)
                detectTapGestures(
                    onPress = {
                        arbiter = SlockButtonGestureArbiter(hasLongClick)
                        val session = owner.beginGesture()
                        gesture = session
                        val callbacks = session.currentCallbacksOrNull()
                        if (callbacks != null && callbacks.enabled && callbacks.pressFeedback) {
                            callbacks.onPressedChange(true)
                            try {
                                tryAwaitRelease()
                            } finally {
                                session.currentCallbacksOrNull()?.onPressedChange(false)
                            }
                        }
                    },
                    onTap = { offset ->
                        val callbacks =
                            (gesture ?: owner.beginGesture()).currentCallbacksOrNull()
                                ?: return@detectTapGestures
                        if (callbacks.enabled) {
                            arbiter.dispatchTap {
                                callbacks.onClick()
                                callbacks.rootAnchor.resolve(SlockPopoverLocalPoint.Exact(offset))?.let { anchor ->
                                    callbacks.onClick2?.invoke(anchor)
                                }
                            }
                        }
                    },
                    onLongPress =
                        if (hasLongClick) {
                            {
                                val callbacks =
                                    (gesture ?: owner.beginGesture()).currentCallbacksOrNull()
                                        ?: return@detectTapGestures
                                if (callbacks.enabled) {
                                    arbiter.dispatchLongClick(callbacks.onLongClick)
                                }
                            }
                        } else {
                            null
                        }
                )
            }
            .slockPressedOffset(pressed = pressed && enabled, pressOffset = pressOffset)
            .then(modifier),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/** Owns the one-gesture tap/long-press decision used by the production pointer callbacks. */
internal class SlockButtonGestureArbiter(private val hasLongClick: Boolean) {
    private var longClickCommitted = false

    fun dispatchTap(action: () -> Unit): Boolean {
        if (longClickCommitted) return false
        action()
        return true
    }

    fun dispatchLongClick(action: (() -> Unit)?): Boolean {
        if (!hasLongClick || action == null || longClickCommitted) return false
        longClickCommitted = true
        action()
        return true
    }
}

private data class SlockButtonGestureCallbacks(
    val enabled: Boolean,
    val pressFeedback: Boolean,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)?,
    val onClick2: ((SlockPopoverAnchor) -> Unit)?,
    val onPressedChange: (Boolean) -> Unit,
    val rootAnchor: SlockLiveRootAnchorRef
)

internal class Border(
    val lineWidth: Dp,
    val lineStyle: BorderStyle,
    val color: Color
)

internal fun Modifier.border(border: Border) =
    this.border(width = border.lineWidth, color = border.color).slockSquareCorners()

internal fun slockTextFieldResolvedTextStyle(textStyle: TextStyle): TextStyle =
    textStyle.withResolvedSlockFontFamily()

internal fun slockTextFieldSingleLine(maxLines: Int): Boolean = maxLines == 1

internal fun TextInputState.toSlockTextFieldValue(): TextFieldValue {
    val boundedSelectionStart = selectionStart.coerceIn(0, text.length)
    val boundedSelectionEnd = selectionEnd.coerceIn(0, text.length)
    val boundedComposition =
        if (
            compositionStart != TextInputState.NO_COMPOSITION &&
            compositionEnd != TextInputState.NO_COMPOSITION
        ) {
            TextRange(
                compositionStart.coerceIn(0, text.length),
                compositionEnd.coerceIn(0, text.length),
            )
        } else {
            null
        }
    return TextFieldValue(
        text = text,
        selection = TextRange(boundedSelectionStart, boundedSelectionEnd),
        composition = boundedComposition,
    )
}

internal data class SlockTextFieldNativeEditorRead(
    val state: TextInputState?,
    val editorNativeRef: Int? = null,
)

internal sealed class SlockTextFieldNativeEditingStateReadResult {
    data class Available(
        val value: TextFieldValue,
        // nativeRef of the AutoHeightTextAreaView the state was read from (-1 when the
        // read path could not attribute one); matches ViewRef.nativeRef on the native side.
        val editorNativeRef: Int,
    ) : SlockTextFieldNativeEditingStateReadResult()

    data object Unavailable : SlockTextFieldNativeEditingStateReadResult()
}

private fun DeclarativeBaseView<*, *>.findSlockNativeTextEditor(): AutoHeightTextAreaView? =
    when (this) {
        is AutoHeightTextAreaView -> this
        is ViewContainer<*, *> ->
            templateChildren().firstNotNullOfOrNull { child ->
                child.findSlockNativeTextEditor()
            }
        else -> null
    }

/**
 * Reads the native editor's current text/selection/composition state at an action boundary.
 *
 * Native UIKit/Android/OHOS editing can already be visible while its ordinary change callback is
 * still queued for Compose. Callers such as Send therefore need an explicit barrier instead of
 * assuming the last rendered [TextFieldValue] is the value currently on screen.
 */
internal class SlockTextFieldNativeEditingStateReader {
    private class Binding(
        val owner: Any,
        var read: (SlockTextFieldNativeEditorRead) -> Unit,
    )

    private var binding: Binding? = null

    internal fun bind(
        owner: Any,
        read: (SlockTextFieldNativeEditorRead) -> Unit,
    ) {
        val activeBinding = binding
        if (activeBinding?.owner === owner) {
            // Modifier.nativeRef may refresh its callback during recomposition. Preserve the
            // binding identity so an in-flight native read is not invalidated by that refresh.
            activeBinding.read = read
        } else {
            binding = Binding(owner = owner, read = read)
        }
    }

    internal fun unbind(owner: Any) {
        if (binding?.owner === owner) binding = null
    }

    fun readCurrentValue(
        onResult: (SlockTextFieldNativeEditingStateReadResult) -> Unit,
    ) {
        val activeBinding = binding
        if (activeBinding == null) {
            onResult(SlockTextFieldNativeEditingStateReadResult.Unavailable)
            return
        }
        activeBinding.read { read ->
            onResult(
                if (binding === activeBinding && read.state != null) {
                    SlockTextFieldNativeEditingStateReadResult.Available(
                        value = read.state.toSlockTextFieldValue(),
                        // Kuikly nativeRef of the resolved AutoHeightTextAreaView — the
                        // instance identity the native layer matches against
                        // viewWithNativeRef for the adoption commit-time editor fence.
                        editorNativeRef = read.editorNativeRef ?: -1,
                    )
                } else {
                    SlockTextFieldNativeEditingStateReadResult.Unavailable
                }
            )
        }
    }
}

/**
 * Receives native keyboard-height events from text editors hosted by a keyboard-aware dialog.
 *
 * Kuikly does not expose Compose's `WindowInsets.ime` / `imePadding`; its supported cross-platform
 * contract is the editor-level `keyboardHeightChange` event. The default is deliberately a no-op
 * so ordinary page editors retain their existing layout behavior.
 */
internal val LocalSlockDialogKeyboardHeightChange =
    compositionLocalOf<(KeyboardParams) -> Unit> { {} }

@Composable
internal fun TextField(
    modifier: Modifier = Modifier,
    value: String = "",
    placeholder: String = "",
    autoFocus: Boolean = true,
    onValueChange: (String) -> Unit,
    onBlur: () -> Unit = {},
    onFocus: () -> Unit = {},
    keyboardHeightChange: (KeyboardParams) -> Unit = {},
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = TextStyle.Default,
    placeholderColor: Color? = null,
    cursorBrush: Brush = SolidColor(Color.Black),
    maxLines: Int = Int.MAX_VALUE,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    focusRequester: FocusRequester = remember { FocusRequester() },
    systemNewlineAction: Boolean = false,
    /**
     * True when the host wants Enter to trigger the IME action (send) rather than
     * insert a newline. Android can only offer an action key on a single-line field,
     * so the native view needs this before it fixes its line mode.
     */
    imeActionRequested: Boolean = true,
    participatesInGlobalFocusDismiss: Boolean = true,
    /**
     * Task #43 QA-4: visual transformation for password-typed inputs.
     * When non-null (e.g. [PasswordVisualTransformation()]), the
     * underlying value stays intact but the rendered text is masked.
     * Show/Hide toggles in the caller just switch this parameter.
     */
    visualTransformation: VisualTransformation = VisualTransformation.None,
    contentAlignment: Alignment = Alignment.CenterStart
) {
    var lastFocus by remember { mutableStateOf(false) }
    val focusOwnerRegistry = LocalSlockInputFocusOwnerRegistry.current
    val focusOwner = remember { Any() }
    val focusOwnerAnchor = remember { SlockInputFocusOwnerAnchor() }

    val currentOnBlur by rememberUpdatedState(onBlur)
    val currentOnFocus by rememberUpdatedState(onFocus)
    val currentKeyboardHeightChange by rememberUpdatedState(keyboardHeightChange)
    val dialogKeyboardHeightChange = LocalSlockDialogKeyboardHeightChange.current
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnTextLayout by rememberUpdatedState(onTextLayout)
    DisposableEffect(focusOwnerRegistry, focusOwner, participatesInGlobalFocusDismiss) {
        onDispose {
            if (participatesInGlobalFocusDismiss) {
                focusOwnerRegistry?.remove(focusOwner)
            }
        }
    }

    val updatedModifier = modifier
        .let { if (systemNewlineAction) it.setProp(SLOCK_SYSTEM_NEWLINE_ACTION_PROP, 1) else it }
        .let { if (systemNewlineAction) it.setProp(SLOCK_IME_ACTION_REQUESTED_PROP, if (imeActionRequested) 1 else 0) else it }
        .let { if (systemNewlineAction) it.setProp(SLOCK_TEXT_FIELD_NUMBER_OF_LINES_PROP, maxLines) else it }
        .keyboardHeightChange { params ->
            currentKeyboardHeightChange(params)
            dialogKeyboardHeightChange(params)
        }
        .focusRequester(focusRequester)
        .onGloballyPositioned(focusOwnerAnchor::update)
        .onFocusChanged {
            if (it.isFocused) {
                if (!lastFocus) {
                    if (participatesInGlobalFocusDismiss) {
                        focusOwnerRegistry?.setFocused(focusOwner, true, focusOwnerAnchor::resolveBounds)
                    }
                    currentOnFocus()
                    lastFocus = true
                }
            } else if (lastFocus) {
                if (participatesInGlobalFocusDismiss) {
                    focusOwnerRegistry?.setFocused(focusOwner, false)
                }
                currentOnBlur()
                lastFocus = false
            }
        }
    val resolvedTextStyle = slockTextFieldResolvedTextStyle(textStyle)
    BasicTextField(
        modifier = updatedModifier,
        value = value,
        onValueChange = currentOnValueChange,
        textStyle = resolvedTextStyle,
        singleLine = slockTextFieldSingleLine(maxLines),
        maxLines = maxLines,
        onTextLayout = currentOnTextLayout,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        cursorBrush = cursorBrush,
        enabled = enabled,
        readOnly = readOnly,
        visualTransformation = visualTransformation,
        decorationBox = { innerTextField ->
            Box(contentAlignment = contentAlignment) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        style = resolvedTextStyle.copy(color = placeholderColor ?: resolvedTextStyle.color),
                        color = placeholderColor ?: resolvedTextStyle.color,
                        maxLines = maxLines,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                innerTextField()
            }
        }
    )

    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            delay(100)
            focusRequester.requestFocus()
        }
    }
}

@Composable
internal fun TextField(
    modifier: Modifier = Modifier,
    value: TextFieldValue,
    placeholder: String = "",
    autoFocus: Boolean = true,
    onValueChange: (TextFieldValue) -> Unit,
    onBlur: () -> Unit = {},
    onFocus: () -> Unit = {},
    keyboardHeightChange: (KeyboardParams) -> Unit = {},
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = TextStyle.Default,
    placeholderColor: Color? = null,
    cursorBrush: Brush = SolidColor(Color.Black),
    maxLines: Int = Int.MAX_VALUE,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    focusRequester: FocusRequester = remember { FocusRequester() },
    systemNewlineAction: Boolean = false,
    /**
     * True when the host wants Enter to trigger the IME action (send) rather than
     * insert a newline. Android can only offer an action key on a single-line field,
     * so the native view needs this before it fixes its line mode.
     */
    imeActionRequested: Boolean = true,
    participatesInGlobalFocusDismiss: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    contentAlignment: Alignment = Alignment.CenterStart,
    nativeEditingStateReader: SlockTextFieldNativeEditingStateReader? = null,
) {
    var lastFocus by remember { mutableStateOf(false) }
    val focusOwnerRegistry = LocalSlockInputFocusOwnerRegistry.current
    val focusOwner = remember { Any() }
    val focusOwnerAnchor = remember { SlockInputFocusOwnerAnchor() }
    val nativeEditingStateBindingOwner = remember { Any() }

    val currentOnBlur by rememberUpdatedState(onBlur)
    val currentOnFocus by rememberUpdatedState(onFocus)
    val currentKeyboardHeightChange by rememberUpdatedState(keyboardHeightChange)
    val dialogKeyboardHeightChange = LocalSlockDialogKeyboardHeightChange.current
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnTextLayout by rememberUpdatedState(onTextLayout)
    DisposableEffect(focusOwnerRegistry, focusOwner, participatesInGlobalFocusDismiss) {
        onDispose {
            if (participatesInGlobalFocusDismiss) {
                focusOwnerRegistry?.remove(focusOwner)
            }
        }
    }
    DisposableEffect(nativeEditingStateReader, nativeEditingStateBindingOwner) {
        onDispose { nativeEditingStateReader?.unbind(nativeEditingStateBindingOwner) }
    }

    val updatedModifier = modifier
        .let { if (systemNewlineAction) it.setProp(SLOCK_SYSTEM_NEWLINE_ACTION_PROP, 1) else it }
        .let { if (systemNewlineAction) it.setProp(SLOCK_IME_ACTION_REQUESTED_PROP, if (imeActionRequested) 1 else 0) else it }
        .let { if (systemNewlineAction) it.setProp(SLOCK_TEXT_FIELD_NUMBER_OF_LINES_PROP, maxLines) else it }
        .keyboardHeightChange { params ->
            currentKeyboardHeightChange(params)
            dialogKeyboardHeightChange(params)
        }
        .focusRequester(focusRequester)
        .onGloballyPositioned(focusOwnerAnchor::update)
        .onFocusChanged {
            if (it.isFocused) {
                if (!lastFocus) {
                    if (participatesInGlobalFocusDismiss) {
                        focusOwnerRegistry?.setFocused(focusOwner, true, focusOwnerAnchor::resolveBounds)
                    }
                    currentOnFocus()
                    lastFocus = true
                }
            } else if (lastFocus) {
                if (participatesInGlobalFocusDismiss) {
                    focusOwnerRegistry?.setFocused(focusOwner, false)
                }
                currentOnBlur()
                lastFocus = false
            }
        }
        .then(
            if (nativeEditingStateReader != null) {
                Modifier.nativeRef {
                    val nativeEditorRoot = this
                    nativeEditingStateReader.bind(nativeEditingStateBindingOwner) { callback ->
                        // CoreTextField splits non-prop/event modifiers onto its outer Box. Resolve
                        // the actual AutoHeightTextAreaView at read time; calling the Box silently
                        // drops getTextInputState because that native view has no such method.
                        val nativeEditor = nativeEditorRoot.findSlockNativeTextEditor()
                        if (nativeEditor == null) {
                            callback(SlockTextFieldNativeEditorRead(state = null))
                        } else {
                            val editorRef = nativeEditor.nativeRef
                            nativeEditor.getTextInputState { state ->
                                callback(
                                    SlockTextFieldNativeEditorRead(
                                        state = state,
                                        editorNativeRef = editorRef,
                                    )
                                )
                            }
                        }
                    }
                }
            } else {
                Modifier
            }
        )
    val resolvedTextStyle = slockTextFieldResolvedTextStyle(textStyle)
    BasicTextField(
        modifier = updatedModifier,
        value = value,
        onValueChange = currentOnValueChange,
        textStyle = resolvedTextStyle,
        singleLine = slockTextFieldSingleLine(maxLines),
        maxLines = maxLines,
        onTextLayout = currentOnTextLayout,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        cursorBrush = cursorBrush,
        enabled = enabled,
        readOnly = readOnly,
        visualTransformation = visualTransformation,
        decorationBox = { innerTextField ->
            Box(contentAlignment = contentAlignment) {
                if (value.text.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        style = resolvedTextStyle.copy(color = placeholderColor ?: resolvedTextStyle.color),
                        color = placeholderColor ?: resolvedTextStyle.color,
                        maxLines = maxLines,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                innerTextField()
            }
        }
    )

    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            delay(100)
            focusRequester.requestFocus()
        }
    }
}

private const val SLOCK_SYSTEM_NEWLINE_ACTION_PROP = "slockSystemNewlineAction"
private const val SLOCK_TEXT_FIELD_NUMBER_OF_LINES_PROP = "numberOfLines"

// Android claims the single-line declaration to make the IME render an action key, and a
// multi-line field suppresses that key entirely. The native view decides the mode exactly once,
// and numberOfLines is what triggers that decision — so this prop must be attached ahead of
// SLOCK_TEXT_FIELD_NUMBER_OF_LINES_PROP in the modifier chain, not after it.
private const val SLOCK_IME_ACTION_REQUESTED_PROP = "slockImeActionRequested"

@Composable
fun Modifier.dtReport(enable: Boolean): Modifier {
    var ref1 = remember { 0 }
    var ref2 = remember { 9 }
    return this.setProp("dragEnable", enable)
        .nativeRef {
            println("<top>.dtReport1")
            ref1 = this.nativeRef
        }.nativeRef {
            println("<top>.dtReport2")
            ref2 = this.nativeRef

        }.clickable {
            println("<top>.clickable1 ref1=${ref1} ${ref2}")
        }.clickable {
            println("<top>.clickable2 ref1=${ref1} ${ref2}")
        }
}

fun Modifier.appearPercentage(
    onPercentageChanged: (Float) -> Unit
): Modifier = this.then(
    Modifier.onGloballyPositioned { layoutCoordinates ->
        val windowBounds =
            layoutCoordinates.parentLayoutCoordinates?.size?.let { IntSize(it.width, it.height) }
        val layoutBounds = layoutCoordinates.boundsInRoot()
        windowBounds?.let { bounds ->
            val visibleHeight = layoutBounds.height.coerceAtMost(bounds.height.toFloat())
            val visibleWidth = layoutBounds.width.coerceAtMost(bounds.width.toFloat())
            val percentageHeight = visibleHeight / bounds.height.toFloat()
            val percentageWidth = visibleWidth / bounds.width.toFloat()
            onPercentageChanged(kotlin.math.min(percentageWidth, percentageHeight))
        }
    }
)

@Composable
internal fun Modifier.onSizeChangedDp(
    onSizeChanged: (IntSize) -> Unit
): Modifier {
    val density = LocalDensity.current
    return this.onSizeChanged {
        with(density) {
            val dpSize = IntSize(
                width = it.width.toDp().value.toInt(),
                height = it.height.toDp().value.toInt()
            )
            onSizeChanged(dpSize)
        }
    }
}

@Composable
fun ComposeNavigationBar(titleInput: String = "", content: @Composable () -> Unit) {
    val localPager = LocalActivity.current.getPager()
    val routerModule = localPager.getModule<RouterModule>(RouterModule.MODULE_NAME)
    val title = localPager.pageData.params.optString("pageTitle", titleInput)

    Column {
        Spacer(modifier = Modifier.height(40.dp))
        Box {
            Text(
                text = "<",
                style = SlockType.PageTitle
                    .copy(fontSize = 22.sp, lineHeight = 28.sp)
                    .withSlockFontWeight(FontWeight.SemiBold),
                modifier = Modifier
                    .padding(vertical = 12.dp, horizontal = 12.dp)
                    .clickable {
                        routerModule?.closePage()
                    }
            )
            Text(
                title,
                style = SlockType.PageTitle
                    .copy(fontSize = 22.sp, lineHeight = 28.sp)
                    .withSlockFontWeight(FontWeight.SemiBold),
                textAlign = TextAlign.Center,
                color = Color(0xFF333333),
                modifier = Modifier
                    .padding(vertical = 12.dp, horizontal = 12.dp)
                    .fillMaxWidth(),
            )
        }
        content.invoke()
    }
}
