package build.raft.app.widget

import android.app.Application
import build.raft.app.RaftAgentStatusDotKind
import build.raft.app.raftAgentStatusDotKind
import build.raft.app.resources.Res
import build.raft.app.resources.agent_status_working_through_worker
import build.raft.app.resources.agent_status_working_through_workers
import build.raft.app.resources.agent_status_working_through_workers_presence
import build.raft.app.resources.desc
import build.raft.app.resources.format
import build.raft.app.resources.member_row_status_online
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Widget agent parsing parity with `agentRunningWorkersDisplayOf` / `agentDeliveryHoldOf`:
 * - expired `provider_limit` `deliveryHold` (`untilMs <= nowMs`) does NOT keep suppressing `runningWorkers`;
 * - active `provider_limit` and `credential_invalid` `deliveryHold` suppress `runningWorkers`;
 * - idle `credentialHold` (`activityDetailKind == "idle"`) suppresses `runningWorkers`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SlockAgentStatusWidgetProviderTest {

    @Test
    fun deliveryHoldExpirationAndCredentialHoldMatchAppRunningWorkersParity() {
        val nowMs = 10_000L
        val payload =
            """
            {"agents":[
              {"id":"a-expired-limit","name":"ExpiredLimit","activity":"online","runningWorkers":2,
               "deliveryHold":{"reason":"provider_limit","detail":"quota_exhausted","since":1000,"untilMs":10000}},
              {"id":"a-active-limit","name":"ActiveLimit","activity":"online","runningWorkers":2,
               "deliveryHold":{"reason":"provider_limit","detail":"quota_exhausted","since":1000,"untilMs":10001}},
              {"id":"a-cred-delivery","name":"CredDelivery","activity":"online","runningWorkers":2,
               "deliveryHold":{"reason":"credential_invalid","detail":"user_reauth","since":1000}},
              {"id":"a-cred-idle","name":"CredIdle","activity":"online","activityDetailKind":"idle","runningWorkers":2,
               "credentialHold":{"reason":"user_reauth"}},
              {"id":"a-cred-non-idle","name":"CredNonIdle","activity":"online","activityDetailKind":"working","runningWorkers":2,
               "credentialHold":{"reason":"user_reauth"}},
              {"id":"a-plain","name":"Plain","activity":"online","runningWorkers":3}
            ]}
            """.trimIndent()

        val byId =
            SlockAgentStatusWidgetProvider.parseAgentWidgetSummaries(payload, nowEpochMillis = nowMs)
                .associateBy { it.id }

        // Expired provider_limit (untilMs <= nowMs): Workers show through and dot is Yellow.
        val expiredLimit = byId.getValue("a-expired-limit")
        assertEquals(2, expiredLimit.runningWorkers)
        assertNotNull(expiredLimit.deliveryHold)
        assertEquals(Res.string.agent_status_working_through_workers.format(2), expiredLimit.detail)
        assertEquals(
            RaftAgentStatusDotKind.Yellow,
            raftAgentStatusDotKind(
                expiredLimit.statusWireValue,
                external = expiredLimit.external,
                nowEpochMillis = nowMs,
                blockedReason = expiredLimit.blockedReason,
                deliveryHold = expiredLimit.deliveryHold,
                runningWorkers = expiredLimit.runningWorkers,
                runtime = expiredLimit.runtime,
            ),
        )

        // Active provider_limit (untilMs > nowMs): suppresses runningWorkers and dot is Orange.
        val activeLimit = byId.getValue("a-active-limit")
        assertNull(activeLimit.runningWorkers)
        assertEquals(Res.string.member_row_status_online.desc(), activeLimit.detail)
        assertEquals(
            RaftAgentStatusDotKind.Orange,
            raftAgentStatusDotKind(
                activeLimit.statusWireValue,
                external = activeLimit.external,
                nowEpochMillis = nowMs,
                blockedReason = activeLimit.blockedReason,
                deliveryHold = activeLimit.deliveryHold,
                runningWorkers = activeLimit.runningWorkers,
                runtime = activeLimit.runtime,
            ),
        )

        // credential_invalid deliveryHold: suppresses runningWorkers and dot is Orange.
        val credDelivery = byId.getValue("a-cred-delivery")
        assertNull(credDelivery.runningWorkers)
        assertEquals(
            RaftAgentStatusDotKind.Orange,
            raftAgentStatusDotKind(
                credDelivery.statusWireValue,
                external = credDelivery.external,
                nowEpochMillis = nowMs,
                blockedReason = credDelivery.blockedReason,
                deliveryHold = credDelivery.deliveryHold,
                runningWorkers = credDelivery.runningWorkers,
                runtime = credDelivery.runtime,
            ),
        )

        // Older daemon idle credentialHold: suppresses runningWorkers.
        val credIdle = byId.getValue("a-cred-idle")
        assertNull(credIdle.runningWorkers)
        assertEquals(Res.string.member_row_status_online.desc(), credIdle.detail)

        // Non-idle credentialHold is ignored at ingest (matching AgentsApi / RealtimeEventMapper).
        val credNonIdle = byId.getValue("a-cred-non-idle")
        assertEquals(2, credNonIdle.runningWorkers)

        // Plain online agent with runningWorkers preserves count.
        val plain = byId.getValue("a-plain")
        assertEquals(3, plain.runningWorkers)
        assertEquals(Res.string.agent_status_working_through_workers.format(3), plain.detail)
    }

    @Test
    fun guestViewerSeesRunningWorkersAsPresenceOnly() {
        val nowMs = 10_000L
        val payload =
            """
            {"agents":[
              {"id":"a-one","name":"One","activity":"online","runningWorkers":1},
              {"id":"a-many","name":"Many","activity":"online","runningWorkers":4}
            ]}
            """.trimIndent()
        val guestById =
            SlockAgentStatusWidgetProvider.parseAgentWidgetSummaries(
                payload,
                nowEpochMillis = nowMs,
                viewerIsGuest = true,
            ).associateBy { it.id }
        for (id in listOf("a-one", "a-many")) {
            val guestAgent = guestById.getValue(id)
            assertEquals(1, guestAgent.runningWorkers)
            assertEquals(Res.string.agent_status_working_through_workers_presence.desc(), guestAgent.detail)
            assertEquals(
                RaftAgentStatusDotKind.Yellow,
                raftAgentStatusDotKind(
                    guestAgent.statusWireValue,
                    external = guestAgent.external,
                    nowEpochMillis = nowMs,
                    blockedReason = guestAgent.blockedReason,
                    deliveryHold = guestAgent.deliveryHold,
                    runningWorkers = guestAgent.runningWorkers,
                    runtime = guestAgent.runtime,
                ),
            )
        }

        // Member viewer keeps singular "Working through a Worker" for count == 1.
        val memberOne =
            SlockAgentStatusWidgetProvider.parseAgentWidgetSummaries(
                payload,
                nowEpochMillis = nowMs,
                viewerIsGuest = false,
            ).first { it.id == "a-one" }
        assertEquals(1, memberOne.runningWorkers)
        assertEquals(Res.string.agent_status_working_through_worker.desc(), memberOne.detail)
    }
}
