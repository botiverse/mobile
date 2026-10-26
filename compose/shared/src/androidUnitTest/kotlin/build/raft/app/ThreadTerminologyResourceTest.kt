package build.raft.app

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the zh-rCN thread terminology to 「消息列」 (task #103, #proj-i18n:96e1bd2d).
 *
 * artin unified thread → 消息列 (web PR #6310; mobile sync per @AngLee ruling). Every
 * conversation-thread surface must read 消息列, no thread 「话题」 may regress, and the
 * placeholder shape of every touched key must stay identical to base.
 */
class ThreadTerminologyResourceTest {

    // The exact 34 zh-rCN keys whose English is thread/Thread/threads and whose 话题 was
    // unified to 消息列. Frozen set: parity below asserts it is complete and unchanged.
    private val frozenThreadKeys =
        listOf(
            "activity_fallback_thread_update",
            "home_nav_followed_threads",
            "member_label_follow_thread",
            "member_permission_message_send_hint",
            "member_permission_messages_hint",
            "member_permission_thread_unfollow_hint",
            "member_permission_threads",
            "member_permission_threads_hint",
            "member_section_threads",
            "saved_meta_thread",
            "search_meta_thread",
            "search_thread_discussion",
            "settings_mobile_single_tap_thread",
            "settings_mobile_single_tap_thread_detail",
            "settings_mobile_single_tap_thread_enabled",
            "thread_action_follow_thread",
            "thread_action_reply_in_thread",
            "thread_action_unfollow_thread",
            "thread_comments_blocked_unlinked",
            "thread_composer_members_this_thread",
            "thread_composer_placeholder_reply",
            "thread_error_access_resolution",
            "thread_error_follow",
            "thread_error_load",
            "thread_error_no_permission",
            "thread_error_open_thread",
            "thread_error_search_unavailable",
            "thread_error_unfollow",
            "thread_feedback_followed",
            "thread_feedback_unfollowed",
            "thread_files_empty_detail",
            "thread_members_remove_confirm_detail",
            "thread_reply_title",
            "thread_task_candidate_tap_open"
        )

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
    fun frozenKeySetIsCompleteAndPlaceholderParityHoldsPerKey() {
        val base = readStrings("base")
        val zh = readStrings("zh-rCN")

        // Set completeness: the frozen set is exactly 34 and every key exists in both locales.
        assertEquals(34, frozenThreadKeys.size, "frozen thread key set must stay at 34")
        frozenThreadKeys.forEach { key ->
            assertTrue(key in base, "base strings.xml missing frozen key $key")
            assertTrue(key in zh, "zh-rCN strings.xml missing frozen key $key")
        }

        // Per-key placeholder-token-sequence parity across the ENTIRE frozen set.
        assertEquals(
            emptyList(),
            threadPlaceholderMismatches(base, zh, frozenThreadKeys),
            "every frozen thread key must keep base/zh placeholder tokens identical"
        )
    }

    @Test
    fun placeholderParityDetectsAccidentalPlaceholderMutation() {
        val base = readStrings("base")
        val zh = readStrings("zh-rCN")

        // Mutation: inject a stray %1$s into one zh value that base does not carry.
        val victim = "thread_action_reply_in_thread" // base "Reply in Thread" has no placeholder
        assertEquals(emptyList(), threadPlaceholderMismatches(base, zh, listOf(victim)))
        val mutatedZh = zh.toMutableMap().also { it[victim] = it.getValue(victim) + " %1\$s" }
        assertEquals(
            listOf(victim),
            threadPlaceholderMismatches(base, mutatedZh, listOf(victim)),
            "adding a stray %1\$s to a zh value must be flagged"
        )

        // Mutation on a multi-placeholder key: drop one placeholder -> also flagged.
        val multiVictim = "thread_composer_members_this_thread" // base "IN THIS THREAD"
        val baseMulti = base.toMutableMap().also { it[multiVictim] = "IN THIS THREAD %1\$s" }
        val zhMulti = zh.toMutableMap().also { it[multiVictim] = "当前消息列成员 %1\$s" }
        assertEquals(emptyList(), threadPlaceholderMismatches(baseMulti, zhMulti, listOf(multiVictim)))
        val zhMultiDropped = zh.toMutableMap().also { it[multiVictim] = "当前消息列成员" }
        assertEquals(
            listOf(multiVictim),
            threadPlaceholderMismatches(baseMulti, zhMultiDropped, listOf(multiVictim)),
            "dropping a placeholder from a zh value must be flagged"
        )
    }

    private fun threadPlaceholderMismatches(
        base: Map<String, String>,
        zh: Map<String, String>,
        keys: List<String>
    ): List<String> =
        keys.filter { key ->
            placeholderTokens(base.getValue(key)) != placeholderTokens(zh.getValue(key))
        }

    @Test
    fun androidNativeCatalogHasNoThreadTopicTermResidue() {
        val zh = readAndroidNativeStrings()
        val residue = zh.filterValues { it.contains("话题") }
        assertTrue(
            residue.isEmpty(),
            "androidApp zh-rCN must not contain 「话题」 for thread surfaces; found: ${residue.keys}"
        )
    }

    private fun placeholderTokens(value: String): List<String> =
        PLACEHOLDER.findAll(value).map { it.value }.toList()

    private fun readStrings(locale: String): Map<String, String> =
        STRING_ENTRY.findAll(
            String(Files.readAllBytes(resourcesRoot().resolve(raftComposeValuesDir(locale)).resolve("strings.xml")), Charsets.UTF_8)
        ).associate { it.groupValues[1] to it.groupValues[2] }

    private fun readAndroidNativeStrings(): Map<String, String> {
        val workingDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val candidates =
            listOf(
                workingDirectory.resolve("../androidApp/src/main/res/values-zh-rCN/strings.xml"),
                workingDirectory.resolve("androidApp/src/main/res/values-zh-rCN/strings.xml"),
                workingDirectory.resolve("compose/androidApp/src/main/res/values-zh-rCN/strings.xml")
            )
        val path =
            candidates.firstOrNull(Files::isRegularFile)
                ?: error("Unable to locate androidApp values-zh-rCN/strings.xml from $workingDirectory")
        return STRING_ENTRY.findAll(String(Files.readAllBytes(path), Charsets.UTF_8))
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun resourcesRoot(): Path {
        val workingDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val candidates =
            listOf(
                workingDirectory.resolve("src/commonMain/composeResources"),
                workingDirectory.resolve("shared/src/commonMain/composeResources"),
                workingDirectory.resolve("compose/shared/src/commonMain/composeResources")
            )
        return candidates.firstOrNull(Files::isDirectory)
            ?: error("Unable to locate shared composeResources from $workingDirectory")
    }

    private companion object {
        val STRING_ENTRY = Regex("<string\\s+name=\"([^\"]+)\">([^<]*)</string>")
        val PLACEHOLDER = Regex("%\\d+\\$[a-zA-Z]")
    }
}
