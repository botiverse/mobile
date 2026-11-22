package build.raft.app.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class RaftRetiredContentHostTest {
    @Test
    fun exactRetiredHostMovesToCdnKeepingPathQueryAndFragment() {
        assertEquals(
            "https://cdn.raft.build/attachments/srv/a%20b.png?w=320&h=240&sig=AbC%2B%3D#frag",
            raftRewriteRetiredContentHost("https://content.raft.build/attachments/srv/a%20b.png?w=320&h=240&sig=AbC%2B%3D#frag")
        )
        assertEquals("https://cdn.raft.build?x=1", raftRewriteRetiredContentHost("https://content.raft.build?x=1"))
        assertEquals("https://cdn.raft.build#f", raftRewriteRetiredContentHost("https://content.raft.build#f"))
        assertEquals("https://cdn.raft.build", raftRewriteRetiredContentHost("https://content.raft.build"))
    }

    @Test
    fun subdomainsAndLookalikeHostsStayUntouched() {
        listOf(
            "https://img.content.raft.build/a.png",
            "https://content.raft.build.evil.example/a.png",
            "https://xcontent.raft.build/a.png",
            "https://content.raft.builds/a.png",
            "https://content-raft.build/a.png",
            "https://content.raft.build./a.png",
            "https://content.raft.build:443/a.png",
            "https://user@content.raft.build/a.png",
            "https://evil.example/https://content.raft.build/a.png",
            "https://cdn.raft.build/a.png?next=https://content.raft.build/b.png",
        ).forEach { url -> assertSame(url, raftRewriteRetiredContentHost(url), url) }
    }
}
