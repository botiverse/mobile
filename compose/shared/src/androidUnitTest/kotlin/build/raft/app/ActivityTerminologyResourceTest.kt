package build.raft.app

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the zh-rCN Activity terminology to 「动态」 on the Android native widget catalog
 * (task #103; AngLee ruling #proj-i18n msg fce28aae, 2026-09-01).
 *
 * The native catalog (compose/androidApp values-zh-rCN) is a SEPARATE copy from the shared MR
 * catalog; its two home-screen widget surfaces still read 活动 after the merged Activity batch
 * (caught by the #267 review census). Ruled: widget name → Raft 动态, unread-Activity counter
 * → 未读动态, live agent activity → 实时 Agent 动态 (the last follows the
 * member_state_activity_start_agent → 动态日志 precedent: agent behavior activity, not the feed).
 */
class ActivityTerminologyResourceTest {

    // Native widget keys pinned to their ruled exact zh values.
    private val nativeWidgetExpectations =
        mapOf(
            "slock_activity_widget_name" to "Raft 动态",
            "slock_activity_widget_description" to "未读动态和收藏计数",
            "slock_agent_status_widget_description" to "实时 Agent 动态"
        )

    @Test
    fun androidNativeWidgetKeysUseActivityFeedTerm() {
        val zh = readAndroidNativeStrings()
        nativeWidgetExpectations.forEach { (key, expected) ->
            assertEquals(expected, zh[key], "androidApp zh-rCN $key must be unified to 动态")
        }
    }

    @Test
    fun androidNativeCatalogHasNoLegacyActivityTermResidue() {
        val zh = readAndroidNativeStrings()
        val residue = zh.filterValues { it.contains("活动") }
        assertTrue(
            residue.isEmpty(),
            "androidApp zh-rCN must not contain 「活动」; found: ${residue.keys}"
        )
    }

    @Test
    fun sharedAuditTrailKeyKeepsActivityTerm() {
        // AngLee ruling (Activity batch): the chat audit-trail hint is a descriptive
        // activity/audit record, NOT the Activity feed — it deliberately keeps 活动.
        val zh = readSharedZhStrings()
        assertEquals(
            "此处仅作为活动/审计记录展示，这些条目不会直接打开对话。",
            zh["member_hint_chat_audit_only"],
            "shared zh-rCN member_hint_chat_audit_only must keep 活动 (audit-trail semantics)"
        )
    }

    private fun readSharedZhStrings(): Map<String, String> {
        val workingDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val candidates =
            listOf(
                workingDirectory.resolve("src/commonMain/resources/MR/zh-rCN/strings.xml"),
                workingDirectory.resolve("shared/src/commonMain/resources/MR/zh-rCN/strings.xml"),
                workingDirectory.resolve("compose/shared/src/commonMain/resources/MR/zh-rCN/strings.xml")
            )
        val path =
            candidates.firstOrNull(Files::isRegularFile)
                ?: error("Unable to locate shared MR zh-rCN strings.xml from $workingDirectory")
        return STRING_ENTRY.findAll(String(Files.readAllBytes(path), Charsets.UTF_8))
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun readAndroidNativeStrings(): Map<String, String> {
        val workingDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val candidates =
            listOf(
                workingDirectory.resolve("../androidApp/src/main/res/values-zh-rCN/strings.xml"),
                workingDirectory.resolve("androidApp/src/main/res/values-zh-rCN/strings.xml"),
                workingDirectory.resolve("compose/androidApp/src/main/res/values-zh-rCN/strings.xml")
            )
        val path =
            candidates.firstOrNull(Files::isRegularFile)
                ?: error("Unable to locate androidApp values-zh-rCN/strings.xml from $workingDirectory")
        return STRING_ENTRY.findAll(String(Files.readAllBytes(path), Charsets.UTF_8))
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private companion object {
        val STRING_ENTRY = Regex("<string\\s+name=\"([^\"]+)\">([^<]*)</string>")
    }
}
