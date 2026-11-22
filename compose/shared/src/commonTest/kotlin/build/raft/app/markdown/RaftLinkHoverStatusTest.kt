package build.raft.app.markdown

import build.raft.app.data.AppLinkApp
import build.raft.app.data.AppLinkPromptApp
import build.raft.app.data.AppLinkResult
import build.raft.app.data.ArtifactHome
import build.raft.app.data.ArtifactKind
import build.raft.app.data.ArtifactSummary
import build.raft.app.data.RaftAppLinkCard
import build.raft.app.data.RaftAppLinkCardElement
import build.raft.app.data.RaftClientConfigAppHosts
import build.raft.app.links.ParsedRaftPermalink
import build.raft.app.raftMarkdownUrlOpen
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The link status: what a hovered link says, how a long one is cut, and who may clear it. */
class RaftLinkHoverStatusTest {
    private val terms = RaftLinkHoverTerms(message = "message", thread = "thread")
    private val staging = "https://staging.raft.build"
    private val names: (ParsedRaftPermalink) -> String? = { target ->
        when (target.channelId.value) {
            "c1" -> "proj-mobile"
            "d1" -> "artin"
            else -> null
        }
    }

    @AfterTest
    fun resetAppHosts() {
        RaftClientConfigAppHosts.publish(emptyList())
    }

    private fun label(
        url: String,
        webUrl: String? = null,
        appHosts: Set<String> = emptySet(),
        forceInApp: Boolean = false,
    ) = raftLinkHoverLabel(url, webUrl, names, terms, appHosts, forceInApp)

    @Test
    fun externalLinkShowsItsFullUrlAsExternal() {
        val url = "https://example.com/docs/a?b=1#c"
        assertEquals(RaftLinkHoverLabel(url, external = true), label(url))
        assertEquals(RaftLinkHoverLabel("mailto:a@b.c", external = true), label(" mailto:a@b.c "))
        assertNull(label("  "))
    }

    @Test
    fun raftMessageLinksReadAsTheirConversation() {
        val msgId = "ae7be26c-daa7-42ca-8480-68d5ac90eaca"
        val parentId = "ced6bfe1-f650-46b3-8859-4d37eb7850d2"
        val cases =
            listOf(
                "https://app.raft.build/s/acme/channel/c1?msg=$msgId" to "#proj-mobile · message",
                "https://app.raft.build/s/acme/channel/c1?msg=$msgId&thread=c1%3A$parentId" to "#proj-mobile · thread",
                "https://app.raft.build/s/acme/dm/d1?msg=$msgId" to "@artin · message",
                "https://open.raft.build/s/acme/channel/c1?msg=$msgId" to "#proj-mobile · message",
                // A Raft Desktop link reads as the web link it stands for.
                "raft:///s/acme/channel/c1?msg=$msgId" to "#proj-mobile · message",
            )
        cases.forEach { (url, expected) -> assertEquals(RaftLinkHoverLabel(expected, external = false), label(url), url) }

        // Staging webUrl and custom appHosts message permalinks resolve both open.target and hover label.
        val stagingMessage = "$staging/s/acme/channel/c1?msg=$msgId"
        val stagingThread = "$staging/s/acme/channel/c1?msg=$msgId&thread=c1%3A$parentId"
        val stagingDm = "$staging/s/acme/dm/d1?msg=$msgId"
        assertNotNull(raftMarkdownUrlOpen(stagingMessage, webUrl = staging, appHosts = emptySet()).target)
        assertEquals(RaftLinkHoverLabel("#proj-mobile · message", external = false), label(stagingMessage, webUrl = staging))
        assertEquals(RaftLinkHoverLabel("#proj-mobile · thread", external = false), label(stagingThread, webUrl = staging))
        assertEquals(RaftLinkHoverLabel("@artin · message", external = false), label(stagingDm, webUrl = staging))
        // Outside its configured staging environment, a staging permalink leaves the app.
        assertEquals(RaftLinkHoverLabel(stagingMessage, external = true), label(stagingMessage, webUrl = null))

        val customHostMessage = "https://raft.example/s/acme/channel/c1?msg=$msgId"
        assertNotNull(raftMarkdownUrlOpen(customHostMessage, webUrl = null, appHosts = setOf("raft.example")).target)
        assertEquals(
            RaftLinkHoverLabel("#proj-mobile · message", external = false),
            label(customHostMessage, appHosts = setOf("raft.example")),
        )
    }

    @Test
    fun raftLinksWithoutAKnownNameShowTheirWebLinkInApp() {
        // Message link to a conversation the page does not know: its web link, still in-app.
        val unknown = "https://app.raft.build/s/acme/channel/c9?msg=ae7be26c-daa7-42ca-8480-68d5ac90eaca"
        assertEquals(RaftLinkHoverLabel(unknown, external = false), label(unknown))
        // A Raft Desktop link shows its environment's web link and stays with Raft.
        assertEquals(RaftLinkHoverLabel("$staging/s/acme/tasks", external = false), label("raft:///s/acme/tasks", staging))
        assertEquals(RaftLinkHoverLabel("$staging/s/acme/channel/c9?msg=ae7be26c-daa7-42ca-8480-68d5ac90eaca", external = false), label("raft:///s/acme/channel/c9?msg=ae7be26c-daa7-42ca-8480-68d5ac90eaca", staging))
    }

    @Test
    fun raftArtifactAndOtherInAppLinksAreNotExternal() {
        val artifactId = "pt_0b6e0f7e-1c1a-4c4e-9a65-0f5e2b1d7a11"
        val shortId = "2QthvfPeGfDnqwr7LG6itx"
        val inAppProd =
            listOf(
                "https://app.raft.build/artifacts/$artifactId",
                "https://app.raft.build./artifacts/$artifactId",
                "https://app.raft.build/artifacts/$shortId/v/2",
                "https://open.raft.build/artifacts/$artifactId",
                "https://app.raft.build/s/acme/artifacts/$artifactId",
                "https://app.raft.build/s/acme/artifacts?artifact=$shortId&version=4",
                "https://app.raft.build/s/acme/channel/c1",
                "https://app.raft.build/join/tok-1",
                "https://app.raft.build/external-invite/inv-1",
            )
        inAppProd.forEach { url ->
            assertEquals(RaftLinkHoverLabel(url, external = false), label(url), url)
        }
        // Raft Desktop Artifact links map onto the environment web URL and stay in-app.
        assertEquals(
            RaftLinkHoverLabel("https://app.raft.build/s/acme/artifacts/$artifactId", external = false),
            label("raft:///s/acme/artifacts/$artifactId"),
        )
        assertEquals(
            RaftLinkHoverLabel("$staging/s/acme/artifacts/$artifactId", external = false),
            label("raft:///s/acme/artifacts/$artifactId", webUrl = staging),
        )
        val inAppStaging =
            listOf(
                "$staging/artifacts/$artifactId",
                "$staging/s/acme/artifacts/$artifactId/v/3",
                "$staging/s/acme/artifacts?artifact=$shortId",
                "$staging/join/tok-1",
            )
        inAppStaging.forEach { url ->
            assertEquals(RaftLinkHoverLabel(url, external = false), label(url, webUrl = staging), url)
        }
        // Custom appHosts from client config also stay in-app.
        val customHostArtifact = "https://raft.example/artifacts/$artifactId"
        assertEquals(
            RaftLinkHoverLabel(customHostArtifact, external = false),
            label(customHostArtifact, appHosts = setOf("raft.example")),
        )
        // Plain links on redirect/content Artifact hosts, plain http, userinfo, non-default port,
        // Raft web pages without an in-app screen, other environments, lookalikes, and third-party
        // URLs all leave the app unless opened by an Artifact chip (forceInApp = true).
        val redirectArtifactUrl = "https://artifacts.raft.build/$artifactId"
        val contentHostArtifactUrl = "https://a-pt-0b6e0f7e-1c1a-4c4e-9a65-0f5e2b1d7a11.raftusercontent.com/index.html"
        val httpProdArtifactUrl = "http://app.raft.build/artifacts/$artifactId"
        val externalCases =
            listOf(
                redirectArtifactUrl,
                contentHostArtifactUrl,
                httpProdArtifactUrl,
                "http://artifacts.raft.build/$artifactId",
                "https://evil.example@artifacts.raft.build/$artifactId",
                "https://user@app.raft.build/artifacts/$artifactId",
                "https://app.raft.build:8443/artifacts/$artifactId",
                "https://app.raft.build/s/acme/tasks",
                "https://app.raft.build/s/acme/artifacts",
                "https://app.raft.build/s/acme/channel/c1?task=c1%3Atask%3Ab0b0",
                "$staging/artifacts/$artifactId",
                "https://app.raft.build.evil.example/artifacts/$artifactId",
                "https://evil.example/artifacts/$artifactId",
                "https://a1b2.w.raftusercontent.com/artifacts/$artifactId",
                "https://github.com/botiverse/mobile/pull/5295",
                "https://stamp.build/botiverse/slock/pull/10449",
            )
        externalCases.forEach { url ->
            assertEquals(RaftLinkHoverLabel(url, external = true), label(url), url)
        }
        // When opened directly by an Artifact chip (opensInApp / forceInApp = true), these stay in-app.
        listOf(redirectArtifactUrl, contentHostArtifactUrl, httpProdArtifactUrl).forEach { url ->
            assertEquals(RaftLinkHoverLabel(url, external = false), label(url, forceInApp = true), url)
        }
    }

    @Test
    fun middleEllipsisKeepsBothEnds() {
        assertEquals("short", raftMiddleEllipsis("short", 10))
        assertEquals("abcd…xyz", raftMiddleEllipsis("abcdefghijklmnopqrstuvwxyz", 8))
        assertEquals("a…z", raftMiddleEllipsis("abcdefghijklmnopqrstuvwxyz", 1))
    }

    @Test
    fun fitMiddleEllipsisTakesTheLongestCutThatFits() {
        val url = "https://example.com/a/very/long/path/to/some/document.pdf"
        assertEquals(url, raftFitMiddleEllipsis(url) { true })
        val fitted = raftFitMiddleEllipsis(url) { it.length <= 30 }
        assertEquals(30, fitted.length)
        assertTrue(fitted.startsWith("https://example") && fitted.endsWith("document.pdf"), fitted)
        // Nothing fits: the shortest cut, never empty.
        assertEquals(12, raftFitMiddleEllipsis(url) { false }.length)
    }

    @Test
    fun hoverableLinksAreUrlLinksAndAppLinkCards() {
        val text =
            buildAnnotatedString {
                append("see ")
                withLink(LinkAnnotation.Url("https://example.com")) { append("example") }
                append(" and ")
                pushStringAnnotation(RaftAppLinkHoverTag, "https://app.example/item/1")
                withLink(LinkAnnotation.Clickable(RaftAppLinkCardTag, linkInteractionListener = null)) { append("card") }
                pop()
                append(" ")
                withLink(LinkAnnotation.Clickable("mention-1", linkInteractionListener = null)) { append("@artin") }
            }
        assertEquals(
            listOf(
                RaftHoverableLink(4, 11, "https://example.com"),
                RaftHoverableLink(16, 20, "https://app.example/item/1"),
            ),
            text.raftHoverableLinks(),
        )
    }

    @Test
    fun hoverableLinksIncludeArtifactAndConsentChipsAndIgnoreNonUrlTags() {
        val labelledUrl = "https://app.raft.build/artifacts/pt_0b6e0f7e-1c1a-4c4e-9a65-0f5e2b1d7a11"
        val chipUrl = "https://app.raft.build/s/acme/artifacts/pt_0b6e0f7e-1c1a-4c4e-9a65-0f5e2b1d7a11"
        val consentUrl = "https://github.com/botiverse/mobile/pull/5295"
        val text =
            buildAnnotatedString {
                pushStringAnnotation(RaftMarkdownLinkUrlTag, labelledUrl)
                withLink(LinkAnnotation.Clickable(RaftArtifactLabelledLinkTag, linkInteractionListener = null)) {
                    append("Q3 report")
                }
                pop()
                append(" ")
                pushStringAnnotation(RaftMarkdownLinkUrlTag, chipUrl)
                withLink(LinkAnnotation.Clickable(RaftArtifactLinkTag, linkInteractionListener = null)) {
                    append("Artifact chip")
                }
                pop()
                append(" ")
                pushStringAnnotation(RaftMarkdownLinkUrlTag, consentUrl)
                withLink(LinkAnnotation.Clickable(RaftAppLinkConsentTag, linkInteractionListener = null)) {
                    append("Consent chip")
                }
                pop()
                append(" ")
                withLink(LinkAnnotation.Clickable("channel-1", linkInteractionListener = null)) { append("#proj-mobile") }
                append(" ")
                withLink(LinkAnnotation.Clickable("task-243", linkInteractionListener = null)) { append("#243") }
                // Even a body-wide click annotation overlapping RaftMarkdownLinkUrlTag stays excluded.
                addLink(LinkAnnotation.Clickable(RaftMarkdownBodyClickTag, linkInteractionListener = null), 0, length)
            }
        assertEquals(
            listOf(
                RaftHoverableLink(0, 9, labelledUrl, opensInApp = true),
                RaftHoverableLink(10, 23, chipUrl, opensInApp = true),
                RaftHoverableLink(24, 36, consentUrl, opensInApp = false),
            ),
            text.raftHoverableLinks(),
        )
    }

    @Test
    fun appLinkChipsProduceHoverableLinksWithExpectedExternalClassification() {
        val artifactId = "pt_0b6e0f7e-1c1a-4c4e-9a65-0f5e2b1d7a11"
        val permalinkUrl = "https://app.raft.build/s/acme/channel/c1?msg=ae7be26c-daa7-42ca-8480-68d5ac90eaca"
        val labelledArtifactUrl = "https://app.raft.build/artifacts/$artifactId"
        val redirectArtifactUrl = "https://artifacts.raft.build/$artifactId"
        val titleOnlyCardUrl = "https://stamp.build/botiverse/slock/pull/10450"
        val cardUrl = "https://stamp.build/botiverse/slock/pull/10449"
        val consentUrl = "https://github.com/botiverse/mobile/pull/5295"
        val pendingUrl = "https://linear.app/raft/issue/RAF-1"
        val summary =
            ArtifactSummary(
                id = artifactId,
                title = "Q3 report",
                description = null,
                kind = ArtifactKind.Html,
                version = 4,
                pinned = false,
                latestVersion = 4,
                home = ArtifactHome.Conversation("proj-mobile", dm = false),
            )
        val cardBody = RaftAppLinkCard(root = "state", elements = mapOf("state" to RaftAppLinkCardElement.Badge("Merged")))
        val host =
            RaftAppLinkChipHost(
                results =
                    mapOf(
                        titleOnlyCardUrl to AppLinkResult.Card(titleOnlyCardUrl, AppLinkApp("Stamp", null), "Title only", card = null),
                        cardUrl to AppLinkResult.Card(cardUrl, AppLinkApp("Stamp", null), "Fix hover", card = cardBody),
                        consentUrl to AppLinkResult.Consent(consentUrl, AppLinkPromptApp("client-gh", "GitHub", null)),
                        // AppLinkResult.Pending renders as a non-pressable chip with no LinkAnnotation, so it is intentionally non-hoverable.
                        pendingUrl to AppLinkResult.Pending(pendingUrl),
                    ),
                onAsk = {},
                onOpenCard = {},
                artifacts = mapOf(artifactId to summary),
                artifactHosts = listOf("app.raft.build"),
                onOpenArtifact = {},
                onOpenArtifactInPlace = { _, _ -> },
            )
        val source =
            buildAnnotatedString {
                append("See ")
                withLink(LinkAnnotation.Url(permalinkUrl)) { append(permalinkUrl) }
                append(", ")
                withLink(LinkAnnotation.Url(labelledArtifactUrl)) { append("the report") }
                append(", ")
                withLink(LinkAnnotation.Url(redirectArtifactUrl)) { append(redirectArtifactUrl) }
                append(", ")
                withLink(LinkAnnotation.Url(titleOnlyCardUrl)) { append(titleOnlyCardUrl) }
                append(", ")
                withLink(LinkAnnotation.Url(cardUrl)) { append(cardUrl) }
                append(", ")
                withLink(LinkAnnotation.Url(consentUrl)) { append(consentUrl) }
                append(", and ")
                withLink(LinkAnnotation.Url(pendingUrl)) { append(pendingUrl) }
            }
        val rendered =
            source
                .withRaftMessagePermalinkChips(names)
                .withRaftAppLinkChipContent(host) {}
                .text
                .withRaftInlineChipGapLinks()
                .withRaftMarkdownBodyClick {}
        val chipExtents = rendered.raftInlineChipExtents()
        // 6 boxed chips: permalink, Artifact chip, title-only Card, card-body Card, Consent, Pending.
        assertEquals(6, chipExtents.size)
        val labelledStart = rendered.text.indexOf("the report")
        val labelledEnd = labelledStart + "the report".length
        val hoverable = rendered.raftHoverableLinks()
        assertEquals(
            listOf(
                RaftHoverableLink(chipExtents[0].first, chipExtents[0].last + 1, permalinkUrl, opensInApp = false),
                RaftHoverableLink(labelledStart, labelledEnd, labelledArtifactUrl, opensInApp = true),
                RaftHoverableLink(chipExtents[1].first, chipExtents[1].last + 1, redirectArtifactUrl, opensInApp = true),
                RaftHoverableLink(chipExtents[2].first, chipExtents[2].last + 1, titleOnlyCardUrl, opensInApp = false),
                RaftHoverableLink(chipExtents[3].first, chipExtents[3].last + 1, cardUrl, opensInApp = false),
                RaftHoverableLink(chipExtents[4].first, chipExtents[4].last + 1, consentUrl, opensInApp = false),
            ),
            hoverable,
        )
        // Every boxed chip's single coalesced range starts with its leading gap and ends with its trailing gap.
        listOf(hoverable[0], hoverable[2], hoverable[3], hoverable[4], hoverable[5]).forEach { chip ->
            assertEquals("\uFFFC\u2060", rendered.text.substring(chip.start, chip.start + 2), chip.url)
            assertEquals("\u2060\uFFFC", rendered.text.substring(chip.end - 2, chip.end), chip.url)
        }
        // Only the card-body chip carries RaftAppLinkHoverTag, and never duplicated on painted flanks.
        assertEquals(listOf(cardUrl), rendered.raftMarkdownAppLinkSpans().map { it.url })
        assertEquals(
            listOf(
                RaftLinkHoverLabel("#proj-mobile · message", external = false),
                RaftLinkHoverLabel(labelledArtifactUrl, external = false),
                RaftLinkHoverLabel(redirectArtifactUrl, external = false),
                RaftLinkHoverLabel(titleOnlyCardUrl, external = true),
                RaftLinkHoverLabel(cardUrl, external = true),
                RaftLinkHoverLabel(consentUrl, external = true),
            ),
            hoverable.map { label(it.url, forceInApp = it.opensInApp) },
        )
        // Contrast: the same redirect host as a plain non-chip link leaves the app (external = true).
        assertEquals(RaftLinkHoverLabel(redirectArtifactUrl, external = true), label(redirectArtifactUrl))
    }

    @Test
    fun onlyTheTextShowingTheStatusClearsIt() {
        val state = RaftLinkHoverStatusState()
        val first = Any()
        val second = Any()
        val a = RaftLinkHoverLabel("https://a.example", external = true)
        val b = RaftLinkHoverLabel("#proj-mobile · message", external = false)
        state.show(first, a)
        state.show(second, b)
        // The first text's late exit leaves the second text's status up.
        state.hide(first)
        assertEquals(b, state.label)
        state.hide(second)
        assertNull(state.label)
    }
}
