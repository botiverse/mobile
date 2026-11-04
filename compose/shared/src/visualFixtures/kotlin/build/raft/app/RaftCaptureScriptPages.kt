package build.raft.app

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.network.RaftCaptureFakeData
import build.raft.app.network.RaftCaptureFakeServer

/**
 * PROTOTYPE capture script: the pageData the app's own navigation builds for a target, so the
 * host script opens the real pages the way a tap would (`thread:<channelId>`, `drafts`,
 * `settings-account`).
 */
internal fun raftCaptureScriptPageData(target: String): String? {
    val shell =
        build.raft.app.compat.json.JSONObject().apply {
            put(RAFT_SHELL_INSTANCE_ID, "legacy-app-shell")
            put(RAFT_SHELL_SERVER_ID, RaftCaptureFakeServer.DesignServerId)
        }
    return when {
        target.startsWith("thread:") -> {
            val channelId = target.removePrefix("thread:")
            val dm = channelId.startsWith("dm_")
            val identity =
                buildThreadLaunchConversationIdentity(
                    kind = if (dm) ConversationKind.DirectMessage else ConversationKind.Channel,
                    channelId = ChannelId(channelId),
                    channelType = if (dm) "dm" else "channel",
                )
            buildStandaloneConversationThreadLaunchPayload(identity, timeContext = null)
                ?.let { threadLaunchPageData(it).toString() }
        }
        target == "drafts" -> shell.apply { put("pageName", HOME_DRAFTS_PAGE) }.toString()
        target == "settings-account" -> shell.apply { put("pageName", SETTINGS_ACCOUNT_PAGE) }.toString()
        else -> null
    }.also { println("RAFT_CAPTURE_PAGE target=$target ok=${it != null} long=${RaftCaptureFakeData.LongChannelName.length}") }
}
