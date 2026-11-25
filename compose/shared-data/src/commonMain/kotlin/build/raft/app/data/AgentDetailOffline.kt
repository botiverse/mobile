package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.model.MemberId
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.serverPermissionsForRole
import kotlinx.serialization.Serializable

/**
 * [RaftKeyedSnapshotDurable] on an offline resource (artin: everything fetched is stored locally, via the
 * shared offline layer): the entity id is the resource's params. The cache ticket's [epoch] is the offline
 * cache's clock, so a write is fenced from when its request started (wipes and confirmed changes after it
 * win); [confirm] is a confirmed mutation ([RaftOfflineResource.update]).
 *
 * [evictServer] / [clearAll] do nothing here: lost access to a server wipes every offline resource of it
 * (AccountLocalData.onServerAccessLost), and sign-out keeps the rows (keyed by account).
 */
@RaftCoreInternalApi class RaftOfflineKeyedSnapshotDurable<D : Any>(
    private val resource: RaftOfflineResource<String, D>,
) : RaftKeyedSnapshotDurable<D> {
    override val epoch: Long
        get() = resource.fetchStarted()

    override suspend fun read(key: RaftDurableSnapshotKey): D? = offlineKey(key, mintedAt = null)?.let { resource.read(it) }

    override suspend fun write(key: RaftDurableSnapshotKey, value: D, expectedEpoch: Long): Boolean {
        val offlineKey = offlineKey(key, mintedAt = expectedEpoch) ?: return false
        return resource.write(offlineKey, value)
    }

    override suspend fun confirm(key: RaftDurableSnapshotKey, value: D) {
        val offlineKey = offlineKey(key, mintedAt = null) ?: return
        // Stamped as a confirmed change (older in-flight answers are superseded) ...
        if (resource.update(offlineKey) { value }) return
        // ... and, with nothing stored yet, stored: the stamp is older than this write, a wipe since the
        // first key was minted still fences it (that key's instant).
        resource.write(offlineKey, value, startedAt = resource.fetchStarted())
    }

    override suspend fun evict(key: RaftDurableSnapshotKey) {
        offlineKey(key, mintedAt = null)?.let { resource.remove(it) }
    }

    override suspend fun evictServer(serverId: String, accountId: String?) = Unit

    override suspend fun clearAll() = Unit

    // Under the offline cache's current environment only: another environment's row is never mixed in.
    private fun offlineKey(key: RaftDurableSnapshotKey, mintedAt: Long?): RaftOfflineResourceKey<String>? {
        val entity = key.entityId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return resource.key(key.accountId, key.serverId, entity, mintedAt)
            ?.takeIf { it.environmentId == key.environmentId.trim().trimEnd('/') }
    }
}

/**
 * The agent detail page as stored offline: an explicit WHITELIST of display fields, all of them declared
 * here (never a copy of the snapshot or of a domain type). Never stored: environment variable values (names
 * only), the runtime provider (it carries the provider API key), app login client keys / return / manifest
 * URLs, the activity log and the live activity text (`activity` / `activityDetail`: free-form agent output,
 * e.g. its thinking), the stuck-tool / crash-loop banners (tool and exit details, device-clock times), DM
 * message previews, and error / permission payloads. A field added to the snapshot does not reach disk
 * until it is added here on purpose.
 */
@Serializable
@RaftCoreInternalApi data class StoredAgentDetail(
    val serverId: String,
    val serverName: String,
    val currentUserId: String? = null,
    /**
     * The viewer's own role on the server when the page was stored (`owner` / `admin` / `member` /
     * `guest`), so a cold or offline open paints the same tabs as the live page: an owner or admin
     * sees an agent's private tabs from the first frame (#proj-mobile:#236). Null in records stored
     * before this field: those paint as before until the refresh rewrites them.
     */
    val viewerRole: String? = null,
    val agentId: String,
    val summary: StoredAgentSummary? = null,
    val directMessages: List<StoredAgentDm> = emptyList(),
    val channels: List<StoredAgentChannel> = emptyList(),
    val reminders: List<StoredAgentReminder> = emptyList(),
    val skills: List<StoredAgentSkillGroup> = emptyList(),
    val appLogins: List<StoredAgentAppLogin>? = null,
    val externalStatus: StoredExternalAgentStatus? = null,
)

@Serializable
@RaftCoreInternalApi data class StoredAgentSummary(
    val id: String,
    val name: String,
    val displayName: String? = null,
    val description: String? = null,
    val serverRole: String? = null,
    val status: String? = null,
    val runtime: String? = null,
    val isExternal: Boolean = false,
    val model: String? = null,
    val runtimeModelKind: String? = null,
    val reasoning: String? = null,
    val runtimeMode: String? = null,
    val executionMode: String? = null,
    val machineId: String? = null,
    val machineName: String? = null,
    val daemonVersion: String? = null,
    val avatarUrl: String? = null,
    val creatorId: String? = null,
    val creatorType: String? = null,
    val creator: StoredAgentCreator? = null,
    val createdAt: String? = null,
    val envVarNames: List<String> = emptyList(),
    val createdAgents: List<StoredAgentCreated> = emptyList(),
    val deletedAt: String? = null,
    val lastSeenAt: String? = null,
    val hostedRuntime: AgentHostedRuntimeSnapshot? = null,
    val managedByAppInstallId: String? = null,
    val managedByAppClientId: String? = null,
    val managedByAppName: String? = null,
    val managedByAppLogoUrl: String? = null,
    val managedByAppBotStatusText: String? = null,
    val profileProjection: String? = null
)

@Serializable
@RaftCoreInternalApi data class StoredAgentCreator(
    val type: String,
    val id: String,
    val name: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
)

@Serializable
@RaftCoreInternalApi data class StoredAgentCreated(
    val id: String,
    val name: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val runtime: String? = null,
    val status: String? = null,
    val isExternal: Boolean,
)

@Serializable
@RaftCoreInternalApi data class StoredAgentDm(
    val id: String,
    val peerId: String? = null,
    val peerName: String,
    val peerAvatarUrl: String? = null,
    val lastMessageAt: String? = null,
)

@Serializable
@RaftCoreInternalApi data class StoredAgentChannel(
    val id: String,
    val name: String,
    val description: String? = null,
    val type: String = "channel",
    val createdAt: String? = null,
    val archivedAt: String? = null,
    val activityMuted: Boolean = false,
    val muteFromSeq: Long? = null,
    val loungeAgentId: String? = null,
)

@Serializable
@RaftCoreInternalApi data class StoredAgentReminder(
    val id: String,
    val title: String,
    val fireAt: String? = null,
    val recurrenceDescription: String? = null,
    val messageRef: String? = null,
    val messagePermalink: String? = null,
    val ownerAgentId: String? = null,
    val status: String? = null,
)

@Serializable
@RaftCoreInternalApi data class StoredAgentSkillGroup(
    val title: String,
    val count: Int = 0,
    val paths: List<StoredAgentSkillPath> = emptyList(),
)

@Serializable
@RaftCoreInternalApi data class StoredAgentSkillPath(
    val path: String,
    val count: Int = 0,
    val skills: List<StoredAgentSkill> = emptyList(),
)

@Serializable
@RaftCoreInternalApi data class StoredAgentSkill(val name: String, val detail: String? = null, val command: String? = null)

@Serializable
@RaftCoreInternalApi data class StoredAgentAppLogin(
    val id: String,
    val type: AgentAppLoginType,
    val clientId: String,
    val clientName: String,
    val clientDescription: String? = null,
    val clientHomepageUrl: String? = null,
    val scopes: List<String> = emptyList(),
    val remember: Boolean = false,
    val createdAt: String? = null,
    val resolvedAt: String? = null,
    val resolvedByUserId: String? = null,
    val revokedAt: String? = null,
)

@Serializable
@RaftCoreInternalApi data class StoredExternalAgentStatus(
    val setupState: ExternalAgentSetupState,
    val credentialLastUsedAt: String? = null,
    val lastActivityAt: String? = null,
)

@RaftCoreInternalApi val AgentDetailResourceSpec: RaftOfflineResourceSpec<String, StoredAgentDetail> =
    RaftOfflineResourceSpec(
        name = "agent_detail",
        version = 1,
        serializer = StoredAgentDetail.serializer(),
        paramsId = { agentId -> agentId },
        maxEntriesPerAccount = 64,
        maxBytesPerEntry = 256 * 1024,
    )

/** The whitelisted record of [this] cached detail (see [StoredAgentDetail] for what never leaves memory). */
@RaftCoreInternalApi fun RaftCachedAgentDetail.toStoredAgentDetail(): StoredAgentDetail =
    StoredAgentDetail(
        serverId = server.id.value,
        serverName = server.name,
        currentUserId = currentUserId,
        viewerRole = server.role,
        agentId = detail.agentId.value,
        summary = detail.summary?.toStored(),
        directMessages =
            detail.directMessages.map {
                StoredAgentDm(id = it.id, peerId = it.peerId, peerName = it.peerName, peerAvatarUrl = it.peerAvatarUrl, lastMessageAt = it.lastMessageAt)
            },
        channels =
            detail.channels.map {
                StoredAgentChannel(it.id, it.name, it.description, it.type, it.createdAt, it.archivedAt, it.activityMuted, it.muteFromSeq, it.loungeAgentId)
            },
        reminders =
            detail.reminders.map {
                StoredAgentReminder(it.id, it.title, it.fireAt, it.recurrenceDescription, it.messageRef, it.messagePermalink, it.ownerAgentId, it.status)
            },
        skills =
            detail.skills.groups.map { group ->
                StoredAgentSkillGroup(
                    title = group.title,
                    count = group.count,
                    paths =
                        group.paths.map { path ->
                            StoredAgentSkillPath(path.path, path.count, path.skills.map { StoredAgentSkill(it.name, it.detail, it.command) })
                        },
                )
            },
        // Only a loaded list (an error / permission payload is not stored).
        appLogins =
            detail.appLogins
                .takeIf { it.loadState == AgentAppLoginLoadState.Content || it.loadState == AgentAppLoginLoadState.Empty }
                ?.items
                ?.map {
                    StoredAgentAppLogin(
                        id = it.id,
                        type = it.type,
                        clientId = it.clientId,
                        clientName = it.clientName,
                        clientDescription = it.clientDescription,
                        clientHomepageUrl = it.clientHomepageUrl,
                        scopes = it.scopes,
                        remember = it.remember,
                        createdAt = it.createdAt,
                        resolvedAt = it.resolvedAt,
                        resolvedByUserId = it.resolvedByUserId,
                        revokedAt = it.revokedAt,
                    )
                },
        externalStatus = detail.externalStatus?.let { StoredExternalAgentStatus(it.setupState, it.credentialLastUsedAt, it.lastActivityAt) },
    )

private fun AgentDetailSummary.toStored(): StoredAgentSummary =
    StoredAgentSummary(
        hostedRuntime = hostedRuntime,
        id = id,
        name = name,
        displayName = displayName,
        description = description,
        serverRole = serverRole,
        status = status,
        runtime = runtime,
        isExternal = isExternal,
        model = model,
        runtimeModelKind = runtimeModelKind,
        reasoning = reasoning,
        runtimeMode = runtimeMode,
        executionMode = executionMode,
        machineId = machineId,
        machineName = machineName,
        daemonVersion = daemonVersion,
        avatarUrl = avatarUrl,
        creatorId = creatorId,
        creatorType = creatorType,
        creator = creator?.let { StoredAgentCreator(it.type, it.id, it.name, it.displayName, it.avatarUrl) },
        createdAt = createdAt,
        // Names only: the values are secrets.
        envVarNames = (envVarNames + envVars.keys).distinct(),
        createdAgents =
            createdAgents.map { StoredAgentCreated(it.id, it.name, it.displayName, it.avatarUrl, it.runtime, it.status, it.isExternal) },
        deletedAt = deletedAt,
        lastSeenAt = lastSeenAt,
        managedByAppInstallId = managedByAppInstallId,
        managedByAppClientId = managedByAppClientId,
        managedByAppName = managedByAppName,
        managedByAppLogoUrl = managedByAppLogoUrl,
        managedByAppBotStatusText = managedByAppBotStatusText,
        profileProjection = profileProjection,
    )

/** The stored record as a cached detail (a read-only stand-in: the page grants no edits from the cache). */
@RaftCoreInternalApi fun StoredAgentDetail.toCachedAgentDetail(): RaftCachedAgentDetail {
    val agent = MemberId(agentId)
    return RaftCachedAgentDetail(
        server =
            ServerSummary(
                id = ServerId(serverId),
                name = serverName,
                role = viewerRole,
                permissions = serverPermissionsForRole(viewerRole)
            ),
        currentUserId = currentUserId,
        detail =
            AgentDetailSnapshot(
                agentId = agent,
                summary =
                    summary?.let {
                        AgentDetailSummary(
                            hostedRuntime = it.hostedRuntime,
                            id = it.id,
                            name = it.name,
                            displayName = it.displayName,
                            description = it.description,
                            serverRole = it.serverRole,
                            status = it.status,
                            runtime = it.runtime,
                            isExternal = it.isExternal,
                            model = it.model,
                            runtimeModelKind = it.runtimeModelKind,
                            reasoning = it.reasoning,
                            runtimeMode = it.runtimeMode,
                            executionMode = it.executionMode,
                            machineId = it.machineId,
                            machineName = it.machineName,
                            daemonVersion = it.daemonVersion,
                            avatarUrl = it.avatarUrl,
                            creatorId = it.creatorId,
                            creatorType = it.creatorType,
                            creator = it.creator?.let { c -> AgentCreatorSummary(c.type, c.id, c.name, c.displayName, c.avatarUrl) },
                            createdAt = it.createdAt,
                            envVarNames = it.envVarNames,
                            createdAgents =
                                it.createdAgents.map { c ->
                                    AgentCreatedSummary(c.id, c.name, c.displayName, c.avatarUrl, c.runtime, c.status, c.isExternal)
                                },
                            deletedAt = it.deletedAt,
                            lastSeenAt = it.lastSeenAt,
                            managedByAppInstallId = it.managedByAppInstallId,
                            managedByAppClientId = it.managedByAppClientId,
                            managedByAppName = it.managedByAppName,
                            managedByAppLogoUrl = it.managedByAppLogoUrl,
                            managedByAppBotStatusText = it.managedByAppBotStatusText,
                            profileProjection = it.profileProjection,
                        )
                    },
                directMessages =
                    directMessages.map { AgentDmSummary(id = it.id, peerId = it.peerId, peerName = it.peerName, peerAvatarUrl = it.peerAvatarUrl, lastMessageAt = it.lastMessageAt) },
                channels =
                    channels.map {
                        AgentChannelSummary(it.id, it.name, it.description, it.type, it.createdAt, it.archivedAt, it.activityMuted, it.muteFromSeq, it.loungeAgentId)
                    },
                reminders =
                    reminders.map {
                        AgentReminderSummary(it.id, it.title, it.fireAt, it.recurrenceDescription, it.messageRef, it.messagePermalink, it.ownerAgentId, it.status)
                    },
                activity = emptyList(),
                skills =
                    AgentSkillsSnapshot(
                        agentId = agent,
                        groups =
                            skills.map { group ->
                                AgentSkillGroup(
                                    title = group.title,
                                    count = group.count,
                                    paths =
                                        group.paths.map { path ->
                                            AgentSkillPathGroup(path.path, path.count, path.skills.map { AgentSkillSummary(it.name, it.detail, it.command) })
                                        },
                                )
                            },
                    ),
                appLogins =
                    appLogins?.let { items ->
                        AgentAppLoginSnapshot(
                            agentId = agent,
                            items =
                                items.map {
                                    AgentAppLoginItem(
                                        id = it.id,
                                        type = it.type,
                                        clientId = it.clientId,
                                        clientName = it.clientName,
                                        clientDescription = it.clientDescription,
                                        clientHomepageUrl = it.clientHomepageUrl,
                                        scopes = it.scopes,
                                        remember = it.remember,
                                        createdAt = it.createdAt,
                                        resolvedAt = it.resolvedAt,
                                        resolvedByUserId = it.resolvedByUserId,
                                        revokedAt = it.revokedAt,
                                    )
                                },
                            loadState = if (items.isEmpty()) AgentAppLoginLoadState.Empty else AgentAppLoginLoadState.Content,
                        )
                    } ?: AgentAppLoginSnapshot(agentId = agent),
                externalStatus = externalStatus?.let { ExternalAgentStatusSnapshot(it.setupState, it.credentialLastUsedAt, it.lastActivityAt) },
            ),
    )
}
