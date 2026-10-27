package build.raft.app.data

import build.raft.app.RaftTaskConversationChannelKind
import build.raft.app.MemberDetailIdentity
import build.raft.app.MemberDetailIdentitySource
import build.raft.app.memberDetailIdentityFromWire
import build.raft.app.resolveThreadRouteIdentity
import build.raft.app.toRaftSurfaceKind
import build.raft.app.toWireChannelKindOrNull
import build.raft.app.model.ChannelActivityNotificationPreference
import build.raft.app.model.ChannelCapabilities
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationPermissions
import build.raft.app.model.ConversationSummary
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
import build.raft.app.model.conversationDisplayTitleCandidate
import build.raft.app.model.conversationIdentityLikeTitle
import build.raft.app.model.conversationSafeDisplayTitleOrNull
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal enum class SharedConversationAliasKind(val resolvesUniqueConversation: Boolean) {
    Channel(true),
    Thread(true),
    Stream(true),
    ParentChannel(false),
    ParentMessage(false),
    PresenceMember(true),
    Member(true)
}

internal data class SharedConversationDirectoryProjection(
    val serverId: String,
    val canonicalChannelId: String,
    val aliases: Set<String>,
    val conversationKind: ConversationKind,
    val surfaceKind: String,
    val channelKind: String,
    val title: String,
    val subtitle: String? = null,
    val avatarWireValue: String? = null,
    val avatarLabel: String = title.take(2).uppercase(),
    val senderMessageIds: Set<String> = emptySet(),
    val senderMemberId: String? = null,
    val senderAvatarWireValue: String? = null,
    val senderAvatarLabel: String? = null,
    val peerIdentity: MemberDetailIdentity? = null,
    val parentChannelId: String? = null,
    val parentMessageId: String? = null,
    val joined: Boolean = true,
    val archived: Boolean = false,
    val activityNotificationPreference: ChannelActivityNotificationPreference =
        ChannelActivityNotificationPreference.Unsupported,
    val permissions: ConversationPermissions = ConversationPermissions(),
    /**
     * Server-resolved channel capabilities carried alongside the projection so
     * member-roster surfaces (web parity: `currentChannel.channelCapabilities`)
     * can gate add/remove affordances. Inbox projections never carry it.
     */
    val channelCapabilities: ChannelCapabilities? = null,
    val jointBillingLocked: Boolean? = null,
    val jointOverLimitGraceEndsAt: String? = null,
    // task #396: external bridge provider ("slack") carried from `channel.bridge.provider`.
    val bridgeProvider: String? = null,
    /** Channel `guestVisible` from the Home channel row; threads/DMs/inbox projections never carry it. */
    val guestVisible: Boolean = false
) {
    val activityMuted: Boolean
        get() = activityNotificationPreference.activityMuted
    val muteFromSeq: Long?
        get() = activityNotificationPreference.muteFromSeq
    val activityMuteSupported: Boolean
        get() = activityNotificationPreference.activityMuteSupported
}

internal data class SharedConversationDirectoryState(
    val projectionsByCanonicalId: Map<String, SharedConversationDirectoryProjection> = emptyMap(),
    val aliasesById: Map<String, Set<String>> = emptyMap(),
    val duplicateAliases: Map<String, Set<String>> = emptyMap()
) {
    fun resolve(id: String?): SharedConversationDirectoryProjection? {
        val normalized = id?.takeIf { it.isNotBlank() } ?: return null
        // A canonical id is the projection's self-attested identity. Aliases
        // may help find that identity, but they must never override it.
        projectionsByCanonicalId[normalized]?.let { return it }
        val canonicalIds = aliasesById[normalized].orEmpty()
        return when {
            // Observation point (t432 family ③): a single alias claim wins by default —
            // e.g. a thread stream id can resolve to a related DM row while the thread's
            // own projection is absent. Reply-like consumers defer/drop upstream, so this
            // stays acceptable; revisit if a non-deferred consumer surfaces.
            canonicalIds.size == 1 -> projectionsByCanonicalId[canonicalIds.single()]
            else -> null
        }
    }
}

internal interface SharedConversationIdentityProvider {
    fun observeServer(serverId: ServerId): Flow<SharedConversationDirectoryState>

    fun projectionFlow(
        serverId: ServerId,
        ids: Collection<String>
    ): Flow<Map<String, SharedConversationDirectoryProjection>>

    fun resolveProjection(
        serverId: ServerId,
        id: String
    ): SharedConversationDirectoryProjection?
}

/**
 * Immutable owner witness attached to a conversation-directory publication. Directory rows are
 * keyed by environment + account + server + conversation id (data-layer plan, Tenny rule 1): only
 * an environment or account change fences them. A token rotation (a higher [authGeneration] for
 * the same account) and a server switch (a higher [selectionGeneration]) keep every row; the
 * selection generation only orders two canonical publications for the same conversation, so a
 * late A-route response cannot overwrite a newer one for that conversation.
 */
internal data class SharedConversationIdentityAuthority(
    val environmentId: String,
    val accountId: String,
    val serverId: String,
    val authGeneration: Long,
    val selectionGeneration: Long = 0L,
)

internal data class SharedConversationIdentityCandidatePublication(
    val serverId: ServerId,
    val accountId: String,
    val revision: Long,
    val state: SharedConversationDirectoryState,
)

/**
 * Provider-owned directory for one shell scope. Home and the opened-DM fast path publish only
 * candidates; a destination-owned canonical detail publication is merged last and therefore wins
 * over those candidates. The hub exists before Home is created, which is the important cold
 * process-restore property.
 *
 * Lifetime (data-layer plan step 4): rows survive token rotation and server switches; an
 * environment/account change clears them; [dropServer] evicts a server the account lost access to;
 * at most [maxServers] servers (least recently published first) and [maxCanonicalPerServer]
 * canonical rows per server are kept. A Home candidate published before the auth binding exists is
 * staged ([stagePendingCandidate]) and applied once the binding arrives ([applyPendingCandidates]).
 */
internal class SharedConversationIdentityHub(
    private val diagnosticsLogger: (String) -> Unit = {},
    private val maxServers: Int = DefaultMaxServers,
    private val maxCanonicalPerServer: Int = DefaultMaxCanonicalPerServer,
) : SharedConversationIdentityProvider {
    private data class Binding(
        val environmentId: String,
        val accountId: String,
        val authGeneration: Long,
    )

    private data class CandidateEntry(
        val revision: Long,
        val state: SharedConversationDirectoryState,
    )

    private data class PendingCandidate(
        val environmentId: String,
        val accountId: String,
        val revision: Long,
        val state: SharedConversationDirectoryState,
    )

    private data class CanonicalEntry(
        val authority: SharedConversationIdentityAuthority,
        val projection: SharedConversationDirectoryProjection,
    )

    private val lock = SynchronizedObject()
    private val candidateByServer = linkedMapOf<String, CandidateEntry>()
    private val canonicalByServer = linkedMapOf<String, LinkedHashMap<String, CanonicalEntry>>()
    private val pendingCandidateByServer = linkedMapOf<String, PendingCandidate>()
    /** Servers in publication order, least recent first (per-server LRU, Tenny rule 3). */
    private val serverRecency = linkedSetOf<String>()
    private var binding: Binding? = null
    private val state = MutableStateFlow<Map<String, SharedConversationDirectoryState>>(emptyMap())

    /** Publish a Home/Activity/open-DM candidate. Lower or duplicate revisions are ignored. */
    fun publishCandidate(
        authority: SharedConversationIdentityAuthority,
        revision: Long,
        next: SharedConversationDirectoryState,
    ): Boolean = synchronized(lock) { publishCandidateLocked(authority, revision, next) }

    /**
     * Keep a candidate that was produced before the auth binding exists (cold-start restore of the
     * durable Home rows). Home's publisher is change-gated and will not send the same rows again,
     * so dropping it would leave the hub empty until the next Home change. Only the latest
     * revision per server is kept; [applyPendingCandidates] applies it under the binding.
     */
    fun stagePendingCandidate(
        environmentId: String,
        accountId: String,
        serverId: String,
        revision: Long,
        next: SharedConversationDirectoryState,
    ): Boolean = synchronized(lock) {
        val environment = environmentId.trim().trimEnd('/').takeIf { it.isNotBlank() } ?: return@synchronized false
        val account = accountId.trim().takeIf { it.isNotBlank() } ?: return@synchronized false
        val server = serverId.trim().takeIf { it.isNotBlank() } ?: return@synchronized false
        val previous = pendingCandidateByServer[server]
        if (
            previous != null &&
            previous.environmentId == environment &&
            previous.accountId == account &&
            revision <= previous.revision
        ) {
            return@synchronized false
        }
        pendingCandidateByServer.remove(server)
        pendingCandidateByServer[server] = PendingCandidate(environment, account, revision, next)
        while (pendingCandidateByServer.size > maxServers) {
            pendingCandidateByServer.remove(pendingCandidateByServer.keys.first())
        }
        true
    }

    /**
     * Apply every staged candidate that belongs to this environment + account under its auth
     * generation. Candidates staged for another environment/account are discarded (fence).
     * Returns the number of servers whose candidate was applied.
     */
    fun applyPendingCandidates(
        environmentId: String,
        accountId: String,
        authGeneration: Long,
    ): Int = synchronized(lock) {
        if (pendingCandidateByServer.isEmpty()) return@synchronized 0
        val environment = environmentId.trim().trimEnd('/')
        val account = accountId.trim()
        if (environment.isBlank() || account.isBlank()) return@synchronized 0
        val staged = pendingCandidateByServer.entries.map { it.key to it.value }
        pendingCandidateByServer.clear()
        var applied = 0
        staged.forEach { (serverId, pending) ->
            if (pending.environmentId != environment || pending.accountId != account) {
                diagnosticsLogger("conversation_directory_pending_drop reason=owner_changed serverId=$serverId")
                return@forEach
            }
            val accepted =
                publishCandidateLocked(
                    authority =
                        SharedConversationIdentityAuthority(
                            environmentId = environment,
                            accountId = account,
                            serverId = serverId,
                            authGeneration = authGeneration,
                        ),
                    revision = pending.revision,
                    next = pending.state,
                )
            if (accepted) applied += 1
        }
        if (applied > 0) diagnosticsLogger("conversation_directory_pending_apply servers=$applied")
        applied
    }

    /** Publish destination-owned canonical detail; it always merges after candidates. */
    fun publishCanonical(
        authority: SharedConversationIdentityAuthority,
        projection: SharedConversationDirectoryProjection,
    ): Boolean = synchronized(lock) {
        val serverId = authority.serverId.trim().takeIf { it.isNotBlank() } ?: return@synchronized false
        if (projection.serverId != serverId) return@synchronized false
        if (!admitBinding(authority)) return@synchronized false
        val rows = canonicalByServer.getOrPut(serverId) { linkedMapOf() }
        val existing = rows[projection.canonicalChannelId]
        if (existing != null && existing.authority.isNewerThan(authority)) {
            diagnosticsLogger(
                "conversation_directory_canonical_drop reason=stale_selection " +
                    "serverId=$serverId incoming=${authority.selectionGeneration} " +
                    "current=${existing.authority.selectionGeneration}"
            )
            return@synchronized false
        }
        // Re-insert so the map's order is least-recently-published first.
        rows.remove(projection.canonicalChannelId)
        rows[projection.canonicalChannelId] = CanonicalEntry(authority = authority, projection = projection)
        while (rows.size > maxCanonicalPerServer) {
            rows.remove(rows.keys.first())
        }
        touchServer(serverId)
        recompute(serverId)
        true
    }

    /**
     * Tenny rule 2: the account was removed from (or lost) [serverId]; drop every row the hub
     * holds for it. The binding is kept: other servers' rows stay valid.
     */
    fun dropServer(serverId: String) = synchronized(lock) {
        val server = serverId.trim().takeIf { it.isNotBlank() } ?: return@synchronized
        removeServerLocked(server)
    }

    fun clear() = synchronized(lock) {
        candidateByServer.clear()
        canonicalByServer.clear()
        pendingCandidateByServer.clear()
        serverRecency.clear()
        binding = null
        state.value = emptyMap()
    }

    override fun observeServer(serverId: ServerId): Flow<SharedConversationDirectoryState> =
        state
            .map { byServer -> byServer[serverId.value] ?: SharedConversationDirectoryState() }
            .distinctUntilChanged()

    override fun projectionFlow(
        serverId: ServerId,
        ids: Collection<String>
    ): Flow<Map<String, SharedConversationDirectoryProjection>> =
        observeServer(serverId)
            .map { current ->
                ids
                    .filter { it.isNotBlank() }
                    .associateWith { id -> current.resolve(id) }
                    .filterValues { it != null }
                    .mapValues { (_, value) -> value!! }
            }
            .distinctUntilChanged()

    override fun resolveProjection(serverId: ServerId, id: String): SharedConversationDirectoryProjection? =
        state.value[serverId.value]?.resolve(id)

    /** [resolveProjection], but only while the hub is bound to [accountId]. */
    fun resolveProjectionFor(
        accountId: String,
        serverId: ServerId,
        id: String
    ): SharedConversationDirectoryProjection? {
        val account = accountId.trim().takeIf { it.isNotBlank() } ?: return null
        val bound = synchronized(lock) { binding?.accountId }
        if (bound != account) return null
        return resolveProjection(serverId, id)
    }

    private fun publishCandidateLocked(
        authority: SharedConversationIdentityAuthority,
        revision: Long,
        next: SharedConversationDirectoryState,
    ): Boolean {
        val serverId = authority.serverId.trim().takeIf { it.isNotBlank() } ?: return false
        if (!admitBinding(authority)) return false
        val previous = candidateByServer[serverId]
        if (previous != null && revision <= previous.revision) return false
        candidateByServer[serverId] = CandidateEntry(revision = revision, state = next)
        // A live publication supersedes anything staged for this server.
        pendingCandidateByServer[serverId]?.takeIf { it.revision <= revision }?.let {
            pendingCandidateByServer.remove(serverId)
        }
        touchServer(serverId)
        recompute(serverId)
        return true
    }

    /**
     * Tenny rule 1: fence only on an environment/account change. A different owner needs a newer
     * auth generation than the current binding (a late publication from a predecessor session is
     * dropped) and clears every row. The same owner is admitted at any auth generation: a token
     * rotation, or a response that was in flight across one, is still this account's data.
     */
    private fun admitBinding(authority: SharedConversationIdentityAuthority): Boolean {
        val environment = authority.environmentId.trim().trimEnd('/')
        val account = authority.accountId.trim()
        if (environment.isBlank() || account.isBlank() || authority.authGeneration < 0L) return false
        val current = binding
        if (current == null) {
            binding = Binding(environment, account, authority.authGeneration)
            return true
        }
        if (current.environmentId != environment || current.accountId != account) {
            if (authority.authGeneration <= current.authGeneration) return false
            clearRowsKeepingBinding()
            binding = Binding(environment, account, authority.authGeneration)
            return true
        }
        if (authority.authGeneration > current.authGeneration) {
            binding = current.copy(authGeneration = authority.authGeneration)
        }
        return true
    }

    private fun clearRowsKeepingBinding() {
        candidateByServer.clear()
        canonicalByServer.clear()
        serverRecency.clear()
        state.value = emptyMap()
    }

    private fun touchServer(serverId: String) {
        serverRecency.remove(serverId)
        serverRecency.add(serverId)
        while (serverRecency.size > maxServers) {
            val eldest = serverRecency.first()
            diagnosticsLogger("conversation_directory_server_evict reason=lru serverId=$eldest")
            removeServerLocked(eldest)
        }
    }

    private fun removeServerLocked(serverId: String) {
        serverRecency.remove(serverId)
        candidateByServer.remove(serverId)
        canonicalByServer.remove(serverId)
        pendingCandidateByServer.remove(serverId)
        if (serverId in state.value) state.value = state.value - serverId
    }

    private fun recompute(serverId: String) {
        val candidate = candidateByServer[serverId]?.state
        val canonical = canonicalByServer[serverId].orEmpty().values.map { it.projection }
        val projections = buildList {
            candidate?.projectionsByCanonicalId?.values?.let(::addAll)
            addAll(canonical)
        }
        val next = state.value.toMutableMap()
        if (projections.isEmpty()) {
            next.remove(serverId)
        } else {
            next[serverId] = projections.toSharedConversationDirectoryState(logger = diagnosticsLogger)
        }
        state.value = next
    }

    private fun SharedConversationIdentityAuthority.isNewerThan(other: SharedConversationIdentityAuthority): Boolean =
        selectionGeneration > other.selectionGeneration ||
            (selectionGeneration == other.selectionGeneration && authGeneration > other.authGeneration)

    companion object {
        const val DefaultMaxServers = 16
        const val DefaultMaxCanonicalPerServer = 256
    }
}

internal fun ConversationSummary.toCanonicalConversationDirectoryProjection(
    serverId: ServerId
): SharedConversationDirectoryProjection? =
    when (kind) {
        ConversationKind.DirectMessage -> toHomeDirectMessageRow().toSharedConversationDirectoryProjection(serverId)
        ConversationKind.Channel,
        ConversationKind.Joint -> toHomeChannelRow().toSharedConversationDirectoryProjection(serverId)
        ConversationKind.Thread,
        ConversationKind.Inbox,
        ConversationKind.System,
        ConversationKind.Task -> null
    }

internal fun HomeSnapshot.toSharedConversationDirectoryState(
    logger: (String) -> Unit = {}
): SharedConversationDirectoryState =
    sharedConversationDirectoryState(
        serverId = server.id,
        conversations = channels + directMessages,
        logger = logger
    )

internal fun InboxSnapshot.toSharedConversationDirectoryState(
    dmPeerNames: Map<String, String> = emptyMap(),
    logger: (String) -> Unit = {}
): SharedConversationDirectoryState =
    items.toSharedConversationDirectoryState(server.id, dmPeerNames, logger)

internal fun Iterable<InboxItem>.toSharedConversationDirectoryState(
    serverId: ServerId,
    dmPeerNames: Map<String, String> = emptyMap(),
    logger: (String) -> Unit = {}
): SharedConversationDirectoryState =
    sharedConversationDirectoryStateFromProjections(
        projections = flatMap { item -> item.toSharedConversationDirectoryProjections(serverId, dmPeerNames, logger) },
        logger = logger
    )

internal fun Collection<SharedConversationDirectoryProjection>.toSharedConversationDirectoryState(
    logger: (String) -> Unit = {}
): SharedConversationDirectoryState =
    sharedConversationDirectoryStateFromProjections(
        projections = this,
        logger = logger
    )

internal fun Iterable<SharedConversationDirectoryState>.mergedSharedConversationDirectoryState(
    logger: (String) -> Unit = {}
): SharedConversationDirectoryState =
    sharedConversationDirectoryStateFromProjections(
        projections = flatMap { state -> state.projectionsByCanonicalId.values },
        logger = logger
    )

private fun sharedConversationDirectoryState(
    serverId: ServerId,
    conversations: List<HomeConversationRow>,
    logger: (String) -> Unit
): SharedConversationDirectoryState =
    // One registration rule for every directory source (t432 PR-C): this Home-row builder
    // previously skipped the parent-id alias registration the projection builder performs,
    // so the same projection set produced different alias tables depending on entry point.
    // Home-source directories now also resolve by parent ids — a safe gain under
    // canonical-first resolution (#281) and the parent-never-claims-child rule.
    sharedConversationDirectoryStateFromProjections(
        projections = conversations.mapNotNull { it.toSharedConversationDirectoryProjection(serverId) },
        logger = logger
    )

private fun sharedConversationDirectoryStateFromProjections(
    projections: Iterable<SharedConversationDirectoryProjection>,
    logger: (String) -> Unit
): SharedConversationDirectoryState {
    val claimed = projections.filter { it.canonicalChannelId.isNotBlank() }
    val byCanonicalId = claimed.associateBy { it.canonicalChannelId }
    // Two projections self-attesting the SAME canonical id remain last-wins. Keep the
    // evidence bounded: Home/Activity merge rebuilds this directory frequently, and logging
    // every duplicate claim on every rebuild produced thousands of identical JSONL rows.
    val canonicalOverlaps =
        if (claimed.size != byCanonicalId.size) {
            claimed.groupBy { it.canonicalChannelId }.filterValues { it.size > 1 }
        } else {
            emptyMap()
        }
    val canonicalKindConflicts =
        canonicalOverlaps.filterValues { claims -> claims.map { it.conversationKind }.distinct().size > 1 }
    val aliasesById =
        buildMap<String, MutableSet<String>> {
            byCanonicalId.values.forEach { projection ->
                putAlias(projection.canonicalChannelId, projection.canonicalChannelId)
                projection.aliases.forEach { alias -> putAlias(alias, projection.canonicalChannelId) }
                projection.parentChannelId?.let { alias -> putAlias(alias, projection.canonicalChannelId) }
                projection.parentMessageId?.let { alias -> putAlias(alias, projection.canonicalChannelId) }
            }
        }.mapValues { (_, values) -> values.toSet() }
    val duplicateAliases = aliasesById.filterValues { it.size > 1 }
    if (canonicalOverlaps.isNotEmpty() || duplicateAliases.isNotEmpty()) {
        val canonicalOverlapSample =
            canonicalOverlaps.entries
                .take(ConversationConflictSampleLimit)
                .joinToString("|") { (canonicalId, claims) -> "$canonicalId:${claims.size}" }
                .ifBlank { "_" }
        val canonicalKindConflictSample =
            canonicalKindConflicts.entries
                .take(ConversationConflictSampleLimit)
                .joinToString("|") { (canonicalId, claims) ->
                    "$canonicalId:${claims.map { it.conversationKind.name }.distinct().joinToString(",").take(96)}"
                }
                .ifBlank { "_" }
        val aliasSample =
            duplicateAliases.entries
                .take(ConversationConflictSampleLimit)
                .joinToString("|") { (alias, canonicalIds) -> "$alias:${canonicalIds.size}" }
                .ifBlank { "_" }
        logger(
            "conversation_identity_merge_summary canonicalOverlapCount=${canonicalOverlaps.size} " +
                "canonicalKindConflictCount=${canonicalKindConflicts.size} aliasAmbiguityCount=${duplicateAliases.size} " +
                "canonicalOverlapSample=$canonicalOverlapSample " +
                "canonicalKindConflictSample=$canonicalKindConflictSample aliasSample=$aliasSample"
        )
    }
    return SharedConversationDirectoryState(
        projectionsByCanonicalId = byCanonicalId,
        aliasesById = aliasesById,
        duplicateAliases = duplicateAliases
    )
}

private const val ConversationConflictSampleLimit = 4

private fun MutableMap<String, MutableSet<String>>.putAlias(aliasId: String, canonicalChannelId: String) {
    if (aliasId.isBlank() || canonicalChannelId.isBlank()) return
    getOrPut(aliasId) { linkedSetOf() }.add(canonicalChannelId)
}

internal fun HomeConversationRow.toSharedConversationDirectoryProjection(
    serverId: ServerId
): SharedConversationDirectoryProjection? {
    val canonicalChannelId = directoryCanonicalChannelId().value.takeIf { it.isNotBlank() } ?: return null
    val title =
        safeDisplayNameOrNull(additionalIdentityIds = setOf(canonicalChannelId))
            ?: canonicalChannelId
    val kind = toDirectoryConversationKind()
    return SharedConversationDirectoryProjection(
        serverId = serverId.value,
        canonicalChannelId = canonicalChannelId,
        aliases = aliases(canonicalChannelId).map { it.id }.filter { it.isNotBlank() }.toSet() + canonicalChannelId,
        conversationKind = kind,
        surfaceKind = kind.toRaftSurfaceKind(),
        channelKind = toDirectoryChannelType(),
        title = title,
        subtitle = subtitle?.takeIf { it.isNotBlank() },
        avatarWireValue = directMessagePeerAvatarWireValue?.takeIf { it.isNotBlank() },
        avatarLabel = title.take(2).uppercase(),
        peerIdentity =
            if (kind == ConversationKind.DirectMessage) {
                memberDetailIdentityFromWire(
                    memberId = presenceMemberId?.value,
                    kindWireValue = directMessagePeerType,
                    sourceServerId = serverId.value,
                    source = MemberDetailIdentitySource.DirectoryDmPeer
                )
            } else {
                null
            },
        parentChannelId = if (kind == ConversationKind.Thread) thread?.parentChannelId?.value else null,
        parentMessageId = if (kind == ConversationKind.Thread) thread?.parentMessageId?.value else null,
        joined = joined && permissions.canRead,
        archived = archived,
        activityNotificationPreference = activityNotificationPreference,
        permissions = permissions,
        channelCapabilities = channelCapabilities,
        jointBillingLocked = jointBillingLocked,
        jointOverLimitGraceEndsAt = jointOverLimitGraceEndsAt,
        bridgeProvider = if (kind == ConversationKind.Thread) null else bridgeProvider,
        guestVisible = kind != ConversationKind.Thread && guestVisible
    )
}

private fun InboxItem.toSharedConversationDirectoryProjections(
    serverId: ServerId,
    dmPeerNames: Map<String, String> = emptyMap(),
    logger: (String) -> Unit
): List<SharedConversationDirectoryProjection> {
    // Workspace Artifacts rows name no conversation.
    if (kind == InboxItemKind.Object) return emptyList()
    val parentProjection =
        if (kind == InboxItemKind.Thread) {
            parentChannelProjection(serverId, dmPeerNames, logger)
        } else {
            null
        }
    // Task v2: a task conversation row proves its parent channel only; it is not a reply-thread
    // projection (no parent message) and is opened by its own typed identity.
    if (isTaskConversation) return listOfNotNull(parentProjection)
    val itemProjection = toSharedConversationDirectoryProjection(serverId, dmPeerNames)
    return listOfNotNull(parentProjection, itemProjection)
}

private fun InboxItem.parentChannelProjection(
    serverId: ServerId,
    dmPeerNames: Map<String, String> = emptyMap(),
    logger: (String) -> Unit
): SharedConversationDirectoryProjection? {
    val parentId = parentChannelId?.value?.takeIf { it.isNotBlank() } ?: return null
    val title =
        parentChannelTitle(dmPeerNames)
            .safeInboxDisplayNameOrNull(additionalIdentityIds = setOf(parentId))
            ?: parentId
    val conversationKind =
        parentChannelKind
            ?: run {
                logger(
                    "conversation_directory_parent_projection_drop " +
                        "reason=missing_parent_channel_kind serverId=${serverId.value} " +
                        "threadChannelId=${threadChannelId?.value.orEmpty()} parentChannelId=$parentId"
                )
                return null
            }
    val channelType = conversationKind.toDirectoryChannelType()
    return SharedConversationDirectoryProjection(
        serverId = serverId.value,
        canonicalChannelId = parentId,
        // The parent projection proves only the parent conversation. A thread
        // stream id belongs to the child ReplyThread projection and must not
        // be claimed as a parent alias.
        aliases = setOf(parentId),
        conversationKind = conversationKind,
        surfaceKind = conversationKind.toRaftSurfaceKind(),
        channelKind = channelType,
        title = title,
        subtitle = null,
        parentChannelId = null,
        parentMessageId = null
    )
}

internal fun InboxItem.toSharedConversationDirectoryProjection(
    serverId: ServerId,
    dmPeerNames: Map<String, String> = emptyMap()
): SharedConversationDirectoryProjection? {
    val canonicalChannelId =
        when (kind) {
            InboxItemKind.Thread -> threadChannelId ?: channelId
            else -> channelId
        }.value.takeIf { it.isNotBlank() } ?: return null
    val parentId = parentChannelId?.value?.takeIf { it.isNotBlank() }
    val parentMessage = parentMessageId?.value?.takeIf { it.isNotBlank() }
    val identityIds =
        buildSet {
            add(canonicalChannelId)
            channelId.value.takeIf { it.isNotBlank() }?.let(::add)
            threadChannelId?.value?.takeIf { it.isNotBlank() }?.let(::add)
            parentId?.let(::add)
            parentMessage?.let(::add)
        }
    val titleSource =
        when (kind) {
            InboxItemKind.Thread -> parentChannelTitle(dmPeerNames)
            else -> channelName
        }
    val title =
        titleSource.safeInboxDisplayNameOrNull(additionalIdentityIds = identityIds)
            ?: canonicalChannelId
    val conversationKind = directoryConversationKind()
    val sender = toSharedConversationDirectorySender()
    return SharedConversationDirectoryProjection(
        serverId = serverId.value,
        canonicalChannelId = canonicalChannelId,
        aliases =
            buildSet {
                add(canonicalChannelId)
                channelId.value.takeIf { it.isNotBlank() }?.let(::add)
                threadChannelId?.value?.takeIf { it.isNotBlank() }?.let(::add)
                parentId?.let(::add)
                parentMessage?.let(::add)
                firstUnreadMessageId?.value?.takeIf { it.isNotBlank() }?.let(::add)
                lastMessageId?.value?.takeIf { it.isNotBlank() }?.let(::add)
                latestActivityMessageId?.value?.takeIf { it.isNotBlank() }?.let(::add)
            },
        conversationKind = conversationKind,
        surfaceKind = conversationKind.toRaftSurfaceKind(),
        channelKind =
            when (kind) {
                InboxItemKind.Thread -> "thread"
                else -> channelKind.toDirectoryChannelType()
            },
        title = title,
        subtitle =
            if (kind == InboxItemKind.Thread) {
                parentMessagePreview?.takeIf { it.isNotBlank() }
            } else {
                lastMessagePreview?.takeIf { it.isNotBlank() }
            },
        avatarWireValue = null,
        avatarLabel = title.take(2).uppercase(),
        senderMessageIds = sender.messageIds,
        senderMemberId = sender.memberId,
        senderAvatarWireValue = sender.avatarWireValue,
        senderAvatarLabel = sender.label,
        peerIdentity = null,
        parentChannelId = parentId,
        parentMessageId = parentMessage
    )
}

/**
 * task #399: the parent title for a DM-parent thread is the peer's display
 * name (hydrated from the Home DM list), not the raw channel name; the raw
 * name is only a fail-soft fallback when the DM list has not landed.
 */
private fun InboxItem.parentChannelTitle(dmPeerNames: Map<String, String>): String =
    if (parentChannelKind == ConversationKind.DirectMessage) {
        parentChannelId?.value?.let(dmPeerNames::get)?.takeIf { it.isNotBlank() }
            ?: parentChannelName
            ?: channelName
    } else {
        parentChannelName ?: channelName
    }

private data class SharedConversationDirectorySender(
    val messageIds: Set<String>,
    val memberId: String?,
    val avatarWireValue: String?,
    val label: String?
)

private fun InboxItem.toSharedConversationDirectorySender(): SharedConversationDirectorySender {
    val latestMessageId = latestActivityMessageId?.value?.takeIf { it.isNotBlank() }
    val lastMessageId = lastMessageId?.value?.takeIf { it.isNotBlank() }
    val latest =
        sharedConversationDirectorySenderCandidate(
            messageIds = setOfNotNull(latestMessageId),
            kind = latestActivitySenderKind,
            id = latestActivitySenderId,
            avatarWireValue = latestActivitySenderAvatarWireValue,
            label = latestActivitySenderName
        )
    if (latest.memberId != null || latest.avatarWireValue != null || latest.label != null) return latest
    return sharedConversationDirectorySenderCandidate(
        messageIds = setOfNotNull(lastMessageId),
        kind = lastMessageSenderKind,
        id = lastMessageSenderId,
        avatarWireValue = lastMessageSenderAvatarWireValue,
        label = lastMessageSenderName
    )
}

private fun sharedConversationDirectorySenderCandidate(
    messageIds: Set<String>,
    kind: MemberKind?,
    id: String?,
    avatarWireValue: String?,
    label: String?
): SharedConversationDirectorySender {
    val cleanId = id?.takeIf { it.isNotBlank() }
    return SharedConversationDirectorySender(
        messageIds = messageIds,
        memberId = cleanId,
        avatarWireValue = avatarWireValue?.takeIf { it.isNotBlank() },
        label = label?.takeIf { it.isNotBlank() }
    )
}

private data class ConversationDirectoryAlias(
    val id: String,
    val kind: SharedConversationAliasKind
)

private fun HomeConversationRow.aliases(canonicalChannelId: String): Set<ConversationDirectoryAlias> =
    buildSet {
        val routeIdentity = resolveThreadRouteIdentity(
            surfaceKind = toDirectoryConversationKind().toRaftSurfaceKind(),
            channelKind = toDirectoryChannelType(),
            instanceId = null,
            sourceThreadId = threadId.value,
            streamChannelId = directoryCanonicalChannelId().value,
            parentChannelId = if (kind == ConversationKind.Thread) thread?.parentChannelId?.value else null,
            parentMessageId = if (kind == ConversationKind.Thread) thread?.parentMessageId?.value else null
        )
        val isReplyThread = kind == ConversationKind.Thread
        add(ConversationDirectoryAlias(canonicalChannelId, SharedConversationAliasKind.Stream))
        channelId
            ?.value
            ?.takeIf { it.isNotBlank() }
            ?.takeIf { kind != ConversationKind.Thread || it == routeIdentity.timelineChannelId }
            ?.let { add(ConversationDirectoryAlias(it, SharedConversationAliasKind.Channel)) }
        threadId.value.takeIf { it.isNotBlank() }?.let { add(ConversationDirectoryAlias(it, SharedConversationAliasKind.Thread)) }
        if (isReplyThread) {
            thread?.streamChannelId?.value?.takeIf { it.isNotBlank() }?.let { add(ConversationDirectoryAlias(it, SharedConversationAliasKind.Stream)) }
            thread?.parentChannelId?.value?.takeIf { it.isNotBlank() }?.let { add(ConversationDirectoryAlias(it, SharedConversationAliasKind.ParentChannel)) }
            thread?.parentMessageId?.value?.takeIf { it.isNotBlank() }?.let { add(ConversationDirectoryAlias(it, SharedConversationAliasKind.ParentMessage)) }
        }
        presenceMemberId?.value?.takeIf { it.isNotBlank() }?.let { add(ConversationDirectoryAlias(it, SharedConversationAliasKind.PresenceMember)) }
    }

private fun HomeConversationRow.directoryCanonicalChannelId(): ChannelId =
    if (kind == ConversationKind.Thread) {
        thread?.streamChannelId ?: ChannelId(threadId.value)
    } else {
        channelId ?: ChannelId(threadId.value)
    }

private fun HomeConversationRow.toDirectoryChannelType(): String =
    when (kind) {
        ConversationKind.Thread -> "thread"
        ConversationKind.Joint -> "joint"
        ConversationKind.Channel -> channelType?.takeIf { it.isNotBlank() } ?: "channel"
        ConversationKind.DirectMessage -> "dm"
        ConversationKind.Task -> RaftTaskConversationChannelKind
        ConversationKind.Inbox,
        ConversationKind.System -> "channel"
    }

private fun InboxItem.directoryConversationKind(): ConversationKind =
    when (kind) {
        InboxItemKind.DirectMessage -> ConversationKind.DirectMessage
        InboxItemKind.Thread -> ConversationKind.Thread
        InboxItemKind.MentionAction -> channelKind ?: ConversationKind.Channel
        InboxItemKind.System, InboxItemKind.Object -> ConversationKind.System
        InboxItemKind.Channel ->
            when (channelKind) {
                ConversationKind.Joint -> ConversationKind.Joint
                else -> ConversationKind.Channel
            }
    }

private fun HomeConversationRow.toDirectoryConversationKind(): ConversationKind =
    when (kind) {
        ConversationKind.Thread -> ConversationKind.Thread
        ConversationKind.Joint -> ConversationKind.Joint
        ConversationKind.DirectMessage -> ConversationKind.DirectMessage
        ConversationKind.Task -> ConversationKind.Task
        ConversationKind.Channel,
        ConversationKind.Inbox,
        ConversationKind.System -> ConversationKind.Channel
    }

private fun HomeConversationRow.safeDisplayNameOrNull(
    additionalIdentityIds: Set<String> = emptySet()
): String? =
    conversationSafeDisplayTitleOrNull(name, headerIdentityIds() + additionalIdentityIds)
        ?.takeUnless { isGenericDisplayName(it, additionalIdentityIds) }

private fun HomeConversationRow.isGenericDisplayName(
    rawName: String = name,
    additionalIdentityIds: Set<String> = emptySet()
): Boolean {
    val candidate = conversationDisplayTitleCandidate(rawName) ?: return true
    if (conversationIdentityLikeTitle(candidate, headerIdentityIds() + additionalIdentityIds)) return true
    return candidate.equals(genericDisplayTitleFallback(), ignoreCase = true)
}

private fun HomeConversationRow.genericDisplayTitleFallback(): String =
    when (kind) {
        ConversationKind.DirectMessage -> "Direct Message"
        ConversationKind.Thread -> "Thread"
        ConversationKind.Channel,
        ConversationKind.Joint,
        ConversationKind.Inbox,
        ConversationKind.System,
        ConversationKind.Task -> "Channel"
    }

private fun HomeConversationRow.headerIdentityIds(): Set<String> =
    listOfNotNull(
        threadId.value,
        channelId?.value,
        thread?.streamChannelId?.value
    ).filter { it.isNotBlank() }.toSet()

private fun ConversationKind?.toDirectoryChannelType(): String =
    when (this) {
        ConversationKind.DirectMessage -> toWireChannelKindOrNull() ?: "dm"
        ConversationKind.Joint -> toWireChannelKindOrNull() ?: "joint"
        ConversationKind.Thread -> "thread"
        ConversationKind.Task -> RaftTaskConversationChannelKind
        ConversationKind.Channel,
        ConversationKind.Inbox,
        ConversationKind.System,
        null -> "channel"
    }

private fun String?.safeInboxDisplayNameOrNull(
    additionalIdentityIds: Set<String> = emptySet()
): String? =
    conversationSafeDisplayTitleOrNull(this, additionalIdentityIds)
        ?.takeUnless { candidate -> candidate.isGenericInboxDisplayName(additionalIdentityIds) }

private fun String.isGenericInboxDisplayName(additionalIdentityIds: Set<String>): Boolean {
    val candidate = conversationDisplayTitleCandidate(this) ?: return true
    if (conversationIdentityLikeTitle(candidate, additionalIdentityIds)) return true
    return candidate.equals("Channel", ignoreCase = true) ||
        candidate.equals("Thread", ignoreCase = true) ||
        candidate.equals("Direct Message", ignoreCase = true)
}
