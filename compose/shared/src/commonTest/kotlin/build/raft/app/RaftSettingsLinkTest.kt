package build.raft.app

import build.raft.app.data.StoredAuthServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Raft web Settings links open the in-app Settings page instead of the browser. */
class RaftSettingsLinkTest {
    private val prod = "https://app.raft.build"
    private val staging = "https://staging.raft.build"
    private val noAppHosts = emptySet<String>()
    private val servers =
        listOf(
            StoredAuthServer(id = "srv-hq", name = "HQ", slug = "hq"),
            StoredAuthServer(id = "srv-lab", name = "Lab", slug = "lab"),
        )

    private fun parse(url: String, configured: String? = null) =
        parseRaftSettingsLink(url, configuredWebUrl = configured, appHosts = noAppHosts)

    private fun key(url: String, configured: String? = null) = parse(url, configured)?.destinationKey

    @Test
    fun everyMappedWebTabOpensItsInAppPage() {
        val expected =
            mapOf(
                "account" to SettingsDestinationAccount,
                "language-region" to SettingsNativeDetailPage.LanguageRegion.key,
                "appearance" to SettingsNativeDetailPage.Appearance.key,
                "notifications" to SettingsNativeDetailPage.Notifications.key,
                "browser" to SettingsNativeDetailPage.Notifications.key,
                "theme" to SettingsNativeDetailPage.Theme.key,
                "server-notifications" to SettingsNativeDetailPage.ServerNotifications.key,
                "server" to SettingsNativeDetailPage.Server.key,
                "billing" to SettingsDestinationBilling,
                "administration" to SettingsDestinationAdministration,
                "moderation" to SettingsDestinationAdministration,
                "im-bridges" to SettingsDestinationImBridges,
                "applications" to SettingsDestinationConnectedApps,
                "integrations" to SettingsDestinationConnectedApps,
                "mcp" to SettingsDestinationConnectedApps,
                "mcp-servers" to SettingsDestinationConnectedApps,
                "computers" to SettingsDestinationComputers,
                "about" to SettingsNativeDetailPage.About.key,
                "feedback" to SettingsDestinationHandsFeedback,
            )
        for ((tab, page) in expected) {
            assertEquals(page, key("$prod/settings/$tab"), tab)
            assertEquals(page, key("$prod/s/hq/settings/$tab"), tab)
            val link = parse("$prod/s/hq/settings/$tab")
            assertEquals("hq", link?.serverSlug, tab)
            assertEquals(tab, link?.webTab, tab)
        }
    }

    @Test
    fun themeLinkOpensThemeSettings() {
        val link = parse("$prod/settings/theme")
        assertEquals(SettingsNativeDetailPage.Theme.key, link?.destinationKey)
        assertNull(link?.serverSlug)
        assertNull(link?.section)
    }

    @Test
    fun plainAccountLinkOpensAccountSettings() {
        val link = parse("$prod/settings/account")
        assertEquals(SettingsDestinationAccount, link?.destinationKey)
        assertNull(link?.section)
        assertEquals(SettingsDestinationAccount, key("$prod/s/hq/settings/account?x=1"))
    }

    @Test
    fun accountLoginSecurityBlocksOpenTheInAppAccountAtTheirRow() {
        for ((url, anchor) in listOf(
            "$prod/settings/account#two-step" to "two-step",
            "$prod/s/hq/settings/account#two-step" to "two-step",
            "$prod/s/hq/settings/account#passkeys" to "passkeys",
        )) {
            val link = assertNotNull(parse(url), url)
            assertEquals(SettingsDestinationAccount, link.destinationKey, url)
            assertEquals(anchor, link.inAppAnchor, url)
        }
    }

    @Test
    fun accountLinkToABlockTheAppLacksStaysAWebLink() {
        assertNull(parse("$prod/s/hq/settings/account?x=1#something-new"))
        // An empty fragment names no block.
        assertEquals(SettingsDestinationAccount, key("$prod/settings/account#"))
        assertNull(parse("$prod/settings/account#")?.inAppAnchor)
        // Other tabs open at their top whatever block they name.
        assertEquals(SettingsNativeDetailPage.Theme.key, key("$prod/s/hq/settings/theme?x=1#y"))
        assertNull(parse("$prod/s/hq/settings/theme?x=1#y")?.inAppAnchor)
    }

    @Test
    fun unknownOrUnmappedSubPageOpensSettingsHome() {
        for (url in listOf(
            "$prod/settings/unknown-section",
            "$prod/s/hq/settings/labs",
            "$prod/s/hq/settings/providers",
            "$prod/s/hq/settings/shared-links",
            "$prod/settings",
            "$prod/s/hq/settings/",
        )) {
            val link = assertNotNull(parse(url), url)
            assertNull(link.destinationKey, url)
        }
        assertEquals("unknown-section", parse("$prod/settings/unknown-section")?.webTab)
    }

    @Test
    fun deeperSegmentsOpenTheirTab() {
        assertEquals(SettingsDestinationComputers, key("$prod/s/hq/settings/computers/abc123"))
        assertEquals(SettingsDestinationHandsFeedback, key("$prod/s/hq/settings/feedback/ticket-1"))
    }

    @Test
    fun onlyRaftOwnOriginsAreSettingsLinks() {
        assertEquals(SettingsNativeDetailPage.Theme.key, key("https://open.raft.build/settings/theme"))
        // Staging: only as the configured environment web URL.
        assertEquals(SettingsNativeDetailPage.Theme.key, key("$staging/s/hq/settings/theme", configured = staging))
        assertNull(parse("$staging/s/hq/settings/theme"))
        assertEquals(
            SettingsNativeDetailPage.Theme.key,
            parseRaftSettingsLink("https://raft.build/settings/theme", appHosts = setOf("raft.build"))?.destinationKey,
        )
        for (url in listOf(
            "https://example.com/settings/theme",
            "https://app.raft.build.evil.example/settings/theme",
            "http://app.raft.build/settings/theme",
            "https://user@app.raft.build/settings/theme",
            "/settings/theme",
        )) {
            assertNull(parse(url), url)
        }
    }

    @Test
    fun nonSettingsRaftPathsAreNotSettingsLinks() {
        for (url in listOf(
            "$prod/",
            "$prod/apps",
            "$prod/s/hq/apps",
            "$prod/s/hq/channel/89dce17f-1cd3-4db8-b08f-7b4141004b5a",
            "$prod/s/hq/settingsx/theme",
            "$prod/s/hq/x/settings/theme",
            "$prod/s/bad%20slug/settings/theme",
            "$prod/settings//theme",
        )) {
            assertNull(parse(url), url)
        }
    }

    @Test
    fun serverSlugIsRespected() {
        val current = raftSettingsLinkTarget(parse("$prod/s/hq/settings/theme")!!, "srv-hq", servers)
        assertEquals(RaftSettingsLinkTarget(null, SettingsNativeDetailPage.Theme.key), current)
        val other = raftSettingsLinkTarget(parse("$prod/s/LAB/settings/theme")!!, "srv-hq", servers)
        assertEquals(RaftSettingsLinkTarget("srv-lab", SettingsNativeDetailPage.Theme.key), other)
        val none = raftSettingsLinkTarget(parse("$prod/settings/account")!!, "srv-hq", servers)
        assertEquals(RaftSettingsLinkTarget(null, SettingsDestinationAccount), none)
        assertNull(raftSettingsLinkTarget(parse("$prod/s/elsewhere/settings/theme")!!, "srv-hq", servers))
    }

    private fun action(href: String, webBase: String = prod) =
        announcementLinkAction(href, webBase, currentServerId = "srv-hq", servers = servers, appHosts = noAppHosts)

    @Test
    fun announcementChooseThemeOpensInAppThemeSettings() {
        val action = assertIs<AnnouncementLinkAction.OpenSettings>(action("/settings/theme"))
        assertEquals("hq", action.link.serverSlug)
        assertEquals(RaftSettingsLinkTarget(null, SettingsNativeDetailPage.Theme.key), action.target)
        // The same link written absolute, on production and on the staging environment.
        assertIs<AnnouncementLinkAction.OpenSettings>(action("$prod/settings/theme"))
        assertIs<AnnouncementLinkAction.OpenSettings>(action("$staging/s/hq/settings/theme", webBase = staging))
    }

    @Test
    fun announcementAccountLinkOpensInAppAccountSettings() {
        val action = assertIs<AnnouncementLinkAction.OpenSettings>(action("/settings/account"))
        assertEquals(RaftSettingsLinkTarget(null, SettingsDestinationAccount), action.target)
    }

    @Test
    fun announcementTwoStepLinkOpensTheInAppLoginSecurityGroup() {
        // dm:@artin #97: the passkey announcement's button lands on Account › Login & security
        // (#proj-mobile:#121), whose rows open the web section in the system browser.
        for ((href, webBase) in listOf(
            "/settings/account#two-step" to prod,
            "$prod/s/hq/settings/account#two-step" to prod,
            "/settings/account#two-step" to staging,
        )) {
            val opened = assertIs<AnnouncementLinkAction.OpenSettings>(action(href, webBase = webBase), href)
            assertEquals(RaftSettingsLinkTarget(null, SettingsDestinationAccount), opened.target, href)
            assertEquals("two-step", opened.link.inAppAnchor, href)
        }
        assertEquals("passkeys", assertIs<AnnouncementLinkAction.OpenSettings>(action("/settings/account#passkeys")).link.inAppAnchor)
        // A block the app does not have is still Raft's web page, in a browser.
        val unknown = AnnouncementLinkAction.OpenUrl("$prod/s/hq/settings/account#unknown-block", raftWebPage = true)
        assertEquals(unknown, action("/settings/account#unknown-block"))
        assertEquals(RaftLinkOpenIntent.RaftWebPage, unknown.openIntent)
    }

    @Test
    fun announcementLinksElsewhereStayWebLinks() {
        val blog = AnnouncementLinkAction.OpenUrl("https://example.com/blog")
        assertEquals(blog, action("https://example.com/blog"))
        // Another site is an ordinary web link: the link setting alone decides where it opens.
        assertEquals(RaftLinkOpenIntent.Ordinary, blog.openIntent)
        // An in-app path that is not Settings opens on the current server, as the web's.
        assertEquals(AnnouncementLinkAction.OpenUrl("$prod/s/hq/apps", raftWebPage = true), action("/apps"))
        assertEquals(AnnouncementLinkAction.OpenUrl("$prod/download", raftWebPage = true), action("/download"))
        // Another account's server: the app cannot show it.
        assertEquals(
            AnnouncementLinkAction.OpenUrl("$prod/s/elsewhere/settings/theme", raftWebPage = true),
            action("/s/elsewhere/settings/theme"),
        )
        assertNull(action("//evil.example/settings/theme"))
        assertNull(action("javascript:alert(1)"))
    }

    @Test
    fun announcementRelativeLinksTakeTheCurrentServer() {
        assertEquals("$prod/s/hq/settings/theme", resolveAnnouncementLink("/settings/theme", prod, "hq"))
        assertEquals("$prod/settings/theme", resolveAnnouncementLink("/settings/theme", prod, null))
        assertEquals("$prod/s/lab/settings/theme", resolveAnnouncementLink("/s/lab/settings/theme", prod, "hq"))
        assertEquals("$prod/s/hq?open=apps", resolveAnnouncementLink("?open=apps", prod, "hq"))
        assertEquals("$prod/s/hq", resolveAnnouncementLink("/", prod, "hq"))
    }
}
