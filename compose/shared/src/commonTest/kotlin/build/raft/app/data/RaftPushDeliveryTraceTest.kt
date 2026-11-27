package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MessageId
import build.raft.app.model.NotificationPushReceived
import build.raft.app.model.ServerId
import build.raft.app.model.raftEpochMillisUtcIso
import build.raft.app.shell.raftNotificationPushBannerOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

@OptIn(RaftCoreInternalApi::class, ExperimentalCoroutinesApi::class)
class RaftPushDeliveryTraceTest {
    private class FakePreferenceStore : SharedPreferenceStore {
        private val values = mutableMapOf<String, String>()

        override fun getString(key: String): String? = values[key]

        override fun putString(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private data class EmittedEvent(
        val serverId: String,
        val name: String,
        val attrs: JsonObject,
        val occurredAtMs: Long,
    )

    private fun testUuid(index: Int): String {
        val hex = index.toString(16).padStart(12, '0')
        return "550e8400-e29b-41d4-a716-$hex"
    }

    @Test
    fun uuidValidationNormalizesValidAndIgnoresInvalid() {
        assertEquals(
            "550e8400-e29b-41d4-a716-446655440000",
            normalizePushDeliveryId("  550E8400-E29B-41D4-A716-446655440000  "),
        )
        assertNull(normalizePushDeliveryId(null))
        assertNull(normalizePushDeliveryId(""))
        assertNull(normalizePushDeliveryId("   "))
        assertNull(normalizePushDeliveryId("not-a-uuid"))
        assertNull(normalizePushDeliveryId("550e8400-e29b-41d4-a716-44665544000"))
        assertNull(normalizePushDeliveryId("550e8400-e29b-41d4-a716-4466554400000"))
        assertNull(normalizePushDeliveryId("550e8400-e29b-41d4-a716-44665544000g"))

        val store = FakePreferenceStore()
        val emitted = mutableListOf<EmittedEvent>()
        val recorder =
            PushDeliveryTraceRecorder(
                preferenceStore = store,
                nowEpochMillis = { 1_700_000_000_000L },
                privacyMode = MutableStateFlow(RaftPrivacyMode.Off),
                isForeground = { true },
                isTransportReady = { true },
                deviceContextProvider = {
                    RaftPushDeliveryDeviceContext("google", "35", gmsAvailable = true, dndActive = false)
                },
                emit = { s, n, a, t -> emitted += EmittedEvent(s, n, a, t) },
            )
        recorder.onSignedIn("user-1", "server-1")

        assertFalse(
            recorder.recordReceived(
                deliveryId = null,
                serverId = "server-1",
                transport = RaftPushDeliveryTransport.Fcm,
                appState = RaftPushDeliveryAppState.Background,
                category = RaftPushDeliveryCategory.Dm,
            )
        )
        assertFalse(
            recorder.recordPresented(
                deliveryId = "bad-id",
                serverId = "server-1",
                transport = RaftPushDeliveryTransport.Fcm,
                appState = RaftPushDeliveryAppState.Background,
                presented = true,
            )
        )
        assertFalse(
            recorder.recordOpened(
                deliveryId = "",
                serverId = "server-1",
                transport = RaftPushDeliveryTransport.Fcm,
                appState = RaftPushDeliveryAppState.Background,
                openSurface = RaftPushDeliveryOpenSurface.SystemTray,
            )
        )
        assertEquals(0, emitted.size)
        assertEquals(0, recorder.bufferedCountForTest())
    }

    @Test
    fun manufacturerNormalizationCoversAllEighteenOemsAndSubBrands() {
        assertEquals(18, RAFT_PUSH_DELIVERY_MANUFACTURERS.size)
        val directCases =
            listOf(
                "Apple" to "apple",
                "HUAWEI" to "huawei",
                "HONOR" to "honor",
                "Xiaomi" to "xiaomi",
                "Redmi" to "redmi",
                "OPPO" to "oppo",
                "OnePlus" to "oneplus",
                "realme" to "realme",
                "vivo" to "vivo",
                "iQOO" to "iqoo",
                "samsung" to "samsung",
                "Google" to "google",
                "Meizu" to "meizu",
                "ZTE" to "zte",
                "nubia" to "nubia",
                "motorola" to "motorola",
                "Sony" to "sony",
                "Nothing" to "other",
                "" to "other",
            )
        for ((raw, expected) in directCases) {
            assertEquals(expected, normalizePushDeliveryManufacturer(raw))
            assertTrue(expected in RAFT_PUSH_DELIVERY_MANUFACTURERS)
        }
        assertEquals("other", normalizePushDeliveryManufacturer(null, null))
        assertEquals("motorola", normalizePushDeliveryManufacturer("Motorola Mobility LLC"))
        assertEquals("sony", normalizePushDeliveryManufacturer("Sony Corporation"))

        // Android Build.BRAND sub-brand overrides parent Build.MANUFACTURER.
        assertEquals("redmi", normalizePushDeliveryManufacturer("Xiaomi", "Redmi"))
        assertEquals("honor", normalizePushDeliveryManufacturer("HUAWEI", "HONOR"))
        assertEquals("iqoo", normalizePushDeliveryManufacturer("vivo", "iQOO"))
        assertEquals("realme", normalizePushDeliveryManufacturer("OPPO", "realme"))
        assertEquals("oneplus", normalizePushDeliveryManufacturer("OPPO", "OnePlus"))
        assertEquals("nubia", normalizePushDeliveryManufacturer("ZTE", "nubia"))
        // Sub-brand not in allowlist falls back to parent manufacturer.
        assertEquals("xiaomi", normalizePushDeliveryManufacturer("Xiaomi", "POCO"))
    }

    @Test
    fun closedEnumsNormalizeWireValuesAndFallbacks() {
        // Transport closed enum uses mobile_socket (never socket).
        assertEquals("fcm", RaftPushDeliveryTransport.Fcm.wire)
        assertEquals("apns", RaftPushDeliveryTransport.Apns.wire)
        assertEquals("mobile_socket", RaftPushDeliveryTransport.MobileSocket.wire)
        assertEquals(RaftPushDeliveryTransport.MobileSocket, RaftPushDeliveryTransport.fromWire("mobile_socket"))
        assertNull(RaftPushDeliveryTransport.fromWire("socket"))

        // Category mapping + unknown -> other.
        assertEquals(RaftPushDeliveryCategory.Dm, normalizePushDeliveryCategory("dm"))
        assertEquals(RaftPushDeliveryCategory.Dm, normalizePushDeliveryCategory("direct_message"))
        assertEquals(RaftPushDeliveryCategory.Mention, normalizePushDeliveryCategory("mention"))
        assertEquals(RaftPushDeliveryCategory.Channel, normalizePushDeliveryCategory("channel"))
        assertEquals(RaftPushDeliveryCategory.Channel, normalizePushDeliveryCategory("channel_unread"))
        assertEquals(RaftPushDeliveryCategory.Thread, normalizePushDeliveryCategory("thread"))
        assertEquals(RaftPushDeliveryCategory.Task, normalizePushDeliveryCategory("task_assigned"))
        assertEquals(RaftPushDeliveryCategory.Reminder, normalizePushDeliveryCategory("reminder"))
        assertEquals(RaftPushDeliveryCategory.SystemNotice, normalizePushDeliveryCategory("system_notice"))
        assertEquals(RaftPushDeliveryCategory.Test, normalizePushDeliveryCategory("test"))
        assertEquals(RaftPushDeliveryCategory.Other, normalizePushDeliveryCategory("brand_new_category"))
        // Legacy fallback when category missing.
        assertEquals(RaftPushDeliveryCategory.Mention, normalizePushDeliveryCategory(null, kind = "channel", mentioned = true))
        assertEquals(RaftPushDeliveryCategory.Dm, normalizePushDeliveryCategory(null, kind = "dm"))
        assertEquals(RaftPushDeliveryCategory.Thread, normalizePushDeliveryCategory(null, kind = "thread"))
        assertEquals(RaftPushDeliveryCategory.Channel, normalizePushDeliveryCategory(null, kind = "channel"))
        assertEquals(RaftPushDeliveryCategory.Other, normalizePushDeliveryCategory(null, kind = null))

        // Suppress reason closed enum (all 12 values).
        assertEquals(12, RaftPushDeliverySuppressReason.entries.size)
        for (reason in RaftPushDeliverySuppressReason.entries) {
            assertEquals(reason, RaftPushDeliverySuppressReason.fromWire(reason.wire))
        }
        assertEquals(RaftPushDeliverySuppressReason.Unknown, RaftPushDeliverySuppressReason.fromWire("surprise"))

        // OS version formatting.
        assertEquals("18.4", formatIosPushDeliveryOsVersion(18, 4))
        assertEquals("35", normalizePushDeliveryOsVersion("35"))
        assertEquals("18.4", normalizePushDeliveryOsVersion("18.4.1"))
        assertEquals("unknown", normalizePushDeliveryOsVersion("   "))
    }

    @Test
    fun bufferPersistsAcrossProcessRestartCapsAt100ExpiresAfter48HoursAndReportsDroppedCountOnce() {
        val store = FakePreferenceStore()
        var nowMs = 1_700_000_000_000L
        val privacyMode = MutableStateFlow(RaftPrivacyMode.Unknown)
        var foreground = false
        var transportReady = false
        val emitted = mutableListOf<EmittedEvent>()

        // Process 1: background FCM wakes process before UI/transport/privacy exist.
        val backgroundRecorder =
            PushDeliveryTraceRecorder(
                preferenceStore = store,
                nowEpochMillis = { nowMs },
                privacyMode = privacyMode,
                isForeground = { foreground },
                isTransportReady = { transportReady },
                deviceContextProvider = {
                    RaftPushDeliveryDeviceContext("xiaomi", "35", gmsAvailable = true, dndActive = true)
                },
                emit = { s, n, a, t -> emitted += EmittedEvent(s, n, a, t) },
            )
        backgroundRecorder.setKnownAccountHint("user-a", "server-1")

        // Record 105 events while backgrounded -> oldest 5 evicted, droppedCount == 5, 0 emitted.
        for (i in 1..105) {
            nowMs += 1_000L
            assertTrue(
                backgroundRecorder.recordReceived(
                    deliveryId = testUuid(i),
                    serverId = "server-1",
                    transport = RaftPushDeliveryTransport.Fcm,
                    appState = RaftPushDeliveryAppState.Background,
                    category = RaftPushDeliveryCategory.Dm,
                )
            )
        }
        assertEquals(0, emitted.size)
        assertEquals(RAFT_PUSH_DELIVERY_BUFFER_MAX_EVENTS, backgroundRecorder.bufferedCountForTest())
        assertEquals(5, backgroundRecorder.droppedCountForTest())

        // Simulate process kill & relaunch 48h later so the oldest 3 kept events (6, 7, 8) expire
        // while 9..105 stay within TTL.
        val firstKeptOccurredAt = 1_700_000_000_000L + 6_000L
        nowMs = firstKeptOccurredAt + RAFT_PUSH_DELIVERY_BUFFER_TTL_MILLIS + 2_500L // 6, 7, 8 expired (>48h)

        val relaunchedRecorder =
            PushDeliveryTraceRecorder(
                preferenceStore = store,
                nowEpochMillis = { nowMs },
                privacyMode = privacyMode,
                isForeground = { foreground },
                isTransportReady = { transportReady },
                deviceContextProvider = {
                    RaftPushDeliveryDeviceContext("xiaomi", "35", gmsAvailable = true, dndActive = false)
                },
                emit = { s, n, a, t -> emitted += EmittedEvent(s, n, a, t) },
            )
        // Still backgrounded & privacy Unknown -> signing in must NOT drain.
        relaunchedRecorder.onSignedIn("user-a", "server-1")
        assertEquals(0, emitted.size)

        // App enters foreground, transport binds, privacy resolves Off -> drains!
        foreground = true
        transportReady = true
        privacyMode.value = RaftPrivacyMode.Off
        relaunchedRecorder.onPrivacyModeChanged(RaftPrivacyMode.Off)

        // 100 kept - 3 expired = 97 drained; total dropped = 5 (cap) + 3 (TTL) = 8.
        assertEquals(97, emitted.size)
        assertEquals(0, relaunchedRecorder.bufferedCountForTest())
        assertEquals(0, relaunchedRecorder.droppedCountForTest())

        val first = emitted.first()
        assertEquals("server-1", first.serverId)
        assertEquals(RAFT_PUSH_DELIVERY_EVENT_RECEIVED, first.name)
        assertEquals(testUuid(9), first.attrs["delivery_id"]?.jsonPrimitive?.content)
        assertEquals("fcm", first.attrs["transport"]?.jsonPrimitive?.content)
        assertEquals("xiaomi", first.attrs["manufacturer"]?.jsonPrimitive?.content)
        assertEquals("35", first.attrs["os_version"]?.jsonPrimitive?.content)
        assertEquals("background", first.attrs["app_state"]?.jsonPrimitive?.content)
        assertEquals(true, first.attrs["gms_available"]?.jsonPrimitive?.boolean)
        assertEquals("dm", first.attrs["push_category"]?.jsonPrimitive?.content)
        assertEquals(1_700_000_000_000L + 9_000L, first.occurredAtMs)
        assertEquals(1_700_000_000_000L + 9_000L, first.attrs["occurred_at_ms"]?.jsonPrimitive?.long)
        assertEquals(nowMs, first.attrs["reported_at_ms"]?.jsonPrimitive?.long)
        assertEquals(8, first.attrs["buffer_dropped_count"]?.jsonPrimitive?.int)

        // Second drained event omits buffer_dropped_count.
        assertNull(emitted[1].attrs["buffer_dropped_count"])

        // Next event after handoff also omits buffer_dropped_count (reset to 0).
        nowMs += 500L
        relaunchedRecorder.recordOpened(
            deliveryId = testUuid(9),
            serverId = "server-1",
            transport = RaftPushDeliveryTransport.Fcm,
            appState = RaftPushDeliveryAppState.ColdStart,
            openSurface = RaftPushDeliveryOpenSurface.SystemTray,
        )
        assertEquals(98, emitted.size)
        val opened = emitted.last()
        assertEquals(RAFT_PUSH_DELIVERY_EVENT_OPENED, opened.name)
        assertEquals("system_tray", opened.attrs["open_surface"]?.jsonPrimitive?.content)
        assertEquals("cold_start", opened.attrs["app_state"]?.jsonPrimitive?.content)
        assertNull(opened.attrs["buffer_dropped_count"])
        // Privacy check: no PII / content / identifiers.
        for (forbidden in listOf("body", "title", "content", "channel_id", "channelId", "sender_id", "senderId", "device_token")) {
            assertFalse(forbidden in opened.attrs, "forbidden key $forbidden must not be present")
        }
    }

    @Test
    fun signOutAccountSwitchAndPrivacyModeOnClearBufferAndResetDroppedCount() {
        val store = FakePreferenceStore()
        var nowMs = 1_700_000_000_000L
        val privacyMode = MutableStateFlow(RaftPrivacyMode.Unknown)
        var foreground = false
        var transportReady = true
        val emitted = mutableListOf<EmittedEvent>()

        fun makeRecorder(): PushDeliveryTraceRecorder =
            PushDeliveryTraceRecorder(
                preferenceStore = store,
                nowEpochMillis = { nowMs },
                privacyMode = privacyMode,
                isForeground = { foreground },
                isTransportReady = { transportReady },
                deviceContextProvider = {
                    RaftPushDeliveryDeviceContext("samsung", "34", gmsAvailable = true, dndActive = false)
                },
                emit = { s, n, a, t -> emitted += EmittedEvent(s, n, a, t) },
                maxEvents = 2,
            )

        // 1. Account switch A -> B wipes A's buffered events & dropped count.
        val r1 = makeRecorder()
        r1.onSignedIn("user-a", "s1")
        for (i in 1..4) {
            r1.recordReceived(testUuid(i), "s1", RaftPushDeliveryTransport.Fcm, RaftPushDeliveryAppState.Background, RaftPushDeliveryCategory.Dm)
        }
        assertEquals(2, r1.bufferedCountForTest())
        assertEquals(2, r1.droppedCountForTest())

        r1.onSignedIn("user-b", "s2")
        assertEquals(0, r1.bufferedCountForTest())
        assertEquals(0, r1.droppedCountForTest())

        // 2. Sign-out wipes buffered events & dropped count.
        for (i in 10..13) {
            r1.recordReceived(testUuid(i), "s2", RaftPushDeliveryTransport.Fcm, RaftPushDeliveryAppState.Background, RaftPushDeliveryCategory.Mention)
        }
        assertEquals(2, r1.bufferedCountForTest())
        assertEquals(2, r1.droppedCountForTest())
        r1.onSignedOut()
        assertEquals(0, r1.bufferedCountForTest())
        assertEquals(0, r1.droppedCountForTest())

        // 3. Privacy mode On wipes buffered events & refuses new records.
        r1.onSignedIn("user-b", "s2")
        r1.recordReceived(testUuid(20), "s2", RaftPushDeliveryTransport.Fcm, RaftPushDeliveryAppState.Background, RaftPushDeliveryCategory.Channel)
        assertEquals(1, r1.bufferedCountForTest())
        privacyMode.value = RaftPrivacyMode.On
        r1.onPrivacyModeChanged(RaftPrivacyMode.On)
        assertEquals(0, r1.bufferedCountForTest())
        assertFalse(
            r1.recordReceived(testUuid(21), "s2", RaftPushDeliveryTransport.Fcm, RaftPushDeliveryAppState.Foreground, RaftPushDeliveryCategory.Dm)
        )
        foreground = true
        assertEquals(0, r1.drainIfReady())
        assertEquals(0, emitted.size)
    }

    @Test
    fun iosDeliveredCenterBackfillDeduplicatesAlreadyReportedIdsAndOmitsAndroidOnlyAttrs() {
        val store = FakePreferenceStore()
        var nowMs = 1_700_000_050_000L
        val privacyMode = MutableStateFlow(RaftPrivacyMode.Off)
        val emitted = mutableListOf<EmittedEvent>()

        fun makeIosRecorder(): PushDeliveryTraceRecorder =
            PushDeliveryTraceRecorder(
                preferenceStore = store,
                nowEpochMillis = { nowMs },
                privacyMode = privacyMode,
                isForeground = { true },
                isTransportReady = { true },
                deviceContextProvider = {
                    RaftPushDeliveryDeviceContext(
                        manufacturer = "apple",
                        osVersion = "18.4",
                        gmsAvailable = null,
                        dndActive = null,
                    )
                },
                emit = { s, n, a, t -> emitted += EmittedEvent(s, n, a, t) },
            )

        val r1 = makeIosRecorder()
        r1.onSignedIn("ios-user", "server-ios")

        val foregroundId = testUuid(101)
        val backgroundDeliveredId = testUuid(102)
        val deliveredDateMs = 1_700_000_010_000L

        // Already reported via willPresent (foreground suppressed).
        assertTrue(
            r1.recordPresented(
                deliveryId = foregroundId,
                serverId = "server-ios",
                transport = RaftPushDeliveryTransport.Apns,
                appState = RaftPushDeliveryAppState.Foreground,
                presented = false,
                suppressReason = RaftPushDeliverySuppressReason.ForegroundSuppressed,
            )
        )
        assertEquals(1, emitted.size)
        assertEquals(false, emitted[0].attrs["presented"]?.jsonPrimitive?.boolean)
        assertEquals("foreground_suppressed", emitted[0].attrs["suppress_reason"]?.jsonPrimitive?.content)
        assertNull(emitted[0].attrs["inferred_from_center"])
        assertNull(emitted[0].attrs["gms_available"])
        assertNull(emitted[0].attrs["dnd_active"])

        // Foreground center sweep skips foregroundId and reports backgroundDeliveredId once.
        assertFalse(
            r1.recordPresentedIfNotAlreadyReported(
                deliveryId = foregroundId,
                serverId = "server-ios",
                transport = RaftPushDeliveryTransport.Apns,
                appState = RaftPushDeliveryAppState.Background,
                inferredFromCenter = true,
                occurredAtMs = deliveredDateMs,
            )
        )
        assertTrue(
            r1.recordPresentedIfNotAlreadyReported(
                deliveryId = backgroundDeliveredId,
                serverId = "server-ios",
                transport = RaftPushDeliveryTransport.Apns,
                appState = RaftPushDeliveryAppState.Background,
                inferredFromCenter = true,
                occurredAtMs = deliveredDateMs,
            )
        )
        assertEquals(2, emitted.size)
        val backfilled = emitted[1]
        assertEquals(RAFT_PUSH_DELIVERY_EVENT_PRESENTED, backfilled.name)
        assertEquals(backgroundDeliveredId, backfilled.attrs["delivery_id"]?.jsonPrimitive?.content)
        assertEquals("apns", backfilled.attrs["transport"]?.jsonPrimitive?.content)
        assertEquals("apple", backfilled.attrs["manufacturer"]?.jsonPrimitive?.content)
        assertEquals("18.4", backfilled.attrs["os_version"]?.jsonPrimitive?.content)
        assertEquals("background", backfilled.attrs["app_state"]?.jsonPrimitive?.content)
        assertEquals(true, backfilled.attrs["presented"]?.jsonPrimitive?.boolean)
        assertEquals(true, backfilled.attrs["inferred_from_center"]?.jsonPrimitive?.boolean)
        assertNull(backfilled.attrs["suppress_reason"])
        assertNull(backfilled.attrs["gms_available"])
        assertNull(backfilled.attrs["dnd_active"])
        assertEquals(deliveredDateMs, backfilled.occurredAtMs)
        assertEquals(deliveredDateMs, backfilled.attrs["occurred_at_ms"]?.jsonPrimitive?.long)
        assertEquals(nowMs, backfilled.attrs["reported_at_ms"]?.jsonPrimitive?.long)

        // Subsequent launch still remembers backgroundDeliveredId was reported.
        val r2 = makeIosRecorder()
        r2.onSignedIn("ios-user", "server-ios")
        assertTrue(r2.isPresentedAlreadyReported(backgroundDeliveredId))
        assertFalse(
            r2.recordPresentedIfNotAlreadyReported(
                deliveryId = backgroundDeliveredId,
                serverId = "server-ios",
                transport = RaftPushDeliveryTransport.Apns,
                appState = RaftPushDeliveryAppState.Background,
                inferredFromCenter = true,
                occurredAtMs = deliveredDateMs,
            )
        )
        assertEquals(2, emitted.size)
    }

    @Test
    fun openedDeduplicatesSameDeliveryIdAndForegroundBannerPreservesNormalizedDeliveryId() {
        val store = FakePreferenceStore()
        val emitted = mutableListOf<EmittedEvent>()
        val recorder =
            PushDeliveryTraceRecorder(
                preferenceStore = store,
                nowEpochMillis = { 1_700_000_000_000L },
                privacyMode = MutableStateFlow(RaftPrivacyMode.Off),
                isForeground = { true },
                isTransportReady = { true },
                deviceContextProvider = {
                    RaftPushDeliveryDeviceContext("google", "35", gmsAvailable = true, dndActive = false)
                },
                emit = { s, n, a, t -> emitted += EmittedEvent(s, n, a, t) },
            )
        recorder.onSignedIn("u1", "s1")

        val id = testUuid(301)
        assertTrue(
            recorder.recordOpened(
                deliveryId = id,
                serverId = "s1",
                transport = RaftPushDeliveryTransport.MobileSocket,
                appState = RaftPushDeliveryAppState.Foreground,
                openSurface = RaftPushDeliveryOpenSurface.InAppBanner,
            )
        )
        assertFalse(
            recorder.recordOpened(
                deliveryId = id,
                serverId = "s1",
                transport = RaftPushDeliveryTransport.MobileSocket,
                appState = RaftPushDeliveryAppState.Foreground,
                openSurface = RaftPushDeliveryOpenSurface.InAppBanner,
            )
        )
        assertEquals(1, emitted.size)
        assertEquals("in_app_banner", emitted[0].attrs["open_surface"]?.jsonPrimitive?.content)

        val banner =
            raftNotificationPushBannerOrNull(
                event =
                    NotificationPushReceived(
                        serverId = ServerId("s1"),
                        kind = ConversationKind.DirectMessage,
                        channelId = ChannelId("dm-1"),
                        messageId = MessageId("m-1"),
                        title = "Ada",
                        body = "Hi",
                        tag = "s1:dm-1",
                        deliveryId = id.uppercase(),
                    ),
                currentServerId = "s1",
                visibleConversationKey = null,
            )
        assertNotNull(banner)
        assertEquals(id, banner.deliveryId)
    }

    @Test
    fun clientTraceSenderPreservesOccurredAtMillisAsRecordTime() = runTest {
        val privacyMode = MutableStateFlow(RaftPrivacyMode.Off)
        val foreground = MutableStateFlow(true)
        val sentBatches = mutableListOf<RaftWebTraceBatchRequest>()
        val occurredAtMs = 1_700_000_010_000L
        val uploadTimeMs = 1_700_000_999_000L

        val sender =
            RaftClientTraceEventSender(
                scope = backgroundScope,
                nowEpochMillis = { uploadTimeMs },
                defaultAttrs = { mapOf("clientPlatform" to "android", "appVersion" to "1.31.0") },
                flushDelayMillis = 100L,
                privacyMode = privacyMode,
                foreground = foreground,
            )
        sender.bindTransport(
            object : RaftClientTraceTransport {
                override suspend fun currentAccount(): String = "user-1"

                override suspend fun attest(serverId: ServerId): RaftScopeAttestationAnswer =
                    RaftScopeAttestationAnswer(
                        status = 200,
                        response = RaftScopeAttestationResponse(attestation = "att-1", expiresAt = "2099-01-01T00:00:00Z"),
                    )

                override suspend fun send(batch: RaftWebTraceBatchRequest): Int {
                    sentBatches += batch
                    return 200
                }
            }
        )
        sender.setAccount("user-1")
        runCurrent()

        val store = FakePreferenceStore()
        val recorder =
            PushDeliveryTraceRecorder(
                preferenceStore = store,
                nowEpochMillis = { uploadTimeMs },
                privacyMode = privacyMode,
                isForeground = { foreground.value },
                isTransportReady = { sender.canAcceptImmediateHandoff() },
                deviceContextProvider = {
                    RaftPushDeliveryDeviceContext("google", "35", gmsAvailable = true, dndActive = false)
                },
                emit = { serverId, name, attrs, occurred ->
                    sender.emit(serverId, name, attrs, occurredAtMillis = occurred)
                },
            )
        recorder.onSignedIn("user-1", "server-1")
        recorder.recordReceived(
            deliveryId = testUuid(401),
            serverId = "server-1",
            transport = RaftPushDeliveryTransport.Fcm,
            appState = RaftPushDeliveryAppState.Background,
            category = RaftPushDeliveryCategory.Dm,
            occurredAtMs = occurredAtMs,
        )

        advanceTimeBy(150L)
        runCurrent()

        assertEquals(1, sentBatches.size)
        val record = sentBatches.single().events.single()
        assertEquals(RAFT_PUSH_DELIVERY_EVENT_RECEIVED, record.name)
        assertEquals(raftEpochMillisUtcIso(occurredAtMs), record.time)
        assertEquals(occurredAtMs, record.attrs["occurred_at_ms"]?.jsonPrimitive?.long)
        assertEquals(uploadTimeMs, record.attrs["reported_at_ms"]?.jsonPrimitive?.long)
        assertEquals("android", record.attrs["clientPlatform"]?.jsonPrimitive?.content)
        assertEquals("1.31.0", record.attrs["appVersion"]?.jsonPrimitive?.content)
    }
}
