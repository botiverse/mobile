package build.raft.app.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.zIndex
import build.raft.app.get
import build.raft.designsystem.motion.RaftMotion

/**
 * One frame of the bottom-tab fade through ([RaftMotion.fadeThrough]'s numbers, Android only):
 * the outgoing tab fades out over the first [RaftMotion.FadeThroughOutgoingMillis], then the
 * incoming tab fades in and grows from [RaftMotion.FadeThroughStartScale] over the rest, each on
 * [RaftMotion.EaseStandard].
 */
internal data class AppShellTabFadeThroughFrame(
    val outgoingAlpha: Float,
    val incomingAlpha: Float,
    val incomingScale: Float,
) {
    /** The outgoing tab is still drawn (under the incoming one). */
    val outgoingShown: Boolean get() = outgoingAlpha > 0f

    companion object {
        /** No transition running: only the selected tab, as it is at rest. */
        val Rest = AppShellTabFadeThroughFrame(outgoingAlpha = 0f, incomingAlpha = 1f, incomingScale = 1f)
    }
}

internal fun appShellTabFadeThroughFrame(elapsedMillis: Float): AppShellTabFadeThroughFrame {
    val outgoingMillis = RaftMotion.FadeThroughOutgoingMillis.toFloat()
    val incomingMillis = RaftMotion.FadeThroughIncomingMillis.toFloat()
    val out = RaftMotion.EaseStandard.transform((elapsedMillis / outgoingMillis).coerceIn(0f, 1f))
    val incoming =
        RaftMotion.EaseStandard.transform(((elapsedMillis - outgoingMillis) / incomingMillis).coerceIn(0f, 1f))
    if (elapsedMillis >= RaftMotion.FadeThroughMillis) return AppShellTabFadeThroughFrame.Rest
    return AppShellTabFadeThroughFrame(
        outgoingAlpha = 1f - out,
        incomingAlpha = incoming,
        incomingScale = RaftMotion.FadeThroughStartScale + (1f - RaftMotion.FadeThroughStartScale) * incoming,
    )
}

private enum class AppShellTabFadeRole {
    Rest,
    Incoming,
    Outgoing,
}

/**
 * Per-tab placement and fade state read strictly in placement / draw phases so tab switches never
 * attach/detach `graphicsLayer` nodes or force remeasurement of kept-alive tab subtrees.
 */
@Stable
private class AppShellTabLayerState(
    placed: Boolean,
    role: AppShellTabFadeRole,
    elapsed: Animatable<Float, *>,
) {
    var placed by mutableStateOf(placed)
    var role by mutableStateOf(role)
    var elapsed by mutableStateOf(elapsed)
}
/** The tab switch the stack is animating: from [outgoing] (null: none) in switch [generation]. */
private class AppShellTabSwitchTracker(var shownTab: String) {
    var outgoing: String? = null
    var generation: Int = 0
}

/**
 * The app shell's kept-alive tab stack with the Android fade through (artin 2026-10-29).
 *
 * Every tab in [tabs] stays composed for the life of the stack, exactly as before: a tab switch
 * never disposes or re-creates a tab, so its scroll position, loaded data and child page survive
 * and the switch triggers no reload. That is why this does not use `AnimatedContent` (which
 * re-creates the incoming content and disposes the outgoing one): the fade animates alpha/scale on
 * the kept-alive layers instead. Only [activeTab] is placed at rest (an unplaced layer is neither
 * drawn nor hit-tested, its state kept); during a fade through the outgoing tab stays placed,
 * inactive, under the incoming one until it has faded out.
 *
 * [fadeThrough] false (iOS, desktop, the wide rail) or [reducedMotion] switches at once, the
 * behaviour the stack always had. [layer] draws one tab: `active` is the selected tab, `placed`
 * whether it is on screen this frame (also the outgoing tab during the fade).
 */
@Composable
internal fun AppShellTabFadeThroughStack(
    activeTab: String,
    tabs: List<String>,
    fadeThrough: Boolean,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    layer: @Composable (tab: String, active: Boolean, placed: Boolean) -> Unit,
) {
    val animate = fadeThrough && !reducedMotion
    val tracker = remember { AppShellTabSwitchTracker(activeTab) }
    if (tracker.shownTab != activeTab) {
        tracker.outgoing = tracker.shownTab.takeIf { animate }
        tracker.shownTab = activeTab
        tracker.generation++
    }
    // Keyed on the switch, so the frame the selection changes already starts the fade (no frame of
    // the incoming tab at full opacity first).
    val elapsed =
        remember(tracker.generation) {
            Animatable(if (tracker.outgoing != null) 0f else RaftMotion.FadeThroughMillis.toFloat())
        }
    LaunchedEffect(elapsed) {
        elapsed.animateTo(
            RaftMotion.FadeThroughMillis.toFloat(),
            tween(durationMillis = RaftMotion.FadeThroughMillis, easing = LinearEasing),
        )
    }
    val transitioning = animate && tracker.outgoing != null
    // Read per frame only in the layers' draw (graphicsLayer); composition only follows whether the
    // outgoing tab is still on screen.
    val outgoingShown by
        remember(elapsed, transitioning) {
            derivedStateOf { transitioning && appShellTabFadeThroughFrame(elapsed.value).outgoingShown }
        }
    val outgoing = tracker.outgoing?.takeIf { outgoingShown && it != activeTab && it in tabs }
    Box(modifier = modifier.fillMaxSize()) {
        tabs.forEach { tab ->
            key("app-shell-$tab") {
                val active = tab == activeTab
                val placed = active || tab == outgoing
                val role =
                    when {
                        active && transitioning -> AppShellTabFadeRole.Incoming
                        tab == outgoing -> AppShellTabFadeRole.Outgoing
                        else -> AppShellTabFadeRole.Rest
                    }
                val layerState = remember { AppShellTabLayerState(placed, role, elapsed) }
                layerState.placed = placed
                layerState.role = role
                layerState.elapsed = elapsed
                val stableTabModifier =
                    remember(layerState) {
                        Modifier
                            .layout { measurable, constraints ->
                                val placeable = measurable.measure(constraints)
                                layout(placeable.width, placeable.height) {
                                    if (layerState.placed) placeable.place(0, 0)
                                }
                            }
                            .graphicsLayer {
                                when (layerState.role) {
                                    AppShellTabFadeRole.Incoming -> {
                                        val frame = appShellTabFadeThroughFrame(layerState.elapsed.value)
                                        alpha = frame.incomingAlpha
                                        scaleX = frame.incomingScale
                                        scaleY = frame.incomingScale
                                    }
                                    AppShellTabFadeRole.Outgoing -> {
                                        alpha = appShellTabFadeThroughFrame(layerState.elapsed.value).outgoingAlpha
                                        scaleX = 1f
                                        scaleY = 1f
                                    }
                                    AppShellTabFadeRole.Rest -> {
                                        alpha = 1f
                                        scaleX = 1f
                                        scaleY = 1f
                                    }
                                }
                            }
                    }
                // The selected tab sits over the outgoing one, so touches during the fade reach it.
                Box(
                    modifier =
                        Modifier
                            .zIndex(if (active) 1f else 0f)
                            .then(stableTabModifier)
                            .fillMaxSize()
                ) {
                    CompositionLocalProvider(LocalRaftAppShellTabSelected provides active) {
                        layer(tab, active, placed)
                    }
                }
            }
        }
    }
}

/**
 * Whether the enclosing app-shell tab is the selected one (true outside the kept-alive tab stack).
 * Unlike a page's `active`, it stays true while another page covers the shell. Kept-alive tabs
 * stay composed unselected, so per-tab copies of something only the selected tab shows (the wide
 * reply-thread column) check it instead of being composed once per retained tab.
 */
internal val LocalRaftAppShellTabSelected = compositionLocalOf { true }
