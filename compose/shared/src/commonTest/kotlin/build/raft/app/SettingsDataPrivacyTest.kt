package build.raft.app

import build.raft.app.data.RaftPageRoute
import build.raft.app.data.RaftProductPageView
import build.raft.app.data.RaftSettingsTab
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.SharedApiEnvironmentStore
import build.raft.app.data.SharedWindowNavigationCoordinator
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.nav.RaftStackSettingsWebSurfaces
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Settings › Account › Data & privacy and `/?data_export=<id>` deep-link routing
 * (`botiverse/slock` #15418 / #15427 / #15451 / #15495 / #15506 / #15517, flag `data_export_v0`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsDataPrivacyTest {
    private val prod = SharedApiEnvironmentStore.Production.webUrl
    private val staging = SharedApiEnvironmentStore.Staging.webUrl
    private val exportId = "11111111-2222-4333-8444-555555555555"
    private val serverIdA = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private val serverIdB = "99999999-8888-4777-8666-555555555555"
    private val noHosts = emptySet<String>()

    private fun session(
        selectedSlug: String? = "workspace",
        servers: List<StoredAuthServer> =
            listOf(
                StoredAuthServer(id = serverIdA, name = "Workspace", slug = selectedSlug, role = "member"),
                StoredAuthServer(id = serverIdB, name = "Acme", slug = "acme", role = "owner"),
            ),
    ): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "user@example.test", displayName = "User"),
            accessToken = "access-token",
            refreshToken = "refresh-token",
            selectedServerId = servers.firstOrNull()?.id,
            selectedServerSlug = selectedSlug,
            servers = servers,
        )

    @Test
    fun webViewSpecAndRouteMetadataMatchSettingsWebViewContract() {
        assertEquals(SettingsWebViewSurface.DataPrivacy, settingsDataPrivacyWebViewSpec.surface)
        assertTrue(settingsDataPrivacyWebViewSpec.allowNavigation)
        assertTrue(settingsDataPrivacyWebViewSpec.lockToWebOrigin)
        assertTrue(SettingsWebViewSurface.DataPrivacy in RaftStackSettingsWebSurfaces)
        assertEquals(
            RaftProductPageView(RaftPageRoute.Settings, RaftSettingsTab.Account),
            SettingsWebViewSurface.DataPrivacy.productPageView(),
        )
    }

    @Test
    fun webViewUrlBuildsPersonalAccountAndServerAdministrationRoutesAndSanitizesIds() {
        val currentSession = session()

        // Settings › Account › Data & privacy entry (no export id).
        assertEquals(
            "$prod/s/workspace/settings/account?embed=raft-settings-v1&shell=host&lang=en#data-export",
            settingsDataPrivacyWebViewUrl(prod, currentSession, localeTag = "en"),
        )
        assertEquals(
            "$staging/s/workspace/settings/account?embed=raft-settings-v1&shell=host&lang=zh-CN#data-export",
            settingsDataPrivacyWebViewUrl("$staging/", currentSession, localeTag = "zh-CN"),
        )
        assertEquals(
            "$prod/settings/account?embed=raft-settings-v1&shell=host&lang=en#data-export",
            settingsDataPrivacyWebViewUrl(prod, session(selectedSlug = null, servers = emptyList()), localeTag = "en"),
        )

        // Personal export deep link preserves normalized lowercase ?data_export=<id>#data-export.
        val personalUrl =
            settingsDataPrivacyWebViewUrl(
                webBaseUrl = prod,
                session = currentSession,
                localeTag = "ja",
                exportId = exportId.uppercase(),
            )
        assertEquals(
            "$prod/s/workspace/settings/account?embed=raft-settings-v1&shell=host&lang=ja&data_export=$exportId#data-export",
            personalUrl,
        )
        // Host lang overlay keeps data_export and fragment intact.
        assertEquals(
            "$prod/s/workspace/settings/account?embed=raft-settings-v1&shell=host&data_export=$exportId&lang=ja#data-export",
            raftWebUrlWithLang(personalUrl, "ja"),
        )

        // Server export deep link routes to that server's Administration › Data & privacy section.
        assertEquals(
            "$prod/s/acme/settings/administration?embed=raft-settings-v1&shell=host&lang=en&data_export=$exportId#server-data-export",
            settingsDataPrivacyWebViewUrl(
                webBaseUrl = prod,
                session = currentSession,
                localeTag = "en",
                exportId = exportId,
                serverId = serverIdB.uppercase(),
            ),
        )

        // Unknown server id falls back to personal Account › Data & privacy (matching slock dataExportLandingPath).
        assertEquals(
            "$prod/s/workspace/settings/account?embed=raft-settings-v1&shell=host&lang=en&data_export=$exportId#data-export",
            settingsDataPrivacyWebViewUrl(
                webBaseUrl = prod,
                session = currentSession,
                localeTag = "en",
                exportId = exportId,
                serverId = "00000000-0000-4000-8000-000000000000",
            ),
        )

        // Non-UUID ids are stripped so untrusted parameters cannot inject query params.
        assertEquals(
            "$prod/s/workspace/settings/account?embed=raft-settings-v1&shell=host&lang=en#data-export",
            settingsDataPrivacyWebViewUrl(
                webBaseUrl = prod,
                session = currentSession,
                localeTag = "en",
                exportId = "not-a-uuid&evil=1",
                serverId = "also-bad",
            ),
        )
    }

    @Test
    fun pageDataRoundTripsValidDataExportIdsAndDropsInvalidValues() {
        val valid =
            settingsWebViewPageData(
                surface = SettingsWebViewSurface.DataPrivacy,
                storeScope = RaftShellStoreScope.create("shell-data-export", initialServerId = serverIdA),
                dataExportId = exportId.uppercase(),
                dataExportServerId = serverIdB.uppercase(),
            )
        assertEquals(SettingsWebViewSurface.DataPrivacy, settingsWebViewSurfaceFromPageData(valid))
        assertEquals(exportId, settingsDataExportIdFromPageData(valid))
        assertEquals(serverIdB, settingsDataExportServerIdFromPageData(valid))

        val invalid =
            settingsWebViewPageData(
                surface = SettingsWebViewSurface.DataPrivacy,
                storeScope = RaftShellStoreScope.create("shell-data-export-bad"),
                dataExportId = "not-an-id",
                dataExportServerId = "bad-server",
            )
        assertNull(settingsDataExportIdFromPageData(invalid))
        assertNull(settingsDataExportServerIdFromPageData(invalid))
    }

    @Test
    fun parserAcceptsEmailedAndSettingsDataExportLinksAndRejectsInvalidOnes() {
        // Emailed personal export links on production, open host, staging, and custom appHosts.
        assertEquals(
            RaftDataExportLink(exportId = exportId),
            parseRaftDataExportLink("$prod/?data_export=$exportId", configuredWebUrl = null, appHosts = noHosts),
        )
        assertEquals(
            RaftDataExportLink(exportId = exportId),
            parseRaftDataExportLink("https://APP.RAFT.BUILD.?data_export=${exportId.uppercase()}", configuredWebUrl = null, appHosts = noHosts),
        )
        assertEquals(
            RaftDataExportLink(exportId = exportId),
            parseRaftDataExportLink("https://open.raft.build/?data_export=$exportId", configuredWebUrl = null, appHosts = noHosts),
        )
        assertEquals(
            RaftDataExportLink(exportId = exportId),
            parseRaftDataExportLink("$staging/?data_export=$exportId", configuredWebUrl = staging, appHosts = noHosts),
        )
        assertEquals(
            RaftDataExportLink(exportId = exportId),
            parseRaftDataExportLink("https://raft.build/?data_export=$exportId", configuredWebUrl = null, appHosts = setOf("raft.build")),
        )

        // Emailed server export link preserves both exportId and serverId.
        assertEquals(
            RaftDataExportLink(exportId = exportId, serverId = serverIdB),
            parseRaftDataExportLink(
                "$prod/?data_export=$exportId&data_export_server=${serverIdB.uppercase()}",
                configuredWebUrl = null,
                appHosts = noHosts,
            ),
        )
        // Malformed data_export_server is ignored cleanly while preserving valid exportId.
        assertEquals(
            RaftDataExportLink(exportId = exportId, serverId = null),
            parseRaftDataExportLink(
                "$prod/?data_export=$exportId&data_export_server=not-a-uuid",
                configuredWebUrl = null,
                appHosts = noHosts,
            ),
        )

        // Direct Settings account / administration URLs carrying ?data_export=<id>.
        assertEquals(
            RaftDataExportLink(exportId = exportId, serverSlug = "acme", isAdministrationPath = false),
            parseRaftDataExportLink(
                "$prod/s/acme/settings/account?data_export=$exportId#data-export",
                configuredWebUrl = null,
                appHosts = noHosts,
            ),
        )
        assertEquals(
            RaftDataExportLink(exportId = exportId, serverSlug = "acme", isAdministrationPath = true),
            parseRaftDataExportLink(
                "$prod/s/acme/settings/administration?data_export=$exportId#server-data-export",
                configuredWebUrl = null,
                appHosts = noHosts,
            ),
        )
        assertEquals(
            RaftDataExportLink(exportId = exportId, serverSlug = null, isAdministrationPath = false),
            parseRaftDataExportLink(
                "$prod/settings/account?data_export=$exportId",
                configuredWebUrl = null,
                appHosts = noHosts,
            ),
        )

        // Reject invalid UUID, missing param, untrusted host, unrelated paths, and malformed escapes.
        val rejected =
            listOf(
                "",
                "$prod/",
                "$prod/?data_export=not-an-id",
                "$prod/?data_export=",
                "$prod/?data_export=%ZZ",
                "$prod/s/acme/settings/account",
                "$prod/s/acme/settings/billing?data_export=$exportId",
                "$prod/s/acme/channel/$serverIdA?data_export=$exportId",
                "$prod/login?data_export=$exportId",
                "$staging/?data_export=$exportId",
                "https://evil.example/?data_export=$exportId",
                "$prod/?data_export=$exportId\\bad",
            )
        for (url in rejected) {
            assertNull(parseRaftDataExportLink(url, configuredWebUrl = null, appHosts = noHosts), "expected null for $url")
        }
    }

    @Test
    fun ownLinkRouteIngressAndInboxClaimDataExportLinks() {
        val url = "$prod/?data_export=$exportId&data_export_server=$serverIdB"
        val route = assertIs<RaftOwnLinkRoute.DataExport>(raftOwnLinkRoute(url, configuredWebUrl = prod, appHosts = noHosts))
        assertEquals(exportId, route.link.exportId)
        assertEquals(serverIdB, route.link.serverId)

        // Invalid ?data_export= does not crash and stays a normal Raft WebPage route.
        assertEquals(
            RaftOwnLinkRoute.WebPage(RaftOwnLinkWebPageReason.NoInAppRoute),
            raftOwnLinkRoute("$prod/?data_export=not-an-id", configuredWebUrl = prod, appHosts = noHosts),
        )

        var now = 1_000L
        val testInbox = RaftDataExportLinkInbox(nowEpochMillis = { now }, ttlMillis = 5_000L)
        val opener =
            raftOwnLinkShellOpener(
                webUrl = { prod },
                appHosts = { noHosts },
                dataExportInbox = testInbox,
            )
        assertTrue(opener(url))
        val pending = assertNotNull(testInbox.pending.value)
        assertEquals(exportId, pending.link.exportId)
        assertEquals(pending, testInbox.take(pending.id))
        assertNull(testInbox.take(pending.id))

        // Expired entry is dropped on take.
        val expiring = testInbox.submit(RaftDataExportLink(exportId), "email")
        now = 10_000L
        assertNull(testInbox.take(expiring.id))

        RaftDataExportLinkInbox.Process.clear()
        try {
            assertTrue(RaftMessageLinkIngress.accept(url, source = "android_view", configuredWebUrl = prod))
            val queued = assertNotNull(RaftDataExportLinkInbox.Process.pending.value)
            assertEquals(exportId, queued.link.exportId)
            assertEquals(serverIdB, queued.link.serverId)

            assertTrue(
                RaftMessageLinkIngress.accept(
                    "raft:///s/acme/settings/account?data_export=$exportId",
                    source = "desktop_scheme",
                    configuredWebUrl = prod,
                )
            )
            val queuedScheme = assertNotNull(RaftDataExportLinkInbox.Process.pending.value)
            assertEquals("acme", queuedScheme.link.serverSlug)

            assertFalse(RaftMessageLinkIngress.accept("$prod/?data_export=not-an-id", source = "android_view", configuredWebUrl = prod))
        } finally {
            RaftDataExportLinkInbox.Process.clear()
        }
    }

    @Test
    fun resolveDataExportLinkActionGatesWebViewBehindFlagAndPreservesQueryOnFallback() {
        val currentSession = session()
        val personalLink = RaftDataExportLink(exportId = exportId)
        val serverLink = RaftDataExportLink(exportId = exportId, serverId = serverIdB)
        val unknownServerLink =
            RaftDataExportLink(exportId = exportId, serverId = "00000000-0000-4000-8000-000000000000")
        val directAdminLink =
            RaftDataExportLink(exportId = exportId, serverSlug = "acme", isAdministrationPath = true)

        // Flag ON on any joined server -> personal link opens Data & privacy WebView.
        assertEquals(
            RaftDataExportLinkAction.OpenSettingsWebView(exportId = exportId, serverId = null),
            resolveRaftDataExportLinkAction(personalLink, currentSession, prod) { id -> id == serverIdB },
        )

        // Flag OFF -> personal link opens browser fallback with ?data_export=<id> preserved.
        assertEquals(
            RaftDataExportLinkAction.OpenWebFallback("$prod/?data_export=$exportId"),
            resolveRaftDataExportLinkAction(personalLink, currentSession, prod) { false },
        )

        // Server export link with flag ON for that server -> WebView targeting that server.
        assertEquals(
            RaftDataExportLinkAction.OpenSettingsWebView(exportId = exportId, serverId = serverIdB),
            resolveRaftDataExportLinkAction(serverLink, currentSession, prod) { id -> id == serverIdB },
        )

        // Server export link with flag OFF for that server (even if ON for another) -> browser fallback preserving both params.
        assertEquals(
            RaftDataExportLinkAction.OpenWebFallback("$prod/?data_export=$exportId&data_export_server=$serverIdB"),
            resolveRaftDataExportLinkAction(serverLink, currentSession, prod) { id -> id == serverIdA },
        )

        // Server export link for a server the person isn't in -> falls back to personal WebView when account flag is ON.
        assertEquals(
            RaftDataExportLinkAction.OpenSettingsWebView(exportId = exportId, serverId = null),
            resolveRaftDataExportLinkAction(unknownServerLink, currentSession, prod) { id -> id == serverIdA },
        )

        // Direct /s/acme/settings/administration?data_export=<id> resolves to serverIdB when ON and preserves path on fallback.
        assertEquals(
            RaftDataExportLinkAction.OpenSettingsWebView(exportId = exportId, serverId = serverIdB),
            resolveRaftDataExportLinkAction(directAdminLink, currentSession, prod) { id -> id == serverIdB },
        )
        assertEquals(
            RaftDataExportLinkAction.OpenWebFallback("$prod/s/acme/settings/administration?data_export=$exportId#server-data-export"),
            resolveRaftDataExportLinkAction(directAdminLink, currentSession, prod) { false },
        )

        // Prefetch target is narrow for a matched server export and account-wide for personal exports.
        assertEquals(listOf(serverIdB), raftDataExportFlagServerIdsToPrefetch(serverLink, currentSession))
        assertEquals(listOf(serverIdA, serverIdB), raftDataExportFlagServerIdsToPrefetch(personalLink, currentSession))
    }

    @Test
    fun shellNavigationIntakeDeliversPendingDataExportLinkWhenSessionIsReady() = runTest {
        val dataExportInbox = RaftDataExportLinkInbox(nowEpochMillis = { 1_000L })
        val intake =
            RaftShellNavigationIntake(
                coordinator = SharedWindowNavigationCoordinator(),
                inbox = RaftMessageLinkInbox(nowEpochMillis = { 1_000L }),
                artifactInbox = RaftArtifactLinkInbox(nowEpochMillis = { 1_000L }),
                externalInviteInbox = RaftExternalInviteLinkInbox(nowEpochMillis = { 1_000L }),
                serverJoinInbox = RaftServerJoinLinkInbox(nowEpochMillis = { 1_000L }),
                sendPromptInbox = RaftSendPromptLinkInbox(nowEpochMillis = { 1_000L }),
                downloadCenterInbox = RaftDownloadCenterLaunchInbox(nowEpochMillis = { 1_000L }),
                dataExportInbox = dataExportInbox,
            )
        val host = FakeShellNavigationIntakeHost()
        host.sessionReady = false

        val job = launch { intake.run(host) }
        try {
            dataExportInbox.submit(RaftDataExportLink(exportId = exportId, serverId = serverIdB), "ios_universal_link")
            advanceUntilIdle()
            assertTrue(host.openedDataExports.isEmpty(), "must wait until session is ready")
            assertNotNull(dataExportInbox.pending.value)

            host.sessionReady = true
            intake.wake()
            advanceUntilIdle()

            assertNull(dataExportInbox.pending.value)
            val taken = host.openedDataExports.single()
            assertEquals(exportId, taken.link.exportId)
            assertEquals(serverIdB, taken.link.serverId)
            assertEquals("ios_universal_link", taken.source)
        } finally {
            job.cancel()
        }
    }
}
