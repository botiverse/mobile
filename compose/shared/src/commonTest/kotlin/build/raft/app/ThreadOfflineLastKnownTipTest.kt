package build.raft.app

import build.raft.app.resources.*
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
    fun insetIsReservedWhileTheFrameStandsInAndTheTipWaitsForAFailedLoad() {
        // Hands e0a5248d: the stand-in is published before its refresh starts, online too. No
        // failure yet, so no "offline" tip, only the inset.
        val opening =
            threadOfflineLastKnownChrome(durableLastKnown = true, status = "content", backgroundRefreshing = false, mainLoadFailed = false)
        assertTrue(opening.insetActive)
        assertFalse(opening.tipVisible)

        // Durable frame rendered, network refresh still running: reserve the inset, no tip yet.
        val refreshing =
            threadOfflineLastKnownChrome(durableLastKnown = true, status = "content", backgroundRefreshing = true, mainLoadFailed = false)
        assertTrue(refreshing.insetActive)
        assertFalse(refreshing.tipVisible)

        // Refresh ended without replacing the frame (transient failure): tip on, inset unchanged (no jump).
        val failed =
            threadOfflineLastKnownChrome(durableLastKnown = true, status = "content", backgroundRefreshing = false, mainLoadFailed = true)
        assertTrue(failed.insetActive)
        assertTrue(failed.tipVisible)

        // A recovery retry is running again after that failure: the tip waits for its answer.
        assertFalse(
            threadOfflineLastKnownChrome(durableLastKnown = true, status = "content", backgroundRefreshing = true, mainLoadFailed = true)
                .tipVisible
        )
    }

    @Test
    fun anEmptyLastKnownChannelIsAStandInToo() {
        // Task #1137: the empty channel carries the tip once the refresh has failed.
        assertFalse(
            threadOfflineLastKnownChrome(durableLastKnown = true, status = "empty", backgroundRefreshing = true, mainLoadFailed = false)
                .tipVisible
        )
        assertTrue(
            threadOfflineLastKnownChrome(durableLastKnown = true, status = "empty", backgroundRefreshing = false, mainLoadFailed = true)
                .tipVisible
        )
        // A canonical empty answer (flag cleared) is an ordinary empty channel with no tip.
        assertFalse(
            threadOfflineLastKnownChrome(durableLastKnown = false, status = "empty", backgroundRefreshing = false, mainLoadFailed = false)
                .tipVisible
        )
    }

    @Test
    fun nothingIsShownWithoutAStandInFrame() {
        // A canonical answer cleared the flag: both go away with the content replacement.
        assertEquals(
            ThreadOfflineLastKnownChrome(insetActive = false, tipVisible = false),
            threadOfflineLastKnownChrome(durableLastKnown = false, status = "content", backgroundRefreshing = false, mainLoadFailed = false)
        )
        // The flag without rendered content (defensive: the store never produces this) shows nothing.
        assertEquals(
            ThreadOfflineLastKnownChrome(insetActive = false, tipVisible = false),
            threadOfflineLastKnownChrome(durableLastKnown = true, status = "loading", backgroundRefreshing = false, mainLoadFailed = true)
        )
        assertEquals(
            ThreadOfflineLastKnownChrome(insetActive = false, tipVisible = false),
            threadOfflineLastKnownChrome(durableLastKnown = true, status = "error", backgroundRefreshing = false, mainLoadFailed = true)
        )
    }

    @Test
    fun payloadProjectionCarriesTheFlagIntoTheSliceAndTheChrome() {
        val payload =
            RaftThreadScreenPayload(
                instanceId = "shared-shell-thread-channel-1",
                title = "general",
                sourceThreadId = "channel-1",
                status = "content",
                backgroundRefreshing = false,
                durableLastKnown = true,
                mainLoadFailed = true,
                messages = listOf(RaftThreadMessageItem(id = "m1", authorId = "u-a", authorName = "Owner A", body = "durable body"))
            )
        assertTrue(payload.toThreadTimelineSlice().durableLastKnown)
        assertTrue(payload.toThreadTimelineSlice().mainLoadFailed)
        assertEquals(ThreadOfflineLastKnownChrome(insetActive = true, tipVisible = true), threadOfflineLastKnownChrome(payload))

        val replaced = payload.copy(durableLastKnown = false)
        assertFalse(replaced.toThreadTimelineSlice().durableLastKnown)
        assertEquals(ThreadOfflineLastKnownChrome(insetActive = false, tipVisible = false), threadOfflineLastKnownChrome(replaced))
    }

    @Test
    fun historyBoundaryNeverClaimsTheBeginningOfTheConversationForAStandInFrame() {
        // Positive control: the ordinary closed-paging label.
        assertEquals(
            Res.string.thread_state_beginning_conversation.desc(),
            threadOlderMessagesStatusSpec(canLoadMoreBefore = false, durableLastKnown = false).label
        )
        // Stand-in frame whose load failed: paging is closed too, but the frame is not the beginning.
        // (While that load still runs, the row says older messages are loading: task #80.)
        assertEquals(
            Res.string.thread_offline_older_unavailable.desc(),
            threadOlderMessagesStatusSpec(canLoadMoreBefore = false, durableLastKnown = true, mainLoadFailed = true).label
        )
        // Task #168: a stand-in whose stored window reaches the conversation's first message IS the beginning.
        assertEquals(
            Res.string.thread_state_beginning_conversation.desc(),
            threadOlderMessagesStatusSpec(canLoadMoreBefore = false, durableLastKnown = true, durableReachedOldest = true).label
        )
        // The slice-level overload reads the flag from the slice.
        val slice =
            RaftThreadScreenPayload(
                instanceId = "shared-shell-thread-channel-1",
                title = "general",
                sourceThreadId = "channel-1",
                status = "content",
                durableLastKnown = true,
                mainLoadFailed = true,
                canLoadMoreBefore = false,
                messages = listOf(RaftThreadMessageItem(id = "m1", authorId = "u-a", authorName = "Owner A", body = "durable body"))
            ).toThreadTimelineSlice()
        assertEquals(Res.string.thread_offline_older_unavailable.desc(), threadOlderMessagesStatusSpec(slice).label)
    }

    @Test
    fun aRecoveredNetworkReasksAnIdleStandInAndDefersWhileARefreshIsRunning() {
        val base = RaftThreadScreenPayload(status = "content")
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
        // HuaTuo alpha 78: a warm re-entry keeps the memory copy after a failed refresh (no stand-in, no tip),
        // and a cold miss shows the error page — both are re-asked too.
        assertEquals(
            OfflineStandInRecoveryAction.Refresh,
            offlineStandInRecoveryAction(base.copy(mainLoadFailed = true, errorCode = "network_timeout")),
            "memory copy kept after a failed refresh"
        )
        assertEquals(
            OfflineStandInRecoveryAction.Refresh,
            offlineStandInRecoveryAction(
                RaftThreadScreenPayload(status = "error", mainLoadFailed = true, errorCode = "network_timeout")
            ),
            "error page"
        )
        // LiBai #2782: an older/newer page failure (even a permission one) also writes errorCode and keeps
        // status=content; it is not a main-load failure and must not trigger an automatic refresh.
        assertEquals(
            OfflineStandInRecoveryAction.None,
            offlineStandInRecoveryAction(base.copy(olderMessagesStatus = "error", errorCode = "forbidden")),
            "paging failure"
        )
        assertEquals(
            OfflineStandInRecoveryAction.Defer,
            offlineStandInRecoveryAction(RaftThreadScreenPayload(status = "loading")),
            "first load still running"
        )
        assertEquals(
            OfflineStandInRecoveryAction.None,
            offlineStandInRecoveryAction(RaftThreadScreenPayload(status = "no_permission", errorCode = "forbidden")),
            "a permission answer is final"
        )
    }

    @Test
    fun aRecoverySignalDuringARefreshFiresOnceThatRefreshFailsBackToTheStandIn() {
        // LiBai #2782 order: refresh running → online (de-duplicated, never repeated) → refresh fails.
        val standIn = RaftThreadScreenPayload(status = "content", durableLastKnown = true)
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
            build.raft.app.compat.json.JSONObject().apply {
                put(build.raft.app.data.realtime.RaftRealtimeNotifyContract.KeyAction, action)
                if (serverId != null) put(build.raft.app.data.realtime.RaftRealtimeNotifyContract.KeyServerId, serverId)
            }.toJsonObject()
        val connected = build.raft.app.data.realtime.RaftRealtimeNotifyContract.ActionPresenceTransportConnected
        assertTrue(isRealtimeTransportConnectedEvent(event(connected, "server-a"), "server-a"))
        assertFalse(isRealtimeTransportConnectedEvent(event(connected, "server-b"), "server-a"), "another server's reconnect")
        assertFalse(isRealtimeTransportConnectedEvent(event(connected, null), "server-a"), "no server on the event")
        assertFalse(isRealtimeTransportConnectedEvent(event(connected, "server-a"), null), "page without a session server")
        assertFalse(isRealtimeTransportConnectedEvent(event("message:new", "server-a"), "server-a"))
    }

    @Test
    fun aVisibleFailedLoadRetriesOnAGrowingCappedBackoff() {
        assertEquals(listOf(10_000L, 20_000L, 40_000L, 60_000L, 60_000L), (0..4).map(::offlineRecoveryRetryDelayMs))
        assertEquals(10_000L, offlineRecoveryRetryDelayMs(-1))
    }
}
