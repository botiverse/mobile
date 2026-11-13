package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Prepare and execute of a block card's buttons (RFC 076 sections 6.2, 6.3). */
class AppLinkCardActionsTest {
    @Test
    fun requestBodiesHaveOnlyTheContractKeys() {
        assertEquals(
            """{"handle":"h","claimed":{"business_revision":"br-1"}}""",
            AppLinkPrepareRequestBody("h", AppLinkPrepareClaim("br-1")).toJson(),
        )
        assertEquals(
            """{"client_request_id":"mobile-1","handle":"h"}""",
            AppLinkExecuteRequestBody("mobile-1", "h").toJson(),
        )
        assertEquals(
            """{"client_request_id":"mobile-1","handle":"h","prepared_action_id":"p","prepared_digest":"d"}""",
            AppLinkExecuteRequestBody("mobile-1", "h", "p", "d").toJson(),
        )
    }

    @Test
    fun theClientRequestIdFitsTheServersPattern() {
        val id = appLinkClientRequestId("0b5a1f8e-6d0c-4c3e-9a51-0e1b2c3d4e01")
        assertTrue(Regex("^[A-Za-z0-9._~:-]{16,128}$").matches(id))
    }

    @Test
    fun aPreparedDescriptionIsRead() {
        val body =
            """{"prepared_action_id":"7d0e8b3a-0000-4000-8000-000000000001","prepared_digest":"ab","description":{
              "prepared_action_id":"7d0e8b3a-0000-4000-8000-000000000001","title":"Merge #12361?","body":"Squash into main.",
              "rows":[{"label":"Branch","value":"agent/x"},{"label":"Checks","value":"All passing"}],"fine_print":"Cannot be undone.",
              "confirm_label":"Merge","cancel_label":"Cancel"}}"""
        val ok = parseAppLinkPrepareAnswer(200, body) as AppLinkActionResult.Ok
        assertEquals("7d0e8b3a-0000-4000-8000-000000000001", ok.value.preparedActionId)
        assertEquals("ab", ok.value.preparedDigest)
        val description = ok.value.description
        assertEquals("Merge #12361?", description.title)
        assertEquals("Squash into main.", description.body)
        assertEquals(listOf("Branch" to "agent/x", "Checks" to "All passing"), description.rows)
        assertEquals("Cannot be undone.", description.finePrint)
        assertEquals("Merge", description.confirmLabel)
        assertEquals("Cancel", description.cancelLabel)
    }

    @Test
    fun anExecuteAnswerCarriesTheStateAndOnlyRaftsHint() {
        val ok = parseAppLinkExecuteAnswer(200, """{"operation_id":"op-1","state":"accepted","toast":{"tone":"info","text":"Sent to Stamp."}}""") as AppLinkActionResult.Ok
        assertEquals(AppLinkExecuted("op-1", "accepted", "Sent to Stamp.", tone = "info"), ok.value)
        assertTrue(ok.value.isOpen)
        val done = parseAppLinkExecuteAnswer(200, """{"operation_id":"op-1","state":"done"}""") as AppLinkActionResult.Ok
        assertTrue(!done.value.isOpen)
    }

    @Test
    fun aRefusalIsTheServersWordsAndA410IsOutOfDate() {
        val stale = parseAppLinkExecuteAnswer(410, """{"error":"This card is out of date. Refresh it and try again.","code":"HANDLE_INVALID"}""") as AppLinkActionResult.Failed
        assertEquals("This card is out of date. Refresh it and try again.", stale.message)
        assertEquals("HANDLE_INVALID", stale.code)
        assertTrue(stale.outOfDate)
        val busy = parseAppLinkPrepareAnswer(429, """{"error":"Too many card actions.","code":"RATE_LIMITED"}""") as AppLinkActionResult.Failed
        assertTrue(!busy.outOfDate)
        assertEquals(null, (parseAppLinkPrepareAnswer(502, null) as AppLinkActionResult.Failed).message)
        assertTrue(parseAppLinkPrepareAnswer(200, """{"prepared_action_id":"p"}""") is AppLinkActionResult.Failed)
        assertTrue(parseAppLinkExecuteAnswer(200, "not json") is AppLinkActionResult.Failed)
    }
}
