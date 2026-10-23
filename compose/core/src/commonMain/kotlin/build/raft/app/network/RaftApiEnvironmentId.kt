package build.raft.app.network

/**
 * One API environment's identity, `key|apiBaseUrl` (normalised): the feature-flag evaluation context
 * and the server-setup admission scope. Kept under its old name from the NetworkKMM recovery bridge,
 * which was removed with the Ktor 3 switch; the value is unchanged.
 */
fun raftNetworkKmmRecoveryEnvironmentId(
    environmentKey: String,
    apiBaseUrl: String
): String {
    val normalizedKey = environmentKey.trim().lowercase().ifBlank { "unknown" }
    val normalizedApiBaseUrl =
        apiBaseUrl
            .trim()
            .replace('\n', '_')
            .replace('\r', '_')
            .trimEnd('/')
            .ifBlank { "unknown" }
    return "$normalizedKey|$normalizedApiBaseUrl"
}
