package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalReactionWire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun splitsLegacyRosterIntoDeterministicFactOverlayAndCompleteCacheSeeds() {
        val result = normalized(
            listOf(
                LegacyReactionRosterDto(
                    emoji = "like",
                    count = 4,
                    reactorIds = listOf("u4", "u2", "u1", "u3"),
                    reactorNames = listOf("D", "B", "A", "C"),
                ),
                LegacyReactionRosterDto(
                    emoji = "fire",
                    count = 1,
                    reactorIds = listOf("u9"),
                    reactorNames = listOf("I"),
                ),
            ),
        )

        assertEquals(listOf("fire", "like"), result.sharedFact.map(ReactionFact::emoji))
        assertEquals(emptyList(), result.sharedFact.single { it.emoji == "like" }.previewK)
        assertEquals("empty-without-room-common-provenance", LEGACY_REACTION_PREVIEW_POLICY)
        assertEquals(listOf("like"), result.viewerOverlay?.reactedEmojis)
        assertEquals(listOf("fire", "like"), result.readCacheSeed.map { it.discussion.emoji })
        assertEquals(emptyList(), result.violations)
        assertEquals(
            listOf("u1", "u2", "u3", "u4"),
            result.readCacheSeed.single { it.discussion.emoji == "like" }.actors.map(ReactionActorRef::id),
        )
    }

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
    fun duplicateActorIdKeepsSharedCountButProducesDiagnosticAndUnknownDerivatives() {
        val result = normalized(
            listOf(
                LegacyReactionRosterDto(
                    emoji = "like",
                    count = 2,
                    reactorIds = listOf("u2", "u2"),
                    reactorNames = listOf("B", "B"),
                ),
            ),
        )

        assertEquals(ReactionFact("like", 2, emptyList()), result.sharedFact.single())
        assertNull(result.viewerOverlay)
        assertEquals(emptyList(), result.readCacheSeed)
        assertEquals(
            listOf(
                ReactionNormalizationDiagnostic(ReactionRosterDiagnosticKind.DuplicateActorId, "like"),
                ReactionNormalizationDiagnostic(ReactionRosterDiagnosticKind.ActorCountMismatch, "like"),
            ),
            result.violations,
        )
    }

    @Test
    fun strictTwoActorRosterSeedsBothActorsWithoutDiagnostics() {
        val result = normalized(
            listOf(
                LegacyReactionRosterDto(
                    emoji = "like",
                    count = 2,
                    reactorIds = listOf("u2", "u1"),
                    reactorNames = listOf("B", "A"),
                ),
            ),
        )

        assertEquals(listOf("like"), result.viewerOverlay?.reactedEmojis)
        assertEquals(listOf("u1", "u2"), result.readCacheSeed.single().actors.map(ReactionActorRef::id))
        assertEquals(emptyList(), result.violations)
    }

    @Test
    fun whitespaceRepairIsDiagnosedAndCannotAuthorRosterDerivatives() {
        val result = normalized(
            listOf(
                LegacyReactionRosterDto(
                    emoji = "like",
                    count = 2,
                    reactorIds = listOf(" u2 ", "u1"),
                    reactorNames = listOf(" B ", "A"),
                ),
            ),
        )

        assertEquals(ReactionFact("like", 2, emptyList()), result.sharedFact.single())
        assertNull(result.viewerOverlay)
        assertEquals(emptyList(), result.readCacheSeed)
        assertEquals(
            listOf(
                ReactionNormalizationDiagnostic(ReactionRosterDiagnosticKind.NonCanonicalActorId, "like"),
                ReactionNormalizationDiagnostic(ReactionRosterDiagnosticKind.NonCanonicalActorDisplayName, "like"),
            ),
            result.violations,
        )
    }

    @Test
    fun canonicalBytesArePermutationInvariantAndContainNoRosterKeys() {
        val first = normalized(
            listOf(
                LegacyReactionRosterDto("like", 4, listOf("u4", "u2", "u1", "u3"), listOf("D", "B", "A", "C")),
                LegacyReactionRosterDto("fire", 1, listOf("u9"), listOf("I")),
            ),
        )
        val permuted = normalized(
            listOf(
                LegacyReactionRosterDto("fire", 1, listOf("u9"), listOf("I")),
                LegacyReactionRosterDto("like", 4, listOf("u3", "u1", "u4", "u2"), listOf("C", "A", "D", "B")),
            ),
        )

        val expected =
            "[{\"emoji\":\"fire\",\"count\":1,\"previewK\":[]}," +
                "{\"emoji\":\"like\",\"count\":4,\"previewK\":[]}]\n"
        val firstBytes = encodeReactionFactsCanonical(first.sharedFact)
        val permutedBytes = encodeReactionFactsCanonical(permuted.sharedFact)
        assertEquals(expected, firstBytes)
        assertEquals(firstBytes, permutedBytes)
        assertFalse("reactorIds" in firstBytes)
        assertFalse("reactorNames" in firstBytes)
    }

    @Test
    fun canonicalPayloadDoesNotGrowWithFullActorRoster() {
        val threeActors = (0 until 3).map { index -> "u${index.toString().padStart(4, '0')}" }
        val fiveThousandActors = (0 until 5_000).map { index -> "u${index.toString().padStart(4, '0')}" }
        val small = normalized(
            listOf(LegacyReactionRosterDto("like", 3, threeActors, threeActors.map { "name-$it" })),
        )
        val large = normalized(
            listOf(LegacyReactionRosterDto("like", 5_000, fiveThousandActors, fiveThousandActors.map { "name-$it" })),
        )

        val smallBytes = encodeReactionFactsCanonical(small.sharedFact)
        val largeBytes = encodeReactionFactsCanonical(large.sharedFact)
        assertEquals(emptyList(), small.sharedFact.single().previewK)
        assertEquals(emptyList(), large.sharedFact.single().previewK)
        assertTrue(largeBytes.length - smallBytes.length < 8, "payload grew with roster: ${smallBytes.length} -> ${largeBytes.length}")
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

    @Test
    fun canonicalWireAdapterPreservesLegacyIngressShapeOnly() {
        val dto = CanonicalReactionWire(
            emoji = "like",
            count = 2,
            reactorIds = listOf("u2", "u1"),
            reactorNames = listOf("B", "A"),
        ).toLegacyReactionRosterDto()

        assertEquals(LegacyReactionRosterDto("like", 2, listOf("u2", "u1"), listOf("B", "A")), dto)
    }

    @Test
    fun typedCacheDistinguishesUnknownFromLoadedEmptyAndEvictsByLru() {
        val cache = BoundedReactionActorsReadCache(principalScope = "user:u2", maxEntries = 2)
        val empty = normalized(listOf(LegacyReactionRosterDto("empty", 0))).readCacheSeed.single()
        val like = normalized(listOf(LegacyReactionRosterDto("like", 1, listOf("u1"), listOf("A")))).readCacheSeed.single()
        val fire = normalized(listOf(LegacyReactionRosterDto("fire", 1, listOf("u9"), listOf("I")))).readCacheSeed.single()

        assertIs<ReactionActorsCacheLookup.Unknown>(cache.get(empty.discussion))
        cache.seed(empty)
        cache.seed(like)
        assertEquals(emptyList(), assertIs<ReactionActorsCacheLookup.Loaded>(cache.get(empty.discussion)).actors)
        cache.seed(fire) // `empty` was just touched, so `like` is the LRU entry.

        assertIs<ReactionActorsCacheLookup.Unknown>(cache.get(like.discussion))
        assertIs<ReactionActorsCacheLookup.Loaded>(cache.get(empty.discussion))
        assertIs<ReactionActorsCacheLookup.Loaded>(cache.get(fire.discussion))
        assertEquals(2, cache.size)
    }

    @Test
    fun typedCacheRequiresAnExplicitPrincipalPartition() {
        assertFailsWith<IllegalArgumentException> {
            BoundedReactionActorsReadCache(principalScope = "", maxEntries = 1)
        }
    }

    @Test
    fun typedCacheClearsAllChildEntriesForParentScopeWithoutGlobalScanSemantics() {
        val otherScope = ReactionParentScopeKey(serverId = "s1", scopeId = "c2", scopeKind = "channel")
        val cache = BoundedReactionActorsReadCache(principalScope = "user:u2", maxEntries = 3)
        val first = normalized(listOf(LegacyReactionRosterDto("like", 0))).readCacheSeed.single()
        val second = normalized(listOf(LegacyReactionRosterDto("fire", 0))).readCacheSeed.single()
        val other = assertIs<ReactionNormalizationOutcome.Normalized>(
            normalizeLegacyReactionRoster(
                context(messageId = "m2", parentScopeKey = otherScope),
                listOf(LegacyReactionRosterDto("like", 0)),
            ),
        ).value.readCacheSeed.single()
        cache.seedAll(listOf(first, second, other))

        cache.clearScope(scope)

        assertIs<ReactionActorsCacheLookup.Unknown>(cache.get(first.discussion))
        assertIs<ReactionActorsCacheLookup.Unknown>(cache.get(second.discussion))
        assertIs<ReactionActorsCacheLookup.Loaded>(cache.get(other.discussion))
        assertEquals(1, cache.size)
    }
}
