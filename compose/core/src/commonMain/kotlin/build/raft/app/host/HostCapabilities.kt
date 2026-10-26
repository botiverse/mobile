package build.raft.app.host

import build.raft.app.data.RAFT_SOCKET_BASE_URL
import kotlinx.serialization.json.JsonObject

/**
 * Native host capabilities the UI-free layer (data, stores, settings/auth orchestration) calls.
 *
 * Today the only implementation is the Kuikly bridge adapter (`RaftHostCapabilities`, which
 * forwards to the `HRBridgeModule` methods of the same name); a Compose Multiplatform host will
 * implement it directly in Kotlin (Android) or via a Swift-implemented protocol (iOS). Nothing here
 * is addressed by a Kuikly pager id, so any host can hand an implementation to
 * `RaftSharedStoreRegistry.get(host)`.
 *
 * Payloads stay the existing bridge wire contracts: the `JsonObject` arguments are the exact
 * method args the native hosts already parse, and results are the host callback payloads.
 *
 * Bridge methods intentionally NOT in this surface yet (they stay on `BridgeModule`, used only by
 * Kuikly pages): page routing (`openPage`, `closePage`, `adaptiveNavigation`,
 * `conversationForwardGesture`), performance scenes/markers, attachment open/save/export/cache
 * and download-owner calls, thread share-image export/probe, motion, and the legacy template
 * methods. Logging never crossed the bridge (it goes through `raftLog*`), so it is not a host
 * capability either.
 */
interface HostCapabilities :
    HostFeedbackCapability,
    HostLinkCapability,
    HostSettingsCapability,
    HostAttachmentPickerCapability,
    HostAvatarCacheCapability,
    HostRealtimeCapability,
    HostAppBadgeCapability

/** Transient user feedback: `toast`, `copyToPasteboard`. */
interface HostFeedbackCapability {
    fun toast(content: String)

    fun copyToPasteboard(content: String)
}

/** `openUrl`: hands a URL (optionally typed) to the system. */
interface HostLinkCapability {
    fun openUrl(url: String, mimeType: String? = null)

    /**
     * An http(s) URL in the user's web browser, never back in Raft through Android App Links (an
     * action card's "Open in web" is a Raft link on purpose). Android pins the VIEW to a browser
     * package (the default browser, never a Raft app), else the ordinary external path; iOS shows
     * it in an SFSafariViewController. A host without a browser path: [openUrl].
     */
    fun openUrlInBrowser(url: String) = openUrl(url)
}

/**
 * `settingsCapability`: the action-multiplexed settings host (storage, about, licenses,
 * developer/API environment, diagnostics, notification permission/settings, Hands update and
 * feedback, app login / mobile OAuth). `command` carries `action` plus its arguments.
 */
interface HostSettingsCapability {
    suspend fun settingsCapability(command: JsonObject): JsonObject?

    /**
     * Streaming variant: keeps the host callback alive until a payload with `terminal=true`.
     * Returns a disposer that drops the callback early.
     */
    fun settingsCapabilityStream(command: JsonObject, onEvent: (JsonObject?) -> Unit): () -> Unit
}

/** `threadAttachmentUpload` (photo library / camera / document picker) and `compressThreadImage`. */
interface HostAttachmentPickerCapability {
    suspend fun threadAttachmentUpload(
        channelId: String,
        imageOnly: Boolean,
        capture: Boolean = false,
        compressImages: Boolean = false,
    ): JsonObject?

    suspend fun compressThreadImage(fileRef: String, fileName: String, mimeType: String): JsonObject?
}

/** `avatarCacheCapability` (`slock_avatar_cache_v1` commands). */
interface HostAvatarCacheCapability {
    suspend fun avatarCacheCapability(command: JsonObject): JsonObject?
}

/** `reportRealTime` actions: the native socket host is driven by shared realtime policy. */
interface HostRealtimeCapability {
    fun realtimeConnect(
        serverId: String,
        accessToken: String,
        shellInstanceId: String = "",
        socketUrl: String = RAFT_SOCKET_BASE_URL,
        lastSeq: Long = 0L,
        forceReconnect: Boolean = false,
        authorityScopeLeaseId: String,
        authorityPrincipalId: String,
        authorityAuthGeneration: Long,
        authorityConnectionFamilyId: String,
    )

    fun realtimeReconcile(
        serverIds: Set<String>,
        accessToken: String,
        environmentId: String,
        socketUrl: String = RAFT_SOCKET_BASE_URL,
        forceReconnect: Boolean = false,
        disconnectReason: String = "auth_invalid",
        authorityScopeLeaseId: String = "",
        authorityPrincipalId: String = "",
        authorityAuthGeneration: Long = 0L,
        authorityConnectionFamilyId: String = "",
    )

    fun realtimeDisconnect(
        reason: String = "shared_disconnect",
        serverId: String = "",
        shellInstanceId: String = "",
        explicitClose: Boolean = false,
    )

    fun realtimeAuthRefreshFinished(reason: String, success: Boolean)

    fun realtimeCursorAdvance(
        serverId: String,
        appliedSeq: Long,
        authorityScopeLeaseId: String,
        authorityPrincipalId: String,
        authorityAuthGeneration: Long,
        authorityConnectionFamilyId: String,
    )
}

/** `appBadge` and `syncDesktopShortcuts` (launcher surfaces). */
interface HostAppBadgeCapability {
    fun appBadge(count: Int, source: String, isStale: Boolean)

    fun syncDesktopShortcuts(shortcutsJson: String)
}
