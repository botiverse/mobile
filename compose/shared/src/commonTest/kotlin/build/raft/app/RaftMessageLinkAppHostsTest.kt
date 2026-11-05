package build.raft.app

import build.raft.app.data.RaftClientConfigAppHosts
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Message links on the client config's `appHosts` (botiverse/slock #11421, `GET /api/public/client-config`):
 * a union with the hosts the App already takes (production, the environment's web URL), matched exactly.
 */
class RaftMessageLinkAppHostsTest {
    private val channel = "89dce17f-1cd3-4db8-b08f-7b4141004b5a"
    private val path = "/s/botiverse/channel/$channel?msg=7556f881-a5f8-4b0d-88dc-8b5af28e8f05"
    private val appHosts = setOf("raft.build", "app.raft.build")

    @AfterTest
    fun reset() = RaftClientConfigAppHosts.publish(emptyList())

    @Test
    fun anAppHostIsAcceptedAndSiblingsAreNot() {
        assertEquals(channel, parseRaftMessageLink("https://raft.build$path", appHosts = appHosts)?.channelId)
        assertNotNull("https://raft.build$path".toRaftMessagePermalinkTarget(appHosts = appHosts))
        for (url in listOf("https://w.raft.build$path", "https://evil.raft.build$path", "https://raft.build.evil.example$path")) {
            assertNull(parseRaftMessageLink(url, appHosts = appHosts), url)
            assertNull(url.toRaftMessagePermalinkTarget(appHosts = appHosts), url)
        }
        // Without the client config those hosts are not taken.
        assertNull(parseRaftMessageLink("https://raft.build$path", appHosts = emptySet()))
        assertNull("https://raft.build$path".toRaftMessagePermalinkTarget(appHosts = emptySet()))
    }

    @Test
    fun theExistingHostsWorkBeforeAndAfterAppHostsArrive() {
        val staging = "https://raft-app-staging.botiverse.dev"
        for (hosts in listOf(emptySet(), setOf("raft.build"))) {
            assertNotNull(parseRaftMessageLink("https://app.raft.build$path", appHosts = hosts))
            assertNotNull(parseRaftMessageLink("https://open.raft.build$path", appHosts = hosts))
            assertNotNull(parseRaftMessageLink("$staging$path", configuredWebUrl = staging, appHosts = hosts))
            assertNotNull("https://app.raft.build$path".toRaftMessagePermalinkTarget(appHosts = hosts))
            assertNotNull("$staging$path".toRaftMessagePermalinkTarget(configuredWebUrl = staging, appHosts = hosts))
        }
    }

    @Test
    fun theProcessListIsWhatTheClientConfigPublished() {
        assertNull(parseRaftMessageLink("https://raft.build$path"))
        RaftClientConfigAppHosts.publish(listOf("Raft.Build."))
        assertEquals(setOf("raft.build"), RaftClientConfigAppHosts.current)
        assertNotNull(parseRaftMessageLink("https://raft.build$path"))
        // The canonical host stays accepted whatever the list says.
        assertNotNull(parseRaftMessageLink("https://app.raft.build$path"))
    }
}
