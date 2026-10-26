package build.raft.app.desktop

import build.raft.app.toRaftJsonString
import java.awt.Desktop
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `settingsCapability`: the action-multiplexed settings host. M1 serves the browser sign-in
 * (`appLoginPrepare` / `appLoginOpen`, the Android host's contract); the callback comes back as
 * `raft://login/callback` (see [RaftDesktopLoginCallbacks]). TODO(desktop): every other action
 * (storage, about, developer environment, diagnostics, notifications, account linking) answers
 * "not implemented" and the shared pages fall back as for an unsupported host action.
 */
object RaftDesktopSettingsCapability {
    /** One of the server's allowed app-login return URIs (exact match). */
    const val APP_LOGIN_RETURN_URI = "raft://login/callback"

    private val random = SecureRandom()

    fun handle(command: JsonObject, callback: ((String?) -> Unit)?) {
        val action = command.string("action")
        val result =
            when (action) {
                "appLoginPrepare" -> prepareAppLogin()
                "appLoginOpen" -> openAppLogin(command.string("authorizationUrl"))
                else -> {
                    log("settingsCapability not implemented: action=$action")
                    callback?.invoke(RaftDesktopBridgeTransport.NOT_IMPLEMENTED)
                    return
                }
            }
        callback?.invoke(JsonObject(result.mapValues { JsonPrimitive(it.value) }).toRaftJsonString())
    }

    private fun prepareAppLogin(): Map<String, String> {
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        val challenge =
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        return mapOf(
            "status" to "prepared",
            "generation" to UUID.randomUUID().toString(),
            "codeVerifier" to verifier,
            "codeChallenge" to challenge,
            "returnUri" to APP_LOGIN_RETURN_URI,
        )
    }

    private fun openAppLogin(authorizationUrl: String): Map<String, String> {
        if (!authorizationUrl.startsWith("https://")) {
            return mapOf("status" to "error", "code" to "settings_mobile_oauth_url_invalid")
        }
        log("app login: opening the browser for $authorizationUrl")
        return runCatching { Desktop.getDesktop().browse(URI(authorizationUrl)) }
            .fold(
                onSuccess = { mapOf("status" to "started") },
                onFailure = {
                    log("app login: no browser (${it.message}); open the URL above manually")
                    // The URL is logged, so a developer can still finish the flow by hand.
                    mapOf("status" to "started")
                },
            )
    }
}
