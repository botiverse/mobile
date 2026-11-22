package build.raft.app

import build.raft.app.model.MemberKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThreadComposerSuggestionSearchTest {
    @Test
    fun anEntrysTierOrdersComparableMatchesButNeverOutranksAnExactNameOrTheCap() {
        // slock #14228 (web composerSuggestionSearch `tier` / `matchBand`).
        fun entry(index: Int, name: String, tier: Int = 0) =
            ThreadComposerSuggestionSearchEntry(index, name, listOf(ThreadComposerSuggestionSearchField(name, priority = 0)), tier)
        val strong = listOf(entry(0, "onboarding-old", 2), entry(1, "wg-onboarding-v3"), entry(2, "onboarding-new", 1))
        assertEquals(listOf("wg-onboarding-v3", "onboarding-new", "onboarding-old"), rankThreadComposerSuggestionEntries("onboarding", strong))

        // Forty archived prefix matches cannot push a live word-start match off the capped list,
        val many = List(40) { entry(it, "onboarding-$it", 2) } + entry(40, "wg-onboarding-v3")
        val ranked = rankThreadComposerSuggestionEntries("onboarding", many)
        assertEquals("wg-onboarding-v3", ranked.first())
        assertEquals(30, ranked.size)
        // nor forty loose live matches an exact name in a higher tier.
        val loose = List(40) { entry(it, "d-e-s-i-g-n-$it") } + entry(40, "design", 1)
        assertEquals("design", rankThreadComposerSuggestionEntries("design", loose).first())
    }

    @Test
    fun memberSearchRanksExactPrefixSubstringAndFuzzyMatches() {
        val suggestions =
            listOf(
                RaftComposerMemberItem(displayName = "Android Developer", handle = "android-developer"),
                RaftComposerMemberItem(displayName = "Cindy", handle = "cindy"),
                RaftComposerMemberItem(displayName = "Dev Ops", handle = "devops"),
                RaftComposerMemberItem(displayName = "Product Owner", handle = "owner", description = "Android developer contact")
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
                RaftComposerChannelItem(name = "对话流专修"),
                RaftComposerChannelItem(name = "输入框专修"),
                RaftComposerChannelItem(name = "markdown专修")
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
    fun pendingQueryKeepsPreviousCandidatesUntilNewRankingIsReady() {
        assertEquals(
            listOf("artin", "android-developer"),
            displayedThreadComposerSuggestions(
                usesImmediateSuggestions = false,
                rankingReady = false,
                rankingResultSuggestions = listOf("artin", "android-developer"),
                immediateSuggestions = emptyList(),
                pendingFallbackSuggestions = listOf("cindy", "artin", "android-developer")
            )
        )
    }

    @Test
    fun blankMemberQueryPrioritizesCurrentChannelBeforeSourceServerRoundRobin() {
        val outsideLocal =
            (0 until 35).map { index ->
                RaftComposerMemberItem(
                    displayName = "Outside local $index",
                    handle = "outside-local-$index",
                    inMentionChannel = false,
                    sourceServerId = "server-local",
                    isLocalServer = true
                )
            }
        val outsideRemote =
            (0 until 10).map { index ->
                RaftComposerMemberItem(
                    displayName = "Outside remote $index",
                    handle = "outside-remote-$index",
                    inMentionChannel = false,
                    sourceServerId = "server-remote",
                    isLocalServer = false,
                    sourceServerLabel = "Remote Lab"
                )
            }
        val inChannelLocal =
            listOf(
                RaftComposerMemberItem(
                    displayName = "Channel agent",
                    handle = "channel-agent",
                    inMentionChannel = true,
                    kind = MemberKind.Agent,
                    sourceServerId = "server-local",
                    isLocalServer = true
                )
            )
        val inChannelRemote =
            listOf(
                RaftComposerMemberItem(
                    displayName = "Remote channel agent",
                    handle = "remote-channel-agent",
                    inMentionChannel = true,
                    kind = MemberKind.Agent,
                    sourceServerId = "server-remote",
                    isLocalServer = false,
                    sourceServerLabel = "Remote Lab"
                )
            )

        val result =
            immediateThreadComposerMemberSuggestions(
                query = "",
                suggestions = outsideLocal + outsideRemote + inChannelLocal + inChannelRemote
            )

        assertEquals(30, result.size)
        assertEquals(
            listOf("channel-agent", "remote-channel-agent"),
            result.take(2).map { it.handle }
        )
        assertTrue(result.take(2).all { it.inMentionChannel })
        assertTrue(result.drop(2).none { it.inMentionChannel })
        assertEquals(setOf("server-local", "server-remote"), result.take(2).mapNotNull { it.sourceServerId }.toSet())
    }
}
