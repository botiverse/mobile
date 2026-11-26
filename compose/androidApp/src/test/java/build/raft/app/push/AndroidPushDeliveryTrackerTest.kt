package build.raft.app.push

import android.app.NotificationManager
import android.content.Intent
import build.raft.app.data.RaftPushDeliverySuppressReason
import build.raft.app.data.RaftPushDeliveryTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Android push delivery observability mappings (`#proj-mobile:#405`):
 * poster outcomes (including duplicate dedup), DND interruption filter mapping, background
 * socket skip reasons, and notification-open Intent extra extraction.
 */
class AndroidPushDeliveryTrackerTest {
    @Test
    fun posterResultMapsToPresentedAndEverySuppressReason() {
        assertEquals(
            AndroidPosterPresentationOutcome(presented = true, suppressReason = null),
            androidPosterResultToPresentationOutcome(
                mapOf("status" to "ok", "code" to "posted", "duplicate" to false)
            ),
        )
        assertEquals(
            AndroidPosterPresentationOutcome(presented = true, suppressReason = null),
            androidPosterResultToPresentationOutcome(
                mapOf("status" to "ok", "code" to "posted")
            ),
        )
        assertEquals(
            AndroidPosterPresentationOutcome(presented = false, suppressReason = RaftPushDeliverySuppressReason.Dedup),
            androidPosterResultToPresentationOutcome(
                mapOf("status" to "ok", "code" to "posted", "duplicate" to true)
            ),
        )
        assertEquals(
            AndroidPosterPresentationOutcome(presented = false, suppressReason = RaftPushDeliverySuppressReason.Retracted),
            androidPosterResultToPresentationOutcome(
                mapOf("status" to "ok", "code" to "already_retracted")
            ),
        )
        assertEquals(
            AndroidPosterPresentationOutcome(presented = false, suppressReason = RaftPushDeliverySuppressReason.PermissionOff),
            androidPosterResultToPresentationOutcome(
                mapOf("status" to "error", "code" to "permission_denied")
            ),
        )
        assertEquals(
            AndroidPosterPresentationOutcome(presented = false, suppressReason = RaftPushDeliverySuppressReason.PermissionOff),
            androidPosterResultToPresentationOutcome(
                mapOf("status" to "error", "code" to "notifications_disabled")
            ),
        )
        assertEquals(
            AndroidPosterPresentationOutcome(presented = false, suppressReason = RaftPushDeliverySuppressReason.ChannelMuted),
            androidPosterResultToPresentationOutcome(
                mapOf("status" to "error", "code" to "channel_blocked")
            ),
        )
        assertEquals(
            AndroidPosterPresentationOutcome(presented = false, suppressReason = RaftPushDeliverySuppressReason.PostFailed),
            androidPosterResultToPresentationOutcome(
                mapOf("status" to "error", "code" to "post_failed")
            ),
        )
        assertEquals(
            AndroidPosterPresentationOutcome(presented = false, suppressReason = RaftPushDeliverySuppressReason.Unknown),
            androidPosterResultToPresentationOutcome(
                mapOf("status" to "error", "code" to "server_missing")
            ),
        )
    }

    @Test
    fun interruptionFilterMapsToDndActive() {
        assertFalse(isAndroidInterruptionFilterDndActive(NotificationManager.INTERRUPTION_FILTER_ALL))
        assertFalse(isAndroidInterruptionFilterDndActive(NotificationManager.INTERRUPTION_FILTER_UNKNOWN))
        assertTrue(isAndroidInterruptionFilterDndActive(NotificationManager.INTERRUPTION_FILTER_PRIORITY))
        assertTrue(isAndroidInterruptionFilterDndActive(NotificationManager.INTERRUPTION_FILTER_NONE))
        assertTrue(isAndroidInterruptionFilterDndActive(NotificationManager.INTERRUPTION_FILTER_ALARMS))
    }

    @Test
    fun backgroundSocketSkipReasonsMapToClosedEnum() {
        assertEquals(
            RaftPushDeliverySuppressReason.PhoneAlertFalse,
            androidSocketBackgroundSkipSuppressReason(
                preferenceEnabled = true,
                isAppInForeground = false,
                remotePushActive = false,
                phoneAlert = false,
            ),
        )
        assertEquals(
            RaftPushDeliverySuppressReason.ForegroundSuppressed,
            androidSocketBackgroundSkipSuppressReason(
                preferenceEnabled = true,
                isAppInForeground = true,
                remotePushActive = false,
                phoneAlert = true,
            ),
        )
        assertEquals(
            RaftPushDeliverySuppressReason.AlertsDisabled,
            androidSocketBackgroundSkipSuppressReason(
                preferenceEnabled = false,
                isAppInForeground = false,
                remotePushActive = false,
                phoneAlert = null,
            ),
        )
        assertEquals(
            RaftPushDeliverySuppressReason.RemotePushActive,
            androidSocketBackgroundSkipSuppressReason(
                preferenceEnabled = true,
                isAppInForeground = false,
                remotePushActive = true,
                phoneAlert = true,
            ),
        )
        assertEquals(
            RaftPushDeliverySuppressReason.Unknown,
            androidSocketBackgroundSkipSuppressReason(
                preferenceEnabled = true,
                isAppInForeground = false,
                remotePushActive = false,
                phoneAlert = null,
            ),
        )
    }

    @Test
    fun extractPushOpenPayloadValidatesUuidTransportAndHistoryFlag() {
        val valid =
            extractPushOpenPayload(
                flags = 0,
                rawDeliveryId = "550E8400-E29B-41D4-A716-446655440000",
                rawTransport = "mobile_socket",
                rawServerId = "server-1",
            )
        assertNotNull(valid)
        assertEquals("550e8400-e29b-41d4-a716-446655440000", valid!!.deliveryId)
        assertEquals(RaftPushDeliveryTransport.MobileSocket, valid.transport)
        assertEquals("server-1", valid.serverId)

        // Unknown transport defaults to fcm; history relaunch or invalid UUID is ignored.
        val fallbackTransport =
            extractPushOpenPayload(
                flags = 0,
                rawDeliveryId = "550e8400-e29b-41d4-a716-446655440000",
                rawTransport = "unexpected",
                rawServerId = "server-2",
            )
        assertEquals(RaftPushDeliveryTransport.Fcm, fallbackTransport?.transport)

        assertNull(
            extractPushOpenPayload(
                flags = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY,
                rawDeliveryId = "550e8400-e29b-41d4-a716-446655440000",
                rawTransport = "fcm",
                rawServerId = "server-1",
            )
        )
        assertNull(
            extractPushOpenPayload(
                flags = 0,
                rawDeliveryId = "not-a-uuid",
                rawTransport = "fcm",
                rawServerId = "server-1",
            )
        )
        assertNull(
            extractPushOpenPayload(
                flags = 0,
                rawDeliveryId = null,
                rawTransport = "fcm",
                rawServerId = "server-1",
            )
        )
    }

    @Test
    fun resolveAndroidPushServerIdFallsBackToRouteJson() {
        assertEquals("s-direct", resolveAndroidPushServerId("s-direct", null))
        assertEquals(
            "s-route",
            resolveAndroidPushServerId(
                serverId = "",
                routeJson = """{"type":"dm","serverId":"s-route","channelId":"c1","messageId":"m1"}""",
            ),
        )
        assertEquals("", resolveAndroidPushServerId(null, null))
    }
}
