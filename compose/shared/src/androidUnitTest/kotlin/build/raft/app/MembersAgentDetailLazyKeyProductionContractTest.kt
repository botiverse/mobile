package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The crash fixed by task #273 is caused at the production lazy-list boundary,
 * so keep the wiring (not only the key helper) under a source contract.
 */
class MembersAgentDetailLazyKeyProductionContractTest {
    @Test
    fun everyDetailLazySurfaceHasAnExplicitIdentity() {
        val source = repositoryFile(
            "compose/shared/src/commonMain/kotlin/build/raft/app/MembersPage.kt"
        ).readText()
        val detailStart = source.indexOf("private fun MembersDetailContent(")
        val profileStart = source.indexOf("private fun MembersAgentProfileTab(", detailStart)
        assertTrue(detailStart >= 0, "MembersDetailContent production function missing")
        assertTrue(profileStart > detailStart, "MembersDetailContent boundary missing")
        val detailSource = source.substring(detailStart, profileStart)

        assertTrue(
            detailSource.contains("item(key = \"member-detail-\${member.id.value}-top-spacer\")"),
            "top spacer must be keyed"
        )
        assertTrue(
            detailSource.contains("item(key = \"member-detail-\${member.id.value}-state-notice\")"),
            "state notice must be keyed"
        )
        assertTrue(
            detailSource.contains("item(key = membersAgentDetailBodyItemKey(member.id.value, selectedTab))"),
            "agent tab body must be keyed"
        )
        assertTrue(
            detailSource.contains("item(key = \"member-detail-\${member.id.value}-human-content\")"),
            "human body must be keyed"
        )
        assertTrue(
            detailSource.contains("item(key = \"member-detail-\${member.id.value}-bottom-spacer\")"),
            "bottom spacer must be keyed"
        )

        val itemCalls = Regex("\\bitem\\s*[({]").findAll(detailSource).count()
        val keyedCalls = Regex("\\bitem\\(key\\s*=").findAll(detailSource).count()
        assertEquals(
            itemCalls,
            keyedCalls,
            "every lazy item in MembersDetailContent must carry an explicit key"
        )
    }

    private fun repositoryFile(relativePath: String): File {
        val start = File(System.getProperty("user.dir") ?: ".")
        return generateSequence(start) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull(File::isFile)
            ?: error("Unable to resolve $relativePath from ${start.absolutePath}")
    }
}
