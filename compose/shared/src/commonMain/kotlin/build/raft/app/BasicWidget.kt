@file:OptIn(ExperimentalFoundationApi::class)

package build.raft.app

import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBackButtonDefaults
import build.raft.designsystem.components.RaftBackButton
import build.raft.designsystem.gesture.raftKeepsBackSwipe
import build.raft.designsystem.gesture.raftBackSwipeRegion
import build.raft.app.compat.ui.nativeEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
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
import build.raft.app.data.parseRaftAvatarMountedRenderModel
import coil3.compose.AsyncImagePainter
import build.raft.app.compat.ui.rememberAsyncImageBitmap
import build.raft.app.compat.ui.keyboardHeightChange
import build.raft.app.compat.ui.LocalRaftHostImeInsets
import coil3.compose.rememberAsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import build.raft.app.image.RaftImageAnimationEffect
import build.raft.app.image.rememberRaftAppInForeground
import build.raft.app.image.raftAnimatedImage
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldState
import build.raft.designsystem.components.raftTextFieldLineLimits
import build.raft.designsystem.components.rememberRaftTextFieldState
import build.raft.designsystem.components.rememberRaftTextInputTraceTransformation
import build.raft.designsystem.components.toRaftKeyboardActionHandler
import build.raft.designsystem.components.toRaftOutputTransformation
import build.raft.designsystem.components.toRaftTextFieldValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActionScope
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.positionChangedIgnoreConsumed
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import build.raft.designsystem.components.RaftWindowDialog
import build.raft.designsystem.components.RaftWindowDialogProperties
import build.raft.app.compat.ui.BorderStyle
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.ui.KeyboardParams
import build.raft.app.compat.ui.TextInputState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

fun Modifier.backgroundColor(color: Color): Modifier {
    return this.background(color)
}

/** Neutral fallback shown before or around image preview content. */
internal val RaftImagePreviewLetterboxColor: Color get() = RaftColor.SurfaceWhite

/** Thumbnail contract: preserve ratio while filling the fixed preview frame. */
internal val RaftImageThumbnailContentScale: ContentScale = ContentScale.Crop

internal data class RaftImagePreviewLoadingPresentation(
    val fallbackUnderlaySource: String?,
    val showLoadingOverlay: Boolean
)

internal data class RaftImagePreviewRenderIdentity(
    val source: String,
    val decodedWidth: Int,
    val decodedHeight: Int
)

internal fun raftImagePreviewRenderIdentity(
    source: String,
    decodedWidth: Int,
    decodedHeight: Int
): RaftImagePreviewRenderIdentity =
    RaftImagePreviewRenderIdentity(
        source = source,
        decodedWidth = decodedWidth.coerceAtLeast(0),
        decodedHeight = decodedHeight.coerceAtLeast(0)
    )

internal fun resolveRaftImagePreviewRenderIdentity(
    currentIdentity: RaftImagePreviewRenderIdentity?,
    source: String,
    decodedWidth: Int,
    decodedHeight: Int,
    activeSucceeded: Boolean
): RaftImagePreviewRenderIdentity {
    val normalizedSource = source.trim()
    if (currentIdentity != null && currentIdentity.source == normalizedSource) {
        if (activeSucceeded) {
            return currentIdentity
        }
        if (currentIdentity.decodedWidth > 0 && currentIdentity.decodedHeight > 0) {
            return currentIdentity
        }
    }
    return raftImagePreviewRenderIdentity(
        source = normalizedSource,
        decodedWidth = decodedWidth,
        decodedHeight = decodedHeight
    )
}

/** Decode bound of the dimensions-only read in the image preview (render identity). */
internal const val RaftImagePreviewIdentityDecodeEdgePx = 64

private class RaftImagePreviewIdentityHolder(
    var identity: RaftImagePreviewRenderIdentity
)

private class RaftImagePreviewSourceOutcome(
    var failed: Boolean = false
)

@Composable
internal fun RaftImagePreviewRenderSlot(
    identity: RaftImagePreviewRenderIdentity,
    content: @Composable () -> Unit
) {
    key(identity) {
        content()
    }
}

private fun AsyncImagePainter.State.raftImagePreviewDiagnosticsPhase(): String =
    when (this) {
        is AsyncImagePainter.State.Empty -> "empty"
        is AsyncImagePainter.State.Loading -> "loading"
        is AsyncImagePainter.State.Success -> "success"
        is AsyncImagePainter.State.Error -> "error"
    }

internal fun raftImagePreviewLoadingPresentation(
    activeSource: String?,
    fallbackSource: String?,
    showLoadingIndicator: Boolean,
    activeSucceeded: Boolean,
    activeFailed: Boolean,
    previousRenderedSource: String? = null,
    previousPresentableSource: String? = null
): RaftImagePreviewLoadingPresentation {
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
    return RaftImagePreviewLoadingPresentation(
        fallbackUnderlaySource = underlay.takeUnless { activeSucceeded },
        showLoadingOverlay =
            showLoadingIndicator &&
                !activeSucceeded &&
                !keepsContentVisible
    )
}

@Composable
fun RaftCachedImage(
    source: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    colorFilter: ColorFilter? = null,
    /**
     * Ask for animated decoding (GIF / animated WebP; the image viewer only). False keeps the
     * plain static request, byte-for-byte today's path.
     */
    animated: Boolean = false,
    /** With [animated]: whether it plays now (the selected pager page); it also pauses in the background. */
    animationActive: Boolean = true,
    onImageStateChange: ((AsyncImagePainter.State) -> Unit)? = null
) {
    val platformContext = LocalPlatformContext.current
    val model: Any? =
        remember(source, animated, platformContext) {
            if (animated && source != null) {
                ImageRequest.Builder(platformContext).data(source).raftAnimatedImage().build()
            } else {
                source
            }
        }
    val painter = rememberAsyncImagePainter(model)
    if (onImageStateChange != null || animated) {
        val imageState by painter.state.collectAsState()
        if (onImageStateChange != null) {
            val stateCallback by rememberUpdatedState(onImageStateChange)
            LaunchedEffect(imageState) {
                stateCallback.invoke(imageState)
            }
        }
        if (animated) {
            val foreground = rememberRaftAppInForeground()
            RaftImageAnimationEffect(
                image = (imageState as? AsyncImagePainter.State.Success)?.result?.image,
                running = animationActive && foreground
            )
        }
    }
    Image(
        painter = painter,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        colorFilter = colorFilter
    )
}

@Composable
internal fun RaftAspectFitImagePreviewSurface(
    source: String?,
    fallbackSource: String? = null,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    placeholderLabel: String = "IMG",
    backgroundColor: Color = RaftImagePreviewLetterboxColor,
    borderWidth: Dp = RaftAppStroke.Default,
    contentScale: ContentScale = ContentScale.Fit,
    zoomable: Boolean = false,
    /** Zoom state hoisted by the page and keyed by image identity; null keeps it per source. */
    zoomState: RaftZoomableImageState? = null,
    showLoadingIndicator: Boolean = false,
    /** Play [source] if it is an animated GIF / WebP (never the fallback / underlay thumbnail). */
    animateSource: Boolean = false,
    /** With [animateSource]: whether the animation runs now (the selected pager page). */
    animationActive: Boolean = true,
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
        RaftImagePreviewSourceOutcome()
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
    // task #402: image previews never carried painter telemetry, so the 9ea07421 "flash white then
    // reappear" report couldn't be attributed. Mirror the avatar line shape: hashed source, model
    // kind, painter phase, and whether an underlay frame was pinned while the new source loaded.
    LaunchedEffect(activeSource, imageState, lastSucceededSource, lastPresentableSource) {
        raftLogInfo(
            "SlockImagePreview",
            "image_preview_painter " +
                "source=${activeSource.raftAvatarDiagnosticsHash()} " +
                "model=${if (activeSource != null && parseRaftAvatarMountedRenderModel(activeSource.orEmpty()) != null) "managed" else "url"} " +
                "painter=${imageState.raftImagePreviewDiagnosticsPhase()} " +
                "underlay=${if (lastSucceededSource != null || lastPresentableSource != null) "yes" else "none"}"
        )
    }
    Box(
        modifier = modifier
            .background(backgroundColor)
            // 0.dp is Dp.Hairline to Modifier.border (a 1px line), so a frameless surface skips it.
            .then(if (borderWidth > 0.dp) Modifier.border(borderWidth, RaftColor.BorderBlack) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (activeSource != null) {
            // Only the decoded dimensions are read (render identity), so decode a small,
            // subsampled copy instead of the full-resolution image per thumbnail.
            // TODO(cmp-switch): read the dimensions from the image header without decoding.
            val activeBitmap = rememberAsyncImageBitmap(activeSource, maxDecodeEdgePx = RaftImagePreviewIdentityDecodeEdgePx)
            val currentSource = activeSource.orEmpty()
            val activeSucceeded = imageState is AsyncImagePainter.State.Success
            val identityHolder = remember(activeSource) {
                RaftImagePreviewIdentityHolder(
                    raftImagePreviewRenderIdentity(
                        source = currentSource,
                        decodedWidth = activeBitmap?.width ?: 0,
                        decodedHeight = activeBitmap?.height ?: 0
                    )
                )
            }
            val renderIdentity =
                resolveRaftImagePreviewRenderIdentity(
                    currentIdentity = identityHolder.identity,
                    source = currentSource,
                    decodedWidth = activeBitmap?.width ?: 0,
                    decodedHeight = activeBitmap?.height ?: 0,
                    activeSucceeded = activeSucceeded
                ).also { identityHolder.identity = it }
            val loadingPresentation =
                raftImagePreviewLoadingPresentation(
                    activeSource = activeSource,
                    fallbackSource = normalizedFallback,
                    showLoadingIndicator = showLoadingIndicator,
                    activeSucceeded = activeSucceeded,
                    activeFailed = imageState is AsyncImagePainter.State.Error,
                    previousRenderedSource = lastSucceededSource,
                    previousPresentableSource = lastPresentableSource
                )
            loadingPresentation.fallbackUnderlaySource?.let { underlaySource ->
                // While the full-size image loads over a zoomed thumbnail, the thumbnail underlay
                // keeps the same zoom so the view does not jump to 1x and back.
                val underlayZoom = zoomState?.takeIf { zoomable }
                RaftCachedImage(
                    source = underlaySource,
                    contentDescription = contentDescription,
                    contentScale = contentScale,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .then(if (underlayZoom != null) Modifier.raftZoomableImageLayer(underlayZoom) else Modifier)
                )
            }
            val animateActive = animateSource && activeSource == normalizedSource
            RaftImagePreviewRenderSlot(renderIdentity) {
                if (zoomable) {
                    RaftZoomableCachedImage(
                        source = activeSource.orEmpty(),
                        contentDescription = contentDescription,
                        modifier = Modifier.fillMaxSize(),
                        zoomState = zoomState,
                        animated = animateActive,
                        animationActive = animationActive,
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
                    RaftCachedImage(
                        source = activeSource,
                        contentDescription = contentDescription,
                        contentScale = contentScale,
                        modifier = Modifier.fillMaxSize(),
                        animated = animateActive,
                        animationActive = animationActive,
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
                RaftImagePreviewLoadingOverlay(
                    failed = imageState is AsyncImagePainter.State.Error,
                    placeholderLabel = placeholderLabel,
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            Text(
                text = placeholderLabel,
                style = RaftType.Button.copy(fontSize = 11.sp, color = RaftColor.TextBlack),
                color = RaftColor.TextBlack,
                maxLines = 1
            )
        }
    }
}

/**
 * Whether a zoom/pan gesture has moved past the touch slop. Motion is accumulated across events, like the
 * SDK's detectTransformGestures: a slow drag moves only a pixel or two per event and would never cross the
 * slop if each event were compared on its own (iOS: a slow one-finger drag on a zoomed image did not pan).
 */
internal class ZoomPanTouchSlopTracker(private val touchSlop: Float) {
    private var zoom = 1f
    private var pan = Offset.Zero
    var pastTouchSlop: Boolean = false
        private set

    /** Adds one event's motion; true once the accumulated zoom or pan is past the slop (and from then on). */
    fun accept(zoomChange: Float, panChange: Offset, viewportExtent: Float): Boolean {
        if (pastTouchSlop) return true
        zoom *= zoomChange
        pan += panChange
        val zoomMotion = abs(1f - zoom) * viewportExtent
        pastTouchSlop = zoomMotion > touchSlop || pan.getDistance() > touchSlop
        return pastTouchSlop
    }
}

/** A zoomable image keeps horizontal (back-direction) drags only while zoomed in: they pan it. */
internal fun raftZoomedImageKeepsBackSwipe(scale: Float): Boolean = scale > 1f

@Composable
private fun RaftZoomableCachedImage(
    source: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    zoomState: RaftZoomableImageState? = null,
    animated: Boolean = false,
    animationActive: Boolean = true,
    onImageStateChange: ((AsyncImagePainter.State) -> Unit)? = null
) {
    // Hoisted state survives the render slot re-keying on a source swap (thumbnail -> full size).
    val state = zoomState ?: remember(source) { RaftZoomableImageState() }
    val animationScope = rememberCoroutineScope()
    DisposableEffect(state) {
        onDispose { state.finishAnimation() }
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { state.viewport = it.toSize() }
            // Zoomed in, a horizontal drag pans the image: it must not start the host's back swipe
            // (iOS full-page pop) or page a surrounding gallery. At 1x the drag is left alone.
            .raftBackSwipeRegion { raftZoomedImageKeepsBackSwipe(state.transform.scale) }
            .raftZoomableImageGestures(state, animationScope),
        contentAlignment = Alignment.Center
    ) {
        RaftCachedImage(
            source = source,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            // Same composable as a still: zoom, pan, double tap and the pager / dismiss drags
            // stay on this Box; only the painter's frames change.
            animated = animated,
            animationActive = animationActive,
            onImageStateChange = { imageState ->
                // Pan is bounded by the image as fitted into the viewport, so it needs the image size.
                // The thumbnail and the full-size image share an aspect ratio, so either one will do.
                (imageState as? AsyncImagePainter.State.Success)
                    ?.painter
                    ?.intrinsicSize
                    ?.takeIf { it != Size.Unspecified }
                    ?.let { state.contentSize = it }
                onImageStateChange?.invoke(imageState)
            },
            modifier = Modifier
                .fillMaxSize()
                .raftZoomableImageLayer(state)
        )
    }
}

/** Draws the image at [state]'s zoom, clipped to its own bounds. */
private fun Modifier.raftZoomableImageLayer(state: RaftZoomableImageState): Modifier =
    clipToBounds()
        .graphicsLayer {
            // Read while drawing: a zoom or pan frame redraws without recomposing.
            val transform = state.transform
            scaleX = transform.scale
            scaleY = transform.scale
            translationX = transform.pan.x
            translationY = transform.pan.y
        }

/**
 * Pinch to zoom (around the fingers' centroid), drag to pan while zoomed, and double tap.
 *
 * At 1x a one-finger drag is never consumed, so the gallery swipe, the swipe-down dismiss and the
 * iOS back swipe keep it; movement is consumed only once a pinch or a zoomed pan passes the touch
 * slop. Down events are never consumed. A double tap zooms to [RaftZoomableImageDoubleTapScale]
 * around the tap, or back to 1x. With [RaftZoomableImageState.handsOffAtHorizontalEdge] a zoomed
 * one-finger pan that keeps pushing against the image's side edge stops consuming for the rest of
 * the gesture, so the surrounding pager takes the drag over (iOS Photos).
 */
private fun Modifier.raftZoomableImageGestures(
    state: RaftZoomableImageState,
    animationScope: CoroutineScope
): Modifier =
    pointerInput(state) {
        var lastTapUpMillis = 0L
        var lastTapPosition: Offset? = null
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            state.stopAnimation()
            val previousTap = lastTapPosition
            lastTapPosition = null
            val secondTap =
                previousTap != null &&
                    down.uptimeMillis - lastTapUpMillis <= viewConfiguration.doubleTapTimeoutMillis &&
                    (down.position - previousTap).getDistance() <= viewConfiguration.touchSlop * 4f
            val touchSlopTracker = ZoomPanTouchSlopTracker(viewConfiguration.touchSlop)
            var isTap = true
            // Another handler (gallery paging, back swipe) consumed the gesture, or the drag was
            // handed to the pager: leave the rest of it alone.
            var released = false
            var travelled = Offset.Zero
            var edgePush = Offset.Zero
            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.count { it.pressed }
                if (pressed == 0) {
                    val up = event.changes.firstOrNull()
                    if (isTap && !released && up != null && !up.isConsumed) {
                        if (secondTap) {
                            state.doubleTap(up.position, animationScope)
                            up.consume()
                        } else {
                            lastTapPosition = up.position
                            lastTapUpMillis = up.uptimeMillis
                        }
                    }
                    break
                }
                if (pressed > 1) isTap = false
                if (released) continue
                if (event.changes.any { it.isConsumed }) {
                    released = true
                    isTap = false
                    continue
                }
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()
                travelled += panChange
                if (travelled.getDistance() > viewConfiguration.touchSlop) isTap = false
                val shouldHandle = zoomChange != 1f || state.transform.scale > 1f
                val pastTouchSlop =
                    shouldHandle &&
                        touchSlopTracker.accept(
                            zoomChange = zoomChange,
                            panChange = panChange,
                            viewportExtent = max(state.viewport.width, state.viewport.height),
                        )
                if (pastTouchSlop) {
                    if (state.handsOffAtHorizontalEdge && pressed == 1 && zoomChange == 1f) {
                        edgePush = state.edgePush(edgePush, panChange)
                        if (raftZoomableImageHandsOffToPager(edgePush, viewConfiguration.touchSlop)) {
                            // Left unconsumed from here on: the pager measures its drag from this point.
                            released = true
                            continue
                        }
                    } else {
                        edgePush = Offset.Zero
                    }
                    state.transformBy(
                        focus = event.calculateCentroid(useCurrent = true),
                        zoomChange = zoomChange,
                        panChange = panChange
                    )
                    event.changes.forEach {
                        if (it.positionChanged()) {
                            it.consume()
                        }
                    }
                }
            }
        }
    }

@Composable
private fun RaftImagePreviewLoadingOverlay(
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
                style = RaftType.Button.copy(fontSize = 11.sp, color = RaftColor.TextBlack),
                color = RaftColor.TextBlack,
                maxLines = 1
            )
        } else {
            RaftHeaderRefreshIndicator()
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
    // TODO(cmp-switch): Kuikly stickyHeaderWithMarginTop pinned below hoverMarginTop.
    stickyHeader(key = key) {
        Box(modifier = modifier) {
            content()
        }
    }
}

fun Modifier.borderRadius(radius: Dp) = clip(RoundedCornerShape(radius))

/** Kuikly native views needed an explicit 0 radius; Compose corners are square by default. */
internal fun Modifier.raftSquareCorners(): Modifier = this

/**
 * Register-driven corner rounding: square (no clip) at [radius] == 0.dp, which is what brutal
 * callers (theme radius [RaftAppRadius.None]) get; the elegant register clips to [radius].
 */
internal fun Modifier.raftThemedCorners(radius: Dp): Modifier =
    if (radius.value <= 0f) this else clip(RoundedCornerShape(radius))

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
    registry: RaftInputFocusOwnerRegistry,
    enabled: Boolean = true,
): Modifier {
    val currentFocusManager by rememberUpdatedState(LocalFocusManager.current)
    val currentEnabled by rememberUpdatedState(enabled)
    val hostAnchor = remember { RaftInputFocusRootAnchor() }
    val focusDismissScope = rememberCoroutineScope()
    return onGloballyPositioned(hostAnchor::update).pointerInput(registry) {
        awaitEachGesture {
            val touchTracker = RaftTouchSequenceTracker<PointerId>()
            val dismissTracker = RaftInputFocusDismissGestureTracker()
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
                var terminalDismissToken: RaftInputFocusDismissToken? = null
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

internal class RaftInputFocusOwnerAnchor {
    private var coordinates: LayoutCoordinates? = null

    fun update(next: LayoutCoordinates) {
        coordinates = next
    }

    fun resolveBounds(): Rect? =
        coordinates
            ?.takeIf { it.isAttached }
            ?.boundsInRoot()
}

internal class RaftInputFocusOwnerRegistry {
    private val activeOwners = mutableMapOf<Any, () -> Rect?>()
    private val retainedInteractionRegions = mutableMapOf<Any, () -> Rect?>()
    private var focusOwnerGeneration: Long = 0L
    private var pointerSafetyGeneration: Long = 0L
    private var multiPointerFocusClearBlocked: Boolean = false
    private var claimedFocusClearToken: RaftInputFocusDismissToken? = null
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

    fun outsideFocusToken(position: Offset): RaftInputFocusDismissToken? {
        if (multiPointerFocusClearBlocked || !shouldClearFocus(position)) return null
        return RaftInputFocusDismissToken(
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

    fun claimFocusClear(token: RaftInputFocusDismissToken): Boolean {
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

    fun releaseUnreconciledFocusClearClaim(token: RaftInputFocusDismissToken) {
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

internal data class RaftInputFocusDismissToken(
    val focusOwnerGeneration: Long,
    val pointerSafetyGeneration: Long
)

private const val RaftNativeCancelEvent = "cancel"

/**
 * Kuikly's `SuperTouchManager` tags a native touch-cancel as
 * the literal `cancel` event while normal physical Up remains untagged, including after a native
 * scroll gesture. A cancel terminates the Compose hit path but is not proof that the finger lifted,
 * so it must invalidate pending focus dismissal instead of being folded into [TouchType.Up].
 */
internal fun isNativeInputFocusDismissCancellation(
    nativeEvent: Any?,
    hasTerminalChange: Boolean
): Boolean = nativeEvent == RaftNativeCancelEvent && hasTerminalChange

internal val LocalRaftInputFocusOwnerRegistry =
    compositionLocalOf<RaftInputFocusOwnerRegistry?> { null }

/**
 * Resolves focused-input geometry from the registry that is current where this resolver is
 * composed. Modal installs its own focus-dismiss host, so callers must acquire this resolver from
 * inside modal content rather than capture the page-level registry before entering [Modal].
 */
@Composable
internal fun rememberRaftFocusedInputBoundsResolver(): () -> Rect? {
    val currentRegistry = rememberUpdatedState(LocalRaftInputFocusOwnerRegistry.current)
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
    val registry = LocalRaftInputFocusOwnerRegistry.current ?: return this
    val interactionOwner = remember { Any() }
    val interactionAnchor = remember { RaftInputFocusOwnerAnchor() }
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
internal class RaftInputFocusDismissGestureTracker {
    private var tracking: Boolean = false
    private var armedDismissToken: RaftInputFocusDismissToken? = null

    fun onTouch(
        type: TouchType,
        dismissToken: RaftInputFocusDismissToken? = null
    ): RaftInputFocusDismissToken? =
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
    val focusOwnerRegistry = LocalRaftInputFocusOwnerRegistry.current
    val currentFocusManager by rememberUpdatedState(LocalFocusManager.current)
    val scrollAnchor = remember { RaftInputFocusRootAnchor() }
    val focusDismissScope = rememberCoroutineScope()
    return onGloballyPositioned(scrollAnchor::update).pointerInput(focusOwnerRegistry) {
        val registry = focusOwnerRegistry ?: return@pointerInput
        awaitEachGesture {
            val touchTracker = RaftTouchSequenceTracker<PointerId>()
            val dismissTracker = RaftInputFocusDismissGestureTracker()
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
                var terminalDismissToken: RaftInputFocusDismissToken? = null
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
internal fun RaftInputFocusDismissHost(
    content: @Composable (Modifier) -> Unit
) {
    val registry = remember { RaftInputFocusOwnerRegistry() }
    CompositionLocalProvider(LocalRaftInputFocusOwnerRegistry provides registry) {
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
        rememberRaftGestureOwner(
            surface = "touch-listener",
            semanticIdentity = Unit,
            callbacks = RaftTouchListenerGestureCallbacks(onTouchEvent)
        )
    return this.raftGesturePointerInput(gestureOwner) { owner ->
        awaitEachGesture {
            val tracker = RaftTouchSequenceTracker<PointerId>()
            var gesture: RaftGestureSession<RaftTouchListenerGestureCallbacks>? = null

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

private data class RaftTouchListenerGestureCallbacks(
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
internal class RaftTouchSequenceTracker<T : Any> {
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

private object RaftPressTokens {
    val ButtonOffset = 2.dp
}

private fun Modifier.raftPressedOffset(
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
internal fun Modifier.raftButtonAccessibility(
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
    /* 物理键触发时调用该事件（当前 Android/iOS 均未触发） */
    onWillDismiss: ((Any?) -> Unit)? = null,
    content: (@Composable () -> Unit)
) {
    // The page window's horizontal safe area (what the page shells around this call padded in
    // total), read here in the page: the dialog is its own full-window layer, so it pads the whole
    // left/right inset itself. Zero outside a page shell (previews, screenshot tests).
    val horizontalSafeArea = LocalRaftAppliedHorizontalSafeArea.current
    // The strips stay transparent: under them is the page's own padded strip (the canvas), and a
    // scrim bleeds over them (RaftModalDismissBarrier). Painting them would cover the ends of a
    // host tab bar while a menu is open.
    // RaftWindowDialog: back-swipe block, edge-to-edge window, system bars that follow the theme.
    // Kuikly's Modal had no framework scrim and left the keyboard to the page: the pages draw
    // RaftModalDismissBarrier and pad for keyboardHeightChange themselves.
    RaftWindowDialog(
        onDismissRequest = { onWillDismiss?.invoke("") },
        properties = RaftModalWindowDialogProperties
    ) {
        // The dialog is its own window with its own keyboard animation; the host's IME report
        // describes the activity window underneath (RaftHostImeInsets).
        CompositionLocalProvider(LocalRaftHostImeInsets provides null) {
            RaftInputFocusDismissHost { focusDismissModifier ->
                Box(modifier = modifier.then(focusDismissModifier).fillMaxSize()) {
                    RaftHorizontalSafeAreaShell(required = horizontalSafeArea, windowRoot = true) {
                        content()
                    }
                }
            }
        }
    }
}

/**
 * Slock renders and handles its own dismiss scrim inside every modal ([RaftModalDismissBarrier]),
 * so the window draws none, and the pages pad for the keyboard themselves.
 */
internal val RaftModalWindowDialogProperties =
    RaftWindowDialogProperties(drawScrim = false, contentHandlesKeyboard = true)

/** Kuikly native drag toggle; nothing to configure on CMP. */
@Composable
fun Modifier.dragEnable(enable: Boolean): Modifier = this

@Composable
internal fun Button(
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
    onClick2: ((RaftPopoverAnchor) -> Unit)? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    pressFeedback: Boolean = true,
    pressOffset: Dp = RaftPressTokens.ButtonOffset,
    clickIdentity: Any? = null,
    anchorOwnerIdentity: Any? = null,
    // Observes the press (raft-ui lays a pressed wash over the control instead of
    // offsetting it); only reported while [pressFeedback] is on.
    onPressedChange: ((Boolean) -> Unit)? = null,
    content: (@Composable () -> Unit) = {}
) {
    val resolvedAnchorOwnerIdentity = anchorOwnerIdentity ?: clickIdentity ?: "button"
    val rootAnchor =
        remember(resolvedAnchorOwnerIdentity) { RaftLiveRootAnchorRef(identity = resolvedAnchorOwnerIdentity) }
    var pressed by remember(resolvedAnchorOwnerIdentity) { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongClick by rememberUpdatedState(onLongClick)
    val currentEnabled by rememberUpdatedState(enabled)
    val hasLongClick = onLongClick != null
    val gestureOwner =
        rememberRaftGestureOwner(
            surface = "button",
            semanticIdentity = resolvedAnchorOwnerIdentity,
            callbacks =
                RaftButtonGestureCallbacks(
                    enabled = enabled,
                    pressFeedback = pressFeedback,
                    onClick = onClick,
                    onLongClick = onLongClick,
                    onClick2 = onClick2,
                    onPressedChange = {
                        pressed = it
                        onPressedChange?.invoke(it)
                    },
                    rootAnchor = rootAnchor
                )
        )
    Box(
        modifier = Modifier
            .raftButtonAccessibility(
                enabled = enabled,
                performClick = { if (currentEnabled) currentOnClick() },
                longClickLabel = longClickLabel,
                performLongClick = currentOnLongClick?.let {
                    { if (currentEnabled) currentOnLongClick?.invoke() }
                },
            )
            .then(
                if (onClick2 != null) {
                    Modifier.raftCaptureLiveRootAnchor(rootAnchor)
                } else {
                    Modifier
                }
            )
            // `enabled` is read through the gesture owner's current slot. Keeping it
            // out of the pointer-input keys prevents Kuikly from tearing down
            // the tap handler when a dynamically-enabled button (for example
            // Undo/Clear after the first edit) transitions from false to true.
            .raftGesturePointerInput(gestureOwner, pressFeedback, hasLongClick) { owner ->
                var gesture: RaftGestureSession<RaftButtonGestureCallbacks>? = null
                var arbiter = RaftButtonGestureArbiter(hasLongClick)
                detectTapGestures(
                    onPress = {
                        arbiter = RaftButtonGestureArbiter(hasLongClick)
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
                                callbacks.rootAnchor.resolve(RaftPopoverLocalPoint.Exact(offset))?.let { anchor ->
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
            .raftPressedOffset(pressed = pressed && enabled, pressOffset = pressOffset)
            .then(modifier),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/** Owns the one-gesture tap/long-press decision used by the production pointer callbacks. */
internal class RaftButtonGestureArbiter(private val hasLongClick: Boolean) {
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

private data class RaftButtonGestureCallbacks(
    val enabled: Boolean,
    val pressFeedback: Boolean,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)?,
    val onClick2: ((RaftPopoverAnchor) -> Unit)?,
    val onPressedChange: (Boolean) -> Unit,
    val rootAnchor: RaftLiveRootAnchorRef
)

internal class Border(
    val lineWidth: Dp,
    val lineStyle: BorderStyle,
    val color: Color
)

internal fun Modifier.border(border: Border) =
    this.border(width = border.lineWidth, color = border.color).raftSquareCorners()

internal fun raftTextFieldResolvedTextStyle(textStyle: TextStyle): TextStyle =
    textStyle.withResolvedRaftFontFamily()

internal fun raftTextFieldSingleLine(maxLines: Int): Boolean = maxLines == 1

/**
 * CMP port of the Kuikly text-area `imeActionRequested` prop: a multi-line native editor that was
 * not asked for an action key stayed multi-line, so the IME showed a newline key. Compose shows the
 * action key on multi-line fields too, so drop the action in that case.
 */
internal fun raftResolvedKeyboardOptions(
    keyboardOptions: KeyboardOptions,
    singleLine: Boolean,
    imeActionRequested: Boolean,
): KeyboardOptions =
    if (!singleLine && !imeActionRequested && keyboardOptions.imeAction != ImeAction.Default) {
        keyboardOptions.copy(imeAction = ImeAction.Default)
    } else {
        keyboardOptions
    }

/**
 * CMP port of the Android host's hardware-Enter routing (RaftLegacyHostActivity
 * handleRaftTextFieldEnterKey): when the field's IME action is Send, Enter sends on key-up and
 * Shift+Enter inserts a newline (multi-line fields only); which of the two is decided on key-down
 * ([RaftHardwareEnterRouter]). Compose would otherwise insert a newline
 * for every hardware Enter on a multi-line field.
 *
 * TODO(cmp-switch): `systemNewlineAction` (an "Insert newline" item in the Android text
 * selection menu) has no common Compose API yet; it is still ignored.
 * TODO(cmp-switch): verify on iOS that the soft-keyboard Send key on a multi-line field fires
 * KeyboardActions.onSend instead of inserting "\n".
 */
@Composable
internal fun Modifier.raftImeEnterAction(
    singleLine: Boolean,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions,
): Modifier {
    val router = remember { RaftHardwareEnterRouter() }
    if (keyboardOptions.imeAction != ImeAction.Send) return this
    val onSend = keyboardActions.onSend ?: return this
    return onPreviewKeyEvent { event ->
        if (event.key != Key.Enter && event.key != Key.NumPadEnter) return@onPreviewKeyEvent false
        val phase =
            when (event.type) {
                KeyEventType.KeyDown -> RaftHardwareEnterRouter.Phase.Down
                KeyEventType.KeyUp -> RaftHardwareEnterRouter.Phase.Up
                else -> return@onPreviewKeyEvent false
            }
        router.onEnter(phase, shiftPressed = event.isShiftPressed, singleLine = singleLine) {
            onSend(RaftHardwareEnterKeyboardActionScope)
        }
    }
}

/**
 * Per-field hardware Enter decision for [raftImeEnterAction]. Send vs newline is decided on the
 * key-down (by the Shift state at that moment) and the key-up only carries it out, so releasing
 * Shift a moment before Enter still inserts the newline instead of sending, and a key-up whose
 * key-down this field did not see (focus moved mid-press) does nothing.
 */
internal class RaftHardwareEnterRouter {
    enum class Phase { Down, Up }

    private enum class Decision { Send, Newline }

    private var pending: Decision? = null

    /** Returns true when the event is consumed (the field must not handle it). */
    fun onEnter(phase: Phase, shiftPressed: Boolean, singleLine: Boolean, send: () -> Unit): Boolean =
        when (phase) {
            Phase.Down -> {
                val decision = if (shiftPressed) Decision.Newline else Decision.Send
                pending = decision
                // Newline: a multi-line field inserts it; a single-line field swallows (no send).
                decision == Decision.Send || singleLine
            }
            Phase.Up -> {
                val decision = pending
                pending = null
                when (decision) {
                    Decision.Send -> {
                        send()
                        true
                    }
                    Decision.Newline -> singleLine
                    null -> false
                }
            }
        }
}

/**
 * Receiver of the `onSend` call [raftImeEnterAction] makes for a hardware Enter. Callers compare
 * against it to tell a hardware Enter (which must not send while the IME is composing: Enter
 * commits the conversion) from the soft keyboard's Send key (which always sends, committing any
 * open composition).
 */
internal object RaftHardwareEnterKeyboardActionScope : KeyboardActionScope {
    override fun defaultKeyboardAction(imeAction: ImeAction) = Unit
}

internal fun TextInputState.toRaftTextFieldValue(): TextFieldValue {
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

internal data class RaftTextFieldNativeEditorRead(
    val state: TextInputState?,
    val editorNativeRef: Int? = null,
)

internal sealed class RaftTextFieldNativeEditingStateReadResult {
    data class Available(
        val value: TextFieldValue,
        // nativeRef of the AutoHeightTextAreaView the state was read from (-1 when the
        // read path could not attribute one); matches ViewRef.nativeRef on the native side.
        val editorNativeRef: Int,
    ) : RaftTextFieldNativeEditingStateReadResult()

    data object Unavailable : RaftTextFieldNativeEditingStateReadResult()
}

internal fun TextFieldValue.toRaftTextInputState(): TextInputState =
    TextInputState(
        text = text,
        selectionStart = selection.start,
        selectionEnd = selection.end,
        compositionStart = composition?.start ?: TextInputState.NO_COMPOSITION,
        compositionEnd = composition?.end ?: TextInputState.NO_COMPOSITION,
    )

/**
 * Composition barrier for action-boundary reads (Send, draft capture): the IME's unfinished
 * composition (mid-dictation, mid-CJK conversion) is committed as-is, without dropping characters.
 *
 * Kuikly's Android editor ran `finishComposingText()` inside `getTextInputState` (7ae5b181a), so
 * the composer never received an open composing region. On CMP the reader returns the Compose
 * [TextFieldValue], whose text already contains the composing characters; clearing the range makes
 * the snapshot match that contract, and when the composer writes the snapshot back to the field
 * (acceptEditorValue) Compose sees a composition-only change and restarts input, which ends the
 * IME's composition the way `finishComposingText` did - so the IME cannot keep composing into
 * the text that is being sent.
 */
internal fun TextInputState.raftWithFinishedComposition(): TextInputState =
    if (compositionStart == TextInputState.NO_COMPOSITION && compositionEnd == TextInputState.NO_COMPOSITION) {
        this
    } else {
        copy(compositionStart = TextInputState.NO_COMPOSITION, compositionEnd = TextInputState.NO_COMPOSITION)
    }

/**
 * Reads the native editor's current text/selection/composition state at an action boundary.
 *
 * Native UIKit/Android editing can already be visible while its ordinary change callback is
 * still queued for Compose. Callers such as Send therefore need an explicit barrier instead of
 * assuming the last rendered [TextFieldValue] is the value currently on screen.
 */
internal class RaftTextFieldNativeEditingStateReader {
    private class Binding(
        val owner: Any,
        // An async read OPERATION: callers hand it the callback that receives the result.
        var read: ((RaftTextFieldNativeEditorRead) -> Unit) -> Unit,
    )

    private var binding: Binding? = null

    internal fun bind(
        owner: Any,
        read: ((RaftTextFieldNativeEditorRead) -> Unit) -> Unit,
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
        onResult: (RaftTextFieldNativeEditingStateReadResult) -> Unit,
    ) {
        val activeBinding = binding
        if (activeBinding == null) {
            onResult(RaftTextFieldNativeEditingStateReadResult.Unavailable)
            return
        }
        activeBinding.read { editorRead ->
            onResult(
                if (binding === activeBinding && editorRead.state != null) {
                    RaftTextFieldNativeEditingStateReadResult.Available(
                        value = editorRead.state.toRaftTextFieldValue(),
                        // Kuikly nativeRef of the resolved AutoHeightTextAreaView — the
                        // instance identity the native layer matches against
                        // viewWithNativeRef for the adoption commit-time editor fence.
                        editorNativeRef = editorRead.editorNativeRef ?: -1,
                    )
                } else {
                    RaftTextFieldNativeEditingStateReadResult.Unavailable
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
internal val LocalRaftDialogKeyboardHeightChange =
    compositionLocalOf<(KeyboardParams) -> Unit> { {} }

/**
 * The app's editor behaviour as a modifier for the editor node itself: the IME enter action
 * (systemNewlineAction / imeActionRequested were native-editor props; see [raftImeEnterAction] /
 * [raftResolvedKeyboardOptions]), dialog keyboard-height events, [focusRequester], focus / blur
 * callbacks and the global tap-outside focus-dismiss registry. [TextField] and the
 * design-system `RaftTextField` adapters (`RaftBrutalInputField`, `RaftBrutalSearchField`) share
 * it, so a field keeps the same behaviour whichever editor draws it.
 */
@Composable
internal fun Modifier.raftAppEditorBehavior(
    singleLine: Boolean,
    resolvedKeyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions,
    keyboardHeightChange: (KeyboardParams) -> Unit,
    focusRequester: FocusRequester,
    onFocus: () -> Unit,
    onBlur: () -> Unit,
    participatesInGlobalFocusDismiss: Boolean = true,
): Modifier {
    var lastFocus by remember { mutableStateOf(false) }
    val focusOwnerRegistry = LocalRaftInputFocusOwnerRegistry.current
    val focusOwner = remember { Any() }
    val focusOwnerAnchor = remember { RaftInputFocusOwnerAnchor() }
    val currentOnBlur by rememberUpdatedState(onBlur)
    val currentOnFocus by rememberUpdatedState(onFocus)
    val currentKeyboardHeightChange by rememberUpdatedState(keyboardHeightChange)
    val dialogKeyboardHeightChange = LocalRaftDialogKeyboardHeightChange.current
    DisposableEffect(focusOwnerRegistry, focusOwner, participatesInGlobalFocusDismiss) {
        onDispose {
            if (participatesInGlobalFocusDismiss) {
                focusOwnerRegistry?.remove(focusOwner)
            }
        }
    }
    return this
        .raftImeEnterAction(
            singleLine = singleLine,
            keyboardOptions = resolvedKeyboardOptions,
            keyboardActions = keyboardActions,
        )
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
}

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
    cursorBrush: Brush = SolidColor(if (RaftAppThemeState.isDark) RaftColor.TextBlack else Color.Black), // raft-color: light legacy cursor
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

    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnTextLayout by rememberUpdatedState(onTextLayout)

    val resolvedKeyboardOptions =
        raftResolvedKeyboardOptions(keyboardOptions, raftTextFieldSingleLine(maxLines), imeActionRequested)
    val updatedModifier = modifier
        .raftAppEditorBehavior(
            singleLine = raftTextFieldSingleLine(maxLines),
            resolvedKeyboardOptions = resolvedKeyboardOptions,
            keyboardActions = keyboardActions,
            keyboardHeightChange = keyboardHeightChange,
            focusRequester = focusRequester,
            onFocus = onFocus,
            onBlur = onBlur,
            participatesInGlobalFocusDismiss = participatesInGlobalFocusDismiss,
        )
    val resolvedTextStyle = raftTextFieldResolvedTextStyle(textStyle)
    // Focused: cursor and selection drags (and single-line scrolling) keep the drag from the
    // host's full-page back swipe.
    val keepsBackSwipe = remember { mutableStateOf(false) }
    val state = rememberRaftTextFieldState(value, currentOnValueChange, traceName = "TextField")
    RaftAppStateTextField(
        state = state,
        modifier = updatedModifier
            .raftKeepsBackSwipe(keepsBackSwipe.value)
            .onFocusChanged { keepsBackSwipe.value = it.isFocused },
        textStyle = resolvedTextStyle,
        maxLines = maxLines,
        onTextLayout = currentOnTextLayout,
        keyboardOptions = resolvedKeyboardOptions,
        keyboardActions = keyboardActions,
        cursorBrush = cursorBrush,
        enabled = enabled,
        readOnly = readOnly,
        visualTransformation = visualTransformation,
        decorator = { innerTextField ->
            Box(contentAlignment = contentAlignment) {
                if (state.text.isEmpty() && placeholder.isNotEmpty()) {
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
    cursorBrush: Brush = SolidColor(if (RaftAppThemeState.isDark) RaftColor.TextBlack else Color.Black), // raft-color: light legacy cursor
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
    nativeEditingStateReader: RaftTextFieldNativeEditingStateReader? = null,
) {
    val nativeEditingStateBindingOwner = remember { Any() }

    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnTextLayout by rememberUpdatedState(onTextLayout)
    DisposableEffect(nativeEditingStateReader, nativeEditingStateBindingOwner) {
        onDispose { nativeEditingStateReader?.unbind(nativeEditingStateBindingOwner) }
    }

    val resolvedKeyboardOptions =
        raftResolvedKeyboardOptions(keyboardOptions, raftTextFieldSingleLine(maxLines), imeActionRequested)
    val updatedModifier = modifier
        .raftAppEditorBehavior(
            singleLine = raftTextFieldSingleLine(maxLines),
            resolvedKeyboardOptions = resolvedKeyboardOptions,
            keyboardActions = keyboardActions,
            keyboardHeightChange = keyboardHeightChange,
            focusRequester = focusRequester,
            onFocus = onFocus,
            onBlur = onBlur,
            participatesInGlobalFocusDismiss = participatesInGlobalFocusDismiss,
        )
    // The editor edits a TextFieldState; the caller's value follows it (rememberRaftTextFieldState).
    // The "native" read is that state itself: it already holds an IME edit the caller has not been
    // told about or recomposed with yet (an IME commit and Send within one frame).
    val state = rememberRaftTextFieldState(value, { next -> currentOnValueChange(next) }, traceName = "TextField")
    if (nativeEditingStateReader != null) {
        SideEffect {
            nativeEditingStateReader.bind(nativeEditingStateBindingOwner) { callback ->
                callback(
                    RaftTextFieldNativeEditorRead(
                        state = state.toRaftTextFieldValue().toRaftTextInputState().raftWithFinishedComposition(),
                        editorNativeRef = -1,
                    )
                )
            }
        }
    }
    val resolvedTextStyle = raftTextFieldResolvedTextStyle(textStyle)
    // Focused: cursor and selection drags (and single-line scrolling) keep the drag from the
    // host's full-page back swipe.
    val keepsBackSwipe = remember { mutableStateOf(false) }
    RaftAppStateTextField(
        state = state,
        modifier = updatedModifier
            .raftKeepsBackSwipe(keepsBackSwipe.value)
            .onFocusChanged { keepsBackSwipe.value = it.isFocused },
        textStyle = resolvedTextStyle,
        maxLines = maxLines,
        onTextLayout = currentOnTextLayout,
        keyboardOptions = resolvedKeyboardOptions,
        keyboardActions = keyboardActions,
        cursorBrush = cursorBrush,
        enabled = enabled,
        readOnly = readOnly,
        visualTransformation = visualTransformation,
        decorator = { innerTextField ->
            Box(contentAlignment = contentAlignment) {
                if (state.text.isEmpty() && placeholder.isNotEmpty()) {
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


/**
 * The app editor's state-based BasicTextField: the value-based options of [TextField] mapped to
 * the TextFieldState API (line limits, keyboard action handler, output transformation, layout
 * callback), plus the text-input trace when it is on.
 */
@Composable
private fun RaftAppStateTextField(
    state: TextFieldState,
    modifier: Modifier,
    textStyle: TextStyle,
    maxLines: Int,
    onTextLayout: (TextLayoutResult) -> Unit,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions,
    cursorBrush: Brush,
    enabled: Boolean,
    readOnly: Boolean,
    visualTransformation: VisualTransformation,
    decorator: TextFieldDecorator,
) {
    val outputTransformation = remember(visualTransformation) { visualTransformation.toRaftOutputTransformation() }
    BasicTextField(
        state = state,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        inputTransformation = rememberRaftTextInputTraceTransformation("TextField"),
        textStyle = textStyle,
        keyboardOptions = keyboardOptions,
        onKeyboardAction = keyboardActions.toRaftKeyboardActionHandler(keyboardOptions.imeAction),
        lineLimits = raftTextFieldLineLimits(singleLine = raftTextFieldSingleLine(maxLines), maxLines = maxLines),
        onTextLayout = { getResult -> getResult()?.let(onTextLayout) },
        cursorBrush = cursorBrush,
        outputTransformation = outputTransformation,
        decorator = decorator,
    )
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
            RaftBackButton(
                onClick = { routerModule?.closePage() },
                label = stringResource(Res.string.common_back),
                modifier = Modifier.align(Alignment.CenterStart).padding(start = RaftBackButtonDefaults.EdgeInset)
            )
            Text(
                title,
                style = RaftType.PageTitle
                    .copy(fontSize = 22.sp, lineHeight = 28.sp)
                    .withRaftFontWeight(FontWeight.SemiBold),
                textAlign = TextAlign.Center,
                color = if (RaftAppThemeState.isDark) RaftColor.TextBlack else Color(0xFF333333), // raft-color: light legacy title ink
                modifier = Modifier
                    .padding(vertical = 12.dp, horizontal = 12.dp)
                    .fillMaxWidth(),
            )
        }
        content.invoke()
    }
}
