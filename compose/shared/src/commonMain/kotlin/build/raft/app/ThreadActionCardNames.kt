package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import build.raft.app.data.KnownEntityScope
import build.raft.app.data.RaftKnownEntityResolver
import build.raft.app.data.SharedJointActorDirectory
import build.raft.app.data.SharedServerMembersEntry
import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What an id on an action card names (web `useActionCardDetail` lookups). */
internal enum class ThreadActionCardEntityKind { Human, Agent, Channel }

/**
 * Names for the ids an action card stores (`initialHumans`, `agents`, `channel`, `targetAgent`,
 * ...): the server resolves an agent's handles to UUIDs when it prepares the card, so the card
 * itself carries only ids. Web looks them up in its member, agent and channel stores; this is
 * the same lookup over what the app already holds in memory. Null when nothing local knows it.
 */
internal fun interface ThreadActionCardNameLookup {
    fun name(kind: ThreadActionCardEntityKind, id: String): String?

    companion object {
        val None = ThreadActionCardNameLookup { _, _ -> null }
    }
}

/** The card's name lookup for the server the thread is viewed in. */
internal val LocalThreadActionCardNames = compositionLocalOf { ThreadActionCardNameLookup.None }

/**
 * The lookup over this server's roster (people and agents: web `useServerStore.members` and
 * `useAgentStore.agents`), then a joint channel's other-server actors the app already resolved
 * ([SharedJointActorDirectory], read only: a card never asks the server for names), and for
 * channels the conversations the app already knows ([RaftKnownEntityResolver], web
 * `useChannelStore.channels`). Like web: display name first, then the handle.
 */
internal fun threadActionCardNameLookup(
    members: SharedServerMembersEntry?,
    serverId: String?,
    jointActors: SharedJointActorDirectory? = null,
    knownEntities: RaftKnownEntityResolver? = null,
    accountId: String? = null,
): ThreadActionCardNameLookup {
    val cleanServerId = serverId?.trim()?.takeIf { it.isNotEmpty() }
    return ThreadActionCardNameLookup { kind, rawId ->
        val id = rawId.trim().takeIf { it.isNotEmpty() } ?: return@ThreadActionCardNameLookup null
        when (kind) {
            ThreadActionCardEntityKind.Human,
            ThreadActionCardEntityKind.Agent -> {
                val memberKind = if (kind == ThreadActionCardEntityKind.Human) MemberKind.Human else MemberKind.Agent
                members?.membersById?.get(id)
                    ?.takeIf { it.kind == memberKind }
                    ?.let { profile -> profile.displayName.takeIf { it.isNotBlank() } ?: profile.handle.takeIf { it.isNotBlank() } }
                    ?: cleanServerId?.let { server ->
                        jointActors?.current(ServerId(server), id, memberKind)
                            ?.let { actor -> actor.displayName?.takeIf { it.isNotBlank() } ?: actor.name.takeIf { it.isNotBlank() } }
                    }
            }
            ThreadActionCardEntityKind.Channel -> {
                val account = accountId?.trim()?.takeIf { it.isNotEmpty() }
                if (cleanServerId == null || account == null) {
                    null
                } else {
                    knownEntities
                        ?.conversation(KnownEntityScope(accountId = account, serverId = ServerId(cleanServerId)), listOf(id))
                        ?.title
                        ?.removePrefix("#")
                        ?.takeIf { it.isNotBlank() }
                }
            }
        }
    }
}

/**
 * The lookup as composition state: it is rebuilt, and the cards re-read their names, whenever a
 * source it reads changes, so a name that arrives after the card painted replaces "Unknown".
 * The roster is the collected [members] entry; joint actors are collected from
 * [SharedJointActorDirectory.rows]; known conversations have no flow of their own, so the caller
 * passes [knownEntitiesRevision], the state it already observes for them (the Home store's content).
 */
@Composable
internal fun rememberThreadActionCardNameLookup(
    members: SharedServerMembersEntry?,
    serverId: String?,
    jointActors: SharedJointActorDirectory?,
    knownEntities: RaftKnownEntityResolver?,
    accountId: String?,
    knownEntitiesRevision: Any?,
): ThreadActionCardNameLookup {
    val jointRowsFlow: StateFlow<Map<*, *>> = remember(jointActors) { jointActors?.rows ?: MutableStateFlow<Map<*, *>>(emptyMap<Any?, Any?>()) }
    val jointRows by jointRowsFlow.collectAsState()
    return remember(members, serverId, jointActors, jointRows, knownEntities, accountId, knownEntitiesRevision) {
        threadActionCardNameLookup(members, serverId, jointActors, knownEntities, accountId)
    }
}

private val ThreadActionCardUuidPattern =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/** True for a UUID literal (shared `looksLikeUuid`): an id, never something to show. */
internal fun looksLikeActionCardUuid(value: String): Boolean = ThreadActionCardUuidPattern.matches(value.trim())

/**
 * The name to show for [raw]: the lookup's name; otherwise [unknown] for an id, never the UUID
 * itself. A value that isn't an id (an older card that stored a handle) is shown as it is.
 */
internal fun ThreadActionCardNameLookup.displayName(
    kind: ThreadActionCardEntityKind,
    raw: String,
    unknown: String,
): String {
    val value = raw.trim()
    return name(kind, value) ?: if (looksLikeActionCardUuid(value)) unknown else value
}
