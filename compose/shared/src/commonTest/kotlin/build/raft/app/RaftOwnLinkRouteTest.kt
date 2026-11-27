package build.raft.app

import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.links.raftMessageBareLinks
import build.raft.app.model.ConversationKind
import build.raft.app.shell.RaftOwnLinkRoute
import build.raft.app.shell.RaftOwnLinkWebPageReason
import build.raft.app.shell.raftOwnLinkIsWebPage
import build.raft.app.shell.raftOwnLinkRoute
import build.raft.app.thread.RaftMarkdownUrlOpen
import build.raft.app.thread.RaftMessageLink
import build.raft.app.thread.opensOnThisPage
import build.raft.app.thread.raftMarkdownUrlOpen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A Raft link tapped in the app opens the app's screen for it (slock `linkRouter.ts`: a Raft page
 * opens in Raft), the rest of Raft's pages their web page; another environment's host is not Raft's.
 */
class RaftOwnLinkRouteTest {
    private val staging = "https://raft-app-staging.botiverse.dev"
    private val slug = "botiverse"
    private val cid = "bd6e1218-1e13-4e40-838a-8c168fad487a"
    private val mid = "1c1c2197-490a-435b-8e07-e6c79c871ed1"
    private val pid = "9d6d1a00-86de-4c81-81ef-1f727384ecbb"
    private val tid = "b0b0b0b0-1111-4222-8333-444455556666"
    private val otherCid = "11111111-2222-4333-8444-555555555555"
    private val prod = "https://app.raft.build"
    private val noHosts = emptySet<String>()

    private fun route(url: String, webUrl: String? = null, appHosts: Set<String> = noHosts) = raftOwnLinkRoute(url, webUrl, appHosts)

    private fun conversation(
        kind: ConversationKind = ConversationKind.Channel,
        msg: String? = null,
        parent: String? = null,
        taskId: String? = null,
    ) = RaftOwnLinkRoute.Conversation(
        RaftMessageLink(slug, kind, cid, messageId = msg, threadParentMessageId = parent, taskId = taskId)
    )

    @Test
    fun everyConversationShapeOpensInTheApp() {
        val cases =
            listOf(
                // A channel or DM alone (before: the browser), a message, a thread, a reply in a thread.
                "$prod/s/$slug/channel/$cid" to conversation(),
                "$prod/s/$slug/dm/$cid" to conversation(ConversationKind.DirectMessage),
                "$prod/s/$slug/channel/$cid?msg=$mid" to conversation(msg = mid),
                "$prod/s/$slug/channel/$cid?thread=$cid%3A$pid" to conversation(parent = pid),
                "$prod/s/$slug/dm/$cid?msg=$mid&thread=$cid:$pid" to conversation(ConversationKind.DirectMessage, mid, pid),
                // Task conversations (slock #16576): channel, DM, and joint channels, with or without `msg=`.
                "$prod/s/$slug/channel/$cid?task=$cid%3Atask%3A$tid" to conversation(taskId = tid),
                "$prod/s/$slug/channel/$cid?msg=$mid&task=$cid%3Atask%3A$tid" to conversation(msg = mid, taskId = tid),
                "$prod/s/$slug/dm/$cid?task=$cid:task:$tid" to conversation(ConversationKind.DirectMessage, taskId = tid),
                "$prod/s/$slug/dm/$cid?msg=$mid&task=$cid%3Atask%3A$tid" to conversation(ConversationKind.DirectMessage, msg = mid, taskId = tid),
                // A task's own conversation is a channel link here; the shell's read of it opens the task (slock #14960).
                "$prod/s/$slug/channel/$cid?msg=$mid&app=missing" to conversation(msg = mid),
            )
        for ((url, expected) in cases) assertEquals(expected, route(url), url)
    }

    @Test
    fun otherRaftPagesOpenTheirScreenOrTheirWebPage() {
        val artifact = "2QthvfPeGfDnqwr7LG6itx"
        val cases =
            listOf(
                "$prod/s/$slug/artifacts?artifact=$artifact" to RaftOwnLinkRoute.Artifact(ArtifactLinkTarget(artifact)),
                "$prod/artifacts/pt_abc123/v/2" to RaftOwnLinkRoute.Artifact(ArtifactLinkTarget("pt_abc123", 2)),
                "$prod/join/tok-1" to RaftOwnLinkRoute.ServerJoin("tok-1"),
                "$prod/external-invite/inv-1" to RaftOwnLinkRoute.ExternalInvite("inv-1"),
                // Non-task-conversation / malformed `?task=` URLs keep falling back to the web task panel.
                "$prod/s/$slug/channel/$cid?task=1" to RaftOwnLinkRoute.WebPage(RaftOwnLinkWebPageReason.TaskPanel),
                "$prod/s/$slug/channel/$cid?task=1&thread=$cid%3A$pid" to RaftOwnLinkRoute.WebPage(RaftOwnLinkWebPageReason.TaskPanel),
                "$prod/s/$slug/channel/$cid?task=$cid%3A$mid" to RaftOwnLinkRoute.WebPage(RaftOwnLinkWebPageReason.TaskPanel),
                "$prod/s/$slug/channel/$cid?task=$otherCid%3Atask%3A$tid" to RaftOwnLinkRoute.WebPage(RaftOwnLinkWebPageReason.TaskPanel),
                "$prod/s/$slug/channel/$cid?task=$cid%3Atask%3A" to RaftOwnLinkRoute.WebPage(RaftOwnLinkWebPageReason.TaskPanel),
                "$prod/s/$slug/channel/$cid?thread=$cid%3A$pid&task=$cid%3Atask%3A$tid" to RaftOwnLinkRoute.WebPage(RaftOwnLinkWebPageReason.TaskPanel),
            )
        for ((url, expected) in cases) assertEquals(expected, route(url), url)
        val webPages =
            listOf(
                "/s/$slug/tasks", "/s/$slug/settings/theme", "/s/$slug/agent/a1", "/s/$slug/human/u1",
                "/s/$slug/computer/m1", "/s/$slug/saved", "/s/$slug/activity", "/s/$slug/search", "/s/$slug/artifacts",
                "/s/$slug", "/servers", "/", "",
            )
        for (path in webPages) {
            assertEquals(RaftOwnLinkRoute.WebPage(RaftOwnLinkWebPageReason.NoInAppRoute), route("$prod$path"), path)
            assertTrue(raftOwnLinkIsWebPage("$prod$path", webUrl = null, appHosts = noHosts), path)
        }
    }

    @Test
    fun sentencePunctuationAfterARaftLinkIsNotPartOfIt() {
        // What the message renders as the link (GFM autolink + the web's raft:// boundary rule) routes in the app.
        for (tail in listOf(".", ",", ")", "!", "?", ":", "。", "，")) {
            val written = "$prod/s/$slug/channel/$cid?msg=$mid"
            val shown = raftMessageBareLinks("See ($written$tail next").single()
            assertEquals(written, shown, "tail=$tail")
            assertEquals(conversation(msg = mid), route(shown), "tail=$tail")
        }
    }

    @Test
    fun aTappedConversationLinkOpensHereOnlyWhenThisPageKnowsItsConversation() {
        val known = setOf(cid)
        // Legacy links (no server) keep this page's own open.
        val legacy = raftMarkdownUrlOpen("https://app.slock.ai/channel/$cid?msg=$mid", null, noHosts)
        assertNull(legacy.conversationLink)
        assertTrue(legacy.opensOnThisPage { it in known })
        // A conversation of this server already in its directory: here, at once.
        val local = raftMarkdownUrlOpen("$prod/s/$slug/channel/$cid?msg=$mid", null, noHosts)
        assertTrue(local.opensOnThisPage { it in known })
        // Not in it (a task's conversation, another server's, one not loaded): the shell reads it first.
        assertFalse(local.opensOnThisPage { false })
        // A channel alone has no message target here: always the shell, never the browser.
        val channelOnly = raftMarkdownUrlOpen("$prod/s/$slug/channel/$cid", null, noHosts)
        assertEquals(conversation().link, channelOnly.conversationLink)
        assertFalse(channelOnly.opensOnThisPage { true })
        // A task-conversation link always goes through the shell resolver (never opens the parent channel locally).
        val taskLink = raftMarkdownUrlOpen("$prod/s/$slug/channel/$cid?msg=$mid&task=$cid%3Atask%3A$tid", null, noHosts)
        assertEquals(conversation(msg = mid, taskId = tid).link, taskLink.conversationLink)
        assertNull(taskLink.target)
        assertFalse(taskLink.opensOnThisPage { true })
        // Non-task-conversation `?task=` links still fall back to the web page.
        val taskPanel = raftMarkdownUrlOpen("$prod/s/$slug/channel/$cid?task=$cid%3A$mid", null, noHosts)
        assertEquals(RaftMarkdownUrlOpen("$prod/s/$slug/channel/$cid?task=$cid%3A$mid", target = null), taskPanel)
    }
}
