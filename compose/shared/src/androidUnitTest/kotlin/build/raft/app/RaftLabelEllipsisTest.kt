package build.raft.app

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A label never ends with an ellipsis, in any language (Raft DS writing.md, raft-design #1072;
 * the web's `labelEllipsis.test.ts`): Create section, not Create section…; 自定义, not 自定义…. A
 * button, menu item or tab that opens a dialog says the action it leads to. Only progress text
 * (Loading…, {first} is typing…) and a field's placeholder (Search members…) keep one.
 *
 * The English decides what a string is: progress text says what is going on with a word in -ing
 * that starts its last sentence (Loading…, Setup complete. Returning to the app…) or follows
 * is/are/still ({first} is typing…, Video is still processing…); a placeholder's key says so, or
 * is listed in [MAY_END_WITH_ELLIPSIS]. A label with an -ing noun (Edit mapping…) is not progress.
 * Every catalog is checked against its English, so a translation can't add an ellipsis either.
 */
class RaftLabelEllipsisTest {
    private val catalogs: Map<String, Map<String, String>> =
        (ComposeResourcesCatalogParityTest.COMPLETE_DIRS + ComposeResourcesCatalogParityTest.PARTIAL_DIRS)
            .associateWith { dir -> parse(resourcesRoot().resolve(dir).resolve("strings.xml")) }

    @Test
    fun onlyProgressTextAndPlaceholdersEndWithAnEllipsis() {
        val english = catalogs.getValue("values")
        val offenders =
            catalogs.flatMap { (dir, strings) ->
                strings.mapNotNull { (key, text) ->
                    if (endsWithEllipsis(text) && !mayEndWithEllipsis(key, english[key])) "$dir/$key: $text" else null
                }
            }
        assertEquals(emptyList(), offenders, "labels (buttons, menu items, tabs) must not end with an ellipsis")
    }

    @Test
    fun theAllowlistOnlyNamesStringsThatStillEndWithAnEllipsis() {
        val english = catalogs.getValue("values")
        val stale = MAY_END_WITH_ELLIPSIS.keys.filter { key -> english[key]?.let(::endsWithEllipsis) != true }
        assertEquals(emptyList(), stale, "allowlisted keys that are gone or no longer end with an ellipsis")
    }

    @Test
    fun progressTextIsToldApartFromLabels() {
        assertTrue(isProgress("Loading…"))
        assertTrue(isProgress("Saving..."))
        assertTrue(isProgress("{first} and {second} are typing…"))
        assertTrue(isProgress("Video is still processing…"))
        assertTrue(isProgress("Opening %1\$s…"))
        assertTrue(isProgress("Setup complete. Returning to the app…"))
        assertFalse(isProgress("Edit mapping…"))
        assertFalse(isProgress("Create section…"))
        assertFalse(isProgress("Open with…"))
        assertFalse(isProgress("Custom…"))
        assertFalse(mayEndWithEllipsis("home_section_new_menu", "New section…"))
        assertTrue(mayEndWithEllipsis("thread_members_search_placeholder", "Search members…"))
        assertTrue(endsWithEllipsis("Saving..."))
        assertFalse(endsWithEllipsis("Save"))
    }

    private fun mayEndWithEllipsis(key: String, english: String?): Boolean =
        "placeholder" in key || key in MAY_END_WITH_ELLIPSIS || (english != null && isProgress(english))

    private fun endsWithEllipsis(text: String): Boolean = ELLIPSIS_AT_END.containsMatchIn(text)

    private fun isProgress(english: String): Boolean {
        val sentence = english.replace(ARGUMENT, " ").split(SENTENCE_BREAK).last()
        val words = WORD.findAll(sentence).map { it.value }.toList()
        fun ing(word: String?) = word != null && word.length > 4 && word.endsWith("ing", ignoreCase = true)
        return ing(words.firstOrNull()) ||
            words.indices.any { index -> words[index].lowercase() in PROGRESS_LEADS && ing(words.getOrNull(index + 1)) }
    }

    private fun parse(file: Path): Map<String, String> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile()).getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val node = nodes.item(index)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }

    private fun resourcesRoot(): Path {
        val workingDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        return listOf(
            workingDirectory.resolve("src/commonMain/composeResources"),
            workingDirectory.resolve("shared/src/commonMain/composeResources"),
            workingDirectory.resolve("compose/shared/src/commonMain/composeResources"),
        ).firstOrNull(Files::isDirectory) ?: error("Unable to locate shared composeResources from $workingDirectory")
    }

    private companion object {
        /** Strings that end with an ellipsis without being labels, whose keys don't say "placeholder". */
        val MAY_END_WITH_ELLIPSIS =
            mapOf(
                "search_filter_search" to "the search field's placeholder in pickers (Search…)",
                "settings_computers_api_key_prefix" to "a cut-off key prefix, not a label",
            )
        val ELLIPSIS_AT_END = Regex("""(…|\.\.\.)\s*$""")
        val ARGUMENT = Regex("""\{[^{}]*\}|%\d+\$[sd]""")
        val SENTENCE_BREAK = Regex("""[.!?]\s+""")
        val WORD = Regex("[A-Za-z]+")
        val PROGRESS_LEADS = setOf("is", "are", "still")
    }
}
