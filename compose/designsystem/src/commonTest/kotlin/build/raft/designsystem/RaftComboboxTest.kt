package build.raft.designsystem

import build.raft.designsystem.components.RaftComboboxDefaultCreateLabel
import build.raft.designsystem.components.RaftComboboxOption
import build.raft.designsystem.components.RaftComboboxPopoverItem
import build.raft.designsystem.components.RaftMenuLazyRow
import build.raft.designsystem.components.RaftComboboxPresentation
import build.raft.designsystem.components.RaftComboboxRow
import build.raft.designsystem.components.RaftComboboxSheetThreshold
import build.raft.designsystem.components.raftComboboxCreateQuery
import build.raft.designsystem.components.raftComboboxFilter
import build.raft.designsystem.components.raftComboboxFocusesSearch
import build.raft.designsystem.components.raftComboboxPresentation
import build.raft.designsystem.components.raftComboboxLabelOf
import build.raft.designsystem.components.raftComboboxPopoverFocusRows
import build.raft.designsystem.components.raftComboboxPopoverItems
import build.raft.designsystem.components.raftComboboxRows
import build.raft.designsystem.components.raftComboboxSearchPresentation
import build.raft.designsystem.components.raftComboboxToggled
import build.raft.designsystem.theme.RaftScale
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun theTriggerShowsTheOptionLabelOrTheValueItselfBeforeItsOptionLoads() {
        val options = listOf(RaftComboboxOption("codex", "Codex"))
        assertEquals("Codex", raftComboboxLabelOf(options, "codex"))
        assertEquals("my-model", raftComboboxLabelOf(options, "my-model"))
    }
}

class RaftComboboxPresentationTest {
    @Test
    fun onAPhoneAFewValuesOpenTheAnchoredListAndMoreOrTypingOrSeveralTheSheet() {
        assertEquals(10, RaftComboboxSheetThreshold)
        // (options, creates, multiple) to where the list opens in a phone's window.
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
            assertEquals(expected, raftComboboxPresentation(count, creates, multiple, RaftScale.Compact), "$input")
        }
    }

    /**
     * #306: from the app's wide breakpoint (a tablet, the web's layout) every search opens in place,
     * under its trigger, as the web's Combobox popup; a phone or a split-screen half keeps the
     * sheet. A few values stay the Select's anchored list at every width.
     */
    @Test
    fun aWideWindowOpensTheSearchInPlaceUnderItsTrigger() {
        val wide = RaftScale.of(RaftScale.WideMinWidth)
        val phone = RaftScale.of(RaftScale.WideMinWidth - 1.dp)
        assertEquals(RaftComboboxPresentation.Popover, raftComboboxSearchPresentation(wide))
        assertEquals(RaftComboboxPresentation.Sheet, raftComboboxSearchPresentation(phone))
        // (options, creates, multiple) on a wide window.
        val cases =
            listOf(
                Triple(RaftComboboxSheetThreshold, false, false) to RaftComboboxPresentation.AnchoredList,
                Triple(RaftComboboxSheetThreshold + 1, false, false) to RaftComboboxPresentation.Popover,
                Triple(3, true, false) to RaftComboboxPresentation.Popover,
                Triple(3, false, true) to RaftComboboxPresentation.Popover,
            )
        cases.forEach { (input, expected) ->
            val (count, creates, multiple) = input
            assertEquals(expected, raftComboboxPresentation(count, creates, multiple, wide), "$input")
        }
    }

    @Test
    fun onlyACreatingComboboxFocusesItsSearchOnOpen() {
        assertTrue(raftComboboxFocusesSearch(creates = true))
        assertFalse(raftComboboxFocusesSearch(creates = false))
    }
}

class RaftComboboxPopoverItemsTest {
    private val options =
        listOf(
            RaftComboboxOption("opus", "Claude Opus", group = "Anthropic"),
            RaftComboboxOption("o3", "o3", group = "OpenAI", enabled = false),
            RaftComboboxOption("codex", "GPT-5 Codex", group = "OpenAI"),
        )

    /**
     * #306 review: the wide popup's lazy list shows what its sheet shows: the typed value first,
     * the options under their group labels, the list action after a rule, and says what found
     * nothing only when nothing else is there. The keyboard moves over the typed value, the
     * enabled options and the action, never over a label, the rule or a disabled option.
     */
    @Test
    fun theListShowsTheTypedValueTheGroupedOptionsAndTheActionAndTheKeysSkipLabels() {
        val items = raftComboboxPopoverItems(options, createQuery = "my-model", hasAction = true)
        assertEquals(
            listOf(
                RaftComboboxPopoverItem.Create("my-model"),
                RaftComboboxPopoverItem.Group("Anthropic"),
                RaftComboboxPopoverItem.Option(0),
                RaftComboboxPopoverItem.Group("OpenAI"),
                RaftComboboxPopoverItem.Option(1),
                RaftComboboxPopoverItem.Option(2),
                RaftComboboxPopoverItem.Separator,
                RaftComboboxPopoverItem.Action,
            ),
            items,
        )
        assertEquals(
            listOf(
                RaftMenuLazyRow(0, RaftComboboxDefaultCreateLabel("my-model")),
                RaftMenuLazyRow(2, "Claude Opus"),
                RaftMenuLazyRow(5, "GPT-5 Codex"),
                RaftMenuLazyRow(7, "Show all supported"),
            ),
            raftComboboxPopoverFocusRows(items, options, RaftComboboxDefaultCreateLabel, actionLabel = "Show all supported"),
        )
        // Nothing matched: the empty line, unless the typed value or the action is there instead.
        assertEquals(listOf(RaftComboboxPopoverItem.Empty), raftComboboxPopoverItems(emptyList(), createQuery = null, hasAction = false))
        assertEquals(listOf(RaftComboboxPopoverItem.Action), raftComboboxPopoverItems(emptyList(), createQuery = null, hasAction = true))
        assertEquals(listOf(RaftComboboxPopoverItem.Create("x")), raftComboboxPopoverItems(emptyList(), createQuery = "x", hasAction = false))
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

class RaftComboboxToggledTest {
    @Test
    fun aChosenValueIsRemovedKeepingTheOrder() {
        assertEquals(listOf("a", "c"), raftComboboxToggled(listOf("a", "b", "c"), "b"))
        assertEquals(emptyList<String>(), raftComboboxToggled(listOf("a"), "a"))
    }
}
