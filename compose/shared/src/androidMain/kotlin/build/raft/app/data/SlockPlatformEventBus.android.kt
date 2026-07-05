package build.raft.app.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject as KuiklyJSONObject
import com.tencent.kuikly.core.render.android.expand.module.sendKuiklyEvent
import org.json.JSONObject as AndroidJSONObject

private object AndroidSlockPlatformEventDispatcher {
    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    fun dispatch(event: SlockEnhancedNotifyEvent) {
        val context = appContext ?: return
        Handler(Looper.getMainLooper()).post {
            context.sendKuiklyEvent(event.eventName, AndroidJSONObject(event.eventData.toString()))
            if (event.eventName != SlockEnhancedNotifyContract.EventName) {
                context.sendKuiklyEvent(
                    SlockEnhancedNotifyContract.EventName,
                    AndroidJSONObject(event.toEnvelopeJson().toString())
                )
            }
        }
    }
}

fun installAndroidAppScopeEventBus(context: Context) {
    AndroidSlockPlatformEventDispatcher.install(context)
}

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
            eventData = KuiklyJSONObject(eventDataJson),
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

internal actual fun createSlockPlatformEventBus(): SlockEventBus =
    SlockPlatformDispatchingEventBus(AndroidSlockPlatformEventDispatcher::dispatch)
