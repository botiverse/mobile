package build.raft.app.data

import build.raft.app.model.MemberId
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
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
    fun parsesWhoStartedTheMigration() {
        // routes/agents.ts at botiverse/slock 24873fd5be37 (#10951): `initiatedBy` while they are a member, else null.
        val initiated =
            parseSharedAgentMigrationStatus(
                notice("completed", extra = ""","initiatedBy":{"id":"user-1","name":"artin","displayName":"Artin"}""")
            )!!.migration!!
        assertEquals(AgentMigrationInitiator(id = "user-1", name = "artin", displayName = "Artin"), initiated.initiatedBy)
        assertEquals("Artin", initiated.initiatedBy?.label)
        val noDisplayName =
            parseSharedAgentMigrationStatus(
                notice("completed", extra = ""","initiatedBy":{"id":"user-1","name":"artin","displayName":null}""")
            )!!.migration!!
        assertEquals("artin", noDisplayName.initiatedBy?.label)
        assertNull(parseSharedAgentMigrationStatus(notice("completed", extra = ""","initiatedBy":null"""))!!.migration!!.initiatedBy)
        assertNull(parseSharedAgentMigrationStatus(notice("completed"))!!.migration!!.initiatedBy)
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
        assertEquals(setOf("targetComputer", "preflight"), computer.keys)
        assertEquals("m-target", computer.getValue("targetComputer").jsonPrimitive.content)
        assertEquals("v1", computer.getValue("preflight").jsonPrimitive.content)

        val computerOverride =
            Json.parseToJsonElement(agentMigrationStartRequestBody(AgentMigrationTarget.Computer("m-target"), preflightOverride = true)).jsonObject
        assertEquals(setOf("targetComputer", "preflight", "preflightOverride"), computerOverride.keys)
        assertEquals("v1", computerOverride.getValue("preflight").jsonPrimitive.content)
        assertTrue(computerOverride.getValue("preflightOverride").jsonPrimitive.boolean)

        val cloud = Json.parseToJsonElement(agentMigrationStartRequestBody(AgentMigrationTarget.Cloud)).jsonObject
        assertEquals(setOf("target"), cloud.keys)
        assertEquals("hosted", cloud.getValue("target").jsonObject.getValue("kind").jsonPrimitive.content)
        assertEquals(setOf("kind"), cloud.getValue("target").jsonObject.keys)

        // slock #17048: leaveOut is serialized only for computer targets when non-empty.
        val computerLeaveOut =
            Json.parseToJsonElement(
                agentMigrationStartRequestBody(
                    AgentMigrationTarget.Computer("m-target"),
                    leaveOut = listOf("work/", "**/drizzle"),
                )
            ).jsonObject
        assertEquals(setOf("targetComputer", "preflight", "leaveOut"), computerLeaveOut.keys)
        assertEquals("""["work/","**/drizzle"]""", computerLeaveOut.getValue("leaveOut").toString())

        val cloudWithLeaveOut =
            Json.parseToJsonElement(
                agentMigrationStartRequestBody(
                    AgentMigrationTarget.Cloud,
                    leaveOut = listOf("work/"),
                )
            ).jsonObject
        assertEquals(setOf("target"), cloudWithLeaveOut.keys)
    }

    @Test
    fun startIntentForwardsLeaveOutOverTheWire() = runTest {
        val network = ScriptedAgentControlsNetworkClient()
        network.respond("GET", statusPath, 200, """{"migration":null}""")
        network.respond("POST", "agents/${agentId.value}/migrate", 200, """{"migrationRef":"$migrateRef"}""")
        val store = AgentMigrationStore(RaftAgentControlsApi(network), this)
        store.dispatch(AgentMigrationIntent.PrepareStart(agentId, checkPlan = false))
        runCurrent()

        store.dispatch(
            AgentMigrationIntent.Start(
                target = AgentMigrationTarget.Computer("m-target"),
                leaveOut = listOf("models/", "**/node_modules"),
            ),
        )
        runCurrent()

        val post = network.requests.single { it.method == "POST" }
        val body = Json.parseToJsonElement(post.bodyText!!).jsonObject
        assertEquals("""["models/","**/node_modules"]""", body.getValue("leaveOut").toString())
    }

    @Test
    fun preflightRefusalSetsNotReadyTargetAndMoveAnywaySendsOverride() = runTest {
        val network = ScriptedAgentControlsNetworkClient()
        network.respond("GET", statusPath, 200, """{"migration":null}""")
        network.respond("GET", "billing/subscription", 200, """{"plan":"pro","subscription":null,"stripeConfigured":true,"permissions":{"canReadBillingSummary":true,"canManageBilling":true}}""")
        network.respond(
            "POST",
            "agents/${agentId.value}/migrate",
            409,
            """{"error":"The agent's runtime is not signed in on the target computer","code":"TARGET_RUNTIME_NOT_READY","details":{"side":"target","runtime":"claude","computerName":"Target","preflight":{"kind":"needs_login"}}}"""
        )
        val store = AgentMigrationStore(RaftAgentControlsApi(network), backgroundScope)
        store.dispatch(AgentMigrationIntent.Watch(agentId))
        runCurrent()

        store.dispatch(AgentMigrationIntent.Start(AgentMigrationTarget.Computer("m-target")))
        runCurrent()

        assertEquals("m-target", store.state.value.notReadyTargetId)
        assertEquals(AgentMigrationErrorCodes.TargetRuntimeNotReady, store.state.value.startError?.code)
        assertEquals(
            MigrationTargetRuntimeNotReady("claude", "Target", MigrationTargetRuntimeNotReadyKind.NeedsLogin),
            store.state.value.startError?.targetRuntimeNotReady,
        )
        val firstPost = Json.parseToJsonElement(network.requests.last { it.method == "POST" }.bodyText!!).jsonObject
        assertEquals("v1", firstPost.getValue("preflight").jsonPrimitive.content)
        assertFalse("preflightOverride" in firstPost)

        network.respond(
            "POST",
            "agents/${agentId.value}/migrate",
            200,
            """{"migrationRef":"$migrateRef","state":"provisioning","sourceMachineId":"m-source","targetMachineId":"m-target","preflight":{"kind":"needs_login"}}"""
        )
        network.respond("GET", statusPath, 200, notice("provisioning"))
        store.dispatch(AgentMigrationIntent.Start(AgentMigrationTarget.Computer("m-target"), preflightOverride = true))
        runCurrent()

        val secondPost = Json.parseToJsonElement(network.requests.last { it.method == "POST" }.bodyText!!).jsonObject
        assertEquals("v1", secondPost.getValue("preflight").jsonPrimitive.content)
        assertTrue(secondPost.getValue("preflightOverride").jsonPrimitive.boolean)
        assertNull(store.state.value.notReadyTargetId)
        assertNull(store.state.value.startError)
        assertEquals(AgentMigrationEffect.Started, store.state.value.effect)
        assertEquals("m-target", store.state.value.lastStartResult?.targetMachineId)
        assertEquals("needs_login", store.state.value.lastStartResult?.preflightKind)
    }

    @Test
    fun unverifiedPreflightIsPreservedOnStartResultAndPrepareStartSkipsPolling() = runTest {
        val network = ScriptedAgentControlsNetworkClient()
        network.respond("GET", "billing/subscription", 200, """{"plan":"pro","subscription":null,"stripeConfigured":true,"permissions":{"canReadBillingSummary":true,"canManageBilling":true}}""")
        network.respond(
            "POST",
            "agents/${agentId.value}/migrate",
            200,
            """{"migrationRef":"$migrateRef","state":"provisioning","sourceMachineId":"m-source","targetMachineId":"m-target","preflight":{"kind":"unverified"}}"""
        )
        val store = AgentMigrationStore(RaftAgentControlsApi(network), backgroundScope)
        store.dispatch(AgentMigrationIntent.PrepareStart(agentId))
        runCurrent()

        assertTrue(network.requests.none { it.path == statusPath }, "PrepareStart does not poll GET /migration")
        assertEquals("pro", store.state.value.billingPlan)

        store.dispatch(AgentMigrationIntent.Start(AgentMigrationTarget.Computer("m-target")))
        runCurrent()

        assertEquals(AgentMigrationEffect.Started, store.state.value.effect)
        assertEquals("unverified", store.state.value.lastStartResult?.preflightKind)
        assertEquals("m-target", store.state.value.lastStartResult?.targetMachineId)
        assertTrue(network.requests.none { it.path == statusPath })
    }

    @Test
    fun parsesCannotRespondReasonsOnCompletedMigrationStatus() {
        for (reason in AgentMigrationCannotRespondReason.entries) {
            val parsed =
                parseSharedAgentMigrationStatus(
                    notice("completed", extra = ""","cannotRespond":"${reason.wire}"""")
                )!!.migration!!
            assertEquals(reason, parsed.cannotRespondReason)
        }
        val none = parseSharedAgentMigrationStatus(notice("completed", extra = ""","cannotRespond":null"""))!!.migration!!
        assertNull(none.cannotRespondReason)
        val unknown = parseSharedAgentMigrationStatus(notice("completed", extra = ""","cannotRespond":"future_reason""""))!!.migration!!
        assertNull(unknown.cannotRespondReason)
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
    fun anAgentPlacedWithoutItsSourceMovedAtOnce() = runTest {
        // botiverse/slock #13575 routes/agents.ts placeAgentWithoutSourceOnTarget: its Computer's
        // access was revoked, so nothing is brought over and there is no migration to follow.
        val placed =
            """{"outcome":"placed_without_source","migrationRef":null,"state":"completed","sourceMachineId":"m-source","targetMachineId":"m-target"}"""
        assertEquals(
            AgentMigrationStartResult(migrationRef = null, placedWithoutSource = true, targetMachineId = "m-target"),
            parseSharedAgentMigrationStart(placed),
        )
        assertEquals(
            AgentMigrationStartResult(migrationRef = migrateRef),
            parseSharedAgentMigrationStart("""{"migrationRef":"$migrateRef","state":"provisioning"}""")
        )
        assertNull(parseSharedAgentMigrationStart("""{"migrationRef":null,"state":"provisioning"}"""))

        val network = ScriptedAgentControlsNetworkClient()
        network.respond("GET", statusPath, 200, """{"migration":null}""")
        network.respond("GET", "billing/subscription", 200, """{"plan":"free","subscription":null,"stripeConfigured":true,"permissions":{"canReadBillingSummary":true,"canManageBilling":true}}""")
        network.respond("POST", "agents/${agentId.value}/migrate", 200, placed)
        network.respond("GET", statusPath, 200, """{"migration":null}""")
        val store = AgentMigrationStore(RaftAgentControlsApi(network), backgroundScope)
        store.dispatch(AgentMigrationIntent.Watch(agentId))
        runCurrent()

        store.dispatch(AgentMigrationIntent.Start(AgentMigrationTarget.Computer("m-target")))
        runCurrent()
        assertNull(store.state.value.startError, "a placement is not a contract mismatch")
        assertFalse(store.state.value.starting)
        // The agent's computer changed: the page reloads it (web `loadAgents()`).
        assertEquals(AgentMigrationEffect.AgentPlacementChanged, store.state.value.effect)
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
    fun dismissPostsTheShownRevisionAndTheClosedMoveReadsAsNone() = runTest {
        val network = ScriptedAgentControlsNetworkClient()
        val dismissPath = "agents/${agentId.value}/migration/dismiss"
        network.respond("GET", statusPath, 200, notice("failed", revision = 4))
        network.respond("GET", statusPath, 200, notice("failed", revision = 5, extra = ""","dismissedAt":"2026-11-22T10:00:00.000Z""""))
        network.respond("POST", dismissPath, 200, notice("failed", revision = 5))
        val store = AgentMigrationStore(RaftAgentControlsApi(network), backgroundScope)
        store.dispatch(AgentMigrationIntent.Watch(agentId, checkPlan = false))
        runCurrent()
        assertFalse(store.state.value.notice!!.isDismissed())

        store.dispatch(AgentMigrationIntent.Dismiss)
        runCurrent()

        val body = Json.parseToJsonElement(network.requests.single { it.path == dismissPath }.bodyText!!).jsonObject
        assertEquals(migrateRef, body.getValue("migrationRef").jsonPrimitive.content)
        assertEquals(4, body.getValue("expectedRevision").jsonPrimitive.int)
        assertNull(store.state.value.dismissError)
        assertTrue(store.state.value.notice!!.isDismissed())

        // Closed already: nothing to send.
        store.dispatch(AgentMigrationIntent.Dismiss)
        runCurrent()
        assertEquals(1, network.requests.count { it.path == dismissPath })
    }

    @Test
    fun aRefusedDismissSaysWhyAndReadsAgain() = runTest {
        val network = ScriptedAgentControlsNetworkClient()
        val dismissPath = "agents/${agentId.value}/migration/dismiss"
        network.respond("GET", statusPath, 200, notice("failed", revision = 4))
        network.respond("GET", statusPath, 200, notice("provisioning", revision = 0))
        network.respond("POST", dismissPath, 409, """{"error":"A newer migration started","code":"MIGRATION_SUPERSEDED"}""")
        val store = AgentMigrationStore(RaftAgentControlsApi(network), backgroundScope)
        store.dispatch(AgentMigrationIntent.Watch(agentId, checkPlan = false))
        runCurrent()

        store.dispatch(AgentMigrationIntent.Dismiss)
        runCurrent()

        assertEquals("MIGRATION_SUPERSEDED", store.state.value.dismissError?.code)
        assertFalse(store.state.value.dismissing)
        assertEquals("provisioning", store.state.value.notice?.state)
    }

    @Test
    fun onlyAFailedMoveIsDismissed() = runTest {
        val network = ScriptedAgentControlsNetworkClient()
        network.respond("GET", statusPath, 200, notice("completed", extra = ""","dismissedAt":"2026-11-22T10:00:00.000Z""""))
        val store = AgentMigrationStore(RaftAgentControlsApi(network), backgroundScope)
        store.dispatch(AgentMigrationIntent.Watch(agentId, checkPlan = false))
        runCurrent()

        assertFalse(store.state.value.notice!!.isDismissed())
        store.dispatch(AgentMigrationIntent.Dismiss)
        runCurrent()
        assertTrue(network.requests.none { it.method == "POST" })
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
}
