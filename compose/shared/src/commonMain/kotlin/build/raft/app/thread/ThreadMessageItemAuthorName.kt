package build.raft.app

import androidx.compose.runtime.Composable

/** The author name to show for this row outside composition (copy/share text, lightbox, task source). */
internal fun RaftThreadMessageItem.shownAuthorName(): String =
    if (externalAuthor.isFallbackName) externalAuthorFallbackName() else authorName

/** The author name to show for this row in composition. */
@Composable
internal fun RaftThreadMessageItem.localizedAuthorName(): String =
    if (externalAuthor.isFallbackName) rememberExternalAuthorFallbackName() else authorName
