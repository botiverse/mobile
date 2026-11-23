package build.raft.app

import build.raft.app.data.RaftClientConfigAppHosts
import kotlin.time.Clock
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The longest prompt a send-prompt link may carry (web `SendPromptLinkIntent.tsx`
 * `MAX_PROMPT_LENGTH`, botiverse/slock #16226).
 */
internal const val SEND_PROMPT_MAX_LENGTH: Int = 4000

/**
 * A parsed send-prompt link intent (`?dialog=send-prompt&prompt=<text>`).
 * [prompt] is percent-decoded, clamped to [SEND_PROMPT_MAX_LENGTH], and guaranteed non-blank.
 */
internal data class RaftSendPromptLink(
    val prompt: String,
)

private enum class SendPromptQueryKind {
    NotSendPrompt,
    EmptyPrompt,
    Valid,
}

private data class SendPromptQueryResult(
    val kind: SendPromptQueryKind,
    val prompt: String? = null,
)

private fun extractSendPromptRawQuery(
    url: String,
    configuredWebUrl: String?,
    appHosts: Set<String>,
    allowRelative: Boolean,
): String? {
    val raw = url.trim()
    if (raw.isEmpty()) return null
    if (raw.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f } || '\\' in raw) return null
    if (raw.startsWith("//")) return null
    if (allowRelative && (raw.startsWith("?") || raw.startsWith("/"))) {
        val withoutFragment = raw.substringBefore('#')
        val queryStart = withoutFragment.indexOf('?')
        return if (queryStart >= 0) withoutFragment.substring(queryStart + 1) else ""
    }
    return raftOwnWebLinkRawQueryOrNull(raw, configuredWebUrl, appHosts)
}

private fun parseSendPromptQuery(rawQuery: String): SendPromptQueryResult {
    if (rawQuery.isEmpty()) return SendPromptQueryResult(SendPromptQueryKind.NotSendPrompt)
    var dialogValue: String? = null
    var promptValue: String? = null
    for (part in rawQuery.split('&')) {
        if (part.isEmpty()) continue
        val key = raftLinkPercentDecodeOrNull(part.substringBefore('='))
            ?: return SendPromptQueryResult(SendPromptQueryKind.NotSendPrompt)
        val value = raftLinkPercentDecodeOrNull(part.substringAfter('=', missingDelimiterValue = ""))
            ?: return SendPromptQueryResult(SendPromptQueryKind.NotSendPrompt)
        if (key == "dialog" && dialogValue == null) {
            dialogValue = value
        } else if (key == "prompt" && promptValue == null) {
            promptValue = value
        }
    }
    if (dialogValue != "send-prompt") return SendPromptQueryResult(SendPromptQueryKind.NotSendPrompt)
    val clamped = (promptValue ?: "").take(SEND_PROMPT_MAX_LENGTH)
    return if (clamped.isNotBlank()) {
        SendPromptQueryResult(SendPromptQueryKind.Valid, clamped)
    } else {
        SendPromptQueryResult(SendPromptQueryKind.EmptyPrompt)
    }
}

/**
 * Parses a send-prompt link (`?dialog=send-prompt&prompt=<text>`) on a relative path/query
 * (when [allowRelative] is true, e.g. announcement CTAs and body links) or on Raft's own origins
 * (production hosts, client config [appHosts], or Developer environment [configuredWebUrl]).
 *
 * Prompts are clamped to [SEND_PROMPT_MAX_LENGTH] characters; empty or blank prompts return null.
 */
internal fun parseRaftSendPromptLink(
    url: String,
    configuredWebUrl: String? = null,
    appHosts: Set<String> = RaftClientConfigAppHosts.current,
    allowRelative: Boolean = true,
): RaftSendPromptLink? {
    val rawQuery = extractSendPromptRawQuery(url, configuredWebUrl, appHosts, allowRelative) ?: return null
    val parsed = parseSendPromptQuery(rawQuery)
    return if (parsed.kind == SendPromptQueryKind.Valid && parsed.prompt != null) {
        RaftSendPromptLink(parsed.prompt)
    } else {
        null
    }
}

/**
 * True when [url] is a relative path/query (if [allowRelative]) or a Raft own-origin URL whose
 * `dialog` query parameter is `send-prompt`, even when `prompt` is empty or blank. Used by
 * announcement link resolution so empty send-prompt links are ignored instead of opening a browser.
 */
internal fun isRaftSendPromptIntentUrl(
    url: String,
    configuredWebUrl: String? = null,
    appHosts: Set<String> = RaftClientConfigAppHosts.current,
    allowRelative: Boolean = true,
): Boolean {
    val rawQuery = extractSendPromptRawQuery(url, configuredWebUrl, appHosts, allowRelative) ?: return false
    return parseSendPromptQuery(rawQuery).kind != SendPromptQueryKind.NotSendPrompt
}

/** A send-prompt link waiting for a ready app shell, as [RaftPendingMessageLink] waits. */
internal data class RaftPendingSendPromptLink(
    val id: Long,
    val link: RaftSendPromptLink,
    val receivedAtEpochMillis: Long,
    val source: String,
)

/**
 * The process-wide single slot for a send-prompt link ([parseRaftSendPromptLink]), tapped in a
 * message or opened from outside the app: newest wins, taken once by a shell with a ready session,
 * dropped after [ttlMillis].
 */
internal class RaftSendPromptLinkInbox(
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val ttlMillis: Long = RaftMessageLinkInbox.DefaultTtlMillis,
) {
    private val lock = SynchronizedObject()
    private var nextId = 1L
    private val mutablePending = MutableStateFlow<RaftPendingSendPromptLink?>(null)
    val pending: StateFlow<RaftPendingSendPromptLink?> = mutablePending.asStateFlow()

    fun submit(link: RaftSendPromptLink, source: String): RaftPendingSendPromptLink = synchronized(lock) {
        RaftPendingSendPromptLink(
            id = nextId++,
            link = link,
            receivedAtEpochMillis = nowEpochMillis(),
            source = source,
        ).also { mutablePending.value = it }
    }

    /** Takes the pending link [id]; null when already taken, replaced, or expired (then cleared). */
    fun take(id: Long): RaftPendingSendPromptLink? = synchronized(lock) {
        val current = mutablePending.value?.takeIf { it.id == id } ?: return null
        mutablePending.value = null
        current.takeIf { nowEpochMillis() - it.receivedAtEpochMillis <= ttlMillis }
    }

    fun clear() = synchronized(lock) { mutablePending.value = null }

    companion object {
        /** The slot platform ingresses, message link taps, and every app shell share. */
        val Process: RaftSendPromptLinkInbox = RaftSendPromptLinkInbox()
    }
}
