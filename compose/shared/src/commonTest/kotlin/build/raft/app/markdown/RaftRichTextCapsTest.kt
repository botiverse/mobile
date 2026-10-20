package build.raft.app.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftRichTextCapsTest {
    @Test
    fun capsLineMatchesLockedV02Contract() {
        val line =
            raftRichTextCapsLine(
                RaftRichTextPlatformCaps(
                    inlineCodeChrome = true,
                    raftRefChrome = false,
                    reactionAtlas = true
                )
            )
        assertEquals(
            "SLOCK_RICHTEXT_CAPS {\"inlineCodeChrome\":true,\"slockRefChrome\":false," +
                "\"reactionAtlas\":true,\"declaredDivergences\":[]}",
            line
        )
    }

    @Test
    fun capsLineSerializesDeclaredDivergencesAndEscapes() {
        val line =
            raftRichTextCapsLine(
                RaftRichTextPlatformCaps(
                    inlineCodeChrome = false,
                    raftRefChrome = false,
                    reactionAtlas = false,
                    declaredDivergences =
                        listOf(
                            RaftRichTextDeclaredDivergence(
                                id = "sample-divergence",
                                description = "line1\nsays \"quoted\" \\ backslash"
                            )
                        )
                )
            )
        assertEquals(
            "SLOCK_RICHTEXT_CAPS {\"inlineCodeChrome\":false,\"slockRefChrome\":false," +
                "\"reactionAtlas\":false,\"declaredDivergences\":[{\"id\":\"sample-divergence\"," +
                "\"description\":\"line1\\nsays \\\"quoted\\\" \\\\ backslash\"}]}",
            line
        )
        assertFalse(line.contains('\n'), "marker must stay a single line")
    }

    @Test
    fun platformCapsLineIsSingleLineWithMarkerPrefix() {
        // Exercises this compile target's actual: the collector contract needs a
        // single line starting with the exact prefix + one space + a JSON object.
        val line = raftRichTextCapsLine()
        assertTrue(line.startsWith("$RAFT_RICHTEXT_CAPS {"))
        assertTrue(line.endsWith("}"))
        assertFalse(line.contains('\n'))
        assertTrue(line.contains("\"declaredDivergences\":["))
    }

    @Test
    fun rendererCapabilitiesFollowPlatformChrome() {
        val platformCaps = raftRichTextPlatformCaps()
        val chromeRenders = platformCaps.inlineCodeChrome && platformCaps.raftRefChrome
        val capabilities = RaftMarkdownRendererRichTextCapabilities
        assertEquals(true, capabilities.consumesDocument)
        assertEquals(chromeRenders, capabilities.consumesChromeTokens)
        assertEquals(chromeRenders, capabilities.hasDrawAdapter)
        assertEquals(platformCaps.declaredDivergences, capabilities.declaredDivergences)
    }
}
