package build.raft.app

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the zh-rCN voice unification 您 -> 你 (task #103 closing batch, #proj-i18n:30e4c713).
 *
 * @AngLee ruling: all 14 formerly-formal 您 keys unify to the informal 你 to match web's
 * consistent informal voice (web has zero 您); voice is a unified product attribute, and the
 * legal/agreement copy also uses the informal structure. base (English) and ja are untouched.
 */
class VoiceUnificationResourceTest {

    // The keys whose zh copy formerly used the formal 您, now unified to 你 (four left with the legacy login pages).
    private val voiceUnifiedKeys =
        listOf(
            "auth_error_restore_session",
            "auth_error_session_refresh_choose_server",
            "auth_error_session_changed_retry_create_server",
            "auth_server_title_first",
            "auth_server_loading",
            "auth_server_create_hint",
            "auth_server_empty_hint",
            "auth_error_session_refresh",
            "auth_title_verify_email",
            "settings_translation_mode_detail"
        )

    @Test
    fun zhCatalogHasNoFormalYouResidue() {
        val zh = readStrings("zh-rCN")
        assertEquals(
            emptyList(),
            formalYouResidueKeys(zh),
            "zh-rCN must contain no formal 「您」; voice is unified to 你"
        )
    }

    @Test
    fun voiceUnifiedKeysDropFormalYouAndExistInBothLocales() {
        val base = readStrings("base")
        val zh = readStrings("zh-rCN")
        assertEquals(10, voiceUnifiedKeys.size, "voice-unified key set must stay at 10")
        voiceUnifiedKeys.forEach { key ->
            assertTrue(key in base, "base missing voice key $key")
            assertTrue(key in zh, "zh-rCN missing voice key $key")
            assertTrue(!zh.getValue(key).contains("您"), "zh-rCN $key must not contain 您, got: ${zh.getValue(key)}")
        }
    }

    @Test
    fun representativeVoiceKeysUseInformalYou() {
        val zh = readStrings("zh-rCN")
        assertEquals("验证你的邮箱", zh.getValue("auth_title_verify_email"))
        assertEquals("创建你的第一个服务器", zh.getValue("auth_server_title_first"))
        assertTrue(zh.getValue("auth_server_empty_hint").startsWith("你还没有服务器"))
    }

    @Test
    fun formalYouResidueDetectsMutation() {
        val zh = readStrings("zh-rCN")

        // Restored state is clean.
        assertEquals(emptyList(), formalYouResidueKeys(zh))

        // Mutation: reintroduce a formal 您 into one unified value -> must be flagged.
        val victim = "auth_title_verify_email"
        val mutated = zh.toMutableMap().also { it[victim] = "验证您的邮箱" }
        assertEquals(
            listOf(victim),
            formalYouResidueKeys(mutated),
            "reintroducing 您 into a zh value must be flagged"
        )
    }

    private fun formalYouResidueKeys(zh: Map<String, String>): List<String> =
        zh.filterValues { it.contains("您") }.keys.toList().sorted()

    private fun readStrings(locale: String): Map<String, String> =
        STRING_ENTRY.findAll(
            String(Files.readAllBytes(resourcesRoot().resolve(locale).resolve("strings.xml")), Charsets.UTF_8)
        ).associate { it.groupValues[1] to it.groupValues[2] }

    private fun resourcesRoot(): Path {
        val workingDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val candidates =
            listOf(
                workingDirectory.resolve("src/commonMain/resources/MR"),
                workingDirectory.resolve("shared/src/commonMain/resources/MR"),
                workingDirectory.resolve("compose/shared/src/commonMain/resources/MR")
            )
        return candidates.firstOrNull(Files::isDirectory)
            ?: error("Unable to locate shared MR resources from $workingDirectory")
    }

    private companion object {
        val STRING_ENTRY = Regex("<string\\s+name=\"([^\"]+)\">([^<]*)</string>")
    }
}
