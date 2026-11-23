package build.raft.app

import build.raft.app.data.AppLinkApp
import build.raft.app.data.AppLinkResult
import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadAppLinkOpenAddressTest {
    private val opened = mutableListOf<String>()

    private fun sampleCard(
        url: String = "https://stamp.build/github/botiverse/mobile/pull/1",
        appName: String = "Stamp",
        connectUrl: String? = null,
    ): AppLinkResult.Card =
        AppLinkResult.Card(
            url = url,
            app = AppLinkApp(name = appName, icon = null),
            title = "PR #1",
            connectUrl = connectUrl,
        )

    private fun open(address: String, card: AppLinkResult.Card, serverId: String? = "srv-1") =
        openThreadAppLinkAddress(
            address = address,
            card = card,
            preferredServerId = serverId,
            openLink = { opened += "link:$it" },
            openAppEntry = { url, req ->
                opened += "entry:$url|${req.scopeOrigin}|${req.appName}|${req.preferredServerId}"
            },
            openInSystemBrowser = { opened += "browser:$it" },
        )

    @Test
    fun sameOriginCardAddressWithoutConnectUrlOpensAsAppEntry() {
        val card = sampleCard()
        open("https://stamp.build/github/botiverse/mobile/pull/1", card)
        open("https://stamp.build/github/botiverse/mobile/pull/1/files", card)

        assertEquals(
            listOf(
                "entry:https://stamp.build/github/botiverse/mobile/pull/1|https://stamp.build|Stamp|srv-1",
                "entry:https://stamp.build/github/botiverse/mobile/pull/1/files|https://stamp.build|Stamp|srv-1",
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
    fun connectUrlGoesToSystemBrowserAndConnectCardNeverOpensAppEntry() {
        val card =
            sampleCard(
                url = "https://example.com/item",
                connectUrl = "https://example.com/connect",
            )
        open("https://example.com/connect", card)
        open("https://example.com/item", card)

        assertEquals(
            listOf(
                "browser:https://example.com/connect",
                "link:https://example.com/item",
            ),
            opened,
        )
    }
}
