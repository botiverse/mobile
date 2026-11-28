package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageType
import build.raft.app.model.ThreadId
import build.raft.app.thread.computePreReadLandingUnreadWitness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
class RaftBadgeTelemetryTest {
    private class FakePreferenceStore : SharedPreferenceStore {
        private val values = mutableMapOf<String, String>()

        override fun getString(key: String): String? = values[key]

        override fun putString(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private data class EmittedBadgeEvent(
        val serverId: String,
        val attrs: JsonObject,
        val occurredAtMs: Long,
    )

    private data class EmittedPushEvent(
        val serverId: String,
        val name: String,
        val attrs: JsonObject,
        val occurredAtMs: Long,
    )

    private fun testUuid(index: Int): String {
        val hex = index.toString(16).padStart(12, '0')
        return "550e8400-e29b-41d4-a716-$hex"
    }

    private fun testMessage(
        seq: Long,
        authorId: String,
        authorKind: MemberKind = MemberKind.Human,
        messageType: MessageType = MessageType.Chat,
        deletedAt: String? = null,
    ): Message =
        Message(
            id = MessageId("m-$seq"),
            threadId = ThreadId("c-1"),
            channelId = ChannelId("c-1"),
            seq = seq,
            author =
                MemberProfile(
                    id = MemberId(authorId),
                    displayName = authorId,
                    handle = authorId,
                    kind = authorKind,
                ),
            timestampLabel = "now",
            body = "hello",
            messageType = messageType,
            deletedAt = deletedAt,
        )

    @Test
    fun badgeReconciledEmitsOncePerForegroundWithColdAndWarmLaunchAndSignedDelta() {
        val store = FakePreferenceStore()
        var now = 1_700_000_000_000L
        var foreground = false
        val privacy = MutableStateFlow(RaftPrivacyMode.Off)
        val emitted = mutableListOf<EmittedBadgeEvent>()
        val recorder =
            BadgeAccuracyRecorder(
                preferenceStore = store,
                nowEpochMillis = { now },
                privacyMode = privacy,
                isForeground = { foreground },
                isTransportReady = { true },
                deviceContextProvider = {
                    RaftPushDeliveryDeviceContext(manufacturer = "google", osVersion = "35")
                },
                emit = { serverId, attrs, occurredAtMs ->
                    emitted += EmittedBadgeEvent(serverId, attrs, occurredAtMs)
                },
            )
        recorder.onSignedIn("user-1", "server-1")

        // Background push set icon badge to 5 before cold launch.
        recorder.recordBadgeSet(count = 5, source = RaftBadgeSource.Alert, setAtMs = now - 12_000L)

        // Enter foreground (cold launch).
        foreground = true
        recorder.onProcessVisibilityChanged(true)

        // In-app bridge.appBadge write during startup MUST NOT clobber the pre-reconcile snapshot.
        now += 100L
        recorder.recordBadgeSet(count = 0, source = RaftBadgeSource.App, setAtMs = now)

        // Foreground sync completes with activity_total = 3 (over-counted by +2).
        now += 400L
        assertTrue(recorder.onForegroundSyncCompleted(serverId = "server-1", activityTotal = 3, syncOk = true))
        // Second sync in the same foreground MUST be ignored (once per foreground).
        assertFalse(recorder.onForegroundSyncCompleted(serverId = "server-1", activityTotal = 2, syncOk = true))

        assertEquals(1, emitted.size)
        val coldAttrs = emitted[0].attrs
        assertNull(coldAttrs["delivery_id"])
        assertEquals("35", coldAttrs["os_version"]?.jsonPrimitive?.content)
        assertEquals("cold_start", coldAttrs["app_state"]?.jsonPrimitive?.content)
        assertEquals(5, coldAttrs["icon_badge_before"]?.jsonPrimitive?.int)
        assertEquals(3, coldAttrs["activity_total"]?.jsonPrimitive?.int)
        assertEquals(2, coldAttrs["delta"]?.jsonPrimitive?.int)
        assertEquals("alert", coldAttrs["last_badge_source"]?.jsonPrimitive?.content)
        assertEquals(12_500L, coldAttrs["last_badge_age_ms"]?.jsonPrimitive?.long)
        assertEquals("cold", coldAttrs["launch"]?.jsonPrimitive?.content)
        assertEquals(true, coldAttrs["sync_ok"]?.jsonPrimitive?.boolean)
        assertEquals(now, coldAttrs["occurred_at_ms"]?.jsonPrimitive?.long)
        assertEquals(now, coldAttrs["reported_at_ms"]?.jsonPrimitive?.long)

        // App updates badge to 3, then backgrounds, then receives a retract badge = 1.
        now += 500L
        recorder.recordBadgeSet(count = 3, source = RaftBadgeSource.App, setAtMs = now)
        foreground = false
        recorder.onProcessVisibilityChanged(false)
        now += 2_000L
        recorder.recordBadgeSet(count = 1, source = RaftBadgeSource.Retract, setAtMs = now)

        // Warm foreground return; sync reports activity_total = 4 (under-counted: delta = -3) and sync_ok = false.
        now += 3_000L
        foreground = true
        recorder.onProcessVisibilityChanged(true)
        now += 200L
        assertTrue(recorder.onForegroundSyncCompleted(serverId = "server-1", activityTotal = 4, syncOk = false))

        assertEquals(2, emitted.size)
        val warmAttrs = emitted[1].attrs
        assertEquals("foreground", warmAttrs["app_state"]?.jsonPrimitive?.content)
        assertEquals(1, warmAttrs["icon_badge_before"]?.jsonPrimitive?.int)
        assertEquals(4, warmAttrs["activity_total"]?.jsonPrimitive?.int)
        assertEquals(-3, warmAttrs["delta"]?.jsonPrimitive?.int)
        assertEquals("retract", warmAttrs["last_badge_source"]?.jsonPrimitive?.content)
        assertEquals(3_200L, warmAttrs["last_badge_age_ms"]?.jsonPrimitive?.long)
        assertEquals("warm", warmAttrs["launch"]?.jsonPrimitive?.content)
        assertEquals(false, warmAttrs["sync_ok"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun badgeReconciledOmitsSourceAndAgeWhenNeverSetAndDetectsIosExternalAlert() {
        val store = FakePreferenceStore()
        var now = 1_700_000_000_000L
        var foreground = true
        val emitted = mutableListOf<EmittedBadgeEvent>()

        // Fresh install: no prior badge source recorded.
        val freshRecorder =
            BadgeAccuracyRecorder(
                preferenceStore = store,
                nowEpochMillis = { now },
                privacyMode = MutableStateFlow(RaftPrivacyMode.Off),
                isForeground = { foreground },
                isTransportReady = { true },
                deviceContextProvider = { RaftPushDeliveryDeviceContext("apple", "18.4") },
                emit = { s, a, t -> emitted += EmittedBadgeEvent(s, a, t) },
            )
        freshRecorder.onSignedIn("user-1", "server-1")
        freshRecorder.onProcessVisibilityChanged(true)
        assertTrue(freshRecorder.onForegroundSyncCompleted("server-1", activityTotal = 0, syncOk = true))
        assertEquals(1, emitted.size)
        assertNull(emitted[0].attrs["last_badge_source"])
        assertNull(emitted[0].attrs["last_badge_age_ms"])
        assertEquals(0, emitted[0].attrs["icon_badge_before"]?.jsonPrimitive?.int)
        assertEquals(0, emitted[0].attrs["delta"]?.jsonPrimitive?.int)

        // App sets badge = 1, backgrounds, then iOS OS updates badge to 4 via APNs alert while asleep.
        freshRecorder.recordBadgeSet(count = 1, source = RaftBadgeSource.App, setAtMs = now)
        foreground = false
        freshRecorder.onProcessVisibilityChanged(false)
        now += 60_000L

        // Warm return on iOS: noteLiveIconBadgeBeforeForeground sees 4 != persisted 1 -> inferred "alert".
        foreground = true
        freshRecorder.noteLiveIconBadgeBeforeForeground(count = 4)
        freshRecorder.onProcessVisibilityChanged(true)
        // Delivered notification center backfill supplies exact alert timestamp before sync finishes.
        freshRecorder.enrichForegroundSnapshotFromDeliveredAlert(badge = 4, alertAtMs = now - 5_000L)
        now += 300L
        assertTrue(freshRecorder.onForegroundSyncCompleted("server-1", activityTotal = 4, syncOk = true))

        assertEquals(2, emitted.size)
        val iosAttrs = emitted[1].attrs
        assertEquals(4, iosAttrs["icon_badge_before"]?.jsonPrimitive?.int)
        assertEquals(4, iosAttrs["activity_total"]?.jsonPrimitive?.int)
        assertEquals(0, iosAttrs["delta"]?.jsonPrimitive?.int)
        assertEquals("alert", iosAttrs["last_badge_source"]?.jsonPrimitive?.content)
        assertEquals(5_300L, iosAttrs["last_badge_age_ms"]?.jsonPrimitive?.long)
    }

    @Test
    fun badgeReconciledRespectsPrivacySignOutAndDesktopDisable() {
        val store = FakePreferenceStore()
        var now = 1_700_000_000_000L
        val privacy = MutableStateFlow(RaftPrivacyMode.Unknown)
        val emitted = mutableListOf<EmittedBadgeEvent>()
        val recorder =
            BadgeAccuracyRecorder(
                preferenceStore = store,
                nowEpochMillis = { now },
                privacyMode = privacy,
                isForeground = { true },
                isTransportReady = { true },
                deviceContextProvider = { RaftPushDeliveryDeviceContext("google", "35") },
                emit = { s, a, t -> emitted += EmittedBadgeEvent(s, a, t) },
            )
        recorder.onSignedIn("user-1", "server-1")
        recorder.onProcessVisibilityChanged(true)
        assertTrue(recorder.onForegroundSyncCompleted("server-1", activityTotal = 2, syncOk = true))
        // Held while privacy mode is Unknown.
        assertEquals(0, emitted.size)
        assertEquals(1, recorder.pendingReconciledCountForTest())

        // Turning privacy Off drains it.
        now += 1_000L
        privacy.value = RaftPrivacyMode.Off
        recorder.onPrivacyModeChanged(RaftPrivacyMode.Off)
        assertEquals(1, emitted.size)
        assertEquals(0, recorder.pendingReconciledCountForTest())

        // Privacy On suppresses and clears.
        privacy.value = RaftPrivacyMode.On
        recorder.onPrivacyModeChanged(RaftPrivacyMode.On)
        recorder.onProcessVisibilityChanged(false)
        recorder.onProcessVisibilityChanged(true)
        assertFalse(recorder.onForegroundSyncCompleted("server-1", activityTotal = 1, syncOk = true))
        assertEquals(1, emitted.size)

        // Desktop (isEnabled = false) is a strict no-op.
        val desktopEmitted = mutableListOf<EmittedBadgeEvent>()
        val desktopRecorder =
            BadgeAccuracyRecorder(
                preferenceStore = FakePreferenceStore(),
                nowEpochMillis = { now },
                privacyMode = MutableStateFlow(RaftPrivacyMode.Off),
                isForeground = { true },
                isTransportReady = { true },
                deviceContextProvider = { RaftPushDeliveryDeviceContext("apple", "15.0") },
                emit = { s, a, t -> desktopEmitted += EmittedBadgeEvent(s, a, t) },
                isEnabled = { false },
            )
        desktopRecorder.onSignedIn("user-1", "server-1")
        desktopRecorder.onProcessVisibilityChanged(true)
        assertFalse(desktopRecorder.onForegroundSyncCompleted("server-1", activityTotal = 1, syncOk = true))
        assertEquals(0, desktopEmitted.size)
    }

    @Test
    fun retractWatermarksClassifyReadBeforeOpenAcrossSeqAndWholeGroup() {
        val store = FakePreferenceStore()
        var now = 1_700_000_000_000L
        val recorder =
            BadgeAccuracyRecorder(
                preferenceStore = store,
                nowEpochMillis = { now },
                privacyMode = MutableStateFlow(RaftPrivacyMode.Off),
                isForeground = { true },
                isTransportReady = { true },
                deviceContextProvider = { RaftPushDeliveryDeviceContext("google", "35") },
                emit = { _, _, _ -> },
            )
        // Numeric throughSeq watermark.
        recorder.recordRetract(groupKey = "s1:c1", throughSeq = 10L, retractedAtMs = now)
        assertTrue(recorder.isReadBeforeOpen("s1:c1", seq = 9L, pushOccurredAtMs = now - 5_000L, nowMs = now + 1_000L))
        assertTrue(recorder.isReadBeforeOpen("s1:c1", seq = 10L, pushOccurredAtMs = now - 1_000L, nowMs = now + 1_000L))
        assertFalse(recorder.isReadBeforeOpen("s1:c1", seq = 11L, pushOccurredAtMs = now + 500L, nowMs = now + 1_000L))
        assertFalse(recorder.isReadBeforeOpen("s1:c2", seq = 1L, pushOccurredAtMs = now - 1_000L, nowMs = now + 1_000L))

        // Whole-group retract (throughSeq = null) respects 60s late-arrival grace against newer pushes.
        recorder.recordRetract(groupKey = "s1:reminder", throughSeq = null, retractedAtMs = now)
        assertTrue(recorder.isReadBeforeOpen("s1:reminder", seq = null, pushOccurredAtMs = now - 10_000L, nowMs = now + 5_000L))
        assertTrue(recorder.isReadBeforeOpen("s1:reminder", seq = null, pushOccurredAtMs = null, nowMs = now + 5_000L))
        assertFalse(
            recorder.isReadBeforeOpen(
                groupKey = "s1:reminder",
                seq = null,
                pushOccurredAtMs = now + RAFT_BADGE_TELEMETRY_RETRACT_LATE_GRACE_MILLIS + 1_000L,
                nowMs = now + RAFT_BADGE_TELEMETRY_RETRACT_LATE_GRACE_MILLIS + 2_000L,
            )
        )

        // Expired after 72h TTL.
        assertFalse(
            recorder.isReadBeforeOpen(
                groupKey = "s1:c1",
                seq = 5L,
                pushOccurredAtMs = now,
                nowMs = now + RAFT_BADGE_TELEMETRY_RETRACT_TTL_MILLIS + 1L,
            )
        )
    }

    @Test
    fun deferredPushOpenEmitsAllFiveAttributesOnLandingLoadSettle() = runTest {
        val prefStore = FakePreferenceStore()
        var now = 1_700_000_000_000L
        val privacy = MutableStateFlow(RaftPrivacyMode.Off)
        val badgeRecorder =
            BadgeAccuracyRecorder(
                preferenceStore = FakePreferenceStore(),
                nowEpochMillis = { now },
                privacyMode = privacy,
                isForeground = { true },
                isTransportReady = { true },
                deviceContextProvider = { RaftPushDeliveryDeviceContext("google", "35") },
                emit = { _, _, _ -> },
            )
        val emitted = mutableListOf<EmittedPushEvent>()
        val pushRecorder =
            PushDeliveryTraceRecorder(
                preferenceStore = prefStore,
                nowEpochMillis = { now },
                privacyMode = privacy,
                isForeground = { true },
                isTransportReady = { true },
                deviceContextProvider = {
                    RaftPushDeliveryDeviceContext("google", "35", gmsAvailable = true, dndActive = false)
                },
                emit = { s, n, a, t -> emitted += EmittedPushEvent(s, n, a, t) },
                scope = backgroundScope,
                pushTimestampRecorder = badgeRecorder::recordPushTimestamp,
                pushTimestampLookup = badgeRecorder::lookupPushTimestamp,
                readBeforeOpenLookup = badgeRecorder::isReadBeforeOpen,
                currentBadgeCountProvider = badgeRecorder::currentBadgeCountForOpen,
            )
        pushRecorder.onSignedIn("user-1", "server-1")

        val deliveryId = testUuid(1)
        val pushSentAt = now
        badgeRecorder.recordBadgeSet(count = 7, source = RaftBadgeSource.Alert, setAtMs = pushSentAt)
        pushRecorder.recordReceived(
            deliveryId = deliveryId,
            serverId = "server-1",
            transport = RaftPushDeliveryTransport.Fcm,
            appState = RaftPushDeliveryAppState.Background,
            category = RaftPushDeliveryCategory.Dm,
            occurredAtMs = pushSentAt,
        )
        badgeRecorder.recordRetract(groupKey = "server-1:dm-1", throughSeq = 42L, retractedAtMs = pushSentAt + 5_000L)
        emitted.clear()

        // Tap happens 15 seconds after push; badge is 7 at tap time.
        val tapAt = pushSentAt + 15_000L
        now = tapAt
        assertTrue(
            pushRecorder.beginDeferredOpen(
                deliveryId = deliveryId,
                serverId = "server-1",
                transport = RaftPushDeliveryTransport.Fcm,
                appState = RaftPushDeliveryAppState.Background,
                openSurface = RaftPushDeliveryOpenSurface.SystemTray,
                target = RaftPushOpenLandingTarget(serverId = "server-1", conversationIds = setOf("dm-1")),
                occurredAtMs = tapAt,
                groupKey = "server-1:dm-1",
                seq = 42L,
            )
        )
        assertTrue(pushRecorder.hasPendingDeferredOpenForTest())
        assertEquals(0, emitted.size)

        // Foreground badge reconciliation changes badge to 0 BEFORE destination finishes loading:
        // badge_at_open MUST stay 7 (captured at tap time!).
        badgeRecorder.recordBadgeSet(count = 0, source = RaftBadgeSource.App, setAtMs = tapAt + 200L)

        // Non-matching conversation load must NOT settle this pending open.
        assertFalse(
            pushRecorder.completeDeferredOpenForConversation(
                serverId = "server-1",
                candidateConversationIds = setOf("other-channel"),
                landingLoadOk = true,
                landingUnread = 99,
            )
        )
        assertEquals(0, emitted.size)

        // Destination conversation first load settles 600ms after tap with pre-read unread = 0.
        now = tapAt + 600L
        assertTrue(
            pushRecorder.completeDeferredOpenForConversation(
                serverId = "server-1",
                candidateConversationIds = setOf("dm-1"),
                landingLoadOk = true,
                landingUnread = 0,
            )
        )
        assertFalse(pushRecorder.hasPendingDeferredOpenForTest())
        assertEquals(1, emitted.size)
        val opened = emitted[0]
        assertEquals(RAFT_PUSH_DELIVERY_EVENT_OPENED, opened.name)
        assertEquals(tapAt, opened.occurredAtMs)
        assertEquals(tapAt, opened.attrs["occurred_at_ms"]?.jsonPrimitive?.long)
        assertEquals(tapAt + 600L, opened.attrs["reported_at_ms"]?.jsonPrimitive?.long)
        assertEquals(true, opened.attrs["landing_load_ok"]?.jsonPrimitive?.boolean)
        assertEquals(0, opened.attrs["landing_unread"]?.jsonPrimitive?.int)
        assertEquals(15_000L, opened.attrs["push_age_ms"]?.jsonPrimitive?.long)
        assertEquals(true, opened.attrs["read_before_open"]?.jsonPrimitive?.boolean)
        assertEquals(7, opened.attrs["badge_at_open"]?.jsonPrimitive?.int)
    }

    @Test
    fun deferredPushOpenTimesOutAt8sAndOmitsLandingUnread() = runTest {
        var now = 1_700_000_000_000L
        val emitted = mutableListOf<EmittedPushEvent>()
        val pushRecorder =
            PushDeliveryTraceRecorder(
                preferenceStore = FakePreferenceStore(),
                nowEpochMillis = { now },
                privacyMode = MutableStateFlow(RaftPrivacyMode.Off),
                isForeground = { true },
                isTransportReady = { true },
                deviceContextProvider = { RaftPushDeliveryDeviceContext("apple", "18.4") },
                emit = { s, n, a, t -> emitted += EmittedPushEvent(s, n, a, t) },
                scope = backgroundScope,
                currentBadgeCountProvider = { 3 },
            )
        pushRecorder.onSignedIn("user-1", "server-1")

        val tapAt = now
        assertTrue(
            pushRecorder.beginDeferredOpen(
                deliveryId = testUuid(2),
                serverId = "server-1",
                transport = RaftPushDeliveryTransport.Apns,
                appState = RaftPushDeliveryAppState.ColdStart,
                openSurface = RaftPushDeliveryOpenSurface.SystemTray,
                target = RaftPushOpenLandingTarget(serverId = "server-1", conversationIds = setOf("ch-1")),
                occurredAtMs = tapAt,
            )
        )
        advanceTimeBy(7_999L)
        runCurrent()
        assertEquals(0, emitted.size)

        now = tapAt + RAFT_PUSH_OPENED_LANDING_TIMEOUT_MILLIS
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(1, emitted.size)
        val attrs = emitted[0].attrs
        assertEquals(false, attrs["landing_load_ok"]?.jsonPrimitive?.boolean)
        assertNull(attrs["landing_unread"])
        assertNull(attrs["push_age_ms"])
        assertEquals(false, attrs["read_before_open"]?.jsonPrimitive?.boolean)
        assertEquals(3, attrs["badge_at_open"]?.jsonPrimitive?.int)
        assertEquals(tapAt, attrs["occurred_at_ms"]?.jsonPrimitive?.long)
        assertEquals(tapAt + 8_000L, attrs["reported_at_ms"]?.jsonPrimitive?.long)
    }

    @Test
    fun deferredPushOpenFlushesOnBackgroundAndWhenSuperseded() = runTest {
        var now = 1_700_000_000_000L
        var foreground = true
        val emitted = mutableListOf<EmittedPushEvent>()
        val pushRecorder =
            PushDeliveryTraceRecorder(
                preferenceStore = FakePreferenceStore(),
                nowEpochMillis = { now },
                privacyMode = MutableStateFlow(RaftPrivacyMode.Off),
                isForeground = { foreground },
                isTransportReady = { true },
                deviceContextProvider = { RaftPushDeliveryDeviceContext("google", "35") },
                emit = { s, n, a, t -> emitted += EmittedPushEvent(s, n, a, t) },
                scope = backgroundScope,
                currentBadgeCountProvider = { 2 },
            )
        pushRecorder.onSignedIn("user-1", "server-1")

        // First open is superseded by a second open before landing finishes.
        val firstId = testUuid(10)
        val secondId = testUuid(11)
        assertTrue(
            pushRecorder.beginDeferredOpen(
                deliveryId = firstId,
                serverId = "server-1",
                transport = RaftPushDeliveryTransport.Fcm,
                appState = RaftPushDeliveryAppState.Background,
                openSurface = RaftPushDeliveryOpenSurface.SystemTray,
                target = RaftPushOpenLandingTarget(serverId = "server-1", conversationIds = setOf("ch-1")),
                occurredAtMs = now,
            )
        )
        now += 500L
        assertTrue(
            pushRecorder.beginDeferredOpen(
                deliveryId = secondId,
                serverId = "server-1",
                transport = RaftPushDeliveryTransport.Fcm,
                appState = RaftPushDeliveryAppState.Background,
                openSurface = RaftPushDeliveryOpenSurface.SystemTray,
                target = RaftPushOpenLandingTarget(serverId = "server-1", isActivityTarget = true),
                occurredAtMs = now,
            )
        )
        // First open flushed immediately with landing_load_ok = false.
        assertEquals(1, emitted.size)
        assertEquals(firstId, emitted[0].attrs["delivery_id"]?.jsonPrimitive?.content)
        assertEquals(false, emitted[0].attrs["landing_load_ok"]?.jsonPrimitive?.boolean)
        assertNull(emitted[0].attrs["landing_unread"])

        // App backgrounds before second open settles -> buffered with landing_load_ok = false, drained on return.
        now += 400L
        foreground = false
        pushRecorder.onProcessVisibilityChanged(false)
        assertFalse(pushRecorder.hasPendingDeferredOpenForTest())
        assertEquals(1, emitted.size)
        assertEquals(1, pushRecorder.bufferedCountForTest())

        now += 1_000L
        foreground = true
        pushRecorder.onProcessVisibilityChanged(true)
        assertEquals(2, emitted.size)
        assertEquals(secondId, emitted[1].attrs["delivery_id"]?.jsonPrimitive?.content)
        assertEquals(false, emitted[1].attrs["landing_load_ok"]?.jsonPrimitive?.boolean)
        assertNull(emitted[1].attrs["landing_unread"])
    }

    @Test
    fun deferredActivityTargetOpenSettlesOnActivityLoad() = runTest {
        val now = 1_700_000_000_000L
        val emitted = mutableListOf<EmittedPushEvent>()
        val pushRecorder =
            PushDeliveryTraceRecorder(
                preferenceStore = FakePreferenceStore(),
                nowEpochMillis = { now },
                privacyMode = MutableStateFlow(RaftPrivacyMode.Off),
                isForeground = { true },
                isTransportReady = { true },
                deviceContextProvider = { RaftPushDeliveryDeviceContext("apple", "18.4") },
                emit = { s, n, a, t -> emitted += EmittedPushEvent(s, n, a, t) },
                scope = backgroundScope,
                currentBadgeCountProvider = { 1 },
            )
        pushRecorder.onSignedIn("user-1", "server-1")

        assertTrue(
            pushRecorder.beginDeferredOpen(
                deliveryId = testUuid(20),
                serverId = "server-1",
                transport = RaftPushDeliveryTransport.Apns,
                appState = RaftPushDeliveryAppState.Background,
                openSurface = RaftPushDeliveryOpenSurface.SystemTray,
                target = RaftPushOpenLandingTarget(serverId = "server-1", isActivityTarget = true),
                occurredAtMs = now,
                pushOccurredAtMs = now + 500L, // negative skew clamps to 0
            )
        )
        // Conversation load does not satisfy an Activity target.
        assertFalse(
            pushRecorder.completeDeferredOpenForConversation(
                serverId = "server-1",
                candidateConversationIds = setOf("ch-1"),
                landingLoadOk = true,
                landingUnread = 5,
            )
        )
        assertTrue(
            pushRecorder.completeDeferredOpenForActivity(
                serverId = "server-1",
                landingLoadOk = true,
                landingUnread = 4,
            )
        )
        assertEquals(1, emitted.size)
        assertEquals(true, emitted[0].attrs["landing_load_ok"]?.jsonPrimitive?.boolean)
        assertEquals(4, emitted[0].attrs["landing_unread"]?.jsonPrimitive?.int)
        assertEquals(0L, emitted[0].attrs["push_age_ms"]?.jsonPrimitive?.long)
    }

    @Test
    fun preReadLandingUnreadWitnessCountsOnlyOtherNonSystemNonDeletedMessagesAfterMaxReadSeq() {
        val messages =
            listOf(
                testMessage(seq = 10L, authorId = "other-1"), // already read
                testMessage(seq = 11L, authorId = "self-user"), // own human message -> excluded
                testMessage(seq = 12L, authorId = "other-1"), // unread #1
                testMessage(seq = 13L, authorId = "agent-1", authorKind = MemberKind.Agent), // unread #2
                testMessage(seq = 14L, authorId = "other-2", messageType = MessageType.System), // system -> excluded
                testMessage(seq = 15L, authorId = "other-2", deletedAt = "2026-11-28T00:00:00Z"), // deleted -> excluded
                testMessage(seq = 16L, authorId = "other-2"), // unread #3
            )
        val unread =
            computePreReadLandingUnreadWitness(
                messages = messages,
                currentUserId = "self-user",
                serverMaxReadSeq = 10L,
                canLoadMoreBefore = true,
                canLoadMoreAfter = false,
            )
        assertEquals(3, unread.unreadCount)
        assertFalse(unread.truncatedWindow)

        // Ghost / already-read push: maxReadSeq >= tail seq -> exact 0!
        val alreadyRead =
            computePreReadLandingUnreadWitness(
                messages = messages,
                currentUserId = "self-user",
                serverMaxReadSeq = 16L,
                canLoadMoreBefore = true,
                canLoadMoreAfter = false,
            )
        assertEquals(0, alreadyRead.unreadCount)
        assertFalse(alreadyRead.truncatedWindow)

        // Anchored older window (canLoadMoreAfter = true) with 0 unread in window must not falsely claim 0.
        val anchoredOld =
            computePreReadLandingUnreadWitness(
                messages = listOf(testMessage(seq = 5L, authorId = "other-1")),
                currentUserId = "self-user",
                serverMaxReadSeq = 10L,
                canLoadMoreBefore = false,
                canLoadMoreAfter = true,
            )
        assertNull(anchoredOld.unreadCount)
        assertTrue(anchoredOld.truncatedWindow)

        // Missing serverMaxReadSeq returns unknown (unless empty tail).
        val unknown =
            computePreReadLandingUnreadWitness(
                messages = messages,
                currentUserId = "self-user",
                serverMaxReadSeq = null,
                canLoadMoreBefore = false,
                canLoadMoreAfter = false,
            )
        assertNull(unknown.unreadCount)
        val emptyTail =
            computePreReadLandingUnreadWitness(
                messages = emptyList(),
                currentUserId = "self-user",
                serverMaxReadSeq = null,
                canLoadMoreBefore = false,
                canLoadMoreAfter = false,
            )
        assertEquals(0, emptyTail.unreadCount)
    }
}
