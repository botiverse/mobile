package build.raft.app.data

internal object SlockSystemNotificationContract {
    const val OpenEventName = "slockSystemNotificationOpen"
    const val OpenReadyEventName = "slockSystemNotificationOpenReady"
    const val OpenNotReadyEventName = "slockSystemNotificationOpenNotReady"
}

/**
 * task #13 external deep-link contract (interface v3.4): dedicated bus events
 * and pageData block keys. All wire fields are JSON strings; optional fields
 * are absent or empty, never the literal `"null"`.
 */
object SlockExternalPermalinkContract {
    const val OpenEventName = "slockExternalPermalinkOpen"
    const val AcceptedEventName = "slockExternalPermalinkAccepted"
    const val ReadyEventName = "slockExternalPermalinkReady"
    const val NotReadyEventName = "slockExternalPermalinkNotReady"
    const val ResultEventName = "slockExternalPermalinkResult"

    const val AcceptedStatusFirstTime = "accepted_first_time"
    const val AcceptedStatusDuplicate = "accepted_duplicate"
    const val AcceptedStatusRejected = "rejected"

    const val ResultStatusOpened = "opened"
    const val ResultStatusRejected = "rejected"

    const val ReasonServerUnavailable = "server_unavailable"
    const val ReasonConversationUnavailable = "conversation_unavailable"
    const val ReasonActivationFailed = "activation_failed"
    const val ReasonIdentityMissing = "identity_missing"
    const val ReasonAuthGenerationStale = "auth_generation_stale"
    const val ReasonLoginCancelled = "login_cancelled"

    // pageData `externalLink` block fields (identity envelope, all strings).
    const val KeyActivationId = "activationId"
    const val KeyServerSlug = "serverSlug"
    const val KeyChannelId = "channelId"
    const val KeyRouteKind = "routeKind"
    const val KeyParentMessageId = "parentMessageId"
    const val KeyMessageId = "messageId"
    const val KeyShellScopeId = "shellScopeId"
    const val KeyAuthGeneration = "authGeneration"
    const val KeyReceivedAtMillis = "receivedAtMillis"
    const val KeyOriginHost = "originHost"

    const val PageDataBlockKey = "externalLink"
}
