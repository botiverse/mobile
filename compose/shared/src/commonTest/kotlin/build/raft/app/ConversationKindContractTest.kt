package build.raft.app

import build.raft.app.model.ConversationKind
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * #417 (t3): pins the canonical kind contract to the legacy converters it will
 * replace. When t4-t6 migrate call sites, these assertions are the safety net
 * proving the canonical functions preserve today's semantics; the legacy
 * comparisons get deleted together with the legacy functions.
 */
class ConversationKindContractTest {
    @Test
    fun parsesEveryDirectMessageVocabularyVariant() {
        listOf("dm", "direct", "direct_message", "direct-message", "directmessage", "DM", " Direct_Message ").forEach { raw ->
            assertEquals(ConversationKind.DirectMessage, parseConversationKindOrNull(raw), "raw=$raw")
        }
    }

    @Test
    fun parsesChannelShapedVocabularyIncludingVisibilityValues() {
        // "public"/"private" are visibility, not kind — both land on Channel and
        // the visibility itself stays a separate attribute.
        listOf("channel", "public", "private", "group", "Channel", " PUBLIC ").forEach { raw ->
            assertEquals(ConversationKind.Channel, parseConversationKindOrNull(raw), "raw=$raw")
        }
    }

    @Test
    fun parsesThreadJointSystemInbox() {
        assertEquals(ConversationKind.Thread, parseConversationKindOrNull("thread"))
        assertEquals(ConversationKind.Joint, parseConversationKindOrNull("joint"))
        assertEquals(ConversationKind.System, parseConversationKindOrNull("system"))
        assertEquals(ConversationKind.Inbox, parseConversationKindOrNull("inbox"))
    }

    @Test
    fun unknownInputParsesToNullNeverToChannel() {
        // Rule 3 of the contract: no `?: Channel`-style silent default. #415's
        // DM-thread misroute came from exactly that default.
        listOf(null, "", "   ", "foo", "channel2", "dms").forEach { raw ->
            assertNull(parseConversationKindOrNull(raw), "raw=$raw")
        }
    }

    @Test
    fun kuiklySurfaceKindMatchesLegacyThreadSurfaceKindForEveryKind() {
        ConversationKind.entries.forEach { kind ->
            val legacy =
                ThreadLaunchConversationIdentity(threadId = ThreadId("t"), kind = kind)
                    .threadSurfaceKind()
            assertEquals(legacy, kind.toRaftSurfaceKind(), "kind=$kind")
        }
    }

    @Test
    fun wireChannelKindMatchesLegacyThreadChannelKindWireValueForEveryKind() {
        ConversationKind.entries.forEach { kind ->
            assertEquals(kind.threadChannelKindWireValue(), kind.toWireChannelKindOrNull(), "kind=$kind")
        }
        assertNull((null as ConversationKind?).threadChannelKindWireValue())
    }

    @Test
    fun parseComposedWithSurfaceProjectionMatchesLegacyParentSurfaceVocabulary() {
        // The legacy String.threadParentSurfaceKindOrNull tables, expressed as
        // parse-then-project. "thread" is intentionally absent: it is not a
        // valid parent surface, and under the contract that rejection is the
        // caller's explicit decision instead of a table omission.
        val legacyParentSurfaceTable =
            mapOf(
                "dm" to RaftThreadSurfaceKind.DirectMessage,
                "direct_message" to RaftThreadSurfaceKind.DirectMessage,
                "direct-message" to RaftThreadSurfaceKind.DirectMessage,
                "directmessage" to RaftThreadSurfaceKind.DirectMessage,
                "direct" to RaftThreadSurfaceKind.DirectMessage,
                "channel" to RaftThreadSurfaceKind.Channel,
                "public" to RaftThreadSurfaceKind.Channel,
                "private" to RaftThreadSurfaceKind.Channel,
                "joint" to RaftThreadSurfaceKind.Channel,
                "group" to RaftThreadSurfaceKind.Channel
            )
        legacyParentSurfaceTable.forEach { (raw, expectedSurface) ->
            assertEquals(
                expectedSurface,
                parseConversationKindOrNull(raw)?.toRaftSurfaceKind(),
                "raw=$raw"
            )
        }
    }

    @Test
    fun parsedThreadParentSurfaceKindMatchesLegacyPerFileVocabTables() {
        // t5/#419 finale: the per-file private parent-surface vocab tables collapsed into
        // parse-through-the-contract + the qualified enum projection. Pin exact parity with
        // the deleted tables, including "direct" -> dm (which those tables accepted) and the
        // parent-shape rejections ("thread"/"system"/unknown -> null).
        mapOf(
            "dm" to RaftThreadSurfaceKind.DirectMessage,
            "direct_message" to RaftThreadSurfaceKind.DirectMessage,
            "direct-message" to RaftThreadSurfaceKind.DirectMessage,
            "directmessage" to RaftThreadSurfaceKind.DirectMessage,
            "direct" to RaftThreadSurfaceKind.DirectMessage,
            "channel" to RaftThreadSurfaceKind.Channel,
            "public" to RaftThreadSurfaceKind.Channel,
            "private" to RaftThreadSurfaceKind.Channel,
            "joint" to RaftThreadSurfaceKind.Channel,
            "group" to RaftThreadSurfaceKind.Channel
        ).forEach { (raw, expected) ->
            assertEquals(expected, raw.parsedThreadParentSurfaceKindOrNull(), "raw=$raw")
        }
        listOf(null, "", "thread", "system", "inbox", "bogus").forEach { raw ->
            assertNull(raw.parsedThreadParentSurfaceKindOrNull(), "raw=$raw")
        }
    }

    @Test
    fun wireChannelKindRoundTripsForTheKindsItCovers() {
        listOf(ConversationKind.Channel, ConversationKind.Joint, ConversationKind.DirectMessage).forEach { kind ->
            assertEquals(kind, parseConversationKindOrNull(kind.toWireChannelKindOrNull()), "kind=$kind")
        }
    }

    @Test
    fun kuiklySurfaceRoundTripsOnlyForSurfaceFaithfulKinds() {
        // Surface projection is lossy for Joint/Inbox/System (all "channel");
        // the contract documents that round-trips are only guaranteed via
        // toWireChannelKindOrNull. Pin the faithful subset.
        listOf(ConversationKind.Channel, ConversationKind.DirectMessage, ConversationKind.Thread).forEach { kind ->
            assertEquals(kind, parseConversationKindOrNull(kind.toRaftSurfaceKind()), "kind=$kind")
        }
        assertEquals(ConversationKind.Channel, parseConversationKindOrNull(ConversationKind.Joint.toRaftSurfaceKind()))
    }
}
