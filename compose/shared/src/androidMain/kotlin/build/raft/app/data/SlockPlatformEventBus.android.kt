package build.raft.app.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import build.raft.app.parseSlockJsonObject
import org.json.JSONObject as AndroidJSONObject

/**
 * Kept for call-site compatibility (SlockApplication). Android app-scope events are delivered
 * in-process by [SlockEnhancedNotifyCenter] only: the former platform fan-out
 * (`SlockKuiklyEventHub`, task #354) existed for Kuikly render views, whose listener is gone with
 * the CMP switch, and it serialized every event (multi-MiB realtime batches included) to JSON on
 * the main thread for nobody.
 */
@Suppress("UNUSED_PARAMETER")
fun installAndroidAppScopeEventBus(context: Context) = Unit

fun postAndroidAppScopeEvent(
    eventName: String,
    eventData: AndroidJSONObject,
    crossProcess: Boolean = false,
    source: String = "android"
) {
    val eventDataJson = eventData.toString()
    val post = {
        SlockEventBuses.appScope().post(
            eventName = eventName,
            eventData = parseSlockJsonObject(eventDataJson),
            crossProcess = crossProcess,
            source = source
        )
    }
    if (Looper.myLooper() == Looper.getMainLooper()) {
        post()
    } else {
        Handler(Looper.getMainLooper()).post { post() }
    }
}

/** In-process only (as on js/native): no platform listener consumes app-scope events on Android. */
internal actual fun createSlockPlatformEventBus(): SlockEventBus = SlockEnhancedNotifyCenter
