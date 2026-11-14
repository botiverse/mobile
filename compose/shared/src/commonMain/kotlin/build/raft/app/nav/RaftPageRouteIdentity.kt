package build.raft.app.nav

import build.raft.app.THREAD_PAGE
import build.raft.app.cmp.host.RaftPageRouteIdentities
import build.raft.app.threadLaunchPayloadOrNull
import kotlinx.serialization.json.JsonObject

/**
 * The route identity of a page open (task #151), installed into :ui-cmp's open guard and used by
 * [RaftPageStack] for "already on top": two opens are the same page when they show the same
 * thread / conversation at the same message anchor, whatever else their pageData carries.
 *
 * - The thread page compares its launch payload's route fields (its payload also carries per-open
 *   clock facts and display settings, which differ tap to tap).
 * - Every other page compares page name plus pageData minus product content
 *   ([RaftPageKeyContentFields]), the replace flag and per-open facts
 *   ([RaftPageRouteIdentities.volatileKeys]).
 */
internal fun raftPageRouteIdentity(pageName: String, pageData: JsonObject): Any {
    if (pageName == THREAD_PAGE) {
        val launch = threadLaunchPayloadOrNull(pageData)
        if (launch != null && launch.hasRouteIdentity()) {
            return RaftThreadRouteIdentity(
                surfaceKind = launch.surfaceKind,
                threadId = launch.threadId.orEmpty(),
                sourceThreadId = launch.sourceThreadId.orEmpty(),
                streamChannelId = launch.streamChannelId.orEmpty(),
                parentChannelId = launch.parentChannelId.orEmpty(),
                parentMessageId = launch.parentMessageId.orEmpty(),
                focusedMessageId = launch.focusedMessageId.orEmpty(),
                selectedTab = launch.selectedTab,
            )
        }
    }
    return RaftPageRouteIdentities.default.identityOf(pageName, JsonObject(pageData - RaftPageRouteIgnoredKeys))
}

private data class RaftThreadRouteIdentity(
    val surfaceKind: String,
    val threadId: String,
    val sourceThreadId: String,
    val streamChannelId: String,
    val parentChannelId: String,
    val parentMessageId: String,
    val focusedMessageId: String,
    val selectedTab: String,
)

private val RaftPageRouteIgnoredKeys: Set<String> = RaftPageKeyContentFields + RAFT_REPLACE_CURRENT_PAGE
