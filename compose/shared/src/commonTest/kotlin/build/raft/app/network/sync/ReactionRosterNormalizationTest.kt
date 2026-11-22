package build.raft.app.network.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ReactionRosterNormalizationTest {
    private val scope = ReactionParentScopeKey(serverId = "s1", scopeId = "c1", scopeKind = "channel")

    private fun context(
        authority: ReactionIngressAuthority = ReactionIngressAuthority.ReceiverPrivate("u2"),
        messageId: String = "m1",
        parentScopeKey: ReactionParentScopeKey = scope,
    ) = ReactionNormalizationContext(
        serverId = "s1",
        messageId = messageId,
        parentScopeKey = parentScopeKey,
        authority = authority,
    )

    private fun normalized(
        reactions: List<LegacyReactionRosterDto>,
        authority: ReactionIngressAuthority = ReactionIngressAuthority.ReceiverPrivate("u2"),
    ): NormalizedReactionIngress = assertIs<ReactionNormalizationOutcome.Normalized>(
        normalizeLegacyReactionRoster(context(authority), reactions),
    ).value

    @Test
    fun sharedRoomInputCannotAuthorViewerOverlay() {
        val result = normalized(
            reactions = listOf(
                LegacyReactionRosterDto("like", 1, listOf("u2"), listOf("B")),
            ),
            authority = ReactionIngressAuthority.SharedRoom,
        )

        assertNull(result.viewerOverlay)
        assertEquals(1, result.sharedFact.single().count)
        assertEquals(1, result.readCacheSeed.single().actors.size)
    }

    @Test
    fun receiverPrivateEmptySnapshotClearsPreviouslySelectedEmoji() {
        val result = normalized(emptyList())

        assertEquals(emptyList(), result.sharedFact)
        assertEquals(emptyList(), result.viewerOverlay?.reactedEmojis)
        assertEquals(emptyList(), result.readCacheSeed)
    }

    @Test
    fun incompleteRosterKeepsCountFactButSuppressesAllRosterDerivedState() {
        val result = normalized(
            listOf(
                LegacyReactionRosterDto(
                    emoji = "like",
                    count = 3,
                    reactorIds = listOf("u2", "u1", "u3"),
                    reactorNames = listOf("B", "A"),
                ),
            ),
        )

        assertEquals(
            emptyList(),
            result.sharedFact.single().previewK,
        )
        assertNull(result.viewerOverlay)
        assertEquals(emptyList(), result.readCacheSeed)
        assertEquals(
            listOf(
                ReactionNormalizationDiagnostic(ReactionRosterDiagnosticKind.ParallelRosterLengthMismatch, "like"),
                ReactionNormalizationDiagnostic(ReactionRosterDiagnosticKind.EmptyActorDisplayName, "like"),
                ReactionNormalizationDiagnostic(ReactionRosterDiagnosticKind.ActorCountMismatch, "like"),
            ),
            result.violations,
        )
    }

    @Test
    fun malformedSemanticInputSkipsOnlyInvalidFactsAndReportsNamedDiagnostics() {
        val normalized = assertIs<ReactionNormalizationOutcome.Normalized>(
            normalizeLegacyReactionRoster(
                context(),
                listOf(
                    LegacyReactionRosterDto("", 0),
                    LegacyReactionRosterDto(" spaced ", 0),
                    LegacyReactionRosterDto("negative", -1),
                    LegacyReactionRosterDto("duplicate", 0),
                    LegacyReactionRosterDto("duplicate", 0),
                    LegacyReactionRosterDto("valid", 0),
                ),
            ),
        ).value
        assertEquals(
            listOf(ReactionFact("valid", 0, emptyList())),
            normalized.sharedFact,
        )
        assertEquals(
            listOf(
                ReactionNormalizationDiagnostic(ReactionRosterDiagnosticKind.EmptyEmoji, ""),
                ReactionNormalizationDiagnostic(ReactionRosterDiagnosticKind.NonCanonicalEmoji, " spaced "),
                ReactionNormalizationDiagnostic(ReactionRosterDiagnosticKind.InvalidCount, "negative"),
                ReactionNormalizationDiagnostic(ReactionRosterDiagnosticKind.DuplicateEmoji, "duplicate"),
            ),
            normalized.violations,
        )
        assertNull(normalized.viewerOverlay)
        assertEquals(listOf("valid"), normalized.readCacheSeed.map { it.discussion.emoji })

        assertEquals(
            ReactionNormalizationFailure.BlankViewerId,
            assertIs<ReactionNormalizationOutcome.Rejected>(
                normalizeLegacyReactionRoster(
                    context(ReactionIngressAuthority.ReceiverPrivate("")),
                    emptyList(),
                ),
            ).failure,
        )
    }
}
