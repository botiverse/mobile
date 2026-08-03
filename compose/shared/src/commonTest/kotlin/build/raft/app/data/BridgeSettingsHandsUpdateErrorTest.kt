package build.raft.app.data

import build.raft.app.appUpdateErrorMessage
import build.raft.app.englishErrorPresentationStringsForTest
import kotlin.test.Test
import kotlin.test.assertEquals

class BridgeSettingsHandsUpdateErrorTest {
    @Test
    fun handsUpdateBridgeErrorsPreserveOnlyStructuredMessageField() {
        val fixtures =
            listOf(
                "structured" to
                    ("""{"status":"error","code":"hands_service_busy","message":"Hands update service is restarting"}""" to
                        "Hands update service is restarting"),
                "malformed" to ("""{"status":"error","message":"unterminated""" to null),
                "plain-text" to ("Hands update service is restarting" to null),
                "empty" to ("" to null)
            )

        fixtures.forEach { (name, fixture) ->
            val (body, expectedMessage) = fixture
            val error = handsUpdateBridgeError(body)

            assertEquals(
                if (name == "structured") "hands_service_busy" else "settings_hands_update_failed",
                error.code,
                name
            )
            assertEquals(expectedMessage, error.serverMessage, name)
        }
    }

    @Test
    fun handsUpdateBridgeDoesNotGuessServerErrorAlias() {
        val error =
            handsUpdateBridgeError(
                """{"status":"error","code":"hands_service_busy","error":"Unrecognized alias"}"""
            )

        assertEquals("hands_service_busy", error.code)
        assertEquals(null, error.serverMessage)
    }

    @Test
    fun handsUpdateBridgeKeepsNonErrorStatusCodeWithoutTrustingItsMessage() {
        val error =
            handsUpdateBridgeError(
                """{"status":"unsupported","code":"hands_update_unsupported","message":"Not an error envelope"}"""
            )

        assertEquals("hands_update_unsupported", error.code)
        assertEquals(null, error.serverMessage)
    }

    @Test
    fun handsUpdateStructuredMessageReachesAppUpdateVisibleCopy() {
        val result =
            DataResult.Error(
                handsUpdateBridgeError(
                    """{"status":"error","code":"hands_service_busy","message":"Hands update service is restarting"}"""
                )
            )

        assertEquals(
            "Hands update service is restarting",
            result.appUpdateErrorMessage("Updates are unavailable", englishErrorPresentationStringsForTest())
        )
    }
}
