package build.raft.app

import build.raft.app.data.ResolvedActor
import build.raft.app.markdown.ThreadMarkdownTag
import build.raft.app.markdown.ThreadMarkdownTagKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Web parity for `botiverse/slock` #16493 (`mentionLabel.ts` + `mentionActionPromptContract.test.ts`):
 * the pending mention banner resolves display names from the server/channel roster (plus cached
 * joint-channel actors) using the same `@Display Name` rule as sent message bodies, falling back
 * to the raw handle when nobody matches or when `targetType` disagrees.
 */
class PendingMentionTargetNamesTest {
    @Test
    fun mentionVisibleLabelAndTargetLabelResolveDisplayNameOrFallBackToHandle() {
        assertEquals("@WA kirby", mentionVisibleLabel("qa5537502fa9cb84b1", "WA kirby"))
        assertEquals("@WA kirby", mentionVisibleLabel("@qa5537502fa9cb84b1", "@WA kirby"))
        assertEquals("@qa5537502fa9cb84b1", mentionVisibleLabel("qa5537502fa9cb84b1", null))
        assertEquals("@qa5537502fa9cb84b1", mentionVisibleLabel("qa5537502fa9cb84b1", "   "))
        assertEquals("@unknown", mentionVisibleLabel("", null))

        val human = action(targetType = "user", targetHandle = "qa5537502fa9cb84b1")
        assertEquals("@WA kirby", human.targetLabel("WA kirby"))
        assertEquals("@qa5537502fa9cb84b1", human.targetLabel(null))

        val agent = action(targetType = "agent", targetHandle = "@Nova")
        assertEquals("@Nova the Agent", agent.targetLabel("Nova the Agent"))
        assertEquals("@Nova", agent.targetLabel(null))

        val unknownKind = action(targetType = "unknown", targetHandle = "Volta")
        assertEquals("Volta", unknownKind.targetLabel("Ignored Display Name"))
    }

    @Test
    fun serverRosterResolvesNonChannelMembersAndEnforcesTargetType() {
        val targetMap =
            buildPendingMentionTargetMap(
                serverMembers =
                    listOf(
                        member(id = "user-kirby", kind = MemberKind.Human, handle = "qa5537502fa9cb84b1", displayName = "WA kirby"),
                        member(id = "user-nameless", kind = MemberKind.Human, handle = "nameless", displayName = ""),
                        member(id = "agent-nova", kind = MemberKind.Agent, handle = "Nova", displayName = "Nova the Agent"),
                    ),
                // Empty channel roster & empty composer suggestions (private channel where non-members aren't suggested).
                channelMembers = emptyList(),
                memberSuggestions = emptyList(),
            )

        val humanAction = action(targetType = "user", targetHandle = "qa5537502fa9cb84b1")
        assertEquals("WA kirby", targetMap.displayName(humanAction))
        assertEquals("@WA kirby", humanAction.targetLabel(targetMap.displayName(humanAction)))

        val agentAction = action(targetType = "agent", targetHandle = "Nova", reason = PendingMentionReasonLoungeAgentExcluded)
        assertEquals("Nova the Agent", targetMap.displayName(agentAction))
        assertEquals("@Nova the Agent", agentAction.targetLabel(targetMap.displayName(agentAction)))

        // Case-insensitive lookup still resolves.
        val mixedCase = action(targetType = "agent", targetHandle = "nova")
        assertEquals("Nova the Agent", targetMap.displayName(mixedCase))

        // Someone with no display name stays as the handle written.
        val nameless = action(targetType = "user", targetHandle = "nameless")
        assertEquals("nameless", targetMap.displayName(nameless))
        assertEquals("@nameless", nameless.targetLabel(targetMap.displayName(nameless)))

        // Unknown handle falls back to null / handle as written.
        val stranger = action(targetType = "user", targetHandle = "stranger")
        assertNull(targetMap.displayName(stranger))
        assertEquals("@stranger", stranger.targetLabel(targetMap.displayName(stranger)))

        // A person's handle that matches only an agent's name stays as written (row's own kind decides).
        val wrongKindUser = action(targetType = "user", targetHandle = "Nova")
        assertNull(targetMap.displayName(wrongKindUser))
        assertEquals("@Nova", wrongKindUser.targetLabel(targetMap.displayName(wrongKindUser)))

        val wrongKindAgent = action(targetType = "agent", targetHandle = "qa5537502fa9cb84b1")
        assertNull(targetMap.displayName(wrongKindAgent))
        assertEquals("@qa5537502fa9cb84b1", wrongKindAgent.targetLabel(targetMap.displayName(wrongKindAgent)))
    }

    @Test
    fun sameHandleHumanAndAgentDisambiguateByTargetType() {
        val targetMap =
            buildPendingMentionTargetMap(
                serverMembers =
                    listOf(
                        member(id = "u-skyzh", kind = MemberKind.Human, handle = "skyzh", displayName = "Alex Chi"),
                        member(id = "a-skyzh", kind = MemberKind.Agent, handle = "skyzh", displayName = "Skyzh Bot"),
                    ),
            )

        assertEquals("Alex Chi", targetMap.displayName(action(targetType = "user", targetHandle = "skyzh")))
        assertEquals("Skyzh Bot", targetMap.displayName(action(targetType = "agent", targetHandle = "skyzh")))
    }

    @Test
    fun precedenceOrdersChannelOverServerOverSuggestionsOverJointActors() {
        val targetMap =
            buildPendingMentionTargetMap(
                jointActors =
                    listOf(
                        ResolvedActor(
                            kind = MemberKind.Human,
                            id = "ext-1",
                            homeServerId = "server-other",
                            name = "partner",
                            displayName = "Partner Remote",
                            avatarUrl = "https://cdn.example/partner.png",
                            gravatarHash = null,
                        ),
                        ResolvedActor(
                            kind = MemberKind.Human,
                            id = "u-shared",
                            homeServerId = "server-other",
                            name = "shared",
                            displayName = "From Joint Actor",
                            avatarUrl = null,
                            gravatarHash = null,
                        ),
                    ),
                memberSuggestions =
                    listOf(
                        RaftComposerMemberItem(
                            displayName = "From Suggestion",
                            handle = "shared",
                            kind = MemberKind.Human,
                            memberId = "u-shared",
                        ),
                    ),
                serverMembers =
                    listOf(
                        member(id = "u-shared", kind = MemberKind.Human, handle = "shared", displayName = "From Server"),
                        member(id = "u-server-only", kind = MemberKind.Human, handle = "server_only", displayName = "Server Only"),
                    ),
                channelMembers =
                    listOf(
                        member(id = "u-shared", kind = MemberKind.Human, handle = "shared", displayName = "From Channel"),
                        member(id = "ext-chan", kind = MemberKind.Agent, handle = "joint_bot", displayName = "Joint Channel Bot"),
                    ),
            )

        assertEquals("From Channel", targetMap.displayName(action("user", "shared")))
        assertEquals("Server Only", targetMap.displayName(action("user", "server_only")))
        assertEquals("Joint Channel Bot", targetMap.displayName(action("agent", "joint_bot")))
        val resolvedJoint = targetMap.resolve("user", "partner")
        assertEquals("Partner Remote", resolvedJoint?.displayName)
        assertEquals("ext-1", resolvedJoint?.memberId)
        assertEquals("server-other", resolvedJoint?.sourceServerId)
        assertEquals("https://cdn.example/partner.png", resolvedJoint?.avatarWireValue)
    }

    @Test
    fun exactCaseHandleMatchBeatsCaseInsensitiveMatch() {
        val targetMap =
            buildPendingMentionTargetMap(
                serverMembers =
                    listOf(
                        member(id = "u-lower", kind = MemberKind.Human, handle = "alice", displayName = "Alice Lower"),
                        member(id = "u-upper", kind = MemberKind.Human, handle = "Alice", displayName = "Alice Upper"),
                    ),
            )

        assertEquals("Alice Lower", targetMap.displayName(action("user", "alice")))
        assertEquals("Alice Upper", targetMap.displayName(action("user", "Alice")))
        assertEquals("Alice Upper", targetMap.displayName(action("user", "ALICE")))
    }

    @Test
    fun pendingMentionLabelsMatchSentMessageMarkdownMentionsForSameRoster() {
        val channelMembers =
            listOf(
                RaftThreadMemberItem(id = "agent-nova", displayName = "Nova the Agent", handle = "Nova", kind = "agent"),
            )
        val serverCandidates =
            listOf(
                RaftThreadMemberItem(id = "user-kirby", displayName = "WA kirby", handle = "qa5537502fa9cb84b1", kind = "human"),
                RaftThreadMemberItem(id = "user-nameless", displayName = "", handle = "nameless", kind = "human"),
            )
        val markdownLabels = threadMarkdownMentionDisplayLabels(channelMembers, serverCandidates)
        val bannerMap =
            buildPendingMentionTargetMap(
                payloadMembers = channelMembers,
                payloadMemberCandidates = serverCandidates,
            )

        fun sentLabel(handle: String) =
            resolveThreadMarkdownTagDisplayLabel(ThreadMarkdownTag("@$handle", ThreadMarkdownTagKind.UserMention), markdownLabels)

        val kirbyAction = action("user", "qa5537502fa9cb84b1")
        val novaAction = action("agent", "Nova")
        val namelessAction = action("user", "nameless")
        val strangerAction = action("user", "stranger")

        assertEquals(sentLabel("qa5537502fa9cb84b1"), kirbyAction.targetLabel(bannerMap.displayName(kirbyAction)))
        assertEquals(sentLabel("Nova"), novaAction.targetLabel(bannerMap.displayName(novaAction)))
        assertEquals(sentLabel("nameless"), namelessAction.targetLabel(bannerMap.displayName(namelessAction)))
        assertEquals(sentLabel("stranger"), strangerAction.targetLabel(bannerMap.displayName(strangerAction)))
    }

    @Test
    fun descriptionsAndDeliveredNoticesUseResolvedDisplayNameAcrossAllStates() {
        val kirby = action("user", "qa5537502fa9cb84b1", availableActions = listOf("add", "notify"))
        assertEquals(
            "@WA kirby isn't in #general. Add them to #general or send a one-time notify.",
            kirby.pendingMentionActionDescription("#general", displayName = "WA kirby"),
        )
        assertEquals(
            "@WA kirby was added to #general",
            kirby.pendingMentionActionDescription("#general", localState = "added", displayName = "WA kirby"),
        )
        assertEquals(
            "Notified @WA kirby once - still not in #general",
            kirby.pendingMentionActionDescription("#general", localState = "notified", displayName = "WA kirby"),
        )
        assertEquals(
            "@WA kirby was added to #general",
            kirby.mentionActionDeliveredNotice("add", "#general", displayName = "WA kirby"),
        )
        assertEquals(
            "Notified @WA kirby once - still not in #general",
            kirby.mentionActionDeliveredNotice("notify", "#general", displayName = "WA kirby"),
        )

        val loungeExcluded =
            action(
                targetType = "agent",
                targetHandle = "Nova",
                reason = PendingMentionReasonLoungeAgentExcluded,
                availableActions = emptyList(),
            )
        assertEquals(
            "@Nova the Agent won't be notified: a message posted here goes only to this Lounge's agent. Mention them in a thread to notify them.",
            loungeExcluded.pendingMentionActionDescription("#lounge", displayName = "Nova the Agent"),
        )
    }

    private fun member(
        id: String,
        kind: MemberKind,
        handle: String,
        displayName: String,
    ) = MemberProfile(
        id = MemberId(id),
        displayName = displayName,
        handle = handle,
        kind = kind,
        serverId = ServerId("server-1"),
    )

    private fun action(
        targetType: String,
        targetHandle: String,
        reason: String = "not_member",
        availableActions: List<String> = listOf("add", "notify"),
    ) = RaftPendingMentionActionItem(
        resolutionId = "res-$targetType-$targetHandle-$reason",
        targetType = targetType,
        targetHandle = targetHandle,
        reason = reason,
        availableActions = availableActions,
    )
}
