package build.raft.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind

/** How long the opened row stays marked after returning to the results (nav spec §7.3). */
internal const val HomeSearchReturnHighlightMillis = 1500
private const val HomeSearchReturnHighlightHoldMillis = 500

/**
 * Nav spec §7.3: coming back from a search result keeps the scroll position and briefly marks the
 * row that was opened ("you came from here"), fading out over [HomeSearchReturnHighlightMillis].
 * [markOpened] records the row; the mark shows when the page is shown again, then clears.
 */
@Stable
internal class HomeSearchReturnHighlight {
    internal var openedKey: String? by mutableStateOf(null)
    internal var shownKey: String? by mutableStateOf(null)
    internal val alpha = Animatable(0f)

    fun markOpened(key: String) {
        openedKey = key
    }

    fun alphaFor(key: String): Float = if (shownKey == key) alpha.value else 0f
}

/** The opened row is marked only once the page shows again after it was left. */
internal fun homeSearchReturnHighlightKey(openedKey: String?, pageVisible: Boolean): String? =
    openedKey?.takeIf { pageVisible }

@Composable
internal fun rememberHomeSearchReturnHighlight(
    pageVisible: Boolean,
    visibleTick: Int,
    // Hoisted by a host whose page content is rebuilt while a result covers it (the Search tab).
    hoisted: HomeSearchReturnHighlight? = null,
): HomeSearchReturnHighlight {
    val owned = remember { HomeSearchReturnHighlight() }
    val highlight = hoisted ?: owned
    // [visibleTick] advances each time the hosting page reappears (a result page closed over it);
    // [pageVisible] covers the kept-alive Search tab being reselected.
    LaunchedEffect(pageVisible, visibleTick) {
        val key = homeSearchReturnHighlightKey(highlight.openedKey, pageVisible) ?: return@LaunchedEffect
        highlight.openedKey = null
        highlight.flash(key)
    }
    return highlight
}

/**
 * Shows the mark on [key] now and fades it out over [HomeSearchReturnHighlightMillis]. Also used
 * by Activity's unread-row walk (ActivityUnreadJump.kt) for the row it jumped to.
 */
internal suspend fun HomeSearchReturnHighlight.flash(key: String) {
    shownKey = key
    try {
        alpha.snapTo(1f)
        // Hold the mark for a moment, then fade it out; [HomeSearchReturnHighlightMillis] in all.
        alpha.animateTo(
            0f,
            tween(
                HomeSearchReturnHighlightMillis - HomeSearchReturnHighlightHoldMillis,
                delayMillis = HomeSearchReturnHighlightHoldMillis,
                easing = LinearEasing
            )
        )
    } finally {
        // Leaving mid-fade (the page left again, the tab was disposed) must not leave the mark;
        // a newer mark started meanwhile keeps its own key. No suspending call here: this also
        // runs on cancellation.
        if (shownKey == key) shownKey = null
    }
}

/** Lays the brand-soft wash under the opened row while its return mark fades. */
internal fun Modifier.homeSearchReturnHighlight(highlight: HomeSearchReturnHighlight, key: String): Modifier =
    drawBehind {
        val alpha = highlight.alphaFor(key)
        if (alpha > 0f) {
            drawRect(RaftColor.BrandSoft, alpha = alpha)
        }
    }
