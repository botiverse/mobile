/*
 * Task #116 — host capability interfaces for the shared composer behavior machine.
 * Implementations land with each consumer cut (step 2/3), platform by platform;
 * compile-only is never a PRODUCT gate (v1.3 §5).
 */
package build.raft.app.composer

import build.raft.app.data.PlatformFile

internal interface ComposerHostCapabilities {
    /** Typed pick. Partial results (206) must surface skippedCount, never be swallowed. */
    suspend fun pickFiles(request: ComposerPickRequest): ComposerPickOutcome

    /**
     * Trusted picker-boundary normalization for image-only picks (audit #10 / OHOS
     * opaque extension-less URIs): when the picker was explicitly image-only, derive a
     * canonical extension from the MIME type so the strict classifier accepts real
     * images. Mixed/video picks stay fail-closed — this is never called for them.
     */
    fun normalizePickedImage(file: PlatformFile): PlatformFile

    /**
     * All-or-nothing durable promotion into the app-owned namespace for [sessionId]:
     * bounded copy + actual stat + atomic rename; any failure cleans staging and
     * reports zero files. sizeBytes/mediaType in results come from the durable stat,
     * never from the picker-era metadata (task #114 blocker 3).
     */
    suspend fun promoteToDurable(
        files: List<PlatformFile>,
        sessionId: String,
    ): ComposerPromotionOutcome
}

internal sealed interface ComposerPromotionOutcome {
    data class Promoted(val refs: List<DurableEditRef>) : ComposerPromotionOutcome
    data class Failed(val reason: String) : ComposerPromotionOutcome
}

/**
 * Injected claim set for crash-consistent orphan sweep (v1.3 §3.2). The machine never
 * enumerates concrete draft stores; surfaces provide this capability. Until claims are
 * authoritative-ready the sweep is fail-closed (deletes nothing).
 */
internal interface ComposerDurableRefClaims {
    suspend fun isAuthoritativeReady(): Boolean
    suspend fun claimedDurableRefs(): Set<String>
}

/** Backing store for the persistent session tombstone (host/store concern). */
internal interface ComposerSessionAuthorityStore : ComposerSessionAuthority {
    /** GC tombstones only past the transport's provable replay horizon. */
    suspend fun gcTombstones(olderThanEpochMs: Long)
}
