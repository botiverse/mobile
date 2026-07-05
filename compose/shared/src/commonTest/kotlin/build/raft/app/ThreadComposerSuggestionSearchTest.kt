package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadComposerSuggestionSearchTest {
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
}
