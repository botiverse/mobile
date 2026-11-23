package build.raft.app

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import build.raft.app.data.MOBILE_THREAD_OPEN_TRACE_NAME
import build.raft.app.data.ProcessRaftDatetimeFoundationOwner
import build.raft.app.data.RaftClientTraceEvents
import build.raft.app.data.ServerFeatureFlagSnapshot
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.model.ServerId
import build.raft.app.data.defaultTraceSampleRate
import build.raft.app.data.resolveTraceSampleRate
import build.raft.app.data.traceSampleEvaluations
import build.raft.app.data.traceSampled
import build.raft.app.nav.LocalRaftStackPageInMotion
import build.raft.app.nav.LocalRaftStackPageLeaving
import kotlinx.coroutines.launch

/** `mobile.thread_open`'s rate for this open: flags first, then the App default (RaftTraceSampling.kt). */
internal fun threadOpenTraceSampleRate(
    flags: ServerFeatureFlagSnapshot,
    fallback: Double = defaultTraceSampleRate(),
): Double = resolveTraceSampleRate(MOBILE_THREAD_OPEN_TRACE_NAME, flags.traceSampleEvaluations(MOBILE_THREAD_OPEN_TRACE_NAME), fallback)

private class ThreadOpenTraceHolder {
    var leaving = false
}

/**
 * One sampling decision per open ([openKey]: the list's thread instance). Not sampled (or rate 0):
 * null, and nothing is installed anywhere. A new [openKey] on the same list ends the previous
 * session as superseded; the list leaving composition ends it as left. Either way it is reported.
 */
@Composable
internal fun rememberThreadOpenTraceSession(
    openKey: String?,
    surfaceKind: String,
    openKind: String,
    flags: () -> ServerFeatureFlagSnapshot,
    serverId: () -> String?,
    log: (String) -> Unit,
): ThreadOpenTraceSession? {
    val holder = remember { ThreadOpenTraceHolder() }
    val session =
        remember(openKey) {
            if (openKey.isNullOrBlank()) return@remember null
            val rate = runCatching { threadOpenTraceSampleRate(flags()) }.getOrDefault(0.0)
            if (!traceSampled(rate)) return@remember null
            ThreadOpenTraceSession(
                surfaceKind = surfaceKind,
                openKind = openKind,
                sampleRate = rate,
                recordCondition = "trace_sample",
                viewKey = openKey,
                nowEpochMs = ProcessRaftDatetimeFoundationOwner::nowEpochMillis,
            )
        }
    val currentServerId = rememberUpdatedState(serverId)
    val currentLog = rememberUpdatedState(log)
    DisposableEffect(session) {
        session?.reporter = { ended -> reportThreadOpenTrace(ended, currentServerId.value(), currentLog.value) }
        onDispose {
            session?.finishAndReport(if (holder.leaving) ThreadOpenEnd.Left else ThreadOpenEnd.Superseded)
        }
    }
    // Declared after the session's effect, so it is disposed first when the page leaves composition.
    DisposableEffect(holder) { onDispose { holder.leaving = true } }
    return session
}

/**
 * The open trace of a thread message list (ThreadMessagesListContent): one sampling decision per
 * [openKey] (the list's thread instance), and the recorder when sampled. [positioned]: the initial
 * bottom anchor is done and no focused-message anchor is pending.
 */
@Composable
internal fun ThreadMessagesListOpenTrace(
    openKey: String,
    slice: ThreadTimelineSlice,
    backgroundRefreshing: Boolean,
    listState: ThreadListViewport,
    flags: SharedServerFeatureFlags,
    /** The shell's server, read until the page knows the viewer's (the first frames of a cold open). */
    initialServerId: String?,
    messageBodyFontSize: Int,
    log: (String) -> Unit,
    positioned: () -> Boolean,
) {
    val viewerServerId =
        (LocalThreadViewerServerId.current ?: initialServerId)?.trim()?.takeIf { it.isNotBlank() }
    val session =
        rememberThreadOpenTraceSession(
            openKey = openKey,
            surfaceKind = slice.surfaceKind,
            openKind = if (slice.focusedMessageId.isNullOrBlank()) "latest" else "focused",
            flags = { flags.snapshotFor(viewerServerId?.let(::ServerId)) },
            serverId = { viewerServerId },
            log = log,
        ) ?: return
    val fontScale = LocalDensity.current.fontScale
    ThreadOpenShiftRecorderEffect(
        session = session,
        listState = listState,
        typeScaleKey = (fontScale * 100f).toInt() * 31 + messageBodyFontSize,
    ) { inputs ->
        inputs.positioned = positioned()
        inputs.status = slice.status
        inputs.messageRows = slice.messages.size
        inputs.durableLastKnown = slice.durableLastKnown
        inputs.backgroundRefreshing = backgroundRefreshing
        inputs.mainLoadFailed = slice.mainLoadFailed
    }
}

/** Log lines (every build) and the upload (privacy mode permitting, in the sender). Once per session. */
internal fun reportThreadOpenTrace(session: ThreadOpenTraceSession, serverId: String?, log: (String) -> Unit) {
    if (!session.claimReport()) return
    val server = serverId?.takeIf { it.isNotBlank() }
    runCatching { session.diagnosticsLines(uploaded = server != null).forEach(log) }
    if (server == null) return
    runCatching {
        val (span, events) = session.traceRecords()
        RaftClientTraceEvents.emitSpan(server, span, events)
    }
}

/** [ThreadOpenFrameRows] over the raw list layout of this frame, read in place. */
private class LazyListFrameRows : ThreadOpenFrameRows {
    var info: LazyListLayoutInfo? = null

    // Non-null reads only: a nullable value class (IntSize?) would box every frame.
    override val count: Int
        get() = info?.visibleItemsInfo?.size ?: 0
    override val totalItems: Int
        get() = info?.totalItemsCount ?: 0
    override val viewportWidth: Int
        get() {
            val current = info ?: return 0
            return current.viewportSize.width
        }
    override val viewportHeight: Int
        get() {
            val current = info ?: return 0
            return current.viewportSize.height
        }
    override val contentPadding: Int
        get() {
            val current = info ?: return 0
            return current.beforeContentPadding + current.afterContentPadding
        }

    override fun key(index: Int): Any = info!!.visibleItemsInfo[index].key

    override fun offset(index: Int): Int = info!!.visibleItemsInfo[index].offset

    override fun size(index: Int): Int = info!!.visibleItemsInfo[index].size
}

/**
 * The observe-only shift recorder of a thread message list: once per frame it hands the list's
 * layout to [session]; it never scrolls and owns no viewport intent. Installed only for a sampled
 * open; stops when the session ends and reports it.
 *
 * [fill] sets the frame's payload / positioning inputs (called on the frame, reading current state).
 */
@Composable
internal fun ThreadOpenShiftRecorderEffect(
    session: ThreadOpenTraceSession?,
    listState: ThreadListViewport,
    typeScaleKey: Int,
    fill: (ThreadOpenFrameInputs) -> Unit,
) {
    if (session == null || session.ended) return
    val density = LocalDensity.current.density
    val inMotion = rememberUpdatedState(LocalRaftStackPageInMotion.current)
    val leaving = rememberUpdatedState(LocalRaftStackPageLeaving.current)
    val currentTypeScale = rememberUpdatedState(typeScaleKey)
    val currentFill = rememberUpdatedState(fill)
    LaunchedEffect(session, listState) {
        val interactionSource = listState.interactionSource
        val startUserNavigationCount = listState.userNavigationCount
        var touched = false
        val drags = launch { interactionSource.interactions.collect { if (it is DragInteraction.Start) touched = true } }
        val rows = LazyListFrameRows()
        val inputs = ThreadOpenFrameInputs()
        try {
            while (!session.ended) {
                withFrameNanos { }
                rows.info = if (listState.isVirtual) listState.layoutInfo else listState.raw.layoutInfo
                inputs.density = density
                inputs.userTouching = touched || listState.userNavigationCount != startUserNavigationCount
                inputs.pageInMotion = inMotion.value
                inputs.pageLeaving = leaving.value
                inputs.typeScaleKey = currentTypeScale.value
                inputs.ownerScrollCount = listState.ownedScrollCount
                inputs.ownerTag = listState.lastScrollOwnerTag
                inputs.ownerAttempt = listState.lastScrollOwnerAttempt
                currentFill.value(inputs)
                session.onFrame(rows, inputs)
            }
        } finally {
            rows.info = null
            drags.cancel()
        }
        session.reporter?.invoke(session)
    }
}
