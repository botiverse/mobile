@file:OptIn(RaftCoreInternalApi::class)

package build.raft.app

import build.raft.app.data.RaftAgentVoiceCallEngineRegistry
import build.raft.app.data.RaftAgentVoiceDisconnectReason
import build.raft.app.data.RaftAgentVoiceEngineListener
import build.raft.app.data.RaftAgentVoiceEngineState
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.events.DisconnectReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidAgentVoiceCallEngineTest {

    @Test
    fun mapsDisconnectReasonsToSharedContract() {
        assertEquals(
            RaftAgentVoiceDisconnectReason.CLIENT_INITIATED,
            mapAndroidLiveKitDisconnectReason(DisconnectReason.CLIENT_INITIATED),
        )
        assertEquals(
            RaftAgentVoiceDisconnectReason.SERVER_CLOSED,
            mapAndroidLiveKitDisconnectReason(DisconnectReason.ROOM_DELETED),
        )
        assertEquals(
            RaftAgentVoiceDisconnectReason.SERVER_CLOSED,
            mapAndroidLiveKitDisconnectReason(DisconnectReason.PARTICIPANT_REMOVED),
        )
        assertEquals(
            RaftAgentVoiceDisconnectReason.SERVER_CLOSED,
            mapAndroidLiveKitDisconnectReason(DisconnectReason.ROOM_CLOSED),
        )
        assertEquals(
            RaftAgentVoiceDisconnectReason.FAILED,
            mapAndroidLiveKitDisconnectReason(DisconnectReason.SERVER_SHUTDOWN),
        )
        assertEquals(
            RaftAgentVoiceDisconnectReason.FAILED,
            mapAndroidLiveKitDisconnectReason(DisconnectReason.JOIN_FAILURE),
        )
        assertEquals(
            RaftAgentVoiceDisconnectReason.FAILED,
            mapAndroidLiveKitDisconnectReason(DisconnectReason.CONNECTION_TIMEOUT),
        )
        assertEquals(
            RaftAgentVoiceDisconnectReason.FAILED,
            mapAndroidLiveKitDisconnectReason(DisconnectReason.MEDIA_FAILURE),
        )
        assertEquals(
            RaftAgentVoiceDisconnectReason.FAILED,
            mapAndroidLiveKitDisconnectReason(DisconnectReason.UNKNOWN_REASON),
        )
        assertEquals(
            RaftAgentVoiceDisconnectReason.FAILED,
            mapAndroidLiveKitDisconnectReason(null),
        )
    }

    @Test
    fun filtersCaptionTopicAndDecodesUtf8Json() {
        val payload = """{"type":"caption","role":"agent","text":"你好 Raft","final":true,"id":"c1"}"""
        assertEquals(
            payload,
            extractAndroidVoiceCaptionJsonOrNull("caption", payload.encodeToByteArray()),
        )
        assertNull(extractAndroidVoiceCaptionJsonOrNull("chat", payload.encodeToByteArray()))
        assertNull(extractAndroidVoiceCaptionJsonOrNull(null, payload.encodeToByteArray()))
        assertNull(extractAndroidVoiceCaptionJsonOrNull("caption", ByteArray(0)))
    }

    @Test
    fun detectsRemoteActiveSpeakersIgnoringLocalOnly() {
        assertFalse(hasRemoteActiveSpeakerFlags(emptyList()))
        assertFalse(hasRemoteActiveSpeakerFlags(listOf(false)))
        assertTrue(hasRemoteActiveSpeakerFlags(listOf(true)))
        assertTrue(hasRemoteActiveSpeakerFlags(listOf(false, true)))
    }

    @Test
    fun ordersAudioDevicesForSpeakerOnAndEarpiece() {
        val speakerOrder = preferredAudioDeviceListForSpeaker(speakerOn = true)
        assertTrue(
            speakerOrder.indexOf(AudioDevice.Speakerphone::class.java) <
                speakerOrder.indexOf(AudioDevice.Earpiece::class.java)
        )

        val earpieceOrder = preferredAudioDeviceListForSpeaker(speakerOn = false)
        assertTrue(
            earpieceOrder.indexOf(AudioDevice.Earpiece::class.java) <
                earpieceOrder.indexOf(AudioDevice.Speakerphone::class.java)
        )
    }

    @Test
    fun permissionDeniedReportsMicErrorAndFailsWithoutConnectingRoom() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var roomCreated = false
        val engine =
            AndroidAgentVoiceCallEngine(
                permissionRequester = { false },
                roomFactory = {
                    roomCreated = true
                    FakeVoiceRoomHandle()
                },
                dispatcher = dispatcher,
                logWarning = {},
            )
        val listener = RecordingListener()

        engine.connect(
            callId = "call-1",
            url = "wss://live.example.com",
            token = "tok-1",
            muted = false,
            speakerOn = true,
            listener = listener,
        )
        advanceUntilIdle()

        assertFalse(roomCreated)
        assertEquals(listOf(RaftAgentVoiceEngineState.CONNECTING), listener.states)
        assertEquals(listOf(RAFT_AGENT_VOICE_MIC_PERMISSION_DENIED), listener.micErrors)
        assertEquals(
            listOf(RaftAgentVoiceDisconnectReason.FAILED to RAFT_AGENT_VOICE_MIC_PERMISSION_DENIED),
            listener.disconnects,
        )
    }

    @Test
    fun connectMuteSpeakerTokenAndDisconnectTransitionsForwardToRoom() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fakeRoom = FakeVoiceRoomHandle()
        val engine =
            AndroidAgentVoiceCallEngine(
                permissionRequester = { true },
                roomFactory = { fakeRoom },
                dispatcher = dispatcher,
                logWarning = {},
            )
        val listener = RecordingListener()

        engine.connect(
            callId = "call-2",
            url = "wss://live.example.com",
            token = "tok-initial",
            muted = false,
            speakerOn = true,
            listener = listener,
        )
        advanceUntilIdle()

        assertEquals("wss://live.example.com", fakeRoom.connectedUrl)
        assertEquals("tok-initial", fakeRoom.connectedToken)
        assertEquals(listOf(true), fakeRoom.micStates)
        assertEquals(listOf(true), fakeRoom.speakerStates)
        assertFalse(engine.isMutedForTests)
        assertTrue(engine.isSpeakerOnForTests)

        engine.setMuted(true)
        advanceUntilIdle()
        assertTrue(engine.isMutedForTests)
        assertEquals(listOf(true, false), fakeRoom.micStates)

        engine.setMuted(false)
        advanceUntilIdle()
        assertFalse(engine.isMutedForTests)
        assertEquals(listOf(true, false, true), fakeRoom.micStates)

        engine.setSpeakerOn(false)
        assertFalse(engine.isSpeakerOnForTests)
        assertEquals(listOf(true, false), fakeRoom.speakerStates)

        engine.updateToken("tok-refreshed")
        assertEquals("tok-refreshed", engine.latestTokenForTests)

        fakeRoom.emitState(RaftAgentVoiceEngineState.RECONNECTING)
        fakeRoom.emitState(RaftAgentVoiceEngineState.CONNECTED)
        fakeRoom.emitSpeaking(true)
        fakeRoom.emitCaption("""{"type":"caption","role":"agent","text":"hi","final":true,"id":"1"}""")

        assertEquals(
            listOf(
                RaftAgentVoiceEngineState.CONNECTING,
                RaftAgentVoiceEngineState.CONNECTED,
                RaftAgentVoiceEngineState.RECONNECTING,
                RaftAgentVoiceEngineState.CONNECTED,
            ),
            listener.states,
        )
        assertEquals(listOf(true), listener.speakingEvents)
        assertEquals(1, listener.captions.size)

        engine.disconnect()
        assertTrue(fakeRoom.released)
    }

    @Test
    fun registryReportsEngineSupportedAfterRegistration() {
        val previous = RaftAgentVoiceCallEngineRegistry.currentOrNull()
        try {
            val engine =
                AndroidAgentVoiceCallEngine(
                    permissionRequester = { true },
                    roomFactory = { FakeVoiceRoomHandle() },
                    logWarning = {},
                )
            RaftAgentVoiceCallEngineRegistry.register(engine)
            assertTrue(RaftAgentVoiceCallEngineRegistry.isSupported())
            assertEquals(engine, RaftAgentVoiceCallEngineRegistry.currentOrNull())
        } finally {
            if (previous != null) {
                RaftAgentVoiceCallEngineRegistry.register(previous)
            } else {
                RaftAgentVoiceCallEngineRegistry.unregister()
            }
        }
    }

    private class FakeVoiceRoomHandle : AndroidVoiceRoomHandle {
        var connectedUrl: String? = null
        var connectedToken: String? = null
        val micStates = mutableListOf<Boolean>()
        val speakerStates = mutableListOf<Boolean>()
        var released = false
        private var sink: AndroidVoiceRoomEventSink? = null

        override suspend fun connect(
            url: String,
            token: String,
            speakerOn: Boolean,
            eventSink: AndroidVoiceRoomEventSink,
        ) {
            connectedUrl = url
            connectedToken = token
            sink = eventSink
            eventSink.onConnectionState(RaftAgentVoiceEngineState.CONNECTED)
        }

        override suspend fun setMicrophoneEnabled(enabled: Boolean): Boolean {
            micStates += enabled
            return true
        }

        override fun setSpeakerOn(speakerOn: Boolean) {
            speakerStates += speakerOn
        }

        override fun disconnectAndRelease() {
            released = true
        }

        fun emitState(state: String) {
            sink?.onConnectionState(state)
        }

        fun emitSpeaking(speaking: Boolean) {
            sink?.onAgentSpeaking(speaking)
        }

        fun emitCaption(jsonText: String) {
            sink?.onCaptionData(jsonText)
        }
    }

    private class RecordingListener : RaftAgentVoiceEngineListener {
        val states = mutableListOf<String>()
        val speakingEvents = mutableListOf<Boolean>()
        val captions = mutableListOf<String>()
        val micErrors = mutableListOf<String>()
        val disconnects = mutableListOf<Pair<String, String?>>()
        var tokenRefreshCount = 0

        override fun onConnectionState(state: String) {
            states += state
        }

        override fun onAgentSpeaking(speaking: Boolean) {
            speakingEvents += speaking
        }

        override fun onCaptionData(jsonText: String) {
            captions += jsonText
        }

        override fun onMicError(reason: String) {
            micErrors += reason
        }

        override fun onDisconnected(reason: String, detail: String?) {
            disconnects += (reason to detail)
        }

        override fun onTokenRefreshNeeded() {
            tokenRefreshCount += 1
        }
    }
}
