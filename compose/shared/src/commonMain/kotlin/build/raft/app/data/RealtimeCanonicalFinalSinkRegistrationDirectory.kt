package build.raft.app.data

import build.raft.app.model.RealtimeEvent
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

internal sealed interface RealtimeCanonicalFinalSinkSurfaceKey {
    data object AppShellDomain : RealtimeCanonicalFinalSinkSurfaceKey
    data object AppShellHomeUnread : RealtimeCanonicalFinalSinkSurfaceKey
    data class ThreadCore(val routeKey: String) : RealtimeCanonicalFinalSinkSurfaceKey {
        init {
            require(routeKey.isNotBlank()) { "canonical final sink ThreadCore route must not be blank" }
        }
    }

    data class ThreadFeature(val routeKey: String) : RealtimeCanonicalFinalSinkSurfaceKey {
        init {
            require(routeKey.isNotBlank()) { "canonical final sink ThreadFeature route must not be blank" }
        }
    }
}

internal data class RealtimeCanonicalFinalSinkRegistrationSlot(
    val scopeLeaseId: String,
    val serverId: String,
    val surfaceKey: RealtimeCanonicalFinalSinkSurfaceKey,
) {
    init {
        require(scopeLeaseId.isNotBlank()) { "canonical final sink scope lease must not be blank" }
        require(serverId.isNotBlank()) { "canonical final sink server must not be blank" }
    }
}

internal data class RealtimeCanonicalFinalSinkRegistrationHandle(
    val slot: RealtimeCanonicalFinalSinkRegistrationSlot,
    val registrationId: String,
) {
    init {
        require(registrationId.isNotBlank()) { "canonical final sink registration id must not be blank" }
    }
}

internal fun interface RealtimeCanonicalFinalSink {
    suspend fun deliver(event: RealtimeEvent)
}

internal data class RealtimeCanonicalFinalSinkCapturedRegistration(
    val handle: RealtimeCanonicalFinalSinkRegistrationHandle,
    val sink: RealtimeCanonicalFinalSink,
)

/**
 * Deterministic final delivery order. Selected-server canonical frames keep the existing Thread-before-AppShell
 * composition; background-unread captures only [RealtimeCanonicalFinalSinkSurfaceKey.AppShellHomeUnread].
 */
internal val RealtimeCanonicalFinalSinkSurfaceKey.deliveryRank: Int
    get() =
        when (this) {
            is RealtimeCanonicalFinalSinkSurfaceKey.ThreadCore -> 0
            is RealtimeCanonicalFinalSinkSurfaceKey.ThreadFeature -> 1
            RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain -> 2
            RealtimeCanonicalFinalSinkSurfaceKey.AppShellHomeUnread -> 3
        }

internal val RealtimeCanonicalFinalSinkSurfaceKey.stableRouteKey: String
    get() =
        when (this) {
            is RealtimeCanonicalFinalSinkSurfaceKey.ThreadCore -> routeKey
            is RealtimeCanonicalFinalSinkSurfaceKey.ThreadFeature -> routeKey
            RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain,
            RealtimeCanonicalFinalSinkSurfaceKey.AppShellHomeUnread -> ""
        }

internal val RealtimeCanonicalFinalSinkSurfaceComparator: Comparator<RealtimeCanonicalFinalSinkSurfaceKey> =
    Comparator { left, right ->
        val rank = left.deliveryRank.compareTo(right.deliveryRank)
        if (rank != 0) rank else left.stableRouteKey.compareTo(right.stableRouteKey)
    }

/**
 * Lightweight provider-owned registration state. It has no coroutine scope, worker, queue, authority map, or
 * durable cursor. Registration can therefore follow real surface lifecycles without constructing the lazy T4
 * router/sidecar while rollout remains inactive.
 */
internal class RealtimeCanonicalFinalSinkRegistrationDirectory(
    private val registrationIdFactory: (() -> String)? = null,
) {
    private data class Registration(
        val handle: RealtimeCanonicalFinalSinkRegistrationHandle,
        val sink: RealtimeCanonicalFinalSink,
    )

    private val lock = SynchronizedObject()
    private val registrationsBySlot = mutableMapOf<RealtimeCanonicalFinalSinkRegistrationSlot, Registration>()
    private var nextDefaultRegistrationId = 0L

    fun register(
        slot: RealtimeCanonicalFinalSinkRegistrationSlot,
        sink: RealtimeCanonicalFinalSink,
    ): RealtimeCanonicalFinalSinkRegistrationHandle =
        synchronized(lock) {
            val registrationId = registrationIdFactory?.invoke() ?: "registration-${++nextDefaultRegistrationId}"
            val handle = RealtimeCanonicalFinalSinkRegistrationHandle(slot = slot, registrationId = registrationId)
            registrationsBySlot[slot] = Registration(handle = handle, sink = sink)
            handle
        }

    fun unregister(handle: RealtimeCanonicalFinalSinkRegistrationHandle): Boolean =
        synchronized(lock) {
            val current = registrationsBySlot[handle.slot] ?: return@synchronized false
            if (current.handle != handle) return@synchronized false
            registrationsBySlot.remove(handle.slot)
            true
        }

    fun capture(
        scopeLeaseId: String,
        serverId: String,
        allowedSurfaceKeys: Set<RealtimeCanonicalFinalSinkSurfaceKey>? = null,
    ): List<RealtimeCanonicalFinalSinkCapturedRegistration> =
        synchronized(lock) {
            registrationsBySlot.values
                .asSequence()
                .filter { registration ->
                    registration.handle.slot.scopeLeaseId == scopeLeaseId &&
                        registration.handle.slot.serverId == serverId &&
                        (allowedSurfaceKeys == null || registration.handle.slot.surfaceKey in allowedSurfaceKeys)
                }
                .sortedWith { left, right ->
                    RealtimeCanonicalFinalSinkSurfaceComparator.compare(
                        left.handle.slot.surfaceKey,
                        right.handle.slot.surfaceKey,
                    )
                }
                .map { registration ->
                    RealtimeCanonicalFinalSinkCapturedRegistration(
                        handle = registration.handle,
                        sink = registration.sink,
                    )
                }
                .toList()
        }

    /** Atomic exact-handle claim. The returned callbacks are immutable enqueue-time captures. */
    fun claimCurrent(
        captured: List<RealtimeCanonicalFinalSinkCapturedRegistration>,
    ): List<RealtimeCanonicalFinalSinkCapturedRegistration> =
        synchronized(lock) {
            captured.filter { registration ->
                registrationsBySlot[registration.handle.slot]?.handle == registration.handle
            }
        }

    fun registrationCount(): Int = synchronized(lock) { registrationsBySlot.size }
}
