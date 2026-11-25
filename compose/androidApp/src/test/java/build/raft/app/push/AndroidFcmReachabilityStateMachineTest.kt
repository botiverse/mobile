package build.raft.app.push

import build.raft.app.data.RaftPushProbeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive deterministic unit tests for [AndroidFcmReachabilityStateMachine] using an injected
 * clock and deterministic probe ID generator (`#proj-mobile:#379`).
 */
class AndroidFcmReachabilityStateMachineTest {
    private var now = 1_000_000L
    private var nextId = 1

    private fun newMachine(): AndroidFcmReachabilityStateMachine =
        AndroidFcmReachabilityStateMachine(
            clockMillis = { now },
            probeIdGenerator = { "probe-${nextId++}" },
        )

    private fun eligible(
        featureEnabled: Boolean = true,
        isForeground: Boolean = true,
        signedIn: Boolean = true,
        fcmUnavailable: Boolean = false,
        hasFcmRegistration: Boolean = true,
        raftApiHealthy: Boolean = true,
        serverKey: String? = "https://api.example|s1",
        networkKey: String? = "net:100:wifi",
    ): ProbeEligibility =
        ProbeEligibility(
            featureEnabled = featureEnabled,
            isForeground = isForeground,
            signedIn = signedIn,
            fcmUnavailable = fcmUnavailable,
            hasFcmRegistration = hasFcmRegistration,
            raftApiHealthy = raftApiHealthy,
            serverKey = serverKey,
            networkKey = networkKey,
        )

    @Test
    fun featureFlagOffPerformsZeroProbesZeroTransitionsAndIgnoresFcmSignals() {
        val sm = newMachine()
        assertEquals(emptyList<FcmProbeEffect>(), sm.evaluate(eligible(featureEnabled = false)))
        assertEquals(FcmReachabilityState.Unknown, sm.snapshot().state)
        assertFalse(sm.snapshot().autoFallbackActive)

        assertEquals(emptyList<FcmProbeEffect>(), sm.onProbeReceived("probe-1", featureEnabled = false))
        assertEquals(emptyList<FcmProbeEffect>(), sm.onRealFcmDelivered(featureEnabled = false))
        assertEquals(FcmReachabilityState.Unknown, sm.snapshot().state)
    }

    @Test
    fun http404MarksServerUnsupportedAndDisablesProbingUntilTtlOrServerSwitch() {
        val sm = newMachine()
        val startEffects = sm.evaluate(eligible())
        assertTrue(startEffects.contains(FcmProbeEffect.StartHttpProbe("probe-1", "net:100:wifi")))

        val unsupportedEffects = sm.onProbeHttpResult("probe-1", RaftPushProbeResult.Unsupported, eligible())
        assertTrue(sm.isServerUnsupported())
        assertEquals(FcmReachabilityState.Unknown, sm.snapshot().state)
        assertEquals(0, sm.snapshot().consecutiveMisses)
        assertFalse(sm.snapshot().autoFallbackActive)
        assertFalse(unsupportedEffects.any { it is FcmProbeEffect.ScheduleMissTimeout })

        // Subsequent foreground checks on the same server emit zero probes.
        now += 120_000L
        assertEquals(emptyList<FcmProbeEffect>(), sm.evaluate(eligible()))
        assertEquals(emptyList<FcmProbeEffect>(), sm.onRealFcmDelivered(featureEnabled = true))
        assertEquals(FcmReachabilityState.Unknown, sm.snapshot().state)

        // Switching to a different server clears the per-server 404 hold and allows probing.
        val otherServerEffects = sm.evaluate(eligible(serverKey = "https://api.example|s2"))
        assertFalse(sm.isServerUnsupported())
        assertTrue(otherServerEffects.contains(FcmProbeEffect.StartHttpProbe("probe-2", "net:100:wifi")))
    }

    @Test
    fun foregroundOnlyPreconditionsGateProbeStart() {
        val sm = newMachine()
        assertEquals(emptyList<FcmProbeEffect>(), sm.evaluate(eligible(isForeground = false)))
        assertEquals(emptyList<FcmProbeEffect>(), sm.evaluate(eligible(signedIn = false)))
        assertEquals(emptyList<FcmProbeEffect>(), sm.evaluate(eligible(fcmUnavailable = true)))
        assertEquals(emptyList<FcmProbeEffect>(), sm.evaluate(eligible(hasFcmRegistration = false)))
        assertEquals(emptyList<FcmProbeEffect>(), sm.evaluate(eligible(raftApiHealthy = false)))
        assertEquals(emptyList<FcmProbeEffect>(), sm.evaluate(eligible(networkKey = null)))
        assertEquals(FcmReachabilityState.Unknown, sm.snapshot().state)
    }

    @Test
    fun fcmProbeArrivingBeforeHttpResponseStillMatchesAndIsNotClobberedByLateHttpSent() {
        val sm = newMachine()
        val startEffects = sm.evaluate(eligible())
        assertTrue(startEffects.contains(FcmProbeEffect.StartHttpProbe("probe-1", "net:100:wifi")))
        assertEquals(FcmReachabilityStatus.Probing, sm.snapshot().state.status)

        // FCM silent probe arrives BEFORE the HTTP response returns.
        now += 500L
        val receivedEffects = sm.onProbeReceived("probe-1", featureEnabled = true)
        assertTrue(receivedEffects.contains(FcmProbeEffect.CancelMissTimeout))
        assertEquals(FcmReachabilityState.Reachable(confirmedAtMillis = now), sm.snapshot().state)
        assertEquals(0, sm.snapshot().consecutiveMisses)

        // HTTP response arrives afterwards with status="sent": must NOT overwrite Reachable or schedule timeout!
        now += 200L
        val httpEffects = sm.onProbeHttpResult("probe-1", RaftPushProbeResult.Sent("probe-1"), eligible())
        assertEquals(emptyList<FcmProbeEffect>(), httpEffects)
        assertEquals(FcmReachabilityStatus.Reachable, sm.snapshot().state.status)
    }

    @Test
    fun notSentAndHttpErrorsNeverCountAsMissesAndTriggerReRegisterWhenRequested() {
        val sm = newMachine()
        sm.evaluate(eligible())

        // not_sent with reRegister=true -> never counts as miss, emits RequestPushReRegistration.
        val notSentEffects =
            sm.onProbeHttpResult(
                probeId = "probe-1",
                result = RaftPushProbeResult.NotSent(reason = "token_unregistered", reRegister = true),
                eligibility = eligible(),
            )
        assertEquals(FcmReachabilityState.Unknown, sm.snapshot().state)
        assertEquals(0, sm.snapshot().consecutiveMisses)
        assertFalse(sm.snapshot().autoFallbackActive)
        assertFalse(notSentEffects.any { it is FcmProbeEffect.ScheduleMissTimeout })
        assertTrue(notSentEffects.contains(FcmProbeEffect.RequestPushReRegistration("token_unregistered")))
        assertTrue(notSentEffects.contains(FcmProbeEffect.ScheduleRetryProbe(AndroidFcmReachabilityStateMachine.MIN_RETRY_GAP_MS)))

        // Advance past retry gap and simulate HTTP 500 / network error (Inconclusive).
        now += AndroidFcmReachabilityStateMachine.MIN_RETRY_GAP_MS
        val retryEffects = sm.evaluate(eligible())
        assertTrue(retryEffects.contains(FcmProbeEffect.StartHttpProbe("probe-2", "net:100:wifi")))

        val inconclusiveEffects =
            sm.onProbeHttpResult("probe-2", RaftPushProbeResult.Inconclusive("http_500"), eligible())
        assertEquals(FcmReachabilityState.Unknown, sm.snapshot().state)
        assertEquals(0, sm.snapshot().consecutiveMisses)
        assertFalse(sm.snapshot().autoFallbackActive)
        assertFalse(inconclusiveEffects.any { it is FcmProbeEffect.ScheduleMissTimeout })
    }

    @Test
    fun requiresTwoConsecutiveMissesAtLeast60SecondsApartOnSameNetworkWithHealthyApi() {
        val sm = newMachine()

        // Probe 1 -> HTTP sent -> starts 15 s timer.
        val start1 = sm.evaluate(eligible())
        assertTrue(start1.contains(FcmProbeEffect.StartHttpProbe("probe-1", "net:100:wifi")))
        val sent1 = sm.onProbeHttpResult("probe-1", RaftPushProbeResult.Sent("probe-1"), eligible())
        assertTrue(sent1.contains(FcmProbeEffect.ScheduleMissTimeout("probe-1", 15_000L)))

        // 15 s elapses -> 1st miss does NOT mark Unreachable and does NOT activate fallback.
        now += 15_000L
        val miss1 = sm.onProbeTimeout("probe-1", eligible())
        assertEquals(FcmReachabilityState.Unknown, sm.snapshot().state)
        assertEquals(1, sm.snapshot().consecutiveMisses)
        assertFalse(sm.snapshot().autoFallbackActive)
        assertTrue(miss1.contains(FcmProbeEffect.ScheduleRetryProbe(60_000L)))

        // Retrying before 60 s must wait the remaining gap.
        now += 30_000L
        val tooEarly = sm.evaluate(eligible())
        assertFalse(tooEarly.any { it is FcmProbeEffect.StartHttpProbe })
        assertTrue(tooEarly.contains(FcmProbeEffect.ScheduleRetryProbe(30_000L)))

        // At 60 s after miss 1 -> Probe 2 starts on same network.
        now += 30_000L
        val start2 = sm.evaluate(eligible())
        assertTrue(start2.contains(FcmProbeEffect.StartHttpProbe("probe-2", "net:100:wifi")))
        val sent2 = sm.onProbeHttpResult("probe-2", RaftPushProbeResult.Sent("probe-2"), eligible())
        assertTrue(sent2.contains(FcmProbeEffect.ScheduleMissTimeout("probe-2", 15_000L)))

        // If Raft API went unhealthy before timeout, timeout must NOT count as a miss!
        now += 15_000L
        val unhealthyTimeout = sm.onProbeTimeout("probe-2", eligible(raftApiHealthy = false))
        assertEquals(FcmReachabilityState.Unknown, sm.snapshot().state)
        assertEquals(1, sm.snapshot().consecutiveMisses)
        assertFalse(sm.snapshot().autoFallbackActive)
        assertFalse(unhealthyTimeout.any { it is FcmProbeEffect.AutoFallbackChanged })

        // Now complete Probe 3 with healthy API -> 2nd consecutive miss -> Unreachable + AutoFallback ON!
        val start3 = sm.evaluate(eligible())
        assertTrue(start3.contains(FcmProbeEffect.StartHttpProbe("probe-3", "net:100:wifi")))
        sm.onProbeHttpResult("probe-3", RaftPushProbeResult.Sent("probe-3"), eligible())
        now += 15_000L
        val miss2 = sm.onProbeTimeout("probe-3", eligible())
        assertEquals(FcmReachabilityState.Unreachable(confirmedAtMillis = now), sm.snapshot().state)
        assertEquals(2, sm.snapshot().consecutiveMisses)
        assertTrue(sm.snapshot().autoFallbackActive)
        assertTrue(miss2.contains(FcmProbeEffect.AutoFallbackChanged(active = true, reason = "fcm_probe_unreachable")))

        // Once Unreachable on the same network, further foreground checks do not spam probes.
        now += 120_000L
        assertFalse(sm.evaluate(eligible()).any { it is FcmProbeEffect.StartHttpProbe })
    }

    @Test
    fun lateArrivingProbeAfterTimeoutOrUnreachableImmediatelyFlipsToReachable() {
        val sm = newMachine()

        // Drive to Unreachable (2 misses on net:100:wifi).
        driveToUnreachable(sm)
        assertEquals(FcmReachabilityStatus.Unreachable, sm.snapshot().state.status)
        assertTrue(sm.snapshot().autoFallbackActive)

        // Unknown probeId is ignored.
        assertEquals(emptyList<FcmProbeEffect>(), sm.onProbeReceived("bogus-id", featureEnabled = true))
        assertEquals(FcmReachabilityStatus.Unreachable, sm.snapshot().state.status)

        // Advance past 5-minute dwell, then late probe-2 arrives!
        now += AndroidFcmReachabilityStateMachine.MIN_FALLBACK_DWELL_MS
        val lateEffects = sm.onProbeReceived("probe-2", featureEnabled = true)
        assertEquals(FcmReachabilityState.Reachable(confirmedAtMillis = now), sm.snapshot().state)
        assertEquals(0, sm.snapshot().consecutiveMisses)
        assertFalse(sm.snapshot().autoFallbackActive)
        assertTrue(lateEffects.contains(FcmProbeEffect.AutoFallbackChanged(active = false, reason = "fcm_probe_received")))
    }

    @Test
    fun networkChangeInvalidatesConclusionButKeepsFallbackActiveThroughUncertaintyAndAntiFlapDwell() {
        val sm = newMachine()
        driveToUnreachable(sm)
        val fallbackActivatedAt = now
        assertTrue(sm.snapshot().autoFallbackActive)

        // 1 minute later (inside 5-min dwell), switch network net:100:wifi -> net:200:cell.
        now = fallbackActivatedAt + 60_000L
        val switchEffects = sm.evaluate(eligible(networkKey = "net:200:cell"))
        // State invalidated to Unknown -> immediately starts probing net:200:cell.
        assertEquals(FcmReachabilityStatus.Probing, sm.snapshot().state.status)
        assertEquals(0, sm.snapshot().consecutiveMisses)
        // FAIL-SAFE: autoFallbackActive MUST stay true while Unknown/Probing!
        assertTrue(sm.snapshot().autoFallbackActive)
        assertFalse(switchEffects.any { it is FcmProbeEffect.AutoFallbackChanged })
        val cellProbe = switchEffects.filterIsInstance<FcmProbeEffect.StartHttpProbe>().single()

        // Probe returns not_sent on net:200:cell -> FAIL-SAFE: autoFallbackActive STILL stays true!
        sm.onProbeHttpResult(cellProbe.probeId, RaftPushProbeResult.NotSent("temporary", reRegister = false), eligible(networkKey = "net:200:cell"))
        assertEquals(FcmReachabilityState.Unknown, sm.snapshot().state)
        assertTrue(sm.snapshot().autoFallbackActive)

        // Real FCM arrives on net:200:cell at t + 2 min (still inside 5-min dwell!):
        // state immediately flips Reachable, but fallback respects remaining 3-min dwell so rapid flips don't bounce FGS!
        now = fallbackActivatedAt + 120_000L
        val realFcmEffects = sm.onRealFcmDelivered(featureEnabled = true)
        assertEquals(FcmReachabilityState.Reachable(confirmedAtMillis = now), sm.snapshot().state)
        assertTrue(sm.snapshot().autoFallbackActive)
        assertFalse(realFcmEffects.any { it is FcmProbeEffect.AutoFallbackChanged })
        assertTrue(realFcmEffects.contains(FcmProbeEffect.ScheduleDwellExpiry(delayMillis = 180_000L)))

        // Rapid flip to net:300:wifi before dwell expires cancels dwell and stays fail-safe ON.
        now = fallbackActivatedAt + 150_000L
        val flapEffects = sm.evaluate(eligible(networkKey = "net:300:wifi"))
        assertTrue(flapEffects.contains(FcmProbeEffect.CancelDwellExpiry))
        assertTrue(sm.snapshot().autoFallbackActive)
        // Even if a stale dwell timer fired now while Unknown/Probing, fallback MUST NOT turn off!
        assertEquals(emptyList<FcmProbeEffect>(), sm.onDwellTimerExpired(featureEnabled = true))
        assertTrue(sm.snapshot().autoFallbackActive)

        // Prove Reachable on net:300:wifi and let remaining dwell elapse -> fallback turns OFF cleanly!
        val wifi3Probe = flapEffects.filterIsInstance<FcmProbeEffect.StartHttpProbe>().single()
        now = fallbackActivatedAt + 200_000L
        val reachEffects = sm.onProbeReceived(wifi3Probe.probeId, featureEnabled = true)
        assertTrue(reachEffects.contains(FcmProbeEffect.ScheduleDwellExpiry(delayMillis = 100_000L)))
        assertTrue(sm.snapshot().autoFallbackActive)

        now = fallbackActivatedAt + AndroidFcmReachabilityStateMachine.MIN_FALLBACK_DWELL_MS
        val dwellDone = sm.onDwellTimerExpired(featureEnabled = true)
        assertFalse(sm.snapshot().autoFallbackActive)
        assertTrue(dwellDone.contains(FcmProbeEffect.AutoFallbackChanged(active = false, reason = "fcm_dwell_elapsed")))
    }

    @Test
    fun reachableExpiresAfter24HoursAndReProbesOnForeground() {
        val sm = newMachine()
        sm.evaluate(eligible())
        sm.onProbeReceived("probe-1", featureEnabled = true)
        assertEquals(FcmReachabilityState.Reachable(confirmedAtMillis = now), sm.snapshot().state)

        // 23h59m later: still fresh, no new probe.
        now += AndroidFcmReachabilityStateMachine.REACHABLE_TTL_MS - 1_000L
        assertFalse(sm.evaluate(eligible()).any { it is FcmProbeEffect.StartHttpProbe })

        // At 24h: TTL expired, triggers a fresh foreground probe.
        now += 1_000L
        val refreshEffects = sm.evaluate(eligible())
        assertTrue(refreshEffects.contains(FcmProbeEffect.StartHttpProbe("probe-2", "net:100:wifi")))
    }

    @Test
    fun backgroundingDuringInFlightProbeCancelsTimeoutWithoutCountingMiss() {
        val sm = newMachine()
        sm.evaluate(eligible())
        sm.onProbeHttpResult("probe-1", RaftPushProbeResult.Sent("probe-1"), eligible())
        assertEquals(FcmReachabilityStatus.Probing, sm.snapshot().state.status)

        val bgEffects = sm.evaluate(eligible(isForeground = false))
        assertTrue(bgEffects.contains(FcmProbeEffect.CancelMissTimeout))
        assertEquals(FcmReachabilityState.Unknown, sm.snapshot().state)
        assertEquals(0, sm.snapshot().consecutiveMisses)
    }

    @Test
    fun buildAndroidNetworkKeyIsStablePerHandleAndDistinguishesTransports() {
        val wifi1 = buildAndroidNetworkKey(101L, hasWifi = true, hasCellular = false, hasVpn = false, hasEthernet = false)
        val wifi1Again = buildAndroidNetworkKey(101L, hasWifi = true, hasCellular = false, hasVpn = false, hasEthernet = false)
        val wifi1Vpn = buildAndroidNetworkKey(101L, hasWifi = true, hasCellular = false, hasVpn = true, hasEthernet = false)
        val cell = buildAndroidNetworkKey(202L, hasWifi = false, hasCellular = true, hasVpn = false, hasEthernet = false)
        assertEquals(wifi1, wifi1Again)
        assertTrue(wifi1 != wifi1Vpn)
        assertTrue(wifi1 != cell)
    }

    @Test
    fun rateLimitedNotSentHonorsServerRetryAfterSeconds() {
        val sm = newMachine()
        val startEffects = sm.evaluate(eligible())
        val probeId = startEffects.filterIsInstance<FcmProbeEffect.StartHttpProbe>().single().probeId

        val parsed =
            build.raft.app.data.parseRaftPushProbeResponse(
                code = 200,
                bodyText = """{"ok":true,"probeId":"$probeId","status":"not_sent","reason":"rate_limited","retryAfterSeconds":600}""",
                expectedProbeId = probeId,
            )
        assertEquals(
            RaftPushProbeResult.NotSent(reason = "rate_limited", reRegister = false, retryAfterSeconds = 600L),
            parsed,
        )

        val effects = sm.onProbeHttpResult(probeId, parsed, eligible())
        assertEquals(FcmReachabilityState.Unknown, sm.snapshot().state)
        assertEquals(0, sm.snapshot().consecutiveMisses)
        assertEquals(now + 600_000L, sm.snapshot().nextRetryAllowedAtMillis)
        assertTrue(effects.contains(FcmProbeEffect.ScheduleRetryProbe(delayMillis = 600_000L)))
    }

    private fun driveToUnreachable(sm: AndroidFcmReachabilityStateMachine) {
        val e1 = sm.evaluate(eligible())
        val p1 = e1.filterIsInstance<FcmProbeEffect.StartHttpProbe>().single().probeId
        sm.onProbeHttpResult(p1, RaftPushProbeResult.Sent(p1), eligible())
        now += AndroidFcmReachabilityStateMachine.PROBE_TIMEOUT_MS
        sm.onProbeTimeout(p1, eligible())
        now += AndroidFcmReachabilityStateMachine.MIN_RETRY_GAP_MS
        val e2 = sm.evaluate(eligible())
        val p2 = e2.filterIsInstance<FcmProbeEffect.StartHttpProbe>().single().probeId
        sm.onProbeHttpResult(p2, RaftPushProbeResult.Sent(p2), eligible())
        now += AndroidFcmReachabilityStateMachine.PROBE_TIMEOUT_MS
        sm.onProbeTimeout(p2, eligible())
    }
}
