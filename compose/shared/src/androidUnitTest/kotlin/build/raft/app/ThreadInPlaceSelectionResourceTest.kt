package build.raft.app

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

/** The in-place selection menu keys must exist in every shipped locale (no silent English fallback). */
class ThreadInPlaceSelectionResourceTest {
    private val keys =
        listOf(
            "thread_text_selection_copy_selection",
            "thread_text_selection_select_all",
        )

    @Test
    fun selectionMenuKeysExistInBaseChineseAndJapanese() {
        val catalogs = listOf("base", "zh-rCN", "ja").associateWith(::readStrings)
        keys.forEach { key ->
            catalogs.forEach { (locale, catalog) ->
                assertTrue(!catalog[key].isNullOrBlank(), "$locale strings.xml missing $key")
            }
        }
    }

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
    }
}
