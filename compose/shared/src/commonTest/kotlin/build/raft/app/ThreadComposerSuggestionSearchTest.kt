package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class ThreadComposerSuggestionSearchTest {
    private data class Person(
        val id: String,
        val handle: String,
        val displayName: String,
        val description: String? = null,
        val sourceServerLabel: String? = null
    )

    @Test
    fun memberSearchRanksExactPrefixSubstringAndFuzzyMatches() {
        val suggestions =
            listOf(
                KuiklyComposerMemberItem(displayName = "Android Developer", handle = "android-developer"),
                KuiklyComposerMemberItem(displayName = "Cindy", handle = "cindy"),
                KuiklyComposerMemberItem(displayName = "Dev Ops", handle = "devops"),
                KuiklyComposerMemberItem(displayName = "Product Owner", handle = "owner", description = "Android developer contact")
            )

        assertEquals(
            listOf("devops", "android-developer", "owner"),
            filterThreadComposerMemberSuggestions("dev", suggestions).map { it.handle }
        )
        assertEquals(
            listOf("android-developer"),
            filterThreadComposerMemberSuggestions("anddev", suggestions).map { it.handle }
        )
    }

    @Test
    fun channelSearchMatchesChinesePinyinFullInitialsAndMixedQuery() {
        val suggestions =
            listOf(
                KuiklyComposerChannelItem(name = "对话流专修"),
                KuiklyComposerChannelItem(name = "输入框专修"),
                KuiklyComposerChannelItem(name = "markdown专修")
            )

        assertEquals(
            listOf("对话流专修"),
            filterThreadComposerChannelSuggestions("duihua", suggestions).map { it.name }
        )
        assertEquals(
            listOf("对话流专修"),
            filterThreadComposerChannelSuggestions("dui hua", suggestions).map { it.name }
        )
        assertEquals(
            listOf("对话流专修"),
            filterThreadComposerChannelSuggestions("dh", suggestions).map { it.name }
        )
        assertEquals(
            listOf("对话流专修"),
            filterThreadComposerChannelSuggestions("dhlzx", suggestions).map { it.name }
        )
        assertEquals(
            listOf("对话流专修"),
            filterThreadComposerChannelSuggestions("对hua", suggestions).map { it.name }
        )
        assertEquals(
            listOf("输入框专修"),
            filterThreadComposerChannelSuggestions("shuru", suggestions).map { it.name }
        )
        assertEquals(
            listOf("输入框专修"),
            filterThreadComposerChannelSuggestions("sr", suggestions).map { it.name }
        )
    }

    @Test
    fun memberSearchMatchesMixedLatinChinesePinyin() {
        val suggestions =
            listOf(
                KuiklyComposerMemberItem(displayName = "KMP-专家", handle = "KMP-专家"),
                KuiklyComposerMemberItem(displayName = "KMP Developer", handle = "KMP-Developer")
            )

        assertEquals(
            listOf("KMP-专家"),
            filterThreadComposerMemberSuggestions("zhuanjia", suggestions).map { it.handle }
        )
        assertEquals(
            listOf("KMP-专家"),
            filterThreadComposerMemberSuggestions("kmpzhuanjia", suggestions).map { it.handle }
        )
    }

    @Test
    fun channelNameMatchRanksAheadOfSubtitleMatch() {
        val suggestions =
            listOf(
                KuiklyComposerChannelItem(name = "general", subtitle = "markdown triage"),
                KuiklyComposerChannelItem(name = "markdown专修", subtitle = "docs")
            )

        assertEquals(
            listOf("markdown专修", "general"),
            filterThreadComposerChannelSuggestions("markdown", suggestions).map { it.name }
        )
    }

    @Test
    fun legacyRawContainsStillMatchesSymbolQueries() {
        val suggestions =
            listOf(
                KuiklyComposerChannelItem(name = "general"),
                KuiklyComposerChannelItem(name = "c++")
            )

        assertEquals(
            listOf("c++"),
            filterThreadComposerChannelSuggestions("++", suggestions).map { it.name }
        )
    }

    @Test
    fun blankQueryKeepsOriginalOrder() {
        val suggestions =
            listOf(
                KuiklyComposerMemberItem(displayName = "B", handle = "b"),
                KuiklyComposerMemberItem(displayName = "A", handle = "a")
            )

        assertEquals(
            listOf("b", "a"),
            filterThreadComposerMemberSuggestions("", suggestions).map { it.handle }
        )
    }

    @Test
    fun blankQueryIsBoundedToThirtyResults() {
        val suggestions =
            (0 until 100).map { index ->
                KuiklyComposerMemberItem(displayName = "Member $index", handle = "member-$index")
            }

        assertEquals(
            (0 until 30).map { index -> "member-$index" },
            filterThreadComposerMemberSuggestions("", suggestions).map { it.handle }
        )
    }

    @Test
    fun tenThousandCandidateRankingKeepsBoundedTopThirtyWithinBudget() {
        val suggestions =
            (0 until 10_000).map { index ->
                KuiklyComposerMemberItem(
                    displayName = "Target Member $index",
                    handle = "target-member-${index.toString().padStart(5, '0')}",
                    description = "Large directory benchmark candidate"
                )
            }
        val started = TimeSource.Monotonic.markNow()

        val result = filterThreadComposerMemberSuggestions("target", suggestions)

        assertEquals(30, result.size)
        assertEquals(
            (0 until 30).map { index -> "target-member-${index.toString().padStart(5, '0')}" },
            result.map { it.handle }
        )
        assertTrue(
            started.elapsedNow() < 5.seconds,
            "10k candidate ranking exceeded the 5s regression budget"
        )
    }

    @Test
    fun peoplePickersReuseMemberPinyinFuzzyRanking() {
        val people =
            listOf(
                Person(id = "expert", handle = "KMP-专家", displayName = "KMP 专家"),
                Person(id = "android", handle = "android-developer", displayName = "Android Developer"),
                Person(id = "server", handle = "alice", displayName = "Alice", sourceServerLabel = "duihua server")
            )
        fun search(query: String): List<String> =
            filterPeopleSuggestions(
                query = query,
                suggestions = people,
                handle = Person::handle,
                displayName = Person::displayName,
                description = Person::description,
                sourceServerLabel = Person::sourceServerLabel,
                legacyId = Person::id
            ).map(Person::id)

        assertEquals(listOf("expert"), search("zhuanjia"))
        assertEquals(listOf("android"), search("anddev"))
        assertEquals(listOf("server"), search("duihua"))
        assertEquals(listOf("android"), search("android"))
    }
}
