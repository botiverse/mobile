package build.raft.app.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import build.raft.app.data.realtime.SlockRealtimeNotifyContract
import build.raft.app.optString
import build.raft.app.parseSlockJsonObject
import build.raft.app.toSlockJsonString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject as AndroidJSONObject

private const val EVENT_LOG_TAG = "SlockPlatformEvent"

internal object AndroidSlockPlatformEventDispatcher {
    private var installed = false

    /** Test seam: production logs through android.util.Log. */
    internal var diagnosticsLogger: (String) -> Unit = { message -> Log.w(EVENT_LOG_TAG, message) }

    /** Test seam: production posts to the main looper. */
    internal var mainPoster: (Runnable) -> Unit = { runnable ->
        Handler(Looper.getMainLooper()).post(runnable)
    }

    @Suppress("UNUSED_PARAMETER")
    fun install(context: Context) {
        // Context is kept for call-site compatibility; the hub needs none.
        installed = true
    }

    /** Test seam: the JVM unit harness has no Context. */
    internal fun installForTesting() {
        installed = true
    }

    fun dispatch(event: SlockEnhancedNotifyEvent) {
        // Keep the historical install gate so events cannot fan out before
        // the app shell is ready.
        if (!installed) return
        mainPoster {
            // task #354: events travel in-process through SlockKuiklyEventHub
            // instead of Kuikly's system broadcast — the app is
            // single-process, and the broadcast's Binder parcel crashed on a
            // 1,628,916-byte realtime event (TransactionTooLargeException,
            // Hands 143077f5). One serialization: the same immutable string
            // is handed to every listener and spliced (not re-encoded) into
            // the envelope.
            val payload = event.eventData.toSlockJsonString()
            diagnosticsLogger(event.dispatchDiagnostics(payload.utf8ByteCount()))
            SlockKuiklyEventHub.publish(event.eventName, payload)
            // Raw-ingest events (the potentially multi-MiB realtime fanout)
            // get exactly one publish to the single AppShell ingest owner —
            // no enhanced-envelope mirror, no second multi-MiB allocation.
            // Small legacy/lifecycle events keep the envelope mirror for the
            // existing enhanced-notify consumers.
            val isRawIngest =
                event.eventName == SlockRealtimeNotifyContract.RawIngestEventName
            if (!isRawIngest && event.eventName != SlockEnhancedNotifyContract.EventName) {
                SlockKuiklyEventHub.publish(
                    SlockEnhancedNotifyContract.EventName,
                    event.envelopeJsonAround(payload)
                )
            }
        }
    }
}

/**
 * Builds the SlockEnhancedNotify envelope around the already-serialized
 * payload without re-encoding it: the wrapper (eventName/crossProcess/source)
 * is serialized once, then the payload string — itself valid JSON — is
 * spliced in as the `data` value.
 */
internal fun SlockEnhancedNotifyEvent.envelopeJsonAround(payload: String): String {
    val wrapper =
        buildJsonObject {
            put(SlockEnhancedNotifyContract.KeyEventName, eventName)
            put(SlockEnhancedNotifyContract.KeyCrossProcess, crossProcess)
            put(SlockEnhancedNotifyContract.KeySource, source)
        }.toSlockJsonString()
    return wrapper.dropLast(1) +
        ",\"" + SlockEnhancedNotifyContract.KeyData + "\":" + payload + "}"
}

/**
 * Dispatch-site observability (task #354): eventName, exact UTF-8 size and
 * non-identifying correlation hashes — never the payload body. This is the
 * log line whose absence left the 1.6 MB crash event unidentifiable.
 */
internal fun SlockEnhancedNotifyEvent.dispatchDiagnostics(payloadBytes: Long): String {
    val serverId = eventData.optString(SlockRealtimeNotifyContract.KeyServerId)
    val scopeLeaseId = eventData.optString(SlockRealtimeNotifyContract.KeyAuthorityScopeLeaseId)
    val innerEventName = eventData.optString(SlockRealtimeNotifyContract.KeyEventName)
    return "slock_platform_event_dispatch eventName=$eventName bytes=$payloadBytes " +
        "innerEventName=${innerEventName.ifEmpty { "-" }} " +
        "serverIdHash=${if (serverId.isEmpty()) "-" else serverId.hashCode().toString(16)} " +
        "witnessHash=${if (scopeLeaseId.isEmpty()) "-" else scopeLeaseId.hashCode().toString(16)} " +
        "source=$source"
}

internal fun String.utf8ByteCount(): Long {
    var bytes = 0L
    var index = 0
    while (index < length) {
        val code = this[index].code
        bytes +=
            when {
                code < 0x80 -> 1L
                code < 0x800 -> 2L
                code in 0xD800..0xDBFF -> {
                    // Surrogate pair → one 4-byte UTF-8 sequence.
                    index += 1
                    4L
                }
                else -> 3L
            }
        index += 1
    }
    return bytes
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

internal actual fun createSlockPlatformEventBus(): SlockEventBus =
    SlockPlatformDispatchingEventBus(AndroidSlockPlatformEventDispatcher::dispatch)
