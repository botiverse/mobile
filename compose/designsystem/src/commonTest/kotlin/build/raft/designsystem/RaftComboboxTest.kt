package build.raft.designsystem

import build.raft.designsystem.components.RaftComboboxOption
import build.raft.designsystem.components.RaftComboboxPresentation
import build.raft.designsystem.components.RaftComboboxRow
import build.raft.designsystem.components.RaftComboboxSheetThreshold
import build.raft.designsystem.components.raftComboboxCreateQuery
import build.raft.designsystem.components.raftComboboxFilter
import build.raft.designsystem.components.raftComboboxFocusesSearch
import build.raft.designsystem.components.raftComboboxPresentation
import build.raft.designsystem.components.raftComboboxLabelOf
import build.raft.designsystem.components.raftComboboxRows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RaftComboboxFilterTest {
    private val models =
        listOf(
            RaftComboboxOption("opus", "Claude Opus", group = "Anthropic", keywords = listOf("claude-opus-4")),
            RaftComboboxOption("sonnet", "Claude Sonnet", group = "Anthropic"),
            RaftComboboxOption("gpt", "GPT-5 Codex", group = "OpenAI", keywords = listOf("codex")),
            RaftComboboxOption("gemini", "Gemini 2.5 Pro", group = "Google", description = "Long context"),
        )

    private fun matches(query: String): List<String> = raftComboboxFilter(models, query).map { it.value }

    @Test
    fun aBlankQueryListsEveryOptionAsGiven() {
        listOf("", "   ", "\t").forEach { query ->
            assertSame(models, raftComboboxFilter(models, query), "query \"$query\"")
        }
    }

    @Test
    fun matchesTheLabelOrAKeywordIgnoringCaseAndKeepsTheOrder() {
        val cases: Map<String, List<String>> =
            mapOf(
                "claude" to listOf("opus", "sonnet"),
                "CLAUDE" to listOf("opus", "sonnet"),
                "  sonnet " to listOf("sonnet"),
                // A keyword, not in the label.
                "opus-4" to listOf("opus"),
                "codex" to listOf("gpt"),
                // Part of a word.
                "em" to listOf("gemini"),
                // Every word must match, in any order, each in the label or a keyword.
                "sonnet claude" to listOf("sonnet"),
                "claude  codex" to emptyList<String>(),
                // The description and the group are not searched.
                "long" to emptyList<String>(),
                "anthropic" to emptyList<String>(),
                "zz" to emptyList<String>(),
            )
        cases.forEach { (query, expected) -> assertEquals(expected, matches(query), "query \"$query\"") }
    }
}

class RaftComboboxRowsTest {
    @Test
    fun eachRunOfAGroupGetsOneLabelAndUngroupedOptionsNone() {
        val options =
            listOf(
                RaftComboboxOption("none", "Unassigned"),
                RaftComboboxOption("wug", "Wug", group = "Agents"),
                RaftComboboxOption("cindy", "Cindy", group = "Agents"),
                RaftComboboxOption("ada", "Ada", group = "People"),
            )
        assertEquals(
            listOf(
                RaftComboboxRow.Option(0),
                RaftComboboxRow.Group("Agents"),
                RaftComboboxRow.Option(1),
                RaftComboboxRow.Option(2),
                RaftComboboxRow.Group("People"),
                RaftComboboxRow.Option(3),
            ),
            raftComboboxRows(options),
        )
    }

    @Test
    fun aGroupWithNoMatchLosesItsLabel() {
        val options =
            listOf(
                RaftComboboxOption("wug", "Wug", group = "Agents"),
                RaftComboboxOption("ada", "Ada", group = "People"),
            )
        assertEquals(
            listOf(RaftComboboxRow.Group("People"), RaftComboboxRow.Option(0)),
            raftComboboxRows(raftComboboxFilter(options, "ada")),
        )
        assertEquals(emptyList<RaftComboboxRow>(), raftComboboxRows(raftComboboxFilter(options, "zz")))
    }

    @Test
    fun theTriggerShowsTheOptionLabelOrTheValueItselfBeforeItsOptionLoads() {
        val options = listOf(RaftComboboxOption("codex", "Codex"))
        assertEquals("Codex", raftComboboxLabelOf(options, "codex"))
        assertEquals("my-model", raftComboboxLabelOf(options, "my-model"))
    }
}

class RaftComboboxPresentationTest {
    @Test
    fun aFewValuesOpenTheAnchoredListAndMoreOrTypingOrSeveralTheSheet() {
        assertEquals(10, RaftComboboxSheetThreshold)
        // (options, creates, multiple) to where the list opens.
        val cases =
            listOf(
                Triple(0, false, false) to RaftComboboxPresentation.AnchoredList,
                Triple(RaftComboboxSheetThreshold, false, false) to RaftComboboxPresentation.AnchoredList,
                Triple(RaftComboboxSheetThreshold + 1, false, false) to RaftComboboxPresentation.Sheet,
                Triple(3, true, false) to RaftComboboxPresentation.Sheet,
                Triple(3, false, true) to RaftComboboxPresentation.Sheet,
                Triple(50, true, true) to RaftComboboxPresentation.Sheet,
            )
        cases.forEach { (input, expected) ->
            val (count, creates, multiple) = input
            assertEquals(expected, raftComboboxPresentation(count, creates, multiple), "$input")
        }
    }

    @Test
    fun onlyACreatingComboboxFocusesItsSearchOnOpen() {
        assertTrue(raftComboboxFocusesSearch(creates = true))
        assertFalse(raftComboboxFocusesSearch(creates = false))
    }
}

class RaftComboboxCreateQueryTest {
    private val options = listOf(RaftComboboxOption("codex", "GPT-5 Codex"), RaftComboboxOption("opus", "Claude Opus"))

    @Test
    fun offersTheTrimmedTextUnlessBlankOrAlreadyALabel() {
        val cases: Map<String, String?> =
            mapOf(
                "" to null,
                "   " to null,
                " my-model " to "my-model",
                // A part of a label is still new.
                "Codex" to "Codex",
                // Exactly a label, ignoring case and surrounding space.
                "claude opus" to null,
                "  GPT-5 CODEX " to null,
            )
        cases.forEach { (query, expected) -> assertEquals(expected, raftComboboxCreateQuery(options, query), "query \"$query\"") }
    }

    @Test
    fun aChosenValueThatIsNotAnOptionIsNotOfferedAgain() {
        assertEquals(null, raftComboboxCreateQuery(options, "my-model", taken = listOf("my-model")))
        assertEquals("other", raftComboboxCreateQuery(options, "other", taken = listOf("my-model")))
    }
}
