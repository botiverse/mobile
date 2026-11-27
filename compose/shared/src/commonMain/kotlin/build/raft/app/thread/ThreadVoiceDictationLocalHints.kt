package build.raft.app.thread

import build.raft.app.data.RaftSharedStoreProvider
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeState
import build.raft.app.data.SharedServerMembersState
import build.raft.app.data.VoiceDictationLocalAgentHint
import build.raft.app.data.VoiceDictationLocalChannelHint
import build.raft.app.data.VoiceDictationLocalDmHint
import build.raft.app.data.VoiceDictationLocalHintSnapshot
import build.raft.app.data.VoiceDictationLocalMemberHint
import build.raft.app.data.collectVoiceDictationContactHintsFromSnapshot
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
import build.raft.app.model.hidesHumanDirectoryForViewer

// Synchronous, zero-network collection of local server-scoped contact, agent, and channel
// vocabulary hints for composer voice dictation (#wg-call:#10).
// Mirrors `botiverse/slock/packages/web/src/features/composer/voiceDictationLocalHints.ts`.

/**
 * Pure adapter mapping already-read in-memory [SharedHomeState] and [SharedServerMembersState]
 * into a server-scoped [VoiceDictationLocalHintSnapshot]. Returns `null` when [serverId] is blank
 * or does not match [currentServerId].
 */
internal fun buildLocalVoiceDictationHintSnapshot(
    serverId: String,
    currentServerId: String?,
    currentUserId: String?,
    activeChannelId: String? = null,
    homeState: SharedHomeState? = null,
    membersState: SharedServerMembersState? = null,
): VoiceDictationLocalHintSnapshot? {
    val cleanServerId = serverId.trim()
    val cleanCurrentServerId = currentServerId?.trim()
    if (cleanServerId.isEmpty() || cleanCurrentServerId.isNullOrEmpty() || cleanCurrentServerId != cleanServerId) {
        return null
    }
    val cleanUserId = currentUserId?.trim()?.takeIf { it.isNotEmpty() }

    val homeSnapshot =
        (homeState?.content as? SharedHomeContentState.Content)
            ?.snapshot
            ?.takeIf { it.server.id.value.trim() == cleanServerId }

    val rawMembersEntry = membersState?.entryFor(ServerId(cleanServerId))
    val membersEntry =
        rawMembersEntry?.takeIf { entry ->
            val ownerAccount = entry.ownerAccountId?.trim()?.takeIf { it.isNotEmpty() }
            entry.serverId.trim() == cleanServerId &&
                (cleanUserId == null || ownerAccount == null || ownerAccount == cleanUserId)
        }
    val profileCache = membersState?.profileCacheByServerId?.get(cleanServerId).orEmpty()

    val recentConversationIds =
        buildList {
            activeChannelId?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
            if (homeSnapshot != null) {
                homeState?.selectedConversationId
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && it !in this }
                    ?.let(::add)
            }
        }

    val dmChannels =
        homeSnapshot?.directMessages.orEmpty().map { dm ->
            val peerId = dm.presenceMemberId?.value?.trim()?.takeIf { it.isNotEmpty() }
            val peerProfile =
                peerId?.let { id -> membersEntry?.membersById?.get(id) ?: profileCache[id] }
            val rowDisplayName =
                dm.name.trim().takeIf {
                    it.isNotEmpty() && it != dm.channelId.value && it != dm.threadId.value
                }
            val peerDisplayName =
                peerProfile?.displayName?.trim()?.takeIf { it.isNotEmpty() } ?: rowDisplayName
            val peerHandle =
                peerProfile?.handle?.trim()?.takeIf { it.isNotEmpty() }
            VoiceDictationLocalDmHint(
                id = dm.channelId.value,
                serverId = homeSnapshot?.server?.id?.value,
                peerDisplayName = peerDisplayName,
                peerName = peerHandle,
                archived = dm.archived,
                lastActivityAt = dm.lastMessageAt,
            )
        }

    val channels =
        homeSnapshot?.channels.orEmpty().map { ch ->
            VoiceDictationLocalChannelHint(
                id = ch.channelId.value,
                serverId = homeSnapshot?.server?.id?.value,
                name = ch.name,
                joined = ch.joined,
                archived = ch.archived,
                isDmOrThread =
                    ch.kind == ConversationKind.DirectMessage ||
                        ch.channelType.equals("dm", ignoreCase = true) ||
                        ch.channelType.equals("thread", ignoreCase = true) ||
                        !ch.loungeAgentId.isNullOrBlank(),
                lastActivityAt = ch.lastMessageAt,
            )
        }

    val rosterMembers = membersEntry?.members.orEmpty()
    val hidesHumanDirectory = membersEntry?.server?.hidesHumanDirectoryForViewer() == true
    val agents = ArrayList<VoiceDictationLocalAgentHint>()
    val members = ArrayList<VoiceDictationLocalMemberHint>()
    for (profile in rosterMembers) {
        val profileServerId = profile.serverId?.value?.trim()?.takeIf { it.isNotEmpty() } ?: cleanServerId
        when (profile.kind) {
            MemberKind.Agent -> {
                agents.add(
                    VoiceDictationLocalAgentHint(
                        id = profile.id.value,
                        serverId = profileServerId,
                        displayName = profile.displayName,
                        name = profile.handle,
                        deleted = !profile.agentDeletedAt.isNullOrBlank() || profile.inMemberLists == false,
                        serviceAgent = profile.raftbenchService,
                    ),
                )
            }
            MemberKind.Human -> {
                if (!hidesHumanDirectory) {
                    members.add(
                        VoiceDictationLocalMemberHint(
                            userId = profile.id.value,
                            serverId = profileServerId,
                            displayName = profile.displayName,
                            name = profile.handle,
                        ),
                    )
                }
            }
            MemberKind.System, MemberKind.External -> Unit
        }
    }

    return VoiceDictationLocalHintSnapshot(
        serverId = cleanServerId,
        currentServerId = cleanCurrentServerId,
        currentUserId = cleanUserId,
        recentConversationIds = recentConversationIds,
        dmChannels = dmChannels,
        channels = channels,
        agents = agents,
        members = members,
    )
}

/**
 * Synchronously collects up to 20 normalized contact, agent, and channel vocabulary hints strictly
 * scoped to [serverId] from already-hydrated in-memory stores on [sharedStores]. Performs zero
 * network calls, zero blocking I/O, and never crosses Server boundaries.
 */
internal fun collectLocalVoiceDictationContactHints(
    sharedStores: RaftSharedStoreProvider,
    storeScope: RaftShellStoreScope,
    serverId: String?,
    currentUserId: String?,
    activeChannelId: String? = null,
): List<String>? {
    val cleanServerId = serverId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val homeStore = sharedStores.sharedHomeStore(storeScope)
    val homeState = homeStore?.state?.value
    val currentServerId =
        sharedStores.currentSelectedServerIdOrNull(storeScope)
            ?: (homeState?.content as? SharedHomeContentState.Content)
                ?.snapshot
                ?.server
                ?.id
                ?.value
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: sharedStores.authStore.readInMemorySessionSnapshot()?.session?.currentServerId?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
    if (currentServerId != cleanServerId) return null

    val membersState =
        sharedStores.existingServerMembersStoreOrNull(storeScope, ServerId(cleanServerId))?.state?.value
    val effectiveUserId =
        currentUserId?.trim()?.takeIf { it.isNotEmpty() }
            ?: sharedStores.currentInstalledAppAccountIdSync()
    val snapshot =
        buildLocalVoiceDictationHintSnapshot(
            serverId = cleanServerId,
            currentServerId = currentServerId,
            currentUserId = effectiveUserId,
            activeChannelId = activeChannelId,
            homeState = homeState,
            membersState = membersState,
        ) ?: return null
    return collectVoiceDictationContactHintsFromSnapshot(snapshot)
}
