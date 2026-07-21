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
    fun allProductionWebViewCallSitesOwnIndependentPages() {
        val appRoot = sourceDirectory("shared/src/commonMain/kotlin/build/raft/app")
        val approvedHosts =
            setOf(
                "InAppBrowserPage.kt",
                "ReleaseNotesPage.kt",
                "SettingsAdministrationPage.kt",
                "SettingsBillingPage.kt",
                "SettingsComputersPage.kt",
                "SettingsMarketplacePage.kt",
                "SlockFilePreview.kt"
            )
        val callSites =
            appRoot.walkTopDown()
                .filter(File::isFile)
                .filter { it.extension == "kt" && it.name != "SlockSandboxWebView.kt" }
                .filter { file ->
                    val source = file.readText()
                    source.contains("SlockWebView(") || source.contains("SlockSandboxWebView(")
                }
                .toList()

        assertEquals(approvedHosts, callSites.map(File::getName).toSet())
        callSites.forEach { file ->
            assertTrue("${file.name} must declare its own @Page host", file.readText().contains("@Page("))
        }
    }

    @Test
    fun computersPagerKeepsNativeCoverUntilWebContentCommit() {
        val computers = sourceFile("shared/src/commonMain/kotlin/build/raft/app/SettingsComputersPage.kt").readText()

        assertTrue(computers.contains("@Page(SETTINGS_COMPUTERS_PAGE"))
        assertTrue(computers.contains("OpenDestination(SharedSettingsDestination.Developer)"))
        assertFalse(computers.contains("OpenDestination(SharedSettingsDestination.Computers)"))
        assertTrue(computers.contains("LaunchedEffect(Unit) { bodyReady = true }"))
        assertTrue(computers.contains("onLoadSuccess = { contentVisible = true }"))
        assertTrue(computers.contains("if (!contentVisible) SettingsComputersLoadingContent()"))
        assertTrue(computers.contains(".alpha(if (contentVisible) 1f else 0f)"))
        assertTrue(computers.contains("Box(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceCream))"))
        assertFalse(computers.contains("repeat(4)"))
        assertFalse(computers.contains(".height(68.dp)"))
    }

    @Test
    fun legacyBootstrapCannotUncoverBeforeTheRealNavigationStarts() {
        val host = sourceFile("androidApp/src/main/java/build/raft/app/shared/SlockSandboxWebViewRenderView.kt").readText()

        assertTrue(host.contains("bootstrapNavigationFence.begin(targetUrl = normalized)"))
        assertTrue(host.contains("\"UTF-8\",\n                bootstrapDocumentUrl"))
        assertTrue(host.contains("bootstrapNavigationFence.onMainFrameStarted(target)"))
        assertTrue(host.contains("bootstrapNavigationFence.mayUncover(url)"))
    }

    @Test
    fun bootstrapFenceKeepsCoverUntilExactTargetCommit() {
        val fence = WebViewBootstrapNavigationFence()
        val bootstrap = "https://app.raft.build/__raft_mobile_bootstrap__"
        val target = "https://app.raft.build/s/workspace/computers"

        fence.begin(target)
        fence.onMainFrameStarted(bootstrap)
        assertFalse(fence.mayUncover(bootstrap))
        assertFalse(fence.mayUncover(target))

        fence.onMainFrameStarted(target)
        assertFalse(fence.mayUncover(bootstrap))
        assertTrue(fence.mayUncover(target))
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

    private fun sourceDirectory(relativePath: String): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        var current = File(userDir).absoluteFile
        repeat(8) {
            File(current, relativePath).takeIf(File::isDirectory)?.let { return it }
            current = current.parentFile ?: return@repeat
        }
        error("Unable to locate $relativePath from ${System.getProperty("user.dir")}")
    }
}
