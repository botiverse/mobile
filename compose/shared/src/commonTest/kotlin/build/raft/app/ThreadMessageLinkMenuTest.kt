package build.raft.app

import build.raft.app.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The link section a long press on a link adds to the message menu (artin, 1.24): Open, Copy link,
 * Share for every link, and Open in browser only for a link that is not Raft's own (a Raft link
 * opens in the app, which is what Open does).
 */
class ThreadMessageLinkMenuTest {
    private val slug = "lagoon-link-menu"
    private val cid = "6f1c2b9e-1d2a-4c7b-9a51-0d3e8f7a6b21"
    private val mid = "0b1c2d3e-4f50-4617-8293-a4b5c6d7e8f9"
    private val staging = "https://raft-app-staging.botiverse.dev"
    private val appHosts = listOf("app.raft.build", "staging.raft.build")

    @Test
    fun anExternalLinkAlsoOffersOpenInBrowser() {
        assertEquals(
            listOf(
                ThreadMessageLinkMenuAction.Open,
                ThreadMessageLinkMenuAction.CopyLink,
                ThreadMessageLinkMenuAction.Share,
                ThreadMessageLinkMenuAction.OpenInBrowser,
            ),
            threadMessageLinkMenuActions(isRaftLink = false),
        )
    }

    @Test
    fun aRaftLinkHasNoOpenInBrowser() {
        assertEquals(
            listOf(
                ThreadMessageLinkMenuAction.Open,
                ThreadMessageLinkMenuAction.CopyLink,
                ThreadMessageLinkMenuAction.Share,
            ),
            threadMessageLinkMenuActions(isRaftLink = true),
        )
    }

    @Test
    fun raftLinksAreTheOnesATapOpensInTheApp() {
        val raft =
            listOf(
                // A message link on the environment's web, and on the production web.
                "$staging/s/$slug/channel/$cid?msg=$mid",
                "https://app.raft.build/s/$slug/channel/$cid?msg=$mid",
                // A Raft Desktop link to a message.
                "raft:///s/$slug/channel/$cid?msg=$mid",
                // An Artifact's address.
                "https://app.raft.build/s/botiverse/artifacts?artifact=2QthvfPeGfDnqwr7LG6itx",
            )
        for (url in raft) assertEquals(true, threadMessageLinkIsRaft(url, staging, appHosts), url)
        val external = listOf("https://example.com/docs", "https://github.com/botiverse/mobile/pull/1", "mailto:a@b.c")
        for (url in external) assertEquals(false, threadMessageLinkIsRaft(url, staging, appHosts), url)
    }

    @Test
    fun eachActionSaysWhatItDoes() {
        // Open and Share are the shared words; Copy link and Open in browser the web's
        // (`message.messageItem.copyLink`, `assistant.wechat.openInBrowser`).
        assertEquals(
            listOf(
                Res.string.common_open,
                Res.string.thread_link_menu_copy_link,
                Res.string.common_share,
                Res.string.thread_link_menu_open_in_browser,
            ),
            ThreadMessageLinkMenuAction.entries.map { it.label() },
        )
    }
}
