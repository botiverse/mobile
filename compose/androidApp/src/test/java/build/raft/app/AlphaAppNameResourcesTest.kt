package build.raft.app

import android.app.Application
import android.content.Context
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.w3c.dom.Element

/**
 * artin 2026-10-31: the Alpha build says "Raft Alpha" on every surface Android shows outside the
 * app (two identical "Raft 正在接收消息" lock-screen notifications, one per build, were
 * indistinguishable). The mechanism is the alpha source set's resource overrides
 * (src/alpha/res/values{,-zh-rCN,-ja,-es,-pt-rBR,-ko,-zh-rTW,-ru}/strings.xml); production keeps "Raft".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AlphaAppNameResourcesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private val expectedAppName = if (BuildConfig.BUILD_TYPE == "alpha") "Raft Alpha" else "Raft"

    @Test
    fun appNameResolvesPerBuildTypeInEveryLocale() {
        for (qualifiers in listOf("en", "zh-rCN", "ja", "es", "pt-rBR", "ko", "zh-rTW", "ru")) {
            RuntimeEnvironment.setQualifiers(qualifiers)
            assertEquals("app_name [$qualifiers] on ${BuildConfig.BUILD_TYPE}", expectedAppName, context.getString(R.string.app_name))
            val keepAliveTitle = context.getString(R.string.raft_background_connection_notification_title)
            assertEquals(
                "keep-alive notification title [$qualifiers] names the build: $keepAliveTitle",
                BuildConfig.BUILD_TYPE == "alpha",
                keepAliveTitle.contains("Raft Alpha"),
            )
        }
    }

    private fun locate(relativePath: String): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(current, relativePath)
            if (candidate.isFile) return candidate
            File(current, "androidApp/$relativePath").takeIf { it.isFile }?.let { return it }
            current = current.parentFile ?: return@repeat
        }
        error("Unable to locate $relativePath from ${System.getProperty("user.dir")}")
    }

    private companion object {
        val LocaleDirs = listOf("values", "values-zh-rCN", "values-ja", "values-es", "values-pt-rBR", "values-ko", "values-zh-rTW", "values-ru")

        /** Strings Android shows outside the app that name it: add a key here and to the alpha overrides together. */
        val SystemVisibleAppNameKeys =
            listOf(
                "app_name", // launcher, recents, notification header, settings, share sheet
                "raft_background_connection_notification_title", // keep-alive foreground notification
                "raft_background_connection_channel_description", // notification settings
                "push_preview_hidden_title", // hidden-preview push title
                "settings_notification_test_title", // test notification
                "notification_unread_general_channel_description", // notification settings
                "slock_activity_widget_name", // widget picker
                "slock_agent_status_widget_name", // widget picker
                "raft_download_notification_tap_retry", // download failure notification
                "external_share_import_failed_toast", // toast over the sharing app
                "attachment_save_as_failed_toast", // toast
            )
    }
}
