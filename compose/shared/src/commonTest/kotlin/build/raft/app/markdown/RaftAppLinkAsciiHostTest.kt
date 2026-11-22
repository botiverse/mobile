package build.raft.app.markdown

import kotlin.test.Test
import kotlin.test.assertEquals

/** raft-design #755: an app link's host is shown in ASCII (punycode), as the web's URL parser writes it. */
class RaftAppLinkAsciiHostTest {
    @Test
    fun unicodeLabelsTakeTheirPunycodeForm() {
        assertEquals("xn--bcher-kva.example", raftAppLinkAsciiHost("bücher.example"))
        assertEquals("xn--mnchen-3ya.de", raftAppLinkAsciiHost("MÜNCHEN.de"))
        assertEquals("xn--r8jz45g.xn--zckzah", raftAppLinkAsciiHost("例え。テスト"))
        assertEquals("xn--e28h.example", raftAppLinkAsciiHost("😀.example"))
    }
}
