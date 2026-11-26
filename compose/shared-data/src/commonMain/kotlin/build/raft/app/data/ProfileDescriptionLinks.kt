package build.raft.app.data

/*
 * A profile description (an agent's or a member's: plain text, never Markdown) cut around its
 * http(s) addresses so the ones that name a Workspace Artifact can be drawn as inline title chips
 * (botiverse/slock #14969 `packages/shared/src/descriptionLinks.ts`, `ProfileDescription.tsx`, and
 * #15230 unreadable-link fallback). Everything else stays the author's plain text.
 */

/** One piece of a plain-text profile description: ordinary text or an http(s) address. */
sealed interface ProfileDescriptionPiece {
    data class Text(val text: String) : ProfileDescriptionPiece

    data class Link(val url: String) : ProfileDescriptionPiece
}

// Full-width punctuation from slock `packages/shared/src/bareLinkEnd.ts` (`FULL_WIDTH_PUNCTUATION`):
// CJK Symbols and Punctuation (U+3000-U+303F) and Halfwidth/Fullwidth punctuation (not letters/digits).
private const val PROFILE_DESCRIPTION_FULL_WIDTH_PUNCTUATION =
    "\u3000-\u303f\uff01-\uff0f\uff1a-\uff20\uff3b-\uff40\uff5b-\uff65"

// Match JS `\s` (ASCII whitespace + Unicode spaces) consistently across JVM and Kotlin/Native.
private const val PROFILE_DESCRIPTION_JS_WHITESPACE =
    "\\s\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\ufeff"

// An address runs to whitespace, angle/quote delimiters, or CJK full-width punctuation ("清单：https://…，见…").
private val PROFILE_DESCRIPTION_ADDRESS =
    Regex("https?://[^$PROFILE_DESCRIPTION_JS_WHITESPACE<>\"'$PROFILE_DESCRIPTION_FULL_WIDTH_PUNCTUATION]+")

// ASCII punctuation that ends a sentence rather than the address ("see https://x/y.").
private val PROFILE_DESCRIPTION_TRAILING = Regex("""[.,;:!?'")\]}]+$""")

/**
 * Splits [text] into plain-text and http(s) address pieces in order. Joining the pieces back
 * always reconstructs [text] verbatim.
 */
fun profileDescriptionPieces(text: String): List<ProfileDescriptionPiece> {
    if (text.isEmpty()) return emptyList()
    val pieces = mutableListOf<ProfileDescriptionPiece>()
    var from = 0
    for (match in PROFILE_DESCRIPTION_ADDRESS.findAll(text)) {
        val url = match.value.replace(PROFILE_DESCRIPTION_TRAILING, "")
        val start = match.range.first
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0 || url.length <= schemeEnd + 3) continue
        if (start > from) pieces += ProfileDescriptionPiece.Text(text.substring(from, start))
        pieces += ProfileDescriptionPiece.Link(url)
        from = start + url.length
    }
    if (from < text.length) pieces += ProfileDescriptionPiece.Text(text.substring(from))
    return pieces
}

/** The http(s) addresses among [pieces], in order. */
fun profileDescriptionUrls(pieces: List<ProfileDescriptionPiece>): List<String> =
    pieces.mapNotNull { (it as? ProfileDescriptionPiece.Link)?.url }

/**
 * Distinct Artifact targets referenced by [pieces] on Raft's [appHosts], in first-seen order
 * (deduplicated by [ArtifactLinkTarget.key], matching web `ProfileDescription.tsx` `asked`).
 */
fun profileDescriptionArtifactTargets(
    pieces: List<ProfileDescriptionPiece>,
    appHosts: Collection<String>,
): List<ArtifactLinkTarget> {
    val byKey = LinkedHashMap<String, ArtifactLinkTarget>()
    for (piece in pieces) {
        val link = piece as? ProfileDescriptionPiece.Link ?: continue
        val target = parseArtifactLinkTarget(link.url, appHosts) ?: continue
        if (target.key !in byKey) byKey[target.key] = target
    }
    return byKey.values.toList()
}

/** One resolved display segment of a profile description (web `ProfileDescription.tsx` `Shown`). */
sealed interface ProfileDescriptionSegment {
    data class Text(val text: String) : ProfileDescriptionSegment

    sealed interface Artifact : ProfileDescriptionSegment {
        val url: String
        val target: ArtifactLinkTarget

        /** Summary answered `ok`: cyan title chip with kind icon, opens Artifact in place. */
        data class Available(
            override val url: String,
            override val target: ArtifactLinkTarget,
            val summary: ArtifactSummary,
        ) : Artifact

        /** Summary answered `deleted`: muted deleted chip with title + "deleted" suffix (not a link). */
        data class Deleted(
            override val url: String,
            override val target: ArtifactLinkTarget,
            val deleted: ArtifactDeletedShown,
        ) : Artifact

        /** Summary not answered yet: muted pending "Artifact" chip, still clickable to open in place (#15230). */
        data class Loading(
            override val url: String,
            override val target: ArtifactLinkTarget,
        ) : Artifact

        /** Summary unavailable or failed: muted "Artifact" chip (never URL or hidden title), still clickable (#15230). */
        data class Unavailable(
            override val url: String,
            override val target: ArtifactLinkTarget,
        ) : Artifact
    }
}

/**
 * Resolves [pieces] against Raft's [appHosts] and current summary [answers] (keyed by
 * [ArtifactLinkTarget.key]). Non-Artifact URLs remain [ProfileDescriptionSegment.Text]; when
 * [enabled] is false (flag off or share capture), every piece stays plain text.
 */
fun resolveProfileDescriptionSegments(
    pieces: List<ProfileDescriptionPiece>,
    appHosts: Collection<String>,
    answers: Map<String, ArtifactSummaryAnswer>,
    enabled: Boolean = true,
): List<ProfileDescriptionSegment> {
    if (pieces.isEmpty()) return emptyList()
    return pieces.map { piece ->
        when (piece) {
            is ProfileDescriptionPiece.Text -> ProfileDescriptionSegment.Text(piece.text)
            is ProfileDescriptionPiece.Link -> {
                val target = if (enabled) parseArtifactLinkTarget(piece.url, appHosts) else null
                if (target == null) {
                    ProfileDescriptionSegment.Text(piece.url)
                } else {
                    when (val answer = answers[target.key]) {
                        is ArtifactSummaryAnswer.Ok ->
                            ProfileDescriptionSegment.Artifact.Available(piece.url, target, answer.summary)
                        is ArtifactSummaryAnswer.Deleted ->
                            ProfileDescriptionSegment.Artifact.Deleted(piece.url, target, answer.deleted)
                        null ->
                            ProfileDescriptionSegment.Artifact.Loading(piece.url, target)
                        ArtifactSummaryAnswer.Unavailable,
                        ArtifactSummaryAnswer.Failed ->
                            ProfileDescriptionSegment.Artifact.Unavailable(piece.url, target)
                    }
                }
            }
        }
    }
}

/**
 * Plain-text representation of [segments] for a description tooltip (web `ProfileDescription.tsx`
 * `words`): resolved titles replace readable and deleted Artifact links; loading or unavailable
 * links stay as the author wrote them (never leaking an unreadable title).
 */
fun profileDescriptionPlainWords(segments: List<ProfileDescriptionSegment>): String =
    buildString {
        for (segment in segments) {
            when (segment) {
                is ProfileDescriptionSegment.Text -> append(segment.text)
                is ProfileDescriptionSegment.Artifact.Available ->
                    append(segment.summary.title.ifBlank { segment.target.id })
                is ProfileDescriptionSegment.Artifact.Deleted ->
                    append(segment.deleted.title.ifBlank { segment.target.id })
                is ProfileDescriptionSegment.Artifact.Loading,
                is ProfileDescriptionSegment.Artifact.Unavailable ->
                    append(segment.url)
            }
        }
    }
