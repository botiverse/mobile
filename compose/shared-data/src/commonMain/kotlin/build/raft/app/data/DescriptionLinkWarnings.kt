package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.reportUnknownTypeDropped
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Links in a just-saved profile description that some of its readers can't open (botiverse/slock
 * #15236, #457: warn, never refuse; the save itself stands). The server answers them as `warnings`
 * on `PATCH /agents/:id` and `PATCH /auth/me` only when there are some, for a description that
 * changed; they are the caller's to show and never part of the stored agent or user. Shared
 * `descriptionLinkWarnings.ts` is the vocabulary: the codes and which fixes apply to each.
 */

/** Why a linked object is narrower than the description (shared `DescriptionLinkWarningCode`). */
enum class DescriptionLinkWarningCode(val wireValue: String) {
    /** Some readers can't open it, and the writer can't either (missing, deleted, revoked, unseen). */
    Unavailable("unavailable"),
    /** Another server's, not public: the description's server's people can't. */
    OtherServer("other_server"),
    /** It lives in one conversation, narrower than any description's audience. */
    Conversation("conversation"),
    /** The whole server's, and a person's description is also read by people outside it ([DescriptionLinkWarning.unreadable]). */
    ServerPartial("server_partial"),
    /** A newer code this app does not know: worded as [Unavailable] (logged), with no fix. */
    Unknown("");
}

/** Whose description: an agent's is read by its server's full members only; a person's in every server they are in. */
enum class DescriptionLinkSubject { Agent, Person }

/** One fix (shared `DescriptionLinkFix`), each for a different intent. */
enum class DescriptionLinkFix { ReplaceLink, PublicCopy, LeaveOut, WidenToServer, MakePublic }

data class DescriptionLinkWarning(
    val code: DescriptionLinkWarningCode,
    /** The link as the description has it. */
    val url: String,
    /** A person's description: the server whose people can't open it. */
    val serverName: String? = null,
    val serverId: String? = null,
    /** `server_partial`: guests can't. */
    val unreadableByGuests: Boolean = false,
    /** `server_partial`: people of other servers in shared channels can't. */
    val unreadableByOtherServers: Boolean = false,
)

/** Shared `descriptionLinkFixes`: which fixes apply to [code], widest audience last. */
@RaftCoreInternalApi fun descriptionLinkFixes(code: DescriptionLinkWarningCode, subject: DescriptionLinkSubject): List<DescriptionLinkFix> =
    when (code) {
        DescriptionLinkWarningCode.Unavailable -> listOf(DescriptionLinkFix.ReplaceLink)
        DescriptionLinkWarningCode.Conversation ->
            if (subject == DescriptionLinkSubject.Agent) {
                listOf(DescriptionLinkFix.PublicCopy, DescriptionLinkFix.LeaveOut, DescriptionLinkFix.WidenToServer)
            } else {
                listOf(DescriptionLinkFix.PublicCopy, DescriptionLinkFix.LeaveOut, DescriptionLinkFix.WidenToServer, DescriptionLinkFix.MakePublic)
            }
        DescriptionLinkWarningCode.ServerPartial, DescriptionLinkWarningCode.OtherServer ->
            listOf(DescriptionLinkFix.PublicCopy, DescriptionLinkFix.LeaveOut, DescriptionLinkFix.MakePublic)
        DescriptionLinkWarningCode.Unknown -> emptyList()
    }

/** Shared `descriptionLinkFixesOf`: the fixes of several warnings about one link, each once, in the order offered. */
@RaftCoreInternalApi fun descriptionLinkFixesOf(codes: List<DescriptionLinkWarningCode>, subject: DescriptionLinkSubject): List<DescriptionLinkFix> {
    val applicable = codes.flatMap { descriptionLinkFixes(it, subject) }.toSet()
    return DescriptionLinkFix.entries.filter { it in applicable }
}

/** The warnings grouped by link, in first-seen order (web `DescriptionLinkWarnings` `links`). */
@RaftCoreInternalApi fun List<DescriptionLinkWarning>.byLink(): List<Pair<String, List<DescriptionLinkWarning>>> =
    groupBy { it.url }.toList()

@Serializable
private data class DescriptionLinkWarningsWire(val warnings: List<DescriptionLinkWarningWire>? = null)

@Serializable
private data class DescriptionLinkWarningWire(
    val code: String? = null,
    val url: String? = null,
    val server: DescriptionLinkWarningServerWire? = null,
    val unreadable: List<String>? = null,
)

@Serializable
private data class DescriptionLinkWarningServerWire(val id: String? = null, val name: String? = null)

private val descriptionLinkWarningsJson = Json { ignoreUnknownKeys = true }

/**
 * The `warnings` of a profile save answer; empty when absent (none, or an older server). A
 * warning without a link is dropped; an unknown code is kept as [DescriptionLinkWarningCode.Unknown].
 */
fun parseSharedDescriptionLinkWarnings(bodyText: String): List<DescriptionLinkWarning> {
    val wire =
        runCatching { descriptionLinkWarningsJson.decodeFromString(DescriptionLinkWarningsWire.serializer(), bodyText) }
            .getOrNull()
            ?.warnings
            ?: return emptyList()
    return wire.mapNotNull { warning ->
        val url = warning.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val code =
            DescriptionLinkWarningCode.entries.firstOrNull { it.wireValue.isNotEmpty() && it.wireValue == warning.code }
                ?: DescriptionLinkWarningCode.Unknown.also {
                    reportUnknownTypeDropped("description_link_warning", "code", warning.code, fallback = "unavailable_sentence")
                }
        val unreadable = warning.unreadable.orEmpty()
        DescriptionLinkWarning(
            code = code,
            url = url,
            serverName = warning.server?.name?.takeIf { it.isNotBlank() },
            serverId = warning.server?.id,
            unreadableByGuests = "guests" in unreadable,
            unreadableByOtherServers = "other_servers" in unreadable,
        )
    }
}
