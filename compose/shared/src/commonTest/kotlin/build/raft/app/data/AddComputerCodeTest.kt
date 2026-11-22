package build.raft.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Add Computer's terminal code, the parts that need no network: what a typed code becomes, what
 * describe's answer reads as, which answers this sheet approves (web botiverse/slock 329e1700f7
 * `features/computer/addComputerCode.ts`, `components/auth/deviceLoginApproval.ts`) and how each
 * answer moves the field (`useDeviceLoginApproval.ts`). Payloads are the server's, botiverse/slock
 * staging 23ae82825 `packages/server/src/routes/deviceAuth.ts` describe (the shape the web's
 * `tests/computerAddByCode.behavior.test.tsx` fixtures copy).
 */
class AddComputerCodeTest {
    @Test
    fun aTypedCodeIsTrimmedAndUpperCasedTheServerFoldsTheRest() {
        assertEquals("VSWA-7M58", normalizeDeviceLoginUserCode(" vswa-7m58 "))
        // Dashes, inner spaces and look-alikes are the server's (deviceAuthService normalizeUserCode).
        assertEquals("VSWA 7M58", normalizeDeviceLoginUserCode("vswa 7m58"))
        assertEquals("", normalizeDeviceLoginUserCode(" \t "))

        assertFalse(AddComputerCodeState(code = "  ").canSubmit, "nothing typed: Continue stays off")
        assertTrue(AddComputerCodeState(code = "vswa").canSubmit)
        assertFalse(AddComputerCodeState(code = "vswa", submitting = AddComputerCodeSubmitting.Describe).canSubmit)
    }

    @Test
    fun describeReadsTheServersShape() {
        val attach = assertNotNull(parseSharedDeviceLoginRequest(deviceCodeDescribeBody(grant = DEVICE_CODE_ATTACH_GRANT)))
        assertEquals("VSWA-7M58", attach.userCode)
        assertEquals("raft-computer", attach.clientName)
        assertEquals("2026-11-21T10:00:00.000Z", attach.requestedAt)
        assertEquals("203.0.113.7", attach.requesterIp)
        assertEquals("São Paulo, BR", attach.requesterLocation)
        assertEquals("ticket-1", attach.approvalTicket)
        val grant = assertNotNull(attach.grant)
        assertEquals(DeviceLoginGrantKind.ComputerAttach, grant.kind)
        assertEquals("build-box", grant.machineName)
        assertEquals("build-box.lan", grant.hostname)
        assertEquals("Linux", grant.os)
        assertEquals(
            listOf(DeviceLoginServer("server-2", "beta", "Beta Labs"), DeviceLoginServer("server-1", "slock", "Slock Community")),
            grant.servers,
        )

        // A sign-in has no grant; an agent key lists agents, not servers.
        assertNull(assertNotNull(parseSharedDeviceLoginRequest(deviceCodeDescribeBody())).grant)
        val key = assertNotNull(parseSharedDeviceLoginRequest(deviceCodeDescribeBody(grant = DEVICE_CODE_AGENT_KEY_GRANT))).grant
        assertEquals(DeviceLoginGrantKind.AgentKey, key?.kind)
        assertEquals(emptyList(), key?.servers)

        // A kind this app doesn't know stays visible as sent, so it can be refused (never dropped).
        val unknown = assertNotNull(parseSharedDeviceLoginRequest(deviceCodeDescribeBody(grant = DEVICE_CODE_UNKNOWN_GRANT))).grant
        assertNull(unknown?.kind)
        assertEquals("computer_adopt", unknown?.kindWireName)

        // Empty and null facts read as none (web `filter(Boolean)`, `name ? … : notGiven`).
        val bare =
            assertNotNull(
                parseSharedDeviceLoginRequest(
                    """{"userCode":"VSWA-7M58","clientName":null,"requestedAt":"2026-11-21T10:00:00.000Z","requester":{"ip":null,"location":""},"approvalTicket":"t","grant":{"kind":"computer_attach","machineName":"","hostname":null,"os":"","servers":[]}}"""
                )
            )
        assertNull(bare.clientName)
        assertNull(bare.requesterIp)
        assertNull(bare.requesterLocation)
        assertNull(bare.grant?.machineName)
        assertNull(bare.grant?.os)
    }

    @Test
    fun describeAnswersThatAreNotTheShapeAreRefused() {
        listOf(
            """{"userCode":"VSWA-7M58","requestedAt":"2026-11-21T10:00:00.000Z","requester":{"ip":null,"location":null}}""",
            """{"requestedAt":"2026-11-21T10:00:00.000Z","approvalTicket":"t"}""",
            """{"request":{"userCode":"VSWA-7M58","approvalTicket":"t"}}""",
            "not json",
            "",
        ).forEach { body -> assertNull(parseSharedDeviceLoginRequest(body), body) }
    }

    @Test
    fun onlyAComputerCodeListingThisServerIsApprovedHere() {
        val attach = deviceCodeRequest(DEVICE_CODE_ATTACH_GRANT)
        val cases =
            listOf(
                Triple("attach, this server listed", attach to "server-1", "server-1"),
                Triple("migrate, this server listed", deviceCodeRequest(DEVICE_CODE_MIGRATE_GRANT) to "server-1", "server-1"),
                Triple("attach, only another server listed", attach to "server-3", null),
                Triple("a sign-in", deviceCodeRequest(grant = null) to "server-1", null),
                Triple("an agent key", deviceCodeRequest(DEVICE_CODE_AGENT_KEY_GRANT) to "server-1", null),
                Triple("an unknown kind", deviceCodeRequest(DEVICE_CODE_UNKNOWN_GRANT) to "server-1", null),
                Triple("no server", attach to null, null),
            )
        cases.forEach { (label, input, expected) ->
            assertEquals(expected, input.first.addComputerTarget(input.second)?.id, label)
        }
    }

    @Test
    fun theDeviceLoginPageIsOfferedOnlyWhereItCanStillHelp() {
        listOf("grant_kind_unsupported", "grant_selection_not_allowed", "grant_selection_required").forEach { code ->
            assertTrue(addComputerCodeOffersDevicePage(code), code)
        }
        listOf("expired", "user_code_invalid", "already_resolved", SECOND_FACTOR_STEP_UP_REQUIRED_CODE, null).forEach { code ->
            assertFalse(addComputerCodeOffersDevicePage(code), "$code")
        }
        // Never with the code in it: the page asks for the code again.
        assertEquals("https://app.raft.build/login/device", deviceLoginWebUrl(null))
        assertEquals("https://raft-app-staging.botiverse.dev/login/device", deviceLoginWebUrl("https://raft-app-staging.botiverse.dev/"))
    }

    @Test
    fun theCodeGoesInTheBodyAndOnlyAnApprovalCarriesTheTicketAndServer() {
        val describe = json(buildDeviceLoginDescribeBody("VSWA-7M58"))
        assertEquals(setOf("userCode"), describe.keys)
        assertEquals("VSWA-7M58", describe.getValue("userCode").jsonPrimitive.content)

        val request = deviceCodeRequest(DEVICE_CODE_ATTACH_GRANT)
        val approve = json(buildDeviceLoginApproveBody(request, serverId = "server-1"))
        assertEquals(setOf("userCode", "approve", "approvalTicket", "serverId"), approve.keys)
        assertEquals("VSWA-7M58", approve.getValue("userCode").jsonPrimitive.content)
        assertTrue(approve.getValue("approve").jsonPrimitive.boolean)
        assertEquals("ticket-1", approve.getValue("approvalTicket").jsonPrimitive.content)
        assertEquals("server-1", approve.getValue("serverId").jsonPrimitive.content)

        val deny = json(buildDeviceLoginApproveBody(request, serverId = null))
        assertEquals(setOf("userCode", "approve"), deny.keys)
        assertFalse(deny.getValue("approve").jsonPrimitive.boolean)
    }

    @Test
    fun describeAnswersMoveTheField() {
        val typed = AddComputerCodeState(open = true, code = " vswa-7m58 ").describing(token = 7)
        assertEquals(AddComputerCodeSubmitting.Describe, typed.submitting)

        val confirm = assertIs<AddComputerCodeStep.Confirm>(typed.described(DataResult.Content(deviceCodeRequest(DEVICE_CODE_ATTACH_GRANT)), "server-1").step)
        assertEquals("server-1", confirm.target?.id)
        assertNull(assertIs<AddComputerCodeStep.Confirm>(typed.described(DataResult.Content(deviceCodeRequest(grant = null)), "server-1").step).target)

        val unknown = typed.described(DataResult.Content(deviceCodeRequest(DEVICE_CODE_UNKNOWN_GRANT)), "server-1")
        assertEquals(AddComputerCodeStep.Enter, unknown.step)
        assertEquals(AddComputerCodeProblem.KindUnsupported, unknown.error?.problem)
        assertTrue(unknown.error?.offersDevicePage == true)
        assertNull(unknown.error?.traceId, "refused here, not by the server")

        listOf(
            "user_code_invalid" to AddComputerCodeProblem.CodeInvalid,
            "expired" to AddComputerCodeProblem.CodeExpired,
            "already_resolved" to AddComputerCodeProblem.CodeAlreadyUsed,
            "device_login_disabled" to AddComputerCodeProblem.DescribeFailed,
        ).forEach { (code, problem) ->
            val failed = typed.described(refusal(code, "Device authorization can't be approved"), "server-1")
            assertEquals(AddComputerCodeStep.Enter, failed.step, code)
            assertNull(failed.submitting, code)
            assertEquals(problem, failed.error?.problem, code)
            assertEquals(TRACE, failed.error?.traceId, code)
            // The field keeps what was typed, open.
            assertEquals(" vswa-7m58 ", failed.code, code)
            assertTrue(failed.open, code)
        }
        // The auth limiter's 429 has no code: the server's own words.
        val limited = typed.described(refusal(code = null, message = "Too many requests, please try again later"), "server-1").error
        assertEquals(AddComputerCodeProblem.DescribeFailed, limited?.problem)
        assertEquals("Too many requests, please try again later", limited?.serverMessage)
    }

    @Test
    fun approveAndDenyAnswersMoveTheConfirmScreen() {
        val request = deviceCodeRequest(DEVICE_CODE_ATTACH_GRANT)
        val target = assertNotNull(request.addComputerTarget("server-1"))
        val confirming = AddComputerCodeState(open = true, code = "vswa-7m58", step = AddComputerCodeStep.Confirm(request, target))
        val approving = confirming.resolving(token = 3, approve = true)
        assertEquals(AddComputerCodeSubmitting.Approve, approving.submitting)

        val approved = assertIs<AddComputerCodeStep.Approved>(approving.resolved(DataResult.Content(Unit), approve = true, request, target).step)
        assertEquals(AddComputerCodeOutcome(migrate = false, machineName = "build-box", serverName = "Slock Community"), approved.outcome)
        val migrate = deviceCodeRequest(DEVICE_CODE_MIGRATE_GRANT)
        assertTrue(assertIs<AddComputerCodeStep.Approved>(approving.resolved(DataResult.Content(Unit), approve = true, migrate, target).step).outcome.migrate)
        assertEquals(
            AddComputerCodeStep.Denied("Slock Community"),
            confirming.resolving(token = 4, approve = false).resolved(DataResult.Content(Unit), approve = false, request, target).step,
        )

        // Still pending: the confirm screen stays, with why.
        listOf(
            "grant_selection_not_allowed" to AddComputerCodeProblem.ServerSelectionNotAllowed,
            "grant_selection_required" to AddComputerCodeProblem.ServerSelectionRequired,
            SECOND_FACTOR_STEP_UP_REQUIRED_CODE to AddComputerCodeProblem.StepUpRequired,
        ).forEach { (code, problem) ->
            val kept = approving.resolved(refusal(code, "refused"), approve = true, request, target)
            assertIs<AddComputerCodeStep.Confirm>(kept.step, code)
            assertNull(kept.submitting, code)
            assertEquals(problem, kept.error?.problem, code)
        }
        // Gone (expired, used, anything else): back to the field.
        listOf(
            Triple("expired", true, AddComputerCodeProblem.CodeExpired),
            Triple("already_resolved", true, AddComputerCodeProblem.CodeAlreadyUsed),
            Triple("grant_kind_unsupported", true, AddComputerCodeProblem.KindUnsupported),
            Triple(null, true, AddComputerCodeProblem.ApproveFailed),
            Triple(null, false, AddComputerCodeProblem.DenyFailed),
        ).forEach { (code, approve, problem) ->
            val gone = confirming.resolving(token = 5, approve).resolved(refusal(code, "Device authorization could not be updated"), approve, request, target)
            assertEquals(AddComputerCodeStep.Enter, gone.step, "$code/$approve")
            assertEquals(problem, gone.error?.problem, "$code/$approve")
        }

        // Verify it's you closed: the confirm screen as it was, nothing said.
        val closed = approving.stepUpClosed()
        assertIs<AddComputerCodeStep.Confirm>(closed.step)
        assertNull(closed.submitting)
        assertNull(closed.error)
    }

    @Test
    fun enterADifferentCodeStartsOverButNotWhileACallIsInFlight() {
        val request = deviceCodeRequest(DEVICE_CODE_ATTACH_GRANT)
        val confirming = AddComputerCodeState(code = "vswa-7m58", step = AddComputerCodeStep.Confirm(request, request.addComputerTarget("server-1")))
        assertEquals(AddComputerCodeState(open = true, code = "", step = AddComputerCodeStep.Enter), confirming.differentCode())
        val inFlight = confirming.resolving(token = 9, approve = true)
        assertSame(inFlight, inFlight.differentCode())
    }

    private fun json(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    private fun refusal(code: String?, message: String): DataResult<Nothing> =
        DataResult.Error(ScreenError(code = code ?: "http_429", retryable = false, serverMessage = message, serverCode = code, traceId = TRACE))

    private companion object {
        const val TRACE = "4bf92f3577b34da6a3ce929d0e0e4736"
    }
}

/** This sheet's server is the sample session's (`settingsStoreSampleSession`: server-1, "slock"). */
internal const val DEVICE_CODE_THIS_SERVER = """{"id":"server-1","slug":"slock","name":"Slock Community"}"""
internal const val DEVICE_CODE_OTHER_SERVER = """{"id":"server-2","slug":"beta","name":"Beta Labs"}"""
internal const val DEVICE_CODE_ATTACH_GRANT =
    """{"kind":"computer_attach","machineName":"build-box","hostname":"build-box.lan","os":"Linux","servers":[$DEVICE_CODE_OTHER_SERVER,$DEVICE_CODE_THIS_SERVER]}"""
internal const val DEVICE_CODE_MIGRATE_GRANT =
    """{"kind":"computer_migrate","machineName":"old-box","hostname":"old-box.lan","os":"macOS","servers":[$DEVICE_CODE_THIS_SERVER]}"""
internal const val DEVICE_CODE_OTHER_SERVER_GRANT =
    """{"kind":"computer_attach","machineName":"build-box","hostname":"build-box.lan","os":"Linux","servers":[$DEVICE_CODE_OTHER_SERVER]}"""
internal const val DEVICE_CODE_AGENT_KEY_GRANT =
    """{"kind":"agent_key","machineName":"ci","hostname":null,"os":null,"requestedAgentId":null,"agents":[]}"""
internal const val DEVICE_CODE_UNKNOWN_GRANT = """{"kind":"computer_adopt","machineName":"old","hostname":null,"os":null}"""

/** describe's 200 (routes/deviceAuth.ts): device mode, the stored code, a ticket, the grant when there is one. */
internal fun deviceCodeDescribeBody(grant: String? = null, userCode: String = "VSWA-7M58", ticket: String = "ticket-1"): String {
    val grantField = grant?.let { ",\"grant\":$it" }.orEmpty()
    return "{\"userCode\":\"$userCode\",\"clientName\":\"raft-computer\",\"requestedAt\":\"2026-11-21T10:00:00.000Z\"," +
        "\"requester\":{\"ip\":\"203.0.113.7\",\"location\":\"São Paulo, BR\"},\"approvalTicket\":\"$ticket\"$grantField}"
}

internal fun deviceCodeRequest(grant: String?): DeviceLoginRequest = assertNotNull(parseSharedDeviceLoginRequest(deviceCodeDescribeBody(grant = grant)))
