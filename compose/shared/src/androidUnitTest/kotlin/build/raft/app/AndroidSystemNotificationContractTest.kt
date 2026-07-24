package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * task #361: the shared Settings > Notifications surface polled
 * `getNotificationPermissionStatus` on every load, but the Android host had no
 * handler for it — the bridge fell into the `settings_capability_unknown`
 * branch and the permission banner claimed "needs permission" forever, on
 * every device, regardless of the real system state. This contract pins the
 * whole class shut: every settings notification action the shared side
 * dispatches must have an explicit case in the Android capability switch, and
 * the status codes the host emits must stay inside the set the shared mapper
 * understands.
 */
class AndroidSystemNotificationContractTest {
    private fun sourceFile(vararg candidates: String): String {
        return candidates.map(::File).firstOrNull(File::isFile)?.readText()?.replace("\r\n", "\n")
            ?: error("Unable to locate any of: ${candidates.joinToString()}")
    }

    private fun activitySource(): String =
        sourceFile(
            "../androidApp/src/main/java/build/raft/app/shared/SlockKuiklyActivity.kt",
            "compose/androidApp/src/main/java/build/raft/app/shared/SlockKuiklyActivity.kt"
        )

    private fun bridgeAuthSource(): String =
        sourceFile(
            "src/commonMain/kotlin/build/raft/app/data/BridgeAuth.kt",
            "compose/shared/src/commonMain/kotlin/build/raft/app/data/BridgeAuth.kt"
        )

    @Test
    fun everySharedNotificationSettingsActionHasAnAndroidHandler() {
        val bridge = bridgeAuthSource()
        val activity = activitySource()
        val dispatchedActions =
            listOf(
                "getNotificationPermissionStatus",
                "requestNotificationPermission",
                "openNotificationSettings",
                "sendTestNotification"
            )
        for (action in dispatchedActions) {
            assertContains(bridge, "\"$action\"", false, "shared side no longer dispatches $action; update this contract")
            assertContains(activity, "\"$action\" ->", false, "Android capability switch is missing a handler for $action")
        }
    }

    @Test
    fun androidPermissionStatusCodesStayWithinTheSharedMapping() {
        val activity = activitySource()
        val capability =
            activity.substringAfter("private fun getNotificationPermissionStatusCapability(")
                .substringBefore("private fun sendTestNotificationCapability(")
        val emittedCodes =
            Regex("\"(permission_[a-z_]+)\"").findAll(capability).map { it.groupValues[1] }.toSet()
        assertTrue(emittedCodes.isNotEmpty(), "status capability must emit permission_* codes")
        val sharedMapper =
            sourceFile(
                "src/commonMain/kotlin/build/raft/app/data/BridgeAuth.kt",
                "compose/shared/src/commonMain/kotlin/build/raft/app/data/BridgeAuth.kt"
            ).substringAfter("internal fun systemNotificationPermissionSnapshot(")
        for (code in emittedCodes) {
            assertContains(sharedMapper, "\"$code\"", false, "shared snapshot mapper does not understand $code")
        }
    }

    @Test
    fun testNotificationPostsOnTheHeadsUpMentionChannel() {
        val activity = activitySource()
        val testCapability =
            activity.substringAfter("private fun sendTestNotificationCapability(")
                .substringBefore("private fun postUnreadNotificationCapability(")
        // A test that lands silently in the shade reads as "nothing happened";
        // it must ride the HIGH-importance mention channel end to end (both the
        // blocked-channel precheck and the actual post).
        val mentionChannelUses =
            Regex("UNREAD_MENTION_NOTIFICATION_CHANNEL_ID").findAll(testCapability).count()
        assertTrue(mentionChannelUses >= 2, "test notification must precheck and post on the mention channel")
        assertContains(testCapability, "PRIORITY_HIGH")
        val poster =
            sourceFile(
                "../androidApp/src/main/java/build/raft/app/shared/AndroidUnreadNotificationPoster.kt",
                "compose/androidApp/src/main/java/build/raft/app/shared/AndroidUnreadNotificationPoster.kt"
            )
        val mentionChannelBlock =
            poster.substringAfter("UNREAD_MENTION_NOTIFICATION_CHANNEL_ID,")
                .substringBefore(").apply")
        assertContains(mentionChannelBlock, "IMPORTANCE_HIGH")
    }
}
