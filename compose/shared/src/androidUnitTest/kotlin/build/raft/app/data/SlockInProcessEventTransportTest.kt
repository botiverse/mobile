package build.raft.app.data

import build.raft.app.data.realtime.SlockRealtimeNotifyContract
import build.raft.app.optString
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject as KuiklyJSONObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * task #354 transport teeth: platform events travel through the in-process
 * hub as one immutable string — zero Binder, one serialization, envelope
 * spliced not re-encoded — and multi-MiB raw-ingest events are published
 * exactly once (no enhanced-envelope mirror, no second large allocation).
 */
class SlockInProcessEventTransportTest {

    private class RecordingListener : SlockKuiklyEventHub.Listener {
        val events = mutableListOf<Pair<String, String>>()

        override fun onEvent(eventName: String, payloadJson: String) {
            events += eventName to payloadJson
        }
    }

    private val logs = mutableListOf<String>()
    private val registered = mutableListOf<SlockKuiklyEventHub.Listener>()

    @BeforeTest
    fun setUp() {
        AndroidSlockPlatformEventDispatcher.installForTesting()
        AndroidSlockPlatformEventDispatcher.diagnosticsLogger = logs::add
        // Synchronous poster: dispatch() must not depend on a Looper here.
        AndroidSlockPlatformEventDispatcher.mainPoster = { runnable -> runnable.run() }
    }

    @AfterTest
    fun tearDown() {
        registered.forEach(SlockKuiklyEventHub::unregister)
        registered.clear()
    }

    private fun listen(): RecordingListener =
        RecordingListener().also {
            SlockKuiklyEventHub.register(it)
            registered += it
        }

    private fun event(
        eventName: String = "realtimeNotify",
        data: JsonObject = buildJsonObject { put("k", "v") },
        source: String = "android_test"
    ): SlockEnhancedNotifyEvent =
        SlockEnhancedNotifyEvent(eventName = eventName, eventData = data, source = source)

    @Test
    fun dispatchDeliversPayloadOnceAndEnvelopeOnce() {
        val listener = listen()

        AndroidSlockPlatformEventDispatcher.dispatch(event())

        assertEquals(2, listener.events.size, "one raw topic delivery plus one envelope delivery")
        val (rawName, rawPayload) = listener.events[0]
        assertEquals("realtimeNotify", rawName)
        assertEquals("v", KuiklyJSONObject(rawPayload).optString("k"))
        val (envelopeName, envelopePayload) = listener.events[1]
        assertEquals(SlockEnhancedNotifyContract.EventName, envelopeName)
        val envelope = KuiklyJSONObject(envelopePayload)
        assertEquals("realtimeNotify", envelope.optString(SlockEnhancedNotifyContract.KeyEventName))
        assertEquals("android_test", envelope.optString(SlockEnhancedNotifyContract.KeySource))
        assertEquals(
            "v",
            envelope.optJSONObject(SlockEnhancedNotifyContract.KeyData)?.optString("k"),
            "the spliced envelope must decode to the same data object"
        )
    }

    @Test
    fun everyListenerReceivesTheSameImmutableStringReference() {
        val first = listen()
        val second = listen()

        AndroidSlockPlatformEventDispatcher.dispatch(event())

        val firstPayload = first.events.first { it.first == "realtimeNotify" }.second
        val secondPayload = second.events.first { it.first == "realtimeNotify" }.second
        assertSame(firstPayload, secondPayload, "zero-copy: the hub must hand out one string reference")
    }

    @Test
    fun dispatchSiteLogCarriesMetadataButNeverThePayloadBody() {
        listen()
        val secret = "payload-body-marker-中文"
        AndroidSlockPlatformEventDispatcher.dispatch(
            event(data = buildJsonObject { put("field", secret) })
        )

        val dispatchLog = logs.single { it.startsWith("slock_platform_event_dispatch ") }
        assertTrue(dispatchLog.contains("eventName=realtimeNotify"))
        assertTrue(dispatchLog.contains("bytes="))
        assertTrue(!dispatchLog.contains(secret), "observability must not leak payload content")
    }

    @Test
    fun crashSizedRawIngestEventIsDeliveredIntactExactlyOnce() {
        val listener = listen()
        // The exact Hands 143077f5 magnitude: a payload whose serialized
        // form is ≥ the 1,628,916-byte crash parcel. ASCII filler →
        // bytes == length. Raw-ingest events must arrive intact — no drop,
        // no chunking — and exactly once: the multi-MiB enhanced-envelope
        // mirror would be a second large allocation.
        val filler = "a".repeat(1_628_916)
        AndroidSlockPlatformEventDispatcher.dispatch(
            event(
                eventName = SlockRealtimeNotifyContract.RawIngestEventName,
                data = buildJsonObject { put("rawPayload", filler) }
            )
        )

        assertEquals(1, listener.events.size, "raw ingest publishes exactly once — no envelope mirror")
        val (topic, payload) = listener.events.single()
        assertEquals(SlockRealtimeNotifyContract.RawIngestEventName, topic)
        assertTrue(payload.length >= 1_628_916, "the crash-sized event must be delivered intact")
    }

    @Test
    fun ordinarySignalsKeepTheEnvelopeMirrorButRawIngestDoesNot() {
        val listener = listen()

        AndroidSlockPlatformEventDispatcher.dispatch(event(eventName = "smallSignal"))
        AndroidSlockPlatformEventDispatcher.dispatch(
            event(eventName = SlockRealtimeNotifyContract.RawIngestEventName)
        )

        val topics = listener.events.map { it.first }
        assertEquals(
            listOf(
                "smallSignal",
                SlockEnhancedNotifyContract.EventName,
                SlockRealtimeNotifyContract.RawIngestEventName
            ),
            topics,
            "legacy signals mirror to the envelope; raw ingest never does"
        )
    }

    @Test
    fun unregisteredListenerStopsReceiving() {
        val listener = listen()
        AndroidSlockPlatformEventDispatcher.dispatch(event())
        val delivered = listener.events.size
        SlockKuiklyEventHub.unregister(listener)

        AndroidSlockPlatformEventDispatcher.dispatch(event())

        assertEquals(delivered, listener.events.size, "no delivery after unregister")
    }

    @Test
    fun reentrantUnregisterDuringDeliveryIsSafe() {
        lateinit var self: SlockKuiklyEventHub.Listener
        var received = 0
        self =
            SlockKuiklyEventHub.Listener { _, _ ->
                received += 1
                SlockKuiklyEventHub.unregister(self)
            }
        SlockKuiklyEventHub.register(self)
        val other = listen()

        AndroidSlockPlatformEventDispatcher.dispatch(event())

        assertEquals(1, received, "self-unregistering listener sees the event once")
        assertEquals(2, other.events.size, "remaining listeners are unaffected")
    }

    @Test
    fun utf8ByteCountMatchesRealEncoding() {
        listOf(
            "plain ascii",
            "中文测试",
            "emoji 😀🎉 mix",
            "" // empty
        ).forEach { sample ->
            assertEquals(
                sample.encodeToByteArray().size.toLong(),
                sample.utf8ByteCount(),
                "utf8ByteCount must match the real encoding for: $sample"
            )
        }
    }
}
