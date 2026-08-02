package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsRouteErrorContractTest {
    @Test
    fun settingsRouteFamiliesAcceptOnlyTheirCanonicalErrorField() {
        val canonical = "The server rejected this settings change"
        val acceptedBodies =
            listOf(
                "canonical" to """{"error":"$canonical"}""",
                "future-fields" to
                    """{"error":"$canonical","future":{"retryAfter":30},"code":"policy_rejected"}"""
            )

        settingsRouteErrorDecoders().forEach { family ->
            acceptedBodies.forEach { (fixture, body) ->
                assertEquals(
                    canonical,
                    family.decode(HomeNetworkResponse(code = 409, bodyText = null, errorBodyText = body)),
                    "${family.name}:$fixture"
                )
            }
        }
    }

    @Test
    fun settingsRouteFamiliesRejectAliasesWrongTypesAndNonJsonBodies() {
        val rejectedBodies =
            listOf(
                "wrong-alias" to """{"message":"must not be guessed"}""",
                "wrong-type" to """{"error":409}""",
                "nested-error" to """{"error":{"message":"must not be guessed"}}""",
                "malformed" to """{"error":"unterminated"""",
                "plain-text" to "must not be guessed",
                "blank" to "",
                "missing" to null
            )

        settingsRouteErrorDecoders().forEach { family ->
            rejectedBodies.forEach { (fixture, body) ->
                assertEquals(
                    null,
                    family.decode(HomeNetworkResponse(code = 409, bodyText = body)),
                    "${family.name}:$fixture"
                )
            }
        }
    }
}

private data class SettingsRouteErrorDecoder(
    val name: String,
    val decode: (HomeNetworkResponse) -> String?
)

private fun settingsRouteErrorDecoders(): List<SettingsRouteErrorDecoder> =
    listOf(
        SettingsRouteErrorDecoder("account", HomeNetworkResponse::settingsAccountRouteErrorTextOrNull),
        SettingsRouteErrorDecoder("connected-apps", HomeNetworkResponse::settingsConnectedAppsRouteErrorTextOrNull),
        SettingsRouteErrorDecoder("server-section", HomeNetworkResponse::settingsServerSectionRouteErrorTextOrNull),
        SettingsRouteErrorDecoder("billing", HomeNetworkResponse::settingsBillingRouteErrorTextOrNull),
        SettingsRouteErrorDecoder("server-action", HomeNetworkResponse::settingsServerActionRouteErrorTextOrNull),
        SettingsRouteErrorDecoder("server-notification", HomeNetworkResponse::settingsServerNotificationRouteErrorTextOrNull)
    )
