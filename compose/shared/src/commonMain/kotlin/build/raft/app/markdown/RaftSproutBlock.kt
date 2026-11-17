package build.raft.app.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import build.raft.app.RaftAppRadius
import build.raft.app.RaftColor
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.raftLogWarn
import build.raft.designsystem.components.RaftSegment
import build.raft.designsystem.components.RaftSegmentedControl
import build.raft.designsystem.gesture.raftKeepsHorizontalDrags
import build.raft.designsystem.tokens.RaftStroke
import build.raft.sprout.compose.SproutHostFrame
import build.raft.sprout.compose.defaultSproutComposeRegistry
import build.raft.sprout.protocol.RAFT_UI_CATALOG
import build.raft.sprout.protocol.ValidationProfile
import build.raft.sprout.protocol.isSafeHttpActionUrl
import build.raft.sprout.runtime.FrameCommit
import build.raft.sprout.runtime.OutboundEffect
import build.raft.sprout.runtime.RuntimeLimits
import build.raft.sprout.runtime.SproutEngine
import build.raft.sprout.runtime.SproutSurface
import kotlinx.coroutines.delay
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

internal enum class RaftSproutView { Preview, Code }

private object RaftSharedSproutEngineHolder {
    val engine: SproutEngine? by lazy {
        runCatching {
            SproutEngine.prepare(
                limits = RuntimeLimits(
                    compilerBudgetMs = 400L,
                    sourceUpdateBudgetMs = 200L,
                    dispatchBudgetMs = 80L,
                    tickBudgetMs = 50L,
                ),
            )
        }.onFailure { err ->
            raftLogWarn("RaftSproutBlock", "sprout_engine_init_failed: ${err.message}")
        }.getOrNull()
    }
}

internal fun raftMarkdownIsGenUiFence(language: String?): Boolean =
    raftMarkdownFenceLanguage(language).equals("genui", ignoreCase = true)

@Composable
internal fun RaftSproutBlock(
    code: String,
    fallback: @Composable () -> Unit,
) {
    val engine = remember { RaftSharedSproutEngineHolder.engine }
    if (engine == null) {
        fallback()
        return
    }

    val clockOrigin = remember { TimeSource.Monotonic.markNow() }
    fun elapsedMs(): Long = clockOrigin.elapsedNow().toLong(DurationUnit.MILLISECONDS)

    val surface: SproutSurface? = remember(engine) {
        runCatching {
            engine.createSurface(
                surfaceId = "chat-genui-${clockOrigin.hashCode()}",
                catalog = RAFT_UI_CATALOG,
                profile = ValidationProfile.PORTABLE_V1,
            )
        }.onFailure { err ->
            raftLogWarn("RaftSproutBlock", "sprout_surface_create_failed: ${err.message}")
        }.getOrNull()
    }

    if (surface == null) {
        fallback()
        return
    }

    DisposableEffect(surface) {
        onDispose {
            runCatching { surface.close() }
        }
    }

    var view by remember { mutableStateOf(RaftSproutView.Preview) }
    var sourceRevision by remember { mutableLongStateOf(0L) }
    var latestCommit by remember { mutableStateOf<FrameCommit?>(null) }
    var failedBeforeFirstFrame by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    fun handleEffects(commit: FrameCommit) {
        for (eff in commit.effects) {
            if (eff is OutboundEffect.Navigate && isSafeHttpActionUrl(eff.url)) {
                runCatching { uriHandler.openUri(eff.url) }
            }
        }
    }

    LaunchedEffect(surface, code) {
        val nextRev = sourceRevision + 1L
        sourceRevision = nextRev
        runCatching {
            surface.setSource(
                revision = nextRev,
                source = code,
                nowMs = elapsedMs(),
                finished = true,
            )
        }.onSuccess { commit ->
            latestCommit = commit
            failedBeforeFirstFrame = commit.frame.root.isEmpty() && commit.diagnostics.isNotEmpty()
            handleEffects(commit)
        }.onFailure { err ->
            raftLogWarn("RaftSproutBlock", "sprout_eval_failed: ${err.message}")
            if (latestCommit?.frame?.root.isNullOrEmpty()) {
                failedBeforeFirstFrame = true
            }
        }
    }

    val nextDue = latestCommit?.nextTimerDueMs
    LaunchedEffect(surface, nextDue) {
        if (nextDue != null) {
            val waitMs = (nextDue - elapsedMs()).coerceAtLeast(16L)
            delay(waitMs)
            runCatching {
                surface.tickWithDiagnostics(elapsedMs())
            }.onSuccess { commit ->
                latestCommit = commit
                handleEffects(commit)
            }
        }
    }

    val commit = latestCommit
    if (failedBeforeFirstFrame && commit?.frame?.root.isNullOrEmpty()) {
        fallback()
        return
    }

    val shape = RoundedCornerShape(RaftAppRadius.Container)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .raftKeepsHorizontalDrags()
            .raftMarkdownObserveCodeTap()
            .clip(shape)
            .background(RaftColor.SurfaceWhite)
            .border(RaftStroke.Hairline, RaftColor.BorderHairline, shape),
    ) {
        RaftDesignSystemTheme {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                RaftSegmentedControl {
                    RaftSegment(
                        text = "Interactive UI",
                        selected = view == RaftSproutView.Preview,
                        onClick = { view = RaftSproutView.Preview },
                    )
                    RaftSegment(
                        text = "TSX",
                        selected = view == RaftSproutView.Code,
                        onClick = { view = RaftSproutView.Code },
                    )
                }
                Spacer(Modifier.weight(1f))
            }
        }
        if (view == RaftSproutView.Code || commit == null) {
            fallback()
        } else {
            Box(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                SproutHostFrame(
                    frame = commit.frame,
                    registry = defaultSproutComposeRegistry,
                    provenanceLabel = "Sprout GenUI · QuickJS Sandbox",
                    isStreaming = false,
                    interactionEnabled = commit.interactionEnabled,
                    onEvent = { ev ->
                        runCatching {
                            surface.dispatch(
                                nodeId = ev.nodeId,
                                eventName = ev.eventName,
                                targetFrameRevision = ev.frameRevision,
                                targetRuntimeEpoch = ev.runtimeEpoch,
                                arguments = ev.arguments,
                                nowMs = elapsedMs(),
                            )
                        }.onSuccess { updated ->
                            latestCommit = updated
                            handleEffects(updated)
                        }
                    },
                    onOpenLinkRequest = { url ->
                        if (isSafeHttpActionUrl(url)) {
                            runCatching { uriHandler.openUri(url) }
                        }
                    },
                )
            }
        }
    }
}
