package build.raft.app.base

import build.raft.app.host.HostCapabilities
import build.raft.app.toJsonObject
import build.raft.app.toKuiklyJson
import kotlinx.serialization.json.JsonObject

/**
 * [HostCapabilities] backed by a Kuikly pager's [BridgeModule] (`HRBridgeModule` on every host).
 *
 * JSON crosses the boundary structurally (`toKuiklyJson` / `toJsonObject`), so the strings the
 * bridge sends to native are byte-identical to the ones the data layer produced before it moved
 * to kotlinx JSON. Calls keep the bridge's threading contract: invoke them on the Kuikly context
 * thread (callers already use `kuiklyBridgeCallContext()`).
 */
internal class RaftHostCapabilities(
    val bridge: BridgeModule,
) : HostCapabilities {
    override fun toast(content: String) = bridge.toast(content)

    override fun copyToPasteboard(content: String) = bridge.copyToPasteboard(content)

    override fun openUrl(url: String, mimeType: String?) = bridge.openUrl(url, mimeType)

    override suspend fun settingsCapability(command: JsonObject): JsonObject? =
        bridge.settingsCapability(command.toKuiklyJson())?.toJsonObject()

    override fun settingsCapabilityStream(
        command: JsonObject,
        onEvent: (JsonObject?) -> Unit,
    ): () -> Unit =
        bridge.settingsCapabilityStream(command.toKuiklyJson()) { payload -> onEvent(payload?.toJsonObject()) }

    override suspend fun threadAttachmentUpload(
        channelId: String,
        imageOnly: Boolean,
        capture: Boolean,
        compressImages: Boolean,
    ): JsonObject? =
        bridge.threadAttachmentUpload(
            channelId = channelId,
            imageOnly = imageOnly,
            capture = capture,
            compressImages = compressImages,
        )?.toJsonObject()

    override suspend fun compressThreadImage(fileRef: String, fileName: String, mimeType: String): JsonObject? =
        bridge.compressThreadImage(fileRef = fileRef, fileName = fileName, mimeType = mimeType)?.toJsonObject()

    override suspend fun avatarCacheCapability(command: JsonObject): JsonObject? =
        bridge.avatarCacheCapability(command.toKuiklyJson())?.toJsonObject()

    override fun realtimeConnect(
        serverId: String,
        accessToken: String,
        shellInstanceId: String,
        socketUrl: String,
        lastSeq: Long,
        forceReconnect: Boolean,
        authorityScopeLeaseId: String,
        authorityPrincipalId: String,
        authorityAuthGeneration: Long,
        authorityConnectionFamilyId: String,
    ) = bridge.realtimeConnect(
        serverId = serverId,
        accessToken = accessToken,
        shellInstanceId = shellInstanceId,
        socketUrl = socketUrl,
        lastSeq = lastSeq,
        forceReconnect = forceReconnect,
        authorityScopeLeaseId = authorityScopeLeaseId,
        authorityPrincipalId = authorityPrincipalId,
        authorityAuthGeneration = authorityAuthGeneration,
        authorityConnectionFamilyId = authorityConnectionFamilyId,
    )

    override fun realtimeReconcile(
        serverIds: Set<String>,
        accessToken: String,
        environmentId: String,
        socketUrl: String,
        forceReconnect: Boolean,
        disconnectReason: String,
        authorityScopeLeaseId: String,
        authorityPrincipalId: String,
        authorityAuthGeneration: Long,
        authorityConnectionFamilyId: String,
    ) = bridge.realtimeReconcile(
        serverIds = serverIds,
        accessToken = accessToken,
        environmentId = environmentId,
        socketUrl = socketUrl,
        forceReconnect = forceReconnect,
        disconnectReason = disconnectReason,
        authorityScopeLeaseId = authorityScopeLeaseId,
        authorityPrincipalId = authorityPrincipalId,
        authorityAuthGeneration = authorityAuthGeneration,
        authorityConnectionFamilyId = authorityConnectionFamilyId,
    )

    override fun realtimeDisconnect(
        reason: String,
        serverId: String,
        shellInstanceId: String,
        explicitClose: Boolean,
    ) = bridge.realtimeDisconnect(
        reason = reason,
        serverId = serverId,
        shellInstanceId = shellInstanceId,
        explicitClose = explicitClose,
    )

    override fun realtimeAuthRefreshFinished(reason: String, success: Boolean) =
        bridge.realtimeAuthRefreshFinished(reason, success)

    override fun realtimeCursorAdvance(
        serverId: String,
        appliedSeq: Long,
        authorityScopeLeaseId: String,
        authorityPrincipalId: String,
        authorityAuthGeneration: Long,
        authorityConnectionFamilyId: String,
    ) = bridge.realtimeCursorAdvance(
        serverId = serverId,
        appliedSeq = appliedSeq,
        authorityScopeLeaseId = authorityScopeLeaseId,
        authorityPrincipalId = authorityPrincipalId,
        authorityAuthGeneration = authorityAuthGeneration,
        authorityConnectionFamilyId = authorityConnectionFamilyId,
    )

    override fun appBadge(count: Int, source: String, isStale: Boolean) =
        bridge.appBadge(count, source, isStale)

    override fun syncDesktopShortcuts(shortcutsJson: String) = bridge.syncDesktopShortcuts(shortcutsJson)
}
