package build.raft.app.data

/*
 * Per-agent memory limits on a Computer, as the daemon reports them in activity text
 * (botiverse/slock 24873fd5be37 packages/shared/src/agentMemoryLimit.ts, from #10860). The daemon
 * writes English text built by the shared `formatMemoryLimitExitDetail` /
 * `formatBackgroundOomActivityText`; these read it back so the client can show its own copy in
 * every locale, also through a server that only passes the text on.
 */

private val memoryLimitPattern = Regex("""^(?:\d{1,19}(?:\.\d{1,6})?[KMGTPE]?|\d{1,3}(?:\.\d{1,2})?%)$""")

/** Shared `isDisplayableMemoryLimit`: a systemd size (`6G`, `1.5G`, `50%`), short enough to show. */
internal fun isDisplayableAgentMemoryLimit(value: String?): Boolean =
    value != null && value.length <= 32 && memoryLimitPattern.matches(value)

private const val MemoryLimitExitDetail = "Stopped: used more than this Computer's per-agent memory limit"

/** Shared `BACKGROUND_OOM_ACTIVITY_TITLE`: a `system` activity's title. */
const val BACKGROUND_OOM_ACTIVITY_TITLE: String = "Background process stopped: memory limit"

private const val BackgroundOomText =
    "A background process this agent started was stopped for using more than this Computer's per-agent memory limit"

/** The limit named in a memory-limit text; [memoryLimit] null when the text names none. */
data class AgentMemoryLimitText(val memoryLimit: String?)

/** Shared `parseMemoryLimitExitDetail`: null when [detail] is any other text. */
fun parseAgentMemoryLimitExitDetail(detail: String?): AgentMemoryLimitText? {
    val text = detail?.trim() ?: return null
    if (!text.startsWith(MemoryLimitExitDetail)) return null
    val rest = text.removePrefix(MemoryLimitExitDetail)
    if (rest.isEmpty()) return AgentMemoryLimitText(null)
    val limit = Regex("""^ \(([^()]+)\)$""").find(rest)?.groupValues?.get(1)
    return limit?.takeIf(::isDisplayableAgentMemoryLimit)?.let { AgentMemoryLimitText(it) }
}

/** Shared `parseBackgroundOomActivityText`: null when [text] is any other text. */
fun parseAgentBackgroundOomActivityText(text: String?): AgentMemoryLimitText? {
    val value = text?.trim() ?: return null
    if (!value.startsWith(BackgroundOomText)) return null
    val rest = value.removePrefix(BackgroundOomText)
    if (rest == ".") return AgentMemoryLimitText(null)
    val limit = Regex("""^ \(([^()]+)\)\.$""").find(rest)?.groupValues?.get(1)
    return limit?.takeIf(::isDisplayableAgentMemoryLimit)?.let { AgentMemoryLimitText(it) }
}

/** Web `isBackgroundOomActivityTitle`. */
fun isAgentBackgroundOomActivityTitle(title: String?): Boolean = title?.trim() == BACKGROUND_OOM_ACTIVITY_TITLE

/**
 * Web `memoryLimitExitDescriptor`: the runtime was stopped at the memory limit. Only for a
 * `runtime_crashed` detail; an unknown [detailKind] (a client that does not carry it) is read by the
 * text alone, which only the daemon's shared formatter writes.
 */
fun agentMemoryLimitExit(detail: String?, detailKind: String?): AgentMemoryLimitText? {
    val kind = detailKind?.trim()?.takeIf { it.isNotEmpty() }
    if (kind != null && kind != "runtime_crashed") return null
    return parseAgentMemoryLimitExitDetail(detail)
}
