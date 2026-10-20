package build.raft.app.data

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.TimeSource

/**
 * Task #150 (Hands 6604f4c4): while the API host cannot be reached at all (DNS cannot resolve
 * it, or no connection can be made), every page switch and resume still fanned out 20–30
 * requests, ~2400 failures in a few minutes on one device, each failing in milliseconds.
 *
 * After [failureThreshold] consecutive unreachable failures the gate opens: requests fail
 * immediately with the last failure (same code 0 and error text, so every repository keeps
 * classifying it exactly as before) instead of reaching the network. When the cooldown ends a
 * single probe request is let through; if it fails the cooldown doubles up to [maxCooldownMillis],
 * and any HTTP response at all (the host answered) closes the gate at once.
 *
 * Only failures with no HTTP status whose transport kind is DNS or CONNECT count: a timeout, a
 * TLS error or a cancelled request says nothing reliable about whether the host is reachable.
 */
internal class RaftHostReachabilityGate(
    private val nowMillis: () -> Long = monotonicMillis(),
    private val failureThreshold: Int = 3,
    private val baseCooldownMillis: Long = 1_000L,
    private val maxCooldownMillis: Long = 15_000L
) {
    sealed interface Admission {
        /** Go to the network. [probe] must be passed back to [record]. */
        data class Proceed(val probe: Boolean) : Admission

        /** Do not go to the network; answer with [response]. */
        data class Blocked(val response: HomeNetworkResponse) : Admission
    }

    private val lock = SynchronizedObject()
    private var consecutiveFailures = 0
    private var cooldownStep = 0
    private var openUntil: Long? = null
    private var probeInFlight = false
    private var lastFailure: HomeNetworkResponse? = null

    fun admit(): Admission =
        synchronized(lock) {
            val until = openUntil ?: return@synchronized Admission.Proceed(probe = false)
            val now = nowMillis()
            if (now < until || probeInFlight) {
                return@synchronized Admission.Blocked(blockedResponse(retryInMillis = (until - now).coerceAtLeast(0L)))
            }
            probeInFlight = true
            Admission.Proceed(probe = true)
        }

    /** Feeds the outcome of a request that [admit] let through. */
    fun record(response: HomeNetworkResponse, probe: Boolean) {
        synchronized(lock) {
            if (probe) probeInFlight = false
            when {
                response.code > 0 -> close()
                response.isHostUnreachableFailure() -> {
                    lastFailure = response
                    consecutiveFailures += 1
                    val now = nowMillis()
                    if (openUntil != null) {
                        // Only a failed probe extends an open gate; stragglers that left before it
                        // opened do not keep pushing the cooldown out.
                        if (probe) {
                            cooldownStep += 1
                            openUntil = now + cooldownMillis()
                        }
                    } else if (consecutiveFailures >= failureThreshold) {
                        cooldownStep = 0
                        openUntil = now + cooldownMillis()
                    }
                }
                // Timeout, TLS, cancellation: no evidence either way; a released probe lets the
                // next request probe again.
                else -> Unit
            }
        }
    }

    /** A request let through by [admit] ended without a response (e.g. it was cancelled). */
    fun abandon(probe: Boolean) {
        if (!probe) return
        synchronized(lock) { probeInFlight = false }
    }

    /** The platform reports the network is back: let requests through again immediately. */
    fun onNetworkAvailable() {
        synchronized(lock) { close() }
    }

    val isOpen: Boolean
        get() = synchronized(lock) { openUntil != null }

    private fun close() {
        consecutiveFailures = 0
        cooldownStep = 0
        openUntil = null
        probeInFlight = false
        lastFailure = null
    }

    private fun cooldownMillis(): Long {
        var cooldown = baseCooldownMillis
        repeat(cooldownStep) { cooldown = (cooldown * 2).coerceAtMost(maxCooldownMillis) }
        return cooldown.coerceAtMost(maxCooldownMillis)
    }

    private fun blockedResponse(retryInMillis: Long): HomeNetworkResponse {
        val last = lastFailure
        return HomeNetworkResponse(
            code = 0,
            bodyText = null,
            errorBodyText = last?.errorBodyText ?: HOST_UNREACHABLE_ERROR_BODY,
            transportDiagnostics =
                "engine=reachability_gate status=none kind=${last?.transportKind() ?: "DNS"} " +
                    "gate=open retryInMs=$retryInMillis"
        )
    }

    companion object {
        private const val HOST_UNREACHABLE_ERROR_BODY = "host_unreachable_backoff"

        private fun monotonicMillis(): () -> Long {
            val start = TimeSource.Monotonic.markNow()
            return { start.elapsedNow().inWholeMilliseconds }
        }
    }
}

/**
 * One [RaftHostReachabilityGate] per API base URL, shared by every network client that uses
 * the same executor, so an unreachable environment never blocks another and all clients see the
 * same state.
 */
internal class RaftHostReachabilityGates(
    private val gateFactory: () -> RaftHostReachabilityGate = { RaftHostReachabilityGate() }
) {
    private val lock = SynchronizedObject()
    private val gates = mutableMapOf<String, RaftHostReachabilityGate>()

    fun forBaseUrl(baseUrl: String): RaftHostReachabilityGate =
        synchronized(lock) { gates.getOrPut(baseUrl.trim().trimEnd('/')) { gateFactory() } }

    /** The platform says the network is back: stop short-circuiting requests at once. */
    fun onNetworkAvailable() {
        val all = synchronized(lock) { gates.values.toList() }
        all.forEach { it.onNetworkAvailable() }
    }
}

/** The transport `kind=` token of a failed request's diagnostics, if any. */
internal fun HomeNetworkResponse.transportKind(): String? =
    transportDiagnostics
        ?.let { HOST_TRANSPORT_KIND_REGEX.find(it)?.groupValues?.get(1) }

/** No HTTP status and the transport says the name did not resolve or no connection was made. */
internal fun HomeNetworkResponse.isHostUnreachableFailure(): Boolean =
    code == 0 && transportKind() in HOST_UNREACHABLE_KINDS

private val HOST_TRANSPORT_KIND_REGEX = Regex("""(?:^|\s)kind=([A-Z_]+)""")
private val HOST_UNREACHABLE_KINDS = setOf("DNS", "CONNECT")
