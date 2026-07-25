package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * task #803 (P0, feedback #152): the picker row's click handler re-evaluated
 * `targets[index]` at CLICK time while the rendered identity was read at
 * RENDER time — a list refresh in between made the same index point at a
 * different row, so the user saw A and selected B. These teeth pin the whole
 * class shut: render-time identity capture, tap-time frozen receipt, and a
 * send path that consumes only the receipt.
 */
class ThreadForwardRowIdentityContractTest {
    private fun threadPageSource(): String {
        val candidates =
            listOf(
                File("src/commonMain/kotlin/build/raft/app/ThreadPage.kt"),
                File("compose/shared/src/commonMain/kotlin/build/raft/app/ThreadPage.kt")
            )
        return candidates.firstOrNull(File::isFile)?.readText()?.replace("\r\n", "\n")
            ?: error("Unable to locate ThreadPage.kt")
    }

    @Test
    fun pickerRowClickCapturesTheRenderTimeIdentityObject() {
        val source = threadPageSource()
        assertContains(source, "val rowTarget = targets[index]")
        assertContains(source, "onClick = { onSelectTarget(rowTarget) }")
        // The click-time index re-read must never come back.
        assertFalse(
            source.contains("onSelectTarget(targets[index])"),
            "row onClick must not re-resolve identity from the mutable list at click time"
        )
    }

    @Test
    fun tapMintsAFrozenReceiptAndSendConsumesOnlyTheReceipt() {
        val source = threadPageSource()
        assertContains(source, "forwardSelectedReceipt =")
        assertContains(source, "toThreadForwardDestinationReceipt(")
        assertContains(source, "receipt = selectedReceipt,")
        assertContains(source, "currentDirectoryGeneration = forwardDirectoryGeneration,")
        // LiBai blocker 1: the confirm callsite must gate against the LIVE
        // server authority, never the frozen picker-open copy.
        assertContains(source, "val liveServerId = forwardServerId")
        assertContains(source, "liveSourceServerId = liveServerId,")
        // The bare selected-target state variable is gone entirely.
        assertFalse(
            source.contains("forwardSelectedTarget"),
            "the mutable selected-target variable must not exist; only the frozen receipt"
        )
        // Success toast reads the SNAPSHOT title, not a re-resolved value.
        assertContains(source, "selectedReceipt.displayedTitle")
    }

    @Test
    fun everyDirectoryPublicationBumpsTheGeneration() {
        val source = threadPageSource()
        // Four publication branches (Content/Empty/Error/NoPermission) — each
        // must invalidate receipts minted against the previous list.
        assertEquals(
            4,
            Regex("forwardDirectoryGeneration \\+= 1").findAll(source).count(),
            "all four forwardTargets publication branches must bump the generation"
        )
    }
}
