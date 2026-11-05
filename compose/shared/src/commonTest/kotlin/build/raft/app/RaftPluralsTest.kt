package build.raft.app

import build.raft.app.resources.raftPluralCategory
import build.raft.app.resources.resolveRaftPluralBlocks
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftPluralsTest {
    private val messages =
        "{%1\$d, plural, one {%1\$d сообщение} few {%1\$d сообщения} many {%1\$d сообщений} other {%1\$d сообщения}}"

    private fun ru(count: Long) = resolveRaftPluralBlocks(messages.replace("%1\$d", count.toString()), "ru")

    @Test
    fun russianCategoriesFollowCldr() {
        val expected =
            mapOf(
                "one" to listOf(1L, 21L, 31L, 101L, 1001L),
                "few" to listOf(2L, 3L, 4L, 22L, 23L, 24L, 102L),
                "many" to listOf(0L, 5L, 9L, 10L, 11L, 12L, 13L, 14L, 15L, 19L, 20L, 25L, 100L, 111L, 112L, 114L),
            )
        expected.forEach { (category, counts) ->
            counts.forEach { assertEquals(category, raftPluralCategory("ru", it), "$it") }
        }
    }

    @Test
    fun otherLanguagesKeepTheTwoFormRule() {
        assertEquals("one", raftPluralCategory("en", 1))
        assertEquals("other", raftPluralCategory("en", 0))
        assertEquals("other", raftPluralCategory("en", 21))
        assertEquals("one", raftPluralCategory("pt", 0))
        assertEquals("other", raftPluralCategory("ko", 1))
    }

    @Test
    fun aBlockPicksTheBranchOfTheSubstitutedCount() {
        assertEquals("1 сообщение", ru(1))
        assertEquals("21 сообщение", ru(21))
        assertEquals("2 сообщения", ru(2))
        assertEquals("24 сообщения", ru(24))
        assertEquals("5 сообщений", ru(5))
        assertEquals("11 сообщений", ru(11))
        assertEquals("12 сообщений", ru(12))
        assertEquals("0 сообщений", ru(0))
    }

    @Test
    fun blocksSitInsideSurroundingTextAndRepeat() {
        val template = "Показано {3, plural, one {# запись} few {# записи} many {# записей} other {# записи}}, ещё {7, plural, few {a} many {b} other {c}}."
        assertEquals("Показано # записи, ещё b.", resolveRaftPluralBlocks(template, "ru"))
    }

    @Test
    fun anExactBranchBeatsTheCategoryAndAMissingOneFallsToOther() {
        assertEquals("ни одного", resolveRaftPluralBlocks("{0, plural, =0 {ни одного} many {много} other {x}}", "ru"))
        // No few branch: the other text is shown, as for a Russian string without few / many forms.
        assertEquals("x", resolveRaftPluralBlocks("{2, plural, one {o} other {x}}", "ru"))
    }

    @Test
    fun groupedCountsAndNegativeCountsAreRead() {
        assertEquals("many", resolveRaftPluralBlocks("{1,200, plural, one {one} few {few} many {many} other {other}}", "ru"))
        assertEquals("one", resolveRaftPluralBlocks("{-1, plural, one {one} other {other}}", "en"))
    }

    @Test
    fun textWithoutAWellFormedBlockIsUnchanged() {
        listOf(
            "21 сообщение",
            "{first} and {second} are typing…",
            "plural of {x}",
            "{3, plural, one {unclosed",
            "{3, plural, one {x}}",
        ).forEach { assertEquals(it, resolveRaftPluralBlocks(it, "ru"), it) }
    }
}
