package build.raft.app.nav

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The saveable identity of one page on [RaftPageStack]: the page name, the opener's identity-only
 * pageData and a per-stack serial [id] (two opens of the same page and data are two entries).
 *
 * Route / pageData rule (AGENTS.md): a key carries identity and intent, never product content; the
 * page's MVI store loads canonical state. [create] drops the known content keys
 * ([RaftPageKeyContentFields]); decoding a key that still has one fails, so a restored stack never
 * carries content either. Host facts (time context, insets) are not part of the key: the host adds
 * them to the page args.
 */
@Serializable
class RaftPageKey private constructor(
    val pageName: String,
    val pageData: JsonObject,
    val id: Long,
) {
    init {
        val content = pageData.keys.filter { it in RaftPageKeyContentFields }
        require(content.isEmpty()) { "RaftPageKey($pageName) carries content fields $content" }
    }

    /** The Navigation 3 entry content key: page name plus the serial. */
    val contentKey: String
        get() = "$pageName#$id"

    override fun equals(other: Any?): Boolean =
        this === other || (other is RaftPageKey && pageName == other.pageName && pageData == other.pageData && id == other.id)

    override fun hashCode(): Int = (pageName.hashCode() * 31 + pageData.hashCode()) * 31 + id.hashCode()

    override fun toString(): String = "RaftPageKey($contentKey)"

    companion object {
        /** A key for [pageName] with [pageData] minus its content fields ([onDropped] gets each one). */
        fun create(
            pageName: String,
            pageData: JsonObject,
            id: Long,
            onDropped: (String) -> Unit = {},
        ): RaftPageKey {
            val dropped = pageData.keys.filter { it in RaftPageKeyContentFields }
            dropped.forEach(onDropped)
            val identity = if (dropped.isEmpty()) pageData else JsonObject(pageData - dropped.toSet())
            return RaftPageKey(pageName, identity, id)
        }
    }
}

/**
 * pageData fields that are product content, never identity. The iOS router drops the same set
 * (RaftRouterIdentityPageData in iosApp/iosApp/Cmp/RaftPageRouter.m; RaftPageKeyContentFieldsSyncTest
 * keeps the two equal).
 */
val RaftPageKeyContentFields: Set<String> =
    setOf(
        "messages",
        "tasks",
        "members",
        "files",
        "attachments",
        "profiles",
        "plans",
        "computers",
        "suggestions",
        "composerState",
        "snapshot",
    )
