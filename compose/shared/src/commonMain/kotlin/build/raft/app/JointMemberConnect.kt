package build.raft.app

import build.raft.app.data.JointChannelConnections
import build.raft.app.data.JointChannelRole
import build.raft.app.data.JointChannelServer
import build.raft.app.data.ServerConnectOutcome
import build.raft.app.data.StoredAuthSession

/**
 * Connect your servers (#wg-rbac:#36, web `packages/web/src/components/channel/memberConnect.ts`,
 * botiverse/slock staging 44ce160f1495): a channel member connects a server they own or
 * administer, no host approval. There is no list endpoint for a member, so the servers offered
 * come from what the server already sent: the account's servers with the role on each
 * (`GET /api/servers`, the stored session) and the channel's connected servers (`jointServers`),
 * by the server's own rule (listConnectableServers: not the host, not connected, no community
 * server, owner or admin to connect it). The route checks it all again and answers each server.
 */

/** A server of the signed-in account, as `GET /api/servers` sent it. */
internal data class MemberConnectServer(
    val id: String,
    val name: String,
    val slug: String,
    val avatarUrl: String?,
    /** `owner | admin | member | guest`. */
    val role: String?,
)

/** The account side of Connect your servers: the current server and every server it belongs to. */
internal data class MemberConnectAccount(
    val currentServerId: String,
    val currentServerName: String,
    val currentServerSlug: String,
    val currentServerRole: String?,
    val servers: List<MemberConnectServer>,
)

internal fun StoredAuthSession.memberConnectAccount(currentServerId: String): MemberConnectAccount? {
    val current = servers.firstOrNull { it.id == currentServerId } ?: return null
    return MemberConnectAccount(
        currentServerId = current.id,
        currentServerName = current.name,
        currentServerSlug = current.slug.orEmpty(),
        currentServerRole = current.role,
        servers =
            servers.map { server ->
                MemberConnectServer(server.id, server.name, server.slug.orEmpty(), server.avatarUrl, server.role)
            },
    )
}

/** Why a server of the viewer's is listed but can't be chosen (web `MemberConnectRowState`). */
internal enum class MemberConnectRowState { Eligible, Connected, NotAdmin }

internal data class MemberConnectRow(val server: MemberConnectServer, val state: MemberConnectRowState)

/** Web `DEFAULT_COMMUNITY_SERVER_SLUG` / `CHINESE_COMMUNITY_SERVER_SLUG` (`utils/communityServers.ts`). */
internal val MEMBER_CONNECT_COMMUNITY_SLUGS = setOf("community", "community-cn")

/** The channel as the rule reads it: its kind, name, archive and the caller's membership, its servers. */
internal data class MemberConnectChannel(
    val isJoint: Boolean,
    val isPrivate: Boolean,
    val name: String,
    val archived: Boolean,
    val joined: Boolean?,
    val servers: List<JointChannelServer>,
)

internal fun JointChannelConnections.memberConnectChannel(): MemberConnectChannel =
    MemberConnectChannel(isJoint, isPrivate, channelName, archived, joined, servers)

/** Web `isMemberConnectChannel`: a private channel (never #all) or a live joint one, not archived, the viewer in it. */
internal fun MemberConnectChannel.takesMemberServers(): Boolean {
    if (archived || joined != true) return false
    if (isJoint) return servers.any { it.active }
    return isPrivate && !name.removePrefix("#").equals("all", ignoreCase = true)
}

/** Web `memberConnectHostServerId`: the joint's host row, else (a private channel) the current server. */
internal fun MemberConnectChannel.memberConnectHostServerId(currentServerId: String): String =
    if (!isJoint) currentServerId else servers.firstOrNull { it.role == JointChannelRole.Host }?.serverId ?: currentServerId

/**
 * Web `memberConnectRows`: the viewer's servers the sheet lists, the ones they can choose first,
 * then those in the channel already, then those they only belong to. Never the host, a server
 * they are a Guest of, or a community server. Empty when the channel takes none.
 */
internal fun memberConnectRows(channel: MemberConnectChannel?, account: MemberConnectAccount?): List<MemberConnectRow> {
    if (channel == null || account == null || !channel.takesMemberServers()) return emptyList()
    val hostServerId = channel.memberConnectHostServerId(account.currentServerId)
    val connected = channel.servers.filter { it.active }.map { it.serverId }.toMutableSet()
    if (!channel.isJoint) connected += account.currentServerId
    return account.servers
        .filter { it.id != hostServerId && it.role != "guest" && it.slug !in MEMBER_CONNECT_COMMUNITY_SLUGS }
        .map { server ->
            MemberConnectRow(
                server = server,
                state =
                    when {
                        server.id in connected -> MemberConnectRowState.Connected
                        server.role == "owner" || server.role == "admin" -> MemberConnectRowState.Eligible
                        else -> MemberConnectRowState.NotAdmin
                    },
            )
        }
        .sortedWith(compareBy<MemberConnectRow> { it.state.ordinal }.thenBy { it.server.name.lowercase() })
}

/** What a refusal says (each one a web message id; the page resolves it with its values). */
internal enum class MemberConnectMessage {
    /** `channel.memberConnect.error.notAdmin` {target}. */
    NotAdmin,

    /** `channel.memberConnect.error.alreadyConnected` {target}. */
    AlreadyConnected,

    /** `channel.memberConnect.error.membershipChanged` {channel}. */
    MembershipChanged,

    /** `channel.memberConnect.error.notAllowed` {channel}. */
    NotAllowed,

    /** `channel.memberConnect.error.guestHasComputer` {host}. */
    GuestHasComputer,

    /** `externalInvite.error.serverMembershipRequired` {target}. */
    ServerMembershipRequired,

    /** `externalInvite.error.invalidServer` {target} {channel}. */
    InvalidServer,

    /** `externalInvite.error.archived` {channel}. */
    Archived,

    /** `externalInvite.error.tooManyAttempts`. */
    TooManyAttempts,

    /** `serverConnectRequest.error.channelFull` {channel}. */
    ChannelFull,

    /** `serverConnectRequest.error.targetFreeLimit` {target}. */
    TargetFreeLimit,

    /** `serverConnectRequest.error.hostLimit` {host}. */
    HostLimit,

    /** `serverConnectRequest.error.jointFreeServerLimit` {channel} {target}. */
    JointFreeServerLimit,

    /** `serverConnectRequest.error.guestsPresent` {channel}. */
    GuestsPresent,

    /** `serverConnectRequest.error.jointPartyLimit` {channel} {max} {host}. */
    JointPartyLimit,

    /** `serverConnectRequest.error.connectInProgress` {channel}. */
    ConnectInProgress,

    /** `serverConnectRequest.error.busy` {channel}. */
    Busy,

    /** `serverConnectRequest.error.generic`. */
    Generic,
}

/** Web `MEMBER_REFUSAL_IDS`: this flow's own words where the actor is the one connecting. */
private val MEMBER_REFUSAL_MESSAGES =
    mapOf(
        "server_admin_required" to MemberConnectMessage.NotAdmin,
        "already_connected" to MemberConnectMessage.AlreadyConnected,
        "membership_changed" to MemberConnectMessage.MembershipChanged,
        "server_membership_required" to MemberConnectMessage.ServerMembershipRequired,
        "invalid_server" to MemberConnectMessage.InvalidServer,
        "channel_archived" to MemberConnectMessage.Archived,
        "channel_full" to MemberConnectMessage.ChannelFull,
        "joint_server_limit" to MemberConnectMessage.ChannelFull,
    )

/** Web `APPROVER_ERROR_IDS` (`externalInviteAccept.ts`): the approver's words for the rest. */
private val APPROVER_REFUSAL_MESSAGES =
    mapOf(
        "guests_present" to MemberConnectMessage.GuestsPresent,
        "joint_party_limit" to MemberConnectMessage.JointPartyLimit,
        "connect_in_progress" to MemberConnectMessage.ConnectInProgress,
        "channel_full" to MemberConnectMessage.ChannelFull,
        "joint_server_limit" to MemberConnectMessage.ChannelFull,
        "joint_channel_free_limit_reached" to MemberConnectMessage.HostLimit,
        "joint_channel_plan_limit_reached" to MemberConnectMessage.HostLimit,
        "channel_conversion_in_progress" to MemberConnectMessage.Busy,
        "channel_conversion_fence_conflict" to MemberConnectMessage.Busy,
        "channel_conversion_ledger_conflict" to MemberConnectMessage.Busy,
    )

/**
 * Web `limitOwner` for the viewer's own server (no plan known here): theirs to upgrade unless the
 * answer names the host's limit or another server.
 */
private fun ServerConnectOutcome.Refused.limitIsMine(): Boolean {
    val side = limitSide?.takeIf { it == "host" || it == "connecting" } ?: return true
    if (side == "connecting" && limitServerId == null) return false
    return limitServerId == serverId
}

/** Web `memberConnectRefusalMessageId`: one chosen server's refusal. */
internal fun memberConnectRefusalMessage(outcome: ServerConnectOutcome.Refused): MemberConnectMessage {
    val code = outcome.code
    code?.let { MEMBER_REFUSAL_MESSAGES[it] }?.let { return it }
    // Web approverErrorMessage(refusalAsError(outcome), { id: outcome.serverId }).
    val hasLimit = outcome.limitSide == "host" || outcome.limitSide == "connecting"
    return when {
        code == "free_external_limit" ->
            if (outcome.limitIsMine()) MemberConnectMessage.TargetFreeLimit else MemberConnectMessage.HostLimit
        code == "joint_free_server_limit" ->
            if (outcome.limitIsMine() && hasLimit) MemberConnectMessage.JointFreeServerLimit else MemberConnectMessage.HostLimit
        code != null && code in APPROVER_REFUSAL_MESSAGES -> APPROVER_REFUSAL_MESSAGES.getValue(code)
        outcome.status == 429 -> MemberConnectMessage.TooManyAttempts
        else -> MemberConnectMessage.Generic
    }
}

/** Web `memberConnectRefusalRowState`: after a refusal, the row is chosen again only where trying again could help. */
internal fun memberConnectRefusalRowState(outcome: ServerConnectOutcome.Refused): MemberConnectRowState? =
    when (outcome.code) {
        "already_connected" -> MemberConnectRowState.Connected
        "server_admin_required" -> MemberConnectRowState.NotAdmin
        else -> null
    }

/** A whole request refused (web `memberConnectRequestError`); [closed]: the route is gone for this viewer. */
internal data class MemberConnectRequestRefusal(val message: MemberConnectMessage, val closed: Boolean)

/** By the server's code, else by status. Null [status]: no answer (network). */
internal fun memberConnectRequestRefusal(status: Int?, code: String?): MemberConnectRequestRefusal =
    when {
        code == "guest_has_computer" -> MemberConnectRequestRefusal(MemberConnectMessage.GuestHasComputer, closed = true)
        code == "channel_not_invitable" -> MemberConnectRequestRefusal(MemberConnectMessage.NotAllowed, closed = true)
        code == "invalid_input" -> MemberConnectRequestRefusal(MemberConnectMessage.Generic, closed = false)
        status == 404 || status == 403 -> MemberConnectRequestRefusal(MemberConnectMessage.NotAllowed, closed = true)
        status == 429 -> MemberConnectRequestRefusal(MemberConnectMessage.TooManyAttempts, closed = false)
        else -> MemberConnectRequestRefusal(MemberConnectMessage.Generic, closed = false)
    }

/** One line of the sheet's warning banner. */
internal sealed interface MemberConnectLine {
    /** Web `channel.memberConnect.partial.connected`: these did go through. */
    data class Connected(val serverIds: List<String>) : MemberConnectLine

    /** A chosen server's refusal; [serverId] is its {target}. */
    data class Refused(val serverId: String, val message: MemberConnectMessage) : MemberConnectLine

    /** The whole request refused. */
    data class Request(val message: MemberConnectMessage) : MemberConnectLine
}

/** The open Connect your servers sheet (web ConnectYourServersDialog's state). */
internal data class MemberConnectSheet(
    /** Chosen servers, in the order chosen. */
    val selected: Set<String> = emptySet(),
    val busy: Boolean = false,
    /** Servers this sheet connected (or started connecting): "Connected just now", Cancel reads Close. */
    val done: Set<String> = emptySet(),
    /** A row's state after a refusal changed it (no longer an admin, already connected). */
    val rowStates: Map<String, MemberConnectRowState> = emptyMap(),
    val lines: List<MemberConnectLine> = emptyList(),
    /** The trace ID of the answer the lines come from. */
    val traceId: String? = null,
    /** The route refused the viewer as a whole: nothing more can be chosen. */
    val closed: Boolean = false,
)

/**
 * Who connected a row, while the app runs (web `markConnectedByYou` / `useConnectedByYou`): the
 * channel's server list carries no "connected by", so a server the viewer just connected says so
 * on its row until the process ends. Keyed by account + server + channel.
 */
internal class MemberConnectedByYouMemory {
    private val byKey = mutableMapOf<String, Set<String>>()

    fun get(key: String): Set<String> = byKey[key].orEmpty()

    fun add(key: String, serverIds: Collection<String>): Set<String> {
        if (serverIds.isEmpty()) return get(key)
        val next = get(key) + serverIds
        byKey[key] = next
        return next
    }
}

internal val RaftMemberConnectedByYou = MemberConnectedByYouMemory()

internal class MemberConnectedByYouSlot(val memory: MemberConnectedByYouMemory, val key: String)
