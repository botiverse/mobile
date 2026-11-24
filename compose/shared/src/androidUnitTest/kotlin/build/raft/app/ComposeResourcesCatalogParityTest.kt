package build.raft.app

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The string catalogs. All eight stay in lockstep: values (en), values-ja, values-zh-rCN, values-zh-rTW,
 * values-ko, values-es, values-pt-rBR and values-ru have the same keys, so a new string is added to every
 * catalog in the same PR (a key a catalog lacks would show the English, RaftNewLocaleCatalogTest).
 * [PARTIAL_DIRS] is for a catalog that is allowed to lag behind; none is now.
 * None may hold a key English lacks. In every catalog a key has the same format placeholders as in
 * English. Compose resources only substitutes positional `%N$s` / `%N$d` placeholders and does not
 * decode Android-only escapes (`\'`, `\"`, `\@`, `\?`), so neither may appear.
 */
class ComposeResourcesCatalogParityTest {
    private val catalogs: Map<String, Map<String, String>> =
        (COMPLETE_DIRS + PARTIAL_DIRS).associateWith { dir -> parse(resourcesRoot().resolve(dir).resolve("strings.xml")) }

    @Test
    fun everyCatalogHasTheSameKeys() {
        val base = catalogs.getValue("values").keys
        assertTrue(base.isNotEmpty(), "values/strings.xml has no strings")
        catalogs.forEach { (dir, strings) ->
            if (dir in COMPLETE_DIRS) assertEquals(emptySet(), base - strings.keys, "keys missing from $dir")
            assertEquals(emptySet(), strings.keys - base, "keys only in $dir")
        }
    }

    @Test
    fun everyKeyHasTheSameFormatPlaceholdersInEveryCatalog() {
        val mismatches =
            catalogs.getValue("values").flatMap { (key, baseText) ->
                val expected = placeholders(baseText)
                catalogs.filterKeys { it != "values" }.mapNotNull { (dir, strings) ->
                    val text = strings[key] ?: return@mapNotNull null
                    val actual = placeholders(text)
                    if (actual == expected) null else "$dir/$key: expected $expected, found $actual"
                }
            }
        assertEquals(emptyList(), mismatches)
    }

    @Test
    fun onlyPositionalStringAndIntegerPlaceholders() {
        val unsupported =
            catalogs.flatMap { (dir, strings) ->
                strings.mapNotNull { (key, text) ->
                    val stray = text.replace(POSITIONAL, "")
                    if ('%' in stray) "$dir/$key: ${text.take(80)}" else null
                }
            }
        assertEquals(emptyList(), unsupported)
    }

    @Test
    fun noAndroidOnlyEscapes() {
        val escaped =
            catalogs.flatMap { (dir, strings) ->
                strings.mapNotNull { (key, text) -> if (ANDROID_ONLY_ESCAPE.containsMatchIn(text)) "$dir/$key" else null }
            }
        assertEquals(emptyList(), escaped)
    }

    private fun placeholders(text: String): Set<String> = POSITIONAL.findAll(text).map { it.value }.toSet()

    private fun parse(file: Path): Map<String, String> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile()).getElementsByTagName("string")
        val strings = LinkedHashMap<String, String>()
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            val key = node.attributes.getNamedItem("name").nodeValue
            assertTrue(strings.put(key, node.textContent) == null, "duplicate key $key in $file")
        }
        return strings
    }

    private fun resourcesRoot(): Path {
        val workingDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        return listOf(
            workingDirectory.resolve("../shared-data/src/commonMain/composeResources").normalize(),
            workingDirectory.resolve("shared-data/src/commonMain/composeResources"),
            workingDirectory.resolve("compose/shared-data/src/commonMain/composeResources"),
        ).firstOrNull(Files::isDirectory) ?: error("Unable to locate shared-data composeResources from $workingDirectory")
    }

    internal companion object {
        val COMPLETE_DIRS =
            listOf("values", "values-ja", "values-zh-rCN", "values-es", "values-pt-rBR", "values-ko", "values-zh-rTW", "values-ru")
        val PARTIAL_DIRS = emptyList<String>()
        val POSITIONAL = Regex("%\\d+\\$[sd]")
        val ANDROID_ONLY_ESCAPE = Regex("""\\['"@?]""")
    }
}
