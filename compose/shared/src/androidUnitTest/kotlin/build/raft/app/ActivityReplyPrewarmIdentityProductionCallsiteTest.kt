package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class ActivityReplyPrewarmIdentityProductionCallsiteTest {
    @Test
    fun inboxActivityPrewarmRepositoryUsesTheSelectedRouteServer() {
        val source = commonMainSource("build/raft/app/SharedInboxPanel.kt")
        val repository = source.window("val conversationRepository =", "val memberRepository =")
        val prewarm = source.window("val replyThreadPrewarmDataSource =", "val activityStore =")

        assertContains(repository, "remember(authStore, networkClient, selectedServerStore)")
        assertContains(repository, "routeServerIdProvider = { selectedServerStore.selectedServerId.value }")
        assertContains(prewarm, "conversationRepository = conversationRepository")
        assertFalse(repository.contains("routeServerIdProvider = { authStore"))
    }

    @Test
    fun homeActivityPrewarmRepositoryUsesTheSelectedRouteServer() {
        val source = commonMainSource("build/raft/app/SettingsHomePage.kt")
        val repository = source.window("val conversationRepository =", "val agentsApiClient =")
        val prewarm = source.window("val homeActivityReplyThreadPrewarmDataSource =", "val homeActivityStore =")

        assertContains(repository, "remember(shellAuthStore, appShellNetworkClient, selectedServerStore)")
        assertContains(repository, "routeServerIdProvider = { selectedServerStore.selectedServerId.value }")
        assertContains(prewarm, "conversationRepository = conversationRepository")
        assertFalse(repository.contains("routeServerIdProvider = { shellAuthStore"))
    }

    private fun commonMainSource(relativePath: String): String {
        val commonMain =
            generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
                .map { File(it, "src/commonMain/kotlin") }
                .firstOrNull(File::isDirectory)
                ?: error("Unable to locate shared commonMain sources")
        return File(commonMain, relativePath).readText().replace("\r\n", "\n")
    }

    private fun String.window(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        val end = indexOf(endMarker, start + startMarker.length)
        require(start >= 0 && end > start) { "Missing production callsite window: $startMarker" }
        return substring(start, end)
    }
}
