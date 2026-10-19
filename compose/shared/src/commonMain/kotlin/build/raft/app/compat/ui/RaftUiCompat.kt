package build.raft.app.compat.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.Velocity
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import build.raft.app.image.raftInstallImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope

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

/** Kuikly `BackHandler`, on CMP's multiplatform back handler. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    ComposeBackHandler(enabled = enabled, onBack = onBack)
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
 */
fun Modifier.keyboardHeightChange(keyboardHeightChange: (KeyboardParams) -> Unit): Modifier =
    composed {
        val density = LocalDensity.current
        val imeBottom = WindowInsets.ime.getBottom(density)
        val callback by rememberUpdatedState(keyboardHeightChange)
        val heightDp = with(density) { imeBottom.toDp().value }
        LaunchedEffect(heightDp) { callback(KeyboardParams(heightDp, 0f)) }
        this
    }

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
 * through the Raft singleton Coil loader (`raftBuildImageLoader`: NetworkKMM fetcher, bundled
 * assets, SVG). Decoded at the original size, like the Kuikly helper (callers draw sub-rects of
 * atlases from it).
 */
@Composable
fun rememberAsyncImageBitmap(model: String?): ImageBitmap? {
    val source = model?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val context = LocalPlatformContext.current
    var bitmap by remember(source) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(source) {
        raftInstallImageLoader()
        val request = ImageRequest.Builder(context).data(source).size(Size.ORIGINAL).build()
        val result = SingletonImageLoader.get(context).execute(request)
        bitmap = (result as? SuccessResult)?.image?.toRaftImageBitmap()
    }
    return bitmap
}

internal expect fun coil3.Image.toRaftImageBitmap(): ImageBitmap

/** Kuikly `DrawableResource`: an image addressed by URL/asset path (resolved by the host). */
class DrawableResource(val path: String)

/** Kuikly `imageResource`: async-loaded; a 1x1 transparent bitmap until it is available. */
@Composable
fun imageResource(resource: DrawableResource): ImageBitmap =
    rememberAsyncImageBitmap(resource.path) ?: remember { ImageBitmap(1, 1) }

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
