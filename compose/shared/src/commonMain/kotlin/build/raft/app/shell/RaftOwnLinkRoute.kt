package build.raft.app

import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.RaftClientConfigAppHosts

/**
 * Where a link to one of Raft's own pages opens when it is tapped in the app: in a message, a
 * description, a card, or an Artifact's page. On the web a Raft page opens in Raft, never in a
 * browser tab (slock `packages/shared/src/linkRouter.ts`, target `raft`; #proj-workspace:#290), so a
 * page the app has a screen for opens that screen; the others go to the web page.
 *
 * The hosts are a message link's ([parseRaftMessageLink]): production, the client config's
 * `appHosts`, and exactly the Developer environment's web URL (AGENTS.md "Environment Web Link URL
 * Rule"). A host written with the dot of a fully-qualified name (`app.raft.build.`) is the same host
 * (slock #14798). A link on another environment's host is not Raft's here: it stays a web link.
 */
internal sealed interface RaftOwnLinkRoute {
    /**
     * `/s/<slug>/channel|dm/<id>` with or without `msg` / `thread`: the shell resolves it as an
     * external link ([RaftMessageLinkResolver]): the account's server for `<slug>`, the conversation,
     * a task's own conversation as the task (slock #14960), the not-found page, a public preview.
     */
    data class Conversation(val link: RaftMessageLink) : RaftOwnLinkRoute

    /** An Artifact's address ([parseRaftArtifactDeepLink]): the in-app viewer. */
    data class Artifact(val target: ArtifactLinkTarget) : RaftOwnLinkRoute

    /** A server join link (`/join/<token>`, [parseRaftServerJoinLink]): the join page. */
    data class ServerJoin(val token: String) : RaftOwnLinkRoute

    /** An external channel invite (`/external-invite/<token>`, [parseRaftExternalInviteLink]): the invite page. */
    data class ExternalInvite(val token: String) : RaftOwnLinkRoute

    /** A send-prompt link (`?dialog=send-prompt&prompt=<text>`, [parseRaftSendPromptLink]): the native sheet. */
    data class SendPrompt(val link: RaftSendPromptLink) : RaftOwnLinkRoute

    /** A Raft page the app has no screen to open from a link: its web page ([RaftLinkOpenIntent.RaftWebPage]). */
    data class WebPage(val reason: RaftOwnLinkWebPageReason) : RaftOwnLinkRoute
}

/** Why a Raft page stays a web page (logged; the PR lists them). */
internal enum class RaftOwnLinkWebPageReason {
    /**
     * A conversation link with the web's task panel (`task=<channel>:task:<id>`, the address bar
     * while a task is open). The app has no read of a task by id to open it from (opening must not
     * create its conversation), and dropping `task` would open only the channel.
     */
    TaskPanel,

    /**
     * Any other page: Settings, profiles (`agent/`, `human/`, `computer/`), the server's lists
     * (`tasks`, `saved`, `activity`, `search`, `members`, `computers`, `apps`, `artifacts` without an
     * Artifact, `drafts`, …), `/servers`, sign-in pages. The app has screens for several of them, but
     * no identity-only route a link can open them by (each is opened from the app's own lists).
     */
    NoInAppRoute,
}

/**
 * [url]'s [RaftOwnLinkRoute], or null when it is not on one of Raft's own origins (an outside link,
 * which the link setting decides). [configuredWebUrl] is the Developer environment's web URL.
 */
internal fun raftOwnLinkRoute(
    url: String,
    configuredWebUrl: String?,
    appHosts: Set<String> = RaftClientConfigAppHosts.current,
): RaftOwnLinkRoute? {
    if (raftOwnWebLinkPath(url, configuredWebUrl, appHosts) == null) return null
    parseRaftSendPromptLink(url, configuredWebUrl, appHosts, allowRelative = false)?.let {
        return RaftOwnLinkRoute.SendPrompt(it)
    }
    parseRaftArtifactDeepLink(url, configuredWebUrl, appHosts)?.let { return RaftOwnLinkRoute.Artifact(it) }
    parseRaftServerJoinLink(url, configuredWebUrl, appHosts)?.let { return RaftOwnLinkRoute.ServerJoin(it) }
    parseRaftExternalInviteLink(url, configuredWebUrl, appHosts)?.let { return RaftOwnLinkRoute.ExternalInvite(it) }
    parseRaftMessageLink(url, configuredWebUrl, appHosts)?.let { link ->
        return if (url.raftLinkHasTaskPanel()) {
            RaftOwnLinkRoute.WebPage(RaftOwnLinkWebPageReason.TaskPanel)
        } else {
            RaftOwnLinkRoute.Conversation(link)
        }
    }
    return RaftOwnLinkRoute.WebPage(RaftOwnLinkWebPageReason.NoInAppRoute)
}

/** Whether [this] (a conversation link) names the web's task panel: a non-empty `task` query value. */
private fun String.raftLinkHasTaskPanel(): Boolean =
    trim().substringBefore('#').substringAfter('?', "").split('&').any { part ->
        part.substringBefore('=') == "task" && part.substringAfter('=', "").isNotEmpty()
    }

/**
 * Opens the in-app destinations of [raftOwnLinkRoute] that wait for the app shell: a conversation
 * link goes to [messageInbox] (the shell resolves and opens it as an external link, over whatever is
 * shown), an external channel invite to [inviteInbox]. True when it was taken; false for anything
 * else (an Artifact and a join link have their own openers, a web page and an outside link are the
 * link setting's).
 */
internal fun raftOwnLinkShellOpener(
    webUrl: () -> String?,
    appHosts: () -> Set<String> = { RaftClientConfigAppHosts.current },
    messageInbox: RaftMessageLinkInbox = RaftMessageLinkInbox.Process,
    inviteInbox: RaftExternalInviteLinkInbox = RaftExternalInviteLinkInbox.Process,
    sendPromptInbox: RaftSendPromptLinkInbox = RaftSendPromptLinkInbox.Process,
    log: (String) -> Unit = {},
): (String) -> Boolean = { url ->
    when (val route = raftOwnLinkRoute(url, webUrl(), appHosts())) {
        is RaftOwnLinkRoute.Conversation -> {
            val pending = messageInbox.submit(route.link, "message_link_tap")
            log(
                "raft_link_tap route=conversation id=${pending.id} kind=${route.link.routeKind.name} " +
                    "thread=${route.link.isThread} msg=${route.link.messageId != null}"
            )
            true
        }
        is RaftOwnLinkRoute.ExternalInvite -> {
            val pending = inviteInbox.submit(route.token, "message_link_tap")
            log("raft_link_tap route=external_invite id=${pending.id}")
            true
        }
        is RaftOwnLinkRoute.SendPrompt -> {
            val pending = sendPromptInbox.submit(route.link, "message_link_tap")
            log("raft_link_tap route=send_prompt id=${pending.id}")
            true
        }
        else -> false
    }
}

/**
 * Whether [url] is a Raft page the app opens as its web page ([RaftOwnLinkRoute.WebPage]): opened
 * with [RaftLinkOpenIntent.RaftWebPage], so "Default browser" reaches a real browser instead of
 * bouncing off Raft's own App Link / Universal Link.
 */
internal fun raftOwnLinkIsWebPage(url: String, webUrl: String?, appHosts: Set<String> = RaftClientConfigAppHosts.current): Boolean =
    raftOwnLinkRoute(url, webUrl, appHosts) is RaftOwnLinkRoute.WebPage
