package build.raft.app.data

import build.raft.app.data.realtime.GeneratedRealtimeContract
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import platform.Foundation.NSNotificationCenter

private object IosSlockPlatformEventDispatcher {
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
                userInfo = event.toEnvelopeJson().toIosEnvelopeUserInfo(event.eventData.toString())
            )
        }
    }
}

private fun JSONObject.toIosUserInfo(): Map<Any?, Any?> =
    buildMap {
        put("stringify", toString())
        GeneratedRealtimeContract.PayloadKey.All.forEach { key ->
            optString(key).takeIf { it.isNotBlank() }?.let { value ->
                put(key, value)
            }
        }
    }

private fun JSONObject.toIosEnvelopeUserInfo(eventDataJson: String): Map<Any?, Any?> =
    mapOf(
        SlockEnhancedNotifyContract.KeyEventName to optString(SlockEnhancedNotifyContract.KeyEventName),
        SlockEnhancedNotifyContract.KeyData to eventDataJson,
        SlockEnhancedNotifyContract.KeyCrossProcess to optString(SlockEnhancedNotifyContract.KeyCrossProcess),
        SlockEnhancedNotifyContract.KeySource to optString(SlockEnhancedNotifyContract.KeySource),
        "stringify" to toString()
    )

internal actual fun createSlockPlatformEventBus(): SlockEventBus =
    SlockPlatformDispatchingEventBus(IosSlockPlatformEventDispatcher::dispatch)
