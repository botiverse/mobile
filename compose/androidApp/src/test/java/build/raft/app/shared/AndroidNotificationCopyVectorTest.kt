package build.raft.app.shared

// Vendored from packages/shared/src/notificationCopy.ts (NOTIFICATION_COPY) at
//   github.com/botiverse/slock PR #16691 (merged to staging) @ 827ab8e3b96aaf5dd99148ad887ec31d3fb25e6d
// (scripts/check_notification_copy_vector.sh keeps this header, the test's assertion and
// AndroidPushCopyKeys.SLOCK_SHA in step). To update: re-copy the catalogue, add the new keys to the
// string files and AndroidPushCopyKeys, and bump all three pins together.

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
            CopyKey("push.title.app_permission_update", "{0} has an update to approve on {1}", 4),
            CopyKey("push.body.app_permission_update.sign_in", "It asks for new sign-in permissions. Approve the update or uninstall the App.", 4),
            CopyKey("push.body.app_permission_update.data", "It asks for more access to data on this Server. Approve the update or uninstall the App.", 4),
            CopyKey("push.body.app_permission_update.sign_in_data", "It asks for new sign-in permissions and more access to data on this Server. Approve the update or uninstall the App.", 4),
            CopyKey("push.body.app_permission_update.card_button", "It changed the address its card buttons call. Approve the update or uninstall the App.", 4),
            CopyKey("push.body.app_permission_update.permissions_card_button", "It asks for new permissions and changed the address its card buttons call. Approve the update or uninstall the App.", 4),
            CopyKey("push.title.lounge", "{0}’s Lounge", 5),
            CopyKey("push.title.thread_in_lounge", "Thread in {0}’s Lounge", 5),
            CopyKey("push.title.task_in_lounge", "Task in {0}’s Lounge", 5),
            CopyKey("push.subtitle.lounge", "{0}’s Lounge", 5),
            CopyKey("push.subtitle.lounge_server", "{0}’s Lounge · {1}", 5),
        )

    @Test
    fun pinnedToTheSlockCatalogue() {
        assertEquals("827ab8e3b96aaf5dd99148ad887ec31d3fb25e6d", AndroidPushCopyKeys.SLOCK_SHA)
        assertEquals(5, RAFT_ANDROID_PUSH_PREVIEW_KEY_SET)
        assertEquals(46, catalogue.size)
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
    fun allLocalesKeepThePlaceholders() {
        val en = localized("en")
        for (tag in listOf("es", "ja", "ko", "pt-BR", "ru", "zh-CN", "zh-TW")) {
            val context = localized(tag)
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
