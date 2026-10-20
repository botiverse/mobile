package build.raft.app

import build.raft.app.model.MemberKind
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
    fun memberSearchMatchesMixedLatinChinesePinyin() {
        val suggestions =
            listOf(
                RaftComposerMemberItem(displayName = "KMP-专家", handle = "KMP-专家"),
                RaftComposerMemberItem(displayName = "KMP Developer", handle = "KMP-Developer")
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
                RaftComposerChannelItem(name = "general", subtitle = "markdown triage"),
                RaftComposerChannelItem(name = "markdown专修", subtitle = "docs")
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
                RaftComposerChannelItem(name = "general"),
                RaftComposerChannelItem(name = "c++")
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
                RaftComposerMemberItem(displayName = "B", handle = "b"),
                RaftComposerMemberItem(displayName = "A", handle = "a")
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
                RaftComposerMemberItem(displayName = "Member $index", handle = "member-$index")
            }

        assertEquals(
            (0 until 30).map { index -> "member-$index" },
            filterThreadComposerMemberSuggestions("", suggestions).map { it.handle }
        )
    }

    @Test
    fun blankQueryCanRenderImmediatePreviewWithoutIndexing() {
        val suggestions = (0 until 100).map { index -> "candidate-$index" }

        assertEquals(
            (0 until 30).map { index -> "candidate-$index" },
            immediateThreadComposerSuggestions("", suggestions)
        )
        assertEquals(emptyList(), immediateThreadComposerSuggestions("can", suggestions))
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
    fun firstPendingQueryKeepsBoundedSourcePreviewInsteadOfEmptyState() {
        assertEquals(
            listOf("cindy", "artin"),
            displayedThreadComposerSuggestions(
                usesImmediateSuggestions = false,
                rankingReady = false,
                rankingResultSuggestions = emptyList(),
                immediateSuggestions = emptyList(),
                pendingFallbackSuggestions = listOf("cindy", "artin")
            )
        )
    }

    @Test
    fun readyEmptyRankingReplacesRetainedCandidatesWithRealEmptyState() {
        assertEquals(
            emptyList(),
            displayedThreadComposerSuggestions(
                usesImmediateSuggestions = false,
                rankingReady = true,
                rankingResultSuggestions = emptyList(),
                immediateSuggestions = emptyList(),
                pendingFallbackSuggestions = listOf("artin", "cindy")
            )
        )
    }

    @Test
    fun blankQueryUsesCurrentImmediateEmptyStateInsteadOfStaleRanking() {
        assertEquals(
            emptyList(),
            displayedThreadComposerSuggestions(
                usesImmediateSuggestions = true,
                rankingReady = false,
                rankingResultSuggestions = listOf("artin"),
                immediateSuggestions = emptyList(),
                pendingFallbackSuggestions = emptyList()
            )
        )
    }

    @Test
    fun blankMemberQueryKeepsSingleServerOrderAndLimit() {
        val suggestions =
            (0 until 100).map { index ->
                RaftComposerMemberItem(
                    displayName = "Member $index",
                    handle = "member-$index",
                    sourceServerId = "server-local",
                    isLocalServer = true
                )
            }

        assertEquals(
            (0 until 30).map { index -> "member-$index" },
            immediateThreadComposerMemberSuggestions("", suggestions).map { it.handle }
        )
        assertEquals(emptyList(), immediateThreadComposerMemberSuggestions("member", suggestions))
    }

    @Test
    fun blankMemberQueryInterleavesJointServerCandidatesBeforeLimit() {
        val local =
            (0 until 35).map { index ->
                RaftComposerMemberItem(
                    displayName = "Local $index",
                    handle = "local-$index",
                    sourceServerId = "server-local",
                    isLocalServer = true
                )
            }
        val remote =
            (0 until 20).map { index ->
                RaftComposerMemberItem(
                    displayName = "Remote $index",
                    handle = "remote-$index",
                    sourceServerId = "server-remote",
                    isLocalServer = false,
                    sourceServerLabel = "Remote Lab"
                )
            }

        val result = immediateThreadComposerMemberSuggestions("", local + remote)

        assertEquals(30, result.size)
        assertEquals(
            listOf("local-0", "local-1", "remote-0", "local-2", "local-3", "remote-1"),
            result.take(6).map { it.handle }
        )
        assertEquals(20, result.count { it.isLocalServer })
        assertEquals(10, result.count { !it.isLocalServer })
        assertEquals(setOf("server-local", "server-remote"), result.mapNotNull { it.sourceServerId }.toSet())
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

    @Test
    fun blankMemberQueryPreservesThreadThenChannelPriorityBeforeOutsideLimit() {
        val outside =
            (0 until 35).map { index ->
                RaftComposerMemberItem(
                    displayName = "Outside $index",
                    handle = "outside-$index",
                    inMentionChannel = false,
                    sourceServerId = "server-local",
                    isLocalServer = true
                )
            }
        val threadParticipant =
            RaftComposerMemberItem(
                displayName = "Thread participant",
                handle = "thread-participant",
                inMentionChannel = false,
                inThread = true,
                kind = MemberKind.Agent,
                sourceServerId = "server-remote",
                isLocalServer = false,
                sourceServerLabel = "Remote Lab"
            )
        val channelMember =
            RaftComposerMemberItem(
                displayName = "Channel agent",
                handle = "channel-agent",
                inMentionChannel = true,
                kind = MemberKind.Agent,
                sourceServerId = "server-local",
                isLocalServer = true
            )

        val result = immediateThreadComposerMemberSuggestions("", outside + channelMember + threadParticipant)

        assertEquals(30, result.size)
        assertEquals(
            listOf("thread-participant", "channel-agent"),
            result.take(2).map { it.handle }
        )
        assertTrue(result[0].inThread)
        assertTrue(result[1].inMentionChannel)
        assertTrue(result.drop(2).none { it.inThread || it.inMentionChannel })
    }

    @Test
    fun blankMemberQueryUsesExclusiveIdentityTiersWhenThreadAndChannelEntriesOverlap() {
        val outside =
            (0 until 35).map { index ->
                RaftComposerMemberItem(
                    displayName = "Outside $index",
                    handle = "outside-$index",
                    inMentionChannel = false
                )
            }
        val channelCopy =
            RaftComposerMemberItem(
                displayName = "Shared agent",
                handle = "shared-agent",
                memberId = "agent-1",
                inMentionChannel = true,
                kind = MemberKind.Agent
            )
        val threadCopy = channelCopy.copy(inThread = true)

        val result = immediateThreadComposerMemberSuggestions("", outside + channelCopy + threadCopy)

        assertEquals(30, result.size)
        assertEquals(listOf(threadCopy), result.filter { it.memberId == "agent-1" })
        assertEquals(threadCopy, result.first())
    }

    @Test
    fun blankMemberQueryKeepsCurrentScopeFirstEvenWhenDirectoryFitsImmediateLimit() {
        val outside =
            RaftComposerMemberItem(
                displayName = "Outside",
                handle = "outside",
                inMentionChannel = false
            )
        val agent =
            RaftComposerMemberItem(
                displayName = "Channel agent",
                handle = "channel-agent",
                inMentionChannel = true,
                kind = MemberKind.Agent
            )

        val result = immediateThreadComposerMemberSuggestions("", listOf(outside, agent))
        assertEquals(listOf("channel-agent", "outside"), result.map { it.handle })
        assertEquals(agent, result.firstComposerMemberSuggestionCandidate())
    }

    @Test
    fun tenThousandCandidateRankingKeepsBoundedTopThirtyWithinBudget() {
        val suggestions =
            (0 until 10_000).map { index ->
                RaftComposerMemberItem(
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
