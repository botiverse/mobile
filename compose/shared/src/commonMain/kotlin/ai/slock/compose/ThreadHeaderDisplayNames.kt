package ai.slock.compose

private val ThreadHeaderUuidRegex =
    Regex("^[A-Fa-f0-9]{8}(?:-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}$")
private val ThreadHeaderShortIdRegex = Regex("^[A-Fa-f0-9]{8}$")

internal fun threadHeaderFallbackTitle(surfaceKind: String): String =
    when (surfaceKind) {
        KuiklyThreadSurfaceKind.ReplyThread -> ThreadReplyPageTitle
        KuiklyThreadSurfaceKind.DirectMessage -> "Direct Message"
        else -> "Channel"
    }

internal fun threadHeaderCandidateTitle(raw: String?): String? =
    raw
        ?.trim()
        ?.removePrefix("#")
        ?.removePrefix("@")
        ?.trim()
        ?.takeIf { it.isNotBlank() }

internal fun threadHeaderIdentityLikeTitle(
    title: String,
    identityIds: Set<String> = emptySet()
): Boolean {
    val candidate = threadHeaderCandidateTitle(title) ?: return false
    if (ThreadHeaderUuidRegex.matches(candidate) || ThreadHeaderShortIdRegex.matches(candidate)) return true
    return identityIds.any { id -> id.isNotBlank() && candidate.equals(id.trim(), ignoreCase = true) }
}

internal fun threadHeaderSafeTitleOrNull(
    raw: String?,
    identityIds: Set<String> = emptySet()
): String? =
    threadHeaderCandidateTitle(raw)
        ?.takeUnless { candidate -> threadHeaderIdentityLikeTitle(candidate, identityIds) }

internal fun threadHeaderSafeTitle(
    raw: String?,
    surfaceKind: String,
    identityIds: Set<String> = emptySet()
): String =
    threadHeaderSafeTitleOrNull(raw, identityIds) ?: threadHeaderFallbackTitle(surfaceKind)
