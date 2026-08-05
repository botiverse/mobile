package build.raft.app

/**
 * task #990 — structured, switchable trace for the Thread IME shrink path.
 *
 * Built after hand-writing correlation keys at each call site produced, in one
 * afternoon: a whole layer with no keys at all, a missing cycle key on another,
 * a bare sequence where a full command id belonged, and a field applied to a
 * site outside its scope (a compile failure). Every one of those is a mistake
 * that only exists when each emit is edited individually, so the keys are bound
 * once here and the events are typed — omitting a required field fails to
 * compile rather than producing a log that quietly lacks it.
 *
 * The switch is a compile-time constant, not a mutable global: a `var` would be
 * a cross-thread mutable singleton, and — more importantly — it could not prove
 * that the disabled build performs no GPU readback and writes no product state.
 * Main keeps it false; a forensics build changes this one line.
 */
internal const val ThreadImeTraceEnabled: Boolean = false

/** Where a record came from. Constants, so a producer cannot be misspelled. */
internal enum class ThreadTraceProducer {
    ComposerInsetRelayout,
    ThreadLayoutProbe,
    DispatchViewportEvent,
    AwaitViewportCommandSettlement,
    FocusedAnchorSession,
    ImeTailDrawProbe,
    ImeTailPixelProbe,
    PreShrinkBaseline
}

/**
 * Frame identity. Required at construction by every event that claims a moment,
 * so "which frame was this" can never be inferred from log ordering — the
 * cross-snapshot join this investigation already drew one wrong conclusion from.
 */
internal data class ThreadTraceFrame(
    val frameSequence: Long,
    val frameTimeNanos: Long
)

/** A viewport command, or the explicit absence of one. */
internal sealed interface ThreadTraceCommand {
    data object None : ThreadTraceCommand

    data class Id(val epoch: Long, val sequence: Long) : ThreadTraceCommand
}

internal fun ThreadTraceCommand.render(): String =
    when (this) {
        ThreadTraceCommand.None -> "none"
        is ThreadTraceCommand.Id -> "$epoch:$sequence"
    }

/**
 * The keys every record carries. Bound once per IME cycle; call sites supply
 * only what is distinctive about their own record.
 */
internal data class ThreadTraceContext(
    val threadInstance: String,
    val imeCycle: Long,
    val layoutGeneration: Long,
    val targetMessageId: String?
)

/**
 * Emission seam. The disabled implementation is the default so that a build
 * without the switch cannot accidentally acquire a sink.
 */
internal fun interface ThreadTraceSink {
    fun emit(line: String)
}

internal class ThreadImeTrace(
    private val context: ThreadTraceContext,
    private val sink: ThreadTraceSink
) {
    /**
     * Records one event. The body is a lambda so that, with tracing disabled,
     * nothing inside it is evaluated — no string building, and no PixelCopy for
     * callers that read pixels inside the body. Cost when off is one constant
     * check.
     */
    inline fun event(
        producer: ThreadTraceProducer,
        function: String,
        name: String,
        frame: ThreadTraceFrame? = null,
        command: ThreadTraceCommand = ThreadTraceCommand.None,
        fields: () -> String
    ) {
        if (!ThreadImeTraceEnabled) return
        emitRendered(producer, function, name, frame, command, fields())
    }

    fun emitRendered(
        producer: ThreadTraceProducer,
        function: String,
        name: String,
        frame: ThreadTraceFrame?,
        command: ThreadTraceCommand,
        rendered: String
    ) {
        if (!ThreadImeTraceEnabled) return
        sink.emit(
            "$name producer=$producer function=$function " +
                "threadInstance=${context.threadInstance} imeCycle=${context.imeCycle} " +
                "layoutGeneration=${context.layoutGeneration} " +
                "targetMessageId=${context.targetMessageId.orEmpty()} " +
                "commandId=${command.render()} " +
                "frameSequence=${frame?.frameSequence ?: -1} " +
                "frameTimeNanos=${frame?.frameTimeNanos ?: -1} " +
                rendered
        )
    }
}

/**
 * Verdict for the cached pre-shrink sample. Named states rather than a boolean,
 * because "no baseline" and "a baseline describing a different target" must not
 * read the same, and neither may be silently compared against.
 */
internal enum class ThreadPreShrinkBaselineStatus {
    Ok,
    Missing,
    StaleThread,
    StaleTarget,
    StaleLayout,
    StaleViewport,
    Incomplete
}

internal fun threadPreShrinkBaselineStatus(
    cachedThreadInstance: String?,
    cachedTargetMessageId: String?,
    cachedLayoutGeneration: Long?,
    cachedViewportStartPx: Int?,
    cachedViewportEndPx: Int?,
    cachedComplete: Boolean,
    currentThreadInstance: String,
    currentTargetMessageId: String?,
    currentLayoutGeneration: Long,
    currentViewportStartPx: Int,
    currentViewportEndPx: Int
): ThreadPreShrinkBaselineStatus =
    when {
        cachedThreadInstance == null -> ThreadPreShrinkBaselineStatus.Missing
        cachedThreadInstance != currentThreadInstance -> ThreadPreShrinkBaselineStatus.StaleThread
        cachedTargetMessageId != currentTargetMessageId -> ThreadPreShrinkBaselineStatus.StaleTarget
        cachedLayoutGeneration != currentLayoutGeneration -> ThreadPreShrinkBaselineStatus.StaleLayout
        cachedViewportStartPx != currentViewportStartPx ||
            cachedViewportEndPx != currentViewportEndPx -> ThreadPreShrinkBaselineStatus.StaleViewport
        !cachedComplete -> ThreadPreShrinkBaselineStatus.Incomplete
        else -> ThreadPreShrinkBaselineStatus.Ok
    }
