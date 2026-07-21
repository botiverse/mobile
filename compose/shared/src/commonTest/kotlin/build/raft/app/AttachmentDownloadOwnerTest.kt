package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AttachmentDownloadOwnerTest {
    @Test
    fun deliberateStringHashCollisionHasDistinctOwnerTokensAndDownloadIds() {
        val ownerA = "Aa|server"
        val ownerB = "BB|server"

        assertEquals(ownerA.hashCode(), ownerB.hashCode())
        assertNotEquals(attachmentDownloadOwnerToken(ownerA), attachmentDownloadOwnerToken(ownerB))
        assertNotEquals(
            ownerScopedAttachmentDownloadId(ownerA, "attachment"),
            ownerScopedAttachmentDownloadId(ownerB, "attachment")
        )
    }

    @Test
    fun mountedOwnerSwitchRejectsOldProgressAndTerminalCommits() {
        var currentOwner: String? = "account-a|server"
        var currentGeneration = 1L
        var progressCommits = 0
        var terminalCommits = 0
        val expectedOwner = currentOwner!!
        val expectedGeneration = currentGeneration

        fun commitProgress() {
            if (
                attachmentOperationCommitAllowed(
                    expectedOwner,
                    expectedGeneration,
                    currentOwner,
                    currentGeneration
                )
            ) {
                progressCommits += 1
            }
        }

        fun commitTerminal() {
            if (
                attachmentOperationCommitAllowed(
                    expectedOwner,
                    expectedGeneration,
                    currentOwner,
                    currentGeneration
                )
            ) {
                terminalCommits += 1
            }
        }

        commitProgress()
        assertEquals(1, progressCommits)
        currentOwner = "account-b|server"
        currentGeneration += 1L
        commitProgress()
        commitTerminal()

        assertEquals(1, progressCommits)
        assertEquals(0, terminalCommits)
        assertFalse(
            attachmentOperationCommitAllowed(
                expectedOwner,
                expectedGeneration,
                currentOwner,
                currentGeneration
            )
        )
        assertTrue(
            attachmentOperationCommitAllowed(
                currentOwner!!,
                currentGeneration,
                currentOwner,
                currentGeneration
            )
        )
    }
}
