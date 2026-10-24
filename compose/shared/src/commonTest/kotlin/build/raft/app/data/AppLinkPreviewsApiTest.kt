package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * App link previews wire (artin #20 lane L). Payloads are the server's own answers, copied from
 * botiverse/slock staging 2d4f961df `packages/server/src/routes/appLinkPreviewPrompt.api.test.ts`
 * ("one prompt per App, on its first link") and `appLinkPreview.api.test.ts` (`appIcons`).
 */
class AppLinkPreviewsApiTest {
    private val promptsAnswer =
        """
        {"previews":[],"prompts":[
          {"url":"https://alpha.example.test/pr/1","app":{"id":"client-alpha","name":"prompt-alpha","icon":null}},
          {"url":"https://beta.example.test/pr/9","app":{"id":"client-beta","name":"prompt-beta","icon":"https://cdn.example.test/beta.webp"}}
        ],"appIcons":{}}
        """.trimIndent()

    private val previewAnswer =
        """
        {"previews":[{"version":1,"title":"Private PR","url":"https://preview.example.test/pr/1","appName":"preview-fixture"}],
         "prompts":[],"appIcons":{"https://preview.example.test/pr/1":"/api/integration-logos/preview-fixture/logo.webp"}}
        """.trimIndent()

    @Test
    fun promptsBecomeConsentResultsWithRaftsStoredIcon() {
        val results = parseAppLinkPreviewAnswer(promptsAnswer)!!
        assertEquals(
            listOf(
                AppLinkResult.Consent("https://alpha.example.test/pr/1", AppLinkPromptApp("client-alpha", "prompt-alpha", null)),
                AppLinkResult.Consent(
                    "https://beta.example.test/pr/9",
                    AppLinkPromptApp("client-beta", "prompt-beta", "https://cdn.example.test/beta.webp"),
                ),
            ),
            results.values.toList(),
        )
    }

    @Test
    fun previewsBecomeCardsWithTheIconFromAppIcons() {
        val results = parseAppLinkPreviewAnswer(previewAnswer) { resolveAppLinkIconUrl(it, "https://api.raft.build/api/") }!!
        assertEquals(
            AppLinkResult.Card(
                url = "https://preview.example.test/pr/1",
                app = AppLinkApp("preview-fixture", "https://api.raft.build/api/integration-logos/preview-fixture/logo.webp"),
                title = "Private PR",
            ),
            results["https://preview.example.test/pr/1"],
        )
    }

    @Test
    fun aPreviewWinsOverAPromptAndAnAppIsAskedOnce() {
        val body =
            """
            {"previews":[{"version":1,"title":"PR","url":"https://a.example.test/pr/1","appName":"A"}],
             "prompts":[{"url":"https://a.example.test/pr/1","app":{"id":"a","name":"A","icon":null}},
                        {"url":"https://b.example.test/1","app":{"id":"b","name":"B","icon":null}},
                        {"url":"https://b.example.test/2","app":{"id":"b","name":"B","icon":null}}]}
            """.trimIndent()
        val results = parseAppLinkPreviewAnswer(body)!!
        assertTrue(results["https://a.example.test/pr/1"] is AppLinkResult.Card)
        assertTrue(results["https://b.example.test/1"] is AppLinkResult.Consent)
        assertNull(results["https://b.example.test/2"])
    }

    @Test
    fun oneUnreadablePreviewDropsThePreviewsButNotThePrompts() {
        val body =
            """
            {"previews":[{"version":1,"title":"","url":"https://a.example.test/1","appName":"A"}],
             "prompts":[{"url":"https://b.example.test/1","app":{"id":"b","name":"B","icon":null}}],"newField":1}
            """.trimIndent()
        val results = parseAppLinkPreviewAnswer(body)!!
        assertEquals(listOf("https://b.example.test/1"), results.keys.toList())
        // A version this client doesn't know is not a card either.
        val v2 = """{"previews":[{"version":2,"title":"T","url":"https://a.example.test/1","appName":"A"}]}"""
        assertTrue(parseAppLinkPreviewAnswer(v2)!!.isEmpty())
        // An http link or a prompt without its App's name is no prompt.
        val badPrompt = """{"previews":[],"prompts":[{"url":"http://b.example.test/1","app":{"id":"b","name":"B","icon":null}}]}"""
        assertTrue(parseAppLinkPreviewAnswer(badPrompt)!!.isEmpty())
    }

    @Test
    fun anOlderServerAnswerAndAFailedBody() {
        assertTrue(parseAppLinkPreviewAnswer("""{"previews":[]}""")!!.isEmpty())
        assertNull(parseAppLinkPreviewAnswer("<html>"))
    }

    @Test
    fun previewHrefFollowsPublicPreviewUrl() {
        assertEquals("https://example.com/", appLinkPreviewHref("https://Example.COM"))
        assertEquals("https://stamp.build/a/b?x=1", appLinkPreviewHref("https://stamp.build/a/b?x=1"))
        assertNull(appLinkPreviewHref("http://example.com/a"))
        assertNull(appLinkPreviewHref("https://user:pw@example.com/a"))
        assertNull(appLinkPreviewHref("https://example.com:8443/a"))
        assertNull(appLinkPreviewHref("https://example.com/a#top"))
        assertNull(appLinkPreviewHref("https://localhost/a"))
        assertNull(appLinkPreviewHref("https://10.0.0.1/a"))
        assertNull(appLinkPreviewHref("https://[::1]/a"))
        assertNull(appLinkPreviewHref("https://exämple.com/a"))
        assertNull(appLinkPreviewHref("https://example.com/" + "a".repeat(2000)))
    }

    @Test
    fun domainAndShortAddress() {
        assertEquals("stamp.build", appLinkDomain("https://www.stamp.build/botiverse/slock/pull/10449?tab=files"))
        assertEquals("someone.github.io", appLinkDomain("https://someone.github.io/"))
        assertEquals("stamp.build/botiverse/slock/pull/10449", appLinkShortAddress("https://www.stamp.build/botiverse/slock/pull/10449?tab=files"))
        assertEquals("stamp.build", appLinkShortAddress("https://stamp.build/"))
        assertNull(appLinkDomain("mailto:a@b.c"))
    }

    @Test
    fun onlyBareLinksOutsideCodeAreAsked() {
        val content =
            """
            See https://a.example.test/pr/1, and [the PR](https://a.example.test/pr/2) and
            [https://a.example.test/pr/3](https://a.example.test/pr/3) and <https://a.example.test/pr/4>.
            `https://a.example.test/pr/5` ![img](https://a.example.test/pr/6)
            ```
            https://a.example.test/pr/7
            ```
            again https://a.example.test/pr/1 and https://a.example.test/pr/8
            """.trimIndent()
        assertEquals(
            listOf("https://a.example.test/pr/1", "https://a.example.test/pr/3", "https://a.example.test/pr/4"),
            appLinkMessagePreviewUrls(content),
        )
        assertEquals(
            listOf(
                "https://a.example.test/pr/1",
                "https://a.example.test/pr/3",
                "https://a.example.test/pr/4",
                "https://a.example.test/pr/8",
            ),
            appLinkMessagePreviewUrls(content, limit = 10),
        )
        // GFM autolink: a closing parenthesis it didn't open and trailing punctuation are not the link.
        assertEquals(listOf("https://x.example.test/a"), appLinkMessagePreviewUrls("(see https://x.example.test/a).", limit = 10))
    }

    @Test
    fun consentAnswers() {
        assertEquals(AppLinkConsentAnswer.Allowed, appLinkConsentAnswerFor(HomeNetworkResponse(200, "{}"), AppLinkConsentAnswer.Allowed))
        assertEquals(AppLinkConsentAnswer.Unavailable, appLinkConsentAnswerFor(HomeNetworkResponse(404, null), AppLinkConsentAnswer.Allowed))
        assertEquals(AppLinkConsentAnswer.Unavailable, appLinkConsentAnswerFor(HomeNetworkResponse(409, null), AppLinkConsentAnswer.Allowed))
        assertEquals(AppLinkConsentAnswer.Failed, appLinkConsentAnswerFor(HomeNetworkResponse(503, null), AppLinkConsentAnswer.Declined))
        assertEquals(AppLinkConsentAnswer.Failed, appLinkConsentAnswerFor(null, AppLinkConsentAnswer.Declined))
    }

    @Test
    fun iconUrls() {
        assertEquals("https://cdn.example.test/a.webp", resolveAppLinkIconUrl("https://cdn.example.test/a.webp", "https://api.raft.build/api/"))
        assertEquals(
            "https://api-aws-staging.botiverse.dev/api/integration-logos/x.webp",
            resolveAppLinkIconUrl("/api/integration-logos/x.webp", "https://api-aws-staging.botiverse.dev/api/"),
        )
        assertNull(resolveAppLinkIconUrl("//evil.example/x.png", "https://api.raft.build/api/"))
        assertNull(resolveAppLinkIconUrl("data:image/png;base64,AAAA", "https://api.raft.build/api/"))
        assertEquals("client%2Fid%20x", "client/id x".appLinkPathSegment())
    }
}
