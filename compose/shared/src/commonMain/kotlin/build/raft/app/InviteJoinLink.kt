package build.raft.app

/**
 * The only production data carried by an invite Universal Link is this opaque token.
 * The destination resolves all server/member/agreement state through the shared auth API.
 */
data class SlockInviteJoinIntent(
    val token: String,
)

private val SlockInviteJoinUrlPattern =
    Regex("^https://app\\.raft\\.build/join/([A-Za-z0-9_-]{1,256})$")
private val SlockInviteTokenPattern = Regex("^[A-Za-z0-9_-]{1,256}$")

/** Returns the opaque invite token only when it satisfies the same route contract. */
fun normalizeSlockInviteTokenOrNull(rawToken: String?): String? {
    val token = rawToken ?: return null
    if (token.isEmpty() || token != token.trim()) return null
    return token.takeIf { SlockInviteTokenPattern.matches(it) }
}

/**
 * Parses exactly one production invite URL. Query strings, fragments, extra path segments,
 * encoded separators, alternate hosts/schemes and whitespace are rejected deliberately.
 */
fun parseSlockInviteJoinUrl(rawUrl: String?): SlockInviteJoinIntent? {
    val value = rawUrl ?: return null
    if (value.isEmpty() || value != value.trim()) return null
    val match = SlockInviteJoinUrlPattern.matchEntire(value) ?: return null
    return normalizeSlockInviteTokenOrNull(match.groupValues[1])?.let(::SlockInviteJoinIntent)
}
