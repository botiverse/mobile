package build.raft.app.data

import build.raft.app.thread.ThreadActionCardSpecEffect
import build.raft.app.thread.ThreadActionCardSpecIntent
import build.raft.app.thread.ThreadActionCardSpecStore
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Unit coverage for server-resolved per-person Pro pricing (`person_v2`, botiverse/slock #16345,
 * #16367, #16449, #16453): pricing rules, seat/price math, capacity refusals, `/billing/pricing`
 * resolution with retry and 404 fallback, `seats:add` card parsing/persistence, and
 * [ThreadActionCardSpecStore] seat-request routing across `person_v2`, `seat_v1`, and older servers.
 */
class ProPricingTest {
    private class ScriptedNetworkClient(
        private val responses: ArrayDeque<HomeNetworkResponse>,
    ) : RaftNetworkClient {
        val requests = mutableListOf<Triple<String, String, ServerId?>>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?,
        ): HomeNetworkResponse {
            requests += Triple(method, path, serverIdOverride)
            return responses.removeFirst()
        }
    }

    @Test
    fun pricingRulesAndTenthsArithmeticMatchServerContract() {
        assertEquals(ProPricing.SeatV1, ProPricing.fromWireOrNull("seat_v1"))
        assertEquals(ProPricing.PersonV2, ProPricing.fromWireOrNull("person_v2"))
        assertNull(ProPricing.fromWireOrNull("unknown"))
        assertNull(ProPricing.fromWireOrNull(null))
        assertEquals(ProPricing.SeatV1, billingPricing(null))
        assertEquals(ProPricing.PersonV2, billingPricing(ProPricing.PersonV2))

        assertTrue(ProPricing.SeatV1.agentsTakeSeats)
        assertFalse(ProPricing.PersonV2.agentsTakeSeats)

        // Tenths avoid floating-point drift (e.g. 0.1 * 19).
        assertEquals(39L, seatTenths(humans = 2, agents = 19, pricing = ProPricing.SeatV1))
        assertEquals(20L, seatTenths(humans = 2, agents = 19, pricing = ProPricing.PersonV2))
        assertEquals("3.9", formatSeatTenths(39L))
        assertEquals("2", formatSeatTenths(20L))

        assertEquals(4, calculateProPackQuantity(provisionedHumans = 2, provisionedAgents = 19, pricing = ProPricing.SeatV1))
        assertEquals(2, calculateProPackQuantity(provisionedHumans = 2, provisionedAgents = 19, pricing = ProPricing.PersonV2))
        assertEquals(1, calculateProPackQuantity(provisionedHumans = 0, provisionedAgents = 0, pricing = ProPricing.PersonV2))

        // Unlimited (-1) or zero need (slock #16453) is never short, even if already over capacity.
        assertEquals(0, seatsShortFor(seats = -1, usedTenths = 50L, needTenths = 10L))
        assertEquals(0, seatsShortFor(seats = 2, usedTenths = 50L, needTenths = 0L))
        assertEquals(1, seatsShortFor(seats = 2, usedTenths = 20L, needTenths = 1L))
        assertEquals(2, seatsShortFor(seats = 2, usedTenths = 25L, needTenths = 10L))
    }

    @Test
    fun proPricesAndFormattedUsdMatchSeatV1AndPersonV2() {
        assertEquals("$10", formatProSeatMonthlyUsd(annual = false, pricing = ProPricing.SeatV1))
        assertEquals("$8.80", formatProSeatMonthlyUsd(annual = true, pricing = ProPricing.SeatV1))
        assertEquals("$20", formatProSeatMonthlyUsd(annual = false, pricing = ProPricing.PersonV2))
        assertEquals("$17.60", formatProSeatMonthlyUsd(annual = true, pricing = ProPricing.PersonV2))

        val v2Annual = calculateProPrice(seatQuantity = 3, annual = true, pricing = ProPricing.PersonV2)
        assertEquals(52.8, v2Annual.monthlyUsd)
        assertEquals(633.6, v2Annual.annualUsd)
        assertEquals(12, v2Annual.discountPercent)

        val v1Monthly = calculateProPrice(seatQuantity = 2, annual = false, pricing = ProPricing.SeatV1)
        assertEquals(20.0, v1Monthly.monthlyUsd)
        assertNull(v1Monthly.annualUsd)
        assertEquals(0, v1Monthly.discountPercent)
    }

    @Test
    fun capacityLimitDetailParsesPricingAndComputesRefusedAgentShortfall() {
        val v1Body =
            """{"error":"Seat limit","capacityLimit":{"limitType":"universal_seats","limit":2,"usage":2,"nextUsage":2.1,"humans":2,"agents":0,"pricing":"seat_v1"}}"""
        val v1 = assertNotNull(parseCapacityLimitDetail(v1Body))
        assertEquals(ProPricing.SeatV1, v1.resolvedPricing)
        assertEquals(1, refusedAgentSeatsShort(v1))

        val v2Body =
            """{"error":"Seat limit","capacityLimit":{"limitType":"universal_seats","limit":2,"usage":3,"nextUsage":3,"humans":3,"agents":5,"pricing":"person_v2"}}"""
        val v2 = assertNotNull(parseCapacityLimitDetail(v2Body))
        assertEquals(ProPricing.PersonV2, v2.resolvedPricing)
        assertEquals(0, refusedAgentSeatsShort(v2))

        // Older server omits pricing -> defaults to seat_v1.
        val legacyBody =
            """{"capacityLimit":{"limitType":"universal_seats","limit":2,"usage":2,"nextUsage":2.1,"humans":2,"agents":0}}"""
        val legacy = assertNotNull(parseCapacityLimitDetail(legacyBody))
        assertNull(legacy.pricing)
        assertEquals(ProPricing.SeatV1, legacy.resolvedPricing)
        assertEquals(1, refusedAgentSeatsShort(legacy))
    }

    @Test
    fun seatRequestPurposeTenthsAndEffectiveShortfallRespectServerPricing() {
        val agent = SeatsAddPurpose.AgentCreate("scout")
        val assistant = SeatsAddPurpose.AssistantCreate
        val invite = SeatsAddPurpose.Invite(listOf("Ada@Example.com", "ada@example.com", "grace@example.com"))
        val promote = SeatsAddPurpose.MemberPromote("u-1", "Ada")
        val join = SeatsAddPurpose.Join("u-2", "Grace")

        assertEquals(1L, seatsAddPurposeTenths(agent, ProPricing.SeatV1))
        assertEquals(0L, seatsAddPurposeTenths(agent, ProPricing.PersonV2))
        assertEquals(1L, seatsAddPurposeTenths(assistant, ProPricing.SeatV1))
        assertEquals(0L, seatsAddPurposeTenths(assistant, ProPricing.PersonV2))
        assertEquals(20L, seatsAddPurposeTenths(invite, ProPricing.SeatV1))
        assertEquals(20L, seatsAddPurposeTenths(invite, ProPricing.PersonV2))
        assertEquals(10L, seatsAddPurposeTenths(promote, ProPricing.PersonV2))
        assertEquals(10L, seatsAddPurposeTenths(join, ProPricing.PersonV2))

        // #16453: under person_v2, an agent or assistant takes 0 tenths -> 0 short even if server reported seatsShort > 0.
        val v2Check = SeatRequestCheck(requesterAllowed = true, seatsShort = 2, pricing = ProPricing.PersonV2, pricingExplicit = true)
        assertEquals(0, seatRequestEffectiveSeatsShort(agent, v2Check))
        assertEquals(0, seatRequestEffectiveSeatsShort(assistant, v2Check))
        assertEquals(2, seatRequestEffectiveSeatsShort(invite, v2Check))

        // Under seat_v1, agent stays short.
        val v1Check = SeatRequestCheck(requesterAllowed = true, seatsShort = 1, pricing = ProPricing.SeatV1, pricingExplicit = true)
        assertEquals(1, seatRequestEffectiveSeatsShort(agent, v1Check))

        // Disallowed requester is never short (completes with no charge).
        assertEquals(0, seatRequestEffectiveSeatsShort(invite, v2Check.copy(requesterAllowed = false)))
    }

    @Test
    fun loadServerProPricingResolvesRetriesAndFallsBackOn404() = runTest {
        // Known billing short-circuits without hitting the network.
        val unusedClient = ScriptedNetworkClient(ArrayDeque())
        assertEquals(
            ProPricing.PersonV2,
            loadServerProPricing(unusedClient, serverId = "srv-1", knownBillingPresent = true, knownBillingPricing = ProPricing.PersonV2, retryDelayMs = 0L),
        )
        assertEquals(0, unusedClient.requests.size)

        // 200 person_v2.
        val v2Client = ScriptedNetworkClient(ArrayDeque(listOf(HomeNetworkResponse(200, """{"pricing":"person_v2"}"""))))
        assertEquals(ProPricing.PersonV2, loadServerProPricing(v2Client, serverId = "srv-v2", retryDelayMs = 0L))
        assertEquals(listOf(Triple<String, String, ServerId?>("GET", "billing/pricing", ServerId("srv-v2"))), v2Client.requests)

        // 404 (older server without /billing/pricing) -> SeatV1 immediately, no retry.
        val legacyClient = ScriptedNetworkClient(ArrayDeque(listOf(HomeNetworkResponse(404, """{"error":"Not found"}"""))))
        assertEquals(ProPricing.SeatV1, loadServerProPricing(legacyClient, serverId = "srv-old", retryDelayMs = 0L))
        assertEquals(1, legacyClient.requests.size)

        // Transient 500 -> retries once and recovers.
        val retryOkClient =
            ScriptedNetworkClient(
                ArrayDeque(
                    listOf(
                        HomeNetworkResponse(500, """{"error":"busy"}"""),
                        HomeNetworkResponse(200, """{"pricing":"person_v2"}"""),
                    )
                )
            )
        assertEquals(ProPricing.PersonV2, loadServerProPricing(retryOkClient, serverId = "srv-retry", retryDelayMs = 0L))
        assertEquals(2, retryOkClient.requests.size)

        // Repeated failure -> returns null so UI never guesses a price.
        val failClient =
            ScriptedNetworkClient(
                ArrayDeque(
                    listOf(
                        HomeNetworkResponse(503, null),
                        HomeNetworkResponse(503, null),
                    )
                )
            )
        assertNull(loadServerProPricing(failClient, serverId = "srv-fail", retryDelayMs = 0L))
        assertEquals(2, failClient.requests.size)
    }

    @Test
    fun seatsAddActionCardParsesAndRoundTripsPurposeMetadata() {
        val raw =
            """
            {
              "kind": "action-card",
              "state": "prepared",
              "sourceServerId": "srv-other",
              "targetServerName": "Lumen",
              "requesterUserId": "u-ada",
              "action": {
                "type": "seats:add",
                "seats": 2,
                "purpose": { "kind": "invite", "emails": ["grace@lumen.test", "alan@lumen.test"] }
              }
            }
            """.trimIndent()
        val parsed = assertNotNull(Json { ignoreUnknownKeys = true }.decodeFromString(WireActionCardMetadata.serializer(), raw).toThreadActionCard())
        assertEquals(SeatsAddActionCardType, parsed.type)
        assertEquals(2, parsed.seatsAddCount)
        assertEquals(SeatsAddPurpose.Invite(listOf("grace@lumen.test", "alan@lumen.test")), parsed.seatsAddPurpose)

        val roundTripped = assertNotNull(actionCardFromMessageMetadata(parsed.toMessageMetadata()))
        assertEquals(parsed.seatsAddCount, roundTripped.seatsAddCount)
        assertEquals(parsed.seatsAddPurpose, roundTripped.seatsAddPurpose)
        assertEquals("srv-other", roundTripped.sourceServerId)

        // Agent purpose round-trip.
        val agentCard = parsed.copy(seatsAddCount = 1, seatsAddPurpose = SeatsAddPurpose.AgentCreate("scout"))
        val agentRoundTripped = assertNotNull(actionCardFromMessageMetadata(agentCard.toMessageMetadata()))
        assertEquals(SeatsAddPurpose.AgentCreate("scout"), agentRoundTripped.seatsAddPurpose)
    }

    @Test
    fun checkSeatRequestClientParsesPricingFallsBackOnLegacyAndForwardsServerOverride() = runTest {
        val network =
            ScriptedNetworkClient(
                ArrayDeque(
                    listOf(
                        HomeNetworkResponse(200, """{"requesterAllowed":true,"seatsShort":2,"pricing":"person_v2"}"""),
                        HomeNetworkResponse(200, """{"requesterAllowed":true,"seatsShort":1}"""),
                        HomeNetworkResponse(404, null),
                    )
                )
            )
        val client = RaftIntegrationsActionsPushApiClient(RaftIntegrationsActionsPushNetworkApi(network))

        val v2 = assertIs<DataResult.Content<SeatRequestCheckOutcome.Checked>>(client.checkSeatRequest(MessageId("m-1"), ServerId("srv-dm"))).data.check
        assertEquals(SeatRequestCheck(requesterAllowed = true, seatsShort = 2, pricing = ProPricing.PersonV2, pricingExplicit = true), v2)
        assertEquals(Triple<String, String, ServerId?>("GET", "actions/m-1/seat-request", ServerId("srv-dm")), network.requests[0])

        // Older server omits pricing -> SeatV1 with pricingExplicit = false.
        val legacy = assertIs<DataResult.Content<SeatRequestCheckOutcome.Checked>>(client.checkSeatRequest(MessageId("m-2"), null)).data.check
        assertEquals(SeatRequestCheck(requesterAllowed = true, seatsShort = 1, pricing = ProPricing.SeatV1, pricingExplicit = false), legacy)

        // Older server lacks /seat-request (plain 404 without action errorCode) -> Unsupported.
        assertEquals(
            DataResult.Content(SeatRequestCheckOutcome.Unsupported),
            client.checkSeatRequest(MessageId("m-3"), ServerId("srv-old")),
        )
    }

    @Test
    fun specStoreSeatRequestCompletesImmediatelyForPersonV2AgentAndOpensSheetForInvite() = runTest {
        val specJson =
            """
            {"catalog":"action-card.v1","icon":"credit_card",
             "title":{"key":"actionCard.title.addSeats.one","text":{"en":"Add 1 seat"}},
             "spec":{"root":"root","elements":{
               "root":{"type":"Stack","props":{},"children":["add"]},
               "add":{"type":"Button","props":{"label":{"key":"actionCard.verb.addSeats","text":{"en":"Add Seats"}},"emphasis":"primary","action":{"kind":"open","intent":"add_seats"}}}
             }}}
            """.trimIndent()
        val spec = assertNotNull(stripActionCardSpec(assertNotNull(parseActionCardSpec(Json.parseToJsonElement(specJson))), RAFT_APP_ACTION_CARD_CAPABILITIES))

        // Case 1 (#16453): agent:create seat request under person_v2 completes immediately without opening add-seats sheet.
        val applied = mutableListOf<Pair<String, ThreadActionCard>>()
        var completedCount = 0
        val agentCard =
            ThreadActionCard(
                type = SeatsAddActionCardType,
                state = "prepared",
                sourceServerId = "srv-v2",
                seatsAddCount = 1,
                seatsAddPurpose = SeatsAddPurpose.AgentCreate("scout"),
            )
        val v2Store =
            ThreadActionCardSpecStore(
                scope = backgroundScope,
                execute = { _, _, _ -> error("unexpected") },
                applyCard = { id, card -> applied += id to card },
                checkSeatRequest = { _, _ ->
                    DataResult.Content(
                        SeatRequestCheckOutcome.Checked(
                            SeatRequestCheck(requesterAllowed = true, seatsShort = 2, pricing = ProPricing.PersonV2, pricingExplicit = true)
                        )
                    )
                },
                completeSeatRequest = { _, card ->
                    completedCount++
                    DataResult.Content(SeatRequestCompleteOutcome.Completed(card.copy(state = "executed")))
                },
            )
        v2Store.dispatch(ThreadActionCardSpecIntent.Press("m-agent", "add", agentCard, spec))
        runCurrent()
        assertEquals(1, completedCount)
        assertNull(v2Store.state.value.seatRequest)
        assertEquals("executed", applied.single().second.state)

        // Case 2: invite seat request under person_v2 opens add-seats sheet with check.pricing and opens web on commit.
        val inviteCard =
            ThreadActionCard(
                type = SeatsAddActionCardType,
                state = "prepared",
                sourceServerId = "srv-v2",
                seatsAddCount = 2,
                seatsAddPurpose = SeatsAddPurpose.Invite(listOf("a@test.com", "b@test.com")),
            )
        v2Store.dispatch(ThreadActionCardSpecIntent.Press("m-invite", "add", inviteCard, spec))
        runCurrent()
        val opened = assertNotNull(v2Store.state.value.seatRequest)
        assertEquals(2, opened.seatsShort)
        assertEquals(ProPricing.PersonV2, opened.pricing)
        assertEquals(20L, opened.needTenths)

        v2Store.dispatch(ThreadActionCardSpecIntent.OpenSeatRequestInWeb)
        assertNull(v2Store.state.value.seatRequest)
        assertEquals(ThreadActionCardSpecEffect.OpenSeatRequestInWeb("m-invite"), v2Store.effects.first())

        // Case 3: older server without /seat-request falls back to OpenSeatRequestInWeb immediately.
        val legacyStore =
            ThreadActionCardSpecStore(
                scope = backgroundScope,
                execute = { _, _, _ -> error("unexpected") },
                applyCard = { _, _ -> },
                checkSeatRequest = { _, _ -> DataResult.Content(SeatRequestCheckOutcome.Unsupported) },
            )
        legacyStore.dispatch(ThreadActionCardSpecIntent.Press("m-legacy", "add", inviteCard, spec))
        runCurrent()
        assertNull(legacyStore.state.value.seatRequest)
        assertEquals(ThreadActionCardSpecEffect.OpenSeatRequestInWeb("m-legacy"), legacyStore.effects.first())

        // Case 4: 409 EXPIRED marks the card expired with no residual error banner.
        val expiredApplied = mutableListOf<ThreadActionCard>()
        val expiredStore =
            ThreadActionCardSpecStore(
                scope = backgroundScope,
                execute = { _, _, _ -> error("unexpected") },
                applyCard = { _, card -> expiredApplied += card },
                checkSeatRequest = { _, _ ->
                    DataResult.Error(ScreenError(code = "action_seat_request_contract_mismatch", retryable = false, serverCode = "EXPIRED"))
                },
            )
        expiredStore.dispatch(ThreadActionCardSpecIntent.Press("m-exp", "add", inviteCard, spec))
        runCurrent()
        assertTrue(expiredApplied.single().isExpired)
        assertNull(expiredStore.state.value.errors["m-exp"])
    }
}
