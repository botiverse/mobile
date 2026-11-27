package build.raft.app

import build.raft.app.resources.parseRaftPluralForms
import build.raft.app.resources.raftPluralCategory
import build.raft.app.resources.raftPluralFormFor
import build.raft.app.thread.key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RaftPluralsTest {
    private val forms =
        parseRaftPluralForms(
            """
            {
              "thread_message_count": { "one": "%1${'$'}d сообщение", "few": "%1${'$'}d сообщения", "many": "%1${'$'}d сообщений" },
              "settings_billing_vo_count_seats": { "few": "%1${'$'}d · %2${'$'}s места" },
              "seats_rule": { "arg": 1, "one": "%1${'$'}s место (1 на %2${'$'}d)", "many": "%1${'$'}s мест (1 на %2${'$'}d)" },
              "thread_history_paywall_replies_title": { "many": "%1${'$'}s ответов старше %2${'$'}d дней" }
            }
            """.trimIndent(),
        )

    private fun ru(key: String, vararg args: Any) = raftPluralFormFor(forms, "ru", key, args.toList())

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
    fun theFormForTheCountIsUsedWhateverKeyTheCallSiteChose() {
        assertEquals("1 сообщение", ru("thread_message_count_one", 1))
        assertEquals("21 сообщение", ru("thread_message_count_many", 21))
        assertEquals("3 сообщения", ru("thread_message_count_many", 3))
        assertEquals("12 сообщений", ru("thread_message_count_many", 12))
    }

    @Test
    fun aMissingEntryOrCategoryFallsBackToTheCatalogString() {
        assertNull(ru("unknown_count_many", 3))
        // The entry has no one / many form: the _other text the call site chose is shown.
        assertNull(ru("settings_billing_vo_count_seats_other", 5, "x"))
        assertEquals("3 · x места", ru("settings_billing_vo_count_seats_other", 3, "x"))
        assertNull(ru("thread_message_count_many", "not a number"))
    }
}
