package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Narrow structural gate for Raft list pull-to-refresh policy. */
class SlockPullRefreshCallerContractTest {

    @Test
    fun allRaftListCallersUseTheSharedHeldInsetPolicy() {
        val expectedCallCounts =
            mapOf(
                "build/raft/app/SlockHomeComponents.kt" to 1,
                "build/raft/app/SharedInboxPanel.kt" to 2,
                "build/raft/app/SettingsHomePage.kt" to 1,
                "build/raft/app/MembersPage.kt" to 1,
            )

        expectedCallCounts.forEach { (path, expectedCount) ->
            val source = source(path)
            assertEquals(
                expectedCount,
                source.split("slockRaftPullToRefreshItem(").size - 1,
                "$path must route every Raft list refresh through the shared policy.",
            )
            assertFalse(
                "holdRefreshInset = false" in source,
                "$path must not opt out of the product-selected held refresh inset.",
            )
            assertFalse(
                Regex("(?<!slockRaft)pullToRefreshItem\\(").containsMatchIn(source),
                "$path must not bypass the shared Raft pull-to-refresh wrapper.",
            )
        }
    }

    @Test
    fun sharedPolicyHoldsTheInsetAndRendersAFullHeightCarrier() {
        val source = source("build/raft/app/SlockSectionHeader.kt")

        assertTrue("holdRefreshInset = true" in source)
        assertTrue("refreshThreshold = refreshThreshold" in source)
        assertTrue(".height(refreshThreshold)" in source)
        assertTrue(".background(PullRefreshCarrierColor)" in source)
        assertTrue("PullRefreshCarrierColor = SlockColor.SurfaceWhite" in source)
        assertFalse("PullRefreshCarrierColor = SlockColor.BrandYellow" in source)
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
