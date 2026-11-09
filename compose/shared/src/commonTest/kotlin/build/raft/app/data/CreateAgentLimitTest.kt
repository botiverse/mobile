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
                """{"plan":"free","displayName":"Free","capacity":{"maxHumans":1,"maxAgents":2,"maxUniversalSeats":-1},"usage":{"humans":2,"agents":2,"universalSeats":0},"stripeConfigured":true}"""
            )
        assertEquals(CreateAgentBilling(plan = "free", maxAgents = 2, agents = 2), billing)
    }

    @Test
    fun billingWithoutCapacityOrUsageLeavesThemUnknown() {
        assertEquals(CreateAgentBilling(plan = "pro"), parseCreateAgentBilling("""{"plan":"pro"}"""))
        assertNull(parseCreateAgentBilling("not json"))
    }

    @Test
    fun theBillingCapIsReachedWhenOneMoreWouldPassIt() {
        val limit = createAgentLimit(CreateAgentBilling(plan = "free", maxAgents = 2, agents = 2), serverPlan = "free", serverAgentCount = 0)
        assertEquals(CreateAgentLimit(reached = true, usage = 2, limit = 2, planLabel = "Free"), limit)
        assertTrue(createAgentAtLimit(limit.usage, limit.limit))
    }

    @Test
    fun belowTheCapCreateStaysOpen() {
        val limit = createAgentLimit(CreateAgentBilling(plan = "free", maxAgents = 2, agents = 1), serverPlan = null, serverAgentCount = 9)
        assertFalse(limit.reached)
        assertFalse(createAgentAtLimit(limit.usage, limit.limit))
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
        assertEquals(CreateAgentLimit(reached = false, usage = 50, limit = -1, planLabel = "Free"), limit)
    }

    @Test
    fun unreadBillingOnProFallsBackToOnePackOfAgents() {
        assertTrue(createAgentLimit(billing = null, serverPlan = "pro", serverAgentCount = 10).reached)
        assertFalse(createAgentLimit(billing = null, serverPlan = "pro", serverAgentCount = 9).reached)
    }

    @Test
    fun thePlanNameComesFromBillingThenTheServerThenFree() {
        assertEquals("Founder", createAgentLimit(CreateAgentBilling(plan = "founder"), serverPlan = "free", serverAgentCount = 0).planLabel)
        assertEquals("Partner", createAgentLimit(null, serverPlan = "partner", serverAgentCount = 0).planLabel)
        assertEquals("Free", createAgentLimit(null, serverPlan = null, serverAgentCount = 0).planLabel)
    }
}
