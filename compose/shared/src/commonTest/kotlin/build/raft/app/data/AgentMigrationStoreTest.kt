package build.raft.app.data

import build.raft.app.model.MemberId
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Payloads copied from botiverse/slock AGENT_CONTROLS_SLOCK_SHA:
// packages/server/src/routes/agents.ts `serializeOwnerMigrationStatus`,
// `GET /:id/migration`, `POST /:id/migrate`, `/migration/cancel`,
// `/migration/confirm-hosted`, `sendMigrationError`; services/hostedAgentMigrationService.ts
// `getHostedMigrationReview`.
class AgentMigrationStoreTest {
    private val agentId = MemberId("6f1c3b0e-1a2b-4c3d-8e9f-0a1b2c3d4e5f")
    private val statusPath = "agents/${agentId.value}/migration"
    private val migrateRef = "mig_AbCdEfGhIjKlMnOpQrStUv"

    private fun notice(
        state: String,
        revision: Int = 3,
        targetKind: String = "computer",
        extra: String = ""
    ): String =
        """{"migration":{"migrationRef":"$migrateRef","agentId":"${agentId.value}","state":"$state",""" +
            """"sourceMachineId":"m-source","targetKind":"$targetKind","targetMachineId":${if (targetKind == "hosted") "null" else "\"m-target\""},""" +
            """"provider":"object_store","failureReason":null,"abortReason":null,"transportErrorCode":null,"transportErrorMessage":null,""" +
            """"sourceWorkspaceArchivedAt":null,"sourceWorkspaceArchiveAbandonedAt":null,"sourceWorkspaceArchiveLastError":null,""" +
            """"sourceQuiescedAt":"2026-10-21T08:00:05.000Z","sourceBuildProgress":{"phase":"packing","files":120,"bytes":4096,"reportedAt":"2026-10-21T08:00:06.000Z"},""" +
            """"transferProgress":null,"prepDeadlineAt":"2026-10-21T08:30:00.000Z","transferDeadlineAt":"2026-10-21T09:00:00.000Z",""" +
            """"arrivalDeadlineAt":"2026-10-21T09:30:00.000Z","transportProvisioningStartedAt":null,"transportProvisionedAt":null,""" +
            """"transportControlRegisteredAt":null,"transportProvisionFailedAt":null,"transportLostAt":null,"transportTeardownAt":null,""" +
            """"readyAt":null,"flippedAt":null,"arrivedAt":null,"completedAt":null,"abortedAt":null,"canceledAt":null,""" +
            """"cancelDisposition":null,"cancelReason":null,"cancelNeedsAttention":false,"cancelDispatchAttempts":0,""" +
            """"cancelLastDispatchAt":null,"cancelAttentionDeadlineAt":null,"cancelNeedsAttentionAt":null,"cancelErrorCode":null,""" +
            """"cancelErrorMessage":null,"cancelSourceAcknowledgedAt":null,"cancelSourceOutcome":null,"cancelTargetAcknowledgedAt":null,""" +
            """"cancelTargetOutcome":null,"revision":$revision,"createdAt":"2026-10-21T08:00:00.000Z","updatedAt":"2026-10-21T08:00:06.000Z"$extra}}"""

    private val hostedReview =
        ""","hosted":{"provider":"antiproton","hostedModel":null,"reviewReadyAt":"2026-10-21T08:05:00.000Z","reviewConfirmedAt":null,""" +
            """"importSummary":{"importedFileCount":42,"importedBytes":10240,"skipped":[{"reason":"git","count":3,"bytes":2048,"largestPaths":[".git/index"]}]}}"""

    @Test
    fun parsesTheOwnerStatusPayloadAndDerivesProgress() {
        val parsed = parseSharedAgentMigrationStatus(notice("provisioning"))!!.migration!!
        assertEquals("provisioning", parsed.state)
        assertEquals(120, parsed.sourceBuildProgress?.files?.toInt())
        assertTrue(parsed.isActive)
        assertTrue(parsed.canCancel())
        assertEquals(1, parsed.progressStep()) // sourceQuiescedAt advances past "stop agent"
        assertNull(parseSharedAgentMigrationStatus("""{"migration":null}""")!!.migration)

        val review = parseSharedAgentMigrationStatus(notice("in_transit", targetKind = "hosted", extra = hostedReview))!!.migration!!
        assertTrue(review.isHostedReviewPending())
        assertEquals(42, review.hosted?.importSummary?.importedFileCount)
        assertEquals(2, review.progressStep())
    }

    @Test
    fun stateMachineMatchesWebForEndingsAndCancel() {
        val switched = parseSharedAgentMigrationStatus(
            notice("arriving", targetKind = "hosted").replace("\"flippedAt\":null", "\"flippedAt\":\"2026-10-21T08:10:00.000Z\"")
        )!!.migration!!
        assertFalse(switched.canCancel()) // a cloud move can't be canceled after the switch
        val failed = parseSharedAgentMigrationStatus(notice("failed"))!!.migration!!
        assertEquals(AgentMigrationEnding.Failed, failed.endedComputerMigration())
        assertTrue(failed.needsAttention())
        assertFalse(parseSharedAgentMigrationStatus(notice("ready", revision = 0))!!.migration!!.canCancel())
    }

    @Test
    fun startBodiesMatchTheWebDialog() {
        val computer = Json.parseToJsonElement(agentMigrationStartRequestBody(AgentMigrationTarget.Computer("m-target"))).jsonObject
        assertEquals(setOf("targetComputer"), computer.keys)
        val cloud = Json.parseToJsonElement(agentMigrationStartRequestBody(AgentMigrationTarget.Cloud)).jsonObject
        assertEquals("hosted", cloud.getValue("target").jsonObject.getValue("kind").jsonPrimitive.content)
        assertEquals(setOf("kind"), cloud.getValue("target").jsonObject.keys)
    }

    @Test
    fun watchPollsEveryTwoSecondsWhileActiveAndStopsWhenItEnds() = runTest {
        val network = ScriptedAgentControlsNetworkClient()
        network.respond("GET", statusPath, 200, notice("provisioning"))
        network.respond("GET", statusPath, 200, notice("completed", revision = 9))
        network.respond("GET", "billing/subscription", 200, """{"plan":"pro","subscription":null,"stripeConfigured":true,"permissions":{"canReadBillingSummary":true,"canManageBilling":true}}""")
        val store = AgentMigrationStore(RaftAgentControlsApi(network), backgroundScope)

        store.dispatch(AgentMigrationIntent.Watch(agentId))
        runCurrent()
        assertEquals("provisioning", store.state.value.notice?.state)
        assertEquals("pro", store.state.value.billingPlan)

        advanceTimeBy(AgentMigrationStore.AGENT_MIGRATION_POLL_MS + 1)
        runCurrent()
        assertEquals("completed", store.state.value.notice?.state)
        // Watched to completion: the agent's computer changed, so the page reloads it.
        assertEquals(AgentMigrationEffect.AgentPlacementChanged, store.state.value.effect)
        val polls = network.requests.count { it.path == statusPath }
        assertEquals(DataRequestPriority.Background, network.requests.last { it.path == statusPath }.priority)

        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(polls, network.requests.count { it.path == statusPath }, "no poll after the move ended")
    }

    @Test
    fun startRefreshesStatusAndSurfacesRefusals() = runTest {
        val network = ScriptedAgentControlsNetworkClient()
        network.respond("GET", statusPath, 200, """{"migration":null}""")
        network.respond("GET", "billing/subscription", 403, """{"error":"Only server owners and admins can view billing"}""")
        network.respond(
            "POST",
            "agents/${agentId.value}/migrate",
            409,
            """{"error":"Target computer matches the source computer","code":"TARGET_COMPUTER_MATCHES_SOURCE","details":{"failureReason":"TARGET_COMPUTER_MATCHES_SOURCE"}}"""
        )
        val store = AgentMigrationStore(RaftAgentControlsApi(network), backgroundScope)
        store.dispatch(AgentMigrationIntent.Watch(agentId))
        runCurrent()
        assertNull(store.state.value.billingPlan) // unreadable billing never invents a denial
        assertFalse(store.state.value.billingChecking)

        store.dispatch(AgentMigrationIntent.Start(AgentMigrationTarget.Computer("m-source")))
        runCurrent()
        assertEquals("TARGET_COMPUTER_MATCHES_SOURCE", store.state.value.startError?.code)
        assertFalse(store.state.value.starting)

        network.respond("POST", "agents/${agentId.value}/migrate", 200, """{"migrationRef":"$migrateRef","state":"provisioning","sourceMachineId":"m-source","targetMachineId":"m-target"}""")
        network.respond("GET", statusPath, 200, notice("provisioning"))
        store.dispatch(AgentMigrationIntent.Start(AgentMigrationTarget.Computer("m-target")))
        runCurrent()
        assertNull(store.state.value.startError)
        assertEquals("provisioning", store.state.value.notice?.state)
    }

    @Test
    fun proPlanRefusalOpensTheUpgradePromptInsteadOfAnError() = runTest {
        val network = ScriptedAgentControlsNetworkClient()
        network.respond("GET", statusPath, 200, """{"migration":null}""")
        network.respond("GET", "billing/subscription", 403, """{"error":"Only server owners and admins can view billing"}""")
        network.respond(
            "POST",
            "agents/${agentId.value}/migrate",
            403,
            """{"error":"Agent migration requires a Pro plan","code":"MIGRATION_PRO_PLAN_REQUIRED","details":{"failureReason":"MIGRATION_PRO_PLAN_REQUIRED"}}"""
        )
        val store = AgentMigrationStore(RaftAgentControlsApi(network), backgroundScope)
        store.dispatch(AgentMigrationIntent.Watch(agentId))
        runCurrent()

        store.dispatch(AgentMigrationIntent.Start(AgentMigrationTarget.Cloud))
        runCurrent()

        assertEquals(AgentMigrationEffect.ProRequired, store.state.value.effect)
        assertNull(store.state.value.startError)
    }

    @Test
    fun cancelRetriesAStaleRevisionWithTheLatestOne() = runTest {
        val network = ScriptedAgentControlsNetworkClient()
        val cancelPath = "agents/${agentId.value}/migration/cancel"
        network.respond("GET", statusPath, 200, notice("provisioning", revision = 3))
        network.respond("GET", statusPath, 200, notice("ready", revision = 5))
        network.respond("GET", statusPath, 200, notice("cancel_requested_pre_flip", revision = 6))
        network.respond("POST", cancelPath, 409, """{"error":"Migration changed concurrently","code":"MIGRATION_REVISION_STALE","details":{"failureReason":"MIGRATION_REVISION_STALE"}}""")
        network.respond("POST", cancelPath, 200, """{"ok":true}""")
        val store = AgentMigrationStore(RaftAgentControlsApi(network), backgroundScope)
        store.dispatch(AgentMigrationIntent.Watch(agentId, checkPlan = false))
        runCurrent()

        store.dispatch(AgentMigrationIntent.Cancel)
        runCurrent()

        val revisions =
            network.requests.filter { it.path == cancelPath }
                .map { Json.parseToJsonElement(it.bodyText!!).jsonObject.getValue("expectedRevision").jsonPrimitive.int }
        assertEquals(listOf(3, 5), revisions)
        assertNull(store.state.value.cancelError)
        assertEquals("cancel_requested_pre_flip", store.state.value.notice?.state)
    }

    @Test
    fun cancelFailureKeepsTheCodeForTheBanner() = runTest {
        val network = ScriptedAgentControlsNetworkClient()
        network.respond("GET", statusPath, 200, notice("provisioning"))
        network.respond("POST", "agents/${agentId.value}/migration/cancel", 404, """{"error":"Migration not found","code":"MIGRATION_NOT_FOUND"}""")
        val store = AgentMigrationStore(RaftAgentControlsApi(network), backgroundScope)
        store.dispatch(AgentMigrationIntent.Watch(agentId, checkPlan = false))
        runCurrent()

        store.dispatch(AgentMigrationIntent.Cancel)
        runCurrent()

        assertEquals("MIGRATION_NOT_FOUND", store.state.value.cancelError?.code)
        assertFalse(store.state.value.canceling)
    }

    @Test
    fun confirmHostedPostsTheShownRevision() = runTest {
        val network = ScriptedAgentControlsNetworkClient()
        val confirmPath = "agents/${agentId.value}/migration/confirm-hosted"
        network.respond("GET", statusPath, 200, notice("in_transit", revision = 7, targetKind = "hosted", extra = hostedReview))
        network.respond("POST", confirmPath, 200, notice("arriving", revision = 8, targetKind = "hosted"))
        val store = AgentMigrationStore(RaftAgentControlsApi(network), backgroundScope)
        store.dispatch(AgentMigrationIntent.Watch(agentId, checkPlan = false))
        runCurrent()

        store.dispatch(AgentMigrationIntent.ConfirmHosted)
        runCurrent()

        val body = Json.parseToJsonElement(network.requests.single { it.path == confirmPath }.bodyText!!).jsonObject
        assertEquals(migrateRef, body.getValue("migrationRef").jsonPrimitive.content)
        assertEquals(7, body.getValue("expectedRevision").jsonPrimitive.int)
        assertNull(store.state.value.confirmError)
    }

    @Test
    fun statusErrorKeepsTheLastNotice() = runTest {
        val network = ScriptedAgentControlsNetworkClient()
        network.respond("GET", statusPath, 200, notice("provisioning"))
        network.respond("GET", statusPath, 500, """{"error":"boom","code":"MIGRATION_STATUS_FAILED","details":{"failureReason":"MIGRATION_STATUS_FAILED"}}""")
        val store = AgentMigrationStore(RaftAgentControlsApi(network), backgroundScope)
        store.dispatch(AgentMigrationIntent.Watch(agentId, checkPlan = false))
        runCurrent()
        advanceTimeBy(AgentMigrationStore.AGENT_MIGRATION_POLL_MS + 1)
        runCurrent()

        assertEquals("provisioning", store.state.value.notice?.state)
        assertEquals("MIGRATION_STATUS_FAILED", store.state.value.statusError?.code)
    }

    @Test
    fun pollPausesWhileHiddenAndReadsOnceWhenShownAgain() = runTest {
        val network = ScriptedAgentControlsNetworkClient()
        network.respond("GET", statusPath, 200, notice("provisioning"))
        var hostVisible = true
        val store = AgentMigrationStore(RaftAgentControlsApi(network), backgroundScope, isHostVisible = { hostVisible })
        store.dispatch(AgentMigrationIntent.Watch(agentId, checkPlan = false))
        runCurrent()
        val reads = { network.requests.count { it.path == statusPath } }
        assertEquals(1, reads())

        store.dispatch(AgentMigrationIntent.SetPageVisible(false))
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, reads(), "no poll while the page is covered")

        store.dispatch(AgentMigrationIntent.SetPageVisible(true))
        runCurrent()
        assertEquals(2, reads(), "one read when the page shows again")

        hostVisible = false
        advanceTimeBy(AgentMigrationStore.AGENT_MIGRATION_POLL_MS + 1)
        runCurrent()
        assertEquals(2, reads(), "no poll while the app is backgrounded")
        hostVisible = true
        store.dispatch(AgentMigrationIntent.SetPageVisible(true))
        runCurrent()
        assertEquals(3, reads())
    }

    @Test
    fun actionsCompletingAfterStopWatchingDoNotRestartReads() = runTest {
        val network = ScriptedAgentControlsNetworkClient()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        network.respond("GET", statusPath, 200, notice("provisioning"))
        network.respond("POST", "agents/${agentId.value}/migration/cancel", 200, """{"ok":true}""")
        val gated =
            object : AgentMigrationDataSource by RaftAgentControlsApi(network) {
                override suspend fun cancelMigration(agentId: MemberId, migrationRef: String, expectedRevision: Int): DataResult<Unit> {
                    gate.await()
                    return DataResult.Content(Unit)
                }
            }
        val store = AgentMigrationStore(gated, backgroundScope)
        store.dispatch(AgentMigrationIntent.Watch(agentId, checkPlan = false))
        runCurrent()
        store.dispatch(AgentMigrationIntent.Cancel)
        runCurrent()
        store.dispatch(AgentMigrationIntent.StopWatching)
        val readsBefore = network.requests.count { it.path == statusPath }

        gate.complete(Unit)
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(readsBefore, network.requests.count { it.path == statusPath })
    }
}
