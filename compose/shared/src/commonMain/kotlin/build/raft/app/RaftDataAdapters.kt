package build.raft.app

import build.raft.app.base.BridgeModule
import build.raft.app.data.RaftEventBus
import build.raft.app.data.RaftSharedStoreProvider
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.compat.json.JSONObject

/*
 * Kuikly-side adapters onto the host-agnostic data layer: Kuikly pages hold a [BridgeModule] and
 * Kuikly `JSONObject` payloads; the data layer takes [build.raft.app.host.HostCapabilities] and kotlinx JSON.
 */

/** Kuikly pages obtain the process-wide stores through their pager's bridge (see [build.raft.app.host.HostCapabilities]). */
internal fun RaftSharedStoreRegistry.get(bridge: BridgeModule): RaftSharedStoreProvider =
    get(bridge.hostCapabilities)

internal fun RaftSharedStoreRegistry.get(
    eventBus: RaftEventBus,
    source: String,
    bridge: BridgeModule,
): RaftSharedStoreProvider = get(eventBus, source, bridge.hostCapabilities)

/**
 * Kuikly pages still build event payloads as Kuikly `JSONObject`; the bus carries kotlinx JSON.
 * The structural conversion keeps the serialized payload byte-identical.
 */
internal fun RaftEventBus.post(
    eventName: String,
    eventData: JSONObject,
    crossProcess: Boolean = false,
    source: String = "shared",
) = post(eventName = eventName, eventData = eventData.toJsonObject(), crossProcess = crossProcess, source = source)

/** Kuikly page data / pager events still arrive as Kuikly `JSONObject`. */
internal fun JSONObject.toRaftAdaptiveDetailCloseRequestOrNull(): RaftAdaptiveDetailCloseRequest? =
    toJsonObject().toRaftAdaptiveDetailCloseRequestOrNull()

internal fun updatedRaftAdaptiveDetailCloseRequest(
    current: RaftAdaptiveDetailCloseRequest?,
    eventData: JSONObject,
): RaftAdaptiveDetailCloseRequest? = updatedRaftAdaptiveDetailCloseRequest(current, eventData.toJsonObject())
