package build.raft.app.core.data.auth

import build.raft.app.data.StoredQaBootstrapMetadata
import build.raft.app.data.StoredQaBootstrapRuntimeEnvelope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QaCaptureBoundaryContractTest {
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
            deviceBootGeneration = "sha256:boot",
            state = "ready"
        )
    private val envelope =
        StoredQaBootstrapRuntimeEnvelope(
            envelopeDigest = "sha256:envelope",
            readyReceiptDigest = "sha256:receipt",
            pid = 42,
            processInstanceId = "process-1",
            processGeneration = "generation-1",
            deviceBootGeneration = "sha256:boot",
            deviceReadyElapsedRealtimeNanos = "100",
            routeReadyProofDigest = "sha256:route"
        )
    private val request =
        QaCaptureProofRequest(
            readyReceiptDigest = "sha256:receipt",
            runCaseId = "run-1",
            attempt = 1,
            captureWindowNonce = "nonce-1",
            scopeGeneration = "generation-1",
            producerCommandGeneration = "command-1",
            imageSha256 = "11".repeat(32)
        )

    @Test
    fun captureProofBindsFrozenScopeAndImageDigest() {
        assertTrue(qaCaptureRequestMatches(request, metadata, envelope, 42, "generation-1"))
        assertFalse(qaCaptureRequestMatches(request.copy(scopeGeneration = "generation-2"), metadata, envelope, 42, "generation-1"))
        assertFalse(qaCaptureRequestMatches(request.copy(imageSha256 = "bad"), metadata, envelope, 42, "generation-1"))
        assertFalse(qaCaptureRequestMatches(request.copy(runCaseId = "run-2"), metadata, envelope, 42, "generation-1"))
        assertFalse(qaCaptureRequestMatches(request.copy(attempt = 2), metadata, envelope, 42, "generation-1"))
        assertFalse(qaCaptureRequestMatches(request.copy(producerCommandGeneration = ""), metadata, envelope, 42, "generation-1"))
        assertFalse(qaCaptureRequestMatches(request, metadata, envelope, 43, "generation-1"))

        val recoveredEnvelope = envelope.copy(pid = 43, processGeneration = "generation-2", recoveryOf = envelope.envelopeDigest)
        assertFalse(qaCaptureRequestMatches(request, metadata, recoveredEnvelope, 43, "generation-2"))
    }
}
