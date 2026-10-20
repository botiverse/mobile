package build.raft.app.data

import build.raft.app.data.realtime.GeneratedRealtimeContract
import build.raft.app.optString
import build.raft.app.parseRaftJsonObject
import build.raft.app.toRaftJsonString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.darwin.NSObjectProtocol

private const val RaftPlatformEventOriginKey = "slockPlatformEventOrigin"
private const val RaftPlatformEventOriginShared = "shared"
private const val RaftPlatformEventSourceIosHost = "ios_host"

private object IosRaftPlatformEventDispatcher {
    private var realtimeObserver: NSObjectProtocol? = null
    private var systemNotificationOpenObserver: NSObjectProtocol? = null
    private var desktopShortcutOpenObserver: NSObjectProtocol? = null

    fun ensureNativeIngress() {
        if (realtimeObserver == null) {
            realtimeObserver =
                NSNotificationCenter.defaultCenter.addObserverForName(
                    name = GeneratedRealtimeContract.Notify.EventName,
                    `object` = null,
                    queue = null
                ) { notification ->
                    notification ?: return@addObserverForName
                    if (notification.userInfo?.get(RaftPlatformEventOriginKey) == RaftPlatformEventOriginShared) {
                        return@addObserverForName
                    }
                    RaftEnhancedNotifyCenter.post(
                        eventName = GeneratedRealtimeContract.Notify.EventName,
                        eventData = notification.toEventData(),
                        crossProcess = true,
                        source = RaftPlatformEventSourceIosHost
                    )
                }
        }
        if (systemNotificationOpenObserver == null) {
            systemNotificationOpenObserver =
                NSNotificationCenter.defaultCenter.addObserverForName(
                    name = RaftSystemNotificationContract.OpenEventName,
                    `object` = null,
                    queue = null
                ) { notification ->
                    notification ?: return@addObserverForName
                    if (notification.userInfo?.get(RaftPlatformEventOriginKey) == RaftPlatformEventOriginShared) {
                        return@addObserverForName
                    }
                    RaftEnhancedNotifyCenter.post(
                        eventName = RaftSystemNotificationContract.OpenEventName,
                        eventData = notification.toEventData(),
                        crossProcess = false,
                        source = RaftPlatformEventSourceIosHost
                    )
                }
        }
        if (desktopShortcutOpenObserver == null) {
            desktopShortcutOpenObserver =
                NSNotificationCenter.defaultCenter.addObserverForName(
                    name = RaftDesktopShortcutContract.OpenEventName,
                    `object` = null,
                    queue = null
                ) { notification ->
                    notification ?: return@addObserverForName
                    if (notification.userInfo?.get(RaftPlatformEventOriginKey) == RaftPlatformEventOriginShared) {
                        return@addObserverForName
                    }
                    RaftEnhancedNotifyCenter.post(
                        eventName = RaftDesktopShortcutContract.OpenEventName,
                        eventData = notification.toEventData(),
                        crossProcess = false,
                        source = RaftPlatformEventSourceIosHost
                    )
                }
        }
    }

    fun dispatch(event: RaftEnhancedNotifyEvent) {
        NSNotificationCenter.defaultCenter.postNotificationName(
            aName = event.eventName,
            `object` = null,
            userInfo = event.eventData.toIosUserInfo()
        )
        if (event.eventName != RaftEnhancedNotifyContract.EventName) {
            NSNotificationCenter.defaultCenter.postNotificationName(
                aName = RaftEnhancedNotifyContract.EventName,
                `object` = null,
                userInfo = event.toEnvelopeJson().toIosEnvelopeUserInfo(event.eventData.toRaftJsonString())
            )
        }
    }
}

private fun JsonObject.toIosUserInfo(): Map<Any?, Any?> =
    buildMap {
        put("stringify", toRaftJsonString())
        put(RaftPlatformEventOriginKey, RaftPlatformEventOriginShared)
        GeneratedRealtimeContract.PayloadKey.All.forEach { key ->
            optString(key).takeIf { it.isNotBlank() }?.let { value ->
                put(key, value)
            }
        }
    }

private fun JsonObject.toIosEnvelopeUserInfo(eventDataJson: String): Map<Any?, Any?> =
    mapOf(
        RaftEnhancedNotifyContract.KeyEventName to optString(RaftEnhancedNotifyContract.KeyEventName),
        RaftEnhancedNotifyContract.KeyData to eventDataJson,
        RaftEnhancedNotifyContract.KeyCrossProcess to optString(RaftEnhancedNotifyContract.KeyCrossProcess),
        RaftEnhancedNotifyContract.KeySource to optString(RaftEnhancedNotifyContract.KeySource),
        RaftPlatformEventOriginKey to RaftPlatformEventOriginShared,
        "stringify" to toRaftJsonString()
    )

private fun NSNotification.toEventData(): JsonObject {
    val serialized = userInfo?.get("stringify")?.toString()?.takeIf { it.isNotBlank() }
    if (serialized != null) {
        runCatching { parseRaftJsonObject(serialized) }.getOrNull()?.let { return it }
    }
    return buildJsonObject {
        userInfo.orEmpty().forEach { (rawKey, rawValue) ->
            val key = rawKey?.toString()?.takeIf { it.isNotBlank() } ?: return@forEach
            if (key != RaftPlatformEventOriginKey && rawValue != null) {
                put(key, rawValue.toString())
            }
        }
    }
}

internal actual fun createRaftPlatformEventBus(): RaftEventBus {
    IosRaftPlatformEventDispatcher.ensureNativeIngress()
    return RaftPlatformDispatchingEventBus(IosRaftPlatformEventDispatcher::dispatch)
}
