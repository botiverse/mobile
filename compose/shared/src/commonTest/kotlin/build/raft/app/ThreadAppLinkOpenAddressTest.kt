package build.raft.app

import build.raft.app.data.AppLinkApp
import build.raft.app.data.AppLinkResult
import build.raft.app.thread.icon
import build.raft.app.thread.openThreadAppLinkAddress
import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadAppLinkOpenAddressTest {
    private val opened = mutableListOf<String>()

    private fun sampleCard(
        url: String = "https://stamp.build/github/botiverse/mobile/pull/1",
        appName: String = "Stamp",
        appIcon: String? = null,
        connectUrl: String? = null,
    ): AppLinkResult.Card =
        AppLinkResult.Card(
            url = url,
            app = AppLinkApp(name = appName, icon = appIcon),
            title = "PR #1",
            connectUrl = connectUrl,
        )

    private fun open(
        address: String,
        card: AppLinkResult.Card,
        serverId: String? = "srv-1",
        lookupInstalledApp: ((origin: String) -> RaftInstalledAppSyncLookup)? = null,
        resolveInstalledAppAsync: (
            (
                address: String,
                origin: String,
                onMatched: (RaftInstalledAppOriginMatch) -> Unit,
                onUnmatched: () -> Unit,
            ) -> Boolean
        )? = null,
    ) =
        openThreadAppLinkAddress(
            address = address,
            card = card,
            preferredServerId = serverId,
            openLink = { opened += "link:$it" },
            openAppEntry = { url, req ->
                opened += "entry:$url|${req.scopeOrigin}|${req.appName}|${req.preferredServerId}|${req.appLogoUrl.orEmpty()}"
            },
            openInSystemBrowser = { opened += "browser:$it" },
            lookupInstalledApp = lookupInstalledApp,
            resolveInstalledAppAsync = resolveInstalledAppAsync,
        )

    @Test
    fun sameOriginCardAddressWithoutConnectUrlOpensAsAppEntry() {
        val card = sampleCard()
        open("https://stamp.build/github/botiverse/mobile/pull/1", card)
        open("https://stamp.build/github/botiverse/mobile/pull/1/files", card)

        assertEquals(
            listOf(
                "entry:https://stamp.build/github/botiverse/mobile/pull/1|https://stamp.build|Stamp|srv-1|",
                "entry:https://stamp.build/github/botiverse/mobile/pull/1/files|https://stamp.build|Stamp|srv-1|",
            ),
            opened,
        )
    }

    @Test
    fun offOriginNavigateButtonFallsBackToOrdinaryLink() {
        val card = sampleCard()
        open("https://github.com/botiverse/mobile/pull/1", card)

        assertEquals(listOf("link:https://github.com/botiverse/mobile/pull/1"), opened)
    }

    @Test
    fun crossOriginStampButtonOnGithubCardOpensAppEntryOnlyWhenStampIsInstalled() {
        val githubCard =
            sampleCard(
                url = "https://github.com/botiverse/mobile/pull/5565",
                appName = "Stamp",
                appIcon = "https://card.icon/stamp.png",
            )
        val stampButtonUrl = "https://stamp.build/s/raft/github/botiverse/mobile/pull/5565"

        // Installed Stamp with logoUrl: promotes to openAppEntry with Stamp's verified origin, name, and logo.
        open(
            address = stampButtonUrl,
            card = githubCard,
            lookupInstalledApp = { origin ->
                if (origin == "https://stamp.build") {
                    RaftInstalledAppSyncLookup.Matched(
                        RaftInstalledAppOriginMatch(
                            origin = "https://stamp.build",
                            appName = "Stamp",
                            logoUrl = "https://api.raft.build/logos/stamp.webp",
                        ),
                        serverId = "srv-1",
                    )
                } else {
                    RaftInstalledAppSyncLookup.KnownNotInstalled
                }
            },
        )
        // Installed Stamp without logoUrl falls back to card.app.icon.
        open(
            address = stampButtonUrl,
            card = githubCard,
            lookupInstalledApp = {
                RaftInstalledAppSyncLookup.Matched(
                    RaftInstalledAppOriginMatch(origin = "https://stamp.build", appName = null, logoUrl = null),
                    serverId = "srv-1",
                )
            },
        )
        // Not installed on current server: falls back to ordinary openLink.
        open(
            address = stampButtonUrl,
            card = githubCard,
            lookupInstalledApp = { RaftInstalledAppSyncLookup.KnownNotInstalled },
        )
        // Cold cache async resolution: promotes when verified, falls back when unmatched.
        open(
            address = stampButtonUrl,
            card = githubCard,
            lookupInstalledApp = { RaftInstalledAppSyncLookup.NotLoaded },
            resolveInstalledAppAsync = { _, origin, onMatched, _ ->
                onMatched(RaftInstalledAppOriginMatch(origin = origin, appName = "Stamp", logoUrl = null))
                true
            },
        )
        open(
            address = "https://uninstalled.example/review/1",
            card = githubCard,
            lookupInstalledApp = { RaftInstalledAppSyncLookup.NotLoaded },
            resolveInstalledAppAsync = { _, _, _, onUnmatched ->
                onUnmatched()
                true
            },
        )

        assertEquals(
            listOf(
                "entry:$stampButtonUrl|https://stamp.build|Stamp|srv-1|https://api.raft.build/logos/stamp.webp",
                "entry:$stampButtonUrl|https://stamp.build|Stamp|srv-1|https://card.icon/stamp.png",
                "link:$stampButtonUrl",
                "entry:$stampButtonUrl|https://stamp.build|Stamp|srv-1|https://card.icon/stamp.png",
                "link:https://uninstalled.example/review/1",
            ),
            opened,
        )
    }

    @Test
    fun connectUrlGoesToSystemBrowserAndConnectCardNeverOpensAppEntry() {
        val card =
            sampleCard(
                url = "https://example.com/item",
                connectUrl = "https://example.com/connect",
            )
        var lookupCalled = false
        val lookup: (String) -> RaftInstalledAppSyncLookup = {
            lookupCalled = true
            RaftInstalledAppSyncLookup.Matched(RaftInstalledAppOriginMatch(it, "Example"), "srv-1")
        }
        open("https://example.com/connect", card, lookupInstalledApp = lookup)
        open("https://example.com/item", card, lookupInstalledApp = lookup)
        open("https://stamp.build/other", card, lookupInstalledApp = lookup)

        assertEquals(false, lookupCalled)
        assertEquals(
            listOf(
                "browser:https://example.com/connect",
                "link:https://example.com/item",
                "link:https://stamp.build/other",
            ),
            opened,
        )
    }
}
