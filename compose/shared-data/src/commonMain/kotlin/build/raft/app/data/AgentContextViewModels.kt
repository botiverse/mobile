package build.raft.app.data

/**
 * Shared domain models for Agent View Context and Settings › Administration › Context access
 * for agents (`botiverse/slock` #12980, #13548, #13550, #14072, #14560).
 *
 * Wire contract sources:
 * - `packages/shared/src/agentContextView.ts`
 * - `packages/shared/src/agentContextViewStatus.ts`
 * - `packages/shared/src/agentContextViewGrant.ts`
 * - `packages/shared/src/diagnosticRedaction.ts`
 */

const val AGENT_CONTEXT_VIEW_DEFAULT_RETENTION_DAYS: Int = 30
const val AGENT_CONTEXT_VIEW_STATUS_TTL_MS: Long = 30_000L

data class AgentContextViewNamedRef(
    val id: String,
    val name: String,
)

enum class AgentContextViewStatusReason(val wireValue: String) {
    NoComputer("no_computer"),
    Offline("offline"),
    OldDaemon("old_daemon"),
    UnsupportedRuntime("unsupported_runtime"),
    NoSession("no_session");

    companion object {
        fun fromWire(raw: String?): AgentContextViewStatusReason? =
            entries.firstOrNull { it.wireValue == raw }
    }
}

sealed interface AgentContextViewStatusState {
    data object Available : AgentContextViewStatusState
    data class Unavailable(val reason: AgentContextViewStatusReason) : AgentContextViewStatusState
}

data class AgentContextViewStatus(
    val runtime: String,
    val computer: AgentContextViewNamedRef?,
    val computerOwner: AgentContextViewNamedRef?,
    val retainedSince: String?,
    val retentionDays: Int = AGENT_CONTEXT_VIEW_DEFAULT_RETENTION_DAYS,
    val state: AgentContextViewStatusState,
)

/**
 * Cached status entry per agent (30s TTL for 200 and 404; failures are not cached).
 *
 * - `status != null`: 200 status (available or unavailable)
 * - `gone == true`: 404 refusal (flag off, no access, or unknown agent) — hides menu item
 * - `failed == true`: network / 5xx / malformed response — shows Retry row in menu (#14072)
 */
data class AgentContextViewStatusEntry(
    val status: AgentContextViewStatus? = null,
    val atMillis: Long? = null,
    val pending: Boolean = false,
    val failed: Boolean = false,
    val gone: Boolean = false,
    val traceId: String? = null,
) {
    fun isFresh(nowMillis: Long, ttlMillis: Long = AGENT_CONTEXT_VIEW_STATUS_TTL_MS): Boolean {
        val recordedAt = atMillis ?: return false
        if (status == null && !gone) return false
        return nowMillis - recordedAt < ttlMillis
    }
}

/**
 * What the message menu shows for an agent's message (web `ViewContextMenuGroup.tsx`,
 * `#14072` retry on check failure, `#14560` hide when `unsupported_runtime`).
 */
sealed interface AgentContextViewMenuEntryState {
    data object Hidden : AgentContextViewMenuEntryState
    data object ShowOpen : AgentContextViewMenuEntryState
    data class ShowRetry(val retrying: Boolean) : AgentContextViewMenuEntryState
}

fun agentContextViewMenuEntryState(entry: AgentContextViewStatusEntry?): AgentContextViewMenuEntryState {
    if (entry == null) return AgentContextViewMenuEntryState.Hidden
    val status = entry.status
    if (status != null) {
        val unavailable = status.state as? AgentContextViewStatusState.Unavailable
        if (unavailable?.reason == AgentContextViewStatusReason.UnsupportedRuntime) {
            return AgentContextViewMenuEntryState.Hidden
        }
        return AgentContextViewMenuEntryState.ShowOpen
    }
    if (entry.gone) return AgentContextViewMenuEntryState.Hidden
    if (entry.failed) return AgentContextViewMenuEntryState.ShowRetry(retrying = entry.pending)
    return AgentContextViewMenuEntryState.Hidden
}

enum class AgentContextViewFailureReason(val wireValue: String) {
    Offline("offline"),
    OldDaemon("old_daemon"),
    AgentMoved("agent_moved"),
    NotFound("not_found"),
    Expired("expired"),
    UnsupportedRuntime("unsupported_runtime"),
    TooLarge("too_large"),
    ReadFailed("read_failed"),
    NoComputer("no_computer"),
    ContextMismatch("context_mismatch"),
    MediaRemoved("media_removed"),
    NewerSessionFormat("newer_session_format"),
    NotRecorded("not_recorded");

    companion object {
        fun fromWire(raw: String?): AgentContextViewFailureReason? =
            entries.firstOrNull { it.wireValue == raw }
    }
}

enum class AgentContextViewTooLargeCause(val wireValue: String) {
    SessionFile("session_file"),
    Request("request"),
    RestoreLimit("restore_limit");

    companion object {
        fun fromWire(raw: String?): AgentContextViewTooLargeCause? =
            entries.firstOrNull { it.wireValue == raw }
    }
}

enum class AgentContextViewSourceKind(val wireValue: String) {
    Native("native"),
    Captured("captured");

    companion object {
        fun fromWire(raw: String?): AgentContextViewSourceKind? =
            entries.firstOrNull { it.wireValue == raw }
    }
}

enum class AgentContextViewVerification(val wireValue: String) {
    Verified("verified"),
    CountMatched("count_matched");

    companion object {
        fun fromWire(raw: String?): AgentContextViewVerification? =
            entries.firstOrNull { it.wireValue == raw }
    }
}

enum class AgentContextViewMessageRole(val wireValue: String) {
    System("system"),
    User("user"),
    Assistant("assistant"),
    Tool("tool");

    companion object {
        fun fromWire(raw: String?): AgentContextViewMessageRole? =
            entries.firstOrNull { it.wireValue == raw }
    }
}

enum class AgentContextViewRedactionKind(val wireValue: String) {
    PrivateKey("private_key"),
    RaftKey("raft_key"),
    Bearer("bearer"),
    Authorization("authorization"),
    VendorKey("vendor_key"),
    GithubToken("github_token"),
    AwsKey("aws_key"),
    GoogleApiKey("google_api_key"),
    XaiKey("xai_key"),
    Jwt("jwt"),
    SecretAssignment("secret_assignment"),
    HexBlob("hex_blob"),
    UrlQuery("url_query"),
    UrlCredentials("url_credentials");

    companion object {
        fun fromWire(raw: String?): AgentContextViewRedactionKind? =
            entries.firstOrNull { it.wireValue == raw }
    }
}

data class AgentContextViewTokens(
    val input: Long?,
    val cacheRead: Long?,
    val total: Long?,
)

data class AgentContextViewSystemBlock(
    val text: String,
)

data class AgentContextViewTool(
    val name: String,
    val description: String,
    val inputSchemaJson: String?,
)

sealed interface AgentContextViewPart {
    data class Text(val text: String) : AgentContextViewPart
    data class Thinking(val text: String?) : AgentContextViewPart
    data class ToolUse(
        val toolName: String,
        val toolUseId: String?,
        val inputJson: String,
        val flatArguments: List<Pair<String, String>>?,
        val summary: String?,
    ) : AgentContextViewPart

    data class ToolResult(
        val toolName: String?,
        val toolUseId: String?,
        val output: String,
        val isError: Boolean,
    ) : AgentContextViewPart

    data class Image(
        val mediaType: String?,
        val sizeBytes: Long?,
    ) : AgentContextViewPart

    data class Compaction(val text: String?) : AgentContextViewPart
    data class Unknown(val originalKind: String) : AgentContextViewPart
}

data class AgentContextViewMessage(
    val role: AgentContextViewMessageRole,
    val parts: List<AgentContextViewPart>,
)

data class AgentContextViewTrigger(
    val messageIndex: Int,
    val partIndex: Int,
    val toolUseId: String?,
)

sealed interface AgentContextViewCompaction {
    data object None : AgentContextViewCompaction
    data class Happened(
        val summaryKind: String?,
        val summaryText: String?,
    ) : AgentContextViewCompaction {
        val isEncrypted: Boolean get() = summaryKind == "encrypted"
    }
}

data class AgentContextViewSource(
    val kind: AgentContextViewSourceKind,
    val format: String,
    val version: String?,
)

data class AgentContextView(
    val runtime: String,
    val model: String?,
    val sessionId: String,
    val requestAt: String?,
    val messageId: String,
    val tokens: AgentContextViewTokens,
    val system: List<AgentContextViewSystemBlock>,
    val tools: List<AgentContextViewTool>,
    val messages: List<AgentContextViewMessage>,
    val compaction: AgentContextViewCompaction,
    val trigger: AgentContextViewTrigger?,
    val redactions: Map<AgentContextViewRedactionKind, Int>,
    val source: AgentContextViewSource,
    val verification: AgentContextViewVerification?,
)

sealed interface AgentContextViewAnswer {
    data object Loading : AgentContextViewAnswer
    data object Gone : AgentContextViewAnswer
    data class Ok(
        val context: AgentContextView,
        val rawJson: String,
    ) : AgentContextViewAnswer

    data class Failed(
        val reason: AgentContextViewFailureReason,
        val tooLargeCause: AgentContextViewTooLargeCause? = null,
        val computer: AgentContextViewNamedRef? = null,
        val computerOwner: AgentContextViewNamedRef? = null,
        val traceId: String? = null,
    ) : AgentContextViewAnswer
}

enum class AgentContextViewOutcome(val wireValue: String) {
    Ok("ok"),
    Offline("offline"),
    OldDaemon("old_daemon"),
    AgentMoved("agent_moved"),
    NotFound("not_found"),
    Expired("expired"),
    UnsupportedRuntime("unsupported_runtime"),
    TooLarge("too_large"),
    ReadFailed("read_failed"),
    NoComputer("no_computer"),
    ContextMismatch("context_mismatch"),
    MediaRemoved("media_removed"),
    NewerSessionFormat("newer_session_format"),
    NotRecorded("not_recorded"),
    Refused("refused"),
    Cancelled("cancelled");

    companion object {
        fun fromWire(raw: String?): AgentContextViewOutcome? =
            entries.firstOrNull { it.wireValue == raw }

        fun fromFailureReason(reason: AgentContextViewFailureReason): AgentContextViewOutcome =
            fromWire(reason.wireValue) ?: ReadFailed
    }
}

sealed interface AgentContextViewQueryRecord {
    data class Search(val query: String, val matches: Int?) : AgentContextViewQueryRecord
    data class Read(val around: String) : AgentContextViewQueryRecord
}

data class AgentContextViewRecordGrant(
    val id: String,
    val granter: AgentContextViewNamedRef?,
)

data class AgentContextViewRecord(
    val viewer: AgentContextViewNamedRef?,
    val at: String,
    val outcome: AgentContextViewOutcome?,
    val outcomeRaw: String,
    val viewerIsAgent: Boolean = false,
    val query: AgentContextViewQueryRecord? = null,
    val grant: AgentContextViewRecordGrant? = null,
)

enum class AgentContextViewBannerReason(val retryable: Boolean) {
    Offline(true),
    OldDaemon(false),
    StatusFailed(true),
    AgentMoved(true),
    NotFound(false),
    Expired(true),
    TooLarge(false),
    ReadFailed(true),
    ContextMismatch(false),
    MediaRemoved(false),
    NewerSessionFormat(false),
    NotRecorded(false),
}

enum class AgentContextViewEmptyReason {
    UnsupportedRuntime,
    NoSession,
    NoComputer,
}

sealed interface AgentContextViewBodyState {
    data object Loading : AgentContextViewBodyState
    data object Gone : AgentContextViewBodyState
    data class Content(val context: AgentContextView) : AgentContextViewBodyState
    data class Banner(
        val reason: AgentContextViewBannerReason,
        val retry: Boolean = reason.retryable,
        val tooLargeCause: AgentContextViewTooLargeCause? = null,
        val traceId: String? = null,
    ) : AgentContextViewBodyState

    data class Empty(val reason: AgentContextViewEmptyReason) : AgentContextViewBodyState
}

private fun statusReasonToBodyState(reason: AgentContextViewStatusReason): AgentContextViewBodyState =
    when (reason) {
        AgentContextViewStatusReason.UnsupportedRuntime ->
            AgentContextViewBodyState.Empty(AgentContextViewEmptyReason.UnsupportedRuntime)
        AgentContextViewStatusReason.NoSession ->
            AgentContextViewBodyState.Empty(AgentContextViewEmptyReason.NoSession)
        AgentContextViewStatusReason.NoComputer ->
            AgentContextViewBodyState.Empty(AgentContextViewEmptyReason.NoComputer)
        AgentContextViewStatusReason.Offline ->
            AgentContextViewBodyState.Banner(AgentContextViewBannerReason.Offline)
        AgentContextViewStatusReason.OldDaemon ->
            AgentContextViewBodyState.Banner(AgentContextViewBannerReason.OldDaemon)
    }

private fun failureReasonToBodyState(
    reason: AgentContextViewFailureReason,
    tooLargeCause: AgentContextViewTooLargeCause?,
    traceId: String?,
): AgentContextViewBodyState =
    when (reason) {
        AgentContextViewFailureReason.UnsupportedRuntime ->
            AgentContextViewBodyState.Empty(AgentContextViewEmptyReason.UnsupportedRuntime)
        AgentContextViewFailureReason.NoComputer ->
            AgentContextViewBodyState.Empty(AgentContextViewEmptyReason.NoComputer)
        AgentContextViewFailureReason.Offline ->
            AgentContextViewBodyState.Banner(AgentContextViewBannerReason.Offline, traceId = traceId)
        AgentContextViewFailureReason.OldDaemon ->
            AgentContextViewBodyState.Banner(AgentContextViewBannerReason.OldDaemon, traceId = traceId)
        AgentContextViewFailureReason.AgentMoved ->
            AgentContextViewBodyState.Banner(AgentContextViewBannerReason.AgentMoved, traceId = traceId)
        AgentContextViewFailureReason.NotFound ->
            AgentContextViewBodyState.Banner(AgentContextViewBannerReason.NotFound, traceId = traceId)
        AgentContextViewFailureReason.Expired ->
            AgentContextViewBodyState.Banner(AgentContextViewBannerReason.Expired, traceId = traceId)
        AgentContextViewFailureReason.TooLarge ->
            AgentContextViewBodyState.Banner(
                reason = AgentContextViewBannerReason.TooLarge,
                tooLargeCause = tooLargeCause,
                traceId = traceId,
            )
        AgentContextViewFailureReason.ReadFailed ->
            AgentContextViewBodyState.Banner(AgentContextViewBannerReason.ReadFailed, traceId = traceId)
        AgentContextViewFailureReason.ContextMismatch ->
            AgentContextViewBodyState.Banner(AgentContextViewBannerReason.ContextMismatch, traceId = traceId)
        AgentContextViewFailureReason.MediaRemoved ->
            AgentContextViewBodyState.Banner(AgentContextViewBannerReason.MediaRemoved, traceId = traceId)
        AgentContextViewFailureReason.NewerSessionFormat ->
            AgentContextViewBodyState.Banner(AgentContextViewBannerReason.NewerSessionFormat, traceId = traceId)
        AgentContextViewFailureReason.NotRecorded ->
            AgentContextViewBodyState.Banner(AgentContextViewBannerReason.NotRecorded, traceId = traceId)
    }

/**
 * Pure panel body state projection matching web `agentContextViewState.ts`.
 */
fun agentContextViewBodyState(
    statusEntry: AgentContextViewStatusEntry?,
    answer: AgentContextViewAnswer? = null,
): AgentContextViewBodyState {
    if (statusEntry?.gone == true) return AgentContextViewBodyState.Gone
    val status = statusEntry?.status
    if (status == null) {
        return if (statusEntry?.failed == true && !statusEntry.pending) {
            AgentContextViewBodyState.Banner(
                reason = AgentContextViewBannerReason.StatusFailed,
                traceId = statusEntry.traceId,
            )
        } else {
            AgentContextViewBodyState.Loading
        }
    }
    when (val s = status.state) {
        is AgentContextViewStatusState.Unavailable -> return statusReasonToBodyState(s.reason)
        AgentContextViewStatusState.Available -> Unit
    }
    return when (answer) {
        null, AgentContextViewAnswer.Loading -> AgentContextViewBodyState.Loading
        AgentContextViewAnswer.Gone -> AgentContextViewBodyState.Gone
        is AgentContextViewAnswer.Ok -> AgentContextViewBodyState.Content(answer.context)
        is AgentContextViewAnswer.Failed ->
            failureReasonToBodyState(answer.reason, answer.tooLargeCause, answer.traceId)
    }
}

/**
 * Why an active grant stopped working (`botiverse/slock` #13550).
 */
enum class AgentContextViewGrantUnusableReason(val wireValue: String) {
    GranterGone("granter_gone"),
    GranterNotAdmin("granter_not_admin"),
    AgentIneligible("agent_ineligible");

    companion object {
        fun fromWire(raw: String?): AgentContextViewGrantUnusableReason? =
            entries.firstOrNull { it.wireValue == raw }
    }
}

/**
 * An active View Context grant (`GET/POST /api/servers/:id/agent-context-view-grants`,
 * `botiverse/slock` #13548 & #13550).
 *
 * [usable] defaults to `true` when absent from older servers; when `false`, [unusableReason]
 * names why the grant stopped working (if recognized by this build).
 */
data class AgentContextViewGrant(
    val id: String,
    val agent: AgentContextViewNamedRef,
    val granter: AgentContextViewNamedRef?,
    val grantedAt: String,
    val usable: Boolean = true,
    val unusableReason: AgentContextViewGrantUnusableReason? = null,
)

data class AgentContextViewGrantCreated(
    val grant: AgentContextViewGrant,
    val created: Boolean,
)

enum class AgentContextViewGrantRevokeOutcome {
    Revoked,
    Gone,
}

/**
 * Ordinary live agent eligibility for Context Access grant picker (web `ContextViewGrantsSection.tsx`):
 * not deleted, not a personal assistant, not an App Bot (`managedByAppInstallId == null`), and not already granted.
 */
fun isEligibleContextViewGrantCandidate(
    agentId: String,
    isAgent: Boolean,
    isDeleted: Boolean,
    isPersonalAssistant: Boolean,
    managedByAppInstallId: String?,
    grantedAgentIds: Set<String>,
): Boolean {
    if (!isAgent || agentId.isBlank()) return false
    if (isDeleted || isPersonalAssistant) return false
    if (!managedByAppInstallId.isNullOrBlank()) return false
    return agentId !in grantedAgentIds
}

/**
 * Message-list filter modes matching web `AgentContextViewToolbar` / `agentContextViewMessageFilter.ts`.
 */
enum class AgentContextViewMessageFilter {
    All,
    Conversation,
    Notices,
    Tools,
}

fun agentContextViewSectionCharCounts(context: AgentContextView): Triple<Int, Int, Int> {
    val systemChars = context.system.sumOf { it.text.length }
    val toolsChars =
        context.tools.sumOf { tool ->
            tool.name.length + tool.description.length + (tool.inputSchemaJson?.length ?: 0)
        }
    val messageChars =
        context.messages.sumOf { message ->
            message.parts.sumOf { part ->
                when (part) {
                    is AgentContextViewPart.Text -> part.text.length
                    is AgentContextViewPart.Thinking -> part.text?.length ?: 0
                    is AgentContextViewPart.ToolUse -> part.inputJson.length
                    is AgentContextViewPart.ToolResult -> part.output.length
                    is AgentContextViewPart.Compaction -> part.text?.length ?: 0
                    is AgentContextViewPart.Image, is AgentContextViewPart.Unknown -> 0
                }
            }
        }
    return Triple(systemChars, toolsChars, messageChars)
}
