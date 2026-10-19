package build.raft.app.data

import build.raft.app.data.realtime.GeneratedRealtimeContract
import build.raft.app.optString
import build.raft.app.parseSlockJsonObject
import build.raft.app.toSlockJsonString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.darwin.NSObjectProtocol

private const val SlockPlatformEventOriginKey = "slockPlatformEventOrigin"
private const val SlockPlatformEventOriginShared = "shared"
private const val SlockPlatformEventSourceIosHost = "ios_host"

private object IosSlockPlatformEventDispatcher {
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
                    if (notification.userInfo?.get(SlockPlatformEventOriginKey) == SlockPlatformEventOriginShared) {
                        return@addObserverForName
                    }
                    SlockEnhancedNotifyCenter.post(
                        eventName = GeneratedRealtimeContract.Notify.EventName,
                        eventData = notification.toEventData(),
                        crossProcess = true,
                        source = SlockPlatformEventSourceIosHost
                    )
                }
        }
        if (systemNotificationOpenObserver == null) {
            systemNotificationOpenObserver =
                NSNotificationCenter.defaultCenter.addObserverForName(
                    name = SlockSystemNotificationContract.OpenEventName,
                    `object` = null,
                    queue = null
                ) { notification ->
                    notification ?: return@addObserverForName
                    if (notification.userInfo?.get(SlockPlatformEventOriginKey) == SlockPlatformEventOriginShared) {
                        return@addObserverForName
                    }
                    SlockEnhancedNotifyCenter.post(
                        eventName = SlockSystemNotificationContract.OpenEventName,
                        eventData = notification.toEventData(),
                        crossProcess = false,
                        source = SlockPlatformEventSourceIosHost
                    )
                }
        }
        if (desktopShortcutOpenObserver == null) {
            desktopShortcutOpenObserver =
                NSNotificationCenter.defaultCenter.addObserverForName(
                    name = SlockDesktopShortcutContract.OpenEventName,
                    `object` = null,
                    queue = null
                ) { notification ->
                    notification ?: return@addObserverForName
                    if (notification.userInfo?.get(SlockPlatformEventOriginKey) == SlockPlatformEventOriginShared) {
                        return@addObserverForName
                    }
                    SlockEnhancedNotifyCenter.post(
                        eventName = SlockDesktopShortcutContract.OpenEventName,
                        eventData = notification.toEventData(),
                        crossProcess = false,
                        source = SlockPlatformEventSourceIosHost
                    )
                }
        }
    }

    fun dispatch(event: SlockEnhancedNotifyEvent) {
        NSNotificationCenter.defaultCenter.postNotificationName(
            aName = event.eventName,
            `object` = null,
            userInfo = event.eventData.toIosUserInfo()
        )
        if (event.eventName != SlockEnhancedNotifyContract.EventName) {
            NSNotificationCenter.defaultCenter.postNotificationName(
                aName = SlockEnhancedNotifyContract.EventName,
                `object` = null,
                userInfo = event.toEnvelopeJson().toIosEnvelopeUserInfo(event.eventData.toSlockJsonString())
            )
        }
    }
}

private fun JsonObject.toIosUserInfo(): Map<Any?, Any?> =
    buildMap {
        put("stringify", toSlockJsonString())
        put(SlockPlatformEventOriginKey, SlockPlatformEventOriginShared)
        GeneratedRealtimeContract.PayloadKey.All.forEach { key ->
            optString(key).takeIf { it.isNotBlank() }?.let { value ->
                put(key, value)
            }
        }
    }

private fun JsonObject.toIosEnvelopeUserInfo(eventDataJson: String): Map<Any?, Any?> =
    mapOf(
        SlockEnhancedNotifyContract.KeyEventName to optString(SlockEnhancedNotifyContract.KeyEventName),
        SlockEnhancedNotifyContract.KeyData to eventDataJson,
        SlockEnhancedNotifyContract.KeyCrossProcess to optString(SlockEnhancedNotifyContract.KeyCrossProcess),
        SlockEnhancedNotifyContract.KeySource to optString(SlockEnhancedNotifyContract.KeySource),
        SlockPlatformEventOriginKey to SlockPlatformEventOriginShared,
        "stringify" to toSlockJsonString()
    )

private fun NSNotification.toEventData(): JsonObject {
    val serialized = userInfo?.get("stringify")?.toString()?.takeIf { it.isNotBlank() }
    if (serialized != null) {
        runCatching { parseSlockJsonObject(serialized) }.getOrNull()?.let { return it }
    }
    return buildJsonObject {
        userInfo.orEmpty().forEach { (rawKey, rawValue) ->
            val key = rawKey?.toString()?.takeIf { it.isNotBlank() } ?: return@forEach
            if (key != SlockPlatformEventOriginKey && rawValue != null) {
                put(key, rawValue.toString())
            }
        }
    }
}

internal actual fun createSlockPlatformEventBus(): SlockEventBus {
    IosSlockPlatformEventDispatcher.ensureNativeIngress()
    return SlockPlatformDispatchingEventBus(IosSlockPlatformEventDispatcher::dispatch)
}
