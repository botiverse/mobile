package build.raft.app.thread

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import build.raft.app.RaftComposerServerUploads
import build.raft.app.ReplyCacheProvenance
import build.raft.app.ThreadAttachmentUploadOrchestrator
import build.raft.app.data.RaftSharedStoreProvider
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.SharedServerChannelMembersSlice
import build.raft.app.data.SharedServerMembersLoadStatus
import build.raft.app.data.StoredAuthSession
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import kotlinx.coroutines.sync.Semaphore

/**
 * The page's owners: the identity task projections are kept under, the server the member directory is
 * keyed on, and the draft-owned composer attachment uploads for this page's account and server.
 */
internal class ThreadRouteOwners(
    val taskProjectionOwnerIdentity: String?,
    val threadMembersServerId: ServerId?,
    val attachmentUploads: RaftComposerServerUploads,
    val attachmentUploadOwnerResolved: Boolean,
    val attachmentUploadOrchestrator: ThreadAttachmentUploadOrchestrator,
    val attachmentUploadSemaphore: Semaphore,
)

/**
 * The page's owners: see [ThreadRouteOwners]. Called every composition of the page from where this
 * code stood, so its remember keeps its keys and its place; only the holder is new each time.
 */
@Composable
internal fun rememberThreadRouteOwners(
    sharedStores: RaftSharedStoreProvider,
    storeScope: RaftShellStoreScope,
    currentSession: StoredAuthSession?,
    currentServerId: String?,
    replyCacheProvenance: ReplyCacheProvenance?,
): ThreadRouteOwners {
    val taskProjectionOwnerIdentity =
        remember(sharedStores.apiEnvironmentStore, currentSession?.user?.id) {
            currentSession?.user?.id
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { userId ->
                    "${sharedStores.apiEnvironmentStore.current().apiBaseUrl.trimEnd('/')}|$userId"
                }
        }
    // The session is captured a few frames after the page composes. Until then the shell's server
    // keys the member directory, so @mentions in the first content frame are already chips with
    // their names; reading nothing there painted them as plain text and re-laid the rows out as
    // chips a few frames later (Compose 版 open jitter, artin 11-03).
    val threadMembersServerId = threadPageServerId(currentServerId, storeScope.initialServerId)?.let(::ServerId)
    // Composer attachment uploads belong to the draft, not to this page (artin: "不能背后上传吗"):
    // the process-wide owner keeps them going after the user leaves the conversation, and the
    // composer that opens this draft again picks up their progress or result. Keyed on this page's
    // own account and server (the shell scope's server, else the page's captured provenance), never
    // on whichever server the session selects later.
    // The account comes from the signed-in route owner at once, not only from the session this
    // page loads asynchronously: a cached conversation renders its full page on the first frame
    // (#5104), and an upload started from that frame (or by a handler it registered) must land in
    // the same store the composer watches. Before, the first frame keyed the store on account ""
    // and the tile stayed on "Checking file…" (Android 1.27.0, artin; reproduced by Reef).
    val attachmentUploadAccountId =
        (currentSession?.user?.id ?: sharedStores.currentAuthenticatedRouteOwner(storeScope)?.accountId)
            ?.trim()
            ?.takeIf(String::isNotBlank)
    val attachmentUploadServerId =
        threadComposerUploadOwnerServerId(
            shellServerId = storeScope.initialServerId,
            pageServerId = replyCacheProvenance?.serverId,
            sessionServerId = currentServerId
        )
    val attachmentUploads =
        sharedStores.composerDraftUploads.forOwner(attachmentUploadAccountId, attachmentUploadServerId)
    // Until the page knows its account and server it cannot tell a draft-owned upload from an
    // orphan, so the orphan safety net waits for both.
    val attachmentUploadOwnerResolved = attachmentUploadAccountId != null && attachmentUploadServerId != null
    val attachmentUploadOrchestrator = attachmentUploads.orchestrator
    val attachmentUploadSemaphore = attachmentUploads.semaphore
    return ThreadRouteOwners(
        taskProjectionOwnerIdentity = taskProjectionOwnerIdentity,
        threadMembersServerId = threadMembersServerId,
        attachmentUploads = attachmentUploads,
        attachmentUploadOwnerResolved = attachmentUploadOwnerResolved,
        attachmentUploadOrchestrator = attachmentUploadOrchestrator,
        attachmentUploadSemaphore = attachmentUploadSemaphore,
    )
}

/**
 * The people a task can be assigned to: the channel's members once loaded, else the payload's.
 * Called every composition of the page from where this code stood, so its remember keeps its keys
 * and its place.
 */
@Composable
internal fun rememberThreadTaskAssigneeOptions(
    threadChannelMembersSlice: SharedServerChannelMembersSlice,
    threadMembersProjection: ThreadMembersProjection,
): List<MemberProfile> {
    val taskAssigneeOptions =
        remember(threadChannelMembersSlice, threadMembersProjection) {
            taskAssigneeIdentityOptions(
                channelMembers = threadChannelMembersSlice.members,
                channelMembersLoaded =
                    threadChannelMembersSlice.status == SharedServerMembersLoadStatus.Loaded,
                payloadMembers = threadMembersProjection.members.mapNotNull { it.toMemberProfile() }
            )
        }
    return taskAssigneeOptions
}
