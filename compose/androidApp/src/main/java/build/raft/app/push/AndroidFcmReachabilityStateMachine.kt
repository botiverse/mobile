package build.raft.app.push

import build.raft.app.data.RaftPushProbeResult

internal enum class FcmReachabilityStatus {
    Unknown,
    Probing,
    Reachable,
    Unreachable,
}

internal sealed interface FcmReachabilityState {
    val status: FcmReachabilityStatus

    data object Unknown : FcmReachabilityState {
        override val status: FcmReachabilityStatus = FcmReachabilityStatus.Unknown
    }

    data class Probing(
        val probeId: String,
        val networkKey: String,
        val startedAtMillis: Long,
        val httpSentConfirmed: Boolean = false,
        val deadlineAtMillis: Long? = null,
    ) : FcmReachabilityState {
        override val status: FcmReachabilityStatus = FcmReachabilityStatus.Probing
    }

    data class Reachable(val confirmedAtMillis: Long) : FcmReachabilityState {
        override val status: FcmReachabilityStatus = FcmReachabilityStatus.Reachable
    }

    data class Unreachable(val confirmedAtMillis: Long) : FcmReachabilityState {
        override val status: FcmReachabilityStatus = FcmReachabilityStatus.Unreachable
    }
}

internal data class FcmReachabilitySnapshot(
    val state: FcmReachabilityState,
    val networkKey: String?,
    val consecutiveMisses: Int,
    val autoFallbackActive: Boolean,
    val autoFallbackActivatedAtMillis: Long?,
    val serverUnsupported: Boolean,
    val nextRetryAllowedAtMillis: Long?,
)

internal data class AndroidNetworkSnapshot(
    val networkKey: String?,
    val online: Boolean,
)

/**
 * Deterministic per-network signature built from Android's [android.net.Network.getNetworkHandle]
 * plus coarse transport flags. Stable across signal-strength / bandwidth capability updates on the
 * same network, while changing immediately across Wi-Fi <-> cellular, SSID switches, or VPN toggles.
 */
internal fun buildAndroidNetworkKey(
    networkHandle: Long,
    hasWifi: Boolean,
    hasCellular: Boolean,
    hasVpn: Boolean,
    hasEthernet: Boolean,
): String {
    val transports =
        buildList {
            if (hasWifi) add("wifi")
            if (hasCellular) add("cell")
            if (hasVpn) add("vpn")
            if (hasEthernet) add("eth")
        }.ifEmpty { listOf("other") }
    return "net:$networkHandle:${transports.joinToString("+")}"
}

internal data class ProbeEligibility(
    val featureEnabled: Boolean,
    val isForeground: Boolean,
    val signedIn: Boolean,
    val fcmUnavailable: Boolean,
    val hasFcmRegistration: Boolean,
    val raftApiHealthy: Boolean,
    val serverKey: String?,
    val networkKey: String?,
)

internal sealed interface FcmProbeEffect {
    data class StartHttpProbe(val probeId: String, val networkKey: String) : FcmProbeEffect

    data class ScheduleMissTimeout(val probeId: String, val delayMillis: Long) : FcmProbeEffect

    data object CancelMissTimeout : FcmProbeEffect

    data class ScheduleRetryProbe(val delayMillis: Long) : FcmProbeEffect

    data object CancelRetryProbe : FcmProbeEffect

    data class ScheduleDwellExpiry(val delayMillis: Long) : FcmProbeEffect

    data object CancelDwellExpiry : FcmProbeEffect

    data class RequestPushReRegistration(val reason: String) : FcmProbeEffect

    data class AutoFallbackChanged(val active: Boolean, val reason: String) : FcmProbeEffect
}

/**
 * Pure, deterministic state machine for Android's end-to-end silent FCM reachability probe
 * (`mobile_fcm_probe_v0`, `#proj-mobile:#379`).
 *
 * Invariants:
 * 1. Flag OFF or server HTTP 404 (for the current server/TTL) -> zero probes, zero transitions,
 *    zero auto-fallback.
 * 2. Probes trigger ONLY in the foreground when signed in, GMS is available, an FCM registration
 *    is settled, Raft API is healthy, and either state is [FcmReachabilityState.Unknown], a first
 *    miss retry is due, or [FcmReachabilityState.Reachable] is older than [reachableTtlMillis].
 * 3. `probeId` is generated BEFORE the HTTP request and remembered so an FCM arriving before the
 *    HTTP response — or late after the 15 s timeout — still matches and flips to [Reachable].
 * 4. ONLY HTTP `status == "sent"` starts the 15-second miss timer. `not_sent`, 404, 5xx, network
 *    errors, and background/offline interruptions NEVER count as FCM misses.
 * 5. Transitioning to [FcmReachabilityState.Unreachable] requires [missesToMarkUnreachable] (2)
 *    consecutive misses on the SAME network separated by at least [minRetryGapMillis] (60 s) while
 *    Raft API stays healthy throughout.
 * 6. Fail-safe + anti-flap dwell: once [autoFallbackActive] switches ON, network changes, `Unknown`,
 *    `Probing`, or `not_sent` NEVER stop it. It turns OFF only once FCM is positively proven
 *    [FcmReachabilityState.Reachable] AND at least [minFallbackDwellMillis] (5 min) have elapsed
 *    since fallback activated (or when the feature disables / server returns 404 / user signs out).
 */
internal class AndroidFcmReachabilityStateMachine(
    private val clockMillis: () -> Long,
    private val probeIdGenerator: () -> String,
    private val probeTimeoutMillis: Long = PROBE_TIMEOUT_MS,
    private val minRetryGapMillis: Long = MIN_RETRY_GAP_MS,
    private val reachableTtlMillis: Long = REACHABLE_TTL_MS,
    private val minFallbackDwellMillis: Long = MIN_FALLBACK_DWELL_MS,
    private val unsupportedServerTtlMillis: Long = UNSUPPORTED_SERVER_TTL_MS,
    private val missesToMarkUnreachable: Int = MISSES_TO_MARK_UNREACHABLE,
    private val maxKnownProbeIds: Int = MAX_KNOWN_PROBE_IDS,
) {
    private val lock = Any()

    private var state: FcmReachabilityState = FcmReachabilityState.Unknown
    private var previousStateBeforeProbe: FcmReachabilityState = FcmReachabilityState.Unknown
    private var currentNetworkKey: String? = null
    private var currentServerKey: String? = null
    private var consecutiveMisses: Int = 0
    private var nextRetryAllowedAtMillis: Long? = null
    private var autoFallbackActive: Boolean = false
    private var autoFallbackActivatedAtMillis: Long? = null
    private var serverUnsupportedUntilMillis: Long? = null
    private val knownProbeIds = LinkedHashMap<String, String>()

    fun snapshot(): FcmReachabilitySnapshot =
        synchronized(lock) {
            val now = clockMillis()
            FcmReachabilitySnapshot(
                state = state,
                networkKey = currentNetworkKey,
                consecutiveMisses = consecutiveMisses,
                autoFallbackActive = autoFallbackActive,
                autoFallbackActivatedAtMillis = autoFallbackActivatedAtMillis,
                serverUnsupported = isServerUnsupportedLocked(now),
                nextRetryAllowedAtMillis = nextRetryAllowedAtMillis,
            )
        }

    fun isServerUnsupported(): Boolean =
        synchronized(lock) {
            isServerUnsupportedLocked(clockMillis())
        }

    fun evaluate(eligibility: ProbeEligibility): List<FcmProbeEffect> =
        synchronized(lock) {
            val now = clockMillis()
            val effects = mutableListOf<FcmProbeEffect>()

            if (!eligibility.featureEnabled) {
                resetLocked(reason = "feature_disabled", clearServerUnsupported = true, effects = effects)
                return@synchronized effects
            }

            if (!eligibility.signedIn || eligibility.fcmUnavailable) {
                val reason = if (!eligibility.signedIn) "signed_out" else "fcm_unavailable"
                resetLocked(reason = reason, clearServerUnsupported = !eligibility.signedIn, effects = effects)
                return@synchronized effects
            }

            if (eligibility.serverKey != currentServerKey) {
                currentServerKey = eligibility.serverKey
                serverUnsupportedUntilMillis = null
            }

            if (isServerUnsupportedLocked(now)) {
                resetLocked(reason = "server_unsupported", clearServerUnsupported = false, effects = effects)
                return@synchronized effects
            } else if (serverUnsupportedUntilMillis != null) {
                serverUnsupportedUntilMillis = null
            }

            // Network binding / invalidation: initial binding (null -> net) preserves an already-proven
            // Reachable state (e.g. real FCM arrived before ConnectivityManager reported), while a real
            // network switch (netA -> netB) invalidates Reachable/Unreachable -> Unknown while keeping
            // autoFallbackActive untouched (fail-safe + anti-flap).
            val incomingNetworkKey = eligibility.networkKey?.takeIf { it.isNotBlank() }
            if (incomingNetworkKey != null) {
                if (currentNetworkKey == null) {
                    currentNetworkKey = incomingNetworkKey
                } else if (currentNetworkKey != incomingNetworkKey) {
                    currentNetworkKey = incomingNetworkKey
                    if (state is FcmReachabilityState.Probing) {
                        effects += FcmProbeEffect.CancelMissTimeout
                    }
                    effects += FcmProbeEffect.CancelRetryProbe
                    effects += FcmProbeEffect.CancelDwellExpiry
                    state = FcmReachabilityState.Unknown
                    previousStateBeforeProbe = FcmReachabilityState.Unknown
                    consecutiveMisses = 0
                    nextRetryAllowedAtMillis = null
                }
            }

            // If we were probing and left foreground, lost API health, or lost settled registration,
            // abort the in-flight probe without counting a miss. Keep knownProbeIds so a still-arriving
            // probe can confirm Reachable.
            if (
                state is FcmReachabilityState.Probing &&
                (!eligibility.isForeground || !eligibility.raftApiHealthy || !eligibility.hasFcmRegistration)
            ) {
                effects += FcmProbeEffect.CancelMissTimeout
                state = previousStateBeforeProbe
            }

            if (!eligibility.isForeground) {
                if (nextRetryAllowedAtMillis != null) {
                    effects += FcmProbeEffect.CancelRetryProbe
                }
                if (autoFallbackActive && state is FcmReachabilityState.Reachable) {
                    maybeCompleteDwellLocked(now, reason = "fcm_dwell_elapsed", effects = effects)
                }
                return@synchronized effects
            }

            if (autoFallbackActive && state is FcmReachabilityState.Reachable) {
                maybeCompleteDwellLocked(now, reason = "fcm_dwell_elapsed", effects = effects)
            }

            val networkKey = incomingNetworkKey
            if (!eligibility.hasFcmRegistration || !eligibility.raftApiHealthy || networkKey == null) {
                return@synchronized effects
            }

            when (val current = state) {
                is FcmReachabilityState.Probing -> return@synchronized effects
                is FcmReachabilityState.Unreachable -> return@synchronized effects
                is FcmReachabilityState.Reachable -> {
                    val age = now - current.confirmedAtMillis
                    if (age in 0L until reachableTtlMillis) {
                        return@synchronized effects
                    }
                }
                FcmReachabilityState.Unknown -> Unit
            }

            val retryAllowedAt = nextRetryAllowedAtMillis
            if (retryAllowedAt != null && now < retryAllowedAt) {
                effects += FcmProbeEffect.ScheduleRetryProbe(delayMillis = retryAllowedAt - now)
                return@synchronized effects
            }

            val probeId = probeIdGenerator().trim()
            if (probeId.isEmpty()) return@synchronized effects
            recordKnownProbeIdLocked(probeId, networkKey)
            previousStateBeforeProbe = state
            state =
                FcmReachabilityState.Probing(
                    probeId = probeId,
                    networkKey = networkKey,
                    startedAtMillis = now,
                    httpSentConfirmed = false,
                    deadlineAtMillis = null,
                )
            nextRetryAllowedAtMillis = null
            effects += FcmProbeEffect.CancelRetryProbe
            effects += FcmProbeEffect.StartHttpProbe(probeId = probeId, networkKey = networkKey)
            effects
        }

    fun onProbeHttpResult(
        probeId: String,
        result: RaftPushProbeResult,
        eligibility: ProbeEligibility,
    ): List<FcmProbeEffect> =
        synchronized(lock) {
            val now = clockMillis()
            val effects = mutableListOf<FcmProbeEffect>()
            if (!eligibility.featureEnabled) return@synchronized effects

            // Handle HTTP 404 even if the probe finished out-of-order so we stop probing this server.
            if (result is RaftPushProbeResult.Unsupported) {
                if (eligibility.serverKey == currentServerKey) {
                    serverUnsupportedUntilMillis = now + unsupportedServerTtlMillis
                    resetLocked(reason = "server_unsupported", clearServerUnsupported = false, effects = effects)
                }
                return@synchronized effects
            }

            val current = state as? FcmReachabilityState.Probing
            if (current == null || current.probeId != probeId) {
                // E.g. FCM probe ALREADY arrived before HTTP response and flipped state to Reachable,
                // or network switched while HTTP was in flight. Never clobber Reachable or start a timer!
                if (result is RaftPushProbeResult.NotSent && result.reRegister) {
                    effects += FcmProbeEffect.RequestPushReRegistration(result.reason)
                }
                return@synchronized effects
            }

            when (result) {
                is RaftPushProbeResult.Sent -> {
                    val sameNetwork = eligibility.networkKey == null || eligibility.networkKey == current.networkKey
                    if (!eligibility.isForeground || !eligibility.signedIn || eligibility.fcmUnavailable || !eligibility.raftApiHealthy || !sameNetwork) {
                        state = previousStateBeforeProbe
                    } else {
                        state =
                            current.copy(
                                httpSentConfirmed = true,
                                deadlineAtMillis = now + probeTimeoutMillis,
                            )
                        effects += FcmProbeEffect.ScheduleMissTimeout(probeId = probeId, delayMillis = probeTimeoutMillis)
                    }
                }
                is RaftPushProbeResult.NotSent -> {
                    // NEVER counts as an FCM miss; keep current transport active (fail-safe).
                    // Honor server retryAfterSeconds (e.g. 10m burst / 24h daily rate limit) as a floor.
                    state = previousStateBeforeProbe
                    val retryAfterMillis =
                        result.retryAfterSeconds?.coerceIn(0L, REACHABLE_TTL_MS / 1_000L)?.times(1_000L) ?: 0L
                    val retryDelay = maxOf(minRetryGapMillis, retryAfterMillis)
                    nextRetryAllowedAtMillis = now + retryDelay
                    if (result.reRegister) {
                        effects += FcmProbeEffect.RequestPushReRegistration(result.reason)
                    }
                    if (eligibility.isForeground) {
                        effects += FcmProbeEffect.ScheduleRetryProbe(delayMillis = retryDelay)
                    }
                }
                is RaftPushProbeResult.Inconclusive -> {
                    // Network error / 5xx / timeout on HTTP call NEVER counts as an FCM miss.
                    state = previousStateBeforeProbe
                    nextRetryAllowedAtMillis = now + minRetryGapMillis
                    if (eligibility.isForeground) {
                        effects += FcmProbeEffect.ScheduleRetryProbe(delayMillis = minRetryGapMillis)
                    }
                }
                RaftPushProbeResult.Unsupported -> Unit // handled above
            }
            effects
        }

    fun onProbeTimeout(probeId: String, eligibility: ProbeEligibility): List<FcmProbeEffect> =
        synchronized(lock) {
            val now = clockMillis()
            val effects = mutableListOf<FcmProbeEffect>()
            if (!eligibility.featureEnabled) return@synchronized effects

            val current = state as? FcmReachabilityState.Probing ?: return@synchronized effects
            if (current.probeId != probeId || !current.httpSentConfirmed) return@synchronized effects

            val sameNetwork =
                currentNetworkKey == current.networkKey &&
                    (eligibility.networkKey == null || eligibility.networkKey == current.networkKey)
            if (
                !eligibility.isForeground ||
                !eligibility.signedIn ||
                eligibility.fcmUnavailable ||
                !eligibility.raftApiHealthy ||
                !sameNetwork
            ) {
                // Raft API did not remain healthy on the same network in foreground -> inconclusive, NOT a miss.
                state = previousStateBeforeProbe
                return@synchronized effects
            }

            consecutiveMisses += 1
            if (consecutiveMisses >= missesToMarkUnreachable) {
                state = FcmReachabilityState.Unreachable(confirmedAtMillis = now)
                previousStateBeforeProbe = state
                nextRetryAllowedAtMillis = null
                effects += FcmProbeEffect.CancelRetryProbe
                effects += FcmProbeEffect.CancelDwellExpiry
                if (!autoFallbackActive) {
                    autoFallbackActive = true
                    autoFallbackActivatedAtMillis = now
                    effects += FcmProbeEffect.AutoFallbackChanged(active = true, reason = "fcm_probe_unreachable")
                }
            } else {
                // First miss: stay Unknown (do NOT mark Unreachable yet) and wait >= minRetryGapMillis (60 s).
                state = FcmReachabilityState.Unknown
                previousStateBeforeProbe = FcmReachabilityState.Unknown
                nextRetryAllowedAtMillis = now + minRetryGapMillis
                effects += FcmProbeEffect.ScheduleRetryProbe(delayMillis = minRetryGapMillis)
            }
            effects
        }

    /**
     * Silent FCM probe received (`data["type"] == "probe"`). Matches both in-flight probes (even
     * before the HTTP response returns) and late-arriving probes (even after the 15 s timeout or
     * after transitioning to [FcmReachabilityState.Unreachable]).
     */
    fun onProbeReceived(probeId: String, featureEnabled: Boolean): List<FcmProbeEffect> =
        synchronized(lock) {
            val now = clockMillis()
            if (!featureEnabled || isServerUnsupportedLocked(now)) return@synchronized emptyList()
            val clean = probeId.trim()
            if (clean.isEmpty() || !knownProbeIds.containsKey(clean)) return@synchronized emptyList()
            knownProbeIds.remove(clean)
            val effects = mutableListOf<FcmProbeEffect>()
            markReachableLocked(now, reason = "fcm_probe_received", effects = effects)
            effects
        }

    /** Any real FCM delivery (alert, sync, retract) immediately proves FCM reachability. */
    fun onRealFcmDelivered(featureEnabled: Boolean): List<FcmProbeEffect> =
        synchronized(lock) {
            val now = clockMillis()
            if (!featureEnabled || isServerUnsupportedLocked(now)) return@synchronized emptyList()
            val effects = mutableListOf<FcmProbeEffect>()
            markReachableLocked(now, reason = "real_fcm_delivered", effects = effects)
            effects
        }

    /** Called when the anti-flap dwell timer expires; turns auto-fallback off only if still Reachable. */
    fun onDwellTimerExpired(featureEnabled: Boolean): List<FcmProbeEffect> =
        synchronized(lock) {
            val now = clockMillis()
            if (!featureEnabled || isServerUnsupportedLocked(now)) return@synchronized emptyList()
            val effects = mutableListOf<FcmProbeEffect>()
            if (autoFallbackActive && state is FcmReachabilityState.Reachable) {
                maybeCompleteDwellLocked(now, reason = "fcm_dwell_elapsed", effects = effects)
            }
            effects
        }

    fun reset(reason: String = "reset"): List<FcmProbeEffect> =
        synchronized(lock) {
            val effects = mutableListOf<FcmProbeEffect>()
            resetLocked(reason = reason, clearServerUnsupported = true, effects = effects)
            effects
        }

    private fun markReachableLocked(now: Long, reason: String, effects: MutableList<FcmProbeEffect>) {
        if (state is FcmReachabilityState.Probing) {
            effects += FcmProbeEffect.CancelMissTimeout
        }
        effects += FcmProbeEffect.CancelRetryProbe
        state = FcmReachabilityState.Reachable(confirmedAtMillis = now)
        previousStateBeforeProbe = state
        consecutiveMisses = 0
        nextRetryAllowedAtMillis = null
        maybeCompleteDwellLocked(now, reason = reason, effects = effects)
    }

    private fun maybeCompleteDwellLocked(now: Long, reason: String, effects: MutableList<FcmProbeEffect>) {
        if (!autoFallbackActive) {
            effects += FcmProbeEffect.CancelDwellExpiry
            return
        }
        val activatedAt = autoFallbackActivatedAtMillis ?: 0L
        val elapsed = now - activatedAt
        if (elapsed >= minFallbackDwellMillis || elapsed < 0L) {
            autoFallbackActive = false
            autoFallbackActivatedAtMillis = null
            effects += FcmProbeEffect.CancelDwellExpiry
            effects += FcmProbeEffect.AutoFallbackChanged(active = false, reason = reason)
        } else {
            effects += FcmProbeEffect.ScheduleDwellExpiry(delayMillis = minFallbackDwellMillis - elapsed)
        }
    }

    private fun resetLocked(
        reason: String,
        clearServerUnsupported: Boolean,
        effects: MutableList<FcmProbeEffect>,
    ) {
        val hadActiveState =
            state != FcmReachabilityState.Unknown ||
                previousStateBeforeProbe != FcmReachabilityState.Unknown ||
                consecutiveMisses != 0 ||
                nextRetryAllowedAtMillis != null ||
                autoFallbackActive ||
                knownProbeIds.isNotEmpty()
        if (hadActiveState) {
            effects += FcmProbeEffect.CancelMissTimeout
            effects += FcmProbeEffect.CancelRetryProbe
            effects += FcmProbeEffect.CancelDwellExpiry
        }
        state = FcmReachabilityState.Unknown
        previousStateBeforeProbe = FcmReachabilityState.Unknown
        consecutiveMisses = 0
        nextRetryAllowedAtMillis = null
        knownProbeIds.clear()
        if (clearServerUnsupported) {
            serverUnsupportedUntilMillis = null
            currentServerKey = null
            currentNetworkKey = null
        }
        if (autoFallbackActive) {
            autoFallbackActive = false
            autoFallbackActivatedAtMillis = null
            effects += FcmProbeEffect.AutoFallbackChanged(active = false, reason = reason)
        }
    }

    private fun isServerUnsupportedLocked(now: Long): Boolean {
        val until = serverUnsupportedUntilMillis ?: return false
        return now < until
    }

    private fun recordKnownProbeIdLocked(probeId: String, networkKey: String) {
        knownProbeIds.remove(probeId)
        knownProbeIds[probeId] = networkKey
        while (knownProbeIds.size > maxKnownProbeIds) {
            val oldest = knownProbeIds.keys.firstOrNull() ?: break
            knownProbeIds.remove(oldest)
        }
    }

    companion object {
        const val PROBE_TIMEOUT_MS: Long = 15_000L
        const val MIN_RETRY_GAP_MS: Long = 60_000L
        const val REACHABLE_TTL_MS: Long = 24L * 60L * 60L * 1000L
        const val MIN_FALLBACK_DWELL_MS: Long = 5L * 60L * 1000L
        const val UNSUPPORTED_SERVER_TTL_MS: Long = 24L * 60L * 60L * 1000L
        const val MISSES_TO_MARK_UNREACHABLE: Int = 2
        const val MAX_KNOWN_PROBE_IDS: Int = 32
    }
}
