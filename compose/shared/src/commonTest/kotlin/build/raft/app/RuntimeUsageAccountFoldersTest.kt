package build.raft.app

import build.raft.app.data.RuntimeAccountUsageAccount
import build.raft.app.data.RuntimeAccountUsageProvider
import build.raft.app.data.RuntimeAccountUsageRead
import build.raft.app.data.RuntimeAccountUsageSnapshot
import build.raft.app.data.defaultRuntimeUsageAccount
import build.raft.app.data.runtimeUsageAccountsForAgent
import build.raft.app.data.summarizeRuntimeQuota
import build.raft.app.data.toRuntimeAccountUsageRead
import build.raft.app.thread.key
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * slock #15348: an agent on its own account folder (CODEX_HOME / CLAUDE_CONFIG_DIR) gets its own
 * account in the Computer's usage snapshot, listed with `agentIds`. Computer-level readings use
 * the default account (no `agentIds`); an older Computer (no account lists agents) reads as before.
 */
class RuntimeUsageAccountFoldersTest {
    private fun accountJson(key: String, plan: String, label: String, ratio: Double, agentIds: String? = null): String {
        val ids = agentIds?.let { ""","agentIds":$it""" }.orEmpty()
        return """{"accountKey":"${key.repeat(64)}","planLabel":"$plan","health":"ok"$ids,"windows":[{"id":"w_$key","label":"$label","status":"ok","usedRatio":$ratio}]}"""
    }

    private fun snapshot(provider: String, vararg accounts: String): RuntimeAccountUsageSnapshot {
        val body =
            """{"state":"fresh","snapshot":{"protocolVersion":2,"provider":"$provider","collectedAt":"2026-11-18T10:00:00.000Z","accounts":[${accounts.joinToString(",")}]}}"""
        return assertIs<RuntimeAccountUsageRead.Available>(Json.parseToJsonElement(body).toRuntimeAccountUsageRead()).snapshot
    }

    @Test
    fun agentIdsParseAndMarkAnAgentsFolderAccount() {
        val s = snapshot("codex", accountJson("b", "Plan b", "Window b", 0.5, """["agent-own"]"""), accountJson("a", "Plan a", "Window a", 0.1))
        assertEquals(listOf(listOf("agent-own"), null), s.accounts.map(RuntimeAccountUsageAccount::agentIds))
        // The default account wherever it sits.
        assertEquals("Plan a", defaultRuntimeUsageAccount(s)?.planLabel)
        // The Computer's plan line reads the default account.
        assertEquals("Plan a", summarizeRuntimeQuota(RuntimeAccountUsageProvider.Codex, RuntimeAccountUsageRead.Available(stale = false, snapshot = s))?.planLabel)
    }

    @Test
    fun anAgentReadsItsOwnFolderElseTheDefaultAccountAndAnOlderComputerEveryAccount() {
        val folders = snapshot("codex", accountJson("a", "Plan a", "Window a", 0.1), accountJson("b", "Plan b", "Window b", 0.5, """["agent-own"]"""))
        assertEquals(listOf("Plan b"), runtimeUsageAccountsForAgent(folders, "agent-own").map { it.planLabel })
        assertEquals(listOf("Plan a"), runtimeUsageAccountsForAgent(folders, "agent-shared").map { it.planLabel })
        val older = snapshot("codex", accountJson("a", "Plan a", "Window a", 0.1), accountJson("b", "Plan b", "Window b", 0.5))
        assertEquals(listOf("Plan a", "Plan b"), runtimeUsageAccountsForAgent(older, "agent-own").map { it.planLabel })
    }
}
