package build.raft.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import build.raft.app.compat.json.JSONObject
import build.raft.designsystem.components.RaftLocatedState
import build.raft.designsystem.motion.rememberRaftReducedMotion
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Opening a Settings search result (raft-design settings.md#searching-settings, "Opening one"):
 * its page opens, scrolls the setting into view and marks it located (states.md#located: `info-soft`
 * at once, held 1.2s, gone at 2s; [RaftLocatedState]). The web does it with /settings/<page>#<id>
 * and `useScrollToSettingsSection`; here the page data carries the setting's id
 * ([SETTINGS_SEARCH_ANCHOR], intent only, as the route contract asks), the page provides a
 * [SettingsSearchLocator], and the setting's row wears [settingsSearchAnchor].
 */

/** Page data: the id of the setting a search result opens its page at (the web's `#<id>`). */
internal const val SETTINGS_SEARCH_ANCHOR = "settingsSearchAnchor"

/** Page data: one per opening, so opening the same result again locates it again. */
internal const val SETTINGS_SEARCH_ANCHOR_NONCE = "settingsSearchAnchorNonce"

private val SettingsSearchAnchorId = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")

/** A search result's request to open its page at [id]; [nonce] tells one opening from the next. */
internal data class SettingsSearchAnchorRequest(val id: String, val nonce: Long = 0L)

internal fun JSONObject.putSettingsSearchAnchor(request: SettingsSearchAnchorRequest?): JSONObject = apply {
    val id = request?.id?.takeIf(SettingsSearchAnchorId::matches) ?: return@apply
    put(SETTINGS_SEARCH_ANCHOR, id)
    put(SETTINGS_SEARCH_ANCHOR_NONCE, request.nonce)
}

/** The anchor a page was opened at, or null: none, or not an index id. */
internal fun settingsSearchAnchorFromPageData(params: JSONObject): SettingsSearchAnchorRequest? {
    val id = params.optString(SETTINGS_SEARCH_ANCHOR).trim().takeIf(SettingsSearchAnchorId::matches) ?: return null
    return SettingsSearchAnchorRequest(id, params.optLong(SETTINGS_SEARCH_ANCHOR_NONCE, 0L))
}

/** The `#<id>` a page drawn by the web opens at (its `useScrollToSettingsSection`). */
internal fun settingsSearchWebUrlWithAnchor(url: String, anchor: SettingsSearchAnchorRequest?): String {
    val id = anchor?.id?.takeIf(SettingsSearchAnchorId::matches) ?: return url
    return url.substringBefore('#') + "#" + id
}

/**
 * One page's located setting: [targetId] is the row to bring into view and mark, once. The row
 * reports itself composed ([anchorComposed]) so a lazy list knows when to stop seeking it, and
 * [settled] once it is in view (or the seek gave up).
 */
@Stable
internal class SettingsSearchLocator(val targetId: String, reducedMotion: Boolean = false) {
    val located = RaftLocatedState(reducedMotion)
    var anchorComposed by mutableStateOf(false)
        internal set
    var settled by mutableStateOf(false)
        internal set
}

internal val LocalSettingsSearchLocator = staticCompositionLocalOf<SettingsSearchLocator?> { null }

/** Gives [content] the locator for the anchor in [params] (none without one). */
@Composable
internal fun ProvideSettingsSearchLocator(params: JSONObject, content: @Composable () -> Unit) {
    val request = remember(params.toString()) { settingsSearchAnchorFromPageData(params) }
    val reducedMotion = rememberRaftReducedMotion()
    val locator = remember(request) { request?.let { SettingsSearchLocator(it.id, reducedMotion) } }
    CompositionLocalProvider(LocalSettingsSearchLocator provides locator, content = content)
}

/** The located highlight's shape on a row: the list cell's (raft-ui `rounded-control`). */
internal val SettingsSearchRowShape: Shape = RoundedCornerShape(RaftRadius.Control)

/** On a whole card: the card's (raft-ui `rounded-container`). */
internal val SettingsSearchCardShape: Shape = RoundedCornerShape(RaftRadius.Container)

/**
 * How far the highlight reaches outside a section that starts with its own header (an icon and a
 * title over its card: [SettingsFormSection], [SettingsFormSectionCard]). Laid over the block's
 * bounds, the tint ran flush against the header's icon and title (artin, Hands raft-android
 * eaeefff5: "这个框没有padding"); 8dp out, as far as a list cell's located shape stands in from its
 * row (states.md#located), gives the header room. Pages keep their 16dp side inset in a padding
 * the list's cross-axis clip reaches past, and their top and bottom inset in `contentPadding`, so a
 * section first on its page is not cut at the top. The web lays its fill on the section's own box
 * (raft-ui styles.css `[data-located]`), which has the same flush look.
 */
internal val SettingsSearchSectionOutset: Dp = 8.dp

/**
 * On a section with its own header: the card's corner, grown by [SettingsSearchSectionOutset] so it
 * stays concentric with the card inside, [SettingsSearchSectionOutset] outside the bounds.
 */
internal val SettingsSearchSectionShape: Shape =
    SettingsSearchOutsetShape(radius = RaftRadius.Container + SettingsSearchSectionOutset, outset = SettingsSearchSectionOutset)

/** A rounded rectangle [outset] outside the bounds it is laid on, corners of [radius]. */
internal data class SettingsSearchOutsetShape(val radius: Dp, val outset: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val bounds = settingsSearchOutsetBounds(size, with(density) { outset.toPx() })
        val corner = with(density) { radius.toPx() }
        return Outline.Rounded(RoundRect(bounds, CornerRadius(corner)))
    }
}

/** [size]'s bounds grown by [outset] on every side (pure: the highlight's geometry). */
internal fun settingsSearchOutsetBounds(size: Size, outset: Float): Rect =
    Rect(left = -outset, top = -outset, right = size.width + outset, bottom = size.height + outset)

/**
 * Marks the setting [id] on its page: when the page was opened at it, the row is brought into view
 * once it is laid out, then shows the located highlight in [shape]. The highlight is laid over the
 * row (around it, in [SettingsSearchSectionShape]) and multiplied in where `info-soft` is opaque
 * (light), so it tints the card's own fill and leaves the text as it is, also on a card that paints
 * its own background; in dark `info-soft` is already translucent. Draw-phase only: the fade never
 * recomposes.
 */
@Composable
internal fun Modifier.settingsSearchAnchor(id: String, shape: Shape = SettingsSearchRowShape): Modifier {
    val locator = LocalSettingsSearchLocator.current
    if (locator == null || locator.targetId != id) return this
    val requester = remember { BringIntoViewRequester() }
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    // Read in the effect only, so a size change doesn't recompose.
    val laidOutSize = remember { arrayOf(Size.Unspecified) }
    DisposableEffect(locator) {
        locator.anchorComposed = true
        onDispose { locator.anchorComposed = false }
    }
    LaunchedEffect(locator) {
        if (locator.settled) return@LaunchedEffect
        // After the row's first layout, so its bounds are known.
        withFrameNanos {}
        // The whole highlight, also where it reaches outside the row ([SettingsSearchSectionShape]).
        val size = laidOutSize[0]
        requester.bringIntoView(if (size.isSpecified) shape.createOutline(size, layoutDirection, density).bounds else null)
        locator.settled = true
        locator.located.locate(id)
    }
    val fill = RaftTheme.colors.infoSoft
    val blend = if (fill.alpha >= 0.99f) BlendMode.Multiply else BlendMode.SrcOver
    return onSizeChanged { laidOutSize[0] = it.toSize() }.bringIntoViewRequester(requester).drawWithContent {
        drawContent()
        val strength = locator.located.strengthFor(id)
        if (strength > 0f) drawOutline(shape.createOutline(size, layoutDirection, this), fill, alpha = strength, blendMode = blend)
    }
}

/** What a lazy Settings page does next while it seeks its located row. */
internal enum class SettingsSearchSeekStep {
    /** Wait a frame: the row is composed (it brings itself into view), or the page is still loading. */
    Wait,

    /** Scroll on by most of a screen, to compose the rows below. */
    Scroll,

    /** Stop: the person scrolled the page themselves, which replaces the intent. */
    Stop,
}

/**
 * The seek's next step (docs/programmatic-scroll-ownership-contract.md): the intent's owner is the
 * search result's opening (one locator per opened page); the fence is the person's own scroll
 * ([userScrolling], seen between the seek's own steps), which ends it for good.
 */
internal fun settingsSearchSeekStep(
    anchorComposed: Boolean,
    userScrolling: Boolean,
    itemCount: Int,
    canScrollForward: Boolean,
): SettingsSearchSeekStep =
    when {
        userScrolling -> SettingsSearchSeekStep.Stop
        anchorComposed || itemCount == 0 || !canScrollForward -> SettingsSearchSeekStep.Wait
        else -> SettingsSearchSeekStep.Scroll
    }

/** How long a page may take to load and show its located row before the seek gives up. */
internal const val SettingsSearchSeekTimeoutMillis = 6_000L

/**
 * A lazy Settings page's list state that, when the page was opened at a setting, scrolls on until
 * the setting's row is composed ([settingsSearchAnchor] then brings it into view). A row further
 * down a lazy list is not composed until it is near the screen, so it cannot bring itself there.
 * Bounded by [SettingsSearchSeekTimeoutMillis]; a row that never shows (not on the page for this
 * person after all) puts the page back at its top.
 */
@Composable
internal fun rememberSettingsSearchListState(): LazyListState {
    val state = rememberLazyListState()
    val locator = LocalSettingsSearchLocator.current
    if (locator != null) {
        LaunchedEffect(locator, state) { seekSettingsSearchAnchor(locator, state) }
    }
    return state
}

@OptIn(ExperimentalFoundationApi::class)
private suspend fun seekSettingsSearchAnchor(locator: SettingsSearchLocator, state: LazyListState) {
    if (locator.settled) return
    var scrolled = false
    var userStopped = false
    withTimeoutOrNull(SettingsSearchSeekTimeoutMillis) {
        while (!locator.settled) {
            withFrameNanos {}
            val info = state.layoutInfo
            when (
                settingsSearchSeekStep(
                    anchorComposed = locator.anchorComposed,
                    userScrolling = state.isScrollInProgress,
                    itemCount = info.totalItemsCount,
                    canScrollForward = state.canScrollForward,
                )
            ) {
                SettingsSearchSeekStep.Wait -> Unit
                SettingsSearchSeekStep.Stop -> {
                    userStopped = true
                    return@withTimeoutOrNull
                }
                SettingsSearchSeekStep.Scroll -> {
                    val viewport = info.viewportEndOffset - info.viewportStartOffset
                    state.scrollBy(viewport * 0.8f)
                    scrolled = true
                }
            }
        }
    }
    if (!locator.settled) {
        locator.settled = true
        if (scrolled && !userStopped && !state.isScrollInProgress) state.scrollToItem(0)
    }
}
