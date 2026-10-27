package build.raft.app.data

import build.raft.app.model.MemberId
import build.raft.app.model.MessageId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * POST /api/actions/:messageId/execute from a spec card (A4): the `form` body and the contract's
 * form refusals (botiverse/slock routes/actions.ts `handleError`, shared actionCardSpec.ts
 * `ActionCardFormInvalidBody` / `ActionCardFormRequiredBody` / `ActionCardAlreadyExecutedBody`).
 */
class ActionCardSpecExecuteApiTest {
    private class ExecuteTransport(private val response: HomeNetworkResponse) : RaftIntegrationsActionsPushApi {
        val bodies = mutableListOf<String>()

        override suspend fun executeAction(messageId: MessageId, body: String): HomeNetworkResponse {
            bodies += body
            return response
        }

        private fun unused(): Nothing = error("not used")
        override suspend fun integrationsOverview() = unused()
        override suspend fun integrationClients() = unused()
        override suspend fun createIntegrationClient(body: String) = unused()
        override suspend fun approveIntegrationRequest(requestId: String, body: String) = unused()
        override suspend fun denyIntegrationRequest(requestId: String) = unused()
        override suspend fun revokeIntegrationGrant(grantId: String) = unused()
        override suspend fun agentIntegrations(agentId: MemberId) = unused()
        override suspend fun emitActionEvent(messageId: MessageId, body: String) = unused()
        override suspend fun markActionExecuted(messageId: MessageId, body: String) = unused()
        override suspend fun reconfirmAction(messageId: MessageId) = unused()
        override suspend fun pushVapidKey() = unused()
        override suspend fun recordPushPromptEvent(body: String) = unused()
        override suspend fun subscribePush(body: String) = unused()
        override suspend fun unsubscribePush(body: String) = unused()
        override suspend fun testPush() = unused()
    }

    private suspend fun execute(code: Int, body: String, headers: Map<String, List<String>> = emptyMap()): DataResult<ActionCardExecuteOutcome> {
        val response =
            if (code in 200..299) {
                HomeNetworkResponse(code, bodyText = body, responseHeaders = headers)
            } else {
                HomeNetworkResponse(code, bodyText = null, errorBodyText = body, responseHeaders = headers)
            }
        return RaftIntegrationsActionsPushApiClient(ExecuteTransport(response))
            .executeActionCardSpec(MessageId("m-1"), buildExecuteActionBody(expectedState = "prepared", expectedConfirmationVersion = 2))
    }

    private val executedMetadata =
        """{"kind":"action-card","state":"executed","executedByUserName":"Ada","confirmationVersion":3,"action":{"type":"feedback:submit"}}"""

    @Test
    fun aPlainExecuteSendsNoFormAndAFormExecuteSendsItsTypedValues() {
        val plain = Json.parseToJsonElement(buildExecuteActionBody(expectedState = "prepared", expectedConfirmationVersion = 2)).jsonObject
        assertFalse("form" in plain)
        assertEquals(2, plain["expectedConfirmationVersion"]?.jsonPrimitive?.content?.toInt())

        val withForm =
            Json.parseToJsonElement(
                buildExecuteActionBody(
                    expectedState = "prepared",
                    expectedConfirmationVersion = 2,
                    form =
                        ExecuteActionFormBody(
                            id = "feedback",
                            values =
                                mapOf(
                                    "message" to ActionCardFormValue.Text("Hi"),
                                    "urgent" to ActionCardFormValue.Toggle(true),
                                    "people" to ActionCardFormValue.Ids(listOf("a", "b")),
                                ),
                        ),
                ),
            ).jsonObject
        val form = withForm.getValue("form").jsonObject
        assertEquals("feedback", form.getValue("id").jsonPrimitive.content)
        val values = form.getValue("values").jsonObject
        assertEquals("Hi", values.getValue("message").jsonPrimitive.content)
        assertEquals(true, values.getValue("urgent").jsonPrimitive.content.toBooleanStrict())
        assertEquals(listOf("a", "b"), (values.getValue("people") as JsonArray).map { it.jsonPrimitive.content })
    }

    @Test
    fun theFormRefusalsComeBackTyped() = runTest {
        val invalid =
            assertIs<ActionCardExecuteOutcome.FormInvalid>(
                assertIs<DataResult.Content<ActionCardExecuteOutcome>>(
                    execute(
                        422,
                        """{"error":"Invalid form","errorCode":"FORM_INVALID","issues":[
                          {"field":"message","code":"too_long","message":{"key":"actionCard.form.issue.too_long","text":{"en":"Use 5 characters or fewer."}}},
                          {"field":null,"code":"brand_new_code","message":{"bad":true}}]}""",
                    ),
                ).data,
            )
        assertEquals("Invalid form", invalid.serverMessage)
        assertEquals(
            listOf("message" to ActionCardFormIssueCode.TooLong, null to ActionCardFormIssueCode.InvalidFormat),
            invalid.issues.map { it.field to it.code },
        )
        assertEquals("Use 5 characters or fewer.", renderActionCardMsgText(invalid.issues[0].message, "en"))
        // An unknown code and an unreadable message keep the issue with the generic words.
        assertEquals(ACTION_CARD_FORM_ISSUE_MESSAGES.getValue(ActionCardFormIssueCode.InvalidFormat), invalid.issues[1].message)

        val required = assertIs<DataResult.Content<ActionCardExecuteOutcome>>(execute(422, """{"error":"Form required","errorCode":"FORM_REQUIRED"}""")).data
        assertEquals(ActionCardExecuteOutcome.FormRequired("Form required"), required)

        val already =
            assertIs<ActionCardExecuteOutcome.AlreadyExecuted>(
                assertIs<DataResult.Content<ActionCardExecuteOutcome>>(
                    execute(409, """{"error":"Already executed","errorCode":"ALREADY_EXECUTED","metadata":$executedMetadata}"""),
                ).data,
            )
        assertEquals("executed" to "Ada", already.card?.state to already.card?.executedByUserName)

        val executed =
            assertIs<ActionCardExecuteOutcome.Executed>(
                assertIs<DataResult.Content<ActionCardExecuteOutcome>>(execute(200, """{"messageId":"m-1","metadata":$executedMetadata}""")).data,
            )
        assertEquals(3, executed.card?.confirmationVersion)
    }

    @Test
    fun otherRefusalsStayErrorsWithTheServersWords() = runTest {
        for ((code, body) in listOf(
            422 to """{"error":"Nope","errorCode":"SOMETHING_ELSE"}""",
            409 to """{"error":"Nope","errorCode":"CONFIRMATION_VERSION_MISMATCH"}""",
            403 to """{"error":"Nope","errorCode":"FORM_INVALID","issues":[]}""",
        )) {
            val error = assertIs<DataResult.Error>(execute(code, body), "$code").error
            assertEquals("Nope", error.serverMessage, "$code")
        }
        assertNull((execute(500, "oops") as? DataResult.Content)?.data)
    }

    /**
     * botiverse/slock 7799cc436: a feedback card's form over the per-person feedback limit gets the
     * limiter's 429 `{ error, code: "feedback_rate_limited" }` plus `Retry-After`; the server may
     * also (or instead) name it `errorCode`. Every shape reads as the same server code.
     */
    @Test
    fun aFeedbackRateLimit429ReadsItsCodeFromCodeOrErrorCodeAndItsRetryAfter() = runTest {
        val message = "Too many feedback submissions. Please try again later."
        for (body in listOf(
            """{"error":"$message","code":"feedback_rate_limited"}""",
            """{"error":"$message","errorCode":"feedback_rate_limited"}""",
            """{"error":"$message","errorCode":"feedback_rate_limited","code":"feedback_rate_limited"}""",
        )) {
            val error = assertIs<DataResult.Error>(execute(429, body, mapOf("retry-after" to listOf("1800"))), body).error
            assertEquals("feedback_rate_limited", error.serverCode, body)
            assertEquals(message, error.serverMessage, body)
            assertEquals(1800L, error.retryAfterSeconds, body)
            assertFalse(error.retryable, body)
        }
        val noHeader = assertIs<DataResult.Error>(execute(429, """{"error":"$message","code":"feedback_rate_limited"}""")).error
        assertNull(noHeader.retryAfterSeconds)
        // errorCode wins when both are there and differ; a non-429 never carries a wait.
        val both = assertIs<DataResult.Error>(execute(409, """{"error":"x","errorCode":"A","code":"B"}""", mapOf("Retry-After" to listOf("5")))).error
        assertEquals("A" to null, both.serverCode to both.retryAfterSeconds)
    }
}
