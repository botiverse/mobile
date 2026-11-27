package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import build.raft.app.data.ResolvedActor
import build.raft.app.data.SharedJointActorDirectory
import build.raft.app.data.SharedServerChannelMembersSlice
import build.raft.app.data.SharedServerMembersEntry
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.avatarWireValueForAvatarSlot
import build.raft.app.thread.RaftThreadMemberItem
import build.raft.app.thread.displayName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal const val PendingMentionActionRowTargetTestTag = "pending-mention-action-target"
internal const val PendingMentionActionRowStatusTestTag = "pending-mention-action-status"
internal const val PendingMentionActionBatchNamesTestTag = "pending-mention-action-batch-names"

/**
 * How a mention reads: `@` and the display name of who it names; `@` and the name written when
 * it names nobody known (botiverse/slock #16493 `mentionLabel.ts`). One rule for the mention in
 * a sent message ([threadMarkdownMentionDisplayLabels]) and for the pending mention banner, so
 * the two never disagree.
 */
internal fun mentionVisibleLabel(name: String, displayName: String?): String {
    val visible = displayName?.trim()?.removePrefix("@")?.trim()?.takeIf { it.isNotEmpty() }
    val written = name.trim().removePrefix("@").trim()
    return when {
        visible != null -> "@$visible"
        written.isNotEmpty() -> "@$written"
        else -> "@unknown"
    }
}

/**
 * A resolved mention target from the server/channel roster or joint-channel actor directory.
 * [targetType] uses the wire values `"user"` or `"agent"` matching [RaftPendingMentionActionItem.targetType].
 */
@Immutable
internal data class PendingMentionTargetEntry(
    val handle: String,
    val displayName: String,
    val targetType: String,
    val memberId: String? = null,
    val sourceServerId: String? = null,
    val avatarWireValue: String? = null,
    val avatarLabel: String? = null,
) {
    val kind: MemberKind
        get() = if (targetType == "agent") MemberKind.Agent else MemberKind.Human

    fun avatarDisplayLabel(): String =
        displayName.trim().ifEmpty { avatarLabel?.trim().orEmpty() }.ifEmpty { handle }
}

/**
 * Lookup of mentionable targets by `(targetType, handle)` built from the server directory, channel
 * roster, and cached joint-channel actors (web `buildMentionMap` + `pendingMentionTargetDisplayName`,
 * botiverse/slock #16493).
 *
 * Unlike the composer's `@` list — which in a private or joint channel holds channel members only,
 * whereas an unnotified mention target is by definition not a member — this map covers every person
 * and agent the server and channel know. Lookup prefers exact-case handle matches before
 * case-insensitive matches, and enforces the row's own `targetType` so a person handle matching an
 * agent's name (or vice versa) stays as written.
 */
@Immutable
internal class PendingMentionTargetMap private constructor(
    private val exactByKey: Map<Pair<String, String>, PendingMentionTargetEntry>,
    private val lowerByKey: Map<Pair<String, String>, PendingMentionTargetEntry>,
) {
    fun resolve(targetType: String, targetHandle: String): PendingMentionTargetEntry? {
        if (targetType != "user" && targetType != "agent") return null
        val handle = targetHandle.trim().removePrefix("@").trim()
        if (handle.isEmpty()) return null
        return exactByKey[targetType to handle] ?: lowerByKey[targetType to handle.lowercase()]
    }

    fun resolve(action: RaftPendingMentionActionItem): PendingMentionTargetEntry? =
        resolve(action.targetType, action.targetHandle)

    fun displayName(action: RaftPendingMentionActionItem): String? =
        resolve(action)?.displayName

    fun isEmpty(): Boolean = exactByKey.isEmpty()

    /** Combines this map with [higherPriority], letting [higherPriority] win on colliding keys. */
    fun mergedWith(higherPriority: PendingMentionTargetMap): PendingMentionTargetMap {
        if (isEmpty()) return higherPriority
        if (higherPriority.isEmpty()) return this
        val exact = LinkedHashMap(exactByKey)
        val lower = LinkedHashMap(lowerByKey)
        exact.putAll(higherPriority.exactByKey)
        lower.putAll(higherPriority.lowerByKey)
        return PendingMentionTargetMap(exact, lower)
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is PendingMentionTargetMap &&
                exactByKey == other.exactByKey &&
                lowerByKey == other.lowerByKey)

    override fun hashCode(): Int = 31 * exactByKey.hashCode() + lowerByKey.hashCode()

    companion object {
        val Empty = PendingMentionTargetMap(emptyMap(), emptyMap())

        fun fromEntries(entries: Iterable<PendingMentionTargetEntry>): PendingMentionTargetMap {
            val exact = linkedMapOf<Pair<String, String>, PendingMentionTargetEntry>()
            val lower = linkedMapOf<Pair<String, String>, PendingMentionTargetEntry>()
            for (entry in entries) {
                val type = entry.targetType
                if (type != "user" && type != "agent") continue
                val handle = entry.handle.trim().removePrefix("@").trim()
                if (handle.isEmpty()) continue
                val displayName = entry.displayName.trim().ifEmpty { handle }
                val normalized =
                    if (entry.handle == handle && entry.displayName == displayName) {
                        entry
                    } else {
                        entry.copy(handle = handle, displayName = displayName)
                    }
                exact[type to handle] = normalized
                lower[type to handle.lowercase()] = normalized
            }
            return if (exact.isEmpty()) Empty else PendingMentionTargetMap(exact, lower)
        }
    }
}

/** Server + channel + joint-actor mention targets provided by the active conversation page. */
internal val LocalPendingMentionTargetMap = compositionLocalOf { PendingMentionTargetMap.Empty }

/**
 * Builds a [PendingMentionTargetMap] layering sources from lowest to highest precedence:
 * 1. Cached other-server joint actors ([jointActors])
 * 2. Composer member suggestions ([memberSuggestions])
 * 3. Payload server directory candidates ([payloadMemberCandidates])
 * 4. Live server member roster ([serverMembers])
 * 5. Payload channel members ([payloadMembers])
 * 6. Live channel member roster ([channelMembers]) — including joint-channel external members
 */
internal fun buildPendingMentionTargetMap(
    serverMembers: List<MemberProfile> = emptyList(),
    channelMembers: List<MemberProfile> = emptyList(),
    payloadMembers: List<RaftThreadMemberItem> = emptyList(),
    payloadMemberCandidates: List<RaftThreadMemberItem> = emptyList(),
    memberSuggestions: List<RaftComposerMemberItem> = emptyList(),
    jointActors: List<ResolvedActor> = emptyList(),
): PendingMentionTargetMap {
    if (
        jointActors.isEmpty() &&
        memberSuggestions.isEmpty() &&
        payloadMemberCandidates.isEmpty() &&
        serverMembers.isEmpty() &&
        payloadMembers.isEmpty() &&
        channelMembers.isEmpty()
    ) {
        return PendingMentionTargetMap.Empty
    }
    val entries = buildList {
        jointActors.forEach { actor -> actor.toPendingMentionTargetEntry()?.let(::add) }
        memberSuggestions.forEach { suggestion -> suggestion.toPendingMentionTargetEntry()?.let(::add) }
        payloadMemberCandidates.forEach { member -> member.toPendingMentionTargetEntry()?.let(::add) }
        serverMembers.forEach { member -> member.toPendingMentionTargetEntry()?.let(::add) }
        payloadMembers.forEach { member -> member.toPendingMentionTargetEntry()?.let(::add) }
        channelMembers.forEach { member -> member.toPendingMentionTargetEntry()?.let(::add) }
    }
    return PendingMentionTargetMap.fromEntries(entries)
}

/**
 * Reactive [PendingMentionTargetMap] for a conversation page, mirroring web `MessageInput`'s
 * `buildMentionMap(agents, humans, channelAgents, channelHumans)` plus cached joint-channel actors.
 */
@Composable
internal fun rememberThreadPendingMentionTargetMap(
    serverMembersEntry: SharedServerMembersEntry?,
    channelMembersSlice: SharedServerChannelMembersSlice?,
    payloadMembers: List<RaftThreadMemberItem> = emptyList(),
    payloadMemberCandidates: List<RaftThreadMemberItem> = emptyList(),
    serverId: ServerId?,
    jointActors: SharedJointActorDirectory?,
): PendingMentionTargetMap {
    val jointRowsFlow: StateFlow<Map<*, *>> =
        remember(jointActors) { jointActors?.rows ?: MutableStateFlow<Map<*, *>>(emptyMap<Any?, Any?>()) }
    val jointRows by jointRowsFlow.collectAsState()
    return remember(
        serverMembersEntry,
        channelMembersSlice,
        payloadMembers,
        payloadMemberCandidates,
        serverId,
        jointActors,
        jointRows,
    ) {
        val cleanServerId = serverId?.value?.trim()?.takeIf { it.isNotEmpty() }
        val serverMembers =
            serverMembersEntry
                ?.takeIf { cleanServerId == null || it.serverId.trim() == cleanServerId }
                ?.members
                .orEmpty()
        val channelMembers = channelMembersSlice?.members.orEmpty()
        val resolvedJointActors =
            if (jointActors != null && serverId != null) {
                jointActors.currentActors(serverId)
            } else {
                emptyList()
            }
        buildPendingMentionTargetMap(
            serverMembers = serverMembers,
            channelMembers = channelMembers,
            payloadMembers = payloadMembers,
            payloadMemberCandidates = payloadMemberCandidates,
            jointActors = resolvedJointActors,
        )
    }
}

private fun MemberKind.toPendingMentionWireTargetType(): String? =
    when (this) {
        MemberKind.Human -> "user"
        MemberKind.Agent -> "agent"
        MemberKind.System, MemberKind.External -> null
    }

private fun String.toPendingMentionWireTargetType(): String? =
    when {
        equals("human", ignoreCase = true) || equals("user", ignoreCase = true) -> "user"
        equals("agent", ignoreCase = true) -> "agent"
        else -> null
    }

private fun MemberProfile.toPendingMentionTargetEntry(): PendingMentionTargetEntry? {
    val targetType = kind.toPendingMentionWireTargetType() ?: return null
    val cleanHandle = handle.trim().removePrefix("@").trim()
    if (cleanHandle.isEmpty()) return null
    val resolvedDisplayName = displayName.trim().ifEmpty { cleanHandle }
    return PendingMentionTargetEntry(
        handle = cleanHandle,
        displayName = resolvedDisplayName,
        targetType = targetType,
        memberId = id.value.trim().takeIf { it.isNotEmpty() },
        sourceServerId = serverId?.value?.trim()?.takeIf { it.isNotEmpty() },
        avatarWireValue = avatarWireValueForAvatarSlot()?.takeIf { it.isNotBlank() },
        avatarLabel = avatarLabel.takeIf { it.isNotBlank() },
    )
}

private fun RaftThreadMemberItem.toPendingMentionTargetEntry(): PendingMentionTargetEntry? {
    val targetType = kind.toPendingMentionWireTargetType() ?: return null
    val cleanHandle = handle.trim().removePrefix("@").trim()
    if (cleanHandle.isEmpty()) return null
    val resolvedDisplayName = displayName.trim().ifEmpty { cleanHandle }
    return PendingMentionTargetEntry(
        handle = cleanHandle,
        displayName = resolvedDisplayName,
        targetType = targetType,
        memberId = id.trim().takeIf { it.isNotEmpty() },
        sourceServerId = sourceServerId?.trim()?.takeIf { it.isNotEmpty() },
        avatarWireValue = avatarUrl?.takeIf { it.isNotBlank() },
        avatarLabel = avatarLabel.takeIf { it.isNotBlank() },
    )
}

private fun RaftComposerMemberItem.toPendingMentionTargetEntry(): PendingMentionTargetEntry? {
    val targetType = kind.toPendingMentionWireTargetType() ?: return null
    val cleanHandle = handle.trim().removePrefix("@").trim()
    if (cleanHandle.isEmpty()) return null
    val resolvedDisplayName = displayName.trim().ifEmpty { cleanHandle }
    return PendingMentionTargetEntry(
        handle = cleanHandle,
        displayName = resolvedDisplayName,
        targetType = targetType,
        memberId = memberId?.trim()?.takeIf { it.isNotEmpty() },
        sourceServerId = sourceServerId?.trim()?.takeIf { it.isNotEmpty() },
        avatarWireValue = avatarWireValue?.takeIf { it.isNotBlank() },
        avatarLabel = avatarLabel.takeIf { it.isNotBlank() },
    )
}

private fun ResolvedActor.toPendingMentionTargetEntry(): PendingMentionTargetEntry? {
    val targetType = kind.toPendingMentionWireTargetType() ?: return null
    val cleanHandle = name.trim().removePrefix("@").trim()
    if (cleanHandle.isEmpty()) return null
    val resolvedDisplayName = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: cleanHandle
    return PendingMentionTargetEntry(
        handle = cleanHandle,
        displayName = resolvedDisplayName,
        targetType = targetType,
        memberId = id.trim().takeIf { it.isNotEmpty() },
        sourceServerId = homeServerId.trim().takeIf { it.isNotEmpty() },
        avatarWireValue = avatarUrl?.takeIf { it.isNotBlank() },
        avatarLabel = resolvedDisplayName.toRaftAvatarLabel().takeIf { it.isNotBlank() },
    )
}
