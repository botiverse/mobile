package build.raft.app

import build.raft.app.resources.*
import androidx.compose.runtime.Composable
import build.raft.app.data.AddComputerDialogState
import build.raft.app.data.EmptyReason
import build.raft.app.data.ErrorPresentation
import build.raft.app.data.ErrorPresentationKey
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.RawServerMessagePriority
import build.raft.app.data.ScreenError
import build.raft.app.data.SharedSettingsActionState
import build.raft.app.data.errorPresentation
import build.raft.app.data.errorPresentationOrNull
import build.raft.app.data.usableServerRefusalMessage

/**
 * Locale-resolved half of the canonical error pipeline.
 *
 * Data/domain code only emits [ErrorPresentationKey] plus an optional raw
 * server reason. This dictionary is constructed at a Composable boundary so
 * locale changes re-resolve every app-owned sentence through MR resources.
 */
@RaftCoreInternalApi class ErrorPresentationStrings(
    private val values: Map<ErrorPresentationKey, String>
) {
    fun value(key: ErrorPresentationKey): String =
        requireNotNull(values[key]) { "Missing localized error presentation copy for $key" }
}

@Composable
@RaftCoreInternalApi fun localizedErrorPresentationStrings(): ErrorPresentationStrings =
    ErrorPresentationStrings(
        mapOf(
            ErrorPresentationKey.SessionExpired to stringResource(Res.string.error_session_expired),
            ErrorPresentationKey.ServerUnreachable to stringResource(Res.string.error_server_unreachable),
            ErrorPresentationKey.RequestTimedOut to stringResource(Res.string.error_request_timed_out),
            ErrorPresentationKey.NetworkConnectionFailed to stringResource(Res.string.error_network_connection_failed),
            ErrorPresentationKey.ServerUnavailable to stringResource(Res.string.error_server_unavailable),
            ErrorPresentationKey.ItemNotFound to stringResource(Res.string.error_item_not_found),
            ErrorPresentationKey.PermissionDenied to stringResource(Res.string.error_permission_denied),
            ErrorPresentationKey.EdgeBlocked to stringResource(Res.string.error_edge_blocked),
            ErrorPresentationKey.AttachmentTooLarge to stringResource(Res.string.error_attachment_too_large),
            ErrorPresentationKey.AttachmentMonthlyQuotaExceeded to stringResource(Res.string.upgrade_dialog_upload_quota_title),
            ErrorPresentationKey.ChannelReadOnly to stringResource(Res.string.thread_error_reason_channel_read_only),
            ErrorPresentationKey.ChannelArchived to stringResource(Res.string.thread_archived_notice),
            ErrorPresentationKey.TooManyAttempts to stringResource(Res.string.error_too_many_attempts),
            ErrorPresentationKey.ResponseUnreadable to stringResource(Res.string.error_response_unreadable),
            ErrorPresentationKey.ServerVersionIncompatible to stringResource(Res.string.error_server_version_incompatible),
            ErrorPresentationKey.Generic to stringResource(Res.string.error_generic),
            ErrorPresentationKey.EmptyNoChannels to stringResource(Res.string.empty_no_channels),
            ErrorPresentationKey.EmptyNoMessages to stringResource(Res.string.empty_no_messages),
            ErrorPresentationKey.EmptyNoTasks to stringResource(Res.string.empty_no_tasks),
            ErrorPresentationKey.EmptyNoMembers to stringResource(Res.string.empty_no_members),
            ErrorPresentationKey.EmptyNoInboxItems to stringResource(Res.string.empty_no_inbox_items),
            ErrorPresentationKey.EmptyNoSettingsAvailable to stringResource(Res.string.empty_no_settings_available),
            ErrorPresentationKey.EmptyNoSearchResults to stringResource(Res.string.empty_no_search_results)
        )
    )

@RaftCoreInternalApi fun ErrorPresentation.resolve(strings: ErrorPresentationStrings): String {
    val raw = usableServerRefusalMessage(rawServerMessage)
    return when (rawServerMessagePriority) {
        RawServerMessagePriority.ServerMessageFirst -> raw ?: strings.value(key)
        RawServerMessagePriority.LocalizedCopyFirst -> strings.value(key)
    }
}

@RaftCoreInternalApi fun ScreenError.userFacingMessage(strings: ErrorPresentationStrings): String =
    errorPresentation().resolve(strings)

@RaftCoreInternalApi fun PermissionBlocker.userFacingMessage(strings: ErrorPresentationStrings): String =
    errorPresentation().resolve(strings)

@RaftCoreInternalApi fun EmptyReason.userFacingMessage(strings: ErrorPresentationStrings): String =
    errorPresentation().resolve(strings)

@RaftCoreInternalApi fun String?.userFacingErrorCodeMessage(strings: ErrorPresentationStrings): String =
    errorPresentation().resolve(strings)

@RaftCoreInternalApi fun AddComputerDialogState.userFacingErrorMessage(strings: ErrorPresentationStrings): String? =
    errorPresentationOrNull()?.resolve(strings)
        ?: errorMessage?.takeIf { it.isNotBlank() }

@RaftCoreInternalApi fun SharedSettingsActionState.userFacingErrorMessage(strings: ErrorPresentationStrings): String? =
    errorPresentationOrNull()?.resolve(strings)

@RaftCoreInternalApi fun SharedSettingsActionState.hasUserFacingError(): Boolean =
    screenError != null || permissionBlocker != null || emptyReason != null || !errorCode.isNullOrBlank()

@Composable
@RaftCoreInternalApi fun ScreenError.localizedUserFacingMessage(): String =
    userFacingMessage(localizedErrorPresentationStrings())

@Composable
@RaftCoreInternalApi fun PermissionBlocker.localizedUserFacingMessage(): String =
    userFacingMessage(localizedErrorPresentationStrings())

@Composable
@RaftCoreInternalApi fun EmptyReason.localizedUserFacingMessage(): String =
    userFacingMessage(localizedErrorPresentationStrings())
