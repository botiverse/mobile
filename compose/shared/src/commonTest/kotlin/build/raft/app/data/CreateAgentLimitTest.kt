package build.raft.app.data

import build.raft.app.createAgentAtLimit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The create dialog's agent limit, as web `CreateAgentDialog` (slock staging 947bd94): billing
 * `capacity.maxAgents` and `usage.agents` from `GET /billing/subscription`, else the plan's own
 * limits and the server's agents.
 */
class CreateAgentLimitTest {
    @Test
    fun parsesThePlanCapAndUsageFromBilling() {
        val billing =
            parseCreateAgentBilling(
                """{"plan":"free","displayName":"Free","pricing":"seat_v1","capacity":{"maxHumans":1,"maxAgents":2,"maxUniversalSeats":-1},"usage":{"humans":2,"agents":2,"universalSeats":0},"stripeConfigured":true}"""
            )
        assertEquals(
            CreateAgentBilling(
                plan = "free",
                pricing = ProPricing.SeatV1,
                maxAgents = 2,
                agents = 2,
                maxUniversalSeats = -1,
                humans = 2,
                universalSeats = 0.0,
            ),
            billing,
        )
    }

    @Test
    fun parsesPersonV2UniversalSeatsFromBilling() {
        val billing =
            parseCreateAgentBilling(
                """{"plan":"pro","displayName":"Pro","pricing":"person_v2","capacity":{"maxHumans":-1,"maxAgents":-1,"maxUniversalSeats":3},"usage":{"humans":3,"agents":25,"universalSeats":3}}"""
            )
        assertEquals(
            CreateAgentBilling(
                plan = "pro",
                pricing = ProPricing.PersonV2,
                maxAgents = -1,
                agents = 25,
                maxUniversalSeats = 3,
                humans = 3,
                universalSeats = 3.0,
            ),
            billing,
        )
    }

    @Test
    fun billingWithoutCapacityOrUsageLeavesThemUnknown() {
        assertEquals(CreateAgentBilling(plan = "pro"), parseCreateAgentBilling("""{"plan":"pro"}"""))
        assertNull(parseCreateAgentBilling("not json"))
    }

    @Test
    fun theBillingCapIsReachedWhenOneMoreWouldPassIt() {
        val limit = createAgentLimit(CreateAgentBilling(plan = "free", maxAgents = 2, agents = 2), serverPlan = "free", serverAgentCount = 0)
        assertEquals(CreateAgentLimit(reached = true, usage = 2, limit = 2, planLabel = "Free", pricing = ProPricing.SeatV1), limit)
        assertTrue(createAgentAtLimit(limit.usage, limit.limit))
    }

    @Test
    fun noAgentCapInBillingMeansNoLimit() {
        val limit = createAgentLimit(CreateAgentBilling(plan = "pro", maxAgents = -1, agents = 40), serverPlan = "pro", serverAgentCount = 40)
        assertFalse(limit.reached)
        assertEquals("Pro", limit.planLabel)
        assertFalse(createAgentAtLimit(limit.usage, limit.limit))
    }

    @Test
    fun unreadBillingOnFreeHasNoAgentCap() {
        val limit = createAgentLimit(billing = null, serverPlan = "free", serverAgentCount = 50)
        assertEquals(CreateAgentLimit(reached = false, usage = 50, limit = -1, planLabel = "Free", pricing = ProPricing.SeatV1), limit)
    }

    @Test
    fun unreadBillingOnProDefersCapEnforcementToServer() {
        // #5588 / slock #16525: when billing can't be read, Create Agent sets no client-side cap of its own.
        assertFalse(createAgentLimit(billing = null, serverPlan = "pro", serverAgentCount = 10).reached)
        assertFalse(createAgentLimit(billing = null, serverPlan = "pro", serverAgentCount = 99).reached)
        assertEquals(-1, createAgentLimit(billing = null, serverPlan = "pro", serverAgentCount = 10).limit)
    }

    @Test
    fun personV2AgentsTakeNoSeatsEvenWhenPeopleFillCapacity() {
        val billing =
            CreateAgentBilling(
                plan = "pro",
                pricing = ProPricing.PersonV2,
                maxAgents = -1,
                agents = 40,
                maxUniversalSeats = 3,
                humans = 3,
                universalSeats = 3.0,
            )
        val limit = createAgentLimit(billing, serverPlan = "pro", serverAgentCount = 40)
        assertFalse(limit.reached)
        assertEquals(ProPricing.PersonV2, limit.pricing)
        assertNull(limit.seatState)
    }

    @Test
    fun seatV1AndOmittedPricingCountAgentsAgainstUniversalSeats() {
        val fullSeatV1 =
            CreateAgentBilling(
                plan = "pro",
                pricing = ProPricing.SeatV1,
                maxAgents = -1,
                agents = 10,
                maxUniversalSeats = 3,
                humans = 2,
                universalSeats = 3.0,
            )
        val fullLimit = createAgentLimit(fullSeatV1, serverPlan = "pro", serverAgentCount = 10)
        assertTrue(fullLimit.reached)
        val seatState = fullLimit.seatState!!
        assertTrue(seatState.reached)
        assertEquals(30L, seatState.usedTenths)
        assertEquals(3, seatState.seatCapacity)
        assertEquals(1, seatState.seatsShort)

        // Older server omits `pricing`: falls back to seat_v1 universal seat counting.
        val omittedPricing = fullSeatV1.copy(pricing = null, agents = 5)
        val partialLimit = createAgentLimit(omittedPricing, serverPlan = "pro", serverAgentCount = 5)
        assertFalse(partialLimit.reached)
        val partialSeats = partialLimit.seatState!!
        assertFalse(partialSeats.reached)
        assertEquals(25L, partialSeats.usedTenths)
        assertEquals(0, partialSeats.seatsShort)
        assertEquals(ProPricing.SeatV1, partialSeats.pricing)
    }

    @Test
    fun dedicatedAgentCapStillBlocksUnderPersonV2() {
        // Free / AppSumo plans with explicit maxAgents still enforce maxAgents even if pricing is person_v2.
        val freeBilling =
            CreateAgentBilling(
                plan = "free",
                pricing = ProPricing.PersonV2,
                maxAgents = 2,
                agents = 2,
                maxUniversalSeats = -1,
                humans = 1,
            )
        val limit = createAgentLimit(freeBilling, serverPlan = "free", serverAgentCount = 2)
        assertTrue(limit.reached)
        assertEquals(2, limit.limit)
        assertNull(limit.seatState)
    }

    @Test
    fun thePlanNameComesFromBillingThenTheServerThenFree() {
        assertEquals("Founder", createAgentLimit(CreateAgentBilling(plan = "founder"), serverPlan = "free", serverAgentCount = 0).planLabel)
        assertEquals("Partner", createAgentLimit(null, serverPlan = "partner", serverAgentCount = 0).planLabel)
        assertEquals("Free", createAgentLimit(null, serverPlan = null, serverAgentCount = 0).planLabel)
    }
}
