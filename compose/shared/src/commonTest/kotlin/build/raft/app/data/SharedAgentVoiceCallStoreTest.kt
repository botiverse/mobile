package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SharedAgentVoiceCallStoreTest {
    private val testCallId = "123e4567-e89b-12d3-a456-426614174000"
    private val callee = AgentVoiceCallee(id = "agent-1", name = "Lagoon", avatarWireValue = "pixel:lagoon")

    @Test
    fun captionBufferParsesUpdatesDedupsLateNonFinalsCapsFinishedIdsWithdrawsEmptyAndKeepsLastTwo() {
        val buffer = AgentVoiceCaptionBuffer()

        // Invalid JSON and non-caption payloads ignored
        assertNull(buffer.parseAndApply("not json"))
        assertNull(buffer.parseAndApply("""{"type":"ping","role":"agent","text":"hi","final":false,"id":"c0"}"""))
        assertNull(buffer.parseAndApply("""{"topic":"other","data":{"type":"caption","role":"agent","text":"hi","final":false,"id":"c0"}}"""))

        // Streaming partial then final replaces in place
        val step1 =
            assertNotNull(
                buffer.parseAndApply("""{"type":"caption","role":"user","text":"Hel","final":false,"id":"c1"}"""),
            )
        assertEquals(listOf(AgentVoiceCaption(id = "c1", mine = true, text = "Hel", final = false)), step1)

        val step2 =
            assertNotNull(
                buffer.parseAndApply("""{"type":"caption","role":"user","text":"Hello there","final":true,"id":"c1"}"""),
            )
        assertEquals(listOf(AgentVoiceCaption(id = "c1", mine = true, text = "Hello there", final = true)), step2)

        // Late non-final for already-finished id is ignored
        assertNull(
            buffer.parseAndApply("""{"type":"caption","role":"user","text":"Hell","final":false,"id":"c1"}"""),
        )
        assertEquals("Hello there", buffer.currentLines.single().text)

        // Envelope form also accepted
        val step3 =
            assertNotNull(
                buffer.parseAndApply(
                    """{"topic":"caption","data":{"type":"caption","role":"agent","text":"Hi!","final":false,"id":"c2"}}""",
                ),
            )
        assertEquals(2, step3.size)
        assertFalse(step3[1].mine)

        // Empty non-final does not withdraw
        val step4 =
            assertNotNull(
                buffer.parseAndApply("""{"type":"caption","role":"agent","text":"   ","final":false,"id":"c2"}"""),
            )
        assertEquals(2, step4.size)

        // Empty final withdraws the line
        val step5 =
            assertNotNull(
                buffer.parseAndApply("""{"type":"caption","role":"agent","text":"","final":true,"id":"c2"}"""),
            )
        assertEquals(listOf("c1"), step5.map { it.id })

        // Keeps only the last 2 lines
        buffer.parseAndApply("""{"type":"caption","role":"agent","text":"Second","final":true,"id":"c3"}""")
        val step6 =
            assertNotNull(
                buffer.parseAndApply("""{"type":"caption","role":"user","text":"Third","final":true,"id":"c4"}"""),
            )
        assertEquals(listOf("c3", "c4"), step6.map { it.id })

        // Finished id set is bounded at 200
        for (i in 1..250) {
            buffer.apply(AgentVoiceCaption(id = "cap-$i", mine = false, text = "Line $i", final = true))
        }
        assertEquals(200, buffer.finishedCount)
        assertEquals(2, buffer.currentLines.size)
    }

    @Test
    fun formatCallClockMatchesWebFormatting() {
        assertEquals("0:00", formatAgentVoiceCallClock(-500L))
        assertEquals("0:00", formatAgentVoiceCallClock(0L))
        assertEquals("0:09", formatAgentVoiceCallClock(9_400L))
        assertEquals("1:05", formatAgentVoiceCallClock(65_000L))
        assertEquals("15:00", formatAgentVoiceCallClock(900_000L))
    }

    @Test
    fun fullCallLifecycleConnectSpeakingCaptionsControlsTokenRenewalAndHangUp() = runTest {
        var now = 1_000_000L
        val api = FakeCallApi(initialExpiresAtMs = now + 120_000L, renewedExpiresAtMs = now + 300_000L)
        val engine = FakeVoiceEngine()
        val store = createStore(api, engine, nowProvider = { now })

        store.startCall(callee, serverId = "srv-1")
        runCurrent()

        assertEquals(AgentVoiceCallPhase.Connecting, store.call.value?.phase)
        assertEquals(testCallId, engine.connectedCallId)
        assertEquals("tok-initial", engine.connectedToken)
        assertEquals(false, engine.currentMuted)
        assertEquals(true, engine.currentSpeakerOn)

        // Second startCall while active only bumps focusNonce and does not start another call
        val focusBefore = store.call.value!!.focusNonce
        store.startCall(AgentVoiceCallee("agent-2", "Other"), serverId = "srv-2")
        assertEquals(focusBefore + 1L, store.call.value!!.focusNonce)
        assertEquals(1, api.startCalls)

        // Connected
        engine.listener!!.onConnectionState(RaftAgentVoiceEngineState.CONNECTED)
        assertEquals(AgentVoiceCallPhase.Live, store.call.value?.phase)
        assertEquals(now, store.call.value?.connectedAtEpochMs)

        // Speaking & captions
        engine.listener!!.onAgentSpeaking(true)
        assertTrue(store.call.value!!.agentSpeaking)
        engine.listener!!.onCaptionData("""{"type":"caption","role":"agent","text":"Ready to help.","final":true,"id":"c1"}""")
        assertEquals(1, store.call.value!!.captions.size)

        // Mute & speaker controls
        store.toggleMute()
        assertTrue(store.call.value!!.muted)
        assertTrue(engine.currentMuted)
        store.toggleSpeaker()
        assertFalse(store.call.value!!.speakerOn)
        assertFalse(engine.currentSpeakerOn)

        // Live mic warning does not end the call
        engine.listener!!.onMicError("busy")
        assertEquals(AgentVoiceCallPhase.Live, store.call.value?.phase)
        assertEquals("busy", store.call.value?.micBlockedReason)

        // Proactive token renewal scheduled 60s before expiry (at +60_000ms)
        now += 60_000L
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(1, api.renewCalls)
        assertEquals(listOf("tok-renewed-1"), engine.updatedTokens)

        // Explicit engine token refresh callback triggers renewal too
        engine.listener!!.onTokenRefreshNeeded()
        runCurrent()
        assertEquals(2, api.renewCalls)
        assertEquals(listOf("tok-renewed-1", "tok-renewed-2"), engine.updatedTokens)

        // Hang up after 95 seconds live
        now += 35_000L
        store.hangUp()
        runCurrent()

        val ended = assertNotNull(store.call.value)
        assertEquals(AgentVoiceCallPhase.Ended, ended.phase)
        assertEquals(AgentVoiceCallEndReason.Left, ended.endReason)
        assertEquals(95_000L, ended.durationMs)
        assertFalse(ended.limitReached)
        assertFalse(ended.agentSpeaking)
        assertNull(ended.micBlockedReason)
        assertEquals(1, engine.disconnectCalls)
        assertEquals(1, api.hangUpCalls)

        // Dismiss clears the ended card
        store.dismissEnded()
        assertNull(store.call.value)
    }

    @Test
    fun serverCloseNearMaxLimitMarksLimitReachedAndSkipsServerHangUp() = runTest {
        var now = 1_000_000L
        val api = FakeCallApi(initialExpiresAtMs = now + 1_200_000L)
        val engine = FakeVoiceEngine()
        val store = createStore(api, engine, nowProvider = { now })

        store.startCall(callee, serverId = "srv-1")
        runCurrent()
        engine.listener!!.onConnectionState(RaftAgentVoiceEngineState.CONNECTED)

        // 14 minutes 30 seconds elapsed (within 60s of 15-minute ceiling)
        now += (14 * 60 + 30) * 1_000L
        engine.listener!!.onDisconnected(RaftAgentVoiceDisconnectReason.SERVER_CLOSED, detail = null)
        runCurrent()

        val ended = assertNotNull(store.call.value)
        assertEquals(AgentVoiceCallPhase.Ended, ended.phase)
        assertEquals(AgentVoiceCallEndReason.Closed, ended.endReason)
        assertTrue(ended.limitReached)
        assertEquals(0, api.hangUpCalls, "server closed the room itself: must not race a client hang-up")
    }

    @Test
    fun hangUpBeforeLiveDismissesCardImmediatelyAndNotifiesServer() = runTest {
        val now = 1_000_000L
        val api = FakeCallApi(initialExpiresAtMs = now + 300_000L)
        val engine = FakeVoiceEngine()
        val store = createStore(api, engine, nowProvider = { now })

        store.startCall(callee, serverId = "srv-1")
        runCurrent()
        assertEquals(AgentVoiceCallPhase.Connecting, store.call.value?.phase)

        store.hangUp()
        runCurrent()

        assertNull(store.call.value, "hanging up before live dismisses the card")
        assertEquals(1, engine.disconnectCalls)
        assertEquals(1, api.hangUpCalls)
    }

    @Test
    fun startRefusalAndJoinMicFailureSetTypedProblemAndSupportRetry() = runTest {
        val now = 1_000_000L
        val api = FakeCallApi(initialExpiresAtMs = now + 300_000L)
        api.nextStartOutcome = AgentVoiceCallStartOutcome.Refused(AgentVoiceCallProblem.AgentOffline)
        val engine = FakeVoiceEngine()
        val store = createStore(api, engine, nowProvider = { now })

        store.startCall(callee, serverId = "srv-1")
        runCurrent()
        assertEquals(AgentVoiceCallPhase.Failed, store.call.value?.phase)
        assertEquals(AgentVoiceCallProblem.AgentOffline, store.call.value?.problem)

        // Retry succeeds at API, then hits mic permission error before connected
        api.nextStartOutcome = null
        store.retry()
        runCurrent()
        assertEquals(AgentVoiceCallPhase.Connecting, store.call.value?.phase)
        engine.listener!!.onMicError("permission")
        runCurrent()

        assertEquals(AgentVoiceCallPhase.Failed, store.call.value?.phase)
        assertEquals(AgentVoiceCallProblem.MicPermission, store.call.value?.problem)
        assertEquals(1, api.hangUpCalls)
    }

    @Test
    fun midCallFailedDisconnectTransitionsToEndedLost() = runTest {
        var now = 1_000_000L
        val api = FakeCallApi(initialExpiresAtMs = now + 300_000L)
        val engine = FakeVoiceEngine()
        val store = createStore(api, engine, nowProvider = { now })

        store.startCall(callee, serverId = "srv-1")
        runCurrent()
        engine.listener!!.onConnectionState(RaftAgentVoiceEngineState.CONNECTED)
        now += 12_000L
        engine.listener!!.onDisconnected(RaftAgentVoiceDisconnectReason.FAILED, detail = "ice_failed")
        runCurrent()

        val ended = assertNotNull(store.call.value)
        assertEquals(AgentVoiceCallPhase.Ended, ended.phase)
        assertEquals(AgentVoiceCallEndReason.Lost, ended.endReason)
        assertEquals(12_000L, ended.durationMs)
    }

    private fun TestScope.createStore(
        api: AgentVoiceCallApi,
        engine: RaftAgentVoiceCallEngine,
        nowProvider: () -> Long,
    ): SharedAgentVoiceCallStore =
        SharedAgentVoiceCallStore(
            api = api,
            scope = backgroundScope,
            engineProvider = { engine },
            nowEpochMillis = nowProvider,
        )

    private inner class FakeCallApi(
        private val initialExpiresAtMs: Long,
        private val renewedExpiresAtMs: Long = initialExpiresAtMs + 180_000L,
    ) : AgentVoiceCallApi {
        var startCalls: Int = 0
        var renewCalls: Int = 0
        var hangUpCalls: Int = 0
        var nextStartOutcome: AgentVoiceCallStartOutcome? = null

        override suspend fun startCall(agentId: String, serverId: String): AgentVoiceCallStartOutcome {
            startCalls += 1
            nextStartOutcome?.let { return it }
            return AgentVoiceCallStartOutcome.Started(
                AgentVoiceCallStartResult(
                    callId = testCallId,
                    room =
                        AgentVoiceCallRoomGrant(
                            url = "wss://live.example.test",
                            token = "tok-initial",
                            expiresAtIso = "2026-11-26T13:15:00.000Z",
                            expiresAtEpochMs = initialExpiresAtMs,
                        ),
                ),
            )
        }

        override suspend fun renewToken(agentId: String, callId: String, serverId: String): AgentVoiceCallStartResult {
            renewCalls += 1
            return AgentVoiceCallStartResult(
                callId = callId,
                room =
                    AgentVoiceCallRoomGrant(
                        url = "wss://live.example.test",
                        token = "tok-renewed-$renewCalls",
                        expiresAtIso = "2026-11-26T13:20:00.000Z",
                        expiresAtEpochMs = renewedExpiresAtMs + renewCalls * 60_000L,
                    ),
            )
        }

        override suspend fun hangUp(agentId: String, callId: String, serverId: String): Boolean {
            hangUpCalls += 1
            return true
        }
    }

    private class FakeVoiceEngine(
        override val isSupported: Boolean = true,
    ) : RaftAgentVoiceCallEngine {
        var connectedCallId: String? = null
        var connectedToken: String? = null
        var currentMuted: Boolean = false
        var currentSpeakerOn: Boolean = true
        var listener: RaftAgentVoiceEngineListener? = null
        val updatedTokens = mutableListOf<String>()
        var disconnectCalls: Int = 0

        override fun connect(
            callId: String,
            url: String,
            token: String,
            muted: Boolean,
            speakerOn: Boolean,
            listener: RaftAgentVoiceEngineListener,
        ) {
            this.connectedCallId = callId
            this.connectedToken = token
            this.currentMuted = muted
            this.currentSpeakerOn = speakerOn
            this.listener = listener
        }

        override fun setMuted(muted: Boolean) {
            this.currentMuted = muted
        }

        override fun setSpeakerOn(speakerOn: Boolean) {
            this.currentSpeakerOn = speakerOn
        }

        override fun updateToken(token: String) {
            updatedTokens += token
        }

        override fun disconnect() {
            disconnectCalls += 1
        }
    }
}
