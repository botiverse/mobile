package build.raft.app

import build.raft.app.data.raftBulkReasoningEffortOptions
import build.raft.app.data.raftBulkReconcileReasoningEffort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The Computer page's bulk Change model for Antigravity, as web `BulkChangeModelDialog` and shared
 * `runtimeChoiceModel` / `runtimeOffersUndeclaredReasoningEfforts` (botiverse/slock #14619, read at
 * 9d710a10a): "AGY configured default" first and preselected; an effort only for a model whose
 * levels the Computer lists.
 */
class RaftComputerBulkModelAntigravityTest {
    private fun option(id: String, efforts: List<String>? = null, default: String? = null) =
        RaftBulkModelOption(id, id, efforts, default)

    @Test
    fun theRuntimesOwnChoiceLeadsAndIsPreselected() {
        // The Computer's agy listing (OAR listModels), which may carry `default` itself or not.
        val listed = listOf(option("gemini-4-pro", listOf("low", "high"), "high"), option("default"), option("claude-sonnet-5"))
        val catalog = raftBulkModelCatalog("antigravity", listed, defaultModel = "gemini-4-pro")
        assertEquals(listOf("default", "gemini-4-pro", "claude-sonnet-5"), catalog.models.map { it.id })
        assertEquals("AGY configured default", catalog.models.first().label)
        assertEquals("default", catalog.defaultModel)

        val withoutIt = raftBulkModelCatalog("antigravity", listOf(option("gemini-4-pro")), defaultModel = null)
        assertEquals(listOf("default", "gemini-4-pro"), withoutIt.models.map { it.id })

        // Other runtimes keep the Computer's list and default.
        val codex = raftBulkModelCatalog("codex", listOf(option("gpt-5.4"), option("gpt-5.3")), defaultModel = "gpt-5.3")
        assertEquals(listOf("gpt-5.4", "gpt-5.3"), codex.models.map { it.id })
        assertEquals("gpt-5.3", codex.defaultModel)
    }

    @Test
    fun antigravityOffersOnlyTheLevelsAModelLists() {
        assertEquals(listOf("low", "high"), raftBulkReasoningEffortOptions("antigravity", listOf("high", "low")))
        assertEquals(emptyList(), raftBulkReasoningEffortOptions("antigravity", null))
        // Other runtimes still offer the base set for a model that reports none.
        assertEquals(listOf("low", "medium", "high", "xhigh"), raftBulkReasoningEffortOptions("claude", null))

        assertEquals("high", raftBulkReconcileReasoningEffort("antigravity", listOf("low", "high"), "high", current = null))
        assertEquals("low", raftBulkReconcileReasoningEffort("antigravity", listOf("low", "high"), "high", current = "low"))
        // A model with no listed levels (the configured default): Default, whatever was picked before.
        assertNull(raftBulkReconcileReasoningEffort("antigravity", null, null, current = "high"))
        assertEquals("high", raftBulkReconcileReasoningEffort("claude", null, null, current = "high"))
    }
}
