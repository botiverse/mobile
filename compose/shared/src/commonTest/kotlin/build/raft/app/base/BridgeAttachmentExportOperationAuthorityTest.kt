package build.raft.app.base

import build.raft.app.AttachmentOwnerLease
import build.raft.app.AttachmentPrincipalKey
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BridgeAttachmentExportOperationAuthorityTest {
    @Test
    fun sameOwnerAndDownloadCanNeverReuseNativeOperationAuthority() {
        val owner =
            AttachmentOwnerLease(
                shellInstanceId = "shell-a",
                scopeId = "scope-a",
                authEpoch = 4L,
                principalKey = AttachmentPrincipalKey("a".repeat(64)),
                generation = 7L
            )

        val first = nextBridgeAttachmentExportOperationId(owner)
        val successor = nextBridgeAttachmentExportOperationId(owner)

        assertNotEquals(first, successor)
        assertTrue(first.startsWith("${owner.principalKey.value}:${owner.scopeId}:${owner.generation}:"))
        assertTrue(successor.startsWith("${owner.principalKey.value}:${owner.scopeId}:${owner.generation}:"))
    }
}
