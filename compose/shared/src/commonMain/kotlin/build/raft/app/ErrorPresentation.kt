package build.raft.app

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
import build.raft.app.resources.MR
import com.tencent.tmm.kmmresource.compose.stringResource

/**
 * Locale-resolved half of the canonical error pipeline.
 *
 * Data/domain code only emits [ErrorPresentationKey] plus an optional raw
 * server reason. This dictionary is constructed at a Composable boundary so
 * locale changes re-resolve every app-owned sentence through MR resources.
 */
internal class ErrorPresentationStrings(
    private val values: Map<ErrorPresentationKey, String>
) {
    fun value(key: ErrorPresentationKey): String =
        requireNotNull(values[key]) { "Missing localized error presentation copy for $key" }
}

@Composable
internal fun localizedErrorPresentationStrings(): ErrorPresentationStrings =
    ErrorPresentationStrings(
        mapOf(
            ErrorPresentationKey.SessionExpired to stringResource(MR.strings.error_session_expired),
            ErrorPresentationKey.ServerUnreachable to stringResource(MR.strings.error_server_unreachable),
            ErrorPresentationKey.RequestTimedOut to stringResource(MR.strings.error_request_timed_out),
            ErrorPresentationKey.NetworkConnectionFailed to stringResource(MR.strings.error_network_connection_failed),
            ErrorPresentationKey.ServerUnavailable to stringResource(MR.strings.error_server_unavailable),
            ErrorPresentationKey.ItemNotFound to stringResource(MR.strings.error_item_not_found),
            ErrorPresentationKey.PermissionDenied to stringResource(MR.strings.error_permission_denied),
            ErrorPresentationKey.EdgeBlocked to stringResource(MR.strings.error_edge_blocked),
            ErrorPresentationKey.AttachmentTooLarge to stringResource(MR.strings.error_attachment_too_large),
            ErrorPresentationKey.TooManyAttempts to stringResource(MR.strings.error_too_many_attempts),
            ErrorPresentationKey.ResponseUnreadable to stringResource(MR.strings.error_response_unreadable),
            ErrorPresentationKey.Generic to stringResource(MR.strings.error_generic),
            ErrorPresentationKey.EmptyNoChannels to stringResource(MR.strings.empty_no_channels),
            ErrorPresentationKey.EmptyNoMessages to stringResource(MR.strings.empty_no_messages),
            ErrorPresentationKey.EmptyNoTasks to stringResource(MR.strings.empty_no_tasks),
            ErrorPresentationKey.EmptyNoMembers to stringResource(MR.strings.empty_no_members),
            ErrorPresentationKey.EmptyNoInboxItems to stringResource(MR.strings.empty_no_inbox_items),
            ErrorPresentationKey.EmptyNoSettingsAvailable to stringResource(MR.strings.empty_no_settings_available),
            ErrorPresentationKey.EmptyNoSearchResults to stringResource(MR.strings.empty_no_search_results)
        )
    )

internal fun ErrorPresentation.resolve(strings: ErrorPresentationStrings): String {
    val raw = rawServerMessage?.takeIf { it.isNotBlank() }
    return when (rawServerMessagePriority) {
        RawServerMessagePriority.ServerMessageFirst -> raw ?: strings.value(key)
        RawServerMessagePriority.LocalizedCopyFirst -> strings.value(key)
    }
}

internal fun ScreenError.userFacingMessage(strings: ErrorPresentationStrings): String =
    errorPresentation().resolve(strings)

internal fun PermissionBlocker.userFacingMessage(strings: ErrorPresentationStrings): String =
    errorPresentation().resolve(strings)

internal fun EmptyReason.userFacingMessage(strings: ErrorPresentationStrings): String =
    errorPresentation().resolve(strings)

internal fun String?.userFacingErrorCodeMessage(strings: ErrorPresentationStrings): String =
    errorPresentation().resolve(strings)

internal fun AddComputerDialogState.userFacingErrorMessage(strings: ErrorPresentationStrings): String? =
    errorPresentationOrNull()?.resolve(strings)
        ?: errorMessage?.takeIf { it.isNotBlank() }

internal fun SharedSettingsActionState.userFacingErrorMessage(strings: ErrorPresentationStrings): String? =
    errorPresentationOrNull()?.resolve(strings)

internal fun SharedSettingsActionState.hasUserFacingError(): Boolean =
    screenError != null || permissionBlocker != null || emptyReason != null || !errorCode.isNullOrBlank()

@Composable
internal fun ScreenError.localizedUserFacingMessage(): String =
    userFacingMessage(localizedErrorPresentationStrings())

@Composable
internal fun PermissionBlocker.localizedUserFacingMessage(): String =
    userFacingMessage(localizedErrorPresentationStrings())

@Composable
internal fun EmptyReason.localizedUserFacingMessage(): String =
    userFacingMessage(localizedErrorPresentationStrings())
