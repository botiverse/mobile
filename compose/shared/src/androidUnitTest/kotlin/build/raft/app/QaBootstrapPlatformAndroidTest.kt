package build.raft.app

import build.raft.app.data.StoredQaBootstrapMetadata
import build.raft.app.data.StoredQaBootstrapReadyReceipt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class QaBootstrapPlatformAndroidTest {
    private val metadata =
        StoredQaBootstrapMetadata(
            ticketDigest = "sha256:ticket",
            runCaseId = "run-1",
            attempt = 1,
            ciJobRef = "https://github.com/botiverse/mobile/actions/runs/1",
            principalId = "user-1",
            serverId = "server-1",
            sessionGeneration = "session-1",
            targetRoute = "compose_slock_app_shell",
            applicationId = "build.raft.app.alpha",
            signingCertSha256 = "sha256:cert",
            deviceBootGeneration = "sha256:boot"
        )
    private val receipt =
        StoredQaBootstrapReadyReceipt(
            receiptId = "receipt-1",
            receiptDigest = "sha256:receipt",
            version = "qa-bootstrap-ready/v1",
            readyAtServer = "2026-07-21T15:00:00.000Z"
        )

    @Test
    fun freshRuntimeEnvelopeHasNoRecoveryAncestor() {
        val fresh = resolveQaBootstrapRuntimeEnvelope(metadata, receipt, 10, "process-a", "generation-a", "100", null)

        assertNull(fresh.recoveryOf)
    }

    @Test
    fun sameProcessReusesFrozenRuntimeEnvelope() {
        val fresh = resolveQaBootstrapRuntimeEnvelope(metadata, receipt, 10, "process-a", "generation-a", "100", null)

        val reused = resolveQaBootstrapRuntimeEnvelope(metadata, receipt, 10, "process-a", "generation-a", "200", fresh)

        assertSame(fresh, reused)
    }

    @Test
    fun newPidRecoversFromPreviousRuntimeEnvelopeDigest() {
        val previous = resolveQaBootstrapRuntimeEnvelope(metadata, receipt, 10, "process-a", "generation-a", "100", null)

        val recovered = resolveQaBootstrapRuntimeEnvelope(metadata, receipt, 11, "process-b", "generation-b", "200", previous)

        assertEquals(previous.envelopeDigest, recovered.recoveryOf)
        assertEquals(11, recovered.pid)
        assertEquals("generation-b", recovered.processGeneration)
    }
}
