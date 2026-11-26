package build.raft.app

import build.raft.app.resources.*
import build.raft.app.format.RaftFormat
import build.raft.app.data.SharedSettingsActionState
import build.raft.app.data.SharedSettingsStorageRow
import build.raft.app.data.SharedSettingsStorageSnapshot
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.AccountSettingsSnapshot
import build.raft.app.data.ACCOUNT_ACTION_AVATAR_UPLOAD
import build.raft.app.data.ACCOUNT_ACTION_PASSWORD_CHANGE
import build.raft.app.data.ACCOUNT_ACTION_PROFILE_SAVE
import build.raft.app.data.ACCOUNT_ACTION_PROVIDER_CONNECT
import build.raft.app.data.ACCOUNT_ACTION_PROVIDER_DISCONNECT
import build.raft.app.data.ACCOUNT_ACTION_SHARE_USAGE_DATA
import build.raft.app.data.SHARE_USAGE_DATA_DEFAULT
import build.raft.app.data.ConnectedAccountStatus
import build.raft.app.model.MemberKind
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.flow.map
import build.raft.app.data.map

internal fun appShellAuthGateViewModelKey(pagerId: String, instanceId: String = ""): String =
    "shared-auth-gate-${instanceId.ifBlank { pagerId }.ifBlank { "default" }}"

internal fun ScreenLoadState<AccountSettingsSnapshot>.toAccountPayload(
    session: StoredAuthSession?,
    actionState: build.raft.app.data.SharedSettingsActionState? = null,
    errorStrings: ErrorPresentationStrings
): RaftSettingsAccountPayload {
    val account = (this as? ScreenLoadState.Content<AccountSettingsSnapshot>)?.data
    val providerActionId =
        actionState
            ?.itemId
            ?.takeIf { actionState.action == ACCOUNT_ACTION_PROVIDER_CONNECT }
    val providerDisconnectId =
        actionState
            ?.itemId
            ?.takeIf { actionState.action == ACCOUNT_ACTION_PROVIDER_DISCONNECT }
    val actionErrorMessage =
        actionState?.userFacingErrorMessage(errorStrings)
    return (account?.toAccountPayload() ?: appShellAccountPayload(session))
        .copy(
            saveInProgress = actionState?.action == ACCOUNT_ACTION_PROFILE_SAVE && actionState.inProgress,
            saveErrorCode = actionErrorMessage.takeIf { actionState?.action == ACCOUNT_ACTION_PROFILE_SAVE },
            avatarInProgress = actionState?.action == ACCOUNT_ACTION_AVATAR_UPLOAD && actionState.inProgress,
            avatarErrorCode = actionErrorMessage.takeIf { actionState?.action == ACCOUNT_ACTION_AVATAR_UPLOAD },
            passwordInProgress = actionState?.action == ACCOUNT_ACTION_PASSWORD_CHANGE && actionState.inProgress,
            passwordErrorCode = actionErrorMessage.takeIf { actionState?.action == ACCOUNT_ACTION_PASSWORD_CHANGE },
            providerConnectingId = providerActionId.takeIf { actionState?.inProgress == true },
            providerErrorId = providerActionId.takeIf { actionErrorMessage != null },
            providerErrorCode = actionErrorMessage.takeIf { providerActionId != null },
            providerDisconnectingId = providerDisconnectId.takeIf { actionState?.inProgress == true },
            providerDisconnectErrorId = providerDisconnectId.takeIf { actionErrorMessage != null },
            providerDisconnectError = actionErrorMessage.takeIf { providerDisconnectId != null },
            providerDisconnectErrorTraceId =
                (actionState?.screenError?.traceId ?: actionState?.permissionBlocker?.traceId)
                    .takeIf { providerDisconnectId != null && actionErrorMessage != null },
            usageDataSaving = actionState?.action == ACCOUNT_ACTION_SHARE_USAGE_DATA && actionState.inProgress
        )
}

internal fun ScreenLoadState<AccountSettingsSnapshot>.settingsAccountAvatarCacheIdentity(
    session: StoredAuthSession?
): RaftMemberAvatarCacheIdentity? {
    val sessionUserId = session?.user?.id?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val account = (this as? ScreenLoadState.Content<AccountSettingsSnapshot>)?.data ?: return null
    val profileUserId = account.profile.userId.trim().takeIf(String::isNotEmpty) ?: return null
    if (profileUserId != sessionUserId) return null
    return raftMemberAvatarCacheIdentity(
        kind = MemberKind.Human,
        memberId = profileUserId,
        serverId = session.currentServerId
    )
}

private fun AccountSettingsSnapshot.toAccountPayload(): RaftSettingsAccountPayload =
    RaftSettingsAccountPayload(
        instanceId = "shared-shell-settings-account",
        profile =
            RaftSettingsAccountProfile(
                displayName = profile.displayName,
                handle = profile.handle,
                email = profile.email,
                avatarLabel = profile.avatarLabel,
                avatarUrl = profile.avatarUrl,
                verified = profile.verified,
                roleLabel = profile.roleLabel.orEmpty()
            ),
        connectedAccounts =
            connectedAccounts.map {
                RaftSettingsConnectedAccount(
                    providerId = it.providerId,
                    provider = it.provider,
                    identifier = it.identifier,
                    status =
                        when (it.status) {
                            ConnectedAccountStatus.Connected -> "connected"
                            ConnectedAccountStatus.NotConnected -> "notConnected"
                            ConnectedAccountStatus.Loading -> "loading"
                            ConnectedAccountStatus.Failed -> "failed"
                        },
                    canConnect = it.canConnect
                )
            },
        canEditProfile = canEditProfile,
        canUploadAvatar = canUploadAvatar,
        canChangePassword = canChangePassword,
        usageDataVisible = usageData != null,
        usageDataSharing = usageData?.sharing ?: SHARE_USAGE_DATA_DEFAULT
    )

private fun appShellAccountPayload(session: StoredAuthSession?): RaftSettingsAccountPayload {
    val user = session?.user
    val displayName = user?.bestDisplayName.orEmpty()
    return RaftSettingsAccountPayload(
        instanceId = "shared-shell-settings-account",
        profile =
            RaftSettingsAccountProfile(
                displayName = displayName,
                // `handle` is the username field; the loaded account fills it from the profile's name.
                handle = user?.name?.trim().orEmpty(),
                email = user?.email.orEmpty(),
                avatarLabel = displayName.take(2).uppercase(),
                avatarUrl = user?.avatarUrl,
                verified = user?.emailVerified == true,
                roleLabel = session?.currentServerSummary()?.role.orEmpty()
            ),
        connectedAccounts =
            user
                ?.email
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    listOf(
                        RaftSettingsConnectedAccount(
                            providerId = "password",
                            provider = "Email password",
                            identifier = it,
                            status = "connected",
                            canConnect = false
                        )
                    )
                }
                .orEmpty()
    )
}

@Composable
internal fun ScreenLoadState<SharedSettingsStorageSnapshot>.toStoragePayload(
    actionState: build.raft.app.data.SharedSettingsActionState?,
    refreshing: Boolean
): RaftSettingsStoragePayload {
    val storage = (this as? ScreenLoadState.Content<SharedSettingsStorageSnapshot>)?.data
    val statusText =
        when (this) {
            ScreenLoadState.Loading -> if (refreshing) stringResource(Res.string.settings_storage_refreshing) else stringResource(Res.string.settings_storage_loading)
            // No storage info from this host (the entry isn't listed there, settingsStorageAvailable):
            // a neutral fact, no line on why.
            is ScreenLoadState.Empty -> stringResource(Res.string.settings_storage_empty)
            is ScreenLoadState.Error -> Res.string.settings_storage_unavailable_detail.format(listOf(this.error.localizedUserFacingMessage())).localized()
            is ScreenLoadState.NoPermission -> Res.string.settings_storage_unavailable_detail.format(listOf(this.blocker.localizedUserFacingMessage())).localized()
            is ScreenLoadState.Content ->
                storage?.feedback.orEmpty().ifBlank {
                    storage?.feedbackError
                        ?.resolve(localizedErrorPresentationStrings())
                        .orEmpty()
                }
        }
    return RaftSettingsStoragePayload(
        instanceId = "shared-shell-settings-storage",
        totalTitle = stringResource(Res.string.settings_storage_total_title),
        totalSize = storage?.totalSizeBytes?.let { RaftFormat.bytes(it) } ?: storage?.totalSizeLabel?.takeIf { it.isNotBlank() } ?: if (refreshing) stringResource(Res.string.settings_state_refreshing) else "--",
        refreshLabel = stringResource(Res.string.settings_storage_refresh),
        safeClearNote = stringResource(Res.string.settings_storage_safe_clear_note),
        feedback = localizedSettingsStorageFeedback(statusText),
        refreshing = refreshing,
        rows =
            storage?.rows?.map { row ->
                row.toStoragePayloadRow(
                    clearInProgress =
                        actionState?.action == "clearStorage" &&
                            actionState.itemId == row.target &&
                            actionState.inProgress
                )
            }.orEmpty()
    )
}

@Composable
private fun SharedSettingsStorageRow.toStoragePayloadRow(clearInProgress: Boolean): RaftSettingsStorageRow =
    RaftSettingsStorageRow(
        target = target,
        iconName = storageTargetIconName(target),
        title = localizedSettingsStorageRowTitle(target, title),
        detail = localizedSettingsStorageRowDetail(detail),
        size = sizeBytes?.let { RaftFormat.bytes(it) } ?: sizeLabel,
        clearLabel =
            if (clearEnabled) {
                when (target) {
                    "imageCache" -> stringResource(Res.string.settings_storage_clear_image_cache)
                    "attachmentDownloads" -> stringResource(Res.string.settings_storage_clear_downloaded_files)
                    "temporaryAndLogs" -> stringResource(Res.string.settings_storage_clear_temp_files)
                    "inAppBrowser" -> stringResource(Res.string.settings_storage_clear_in_app_browser)
                    else -> stringResource(Res.string.common_clear)
                }
            } else {
                ""
            },
        clearEnabled = clearEnabled,
        clearInProgress = clearInProgress
    )

@Composable
private fun localizedSettingsStorageRowTitle(target: String, fallback: String): String =
    when (target) {
        "database" -> stringResource(Res.string.settings_storage_row_database)
        "imageCache" -> stringResource(Res.string.settings_storage_row_image_cache)
        "attachmentDownloads" -> stringResource(Res.string.settings_storage_row_downloads)
        "temporaryAndLogs" -> stringResource(Res.string.settings_storage_row_temporary)
        "inAppBrowser" -> stringResource(Res.string.settings_storage_row_in_app_browser)
        else -> fallback
    }

@Composable
private fun localizedSettingsStorageRowDetail(detail: String): String =
    when (detail) {
        "Messages, task metadata, and server snapshots stored on this device." ->
            stringResource(Res.string.settings_storage_row_database_detail)
        "Downloaded image previews kept for faster viewing." ->
            stringResource(Res.string.settings_storage_row_image_cache_detail)
        "Files downloaded for opening or installing from previews.",
        "Private copies kept for opening or installing files. Public exports are not removed.",
        "Temporary data from active or failed downloads. Completed files remain in Downloads." ->
            stringResource(Res.string.settings_storage_row_downloads_detail)
        "Debug logs and temporary files generated by the app." ->
            stringResource(Res.string.settings_storage_row_temporary_detail)
        "Cached web pages and cookies from websites opened in Raft." ->
            stringResource(Res.string.settings_storage_row_in_app_browser_detail)
        else -> detail
    }

@Composable
private fun localizedSettingsStorageFeedback(feedback: String): String =
    when (feedback) {
        "Image cache cleared." -> stringResource(Res.string.settings_storage_feedback_image_cleared)
        "Downloaded files cleared.",
        "Attachment cache cleared. Public exports were not removed.",
        "Attachment staging cleared. Completed Downloads were not removed." ->
            stringResource(Res.string.settings_storage_feedback_downloads_cleared)
        "Temporary files and diagnostics logs cleared." ->
            stringResource(Res.string.settings_storage_feedback_temporary_cleared)
        "In-app browser cache and cookies cleared." ->
            stringResource(Res.string.settings_storage_feedback_in_app_browser_cleared)
        else -> feedback
    }

private fun storageTargetIconName(target: String): RaftLucideIconName =
    when (target) {
        "database" -> RaftLucideIconName.Archive
        "imageCache" -> RaftLucideIconName.Image
        "attachmentDownloads" -> RaftLucideIconName.Download
        "temporaryAndLogs" -> RaftLucideIconName.FileText
        "inAppBrowser" -> RaftLucideIconName.Globe
        else -> RaftLucideIconName.Archive
    }
