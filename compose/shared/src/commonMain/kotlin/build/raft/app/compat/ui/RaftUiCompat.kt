package build.raft.app.compat.ui

import build.raft.designsystem.components.LocalRaftToastAvoidanceActive
import build.raft.designsystem.gesture.LocalRaftBackSwipeActive
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler as ComposeBackHandler
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll as composeNestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Velocity
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.PlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.maxBitmapSize
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import build.raft.app.image.raftInstallImageLoader
import build.raft.app.image.raftPlatformMaxDisplayBitmapEdgePx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Collects [this] only while the enclosing [LocalLifecycleOwner] is at least [Lifecycle.State.STARTED],
 * holding the last received value while covered (`CREATED` in `RaftMobilePageStackDisplay`).
 *
 * When the page returns to [Lifecycle.State.STARTED], collection restarts and catches up from the
 * upstream `StateFlow` / replay flow immediately, so covered pages (the app shell or an older
 * conversation under a pushed thread) do not recompose mid-transition on store updates.
 */
@Composable
internal fun <T> Flow<T>.collectAsStateWhileStarted(initial: T): State<T> {
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = remember(this) { mutableStateOf(initial) }
    LaunchedEffect(this, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            collect { state.value = it }
        }
    }
    return state
}

/** [collectAsStateWhileStarted] for a [StateFlow], seeded with its live [StateFlow.value]. */
@Composable
internal fun <T> StateFlow<T>.collectAsStateWhileStarted(): State<T> = collectAsStateWhileStarted(value)

/*
 * TODO(cmp-switch): replacements for the Kuikly-only compose extensions
 * (Kuikly compose `extension.*`, `coil3.rememberAsyncImageBitmap`, `resources.*`,
 * `BackHandler`, `withKuiklyIdleContext`). Same names and signatures so call sites compile; the
 * ones that only tuned Kuikly's native views are documented no-ops. Prefer upstream APIs in new
 * code (WindowInsets.ime / imePadding, NestedScrollConnection, Coil, BackHandler).
 */

/** `#AARRGGBB` / `#RRGGBB` (Kuikly colour strings) to a Compose [Color]; black when malformed. */
fun raftColorFromArgbHex(value: String): Color {
    val hex = value.trim().removePrefix("#")
    val argb =
        when (hex.length) {
            8 -> hex.toLongOrNull(16)
            6 -> hex.toLongOrNull(16)?.let { it or 0xFF000000L }
            else -> null
        } ?: 0xFF000000L
    return Color(argb.toInt())
}

// ---- Back handling ----------------------------------------------------------------------------

/**
 * Whether the enclosing page is the active one. Kept-alive inactive pages (hidden app-shell tabs,
 * `RaftChildPage` with `active = false`) stay composed, so without this their back
 * handlers would stay registered and, being registered after the shell's, would win: Back would
 * close a menu on an invisible tab. Provided by `RaftPagerHost` via [RaftProvidePageActive].
 */
val LocalRaftPageActive = compositionLocalOf { true }

/** Provides [LocalRaftPageActive] for [content]: active only if [active] and every ancestor page is. */
@Composable
fun RaftProvidePageActive(
    active: Boolean,
    toastAvoidActive: Boolean = active,
    content: @Composable () -> Unit,
) {
    val parentActive = LocalRaftPageActive.current
    val pageActive = parentActive && active
    val parentToastAvoidActive = LocalRaftToastAvoidanceActive.current
    val effectiveToastAvoidActive = parentToastAvoidActive && toastAvoidActive
    CompositionLocalProvider(
        LocalRaftPageActive provides pageActive,
        // Inactive kept-alive content may keep stale bounds: its back-swipe regions never report.
        LocalRaftBackSwipeActive provides pageActive,
        // Top stack pages report toast avoidances immediately (even while sliding in) so a toast
        // triggered right after a push or pop never hits a 300ms empty-avoidance dead zone.
        LocalRaftToastAvoidanceActive provides effectiveToastAvoidActive,
        content = content,
    )
}

/** A back handler's effective `enabled`: the caller's flag AND the enclosing page being active. */
@Composable
fun raftBackHandlerEnabled(enabled: Boolean): Boolean = enabled && LocalRaftPageActive.current

/**
 * Kuikly `BackHandler`, on CMP's multiplatform back handler. Disabled while the enclosing page is
 * inactive ([LocalRaftPageActive]).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    ComposeBackHandler(enabled = raftBackHandlerEnabled(enabled), onBack = onBack)
}

// ---- Coroutines --------------------------------------------------------------------------------

/** Kuikly ran [block] when its render thread was idle; CMP has no such phase. */
suspend fun <T> withKuiklyIdleContext(pagerId: String, block: suspend CoroutineScope.() -> T): T =
    coroutineScope(block)

// ---- Keyboard ----------------------------------------------------------------------------------

/** Kuikly keyboard event payload: [height] in dp, [duration] in seconds. */
data class KeyboardParams(val height: Float, val duration: Float)

/**
 * Kuikly `keyboardHeightChange`: reports the IME height (dp) whenever it changes, from
 * `WindowInsets.ime`. CMP animates the inset frame by frame, so [KeyboardParams.duration] is 0.
 *
 * Keeps Kuikly's per-platform meaning of the height: on Android, Kuikly's keyboard watcher
 * reported `ime - navigationBars` and the host advertises that through
 * `raftKeyboardInsetExcludesBottomSafeArea`, so pages add their bottom safe area back while the
 * keyboard is open. Reporting the raw `WindowInsets.ime` bottom (which already includes the
 * navigation bar) counted the navigation bar twice and left a gap between the composer and the
 * keyboard. See [raftKeyboardHeightExcludesNavigationBars].
 */
fun Modifier.keyboardHeightChange(keyboardHeightChange: (KeyboardParams) -> Unit): Modifier =
    composed {
        val density = LocalDensity.current
        val imeBottom = raftImeBottomPx(density)
        val navigationBottom =
            if (raftKeyboardHeightExcludesNavigationBars) WindowInsets.navigationBars.getBottom(density) else 0
        val keyboardPx = (imeBottom - navigationBottom).coerceAtLeast(0)
        val callback by rememberUpdatedState(keyboardHeightChange)
        val heightDp = with(density) { keyboardPx.toDp().value }
        LaunchedEffect(heightDp) { callback(KeyboardParams(heightDp, 0f)) }
        this
    }

/**
 * True where the Kuikly host reported keyboard heights without the navigation bar (Android:
 * `ime - navigationBars`); false where it reported the full keyboard frame (iOS).
 */
internal expect val raftKeyboardHeightExcludesNavigationBars: Boolean

/** Kuikly iOS interactive keyboard dismissal: no CMP equivalent. TODO(cmp-switch). */
fun Modifier.keyboardDismissModeInteractiveIOS(): Modifier = this

// ---- Scrolling ---------------------------------------------------------------------------------

/** Kuikly nested-scroll policy per direction. */
enum class NestedScrollMode(val value: String) {
    SELF_ONLY("SELF_ONLY"),
    SELF_FIRST("SELF_FIRST"),
    PARENT_FIRST("PARENT_FIRST"),
}

/**
 * Kuikly `nestedScroll(scrollUp, scrollDown)`. `SELF_ONLY` keeps the leftover scroll/fling of
 * that direction from reaching parents (CMP's default is "self first, then parent", which covers
 * `SELF_FIRST`). `PARENT_FIRST` is treated as `SELF_FIRST`. TODO(cmp-switch): verify call sites.
 */
fun Modifier.nestedScroll(
    scrollUp: NestedScrollMode = NestedScrollMode.SELF_FIRST,
    scrollDown: NestedScrollMode = NestedScrollMode.SELF_FIRST,
): Modifier {
    if (scrollUp != NestedScrollMode.SELF_ONLY && scrollDown != NestedScrollMode.SELF_ONLY) return this
    val connection =
        object : NestedScrollConnection {
            private fun blocks(delta: Float): Boolean =
                // Content moving up (finger up, delta < 0) is a "scroll up" in Kuikly's terms.
                (delta < 0f && scrollUp == NestedScrollMode.SELF_ONLY) ||
                    (delta > 0f && scrollDown == NestedScrollMode.SELF_ONLY)

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                Offset(
                    x = if (blocks(available.x)) available.x else 0f,
                    y = if (blocks(available.y)) available.y else 0f,
                )

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                Velocity(
                    x = if (blocks(available.x)) available.x else 0f,
                    y = if (blocks(available.y)) available.y else 0f,
                )
        }
    return this.composeNestedScroll(connection)
}

/** Kuikly iOS bounce toggle: CMP's overscroll is not configurable per list. TODO(cmp-switch). */
fun Modifier.bouncesEnable(enable: Boolean): Modifier = this

/** Kuikly iOS status-bar-tap scroll-to-top hook: not delivered on CMP. TODO(cmp-switch). */
fun Modifier.scrollToTop(onScrollToTop: () -> Unit): Modifier = this

// ---- Native view props -------------------------------------------------------------------------

/**
 * Kuikly `setProp`: pushed a raw property to the backing native view. There is no native view on
 * CMP, so this is a no-op; each call site must express the property in Compose
 * (TODO(cmp-switch): e.g. square corners -> clip(RectangleShape), IME props -> KeyboardOptions).
 */
fun Modifier.setProp(key: String, value: Any): Modifier = this

// ---- Images ------------------------------------------------------------------------------------

/**
 * Kuikly `rememberAsyncImageBitmap`: the decoded bitmap for [model] (URL, `assets://` path,
 * managed avatar model, inline SVG, local path), or null while loading / on failure. Loaded
 * through the Raft singleton Coil loader (`raftBuildImageLoader`: Ktor fetcher, bundled
 * assets, SVG).
 *
 * Decoded with its longer edge bounded by the window's long edge (aspect ratio kept), like the
 * Kuikly Android adapter that downsampled to the screen: a camera photo must never be decoded at
 * full resolution just to be shown on a phone. Use [rememberAsyncImageBitmap] with an explicit
 * `maxDecodeEdgePx` for smaller targets.
 */
@Composable
fun rememberAsyncImageBitmap(model: String?): ImageBitmap? =
    rememberAsyncImageBitmap(model, maxDecodeEdgePx = rememberRaftDisplayImageDecodeEdgePx())

/**
 * Builds a screen- or export-bounded Coil [ImageRequest] shared by preview painters
 * (`RaftCachedImage`) and bitmap loaders ([rememberAsyncImageBitmap], [raftLoadImageBitmap]) so
 * preview -> editor transitions hit Coil's memory cache instead of re-decoding the same photo from
 * disk (#wg-mobile-feedback:#269). When [overrideMaxBitmapSize] is true (high-res exports or
 * bundled pixel-coordinate atlases), also overrides per-request `maxBitmapSize` past the platform
 * loader's display cap.
 */
internal fun raftBoundedImageRequest(
    context: PlatformContext,
    source: String,
    maxDecodeEdgePx: Int,
    memoryCacheKey: String? = null,
    diskCacheKey: String? = null,
    disableMemoryCache: Boolean = false,
    overrideMaxBitmapSize: Boolean = false,
    configure: (ImageRequest.Builder.() -> Unit)? = null,
): ImageRequest {
    val decodeSize = raftImageDecodeSize(maxDecodeEdgePx)
    return ImageRequest.Builder(context)
        .data(source)
        .size(decodeSize)
        .scale(Scale.FIT)
        .precision(Precision.INEXACT)
        .apply {
            if (overrideMaxBitmapSize) maxBitmapSize(decodeSize)
            if (disableMemoryCache) memoryCachePolicy(CachePolicy.DISABLED)
            memoryCacheKey?.trim()?.takeIf { it.isNotEmpty() }?.let { memoryCacheKey(it) }
            diskCacheKey?.trim()?.takeIf { it.isNotEmpty() }?.let { diskCacheKey(it) }
            configure?.invoke(this)
        }
        .build()
}

/**
 * [rememberAsyncImageBitmap] with the decoded longer edge bounded by [maxDecodeEdgePx]
 * ([RAFT_IMAGE_DECODE_ORIGINAL] decodes at the original size: only for bundled atlases whose
 * pixel coordinates matter, never for user content).
 */
@Composable
fun rememberAsyncImageBitmap(
    model: String?,
    maxDecodeEdgePx: Int,
    diskCacheKey: String? = null,
): ImageBitmap? {
    val source = model?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val context = LocalPlatformContext.current
    val decodeSize = raftImageDecodeSize(maxDecodeEdgePx)
    var bitmap by remember(source, decodeSize, diskCacheKey) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(source, decodeSize, diskCacheKey) {
        raftInstallImageLoader()
        val request =
            raftBoundedImageRequest(
                context = context,
                source = source,
                maxDecodeEdgePx = maxDecodeEdgePx,
                diskCacheKey = diskCacheKey,
                // Bundled atlases (RAFT_IMAGE_DECODE_ORIGINAL) rely on source pixel coordinates and
                // must bypass the platform display bitmap cap.
                overrideMaxBitmapSize = maxDecodeEdgePx == RAFT_IMAGE_DECODE_ORIGINAL,
            )
        val result = SingletonImageLoader.get(context).execute(request)
        val decodedImage = (result as? SuccessResult)?.image
        // Copy/convert the decoded Coil image into a Compose ImageBitmap off the main thread so
        // Skia's toBitmap() allocation does not stall UIKit/Compose transitions or first gestures.
        bitmap =
            if (decodedImage != null) {
                withContext(Dispatchers.Default) { decodedImage.toRaftImageBitmap() }
            } else {
                null
            }
    }
    return bitmap
}

/**
 * One-shot decode of [model] outside composition, with its longer edge bounded by
 * [maxDecodeEdgePx] (never upscaled). Skips writing Coil's memory cache: callers are exports that
 * need a large bitmap once (the image editor's full-resolution save), not something to keep.
 */
internal suspend fun raftLoadImageBitmap(
    context: PlatformContext,
    model: String,
    maxDecodeEdgePx: Int,
): ImageBitmap? {
    val source = model.trim().takeIf { it.isNotEmpty() } ?: return null
    raftInstallImageLoader()
    val request =
        raftBoundedImageRequest(
            context = context,
            source = source,
            maxDecodeEdgePx = maxDecodeEdgePx,
            disableMemoryCache = true,
            overrideMaxBitmapSize = true,
        )
    val result = SingletonImageLoader.get(context).execute(request)
    val decodedImage = (result as? SuccessResult)?.image ?: return null
    return withContext(Dispatchers.Default) { decodedImage.toRaftImageBitmap() }
}

/** Pass as `maxDecodeEdgePx` to decode at the original size (bundled atlases only). */
const val RAFT_IMAGE_DECODE_ORIGINAL: Int = Int.MAX_VALUE

/** Fallback decode bound when the window size is not known yet (a large phone's long edge). */
internal const val RAFT_IMAGE_DECODE_FALLBACK_EDGE_PX: Int = 2400

/** Coil decode size for a long-edge bound: a square box, so FIT bounds the longer edge. */
internal fun raftImageDecodeSize(maxDecodeEdgePx: Int): Size =
    when {
        maxDecodeEdgePx == RAFT_IMAGE_DECODE_ORIGINAL -> Size.ORIGINAL
        maxDecodeEdgePx <= 0 -> Size(RAFT_IMAGE_DECODE_FALLBACK_EDGE_PX, RAFT_IMAGE_DECODE_FALLBACK_EDGE_PX)
        else -> Size(maxDecodeEdgePx, maxDecodeEdgePx)
    }

/**
 * Display decode bound (longer edge in px) clamped to [platformMaxEdgePx] when the platform loader
 * enforces a smaller `maxBitmapSize` (e.g. 2048 px on iOS/Desktop). Keeping the requested size at
 * or below the loader cap prevents Coil's memory-cache size check from rejecting a sampled cached
 * bitmap on preview -> editor handoff.
 */
internal fun raftDisplayImageDecodeEdgePx(
    windowLongEdgePx: Int,
    platformMaxEdgePx: Int? = raftPlatformMaxDisplayBitmapEdgePx,
): Int {
    val windowBound = if (windowLongEdgePx > 0) windowLongEdgePx else RAFT_IMAGE_DECODE_FALLBACK_EDGE_PX
    return if (platformMaxEdgePx != null && platformMaxEdgePx > 0) {
        minOf(windowBound, platformMaxEdgePx)
    } else {
        windowBound
    }
}

/** The window's longer edge in px, or a phone-sized fallback. */
@Composable
fun rememberRaftWindowLongEdgePx(): Int {
    val container = LocalWindowInfo.current.containerSize
    val longEdge = maxOf(container.width, container.height)
    return if (longEdge > 0) longEdge else RAFT_IMAGE_DECODE_FALLBACK_EDGE_PX
}

/** Display decode bound in px shared by full-screen image previews and the image editor. */
@Composable
internal fun rememberRaftDisplayImageDecodeEdgePx(): Int =
    raftDisplayImageDecodeEdgePx(rememberRaftWindowLongEdgePx())

internal expect fun coil3.Image.toRaftImageBitmap(): ImageBitmap

/**
 * This image as one a software canvas can draw: a graphics-layer snapshot on Android is a
 * `HARDWARE` bitmap, which a software Canvas refuses. The other platforms return it as is.
 */
internal expect fun ImageBitmap.raftSoftwareImageBitmap(): ImageBitmap

/** Kuikly `DrawableResource`: an image addressed by URL/asset path (resolved by the host). */
class DrawableResource(val path: String)

/** Kuikly `imageResource`: async-loaded; a 1x1 transparent bitmap until it is available. */
@Composable
fun imageResource(resource: DrawableResource): ImageBitmap =
    // Bundled atlases: callers draw sub-rects in source pixel coordinates.
    rememberAsyncImageBitmap(resource.path, maxDecodeEdgePx = RAFT_IMAGE_DECODE_ORIGINAL)
        ?: remember { ImageBitmap(1, 1) }

/** Kuikly `@InternalResourceApi` marker. */
@RequiresOptIn
annotation class InternalResourceApi

// ---- Text input / borders ----------------------------------------------------------------------

/** Kuikly `TextInputState`: an editor's text, selection and IME composition (-1 = none). */
data class TextInputState(
    val text: String,
    val selectionStart: Int = text.length,
    val selectionEnd: Int = selectionStart,
    val compositionStart: Int = NO_COMPOSITION,
    val compositionEnd: Int = NO_COMPOSITION,
    val length: Int? = null,
) {
    companion object {
        const val NO_COMPOSITION = -1
    }
}

/** Kuikly `BorderStyle`. Compose borders are always solid. */
enum class BorderStyle(val value: String) {
    SOLID("solid"),
    DOTTED("dotted"),
    DASHED("dashed"),
}

// ---- Media -------------------------------------------------------------------------------------

/** Kuikly `VideoPlayControl` (commands for the native video view). */
enum class VideoPlayControl(val value: Int) { PREPLAY(1), PLAY(2), PAUSE(3), STOP(4) }

/** Kuikly `PlayState` (native video/audio player state). */
enum class PlayState {
    NONE,
    PLAYING,
    BUFFERING,
    PAUSED,
    PLAY_END,
    ERROR;

    companion object {
        fun fromInt(value: Int): PlayState = entries.firstOrNull { it.ordinal == value } ?: NONE
    }
}

// ---- Pointer -----------------------------------------------------------------------------------

/**
 * Kuikly `PointerEvent.nativeEvent` ("cancel" when the native touch sequence was cancelled).
 * Upstream has no common equivalent; always null. TODO(cmp-switch): detect cancellation from the
 * pointer changes (e.g. all changes consumed / pointer removed without release) where it matters.
 */
val androidx.compose.ui.input.pointer.PointerEvent.nativeEvent: Any?
    get() = null
