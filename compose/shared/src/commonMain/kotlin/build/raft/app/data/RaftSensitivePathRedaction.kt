package build.raft.app.data

/**
 * Request paths that carry a secret in a segment, as network diagnostics may print them (the
 * `endpoint …` lines, `slock_http_execute_*`): logs reach feedback diagnostics, so the secret
 * becomes `:redacted`. Today: an external channel invite's token
 * (`external-invites/{token}[/…]` at the API root, the app's path; `external-invite/{token}`, the web
 * link). A channel's `channels/:id/external-invites/:inviteId` names no secret and is left as is.
 */
private val SensitivePathSegments = Regex("(^/?|/api/|://[^/]+/)(external-invites?/)[^/?#\\s]+")

internal const val RaftRedactedPathSegment: String = ":redacted"

/** [path] (a path or an absolute URL) with every secret segment replaced by [RaftRedactedPathSegment]. */
internal fun raftRedactSensitivePath(path: String): String =
    if ("external-invite" !in path) path else SensitivePathSegments.replace(path) { match -> match.groupValues[1] + match.groupValues[2] + RaftRedactedPathSegment }
