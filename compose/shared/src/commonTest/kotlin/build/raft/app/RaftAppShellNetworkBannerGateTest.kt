package build.raft.app

import build.raft.app.RaftAppShellNetworkStatus.Offline
import build.raft.app.RaftAppShellNetworkStatus.Online
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class RaftAppShellNetworkBannerGateTest {
    @Test
    fun offlineBlipShorterThanGraceNeverShowsBanner() =
        runTest {
            val gate = RaftAppShellNetworkBannerGate(backgroundScope)
            val emitted = mutableListOf<RaftAppShellNetworkStatus>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                gate.bannerStatus.collect { emitted += it }
            }

            gate.onHostStatus(Offline)
            advanceTimeBy(2_999)
            runCurrent()
            assertEquals(Online, gate.bannerStatus.value, "banner shown before 3s grace elapsed")
            gate.onHostStatus(Online)
            advanceTimeBy(10_000)
            runCurrent()

            assertEquals(listOf(Online), emitted, "a sub-3s blip must never emit Offline")
        }

    @Test
    fun continuousOfflineForGraceShowsBanner() =
        runTest {
            val gate = RaftAppShellNetworkBannerGate(backgroundScope)

            gate.onHostStatus(Offline)
            // A repeated Offline signal (e.g. DNS change while still down) must not restart the window.
            advanceTimeBy(2_000)
            gate.onHostStatus(Offline)
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(Offline, gate.bannerStatus.value)
        }

    @Test
    fun goingOnlineHidesBannerImmediately() =
        runTest {
            val gate = RaftAppShellNetworkBannerGate(backgroundScope)
            gate.onHostStatus(Offline)
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(Offline, gate.bannerStatus.value)

            gate.onHostStatus(Online)

            assertEquals(Online, gate.bannerStatus.value, "Online must hide the banner without waiting")
        }

    @Test
    fun offlineOnlineOfflineFlapRestartsGrace() =
        runTest {
            val gate = RaftAppShellNetworkBannerGate(backgroundScope)

            gate.onHostStatus(Offline)
            advanceTimeBy(2_000)
            gate.onHostStatus(Online)
            advanceTimeBy(500)
            gate.onHostStatus(Offline)
            // 3.5s since the first Offline, but only 1s since the latest one.
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(Online, gate.bannerStatus.value, "flap must restart the 3s grace window")

            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(Offline, gate.bannerStatus.value, "3s after the latest Offline the banner shows")
        }
}
