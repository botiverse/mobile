package build.raft.app

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

class ErrorPresentationResourceParityTest {
    private val canonicalKeys =
        setOf(
            "error_session_expired",
            "error_server_unreachable",
            "error_request_timed_out",
            "error_network_connection_failed",
            "error_server_unavailable",
            "error_item_not_found",
            "error_permission_denied",
            "error_attachment_too_large",
            "error_too_many_attempts",
            "error_response_unreadable",
            "error_generic",
            "empty_no_channels",
            "empty_no_messages",
            "empty_no_tasks",
            "empty_no_members",
            "empty_no_inbox_items",
            "empty_no_settings_available",
            "empty_no_search_results"
        )

    @Test
    fun baseChineseAndJapaneseContainTheSameCanonicalErrorKeys() {
        val resourcesRoot = resolveResourcesRoot()
        val localeKeySets =
            listOf("base", "zh-rCN", "ja").associateWith { locale ->
                val xml = String(Files.readAllBytes(resourcesRoot.resolve(raftComposeValuesDir(locale)).resolve("strings.xml")), Charsets.UTF_8)
                val matches = STRING_NAME.findAll(xml).map { it.groupValues[1] }.toList()
                assertEquals(matches.size, matches.toSet().size, "$locale strings.xml contains duplicate keys")
                matches.filterTo(mutableSetOf()) { it in canonicalKeys }
            }

        localeKeySets.forEach { (locale, keys) ->
            assertEquals(canonicalKeys, keys, "$locale must contain every canonical error/empty key")
        }
        assertEquals(1, localeKeySets.values.toSet().size, "base/zh-rCN/ja canonical key sets must stay in parity")
    }

    private fun resolveResourcesRoot(): Path {
        val workingDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val candidates =
            listOf(
                workingDirectory.resolve("src/commonMain/composeResources"),
                workingDirectory.resolve("shared/src/commonMain/composeResources"),
                workingDirectory.resolve("compose/shared/src/commonMain/composeResources")
            )
        return candidates.firstOrNull(Files::isDirectory)
            ?: error("Unable to locate shared composeResources from $workingDirectory")
    }

    private companion object {
        val STRING_NAME = Regex("<string\\s+name=\"([^\"]+)\"")
    }
}
