package build.raft.app.data

import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Typed boundary for `POST /api/actors/resolve`: batched display identity (name, avatar, home
 * server) for people and agents, used for the senders of a joint channel who live on the other
 * server and so are in neither of this server's rosters.
 *
 * Server contract (botiverse/slock 200dbbf09, #11193):
 *  - `packages/shared/src/actorResolveContract.ts`: request `{ actors: [{ kind: "human" | "agent",
 *    id: uuid }] }` (1..200 refs, strict; one malformed id rejects the whole batch with 400);
 *    response `{ actors: [{ kind, id, homeServerId, name, displayName, avatarUrl, gravatarHash }] }`.
 *  - `packages/server/src/routes/actors.ts`: scoped by `X-Server-Id`; 403 for guests. An actor
 *    that is unknown or not visible to the caller is simply absent from the answer.
 *  - A server without the route (production before the release) answers 404.
 *
 * Field names are exact camelCase. No snake_case fallbacks.
 */
internal const val ACTOR_RESOLVE_MAX_ACTORS = 200

private val actorResolveJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = true
}

private val actorResolveUuid =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/** The server validates every ref id as a UUID: anything else would fail the whole batch. */
internal fun isActorResolveId(id: String): Boolean = actorResolveUuid.matches(id)

/** One actor to resolve. [kind] is [MemberKind.Human] or [MemberKind.Agent]. */
data class ActorResolveRef(
    val kind: MemberKind,
    val id: String,
) {
    val key: String get() = "${kind.actorResolveWireKind()}:$id"
}

/** A display identity the server answered for. */
data class ResolvedActor(
    val kind: MemberKind,
    val id: String,
    val homeServerId: String,
    val name: String,
    val displayName: String?,
    val avatarUrl: String?,
    /** Only for a person of the server that was asked; null for everyone else. */
    val gravatarHash: String?,
)

@Serializable
private data class WireActorResolveRef(
    val kind: String,
    val id: String,
)

@Serializable
private data class WireActorResolveRequest(
    val actors: List<WireActorResolveRef>,
)

@Serializable
private data class WireResolvedActor(
    val kind: String,
    val id: String,
    val homeServerId: String,
    val name: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val gravatarHash: String? = null,
)

private fun MemberKind.actorResolveWireKind(): String =
    if (this == MemberKind.Agent) "agent" else "human"

private fun String.actorResolveKindOrNull(): MemberKind? =
    when (this) {
        "human" -> MemberKind.Human
        "agent" -> MemberKind.Agent
        else -> null
    }

internal fun buildSharedActorResolveBody(refs: List<ActorResolveRef>): String =
    actorResolveJson.encodeToString(
        WireActorResolveRequest.serializer(),
        WireActorResolveRequest(refs.map { WireActorResolveRef(kind = it.kind.actorResolveWireKind(), id = it.id) })
    )

/**
 * Parses the answer for [requestedServerId]. Each entry is checked on its own: a malformed one is
 * dropped, the rest are kept. A `gravatarHash` is kept only for a person whose home is the server
 * that was asked (it is email-derived; other servers' people belong to another organization).
 * Returns null when the body is not a `{ actors: [...] }` object.
 */
fun parseSharedActorResolveResponse(bodyText: String, requestedServerId: String): List<ResolvedActor>? {
    val root = runCatching { actorResolveJson.parseToJsonElement(bodyText) }.getOrNull() as? JsonObject ?: return null
    val actors = root["actors"] as? JsonArray ?: return null
    return actors.mapNotNull { element ->
        val wire = runCatching { actorResolveJson.decodeFromJsonElement(WireResolvedActor.serializer(), element) }
            .getOrNull() ?: return@mapNotNull null
        val kind = wire.kind.actorResolveKindOrNull() ?: return@mapNotNull null
        val id = wire.id.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val homeServerId = wire.homeServerId.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        ResolvedActor(
            kind = kind,
            id = id,
            homeServerId = homeServerId,
            name = wire.name,
            displayName = wire.displayName?.takeIf { it.isNotBlank() },
            avatarUrl = wire.avatarUrl?.trim()?.takeIf { it.isNotBlank() },
            gravatarHash =
                wire.gravatarHash
                    ?.trim()
                    ?.takeIf { it.isNotBlank() && kind == MemberKind.Human && homeServerId == requestedServerId.trim() },
        )
    }
}

/** What one resolve call came back with. */
sealed interface ActorResolveOutcome {
    data class Resolved(val actors: List<ResolvedActor>) : ActorResolveOutcome

    /**
     * The server will not answer for this scope this session: 404/405/501 (a server without the
     * route) or 403 (a guest).
     */
    data object Unavailable : ActorResolveOutcome

    /** Any other failure; the refs count as asked and are not retried. */
    data class Failed(val code: Int) : ActorResolveOutcome
}

internal fun HomeNetworkResponse.toActorResolveOutcome(requestedServerId: String): ActorResolveOutcome =
    when {
        isSuccessful ->
            bodyText
                ?.let { parseSharedActorResolveResponse(it, requestedServerId) }
                ?.let { ActorResolveOutcome.Resolved(it) }
                ?: ActorResolveOutcome.Failed(code)
        code == 403 || code == 404 || code == 405 || code == 501 -> ActorResolveOutcome.Unavailable
        else -> ActorResolveOutcome.Failed(code)
    }

/** `POST actors/resolve` for [serverId] (sent as its `X-Server-Id`). */
internal suspend fun RaftNetworkClient.resolveActors(
    priority: DataRequestPriority,
    serverId: ServerId,
    refs: List<ActorResolveRef>,
): ActorResolveOutcome =
    request(
        priority = priority,
        path = "actors/resolve",
        method = "POST",
        serverIdOverride = serverId,
        bodyText = buildSharedActorResolveBody(refs),
    ).toActorResolveOutcome(serverId.value)
