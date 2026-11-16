package build.raft.app

import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.LinkOpenMode
import build.raft.app.links.raftMessageBareLinks
import build.raft.app.model.ConversationKind
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
    private val prod = "https://app.raft.build"
    private val noHosts = emptySet<String>()

    private fun route(url: String, webUrl: String? = null, appHosts: Set<String> = noHosts) = raftOwnLinkRoute(url, webUrl, appHosts)

    private fun conversation(kind: ConversationKind = ConversationKind.Channel, msg: String? = null, parent: String? = null) =
        RaftOwnLinkRoute.Conversation(RaftMessageLink(slug, kind, cid, messageId = msg, threadParentMessageId = parent))

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
                // The web's task panel: the app can't open a task by its id, and the channel alone loses it.
                "$prod/s/$slug/channel/$cid?task=$cid%3Atask%3Ab0b0" to RaftOwnLinkRoute.WebPage(RaftOwnLinkWebPageReason.TaskPanel),
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
    fun hostsAreTheEnvironmentsAndAFullyQualifiedDotIsTheSameHost() {
        val link = "/s/$slug/channel/$cid?msg=$mid"
        // Production's two hosts, the client config's app hosts, the Developer environment's web exactly.
        assertEquals(conversation(msg = mid), route("https://open.raft.build$link"))
        assertEquals(conversation(msg = mid), route("https://raft.build$link", appHosts = setOf("raft.build")))
        assertEquals(conversation(msg = mid), route("$staging$link", webUrl = staging))
        // slock #14798: `app.raft.build.` (and an app host written with its dot) is the same host.
        assertEquals(conversation(msg = mid), route("https://app.raft.build.$link"))
        assertEquals(conversation(msg = mid), route("https://APP.raft.build..$link"))
        assertEquals(conversation(msg = mid), route("https://raft.build.$link", appHosts = setOf("raft.build.")))
        assertEquals(conversation(msg = mid), route("$staging.$link", webUrl = staging))
        // Another environment's web, plain http on production, a look-alike: not Raft's here.
        for (url in listOf("$staging$link", "http://app.raft.build$link", "https://app.raft.build.evil.test$link", "https://example.com$link")) {
            assertNull(route(url), url)
            assertFalse(raftOwnLinkIsWebPage(url, webUrl = null, appHosts = noHosts), url)
        }
        // Production stays Raft's in a staging build.
        assertEquals(conversation(msg = mid), route("$prod$link", webUrl = staging))
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
        // The web's task panel: its web page.
        val taskPanel = raftMarkdownUrlOpen("$prod/s/$slug/channel/$cid?task=$cid%3Atask%3Ab0b0", null, noHosts)
        assertEquals(RaftMarkdownUrlOpen("$prod/s/$slug/channel/$cid?task=$cid%3Atask%3Ab0b0", target = null), taskPanel)
    }

    @Test
    fun theNavigatorHandsConversationsToTheShellAndOpensOtherRaftPagesAsWebPages() {
        val inbox = RaftMessageLinkInbox()
        val inviteInbox = RaftExternalInviteLinkInbox()
        val opened = mutableListOf<String>()
        fun navigator(mode: LinkOpenMode) =
            RaftLinkNavigator(
                modeProvider = { mode },
                openInAppBrowser = { opened += "in_app:$it"; true },
                openExternalBrowser = { opened += "view:$it" },
                onRejected = { opened += "rejected:$it" },
                openRaftWebPageInBrowser = { opened += "browser:$it" },
                openOwnLink = raftOwnLinkShellOpener(webUrl = { null }, appHosts = { noHosts }, messageInbox = inbox, inviteInbox = inviteInbox),
                isRaftWebPage = { raftOwnLinkIsWebPage(it, webUrl = null, appHosts = noHosts) },
            )
        val channel = "$prod/s/$slug/channel/$cid"
        assertEquals(RaftLinkOpenTarget.InAppDestination, navigator(LinkOpenMode.SystemBrowser).open(channel).target)
        assertEquals(conversation().link, inbox.pending.value?.link)
        assertEquals(RaftLinkOpenTarget.InAppDestination, navigator(LinkOpenMode.SystemBrowser).open("$prod/external-invite/inv-1").target)
        assertEquals("inv-1", inviteInbox.pending.value?.token)
        // "Open in browser" still means the browser.
        navigator(LinkOpenMode.SystemBrowser).open(channel, RaftLinkOpenIntent.ForceExternal)
        // A Raft page without a screen here: a real browser (not Raft's own App Link), or the in-app browser.
        navigator(LinkOpenMode.SystemBrowser).open("$prod/s/$slug/tasks")
        navigator(LinkOpenMode.InAppBrowser).open("$prod/s/$slug/tasks")
        // Outside links as before.
        navigator(LinkOpenMode.SystemBrowser).open("https://example.com/a")
        assertEquals(
            listOf("view:$channel", "browser:$prod/s/$slug/tasks", "in_app:$prod/s/$slug/tasks", "view:https://example.com/a"),
            opened,
        )
    }
}
