package build.raft.app.nav

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavMetadataKey
import androidx.navigation3.runtime.get
import androidx.navigation3.runtime.metadata
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import androidx.navigation3.ui.LocalNavAnimatedContentScope

/**
 * The phone window's scene of [RaftPageStack]: the top entry full window, every entry under it
 * still composed at 0x0 (kept alive, like an Activity under another one; [RaftMobilePageStackDisplay]
 * pauses them), and one scene per top entry so pushes and pops animate in `NavDisplay`.
 *
 * Motion lives on the top entry ([Modifier.animateEnterExit]), the scene transition itself is none:
 * a pushed page slides in from the end edge over the page it was opened from, which this scene
 * draws full window under it until the push settles (the scene being left draws nothing: Navigation
 * 3 renders a shared entry only in the newer scene). On a pop, and while a predictive back gesture
 * drags (Android 14+), the leaving page slides out over the previous scene, whose top page is the
 * only other one drawn. So at most two pages are drawn at any time.
 *
 * A covered page whose entry says so ([RaftStackCoveredKeepsSizeKey]: the chat, the file preview)
 * keeps the window size instead of 0x0: measured at the scene's constraints and not placed, so it
 * draws nothing and takes no input, but its list viewport never shrinks to nothing and grows back
 * (an Activity under another keeps its size; the chat's viewport intents and scroll restore rely
 * on that). A page opened without the push ([RaftStackOpensWithoutMotionKey]) appears in place.
 */
class RaftMobileStackScene<T : Any>(
    override val entries: List<NavEntry<T>>,
    private val motion: RaftMobileStackMotion,
    private val transitions: RaftStackTransitionTracker? = null,
) : Scene<T> {
    init {
        require(entries.isNotEmpty()) { "RaftMobileStackScene needs at least one entry" }
    }

    override val key: Any = entries.last().contentKey

    override val previousEntries: List<NavEntry<T>> = entries.dropLast(1)

    override val content: @Composable () -> Unit = {
        val top = entries.last()
        val below = entries.getOrNull(entries.lastIndex - 1)
        val scope = LocalNavAnimatedContentScope.current
        val pushed = motion.isPushed(top.contentKey)
        val entering =
            scope.transition.targetState == EnterExitState.Visible &&
                scope.transition.currentState != EnterExitState.Visible
        // perf(nav): a pushed page is built (composed, measured, laid out, drawn) off the end edge in
        // the frame it is pushed, and its slide starts on the frame after that work, so the motion never
        // waits on the page's first build mid slide. The slide is the motion's own, not the scene
        // transition's: the page below stays drawn until it ends.
        val slide = if (pushed && !top.raftStackOpensWithoutMotion()) motion.pushSlide(top.contentKey) else null
        if (slide != null) {
            LaunchedEffect(slide) {
                if (slide.value > 0f) {
                    withFrameNanos {}
                    slide.animateTo(0f, tween(RaftMobileStackMotionMillis, easing = FastOutSlowInEasing))
                }
            }
        }
        val sliding by remember(slide) { derivedStateOf { slide != null && slide.value > 0f } }
        run {
            // This scene's motion for the transition tracker and the stack's settled top page: shown and about to leave while its
            // page is still on top is a predictive back preview; headed to shown with nothing left
            // running is settled (a push / pop landing here, or a gesture let go back onto it: a
            // cancelled seek can end with the target shown and no animation, whatever the current).
            val topKey = top.contentKey.toString()
            val transition = scope.transition
            LaunchedEffect(transition, topKey) {
                snapshotFlow { Triple(transition.currentState, transition.targetState, transition.isRunning || sliding) }
                    .collect { (current, target, running) ->
                        if (target == EnterExitState.PostExit) {
                            if (current == EnterExitState.Visible) transitions?.onBackPreview(topKey)
                        } else if (target == EnterExitState.Visible && !running) {
                            motion.settledTop = topKey
                            transitions?.onSettled(topKey)
                        }
                    }
            }
        }
        entries.forEach { entry ->
            key(entry.contentKey) {
                val modifier =
                    when {
                        entry === top ->
                            with(scope) {
                                val slideModifier =
                                    if (slide == null) Modifier else Modifier.graphicsLayer { translationX = slide.value * size.width }
                                Modifier.fillMaxSize().then(slideModifier).animateEnterExit(
                                    enter = EnterTransition.None,
                                    exit = RaftMobileStackPopExit,
                                )
                            }
                        entry === below && pushed && (entering || sliding) -> Modifier.fillMaxSize()
                        entry.raftStackCoveredKeepsSize() -> RaftMobileStackCoveredKeepsSize
                        else -> Modifier.size(0.dp)
                    }
                Box(modifier) { entry.Content() }
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is RaftMobileStackScene<*> && entries == other.entries)

    override fun hashCode(): Int = entries.hashCode()

    override fun toString(): String = "RaftMobileStackScene(entries=${entries.map { it.contentKey }})"
}

/** [RaftMobileStackScene] for any non-empty back stack. */
class RaftMobileStackSceneStrategy<T : Any>(
    private val motion: RaftMobileStackMotion,
    private val transitions: RaftStackTransitionTracker? = null,
) : SceneStrategy<T> {
    override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T>? =
        if (entries.isEmpty()) null else RaftMobileStackScene(entries, motion, transitions)
}

/**
 * Which pages the last stack change brought in: a scene whose top page was not on the stack before
 * that change is a push (its page slides in); any other scene entering is a pop or the previous
 * scene of a back gesture (its page is already in place). Plain fields, updated in composition
 * before the scenes read them.
 */
class RaftMobileStackMotion {
    private var current: List<Any> = emptyList()
    private var beforeLastChange: Set<Any> = emptySet()

    /**
     * The content key of the top page whose scene last settled (shown, no motion running). A page
     * becomes the active page (back handlers, `viewDidAppear`: its read state, realtime interest and
     * loads) only once its push / pop has settled, so that work never lands mid slide.
     */
    var settledTop: Any? by mutableStateOf(null)

    private val slides = HashMap<Any, Animatable<Float, AnimationVector1D>>()

    /** The push slide of [contentKey] (1 = off the end edge, 0 = in place), shared by its scenes. */
    fun pushSlide(contentKey: Any): Animatable<Float, AnimationVector1D> = slides.getOrPut(contentKey) { Animatable(1f) }

    fun update(contentKeys: List<Any>) {
        if (contentKeys == current) return
        beforeLastChange = current.toSet()
        current = contentKeys
        slides.keys.retainAll(contentKeys.toSet())
    }

    fun isPushed(contentKey: Any): Boolean = contentKey !in beforeLastChange
}

/**
 * Entry metadata: when covered, the page keeps the window size (measured, not placed) instead of
 * 0x0. Set by [RaftMobilePageStackDisplay] for [RaftStackCoveredKeepsSizePages].
 */
data object RaftStackCoveredKeepsSizeKey : NavMetadataKey<Boolean>

/** Entry metadata: the page was opened without the push (`slockOpenWithoutAnimation`). */
data object RaftStackOpensWithoutMotionKey : NavMetadataKey<Boolean>

/** The mobile scene's metadata for one page (both keys only when true). */
fun raftMobileStackMetadata(coveredKeepsSize: Boolean, opensWithoutMotion: Boolean): Map<String, Any> =
    metadata {
        if (coveredKeepsSize) put(RaftStackCoveredKeepsSizeKey, true)
        if (opensWithoutMotion) put(RaftStackOpensWithoutMotionKey, true)
    }

internal fun NavEntry<*>.raftStackCoveredKeepsSize(): Boolean = metadata[RaftStackCoveredKeepsSizeKey] == true

internal fun NavEntry<*>.raftStackOpensWithoutMotion(): Boolean = metadata[RaftStackOpensWithoutMotionKey] == true

/** Measured at the scene's constraints (the window), placed nowhere: no drawing, no input. */
private val RaftMobileStackCoveredKeepsSize: Modifier =
    Modifier.layout { measurable, constraints ->
        measurable.measure(constraints)
        layout(0, 0) {}
    }

private const val RaftMobileStackMotionMillis = 300

private val RaftMobileStackPopExit: ExitTransition =
    slideOutHorizontally(tween(RaftMobileStackMotionMillis, easing = FastOutSlowInEasing)) { width -> width }

/** The scene-level transform: none, the top entry animates itself (see [RaftMobileStackScene]). */
internal val RaftMobileStackSceneTransform = EnterTransition.None togetherWith ExitTransition.None
