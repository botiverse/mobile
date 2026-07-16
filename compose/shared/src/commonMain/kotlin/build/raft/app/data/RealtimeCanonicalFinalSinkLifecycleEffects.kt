package build.raft.app.data

/**
 * Production lifecycle binder shared by the future Settings and Thread callsites. Stage B2 exposes the
 * binder only through a configured provider; no production page calls it yet, so runtime registration stays
 * strictly zero while tests can prove exact replace/dispose semantics without copying directory behavior.
 */
internal class RealtimeCanonicalFinalSinkLifecycleEffects(
    private val provider: RealtimeAuthorityProvider,
) {
    fun mount(
        scopeLeaseId: String,
        serverId: String,
        surfaceKey: RealtimeCanonicalFinalSinkSurfaceKey,
        sink: RealtimeCanonicalFinalSink,
    ): RealtimeCanonicalFinalSinkRegistrationHandle? =
        provider.registerCanonicalFinalSink(
            RealtimeCanonicalFinalSinkRegistrationSlot(
                scopeLeaseId = scopeLeaseId,
                serverId = serverId,
                surfaceKey = surfaceKey,
            ),
            sink,
        )

    fun dispose(handle: RealtimeCanonicalFinalSinkRegistrationHandle?): Boolean =
        handle != null && provider.unregisterCanonicalFinalSink(handle)
}
