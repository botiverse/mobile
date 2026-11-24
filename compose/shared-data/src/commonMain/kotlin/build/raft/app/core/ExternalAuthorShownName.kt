package build.raft.app

import androidx.compose.runtime.Composable
import build.raft.app.model.ExternalMessageAuthor
import build.raft.app.model.Message
import build.raft.app.resources.Res
import build.raft.app.resources.desc
import build.raft.app.resources.message_author_external_fallback
import build.raft.app.resources.resolve
import build.raft.app.resources.stringResource

/*
 * An External (Slack-bridge) sender the wire did not name carries the English stand-in
 * "External user" as its display name, flagged by [ExternalMessageAuthor.displayNameIsFallback]
 * (#3990). Every surface that shows an author name reads it through these helpers so the flag,
 * not a string comparison, picks the localized `message_author_external_fallback`. A name the
 * server sent (including one such as "Unknown") is shown as is.
 */

/** The shown author name is the stand-in for an External sender the wire did not name. */
@RaftCoreInternalApi val ExternalMessageAuthor?.isFallbackName: Boolean
    get() = this?.displayNameIsFallback == true

/** [name], or [fallbackName] when [author] is the unnamed External stand-in. */
@RaftCoreInternalApi fun externalAuthorShownName(author: ExternalMessageAuthor?, name: String, fallbackName: String): String =
    if (author.isFallbackName) fallbackName else name

/** The localized "External user", in the app language, outside composition. */
@RaftCoreInternalApi fun externalAuthorFallbackName(): String = Res.string.message_author_external_fallback.desc().resolve()

/** The localized "External user" in the app language; recomposes on a language change. */
@Composable
@RaftCoreInternalApi fun rememberExternalAuthorFallbackName(): String = stringResource(Res.string.message_author_external_fallback)

/** The sender name to show for this message outside composition (notifications, previews, stores). */
@RaftCoreInternalApi fun Message.shownAuthorName(): String =
    if (externalAuthor.isFallbackName) externalAuthorFallbackName() else author.displayName
