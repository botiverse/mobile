package build.raft.app

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the zh-rCN thread terminology to 「消息列」 (task #103, #proj-i18n:96e1bd2d).
 *
 * artin unified thread → 消息列 (web PR #6310; mobile sync per @AngLee ruling). Every
 * conversation-thread surface must read 消息列, and no thread 「话题」 may regress.
 * base (English) is untouched and stays "thread".
 */
class ThreadTerminologyResourceTest {
    @Test
    fun zhCatalogHasNoThreadTopicTermResidue() {
        val zh = readStrings("zh-rCN")
        val residue = zh.filterValues { it.contains("话题") }
        assertTrue(
            residue.isEmpty(),
            "zh-rCN must not contain 「话题」 for thread surfaces; found: ${residue.keys}"
        )
    }

    @Test
    fun representativeThreadSurfacesUseMessageListTerm() {
        val zh = readStrings("zh-rCN")
        val expected =
            mapOf(
                "thread_action_open_thread" to "打开消息列",
                "thread_action_follow_thread" to "关注消息列",
                "thread_action_unfollow_thread" to "取消关注消息列",
                "thread_reply_title" to "消息列",
                "thread_feedback_unfollowed" to "已取消关注消息列",
                "thread_composer_placeholder_reply" to "在消息列中回复",
                "member_section_threads" to "消息列",
                "member_permission_threads" to "消息列",
                "home_nav_followed_threads" to "已关注的消息列",
                "search_meta_thread" to "消息列",
                "saved_meta_thread" to "消息列"
            )
        expected.forEach { (key, value) ->
            assertEquals(value, zh[key], "zh-rCN $key must be unified to 消息列")
        }
    }

    @Test
    fun baseEnglishThreadValuesAreUnchanged() {
        val base = readStrings("base")
        assertEquals("Open Thread", base["thread_action_open_thread"])
        assertEquals("Thread", base["thread_reply_title"])
        assertEquals("Unfollowed thread", base["thread_feedback_unfollowed"])
        assertEquals("Followed threads", base["home_nav_followed_threads"])
    }

    @Test
    fun placeholderParityPreservedAcrossThreadKeys() {
        val base = readStrings("base")
        val zh = readStrings("zh-rCN")
        // spot-check keys that carry placeholders stay shape-consistent
        listOf("thread_members_remove_confirm_detail").forEach { key ->
            assertEquals(
                PLACEHOLDER.findAll(base.getValue(key)).map { it.value }.toList(),
                PLACEHOLDER.findAll(zh.getValue(key)).map { it.value }.toList(),
                "$key placeholder tokens must match between base and zh-rCN"
            )
        }
        assertFalse(zh.getValue("thread_members_remove_confirm_detail").contains("话题"))
    }

    private fun readStrings(locale: String): Map<String, String> =
        STRING_ENTRY.findAll(
            String(Files.readAllBytes(resourcesRoot().resolve(locale).resolve("strings.xml")), Charsets.UTF_8)
        ).associate { it.groupValues[1] to it.groupValues[2] }

    private fun resourcesRoot(): Path {
        val workingDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val candidates =
            listOf(
                workingDirectory.resolve("src/commonMain/resources/MR"),
                workingDirectory.resolve("shared/src/commonMain/resources/MR"),
                workingDirectory.resolve("compose/shared/src/commonMain/resources/MR")
            )
        return candidates.firstOrNull(Files::isDirectory)
            ?: error("Unable to locate shared MR resources from $workingDirectory")
    }

    private companion object {
        val STRING_ENTRY = Regex("<string\\s+name=\"([^\"]+)\">([^<]*)</string>")
        val PLACEHOLDER = Regex("%\\d+\\$[a-zA-Z]")
    }
}
