package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosSystemNotificationContractTest {
    private fun iosAppSource(): String {
        val candidates =
            listOf(
                File("../iosApp/iosApp/iOSApp.swift"),
                File("iosApp/iosApp/iOSApp.swift"),
                File("compose/iosApp/iosApp/iOSApp.swift")
            )
        return candidates.firstOrNull(File::isFile)?.readText()?.replace("\r\n", "\n")
            ?: error("Unable to locate compose/iosApp/iosApp/iOSApp.swift")
    }

    @Test
    fun iosHostPresentsAlertsAndRoutesOnlySanitizedIdentity() {
        val source = iosAppSource()

        assertContains(
            source,
            "final class SlockIosAppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate"
        )
        assertContains(source, "UNUserNotificationCenter.current().delegate = self")
        assertContains(source, "completionHandler([.banner, .list, .sound, .badge])")
        assertContains(source, "source: \"cold_launch\"")
        assertContains(source, "source: \"notification_response\"")
        assertContains(source, "Notification.Name(\"slockSystemNotificationOpen\")")
        assertContains(source, "Notification.Name(\"slockSystemNotificationOpenReady\")")
        assertContains(source, "Notification.Name(\"slockSystemNotificationOpenNotReady\")")
        assertContains(source, "\"slockNotificationServerId\": serverId")
        assertContains(source, "\"slockNotificationThreadId\": threadId")
        assertContains(source, "\"slockNotificationMessageId\": messageId")
        assertTrue(source.contains("[\"channel\", \"dm\", \"thread\"].contains(kind)"))

        val sanitizer =
            source.substringAfter("private static func sanitizedSystemNotificationOpenPayload(")
                .substringBefore("private static func systemNotificationOpenKey(")
        assertFalse(sanitizer.contains("alert"))
        assertFalse(sanitizer.contains("body"))
        assertFalse(sanitizer.contains("title"))
        assertFalse(sanitizer.contains("content"))
    }
}
