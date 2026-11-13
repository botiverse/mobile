package build.raft.app.data

/**
 * Request paths that carry a secret in a segment, as network diagnostics may print them (the
 * `endpoint …` lines, `slock_http_execute_*`): logs reach feedback diagnostics, so the secret
 * becomes `:redacted`. Today: an external channel invite's token
 * (`external-invites/{token}[/…]` at the API root, the app's path; `external-invite/{token}`, the web
 * link), and a server join link's (`join/{token}`, the web link; `auth/invite-info?token={token}`,
 * the join page's read). A channel's `channels/:id/external-invites/:inviteId` names no secret and is
 * left as is.
 */
private val SensitivePathSegments = Regex("(^/?|/api/|://[^/]+/)(external-invites?/|join/)[^/?#\\s]+")
private val SensitiveQueryValues = Regex("([?&]token=)[^&#\\s]+")

internal const val RaftRedactedPathSegment: String = ":redacted"

/** [path] (a path or an absolute URL) with every secret segment replaced by [RaftRedactedPathSegment]. */
internal fun raftRedactSensitivePath(path: String): String {
    var redacted = path
    if ("external-invite" in redacted || "join/" in redacted) {
        redacted = SensitivePathSegments.replace(redacted) { match -> match.groupValues[1] + match.groupValues[2] + RaftRedactedPathSegment }
    }
    if ("token=" in redacted) {
        redacted = SensitiveQueryValues.replace(redacted) { match -> match.groupValues[1] + RaftRedactedPathSegment }
    }
    return redacted
}
