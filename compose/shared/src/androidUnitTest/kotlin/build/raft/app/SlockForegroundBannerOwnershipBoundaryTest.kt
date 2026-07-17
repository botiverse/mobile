package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlockForegroundBannerOwnershipBoundaryTest {
    @Test
    fun appShellAndThreadUseOneProcessBannerOwner() {
        val provider = source("build/raft/app/data/SlockSharedStoreProvider.kt")
        val appShell = source("build/raft/app/SettingsHomePage.kt")
        val thread = source("build/raft/app/ThreadPage.kt")

        assertTrue(
            "val foregroundMessageBannerStore = SharedForegroundMessageBannerStore()" in provider,
            "The provider must own one process-lifetime foreground banner store.",
        )
        listOf(appShell, thread).forEach { page ->
            assertTrue(
                "sharedStores.foregroundMessageBannerStore" in page,
                "Every banner host must observe the provider-owned store.",
            )
            assertFalse(
                "var foregroundMessageBanner by remember" in page,
                "A route-local banner owner is destroyed during Activity/page replacement.",
            )
            assertFalse(
                "LaunchedEffect(foregroundMessageBanner?.id)" in page,
                "Route-local auto-dismiss restarts the banner deadline during Activity/page replacement.",
            )
        }
    }

    private val commonMain: File
        get() = requireNotNull(
            listOf(File("src/commonMain/kotlin"), File("shared/src/commonMain/kotlin"))
                .firstOrNull(File::isDirectory),
        ) { "Could not locate shared commonMain Kotlin sources" }

    private fun source(path: String): String {
        val file = File(commonMain, path)
        assertTrue(file.isFile, "Missing production source $path")
        return file.readText().replace("\r\n", "\n")
    }
}
