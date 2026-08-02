package build.raft.app

import build.raft.app.data.ErrorPresentationKey

internal fun englishErrorPresentationStringsForTest(): ErrorPresentationStrings =
    ErrorPresentationStrings(
        mapOf(
            ErrorPresentationKey.SessionExpired to "Your session expired. Sign in again to continue.",
            ErrorPresentationKey.ServerUnreachable to "The server could not be reached. Check your connection and try again.",
            ErrorPresentationKey.RequestTimedOut to "The server did not respond in time. Please try again.",
            ErrorPresentationKey.NetworkConnectionFailed to "The network connection failed. Check your connection and try again.",
            ErrorPresentationKey.ServerUnavailable to "The server is unavailable right now. Please try again shortly.",
            ErrorPresentationKey.ItemNotFound to "This item could not be found. It may have been moved or deleted.",
            ErrorPresentationKey.PermissionDenied to "You do not have permission to view this page.",
            ErrorPresentationKey.AttachmentTooLarge to "The attachment is too large. Choose a smaller file and try again.",
            ErrorPresentationKey.TooManyAttempts to "Too many attempts. Please wait a moment and try again.",
            ErrorPresentationKey.ResponseUnreadable to "The response could not be read. Please try again.",
            ErrorPresentationKey.Generic to "Something went wrong. Please try again.",
            ErrorPresentationKey.EmptyNoChannels to "No channels are available yet.",
            ErrorPresentationKey.EmptyNoMessages to "No messages are available yet.",
            ErrorPresentationKey.EmptyNoTasks to "No tasks are available yet.",
            ErrorPresentationKey.EmptyNoMembers to "No members are available yet.",
            ErrorPresentationKey.EmptyNoInboxItems to "No activity is available yet.",
            ErrorPresentationKey.EmptyNoSettingsAvailable to "These settings are not available right now.",
            ErrorPresentationKey.EmptyNoSearchResults to "No results matched your search."
        )
    )
