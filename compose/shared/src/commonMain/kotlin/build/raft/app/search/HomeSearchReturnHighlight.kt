package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import build.raft.designsystem.components.RaftLocatedState
import build.raft.designsystem.components.raftLocated

/**
 * Nav spec §7.3: coming back from a search result keeps the scroll position and briefly marks the
 * row that was opened ("you came from here") with raft-ui's located highlight (0.22.1 #719:
 * `info-soft`, held 1.2s, faded out by 2s; [RaftLocatedState]). [markOpened] records the row; the
 * mark shows when the page is shown again, then clears.
 */
@Stable
internal class HomeSearchReturnHighlight {
    internal var openedKey: String? by mutableStateOf(null)
    internal val located = RaftLocatedState()

    fun markOpened(key: String) {
        openedKey = key
    }
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
 * Shows the located highlight on [key] now; it is gone after 2s ([RaftLocatedState.locate]).
 * Also used by Activity's unread-row walk (ActivityUnreadJump.kt) for the row it jumped to.
 */
internal suspend fun HomeSearchReturnHighlight.flash(key: String) {
    located.locate(key)
}

/** Lays the located `info-soft` over the opened row's fill while its return mark lasts. */
internal fun Modifier.homeSearchReturnHighlight(highlight: HomeSearchReturnHighlight, key: String): Modifier =
    raftLocated(highlight.located, key, RaftColor.InfoSoft)
