package build.raft.app

import build.raft.app.compat.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The app's Settings search index (artin #proj-raft-ui:#204, option A): its ids are the web's
 * (botiverse/slock packages/shared/src/settingsSearch.v1.json at b36482a1), it lists only what
 * the app's pages have, and each person finds only what their list and pages show them.
 */
class RaftSettingsSearchIndexTest {
    /** settingsSearch.v1.json's ids at b36482a1 (plus 9b284c6d's notification-followed-thread-replies), in its order. */
    private val webIds =
        listOf(
            "display-name", "username", "email", "password", "passkeys", "two-step", "sign-out", "delete-account",
            "display-language", "translation-mode", "time-format", "timezone", "message-font-size",
            "live-agent-activity", "show-agent-model", "show-sender-description", "hide-empty-sidebar-sections", "send-with",
            "push-notifications", "pause-notifications", "quiet-hours", "notification-preview", "notification-sound",
            "notification-followed-thread-replies",
            "theme", "server-theme", "mute-server", "app-authorizations", "server-name", "public-access", "leave-server", "plan",
            "invite-links", "single-sign-on", "member-permissions", "ai-providers", "desktop-app",
        )

    /** The phone's list with a current server (settingsHomeRowKeys, serverPanelPages). */
    private val phoneRows =
        settingsHomeRowKeys(
            hasThemeServer = true,
            releaseNotes = true,
            serverPanelPages = true,
        )

    private fun ids(context: RaftSettingsSearchContext) = raftSettingsSearchPlacements(context).map { it.entry.id }

    @Test
    fun everyEntryIsAWebIdWithItsWordsAndInTheWebOrder() {
        val ids = RaftSettingsSearchEntries.map { it.id }
        assertEquals(ids.distinct(), ids)
        assertEquals(webIds.filter { it in ids }, ids)
        // The app has no Providers page and no About › Desktop app card.
        assertEquals(setOf("ai-providers", "desktop-app"), webIds.toSet() - ids.toSet())
        assertEquals(ids.toSet(), RaftSettingsSearchCopies.keys)
        RaftSettingsSearchEntries.forEach { entry ->
            assertTrue(entry.aliases["en"].orEmpty().isNotEmpty(), entry.id)
            entry.aliases.values.flatten().forEach { word -> assertEquals(word.trim(), word, entry.id) }
        }
    }

    @Test
    fun passkeysAndTwoStepAreIndexedUnderAccountWithWebAliases() {
        val member = ids(RaftSettingsSearchContext(phoneRows, role = "member"))
        assertTrue("passkeys" in member)
        assertTrue("two-step" in member)
        val passkeys = RaftSettingsSearchEntries.first { it.id == "passkeys" }
        val twoStep = RaftSettingsSearchEntries.first { it.id == "two-step" }
        assertTrue("face id" in passkeys.aliases["en"].orEmpty())
        assertTrue("通行密钥" in passkeys.aliases["zh-cn"].orEmpty())
        assertTrue("2fa" in twoStep.aliases["en"].orEmpty())
        assertTrue("两步验证" in twoStep.aliases["zh-cn"].orEmpty())
    }

    @Test
    fun anOrdinaryMemberFindsNoAdminOnlyOrOwnerOnlySettings() {
        val member =
            ids(RaftSettingsSearchContext(phoneRows, role = "member", publicServer = true, serverSso = true))
        assertTrue("leave-server" in member)
        assertTrue("delete-account" in member)
        for (hidden in listOf("plan", "invite-links", "single-sign-on", "member-permissions", "public-access")) {
            assertFalse(hidden in member, hidden)
        }
    }

    @Test
    fun flagsThatAreOffHideTheirSections() {
        val admin = ids(RaftSettingsSearchContext(phoneRows, role = "admin"))
        assertFalse("single-sign-on" in admin) // server_sso_v1 off
        assertFalse("public-access" in admin) // owner only, and public_server_v0 off
        assertTrue("invite-links" in admin)
    }

    @Test
    fun settingsFollowTheListsPageOrderThenTheIndexs() {
        val placements = raftSettingsSearchPlacements(RaftSettingsSearchContext(phoneRows, role = "owner"))
        val pageOrder = placements.map { phoneRows.indexOf(it.rowKey) }
        assertEquals(pageOrder.sorted(), pageOrder)
        // Within Account, the index's own order.
        assertEquals(
            listOf("display-name", "username", "email", "password", "passkeys", "two-step", "sign-out", "delete-account"),
            placements.filter { it.rowKey == SettingsDestinationAccount }.map { it.entry.id },
        )
    }

    @Test
    fun aPageTheListDoesNotShowHasNoResults() {
        val rows = phoneRows - SettingsDestinationAdministration
        assertFalse("invite-links" in ids(RaftSettingsSearchContext(rows, role = "owner")))
    }

    @Test
    fun theAnchorRidesInPageDataAndOnTheWebUrl() {
        val data = JSONObject().putSettingsSearchAnchor(SettingsSearchAnchorRequest("send-with", 7))
        assertEquals(SettingsSearchAnchorRequest("send-with", 7), settingsSearchAnchorFromPageData(data))
        // Not an index id: nothing is put, nothing is read.
        assertNull(settingsSearchAnchorFromPageData(JSONObject().putSettingsSearchAnchor(SettingsSearchAnchorRequest("x#y"))))
        assertNull(settingsSearchAnchorFromPageData(JSONObject().put(SETTINGS_SEARCH_ANCHOR, "../billing")))
        assertEquals(
            "https://raft.build/s/acme/settings/administration?lang=en#invite-links",
            settingsSearchWebUrlWithAnchor(
                "https://raft.build/s/acme/settings/administration?lang=en",
                SettingsSearchAnchorRequest("invite-links"),
            ),
        )
        assertEquals("https://raft.build/settings/billing", settingsSearchWebUrlWithAnchor("https://raft.build/settings/billing", null))
        val wide = settingsWideDetailPage(SettingsDestinationAdministration, build.raft.app.data.RaftShellStoreScope.Legacy, anchor = SettingsSearchAnchorRequest("plan", 3))
        assertEquals("plan", wide?.second?.let(::settingsSearchAnchorFromPageData)?.id)
    }

    @Test
    fun aLazyPageSeeksItsRowUntilComposedAndStopsForTheUsersScroll() {
        assertEquals(SettingsSearchSeekStep.Scroll, settingsSearchSeekStep(anchorComposed = false, userScrolling = false, itemCount = 5, canScrollForward = true))
        // The row is there: it brings itself into view.
        assertEquals(SettingsSearchSeekStep.Wait, settingsSearchSeekStep(anchorComposed = true, userScrolling = false, itemCount = 5, canScrollForward = true))
        // Still loading, or at the end with nothing more to compose: wait for content.
        assertEquals(SettingsSearchSeekStep.Wait, settingsSearchSeekStep(anchorComposed = false, userScrolling = false, itemCount = 0, canScrollForward = false))
        assertEquals(SettingsSearchSeekStep.Wait, settingsSearchSeekStep(anchorComposed = false, userScrolling = false, itemCount = 5, canScrollForward = false))
        // A drag replaces the intent, even with the row still to find.
        assertEquals(SettingsSearchSeekStep.Stop, settingsSearchSeekStep(anchorComposed = false, userScrolling = true, itemCount = 5, canScrollForward = true))
    }
}
