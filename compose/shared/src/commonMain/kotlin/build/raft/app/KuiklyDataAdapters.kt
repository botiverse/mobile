package build.raft.app

import build.raft.app.base.BridgeModule
import build.raft.app.data.SlockEventBus
import build.raft.app.data.SlockSharedStoreProvider
import build.raft.app.data.SlockSharedStoreRegistry
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/*
 * Kuikly-side adapters onto the host-agnostic data layer: Kuikly pages hold a [BridgeModule] and
 * Kuikly `JSONObject` payloads; the data layer takes [build.raft.app.host.HostCapabilities] and kotlinx JSON.
 */

/** Kuikly pages obtain the process-wide stores through their pager's bridge (see [build.raft.app.host.HostCapabilities]). */
internal fun SlockSharedStoreRegistry.get(bridge: BridgeModule): SlockSharedStoreProvider =
    get(bridge.hostCapabilities)

internal fun SlockSharedStoreRegistry.get(
    eventBus: SlockEventBus,
    source: String,
    bridge: BridgeModule,
): SlockSharedStoreProvider = get(eventBus, source, bridge.hostCapabilities)

/**
 * Kuikly pages still build event payloads as Kuikly `JSONObject`; the bus carries kotlinx JSON.
 * The structural conversion keeps the serialized payload byte-identical.
 */
internal fun SlockEventBus.post(
    eventName: String,
    eventData: JSONObject,
    crossProcess: Boolean = false,
    source: String = "shared",
) = post(eventName = eventName, eventData = eventData.toJsonObject(), crossProcess = crossProcess, source = source)

/** Kuikly page data / pager events still arrive as Kuikly `JSONObject`. */
internal fun JSONObject.toSlockAdaptiveDetailCloseRequestOrNull(): SlockAdaptiveDetailCloseRequest? =
    toJsonObject().toSlockAdaptiveDetailCloseRequestOrNull()

internal fun updatedSlockAdaptiveDetailCloseRequest(
    current: SlockAdaptiveDetailCloseRequest?,
    eventData: JSONObject,
): SlockAdaptiveDetailCloseRequest? = updatedSlockAdaptiveDetailCloseRequest(current, eventData.toJsonObject())
