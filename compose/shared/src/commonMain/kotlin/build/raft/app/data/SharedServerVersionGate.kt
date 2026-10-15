package build.raft.app.data

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** Result of probing a candidate private deployment before it is saved as the custom environment. */
sealed interface ServerVersionProbe {
    /** The deployment reported a version at or above the client baseline. */
    data class Compatible(val version: String) : ServerVersionProbe

    /** The deployment reported a version below the client baseline. */
    data class Incompatible(val version: String) : ServerVersionProbe

    /** The endpoint could not be reached or returned a non-2xx response. */
    data object Unreachable : ServerVersionProbe

    /** The endpoint answered 2xx but carried no usable `version` field and no Raft build identity. */
    data object MissingVersion : ServerVersionProbe

    /**
     * A Raft server that reports its build identity but no `version`: raft-source's `/api/version` answers
     * `{sha, builtAt, branch}` (or 503 `build_identity_unavailable` when those are not configured). Accepted —
     * otherwise every open-source deployment is refused (artin, 2026-10-15).
     */
    data object Unversioned : ServerVersionProbe
}

/**
 * Save-time gate for private (self-hosted) deployments. The client only accepts a
 * custom environment whose server reports a `version` at or above the open-source
 * baseline this client is built from. `revision` hashes are never compared.
 */
internal object SharedServerVersionGate {
    /**
     * Lowest server version a private deployment may run. Tracks the open-source
     * release line of this client; bump when the release baseline moves.
     */
    const val MIN_SERVER_VERSION = "1.13.0"

    /** raft-source requires a 40-hex `RAFT_RELEASE_SHA`; short hashes are tolerated, anything else is not Raft. */
    private val RAFT_BUILD_SHA = Regex("^[0-9a-fA-F]{7,40}$")

    /** Numeric triple compare; non-numeric input never passes. */
    fun parseVersion(text: String): List<Int>? {
        val core = text.trim().removePrefix("v").substringBefore('-').substringBefore('+')
        val parts = core.split('.')
        if (parts.isEmpty() || parts.any { it.isEmpty() || it.any { c -> !c.isDigit() } }) return null
        return parts.map { it.toIntOrNull() ?: return null }
    }

    fun isVersionAtLeast(version: String, baseline: String): Boolean {
        val v = parseVersion(version) ?: return false
        val b = parseVersion(baseline) ?: return false
        for (i in 0 until maxOf(v.size, b.size)) {
            val diff = v.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (diff != 0) return diff > 0
        }
        return true
    }

    /**
     * GETs `<apiBaseUrl>/version` without auth. A `version` string is compared with the baseline; a Raft
     * build identity without one ([ServerVersionProbe.Unversioned], raft-source) is accepted. Network errors,
     * other non-2xx answers and non-Raft bodies refuse the save.
     */
    suspend fun probe(
        apiBaseUrl: String,
        transportFactory: (String) -> SlockAuthTransport = { SharedSlockAuthTransport(baseUrl = it) }
    ): ServerVersionProbe {
        val base = apiBaseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return ServerVersionProbe.Unreachable
        val response =
            runCatchingPreservingCancellation { transportFactory("$base/").serverVersion() }
                .getOrNull() ?: return ServerVersionProbe.Unreachable
        val json =
            (response.bodyText ?: response.errorBodyText)
                ?.takeIf { it.isNotBlank() }
                ?.let { body -> runCatching { JSONObject(body) }.getOrNull() }
        val buildIdentityUnavailable =
            response.code == 503 && json?.optString("code") == "build_identity_unavailable"
        val version = json?.optString("version")?.trim().orEmpty()
        if (version.isEmpty()) {
            // No version reported: only a Raft build identity passes (Ark: never let it override a real version).
            if (buildIdentityUnavailable) return ServerVersionProbe.Unversioned
            if (response.code !in 200..299) return ServerVersionProbe.Unreachable
            // A web app's catch-all page also answers 2xx: only a JSON build identity counts as Raft.
            return if (json?.optString("sha")?.trim()?.let(RAFT_BUILD_SHA::matches) == true) {
                ServerVersionProbe.Unversioned
            } else {
                ServerVersionProbe.MissingVersion
            }
        }
        // A reported version always decides; one that does not parse is refused, never treated as absent.
        if (response.code !in 200..299 && !buildIdentityUnavailable) return ServerVersionProbe.Unreachable
        if (parseVersion(version) == null) return ServerVersionProbe.MissingVersion
        return if (isVersionAtLeast(version, MIN_SERVER_VERSION)) {
            ServerVersionProbe.Compatible(version)
        } else {
            ServerVersionProbe.Incompatible(version)
        }
    }
}
