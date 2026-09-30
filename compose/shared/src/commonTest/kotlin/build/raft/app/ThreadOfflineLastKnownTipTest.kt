package build.raft.app

import build.raft.app.resources.MR
import com.tencent.tmm.kmmresource.resource.desc.desc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task #1125: the chrome the thread page puts around an offline durable stand-in frame (#2342) —
 * when the list reserves the tip's height, when the tip itself is shown, and what the history
 * boundary row says while the frame stands in. Pure decisions; the composables are thin.
 */
class ThreadOfflineLastKnownTipTest {
    @Test
    fun insetIsReservedWhileTheFrameStandsInAndTheTipWaitsForTheRefreshToEnd() {
        // Durable frame rendered, network refresh still running: reserve the inset, no tip yet.
        val refreshing = threadOfflineLastKnownChrome(durableLastKnown = true, status = "content", backgroundRefreshing = true)
        assertTrue(refreshing.insetActive)
        assertFalse(refreshing.tipVisible)

        // Refresh ended without replacing the frame (transient failure): tip on, inset unchanged (no jump).
        val failed = threadOfflineLastKnownChrome(durableLastKnown = true, status = "content", backgroundRefreshing = false)
        assertTrue(failed.insetActive)
        assertTrue(failed.tipVisible)
    }

    @Test
    fun anEmptyLastKnownChannelIsAStandInToo() {
        // Task #1137: the empty channel carries the tip once the refresh has failed.
        assertFalse(threadOfflineLastKnownChrome(durableLastKnown = true, status = "empty", backgroundRefreshing = true).tipVisible)
        assertTrue(threadOfflineLastKnownChrome(durableLastKnown = true, status = "empty", backgroundRefreshing = false).tipVisible)
        // A canonical empty answer (flag cleared) is an ordinary empty channel with no tip.
        assertFalse(threadOfflineLastKnownChrome(durableLastKnown = false, status = "empty", backgroundRefreshing = false).tipVisible)
    }

    @Test
    fun nothingIsShownWithoutAStandInFrame() {
        // A canonical answer cleared the flag: both go away with the content replacement.
        assertEquals(
            ThreadOfflineLastKnownChrome(insetActive = false, tipVisible = false),
            threadOfflineLastKnownChrome(durableLastKnown = false, status = "content", backgroundRefreshing = false)
        )
        // The flag without rendered content (defensive: the store never produces this) shows nothing.
        assertEquals(
            ThreadOfflineLastKnownChrome(insetActive = false, tipVisible = false),
            threadOfflineLastKnownChrome(durableLastKnown = true, status = "loading", backgroundRefreshing = false)
        )
        assertEquals(
            ThreadOfflineLastKnownChrome(insetActive = false, tipVisible = false),
            threadOfflineLastKnownChrome(durableLastKnown = true, status = "error", backgroundRefreshing = false)
        )
    }

    @Test
    fun payloadProjectionCarriesTheFlagIntoTheSliceAndTheChrome() {
        val payload =
            KuiklyThreadScreenPayload(
                instanceId = "shared-shell-thread-channel-1",
                title = "general",
                sourceThreadId = "channel-1",
                status = "content",
                backgroundRefreshing = false,
                durableLastKnown = true,
                messages = listOf(KuiklyThreadMessageItem(id = "m1", authorId = "u-a", authorName = "Owner A", body = "durable body"))
            )
        assertTrue(payload.toThreadTimelineSlice().durableLastKnown)
        assertEquals(ThreadOfflineLastKnownChrome(insetActive = true, tipVisible = true), threadOfflineLastKnownChrome(payload))

        val replaced = payload.copy(durableLastKnown = false)
        assertFalse(replaced.toThreadTimelineSlice().durableLastKnown)
        assertEquals(ThreadOfflineLastKnownChrome(insetActive = false, tipVisible = false), threadOfflineLastKnownChrome(replaced))
    }

    @Test
    fun historyBoundaryNeverClaimsTheBeginningOfTheConversationForAStandInFrame() {
        // Positive control: the ordinary closed-paging label.
        assertEquals(
            MR.strings.thread_state_beginning_conversation.desc(),
            threadOlderMessagesStatusSpec(canLoadMoreBefore = false, durableLastKnown = false).label
        )
        // Stand-in frame: paging is closed too, but the frame is not the beginning.
        assertEquals(
            MR.strings.thread_offline_older_unavailable.desc(),
            threadOlderMessagesStatusSpec(canLoadMoreBefore = false, durableLastKnown = true).label
        )
        // The slice-level overload reads the flag from the slice.
        val slice =
            KuiklyThreadScreenPayload(
                instanceId = "shared-shell-thread-channel-1",
                title = "general",
                sourceThreadId = "channel-1",
                status = "content",
                durableLastKnown = true,
                canLoadMoreBefore = false,
                messages = listOf(KuiklyThreadMessageItem(id = "m1", authorId = "u-a", authorName = "Owner A", body = "durable body"))
            ).toThreadTimelineSlice()
        assertEquals(MR.strings.thread_offline_older_unavailable.desc(), threadOlderMessagesStatusSpec(slice).label)
    }

    @Test
    fun aRecoveredNetworkReasksAnIdleStandInAndDefersWhileARefreshIsRunning() {
        val base = KuiklyThreadScreenPayload(status = "content")
        assertEquals(
            OfflineStandInRecoveryAction.Refresh,
            offlineStandInRecoveryAction(base.copy(durableLastKnown = true, backgroundRefreshing = false))
        )
        assertEquals(
            OfflineStandInRecoveryAction.Refresh,
            offlineStandInRecoveryAction(base.copy(status = "empty", durableLastKnown = true)),
            "an empty last-known channel too"
        )
        // LiBai #2782: a refresh started offline may still fail back to the stand-in, so the signal is kept.
        assertEquals(
            OfflineStandInRecoveryAction.Defer,
            offlineStandInRecoveryAction(base.copy(durableLastKnown = true, backgroundRefreshing = true))
        )
        assertEquals(OfflineStandInRecoveryAction.None, offlineStandInRecoveryAction(base.copy(durableLastKnown = false)))
    }

    @Test
    fun aRecoverySignalDuringARefreshFiresOnceThatRefreshFailsBackToTheStandIn() {
        // LiBai #2782 order: refresh running → online (de-duplicated, never repeated) → refresh fails.
        val standIn = KuiklyThreadScreenPayload(status = "content", durableLastKnown = true)
        val gate = OfflineStandInRecoveryGate()
        assertFalse(gate.onNetworkRecovered(standIn.copy(backgroundRefreshing = true)), "deferred, not dropped")
        assertFalse(gate.onPayloadSettled(standIn.copy(backgroundRefreshing = true)), "still running")
        assertTrue(gate.onPayloadSettled(standIn.copy(backgroundRefreshing = false)), "failed back: re-ask now")
        assertFalse(gate.onPayloadSettled(standIn.copy(backgroundRefreshing = false)), "exactly once")

        // Same order, but the running refresh succeeds: the network answer clears the memory.
        val succeeded = OfflineStandInRecoveryGate()
        assertFalse(succeeded.onNetworkRecovered(standIn.copy(backgroundRefreshing = true)))
        assertFalse(succeeded.onPayloadSettled(standIn.copy(durableLastKnown = false)))
        assertFalse(succeeded.onPayloadSettled(standIn.copy(backgroundRefreshing = false)), "memory cleared")

        // Control: online after the failure refreshes immediately.
        assertTrue(OfflineStandInRecoveryGate().onNetworkRecovered(standIn))
    }

    @Test
    fun onlyThisServersTransportConnectedEventCountsAsARealtimeRecovery() {
        fun event(action: String, serverId: String?) =
            com.tencent.kuikly.core.nvi.serialization.json.JSONObject().apply {
                put(build.raft.app.data.realtime.SlockRealtimeNotifyContract.KeyAction, action)
                if (serverId != null) put(build.raft.app.data.realtime.SlockRealtimeNotifyContract.KeyServerId, serverId)
            }
        val connected = build.raft.app.data.realtime.SlockRealtimeNotifyContract.ActionPresenceTransportConnected
        assertTrue(isRealtimeTransportConnectedEvent(event(connected, "server-a"), "server-a"))
        assertFalse(isRealtimeTransportConnectedEvent(event(connected, "server-b"), "server-a"), "another server's reconnect")
        assertFalse(isRealtimeTransportConnectedEvent(event(connected, null), "server-a"), "no server on the event")
        assertFalse(isRealtimeTransportConnectedEvent(event(connected, "server-a"), null), "page without a session server")
        assertFalse(isRealtimeTransportConnectedEvent(event("message:new", "server-a"), "server-a"))
    }
}
