package build.raft.app.shared

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsWebViewPagerContractTest {
    @Test
    fun documentStartInjectionUsesExactHttpOriginOnly() {
        assertEquals(
            "https://app.raft.build",
            documentStartScriptOriginRule("https://app.raft.build/s/workspace/computers?embed=1")
        )
        assertEquals(
            "http://localhost:5173",
            documentStartScriptOriginRule("http://localhost:5173/computers")
        )
        assertNull(documentStartScriptOriginRule("javascript:alert(1)"))
        assertNull(documentStartScriptOriginRule("https:///missing-host"))
        assertEquals(
            "https://app.raft.build/__raft_mobile_bootstrap__",
            injectedJavaScriptBootstrapBaseUrl("https://app.raft.build/s/workspace/computers")
        )
    }

    @Test
    fun settingsChildPagerCannotEmbedWebViews() {
        val settingsHome = sourceFile("shared/src/commonMain/kotlin/build/raft/app/SettingsHomePage.kt").readText()

        assertFalse(settingsHome.contains("SlockWebView("))
        assertFalse(settingsHome.contains("SettingsBillingWebViewContent("))
        assertFalse(settingsHome.contains("SettingsComputersWebViewContent("))
        assertTrue(settingsHome.contains("onOpenComputers()"))
        assertTrue(settingsHome.contains("onOpenBilling()"))
        assertTrue(settingsHome.contains("onOpenReleaseNotes()"))
    }

    @Test
    fun computersPagerKeepsNativeCoverUntilWebContentCommit() {
        val computers = sourceFile("shared/src/commonMain/kotlin/build/raft/app/SettingsComputersPage.kt").readText()

        assertTrue(computers.contains("@Page(SETTINGS_COMPUTERS_PAGE"))
        assertTrue(computers.contains("LaunchedEffect(Unit) { bodyReady = true }"))
        assertTrue(computers.contains("onLoadSuccess = { contentVisible = true }"))
        assertTrue(computers.contains("if (!contentVisible) SettingsComputersLoadingContent()"))
        assertTrue(computers.contains(".alpha(if (contentVisible) 1f else 0f)"))
    }

    @Test
    fun legacyBootstrapCannotUncoverBeforeTheRealNavigationStarts() {
        val host = sourceFile("androidApp/src/main/java/build/raft/app/shared/SlockSandboxWebViewRenderView.kt").readText()

        assertTrue(host.contains("bootstrapDocumentPending = true"))
        assertTrue(host.contains("bootstrapDocumentUrl = injectedJavaScriptBootstrapBaseUrl(normalized)"))
        assertTrue(host.contains("bootstrapDocumentPending && target != bootstrapDocumentUrl"))
        assertTrue(host.contains("if (!bootstrapDocumentPending) notifyLoadSuccess(url, \"commit_visible\")"))
        assertTrue(host.contains("if (!bootstrapDocumentPending) notifyLoadSuccess(url, \"finished\")"))
    }

    private fun sourceFile(relativePath: String): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        var current = File(userDir).absoluteFile
        repeat(8) {
            File(current, relativePath).takeIf(File::isFile)?.let { return it }
            current = current.parentFile ?: return@repeat
        }
        error("Unable to locate $relativePath from ${System.getProperty("user.dir")}")
    }
}
