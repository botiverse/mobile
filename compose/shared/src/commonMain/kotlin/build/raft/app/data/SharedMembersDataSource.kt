package build.raft.app.data

import build.raft.app.model.MemberKind

/**
 * KMP-side data source for the Members page **humans section** flows.
 *
 * Per @artin 2026-06-17 #members专修 msg=f1db7897 ("应该抽象一个统一的"),
 * the create-agent flow was extracted to its own
 * [SharedCreateAgentCoordinator]. This data source now owns the
 * members-side surface that is **not** the create-agent flow:
 *   - `loadServerMembers`: GET /servers/{id}/members for the humans "+"
 *     picker (candidates minus current channel members).
 *   - `addChannelMember`: POST /channels/{id}/members with the kind-keyed
 *     body (`{agentId}` or `{userId}`) for the humans section "+"
 *     when the chosen UX is "add existing human to channel".
 *   - `loadJoinLink` / `ensureJoinLink`: GET/POST /servers/{id}/join-links
 *     for the humans "+" invite flow (task #43).
 *   - `sendInvites`: POST /servers/{id}/invites per email for the humans
 *     "+" invite flow (task #43).
 *
 * The invite-flow methods mirror the React `InviteHumanDialog.tsx` flow
 * (join link + emails + send). They use the existing
 * `SettingsRepository` API surface (`createServerJoinLink`,
 * `createServerInvite`) which already supports permission gating
 * (`canManageServer`) and structured error codes.
 */
internal class SharedMembersDataSource(
    private val memberRepository: MemberRepository,
    private val settingsRepository: SettingsRepository,
    private val serverMembersStore: SharedServerMembersStore? = null,
    /** Join-link revoke (`DELETE /servers/{id}/join-links/{linkId}`) for the invite dialog. */
    private val settingsActionRepository: SettingsServerActionRepository? =
        settingsRepository as? SettingsServerActionRepository
) {
    suspend fun loadServerMembers(
        serverId: String
    ): DataResult<List<build.raft.app.model.MemberProfile>> {
        val server = build.raft.app.model.ServerId(serverId)
        val store = serverMembersStore
        if (store != null) {
            return when (val result = store.refreshMembers(server, DataRequestPriority.Foreground)) {
                is DataResult.Content -> DataResult.Content(store.currentServerMembers(server))
                is DataResult.Empty -> DataResult.Empty(result.reason)
                is DataResult.Error -> result
                is DataResult.NoPermission -> result
            }
        }
        return when (val result = memberRepository.getMembers(DataRequestPriority.Foreground, server)) {
            is DataResult.Content -> DataResult.Content(result.data.members)
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    suspend fun addChannelMember(
        channelId: String,
        memberId: String,
        kind: MemberKind,
        serverId: String? = null
    ): DataResult<Unit> {
        val request =
            AddChannelMemberRequest(
                channelId = build.raft.app.model.ChannelId(channelId),
                memberId = build.raft.app.model.MemberId(memberId),
                kind = kind
            )
        val result =
            serverId
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { cleanServerId ->
                    serverMembersStore?.addChannelMember(
                        serverId = build.raft.app.model.ServerId(cleanServerId),
                        request = request,
                        priority = DataRequestPriority.Foreground
                    )
                }
                ?: memberRepository.addChannelMember(DataRequestPriority.Foreground, request)
        return when (result) {
            is DataResult.Content -> DataResult.Content(Unit)
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    /**
     * Atomic batch add (task #352): one request for mixed humans + agents. The
     * server dedupes and validates the whole batch (rollback on a later failure),
     * so there is no partial success and no per-member fallback here.
     */
    suspend fun addChannelMembersBatch(
        channelId: String,
        userIds: List<String>,
        agentIds: List<String>,
        serverId: String? = null
    ): DataResult<AddChannelMembersBatchResult> {
        val request =
            AddChannelMembersBatchRequest(
                channelId = build.raft.app.model.ChannelId(channelId),
                userIds = userIds.map { build.raft.app.model.MemberId(it) },
                agentIds = agentIds.map { build.raft.app.model.MemberId(it) }
            )
        val result =
            serverId
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { cleanServerId ->
                    serverMembersStore?.addChannelMembersBatch(
                        serverId = build.raft.app.model.ServerId(cleanServerId),
                        request = request,
                        priority = DataRequestPriority.Foreground
                    )
                }
                ?: memberRepository.addChannelMembersBatch(DataRequestPriority.Foreground, request)
        return result
    }

    /**
     * List existing server join links. The first one is the canonical
     * "anyone with this link can join" link that the React
     * `InviteHumanDialog` shows.
     *
     * Returns the list (possibly empty) wrapped in [DataResult]. Callers
     * are expected to fall back to [ensureJoinLink] when the list is
     * empty, matching the React flow.
     */
    suspend fun loadJoinLinks(serverId: String): DataResult<List<SettingsServerJoinLinkSnapshot>> {
        return when (val result = settingsRepository.listServerJoinLinks(serverId)) {
            is DataResult.Content -> DataResult.Content(result.data)
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    /**
     * Create a server join link with no expiry / no max-uses. Returns
     * the new link snapshot with its URL populated.
     */
    suspend fun ensureJoinLink(serverId: String): DataResult<SettingsServerJoinLinkSnapshot> =
        createJoinLink(serverId, maxUses = null, expiresAt = null)

    /** Create a join link with the invite dialog's usage cap / expiry (null = unlimited / never). */
    suspend fun createJoinLink(
        serverId: String,
        maxUses: Int?,
        expiresAt: String?
    ): DataResult<SettingsServerJoinLinkSnapshot> =
        settingsRepository.createServerJoinLinkForInvite(
            SettingsServerJoinLinkCreateRequest(expiresAt = expiresAt, maxUses = maxUses)
        )

    /** Revoke a join link: its token stops working (web InviteHumanDialog "Update" / "Revoke"). */
    suspend fun revokeJoinLink(serverId: String, linkId: String): DataResult<SettingsServerActionResult> =
        settingsActionRepository?.revokeServerJoinLink(linkId)
            ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

    /**
     * Send an invite email to one address. Returns success on 2xx,
     * NoPermission if the active session can't manage invites.
     */
    suspend fun sendInvite(
        serverId: String,
        email: String,
        role: String? = null
    ): DataResult<SettingsServerActionResult> {
        return settingsRepository.createServerInvite(
            SettingsServerInviteCreateRequest(email = email, role = role)
        )
    }
}
