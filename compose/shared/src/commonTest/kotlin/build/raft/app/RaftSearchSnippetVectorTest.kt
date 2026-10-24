// Wire-contract vectors for `buildSearchSnippet`, copied case for case from
//   github.com/botiverse/slock PR #11148 (merged to staging) @ f73ab2dde90f39cae8dcd815a5cb9805d47bef7c
//   packages/server/src/services/searchService.test.ts  (snippet tests)
//   packages/web/tests/searchEntities.test.ts            (channel description subtitle)
// Pinned SHA: RAFT_SEARCH_SNIPPET_SLOCK_SHA in RaftSearchSnippet.kt (AGENTS.md vector exception;
// scripts/check_search_snippet_vector.sh keeps this header and the constant in step). The source
// asserts are reproduced as they are: exact strings where it compares one, the same property
// checks where it does not. Update by re-copying the cases and bumping the SHA together.
package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RaftSearchSnippetVectorTest {
    private class Vector(
        val name: String,
        val content: String,
        val tokens: List<String>,
        val verify: (snippet: String) -> Unit,
    )

    private val remy = listOf("remy")

    private fun width(text: String): Int {
        var total = 0
        var index = 0
        while (index < text.length) {
            val high = text[index]
            val cp = if (high.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) {
                ((high.code - 0xd800) shl 10) + (text[index + 1].code - 0xdc00) + 0x10000
            } else {
                high.code
            }
            total += raftSearchSnippetCharWidth(cp)
            index += if (cp > 0xffff) 2 else 1
        }
        return total
    }

    private fun exact(name: String, content: String, expected: String, tokens: List<String> = remy) =
        Vector(name, content, tokens) { assertEquals(expected, it, name) }

    private val vectors: List<Vector> = buildList {
        // searchService.test.ts: "snippet of a long Chinese message keeps 12 characters before a late hit"
        val zhBefore = "我们今天下午讨论了搜索结果的展示问题大家觉得中文长消息的命中位置经常被截断在省略号后面所以需要重新设计片段的截取方式让命中词出现在第一行里面这样用户在手机上也能直接看到"
        val zhAfter = "负责跟进这个问题并且会在明天的会议上同步进展同时整理一份测试清单覆盖中文英文和混合文本的情况确保两端看到的片段完全一致没有任何差别也不会改变接口的形状"
        add(Vector("long Chinese message, late hit", "${zhBefore}remy$zhAfter", remy) { snippet ->
            assertTrue(zhBefore.length > 70)
            assertTrue(snippet.startsWith("…"), snippet)
            val hitAt = snippet.indexOf("remy")
            val context = snippet.substring(1, hitAt)
            assertEquals(zhBefore.takeLast(12), context)
            assertEquals(RAFT_SEARCH_SNIPPET_WIDTH_BEFORE_HIT, width(context))
            assertTrue(snippet.endsWith("…"), snippet)
            assertEquals(RAFT_SEARCH_SNIPPET_WIDTH_AFTER_HIT, width(snippet.substring(hitAt + 4, snippet.length - 1)))
        })

        // "snippet budgets mixed Latin and CJK text by display width"
        val mixedContent = "Release notes 第二版 draft: 搜索 overlay 和 results 页面的高亮规则 need review by" +
            " remy before Friday, 然后再合并到 staging 分支。"
        add(Vector("mixed Latin and CJK by display width", mixedContent, remy) { snippet ->
            val hitAt = snippet.indexOf("remy")
            val context = snippet.substring(1, hitAt)
            assertTrue(snippet.startsWith("…"), snippet)
            assertTrue(mixedContent.contains(context), context)
            val w = width(context)
            assertTrue(w <= RAFT_SEARCH_SNIPPET_WIDTH_BEFORE_HIT && w >= RAFT_SEARCH_SNIPPET_WIDTH_BEFORE_HIT - 4, "width $w: $context")
            assertTrue(context.length > 8 && context.length < 24, "mixed text is neither all-wide nor all-narrow: $context")
            assertTrue(snippet.endsWith("分支。"), snippet)
        })

        // "snippet keeps up to 24 Latin columns before a hit and never splits a surrogate pair"
        add(exact("24 Latin columns, cut moves to the next word", "word ".repeat(30) + "remy and the rest", "…word word word word remy and the rest"))
        add(exact("emoji never split", "😀".repeat(30) + "remy", "…" + "😀".repeat(12) + "remy"))
        add(exact("short note is untouched", "short note for remy", "short note for remy"))

        // "snippet starts and ends on Latin word boundaries"
        add(exact("start moves off the middle of a word", "Every decision of that is written down, Remy will check it", "…that is written down, Remy will check it"))
        add(exact("start moves off the middle of 'attachments'", "Uploads, links and attachments, previews; or Remy owns the rest", "…previews; or Remy owns the rest"))
        val tail = "lorem ".repeat(40)
        add(Vector("end moves back to a word boundary", "remy $tail", remy) { snippet ->
            assertTrue(snippet.endsWith("lorem…"), snippet)
            assertTrue(!snippet.endsWith(" …"), snippet)
            assertTrue(width(snippet.substring(4, snippet.length - 1)) <= RAFT_SEARCH_SNIPPET_WIDTH_AFTER_HIT, snippet)
            assertEquals("remy " + tail.take(132).trimEnd() + "…", snippet)
        })
        add(Vector("no hit keeps the start on a word boundary", "alpha ".repeat(40), remy) { snippet ->
            assertTrue(snippet.endsWith("alpha…") && snippet.length <= 180, snippet)
        })

        // "snippet drops punctuation a CJK cut leaves at the start"
        add(exact("CJK comma dropped", "先把需求背景和验收标准整理好，这些都写清楚之后再请 remy 看一下", "…这些都写清楚之后再请 remy 看一下"))
        for (mark in listOf("。", "、", "；", "：", ",", ".", ";", ":")) {
            add(Vector("CJK cut leaves '$mark'", "一二三四五六七八九十一二三四五${mark}一二三四五六七八九十请 remy", remy) { snippet ->
                assertTrue(snippet.startsWith("…一"), "$mark: $snippet")
            })
        }

        // "snippet edge polish never cuts or hides the hit"
        add(exact("word longer than the reach keeps the plain cut", "x".repeat(60) + "remy", "…" + "x".repeat(24) + "remy"))
        add(Vector("hit right after a long word is never passed", "y".repeat(30) + ", remy", remy) { snippet ->
            assertTrue(snippet.endsWith("remy") && snippet.startsWith("…y"), snippet)
        })
        add(exact("inner punctuation and the @ of the hit stay", "一".repeat(12) + "，@remy", "…一一一一一一一一一一，@remy"))
        add(exact("run of separators before the hit is dropped", "二".repeat(30) + "，".repeat(13) + "remy", "…remy"))
        add(exact("end cut never moves before the hit's end", "remy" + "z".repeat(300), "remy" + "z".repeat(RAFT_SEARCH_SNIPPET_WIDTH_AFTER_HIT) + "…"))

        // searchEntities.test.ts: "a channel matched by its description gets the description, cut like a message snippet"
        add(exact("web channel description, 12 CJK before the hit", "这个频道用于讨论每周的发布计划和阻塞问题最终由remy确认上线", "…发布计划和阻塞问题最终由remy确认上线"))
        add(exact("web channel description, short", "Ask remy", "Ask remy"))
    }

    @Test
    fun snippetsMatchTheSlockVectors() {
        assertEquals("f73ab2dde90f39cae8dcd815a5cb9805d47bef7c", RAFT_SEARCH_SNIPPET_SLOCK_SHA)
        assertEquals(EXPECTED_VECTOR_COUNT, vectors.size, "a vector was added or dropped without updating the count")
        vectors.forEach { vector ->
            vector.verify(buildSearchSnippet(vector.content, vector.tokens))
        }
    }

    @Test
    fun theWebPassesTheQueryTermsWithoutTheirHandlePrefix() {
        // searchEntities.test.ts: the "@remy" query on the CJK-comma description (web searchHighlightTokens).
        assertEquals(remy, homeSearchHighlightTokens("@remy"))
        assertEquals(
            "…这些都写清楚之后再请 remy 看一下",
            buildSearchSnippet("先把需求背景和验收标准整理好，这些都写清楚之后再请 remy 看一下", homeSearchHighlightTokens("@remy")),
        )
    }

    private companion object {
        const val EXPECTED_VECTOR_COUNT = 25
    }
}
