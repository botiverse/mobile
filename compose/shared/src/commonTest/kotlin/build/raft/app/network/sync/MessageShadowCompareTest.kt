/*
 * T-app-bind shadow-compare parity gate (sync-core messages).
 *
 * Proves the NEW canonical pipeline (ingress decodeCanonicalMessageWire -> CanonicalMessageRow.toAppMessage)
 * reproduces the LEGACY realtime projection (RealtimeEventMapper) on canonical facts, for the SAME raw
 * socket payload. This is the pre-condition for moving sync-core messages from shadow to sole-apply:
 * shadow mode must show zero canonical-fact divergence against the authoritative path.
 *
 * Domain-outside / derived-display fields the canonical row does not carry (updatedAt-derived labels,
 * attachment.commentCount, task/thread/reply/saved state) are normalized out
 * by toShadowParityView and are asserted NOT to cause divergence.
 */
package build.raft.app.network.sync

import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.Message
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageUpdated
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageShadowCompareTest {

    private val mapper = RealtimeEventMapper()

    /** The message the LEGACY realtime path produces for [payload] under [eventName]. */
    private fun legacyMessage(eventName: String, payload: JsonObject): Message =
        when (val event = mapper.map(eventName, payload)) {
            is MessageNew -> event.message
            is MessageUpdated -> event.message
            else -> error("expected a message event, got $event")
        }

    private fun assertParity(eventName: String, face: ProductionFace, payload: JsonObject) {
        val legacy = legacyMessage(eventName, payload)
        val outcome = compareCanonicalShadow(payload, face, legacy)
        assertEquals(
            MessageShadowOutcome.Parity,
            outcome,
            "canonical pipeline diverged from legacy projection: $outcome",
        )
    }

    @Test
    fun messageNew_fullAggregates_isParity() {
        assertParity(RealtimeSocketEvents.MessageNew, ProductionFace.MessageNew, richMessagePayload())
    }

    @Test
    fun messageUpdated_enriched_isParity() {
        // updatedAt remains update metadata only; the timeline label is createdAt.
        // The canonical row cannot carry updatedAtLabel, so parity still relies on normalization.
        val payload = buildJsonObject {
            richMessageFields(this)
            put("updatedAt", "2026-07-11T21:00:00Z")
        }
        assertParity(RealtimeSocketEvents.MessageUpdated, ProductionFace.EnrichedUpdated, payload)
    }

    @Test
    fun messageNew_minimalRequiredOnly_isParity() {
        val payload = buildJsonObject {
            put("id", "m-min")
            put("seq", 7L)
            put("channelId", "c-1")
            put("senderType", "user")
            put("senderId", "u-1")
            put("content", "hi")
            put("messageType", "chat")
            put("randomId", JsonNull)
            put("threadId", JsonNull)
            put("createdAt", "2026-07-11T20:00:00Z")
        }
        assertParity(RealtimeSocketEvents.MessageNew, ProductionFace.MessageNew, payload)
    }

    @Test
    fun agentSender_isParity() {
        val payload = buildJsonObject {
            richMessageFields(this)
            put("senderType", "agent")
            put("senderName", "HanXin")
        }
        assertParity(RealtimeSocketEvents.MessageNew, ProductionFace.MessageNew, payload)
    }

    @Test
    fun systemMessage_isParity() {
        val payload = buildJsonObject {
            richMessageFields(this)
            put("messageType", "system")
        }
        assertParity(RealtimeSocketEvents.MessageNew, ProductionFace.MessageNew, payload)
    }

    @Test
    fun attachmentSizeBytesRemainRawAndEqualAcrossBothPaths() {
        val payload = buildJsonObject {
            richMessageFields(this)
            putJsonArray("attachments") {
                add(
                    buildJsonObject {
                        put("id", "a-big")
                        put("filename", "clip.mp4")
                        put("mimeType", "video/mp4")
                        put("sizeBytes", 2048L)
                    },
                )
            }
        }
        val legacy = legacyMessage(RealtimeSocketEvents.MessageNew, payload)
        // Raw bytes are now preserved through both paths; formatting belongs to the UI boundary.
        assertTrue(legacy.attachments.single().sizeBytes == 2048L)
        assertTrue(legacy.attachments.single().sizeLabel.isEmpty())
        assertParity(RealtimeSocketEvents.MessageNew, ProductionFace.MessageNew, payload)
    }

    @Test
    fun missingRequiredContent_isDecodeFailed() {
        val payload = buildJsonObject {
            put("id", "m-x")
            put("seq", 1L)
            put("channelId", "c-1")
            put("senderType", "user")
            put("senderId", "u-1")
            put("messageType", "chat")
            put("randomId", JsonNull)
            put("threadId", JsonNull)
            put("createdAt", "2026-07-11T20:00:00Z")
            // content omitted -> canonical-required, fail closed
        }
        val outcome = compareCanonicalShadow(payload, ProductionFace.MessageNew, legacyMessage(RealtimeSocketEvents.MessageNew, payload))
        assertTrue(outcome is MessageShadowOutcome.DecodeFailed, "expected DecodeFailed, got $outcome")
        assertTrue("content" in (outcome as MessageShadowOutcome.DecodeFailed).missingOrInvalidRequiredFields)
    }

    // ── Adversarial / degenerate-input parity battery ────────────────────────────────────────────────
    // A happy-path-only parity suite is a FALSE GREEN: the divergences that matter most are on the
    // degenerate paths a producer violation can reach (AD2's #698 review). Each case feeds a malformed
    // value that passes ingress strict-TYPE checks but not nonblank/non-negative, and asserts the projection
    // reproduces the legacy mapper's fail-soft rule exactly (parity-first — a divergence must be a
    // registered known-delta, never silently introduced).

    @Test
    fun rawSystemSenderIsRejectedByFrontendRealtimeProjection() {
        val payload = buildJsonObject {
            richMessageFields(this)
            put("senderType", "system")
            put("messageType", "chat")
        }

        assertEquals(null, mapper.map(RealtimeSocketEvents.MessageNew, payload))
    }

    @Test
    fun unknownSenderKind_isProjectionRejectedInsteadOfHumanDefault() {
        val validPayload = richMessagePayload()
        val authoritative = legacyMessage(RealtimeSocketEvents.MessageNew, validPayload)
        val invalidPayload =
            buildJsonObject {
                richMessageFields(this)
                put("senderType", "future-member")
            }

        val outcome =
            compareCanonicalShadow(
                invalidPayload,
                ProductionFace.MessageNew,
                authoritative,
            )

        assertEquals(MessageShadowOutcome.ProjectionRejected(ProductionFace.MessageNew), outcome)
    }

    @Test
    fun adversarial_emptySenderDescription_preservedNotCollapsed() {
        val payload = buildJsonObject {
            richMessageFields(this)
            put("senderDescription", "")
        }
        assertParity(RealtimeSocketEvents.MessageNew, ProductionFace.MessageNew, payload)
    }

    @Test
    fun adversarial_emptyAttachmentFilenameAndMime_preservedNotDefaulted() {
        val payload = buildJsonObject {
            richMessageFields(this)
            putJsonArray("attachments") {
                add(
                    buildJsonObject {
                        put("id", "a-0")
                        put("filename", "")
                        put("mimeType", "")
                        put("sizeBytes", 10L)
                    },
                )
            }
        }
        assertParity(RealtimeSocketEvents.MessageNew, ProductionFace.MessageNew, payload)
    }

    @Test
    fun adversarial_blankEmojiAndNegativeCountReactions_droppedLikeLegacy() {
        val payload = buildJsonObject {
            richMessageFields(this)
            putJsonArray("reactions") {
                add(buildJsonObject { put("emoji", ""); put("count", 1) }) // blank emoji -> dropped
                add(buildJsonObject { put("emoji", "👎"); put("count", -3) }) // negative count -> dropped
            }
        }
        assertParity(RealtimeSocketEvents.MessageNew, ProductionFace.MessageNew, payload)
    }

    @Test
    fun adversarial_emptyCommentRefIds_wholeRefDroppedLikeLegacy() {
        val payload = buildJsonObject {
            richMessageFields(this)
            putJsonObject("commentRef") {
                put("attachmentId", "")
                put("filename", "")
            }
        }
        assertParity(RealtimeSocketEvents.MessageNew, ProductionFace.MessageNew, payload)
    }

    @Test
    fun canonicalFactMismatch_isDivergent() {
        val payload = richMessagePayload()
        val legacy = legacyMessage(RealtimeSocketEvents.MessageNew, payload)
        // Corrupt a canonical fact on the authoritative side; the shadow (decoded from raw) must catch it.
        val mangled = legacy.copy(body = "${legacy.body}-TAMPERED")
        val outcome = compareCanonicalShadow(payload, ProductionFace.MessageNew, mangled)
        assertTrue(outcome is MessageShadowOutcome.Divergent, "expected Divergent, got $outcome")
        assertEquals(listOf("body"), (outcome as MessageShadowOutcome.Divergent).fields)
    }

    // ── t0: envelope round-trip parity (codec layer) ─────────────────────────────────────────────────
    // The compared projection is the fact AFTER an envelope codec round-trip, so Parity proves
    // decode → envelope round-trip → project on real traffic. The injectable codec seam proves the new
    // outcomes BITE (a happy-path-only assertion would be a false green — the round-trip could be dead code).

    @Test
    fun envelopeRoundTrip_realCodec_richFact_isParity() {
        // The real envelope codec must round-trip the rich fact losslessly — the added round-trip introduces
        // no divergence of its own (all existing parity tests also transitively exercise this).
        assertParity(RealtimeSocketEvents.MessageNew, ProductionFace.MessageNew, richMessagePayload())
    }

    @Test
    fun envelopeRoundTrip_lossyDecodeDroppingReactions_isEnvelopeRoundTripDivergent() {
        val payload = richMessagePayload() // has a 👍 reaction
        val legacy = legacyMessage(RealtimeSocketEvents.MessageNew, payload)
        // Inject a lossy codec that silently drops reactions on restore — the round-trip must catch the drift
        // and name the field, isolated from decode/projection parity.
        val outcome = compareCanonicalShadow(
            payload,
            ProductionFace.MessageNew,
            legacy,
            decodeFact = { json -> decodeCanonicalFact(json)?.copy(reactions = emptyList()) },
        )
        assertTrue(outcome is MessageShadowOutcome.EnvelopeRoundTripDivergent, "expected EnvelopeRoundTripDivergent, got $outcome")
        assertEquals(listOf("reactions"), (outcome as MessageShadowOutcome.EnvelopeRoundTripDivergent).fields)
    }

    @Test
    fun envelopeRoundTrip_decodeFailsClosed_isEnvelopeDecodeFailed() {
        val payload = richMessagePayload()
        val legacy = legacyMessage(RealtimeSocketEvents.MessageNew, payload)
        // A persisted BLOB that will not restore (decode returns null) is fail-closed, not a silent pass.
        val outcome = compareCanonicalShadow(
            payload,
            ProductionFace.MessageNew,
            legacy,
            decodeFact = { null },
        )
        assertTrue(outcome is MessageShadowOutcome.EnvelopeDecodeFailed, "expected EnvelopeDecodeFailed, got $outcome")
        assertEquals(ProductionFace.MessageNew, (outcome as MessageShadowOutcome.EnvelopeDecodeFailed).face)
    }

    private fun richMessagePayload(): JsonObject = buildJsonObject { richMessageFields(this) }

    /** A fully-populated, well-formed canonical message payload (all aggregates present, non-degenerate). */
    private fun richMessageFields(builder: kotlinx.serialization.json.JsonObjectBuilder) = with(builder) {
        put("id", "m-rich")
        put("seq", 42L)
        put("channelId", "c-1")
        put("senderType", "user")
        put("senderId", "u-1")
        put("content", "hello world")
        put("messageType", "chat")
        put("randomId", "r-1")
        put("threadId", JsonNull)
        put("createdAt", "2026-07-11T20:00:00Z")
        put("senderName", "Alice")
        put("senderDescription", "eng")
        put("senderMembershipStatus", "active")
        put("roleDescription", "owner") // unknown non-canonical field is ignored by both paths
        putJsonArray("attachments") {
            add(
                buildJsonObject {
                    put("id", "a-1")
                    put("filename", "note.txt")
                    put("mimeType", "text/plain")
                    put("sizeBytes", 12L)
                    put("thumbnailUrl", "https://cdn/x.png")
                    put("width", 4)
                    put("height", 5)
                },
            )
        }
        putJsonArray("reactions") {
            add(
                buildJsonObject {
                    put("emoji", "👍")
                    put("count", 2)
                    putJsonArray("reactorIds") { add("u-2"); add("u-3") }
                    putJsonArray("reactorNames") { add("Bob"); add("Cara") }
                },
            )
        }
        putJsonObject("commentRef") {
            put("attachmentId", "a-1")
            put("filename", "note.txt")
            put("hostMessageId", "m-host")
            put("anchorLabel", "line 3")
            putJsonObject("hostSource") {
                put("type", "channel")
                put("routeKind", "channel")
                put("channelId", "c-host")
            }
        }
        putJsonObject("conversationContext") {
            put("channelType", "channel")
            put("parentChannelId", "c-parent")
        }
    }
}
