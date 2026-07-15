package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalReactionWire
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The V1 producer shape. It may exist at ingress during the compatibility window, but it is never a
 * V2 canonical fact or a persisted read model.
 */
@Serializable
data class LegacyReactionRosterDto(
    val emoji: String,
    val count: Int,
    val reactorIds: List<String> = emptyList(),
    val reactorNames: List<String> = emptyList(),
)

/** A stable actor identity used by previewK and the typed ReactionActors read cache. */
@Serializable
data class ReactionActorRef(
    val id: String,
    val displayName: String,
)

/** The bounded shared V2 message fact. Full reactor rosters are intentionally absent. */
@Serializable
data class ReactionFact(
    val emoji: String,
    val count: Int,
    val previewK: List<ReactionActorRef> = emptyList(),
)

/** Typed root for Message x ReactionActors(emoji). */
data class ReactionActorsDiscussionRef(
    val serverId: String,
    val messageId: String,
    val emoji: String,
)

/**
 * A complete receiver-private snapshot for one message. Applying it clears the message's previous
 * reaction overlay before setting [reactedEmojis], so an emoji removed from the shared fact cannot leave
 * a stale reactedByMe=true bit behind.
 */
@Serializable
data class ReactionViewerOverlaySnapshot(
    val serverId: String,
    val messageId: String,
    val completeness: String = REACTION_COMPLETENESS_COMPLETE,
    val reactedEmojis: List<String>,
)

/**
 * Non-durable grouping key used only inside one already principal-scoped reaction cache instance.
 *
 * This deliberately has no conversion to/from [SyncScopeKey]: reaction normalization must not fabricate a
 * durable principal or accidentally enter the fold persistence API.
 */
data class ReactionParentScopeKey(
    val serverId: String,
    val scopeId: String,
    val scopeKind: String,
)

/**
 * A complete compatibility seed. Partial/malformed V1 rosters never become a loaded-empty cache entry.
 *
 * Forward ledger: V1 has no partial-roster marker, so `complete` is the only legal value in this adapter.
 * Add another completeness value only together with an authoritative producer signal and red/green tests
 * that prove partial input cannot clear or replace a complete cache entry.
 */
data class ReactionActorsReadCacheSeed(
    val parentScopeKey: ReactionParentScopeKey,
    val messageId: String,
    val emoji: String,
    val actors: List<ReactionActorRef>,
    val completeness: String = REACTION_COMPLETENESS_COMPLETE,
) {
    val discussion: ReactionActorsDiscussionRef
        get() = ReactionActorsDiscussionRef(
            serverId = parentScopeKey.serverId,
            messageId = messageId,
            emoji = emoji,
        )
}

sealed interface ReactionIngressAuthority {
    /** Channel-room frames are shared bytes and therefore cannot author receiver-private state. */
    data object SharedRoom : ReactionIngressAuthority

    /** Hydrate, own-mutation ACK, or user-room input that is authoritative for one viewer. */
    data class ReceiverPrivate(val viewerId: String) : ReactionIngressAuthority
}

data class ReactionNormalizationContext(
    val serverId: String,
    val messageId: String,
    val parentScopeKey: ReactionParentScopeKey,
    val authority: ReactionIngressAuthority,
)

data class NormalizedReactionIngress(
    val sharedFact: List<ReactionFact>,
    val viewerOverlay: ReactionViewerOverlaySnapshot?,
    val readCacheSeed: List<ReactionActorsReadCacheSeed>,
    val violations: List<ReactionNormalizationDiagnostic>,
)

enum class ReactionNormalizationFailure {
    InvalidContext,
    BlankViewerId,
}

@Serializable
enum class ReactionRosterDiagnosticKind {
    @SerialName("duplicate_emoji")
    DuplicateEmoji,
    @SerialName("empty_emoji")
    EmptyEmoji,
    @SerialName("non_canonical_emoji")
    NonCanonicalEmoji,
    @SerialName("invalid_count")
    InvalidCount,
    @SerialName("parallel_roster_length_mismatch")
    ParallelRosterLengthMismatch,
    @SerialName("empty_actor_id")
    EmptyActorId,
    @SerialName("non_canonical_actor_id")
    NonCanonicalActorId,
    @SerialName("duplicate_actor_id")
    DuplicateActorId,
    @SerialName("empty_actor_display_name")
    EmptyActorDisplayName,
    @SerialName("non_canonical_actor_display_name")
    NonCanonicalActorDisplayName,
    @SerialName("actor_count_mismatch")
    ActorCountMismatch,
}

@Serializable
data class ReactionNormalizationDiagnostic(
    val kind: ReactionRosterDiagnosticKind,
    val emoji: String,
)

/**
 * An ingress adapter must diagnose both branches. [Normalized] exposes recoverable violations inline;
 * [Rejected] is a whole-call failure and must be reported independently rather than disappearing because
 * a caller only drained [NormalizedReactionIngress.violations].
 */
sealed interface ReactionNormalizationOutcome {
    data class Normalized(val value: NormalizedReactionIngress) : ReactionNormalizationOutcome
    data class Rejected(
        val failure: ReactionNormalizationFailure,
        val emoji: String? = null,
    ) : ReactionNormalizationOutcome
}

private const val REACTION_COMPLETENESS_COMPLETE = "complete"
const val LEGACY_REACTION_PREVIEW_POLICY: String = "empty-without-room-common-provenance"

@OptIn(ExperimentalSerializationApi::class)
private val reactionFactJson = Json {
    encodeDefaults = true
    explicitNulls = true
    prettyPrint = false
}

fun CanonicalReactionWire.toLegacyReactionRosterDto(): LegacyReactionRosterDto =
    LegacyReactionRosterDto(
        emoji = emoji,
        count = count,
        reactorIds = reactorIds,
        reactorNames = reactorNames,
    )

/**
 * Pure V1 -> V2 split. Rejection is a shadow diagnostic, not an exception: the legacy accept path stays
 * fail-open while normalized sole-apply remains disabled.
 */
fun normalizeLegacyReactionRoster(
    context: ReactionNormalizationContext,
    reactions: List<LegacyReactionRosterDto>,
): ReactionNormalizationOutcome {
    if (
        context.serverId.isBlank() ||
        context.messageId.isBlank() ||
        context.parentScopeKey.serverId != context.serverId
    ) {
        return ReactionNormalizationOutcome.Rejected(ReactionNormalizationFailure.InvalidContext)
    }
    val viewerId = when (val authority = context.authority) {
        ReactionIngressAuthority.SharedRoom -> null
        is ReactionIngressAuthority.ReceiverPrivate -> authority.viewerId.takeIf(String::isNotBlank)
            ?: return ReactionNormalizationOutcome.Rejected(ReactionNormalizationFailure.BlankViewerId)
    }

    val emojiCounts = reactions.groupingBy(LegacyReactionRosterDto::emoji).eachCount()
    val diagnosedDuplicateEmojis = mutableSetOf<String>()
    val facts = mutableListOf<ReactionFact>()
    val seeds = mutableListOf<ReactionActorsReadCacheSeed>()
    val reactedEmojis = mutableListOf<String>()
    val diagnostics = mutableListOf<ReactionNormalizationDiagnostic>()
    var rosterDerivativesAreAuthoritative = true

    for (reaction in reactions) {
        if (reaction.emoji.isBlank()) {
            diagnostics += ReactionNormalizationDiagnostic(ReactionRosterDiagnosticKind.EmptyEmoji, reaction.emoji)
            rosterDerivativesAreAuthoritative = false
            continue
        }
        if (reaction.emoji != reaction.emoji.trim()) {
            diagnostics += ReactionNormalizationDiagnostic(
                ReactionRosterDiagnosticKind.NonCanonicalEmoji,
                reaction.emoji,
            )
            rosterDerivativesAreAuthoritative = false
            continue
        }
        if (reaction.count < 0) {
            diagnostics += ReactionNormalizationDiagnostic(ReactionRosterDiagnosticKind.InvalidCount, reaction.emoji)
            rosterDerivativesAreAuthoritative = false
            continue
        }
        if (emojiCounts[reaction.emoji] != 1) {
            if (diagnosedDuplicateEmojis.add(reaction.emoji)) {
                diagnostics += ReactionNormalizationDiagnostic(ReactionRosterDiagnosticKind.DuplicateEmoji, reaction.emoji)
            }
            rosterDerivativesAreAuthoritative = false
            continue
        }

        val rosterDiagnostics = mutableListOf<ReactionRosterDiagnosticKind>()
        if (reaction.reactorIds.size != reaction.reactorNames.size) {
            rosterDiagnostics += ReactionRosterDiagnosticKind.ParallelRosterLengthMismatch
        }
        val actors = mutableListOf<ReactionActorRef>()
        val actorIds = mutableSetOf<String>()
        reaction.reactorIds.forEachIndexed { index, rawId ->
            val id = rawId.trim()
            if (id.isEmpty()) {
                rosterDiagnostics += ReactionRosterDiagnosticKind.EmptyActorId
                return@forEachIndexed
            }
            if (rawId != id) {
                rosterDiagnostics += ReactionRosterDiagnosticKind.NonCanonicalActorId
            }
            if (!actorIds.add(id)) {
                rosterDiagnostics += ReactionRosterDiagnosticKind.DuplicateActorId
                return@forEachIndexed
            }
            val rawDisplayName = reaction.reactorNames.getOrNull(index).orEmpty()
            val displayName = rawDisplayName.trim()
            if (displayName.isEmpty()) {
                rosterDiagnostics += ReactionRosterDiagnosticKind.EmptyActorDisplayName
                return@forEachIndexed
            }
            if (rawDisplayName != displayName) {
                rosterDiagnostics += ReactionRosterDiagnosticKind.NonCanonicalActorDisplayName
            }
            actors += ReactionActorRef(id = id, displayName = displayName)
        }
        if (actors.size != reaction.count) {
            rosterDiagnostics += ReactionRosterDiagnosticKind.ActorCountMismatch
        }
        val distinctRosterDiagnostics = rosterDiagnostics.distinct()
        val rosterIsComplete = distinctRosterDiagnostics.isEmpty()
        distinctRosterDiagnostics.forEach { kind ->
            diagnostics += ReactionNormalizationDiagnostic(kind = kind, emoji = reaction.emoji)
        }
        if (!rosterIsComplete) rosterDerivativesAreAuthoritative = false
        val orderedActors = actors.sortedBy(ReactionActorRef::id)

        facts += ReactionFact(
            emoji = reaction.emoji,
            count = reaction.count,
            // The legacy roster may be viewer-filtered (for example hideHumansFromMembers), so it has no
            // room-common provenance. Actor detail may seed the viewer-partitioned cache, but names cannot
            // enter a shared fact until the server provides an all-receivers-safe preview surface.
            previewK = emptyList(),
        )

        if (rosterIsComplete) {
            seeds += ReactionActorsReadCacheSeed(
                parentScopeKey = context.parentScopeKey,
                messageId = context.messageId,
                emoji = reaction.emoji,
                actors = orderedActors,
            )
        }

        if (rosterIsComplete && viewerId != null && viewerId in actorIds) {
            reactedEmojis += reaction.emoji
        }
    }

    val orderedFacts = facts.sortedBy(ReactionFact::emoji)
    val orderedSeeds = seeds.sortedBy { it.discussion.emoji }
    val overlay = viewerId?.takeIf { rosterDerivativesAreAuthoritative }?.let {
        ReactionViewerOverlaySnapshot(
            serverId = context.serverId,
            messageId = context.messageId,
            reactedEmojis = reactedEmojis.sorted(),
        )
    }
    return ReactionNormalizationOutcome.Normalized(
        NormalizedReactionIngress(
            sharedFact = orderedFacts,
            viewerOverlay = overlay,
            readCacheSeed = orderedSeeds,
            violations = diagnostics,
        ),
    )
}

/** Byte-stable shared-fact encoding used by the future reviewed Web/KMP golden vector. */
fun encodeReactionFactsCanonical(reactions: List<ReactionFact>): String =
    reactionFactJson.encodeToString(reactions.sortedBy(ReactionFact::emoji)) + "\n"

fun encodeReactionViewerOverlayCanonical(overlay: ReactionViewerOverlaySnapshot?): String =
    reactionFactJson.encodeToString(overlay) + "\n"

sealed interface ReactionActorsCacheLookup {
    /** Cache absence/eviction is unknown, never an authoritative empty actor list. */
    data object Unknown : ReactionActorsCacheLookup
    data class Loaded(val actors: List<ReactionActorRef>) : ReactionActorsCacheLookup
}

/**
 * Small backend-neutral LRU for the typed ReactionActors read side. One instance belongs to exactly one
 * viewer/principal partition; constructing an unpartitioned process-global actor cache is not possible.
 */
class BoundedReactionActorsReadCache(
    val principalScope: String,
    maxEntries: Int,
) {
    private data class Entry(
        val parentScopeKey: ReactionParentScopeKey,
        val actors: List<ReactionActorRef>,
    )

    private val capacity = maxEntries.also { require(principalScope.isNotBlank() && it > 0) }
    private val entries = linkedMapOf<ReactionActorsDiscussionRef, Entry>()
    private val keysByParentScope = mutableMapOf<ReactionParentScopeKey, MutableSet<ReactionActorsDiscussionRef>>()

    fun seed(seed: ReactionActorsReadCacheSeed) {
        remove(seed.discussion)
        entries[seed.discussion] = Entry(seed.parentScopeKey, seed.actors.toList())
        keysByParentScope.getOrPut(seed.parentScopeKey, ::mutableSetOf).add(seed.discussion)
        evictIfNeeded()
    }

    fun seedAll(seeds: Iterable<ReactionActorsReadCacheSeed>) {
        seeds.forEach(::seed)
    }

    fun get(discussion: ReactionActorsDiscussionRef): ReactionActorsCacheLookup {
        val entry = entries.remove(discussion) ?: return ReactionActorsCacheLookup.Unknown
        entries[discussion] = entry
        return ReactionActorsCacheLookup.Loaded(entry.actors)
    }

    fun clearScope(parentScopeKey: ReactionParentScopeKey) {
        keysByParentScope.remove(parentScopeKey).orEmpty().forEach(entries::remove)
    }

    val size: Int get() = entries.size

    private fun evictIfNeeded() {
        while (entries.size > capacity) {
            remove(entries.keys.first())
        }
    }

    private fun remove(discussion: ReactionActorsDiscussionRef) {
        val removed = entries.remove(discussion) ?: return
        keysByParentScope[removed.parentScopeKey]?.let { keys ->
            keys.remove(discussion)
            if (keys.isEmpty()) keysByParentScope.remove(removed.parentScopeKey)
        }
    }
}
