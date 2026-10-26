package build.raft.app.data

import build.raft.app.canonical.message.MessageAggregateField
import build.raft.app.network.sync.CanonicalActionCardResultV2
import build.raft.app.network.sync.CanonicalActionMetadataV2
import build.raft.app.network.sync.CanonicalMessageEnvelopeV2
import build.raft.app.network.sync.CanonicalMessageFoldInput
import build.raft.app.network.sync.CanonicalMessageFoldKind
import build.raft.app.network.sync.CanonicalMessageV2DecodeResult
import build.raft.app.network.sync.CanonicalMessageV2EnvelopeConversion
import build.raft.app.network.sync.ProductionFace
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.decodeCanonicalMessageV2
import build.raft.app.network.sync.decodeCanonicalMessageWire
import build.raft.app.network.sync.encodeCanonicalMessageV2
import build.raft.app.network.sync.toCanonicalMessageV2Conversion
import build.raft.app.network.sync.toThreadMessageItem
import build.raft.app.actionCardSpecToRender
import build.raft.app.showsFeedbackTicket
import build.raft.app.threadActionCard
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task #43: a done action card keeps its result on the canonical V2 path.
 *
 * Before it, the V2 producer refused the whole card when its root, action or result had a key outside one
 * flat list (result: kind, id, name, channelId, channelName, addedHumanIds, addedAgentIds). A done feedback
 * card (`feedback:submit`: action feedbackType / message, result ticketId / reference / source) and a done
 * app card (result clientId, ...) were refused, so the REST page holding them was refused whole
 * (`canonical_v2_snapshot_not_full_v6`) and a realtime frame for them marked the window discontinuous: the
 * offline / cold-open copy of the channel stopped before the card, and the card was not there.
 *
 * Shapes: botiverse/slock staging 7cd67301 — `packages/shared/src/actionCards.ts` (`ActionCardMetadata`,
 * `ActionCardResult`, the action schemas), `actionCardsService.ts` result builders, and
 * `routes/productFeedback.ts` (`markFeedbackCardSubmitted`).
 */
class CanonicalMessageV2ActionCardResultTest {
    @Test
    fun aPageWithDoneFeedbackAppAndChannelCardsIsWrittenWithTheirResults() = runTest {
        val page = Json.parseToJsonElement(
            "[" + listOf(
                node("m1", 1, null),
                node("m2", 2, doneFeedbackCard),
                node("m3", 3, doneAppRegistrationCard),
                node("m4", 4, doneChannelCreateCard),
                node("m5", 5, doneOwnerRecoveryCard),
                node("m6", 6, doneAgentLoginCard),
            ).joinToString(",") + "]",
        ) as kotlinx.serialization.json.JsonArray
        val conversion = CanonicalMessageV2RestSnapshotFetcher(RecordingNetworkClient(canonicalV2TestPage())).convertPage(
            partition = partition,
            scopeId = "channel-1",
            channelType = "channel",
            rawMessages = page,
        )
        // The disappearance: this page used to be Refused("canonical_v2_snapshot_not_full_v6").
        val envelopes = assertIs<CanonicalMessageV2PageConversion.Converted>(conversion, conversion.toString()).envelopes
        assertEquals(listOf("m1", "m2", "m3", "m4", "m5", "m6"), envelopes.map { it.fact.id })

        val feedback = envelopes.card("m2")
        assertEquals("executed", feedback.state)
        assertEquals("user-1", feedback.executedByUserId)
        assertEquals(
            CanonicalActionCardResultV2(kind = "product-feedback", ticketId = TICKET, reference = "FB-9", source = "android"),
            feedback.result,
        )
        assertEquals("feedback:submit", feedback.action.type)
        assertNotNull(feedback.presentation, "the server presentation renders the card")

        assertEquals(
            CanonicalActionCardResultV2(
                kind = "integration-app-registration",
                mode = "register",
                clientId = CLIENT_ID,
                clientKey = "acme-crm",
                clientName = "Acme CRM",
                returnUrl = "https://acme.example/callback",
                homepageUrl = null,
                agentManifestUrl = null,
                setupUrl = "https://acme.example/setup",
                setupDescription = "Connect your Acme workspace",
                scopes = listOf("messages:read", "channels:read"),
                unsafeDemoUrlOverride = false,
            ),
            envelopes.card("m3").result,
        )
        assertEquals(CanonicalActionCardResultV2(kind = "channel", id = CHANNEL_ID, name = "launch"), envelopes.card("m4").result)
        assertEquals(
            CanonicalActionCardResultV2(
                kind = "integration-app-owner-recovery",
                clientId = CLIENT_ID,
                clientKey = "acme-crm",
                clientName = "Acme CRM",
                ownerAgentId = AGENT_ID,
                ownerAgentName = "Friday",
            ),
            envelopes.card("m5").result,
        )
        assertEquals(
            CanonicalActionCardResultV2(
                kind = "agent-integration-login",
                requestId = REQUEST_ID,
                agentId = AGENT_ID,
                agentName = "Friday",
                clientId = CLIENT_ID,
                clientKey = "acme-crm",
                clientName = "Acme CRM",
                scopes = listOf("messages:read"),
                grantId = null,
            ),
            envelopes.card("m6").result,
        )

        // Every fact is valid on disk and round-trips byte for byte.
        envelopes.forEach { envelope ->
            val bytes = encodeCanonicalMessageV2(envelope, partition.serverId)
            val decoded = assertIs<CanonicalMessageV2DecodeResult.Accepted>(decodeCanonicalMessageV2(bytes, partition.serverId), bytes)
            assertEquals(envelope, decoded.envelope)
            assertEquals(bytes, encodeCanonicalMessageV2(decoded.envelope, partition.serverId))
        }
    }

    @Test
    fun theStoredCardsRenderTheirResultLikeRest() = runTest {
        val feedback = convert(doneFeedbackCard)
        val feedbackItem = assertNotNull(feedback.toThreadMessageItem(currentUserId = "user-1"))
        assertEquals("action-card", feedbackItem.metadata["actionCard.kind"])
        assertEquals("executed", feedbackItem.metadata["actionCard.state"])
        assertEquals("product-feedback", feedbackItem.metadata["actionCard.resultKind"])
        assertEquals("Ada", feedbackItem.metadata["actionCard.executedByUserName"])

        val app = assertNotNull(convert(doneAppRegistrationCard).toThreadMessageItem(currentUserId = null))
        assertEquals("integration-app-registration", app.metadata["actionCard.resultKind"])
        assertEquals(CLIENT_ID, app.metadata["actionCard.resultId"], "REST reads the result's clientId as its id")
        assertEquals("Acme CRM", app.metadata["actionCard.resultName"])

        val owner = assertNotNull(convert(doneOwnerRecoveryCard).toThreadMessageItem(currentUserId = null))
        assertEquals(CLIENT_ID, owner.metadata["actionCard.resultId"])
        assertEquals("Acme CRM", owner.metadata["actionCard.resultName"])

        val channel = assertNotNull(convert(doneChannelCreateCard).toThreadMessageItem(currentUserId = null))
        assertEquals("channel", channel.metadata["actionCard.resultKind"])
        assertEquals(CHANNEL_ID, channel.metadata["actionCard.resultId"])
        assertEquals("launch", channel.metadata["actionCard.resultName"])
    }

    @Test
    fun aDoneFeedbackCardReadFromV2ShowsViewInSettingsToItsSenderOnTheSameApp() = runTest {
        // S0.3: the cold-open / offline frame, before any REST page; the card as the server builds it
        // (vector result_open_feedback_ticket's result tree: the summary and "View in Settings").
        val stored = assertIs<CanonicalMessageV2DecodeResult.Accepted>(
            decodeCanonicalMessageV2(encodeCanonicalMessageV2(convert(doneFeedbackCardWithSpec), partition.serverId), partition.serverId),
        ).envelope
        val card = assertNotNull(assertNotNull(stored.toThreadMessageItem(currentUserId = "user-1")).threadActionCard())
        assertEquals(Triple("user-1", TICKET, "android"), Triple(card.executedByUserId, card.resultTicketId, card.resultSource))

        assertTrue(card.showsFeedbackTicket(viewerUserId = "user-1", clientOs = "android", feedbackPageAvailable = true))
        val shown = assertNotNull(
            card.actionCardSpecToRender(specEnabled = true, capabilities = RAFT_APP_ACTION_CARD_CAPABILITIES, feedbackTicketVisible = true),
        )
        assertEquals(
            ActionCardSpecAction.Open(ActionCardSpecIntent.FeedbackTicket),
            (shown.result?.element("openTicket") as ActionCardSpecButton).action,
        )
        // Another viewer, or the iOS app (the ticket was filed into Android's): the summary alone.
        assertFalse(card.showsFeedbackTicket(viewerUserId = "user-2", clientOs = "android", feedbackPageAvailable = true))
        assertFalse(card.showsFeedbackTicket(viewerUserId = "user-1", clientOs = "ios", feedbackPageAvailable = true))
        val hidden = assertNotNull(card.actionCardSpecToRender(true, RAFT_APP_ACTION_CARD_CAPABILITIES, feedbackTicketVisible = false))
        assertNull(hidden.result?.element("openTicket"))
    }

    @Test
    fun aRealtimeDoneFrameConvertsInsteadOfBreakingTheWindow() = runTest {
        // message:updated when the card turns done (the canary's update path, allowPartial = true).
        val conversion = foldInput(doneFeedbackCard, ProductionFace.EnrichedUpdated, CanonicalMessageFoldKind.Updated)
            .toCanonicalMessageV2Conversion(partition, replyCount = 0, allowPartial = true)
        assertIs<CanonicalMessageV2EnvelopeConversion.Converted>(conversion, conversion.toString())
    }

    @Test
    fun aKeyItsKindDoesNotTakeIsDroppedNotTheCard() = runTest {
        // A channel result carrying feedback / app keys, a feedback result carrying channel / app keys, and a
        // key no kind has: each keeps only its own kind's keys.
        val channel = convert(
            doneChannelCreateCard.withResult(
                """{"kind":"channel","id":"$CHANNEL_ID","name":"launch","ticketId":"t","clientId":"c","somethingNew":1}""",
            ),
        )
        assertEquals(CanonicalActionCardResultV2(kind = "channel", id = CHANNEL_ID, name = "launch"), channel.card().result)

        val feedback = convert(
            doneFeedbackCard.withResult(
                """{"kind":"product-feedback","ticketId":"$TICKET","reference":null,"source":"web",""" +
                    """"id":"x","channelId":"y","addedHumanIds":["u"],"clientId":"c","somethingNew":{"a":1}}""",
            ),
        )
        assertEquals(CanonicalActionCardResultV2(kind = "product-feedback", ticketId = TICKET, source = "web"), feedback.card().result)
        val bytes = encodeCanonicalMessageV2(feedback, partition.serverId)
        assertFalse("\"reference\"" in bytes, "wire null on an optional key is absent, never null")
        assertFalse("somethingNew" in bytes)

        // A wrong type drops only that key; a kind the App does not know keeps only its kind.
        val typed = convert(
            doneAppRegistrationCard.withResult(
                """{"kind":"integration-app-registration","clientId":7,"clientName":"Acme CRM","scopes":"all","unsafeDemoUrlOverride":"yes"}""",
            ),
        )
        assertEquals(CanonicalActionCardResultV2(kind = "integration-app-registration", clientName = "Acme CRM"), typed.card().result)
        val future = convert(doneChannelCreateCard.withResult("""{"kind":"future-kind","id":"x","name":"y"}"""))
        assertEquals(CanonicalActionCardResultV2(kind = "future-kind"), future.card().result)

        // A result without a kind is dropped; the done card stays.
        val kindless = convert(doneFeedbackCard.withResult("""{"ticketId":"$TICKET"}"""))
        assertEquals("executed", kindless.card().state)
        assertNull(kindless.card().result)
    }

    @Test
    fun theDiskStaysExactAndAPlainResultKeepsItsSchema6Bytes() = runTest {
        val channel = convert(doneChannelCreateCard)
        val bytes = encodeCanonicalMessageV2(channel, partition.serverId)
        assertTrue(
            """"result":{"kind":"channel","id":"$CHANNEL_ID","name":"launch","channelId":null,"channelName":null,"addedHumanIds":[],"addedAgentIds":[]}""" in bytes,
            bytes,
        )
        val feedbackBytes = encodeCanonicalMessageV2(convert(doneFeedbackCard), partition.serverId)
        // Reverse: stored bytes a producer could never write are still refused on read.
        listOf(
            bytes.replace("\"name\":\"launch\",", "\"name\":\"launch\",\"ticketId\":\"t\","),
            feedbackBytes.replace("\"source\":\"android\"", "\"source\":null"),
            feedbackBytes.replace("\"source\":\"android\"", "\"source\":\"android\",\"somethingNew\":1"),
            feedbackBytes.replace("\"source\":\"android\"", "\"source\":\"android\",\"clientId\":\"c\""),
            feedbackBytes.replace("\"executedByUserId\":\"user-1\"", "\"executedByUserId\":null"),
            feedbackBytes.replace(",\"addedAgentIds\":[]", ""),
        ).forEach { tampered ->
            assertIs<CanonicalMessageV2DecodeResult.Rejected>(decodeCanonicalMessageV2(tampered, partition.serverId), tampered)
        }
    }

    private fun convert(actionMetadata: String): CanonicalMessageEnvelopeV2 {
        val conversion = foldInput(actionMetadata, ProductionFace.EnrichedUpdated, CanonicalMessageFoldKind.Updated)
            .toCanonicalMessageV2Conversion(partition, replyCount = 0)
        return assertIs<CanonicalMessageV2EnvelopeConversion.Converted>(conversion, conversion.toString()).envelope
    }

    private fun foldInput(actionMetadata: String, face: ProductionFace, kind: CanonicalMessageFoldKind): CanonicalMessageFoldInput {
        val wire = Json.parseToJsonElement(node("m1", 1, actionMetadata, context = true)).jsonObject
        val carry = assertNotNull(decodeCanonicalMessageWire(wire, face))
        return CanonicalMessageFoldInput(kind, carry.row, MessageAggregateField.entries.toList())
    }

    private fun List<CanonicalMessageEnvelopeV2>.card(id: String) = single { it.fact.id == id }.card()

    private fun CanonicalMessageEnvelopeV2.card() = assertIs<CanonicalActionMetadataV2.ActionCard>(fact.actionMetadata)

    private fun String.withResult(result: String): String {
        val card = Json.parseToJsonElement(this).jsonObject
        return JsonObject(card + ("result" to Json.parseToJsonElement(result))).toString()
    }

    /** One messages-page node in the server shape; [context] adds the realtime `conversationContext`. */
    private fun node(id: String, seq: Long, actionMetadata: String?, context: Boolean = false): String =
        """{"id":"$id","seq":$seq,"channelId":"channel-1","senderType":"agent","senderId":"$AGENT_ID",""" +
            """"content":"card $id","messageType":"chat","randomId":null,"threadId":null,""" +
            """"createdAt":"2026-10-26T08:00:0$seq.000Z","senderName":"Friday","senderDescription":null,""" +
            """"senderMembershipStatus":null,"mentions":[],"attachments":[],"reactions":[],"commentRef":null,""" +
            """"actionMetadata":${actionMetadata ?: "null"}""" +
            (if (context) ""","conversationContext":{"channelType":"channel"}""" else "") + "}"

    private val partition = canonicalV2TestGrant().durablePartition(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)

    private companion object {
        const val TICKET = "6f1c2a52-8a1e-4c55-9f0e-2b7a43c1d001"
        const val CLIENT_ID = "0b6f4f0e-1d2c-4b3a-8e9f-7a6b5c4d3e21"
        const val AGENT_ID = "9d8c7b6a-5f4e-4d3c-8b2a-1f0e9d8c7b6a"
        const val CHANNEL_ID = "3c2b1a09-8f7e-4d6c-9b5a-4e3d2c1b0a99"
        const val REQUEST_ID = "7e6d5c4b-3a29-4f18-8e07-d6c5b4a39281"

        /** The executed-card root every server path writes (`commitActionCardResult`). */
        fun done(action: String, result: String) =
            """{"kind":"action-card","sourceServerId":"server-a","targetServerName":"Raft HQ","action":$action,""" +
                """"presentation":{"title":"Done","confirmLabel":"Run","genericApprovalAllowed":false},""" +
                """"state":"executed","executedAt":"2026-10-26T08:01:00.000Z","executedByUserId":"user-1",""" +
                """"executedByUserName":"Ada","result":$result,"confirmationVersion":2}"""

        /** `feedback:submit`, completed by `markFeedbackCardSubmitted` from the Hands receipt. */
        val doneFeedbackCard = done(
            """{"type":"feedback:submit","feedbackType":"problem","message":"Search misses old threads","draftHint":"Edit before sending"}""",
            """{"kind":"product-feedback","ticketId":"$TICKET","reference":"FB-9","source":"android"}""",
        )

        /**
         * [doneFeedbackCard] with its `presentation.card` (S0.3 builder output, as the v4 vector's
         * `result_open_feedback_ticket`): no ticket id in the card; the result says it.
         */
        val doneFeedbackCardWithSpec = doneFeedbackCard.replace(
            """"genericApprovalAllowed":false}""",
            """"genericApprovalAllowed":false,"card":{"catalog":"action-card.v1","title":{"key":"actionCard.test.title","text":{"en":"Test card"}},""" +
                """"spec":{"root":"root","elements":{"root":{"type":"Stack","props":{},"children":["t"]},"t":{"type":"Text","props":{"text":"x"}}}},""" +
                """"result":{"root":"root","elements":{"root":{"type":"Stack","props":{},"children":["summary","openTicket"]},""" +
                """"summary":{"type":"Text","props":{"text":{"key":"actionCard.test.sent","text":{"en":"Sent to the Raft team."}}}},""" +
                """"openTicket":{"type":"Button","props":{"label":{"key":"actionCard.test.viewTicket","text":{"en":"View in Settings"}},""" +
                """"action":{"kind":"open","intent":"feedback_ticket"}}}}}}}""",
        )

        /** `integration:register_app` (`actionCardsService` register branch). */
        val doneAppRegistrationCard = done(
            """{"type":"integration:register_app","name":"Acme CRM","clientKey":"acme-crm","returnUrl":"https://acme.example/callback",""" +
                """"setupUrl":"https://acme.example/setup","setupDescription":"Connect your Acme workspace","scopes":["messages:read","channels:read"]}""",
            """{"kind":"integration-app-registration","mode":"register","clientId":"$CLIENT_ID","clientKey":"acme-crm",""" +
                """"clientName":"Acme CRM","returnUrl":"https://acme.example/callback","homepageUrl":null,"agentManifestUrl":null,""" +
                """"setupUrl":"https://acme.example/setup","setupDescription":"Connect your Acme workspace",""" +
                """"scopes":["messages:read","channels:read"],"unsafeDemoUrlOverride":false}""",
        )

        /** `channel:create` (`{ kind: "channel", id, name }`). */
        val doneChannelCreateCard = done(
            """{"type":"channel:create","name":"launch","visibility":"public","humans":[],"agents":[]}""",
            """{"kind":"channel","id":"$CHANNEL_ID","name":"launch"}""",
        )

        /** `integration:recover_app_owner` (action `targetAgent`; result `ownerAgentId`). */
        val doneOwnerRecoveryCard = done(
            """{"type":"integration:recover_app_owner","clientKey":"acme-crm","targetAgent":"@friday"}""",
            """{"kind":"integration-app-owner-recovery","clientId":"$CLIENT_ID","clientKey":"acme-crm","clientName":"Acme CRM",""" +
                """"ownerAgentId":"$AGENT_ID","ownerAgentName":"Friday"}""",
        )

        /** `integration:approve_agent_login`. */
        val doneAgentLoginCard = done(
            """{"type":"integration:approve_agent_login","requestId":"$REQUEST_ID","agentId":"$AGENT_ID","agentName":"Friday",""" +
                """"clientId":"$CLIENT_ID","clientKey":"acme-crm","clientName":"Acme CRM","scopes":["messages:read"]}""",
            """{"kind":"agent-integration-login","requestId":"$REQUEST_ID","agentId":"$AGENT_ID","agentName":"Friday",""" +
                """"clientId":"$CLIENT_ID","clientKey":"acme-crm","clientName":"Acme CRM","scopes":["messages:read"],"grantId":null}""",
        )
    }
}
