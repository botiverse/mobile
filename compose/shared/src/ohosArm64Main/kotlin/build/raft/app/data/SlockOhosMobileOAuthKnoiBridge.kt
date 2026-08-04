package build.raft.app.data

import com.tencent.tmm.knoi.annotation.KNExport

/**
 * OHOS native -> shared OAuth callback ingress. ArkTS admits only the exact
 * build-bound Raft callback route and passes identity/intent fields; the
 * persisted shared pending request remains authoritative for provider, mode,
 * PKCE and API environment.
 */
@KNExport
fun slockOhosMobileOAuthCompleteCallback(
    scheme: String,
    host: String,
    path: String,
    provider: String,
    mode: String,
    code: String,
    error: String,
): Boolean {
    if (
        scheme !in setOf("raft", "raft-debug") ||
        host != "oauth" ||
        path != "/callback" ||
        (code.isBlank() && error.isBlank())
    ) {
        return false
    }
    SlockIosMobileOAuthBridge.completeCallback(
        scheme = scheme,
        host = host,
        path = path,
        provider = provider.ifBlank { null },
        mode = mode.ifBlank { null },
        code = code.ifBlank { null },
        error = error.ifBlank { null },
        expectedGeneration = null,
    ) { result ->
        SlockEventBuses.inProcess().post(
            eventName = SLOCK_MOBILE_OAUTH_COMPLETION_EVENT,
            eventData =
                com.tencent.kuikly.core.nvi.serialization.json.JSONObject()
                    .put("status", result.status)
                    .put("provider", result.provider.orEmpty()),
            crossProcess = false,
            source = "ohos_mobile_oauth",
        )
    }
    return true
}
