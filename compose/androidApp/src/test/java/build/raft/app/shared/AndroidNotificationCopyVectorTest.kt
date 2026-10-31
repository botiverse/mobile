package build.raft.app.shared

// Vendored from packages/shared/src/notificationCopy.ts (NOTIFICATION_COPY) at
//   github.com/botiverse/slock PR #12282 (merged to staging) @ fbc091209976f3d22d58f0cc65e330599c45bccf
// (scripts/check_notification_copy_vector.sh keeps this header, the test's assertion and
// AndroidPushCopyKeys.SLOCK_SHA in step). To update: re-copy the catalogue, add the new keys to the
// three string files and AndroidPushCopyKeys, and bump all three pins together.

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import build.raft.app.data.RAFT_ANDROID_PUSH_PREVIEW_KEY_SET
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Every key the server sends a registration of [RAFT_ANDROID_PUSH_PREVIEW_KEY_SET] has an Android
 * string: the English one is the server's template (`{0}` = `%1$s`), zh-CN and ja carry the same
 * placeholders, and zh-CN is translated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidNotificationCopyVectorTest {
    private data class CopyKey(val key: String, val en: String, val keySet: Int)

    private val catalogue =
        listOf(
            CopyKey("push.preview.hidden.title", "Raft", 1),
            CopyKey("push.preview.hidden.message", "You have a new message", 1),
            CopyKey("push.preview.hidden.mention", "You were mentioned", 1),
            CopyKey("push.preview.sender_only.message", "{0} sent a message", 1),
            CopyKey("push.preview.sender_only.mention", "{0} mentioned you", 1),
            CopyKey("push.preview.sender_only.task", "{0}: task update", 2),
            CopyKey("push.preview.sender_only.agent", "{0} needs your attention", 2),
            CopyKey("push.preview.sender_only.reminder", "{0}: reminder", 2),
            CopyKey("push.preview.sender_only.notice", "{0}: new notice", 2),
            CopyKey("push.preview.hidden.task", "You have a task update", 2),
            CopyKey("push.preview.hidden.agent", "An agent needs your attention", 2),
            CopyKey("push.preview.hidden.reminder", "A reminder is due", 2),
            CopyKey("push.preview.hidden.notice", "You have a new notice", 2),
            CopyKey("push.title.dm", "DM", 3),
            CopyKey("push.title.thread", "Thread", 3),
            CopyKey("push.title.thread_in_dm", "Thread in DM", 3),
            CopyKey("push.title.thread_in_channel", "Thread in #{0}", 3),
            CopyKey("push.title.task", "Task", 3),
            CopyKey("push.title.task_in_dm", "Task in DM", 3),
            CopyKey("push.title.task_in_channel", "Task in #{0}", 3),
            CopyKey("push.title.task_assigned", "Assigned to you", 3),
            CopyKey("push.title.agent_action_required", "{0} needs your attention", 3),
            CopyKey("push.title.agent_tool_stuck", "{0} is stuck", 3),
            CopyKey("push.title.reminder", "Reminder", 3),
            CopyKey("push.title.agent_migration_review", "Confirm moving {0} to the cloud", 3),
            CopyKey("push.title.test", "Raft test notification", 3),
            CopyKey("push.subtitle.dm", "DM", 3),
            CopyKey("push.subtitle.dm_server", "DM · {0}", 3),
            CopyKey("push.body.attachment", "Sent an attachment", 3),
            CopyKey("push.body.attachments", "Sent {0} attachments", 3),
            CopyKey("push.body.no_text", "(no text)", 3),
            CopyKey("push.body.sender_attachment", "{0}: Sent an attachment", 3),
            CopyKey("push.body.sender_attachments", "{0}: Sent {1} attachments", 3),
            CopyKey("push.body.sender_no_text", "{0}: (no text)", 3),
            CopyKey("push.body.agent_migration_review", "{0} is paused until you confirm. Review which files come over before the window closes.", 3),
            CopyKey("push.body.test", "If you can read this, notifications reach this device.", 3),
        )

    @Test
    fun pinnedToTheSlockCatalogue() {
        assertEquals("fbc091209976f3d22d58f0cc65e330599c45bccf", AndroidPushCopyKeys.SLOCK_SHA)
        assertEquals(3, RAFT_ANDROID_PUSH_PREVIEW_KEY_SET)
        assertEquals(36, catalogue.size)
    }

    @Test
    fun everyKeyOfTheDeclaredSetHasItsEnglishTemplate() {
        val en = localized("en")
        val declared = catalogue.filter { it.keySet <= RAFT_ANDROID_PUSH_PREVIEW_KEY_SET }
        assertEquals(catalogue.size, declared.size)
        for (copy in declared) {
            val (resource, argCount) = AndroidPushCopyKeys.resources[copy.key] ?: error("no Android string for ${copy.key}")
            assertEquals(copy.key, placeholders(copy.en).size, argCount)
            assertEquals(copy.key, androidTemplate(copy.en), en.getString(resource))
        }
        // No Android key the server never sends.
        assertEquals(catalogue.map { it.key }.toSet(), AndroidPushCopyKeys.resources.keys)
    }

    @Test
    fun zhCnAndJaKeepThePlaceholders() {
        val en = localized("en")
        for ((tag, context) in listOf("zh-CN" to localized("zh-CN"), "ja" to localized("ja"))) {
            for (copy in catalogue) {
                val (resource, _) = AndroidPushCopyKeys.resources.getValue(copy.key)
                val text = context.getString(resource)
                assertEquals("$tag ${copy.key}", androidPlaceholders(androidTemplate(copy.en)), androidPlaceholders(text))
                if (tag == "zh-CN" && copy.key != "push.preview.hidden.title") {
                    assertNotEquals("$tag ${copy.key} is not translated", en.getString(resource), text)
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun theResolverFormatsArgsAndRefusesMissingOnes() {
        val strings = AndroidResourcePushPreviewStrings(RuntimeEnvironment.getApplication())
        assertEquals("#general 中的消息列", strings.resolve("push.title.thread_in_channel", listOf("general")))
        assertEquals("Alice：发送了 3 个附件", strings.resolve("push.body.sender_attachments", listOf("Alice", "3")))
        assertEquals("私信 · Acme", strings.resolve("push.subtitle.dm_server", listOf("Acme")))
        assertEquals(null, strings.resolve("push.body.sender_attachments", listOf("Alice")))
        assertEquals(null, strings.resolve("push.title.agent_tool_stuck", listOf(" ")))
        assertEquals(null, strings.resolve("push.title.from_the_future", emptyList()))
    }

    private fun localized(tag: String): Context {
        val base = RuntimeEnvironment.getApplication()
        val configuration = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return base.createConfigurationContext(configuration)
    }

    private fun placeholders(template: String): Set<String> =
        Regex("""\{(\d+)\}""").findAll(template).map { it.groupValues[1] }.toSet()

    private fun androidTemplate(template: String): String =
        template.replace(Regex("""\{(\d+)\}""")) { "%" + (it.groupValues[1].toInt() + 1) + "\$s" }

    private fun androidPlaceholders(text: String): Set<String> =
        Regex("%(\\d+)\\\$s").findAll(text).map { it.value }.toSet()
}
