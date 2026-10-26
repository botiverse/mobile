package build.raft.app

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchHomeResourceTest {
    private val keys =
        listOf(
            "search_error_sort_recent",
            "search_error_too_broad",
            "search_error_too_broad_detail",
            "search_home_history",
            "search_home_frequent",
            "search_home_edit",
            "search_home_done",
            "search_home_clear",
            "search_home_remove_history",
            "search_home_frequent_empty",
        )

    @Test
    fun searchHomeKeysExistAndKeepPlaceholderParityAcrossRequiredLocales() {
        val catalogs = listOf("base", "zh-rCN", "ja").associateWith(::readStrings)
        keys.forEach { key ->
            catalogs.forEach { (locale, catalog) ->
                assertTrue(key in catalog, "$locale strings.xml missing $key")
            }
            val expectedTokens = placeholderTokens(catalogs.getValue("base").getValue(key))
            listOf("zh-rCN", "ja").forEach { locale ->
                assertEquals(
                    expectedTokens,
                    placeholderTokens(catalogs.getValue(locale).getValue(key)),
                    "$key placeholder tokens must match base in $locale",
                )
            }
        }
        assertEquals(listOf("%1\$s"), placeholderTokens(catalogs.getValue("base").getValue("search_home_remove_history")))
    }

    @Test
    fun placeholderParityToothRejectsAQueryLabelWithoutItsArgument() {
        val base = readStrings("base")
        assertEquals(
            listOf("%1\$s"),
            placeholderTokens(base.getValue("search_home_remove_history")),
        )
        assertEquals(
            emptyList(),
            placeholderTokens(base.getValue("search_home_remove_history").replace("%1\$s", "query")),
        )
    }

    private fun placeholderTokens(value: String): List<String> =
        PLACEHOLDER.findAll(value).map { it.value }.toList()

    private fun readStrings(locale: String): Map<String, String> =
        STRING_ENTRY.findAll(
            String(Files.readAllBytes(resourcesRoot().resolve(raftComposeValuesDir(locale)).resolve("strings.xml")), Charsets.UTF_8)
        ).associate { it.groupValues[1] to it.groupValues[2] }

    private fun resourcesRoot(): Path {
        val workingDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val candidates =
            listOf(
                workingDirectory.resolve("src/commonMain/composeResources"),
                workingDirectory.resolve("shared/src/commonMain/composeResources"),
                workingDirectory.resolve("compose/shared/src/commonMain/composeResources"),
            )
        return candidates.firstOrNull(Files::isDirectory)
            ?: error("Unable to locate shared composeResources from $workingDirectory")
    }

    private companion object {
        val STRING_ENTRY = Regex("<string\\s+name=\"([^\"]+)\">([^<]*)</string>")
        val PLACEHOLDER = Regex("%\\d+\\$[a-zA-Z]")
    }
}
