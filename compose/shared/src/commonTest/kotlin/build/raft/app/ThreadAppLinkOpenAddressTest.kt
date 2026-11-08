package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadAppLinkOpenAddressTest {
    private val opened = mutableListOf<String>()

    private fun open(address: String, connectUrl: String?) =
        openThreadAppLinkAddress(address, connectUrl, { opened += "link:$it" }, { opened += "browser:$it" })

    @Test
    fun aCardLinkOpensAsAMessageLinkDoes() {
        open("https://example.com/item", connectUrl = null)
        assertEquals(listOf("link:https://example.com/item"), opened)
    }

    @Test
    fun aConnectUrlGoesToTheSystemBrowser() {
        open("https://example.com/connect", connectUrl = "https://example.com/connect")
        assertEquals(listOf("browser:https://example.com/connect"), opened)
    }
}
