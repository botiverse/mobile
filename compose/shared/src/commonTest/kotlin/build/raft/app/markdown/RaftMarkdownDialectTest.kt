package build.raft.app.markdown

import com.tencent.kuiklybase.markdown.model.RaftMarkdownDialect
import com.tencent.kuiklybase.markdown.model.RaftGFMFlavourDescriptor
import com.tencent.kuiklybase.markdown.model.parseMarkdownInlineSyntax
import kotlin.test.Test
import kotlin.test.assertEquals
import org.intellij.markdown.parser.MarkdownParser

class RaftMarkdownDialectTest {
    @Test
    fun mentionKeepsInternalDotButReturnsTrailingSentenceDotToText() {
        val source = "Ping @release.bot and @artin."
        val syntax = parseMarkdownInlineSyntax(source)

        assertEquals(listOf("@release.bot", "@artin"), syntax.raftRefs.map { it.sourceText })
        assertEquals(source.lastIndexOf('.'), syntax.raftRefs.last().endOffset)
    }

    @Test
    fun recognizesRaftRefsInOrdinaryGfmText() {
        assertEquals(
            listOf(
                "#1487",
                "@artin",
                "#visual-testing",
                "#visual_testing:c5c60593",
                "@release_bot",
            ),
            "Review #1487 with @artin in #visual-testing, #visual_testing:c5c60593, and @release_bot now."
                .raftRefs(),
        )
    }

    @Test
    fun bareTaskRefsUseTheWebAsciiWordBoundaries() {
        // Web: "see task #42 and (#7) but not path/#9" -> #42, #7.
        assertEquals(listOf("#42", "#7"), "see task #42 and (#7) but not path/#9".raftRefs())
        assertEquals(listOf("#12", "#13"), "去#12看看 #13，".raftRefs())
        assertEquals(emptyList<String>(), "word#2 snake_#4 #12a #12_".raftRefs())
    }

    @Test
    fun mentionsKeepTheWebFreeTextLeftBoundary() {
        // Web `createRaftUserRefRegex`: `(^|[^\p{L}\p{N}_-])@name`.
        assertEquals(listOf("@Alice"), "skip -@bob and _@bob, but notify - @Alice".raftRefs())
        assertEquals(listOf("@Alice"), "contact alice@example.com, then notify @Alice".raftRefs())
        assertEquals(emptyList<String>(), "ship foo@1.2.3 and node@22 today".raftRefs())
        assertEquals(emptyList<String>(), "草案@Mona".raftRefs())
        assertEquals(listOf("@Mona"), "草案，@Mona".raftRefs())
        assertEquals(listOf("@Mona"), "（@Mona）".raftRefs())
    }

    private fun String.raftRefs(): List<String> =
        RaftMarkdownDialect.inlineRefs(
            MarkdownParser(RaftGFMFlavourDescriptor()).buildMarkdownTreeFromString(this),
            this,
        ).map { ref -> ref.sourceText }
}
