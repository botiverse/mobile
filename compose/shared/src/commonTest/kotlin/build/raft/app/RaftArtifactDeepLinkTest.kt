package build.raft.app

import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.LinkOpenMode
import build.raft.app.data.RaftClientConfigAppHosts
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * slock v1.21.61 #13670: the web keeps an open Artifact in its address,
 * `/s/<slug>/artifacts?artifact=<short id>[&version=<n>]`. Opened from outside the app or tapped in
 * it, that address opens the in-app viewer.
 */
class RaftArtifactDeepLinkTest {
    private val short = "2QthvfPeGfDnqwr7LG6itx"
    private val staging = "https://raft-app-staging.botiverse.dev"

    @AfterTest
    fun clear() {
        RaftArtifactLinkInbox.Process.clear()
        RaftMessageLinkInbox.Process.clear()
        RaftClientConfigAppHosts.publish(emptyList())
    }

    @Test
    fun theDeepLinkIsReadOnAMessageLinksOrigins() {
        assertEquals(
            ArtifactLinkTarget(short, 4),
            parseRaftArtifactDeepLink("https://app.raft.build/s/hq/artifacts?artifact=$short&version=4"),
        )
        assertEquals(ArtifactLinkTarget(short), parseRaftArtifactDeepLink("https://open.raft.build/s/hq/artifacts?artifact=$short"))
        assertEquals(
            ArtifactLinkTarget(short),
            parseRaftArtifactDeepLink("$staging/s/hq/artifacts?artifact=$short&version=x", configuredWebUrl = staging),
        )
        assertEquals(
            ArtifactLinkTarget(short, 2),
            parseRaftArtifactDeepLink("https://raft.example/s/hq/artifacts?artifact=$short&version=2", appHosts = setOf("raft.example")),
        )
        // The share address people send (CLI push, Copy link), with and without a version.
        assertEquals(ArtifactLinkTarget("pt_abc123"), parseRaftArtifactDeepLink("https://app.raft.build/artifacts/pt_abc123"))
        assertEquals(ArtifactLinkTarget("pt_abc123", 2), parseRaftArtifactDeepLink("https://open.raft.build/artifacts/pt_abc123/v/2"))
        assertEquals(ArtifactLinkTarget(short), parseRaftArtifactDeepLink("https://app.raft.build/s/hq/artifacts/$short"))
        for (url in listOf(
            "https://evil.example/s/hq/artifacts?artifact=$short",
            "$staging/s/hq/artifacts?artifact=$short",
            "https://evil.example/artifacts/pt_abc123",
            "https://app.raft.build/artifacts",
            "https://app.raft.build/s/hq/artifacts?artifact=nope",
        )) {
            assertNull(parseRaftArtifactDeepLink(url), url)
        }
    }

    @Test
    fun ingressQueuesTheArtifactNotAMessage() {
        for (url in listOf(
            "https://app.raft.build/s/hq/artifacts?artifact=$short&version=3",
            "raft:///s/hq/artifacts?artifact=$short&version=3",
        )) {
            RaftArtifactLinkInbox.Process.clear()
            assertTrue(RaftMessageLinkIngress.accept(url, source = "test", configuredWebUrl = ""), url)
            assertEquals(ArtifactLinkTarget(short, 3), RaftArtifactLinkInbox.Process.pending.value?.target, url)
            assertNull(RaftMessageLinkInbox.Process.pending.value, url)
        }
        RaftArtifactLinkInbox.Process.clear()
        assertFalse(RaftMessageLinkIngress.accept("https://app.raft.build/s/hq/artifacts", source = "test", configuredWebUrl = ""))
        assertNull(RaftArtifactLinkInbox.Process.pending.value)
    }

    @Test
    fun aTappedHomeAddressOpensTheViewerWhateverTheLinkSetting() {
        val opened = mutableListOf<String>()
        fun navigator(mode: LinkOpenMode, viewerOpens: Boolean = true) =
            RaftLinkNavigator(
                modeProvider = { mode },
                openInAppBrowser = { opened += "in_app:$it"; true },
                openExternalBrowser = { opened += "view:$it" },
                onRejected = { opened += "rejected:$it" },
                openArtifactAddress = { url -> if (url.contains("artifact=")) { opened += "viewer:$url"; viewerOpens } else false },
            )
        val address = "https://app.raft.build/s/hq/artifacts?artifact=$short"
        assertEquals(RaftLinkOpenTarget.ArtifactViewer, navigator(LinkOpenMode.SystemBrowser).open(address).target)
        assertEquals(RaftLinkOpenTarget.ArtifactViewer, navigator(LinkOpenMode.InAppBrowser).open(address).target)
        // Forced to the browser, it is a browser's; with no viewer page here, the link setting's.
        navigator(LinkOpenMode.SystemBrowser).open(address, RaftLinkOpenIntent.ForceExternal)
        navigator(LinkOpenMode.SystemBrowser, viewerOpens = false).open(address)
        navigator(LinkOpenMode.SystemBrowser).open("https://example.test/a")
        assertEquals(
            listOf("viewer:$address", "viewer:$address", "view:$address", "viewer:$address", "view:$address", "view:https://example.test/a"),
            opened,
        )
    }

    @Test
    fun aTappedShareLinkOpensTheViewerOnEveryRaftHost() {
        val opened = mutableListOf<String>()
        var webUrl = staging
        var appHosts = emptySet<String>()
        val navigator =
            RaftLinkNavigator(
                modeProvider = { LinkOpenMode.InAppBrowser },
                openInAppBrowser = { opened += "in_app:$it"; true },
                openExternalBrowser = { opened += "view:$it" },
                onRejected = { opened += "rejected:$it" },
                openArtifactAddress =
                    raftArtifactLinkOpener(webUrl = { webUrl }, appHosts = { appHosts }) { target ->
                        opened += "viewer:${target.id}@${target.version}"
                        true
                    },
            )
        fun open(url: String) = navigator.open(url).target

        // Before the client config loads (no app hosts): the production share address, with and
        // without a version, and the environment's own web.
        assertEquals(RaftLinkOpenTarget.ArtifactViewer, open("https://app.raft.build/artifacts/pt_abc123"))
        assertEquals(RaftLinkOpenTarget.ArtifactViewer, open("https://open.raft.build/artifacts/pt_abc123/v/2"))
        assertEquals(RaftLinkOpenTarget.ArtifactViewer, open("$staging/artifacts/pt_abc123"))
        assertEquals(RaftLinkOpenTarget.ArtifactViewer, open("https://app.raft.build/s/hq/artifacts/$short"))
        // The Artifacts home address still opens it, on production and on the environment's web.
        assertEquals(RaftLinkOpenTarget.ArtifactViewer, open("https://app.raft.build/s/hq/artifacts?artifact=$short&version=4"))
        assertEquals(RaftLinkOpenTarget.ArtifactViewer, open("$staging/s/hq/artifacts?artifact=$short"))
        // A client-config host once it has loaded.
        assertEquals(RaftLinkOpenTarget.InAppBrowser, open("https://raft.example/artifacts/pt_abc123"))
        appHosts = setOf("raft.example")
        assertEquals(RaftLinkOpenTarget.ArtifactViewer, open("https://raft.example/artifacts/pt_abc123"))
        // A production environment does not claim the staging web; nor anyone's other host or path.
        webUrl = ""
        assertEquals(RaftLinkOpenTarget.InAppBrowser, open("$staging/artifacts/pt_abc123"))
        assertEquals(RaftLinkOpenTarget.ArtifactViewer, open("https://app.raft.build/artifacts/pt_abc123"))
        assertEquals(RaftLinkOpenTarget.InAppBrowser, open("https://evil.example/artifacts/pt_abc123"))
        assertEquals(RaftLinkOpenTarget.InAppBrowser, open("https://app.raft.build/artifacts"))
        assertEquals(
            listOf(
                "viewer:pt_abc123@null",
                "viewer:pt_abc123@2",
                "viewer:pt_abc123@null",
                "viewer:$short@null",
                "viewer:$short@4",
                "viewer:$short@null",
                "in_app:https://raft.example/artifacts/pt_abc123",
                "viewer:pt_abc123@null",
                "in_app:$staging/artifacts/pt_abc123",
                "viewer:pt_abc123@null",
                "in_app:https://evil.example/artifacts/pt_abc123",
                "in_app:https://app.raft.build/artifacts",
            ),
            opened,
        )
    }
}
