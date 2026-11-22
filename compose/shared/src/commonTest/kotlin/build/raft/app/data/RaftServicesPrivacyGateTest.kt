package build.raft.app.data

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Owner ruling (observability/privacy): automatic requests to Raft's own services (Hands crash,
 * ANR, system-kill and WebView-crash uploads, the device ping, automatic update checks) go only
 * when every server the app is signed in to answered privacy mode off.
 */
class RaftServicesPrivacyGateTest {
    @AfterTest
    fun resetProcessState() {
        RaftTelemetryPrivacy.publish(RaftPrivacyMode.Unknown)
    }

    @Test
    fun acrossServersIsOffOnlyWhenEveryOneAnsweredOff() {
        val off = RaftPrivacyMode.Off
        val on = RaftPrivacyMode.On
        val unknown = RaftPrivacyMode.Unknown
        assertEquals(off, raftPrivacyModeAcross(listOf(off)))
        assertEquals(off, raftPrivacyModeAcross(listOf(off, off, off)), "all off")
        assertEquals(on, raftPrivacyModeAcross(listOf(off, on, off)), "any on")
        assertEquals(on, raftPrivacyModeAcross(listOf(unknown, on)), "on wins over unknown")
        assertEquals(unknown, raftPrivacyModeAcross(listOf(off, unknown)), "any unknown")
        assertEquals(unknown, raftPrivacyModeAcross(emptyList()), "no server answered")
    }

    @Test
    fun automaticRequestsGoOnlyWhenAllSignedInServersAreOff() {
        assertTrue(RaftServicesPrivacyGate.allowsAutomatic(RaftPrivacyMode.Off))
        assertFalse(RaftServicesPrivacyGate.allowsAutomatic(RaftPrivacyMode.On))
        assertFalse(RaftServicesPrivacyGate.allowsAutomatic(RaftPrivacyMode.Unknown))
    }

    @Test
    fun aFailedReadIsOffForTracesButUnknownForDeferrableUploads() {
        RaftTelemetryPrivacy.publish("https://self-hosted.example/api", RaftPrivacyAnswer.Failed)
        assertEquals(RaftPrivacyMode.Off, RaftTelemetryPrivacy.state.value)
        assertEquals(RaftPrivacyMode.Unknown, RaftTelemetryPrivacy.acrossSignedInServers.value)
        assertFalse(RaftServicesPrivacyGate.allowsAutomatic())

        // A successful response, even without the field (an older server), is off for both.
        RaftTelemetryPrivacy.publish("https://self-hosted.example/api", RaftPrivacyAnswer.Off)
        assertEquals(RaftPrivacyMode.Off, RaftTelemetryPrivacy.acrossSignedInServers.value)
        assertTrue(RaftServicesPrivacyGate.allowsAutomatic())
    }

    @Test
    fun aLaunchWaitsForTheAnswerThenDecidesOnce() = runTest {
        val across = MutableStateFlow(RaftPrivacyMode.Unknown)
        val decision = async { RaftServicesPrivacyGate.awaitLaunchDecision(across, waitMillis = 60_000L) }
        advanceTimeBy(5_000L)
        runCurrent()
        assertFalse(decision.isCompleted, "held while a server is unknown")
        across.value = RaftPrivacyMode.Off
        runCurrent()
        assertEquals(RaftPrivacyMode.Off, decision.await())
    }

    @Test
    fun aServerThatNeverAnswersSkipsThisLaunch() = runTest {
        val across = MutableStateFlow(RaftPrivacyMode.Unknown)
        val decision = async { RaftServicesPrivacyGate.awaitLaunchDecision(across, waitMillis = 60_000L) }
        advanceTimeBy(60_001L)
        runCurrent()
        assertEquals(RaftPrivacyMode.Unknown, decision.await())
        assertFalse(RaftServicesPrivacyGate.allowsAutomatic(decision.await()))
    }
}
