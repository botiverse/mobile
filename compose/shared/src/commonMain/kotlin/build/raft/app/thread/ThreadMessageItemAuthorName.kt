package build.raft.app.thread

import androidx.compose.runtime.Composable
import build.raft.app.externalAuthorFallbackName
import build.raft.app.isFallbackName
import build.raft.app.rememberExternalAuthorFallbackName
import build.raft.app.shownAuthorName
import build.raft.app.thread.shownAuthorName

/** The author name to show for this row outside composition (copy/share text, lightbox, task source). */
internal fun RaftThreadMessageItem.shownAuthorName(): String =
    if (externalAuthor.isFallbackName) externalAuthorFallbackName() else authorName

/** The author name to show for this row in composition. */
@Composable
internal fun RaftThreadMessageItem.localizedAuthorName(): String =
    if (externalAuthor.isFallbackName) rememberExternalAuthorFallbackName() else authorName
