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
        assertFalse(settingsHome.contains("StandaloneSettingsWebViewPage("))
        assertTrue(settingsHome.contains("onOpenComputers()"))
        assertTrue(settingsHome.contains("onOpenBilling()"))
        assertTrue(settingsHome.contains("onOpenReleaseNotes()"))
    }

    @Test
    fun productionWebViewCallSitesAreApprovedPagesOrTheSharedStandaloneHost() {
        val appRoot = sourceDirectory("shared/src/commonMain/kotlin/build/raft/app")
        val approvedHosts =
            setOf(
                "InAppBrowserPage.kt",
                "ReleaseNotesPage.kt",
                "SettingsStandaloneWebViewHost.kt",
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
        callSites.filterNot { it.name == "SettingsStandaloneWebViewHost.kt" }.forEach { file ->
            assertTrue("${file.name} must declare its own @Page host", file.readText().contains("@Page("))
        }
        assertFalse(
            "the reusable standalone host must not become a routable page",
            sourceFile("shared/src/commonMain/kotlin/build/raft/app/SettingsStandaloneWebViewHost.kt")
                .readText()
                .contains("@Page("),
        )
    }

    @Test
    fun computersPagerKeepsNativeCoverUntilWebContentCommit() {
        val computers = sourceFile("shared/src/commonMain/kotlin/build/raft/app/SettingsComputersPage.kt").readText()
        val host = sourceFile("shared/src/commonMain/kotlin/build/raft/app/SettingsStandaloneWebViewHost.kt").readText()

        assertTrue(computers.contains("@Page(SETTINGS_COMPUTERS_PAGE"))
        assertTrue(computers.contains("destination = SharedSettingsDestination.Developer"))
        assertTrue(computers.contains("coverUntilContentCommit = true"))
        assertTrue(computers.contains("allowedNavigationSchemes = setOf(\"http\", \"https\")"))
        assertTrue(computers.contains("Box(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceCream))"))
        assertFalse(computers.contains("repeat(4)"))
        assertFalse(computers.contains(".height(68.dp)"))
        assertTrue(host.contains("LaunchedEffect(Unit) { bodyReady = true }"))
        assertTrue(host.contains("onLoadSuccess = { contentVisible = true }"))
        assertTrue(host.contains("if (spec.coverUntilContentCommit && !contentVisible) loadingContent()"))
        assertTrue(host.contains(".alpha(if (!spec.coverUntilContentCommit || contentVisible) 1f else 0f)"))
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

    @Test
    fun fourSettingsWebViewsUseTheSharedRuntimeAccessTokenFence() {
        val expectations =
            mapOf(
                "SettingsComputersPage.kt" to
                    Pair("SettingsWebViewSurface.Computers", "SharedSettingsDestination.Developer"),
                "SettingsBillingPage.kt" to
                    Pair("SettingsWebViewSurface.Billing", "SharedSettingsDestination.Billing"),
                "SettingsMarketplacePage.kt" to
                    Pair("SettingsWebViewSurface.Marketplace", "SharedSettingsDestination.Developer"),
                "SettingsAdministrationPage.kt" to
                    Pair("SettingsWebViewSurface.Administration", "SharedSettingsDestination.Administration"),
            )
        val host = sourceFile("shared/src/commonMain/kotlin/build/raft/app/SettingsStandaloneWebViewHost.kt").readText()

        assertEquals(1, Regex("\\bSlockWebView\\(").findAll(host).count())
        assertEquals(1, Regex("\\bSettingsWebViewAccessTokenSyncEffect\\(").findAll(host).count())
        assertEquals(1, Regex("\\brememberSettingsWebViewHostAuthBinding\\(").findAll(host).count())
        assertTrue(host.contains("rememberSettingsWebViewAuthSnapshot"))
        assertTrue(host.contains("key(hostAuthBinding?.webViewGeneration)"))
        assertTrue(host.contains("remember(webUrl, hostAuthBinding?.webViewGeneration)"))
        assertTrue(host.contains("navigationState.progress >= 100"))
        assertTrue(host.contains("surface = spec.surface"))
        assertTrue(host.contains("accessTokenSyncLogger = bridge::log"))
        assertTrue(host.contains("standaloneSettingsWebViewInjectedJavaScript(session, hostAuthBinding, spec.surface)"))
        assertTrue(host.contains("settingsWebViewAuthStorageJavaScript(session, hostBinding)"))

        expectations.forEach { (fileName, expectation) ->
            val source = sourceFile("shared/src/commonMain/kotlin/build/raft/app/$fileName").readText()
            assertTrue("$fileName must use the shared page host", source.contains("StandaloneSettingsWebViewPage("))
            assertTrue("$fileName must declare a shared-host spec", source.contains("StandaloneSettingsWebViewSpec("))
            assertTrue("$fileName must declare its surface identity", source.contains(expectation.first))
            assertTrue("$fileName must declare its store destination", source.contains(expectation.second))
            assertFalse("$fileName must not own a native WebView", source.contains("SlockWebView("))
            assertFalse("$fileName must not copy token sync", source.contains("SettingsWebViewAccessTokenSyncEffect"))
            assertFalse("$fileName must not copy auth binding", source.contains("rememberSettingsWebViewHostAuthBinding"))
            assertFalse("$fileName must not copy auth bootstrap writes", source.contains("settingsWebViewAuthStorageJavaScript"))
        }
    }

    @Test
    fun fourStandaloneSettingsWebViewsUseTheCanonicalEnvironmentWebUrl() {
        val standaloneEnvironment =
            sourceFile("shared/src/commonMain/kotlin/build/raft/app/SettingsStandaloneStore.kt").readText()
        val pageFiles =
            listOf(
                "SettingsComputersPage.kt",
                "SettingsBillingPage.kt",
                "SettingsMarketplacePage.kt",
                "SettingsAdministrationPage.kt",
            )

        assertTrue(standaloneEnvironment.contains("apiEnvironmentStore.current().webUrl"))
        val host = sourceFile("shared/src/commonMain/kotlin/build/raft/app/SettingsStandaloneWebViewHost.kt").readText()
        assertTrue(host.contains("webBaseUrl = environment.webBaseUrl"))
        pageFiles.forEach { fileName ->
            val source = sourceFile("shared/src/commonMain/kotlin/build/raft/app/$fileName").readText()
            assertTrue("$fileName must enter through the canonical shared host", source.contains("StandaloneSettingsWebViewPage("))
            assertFalse(
                "$fileName must not depend on the asynchronously loaded developer snapshot",
                source.contains("webBaseUrl = settingsState.developer.configuredWebBaseUrlOrNull()"),
            )
            assertFalse("$fileName must not resolve an environment itself", source.contains("rememberStandaloneSettingsEnvironment"))
        }

        val marketplace = sourceFile("shared/src/commonMain/kotlin/build/raft/app/SettingsMarketplacePage.kt").readText()
        assertFalse(marketplace.contains("settingsState.developer is ScreenLoadState.Loading"))
    }

    @Test
    fun fourStandaloneSettingsPagesKeepOnlyExplicitSurfaceDifferences() {
        val expectations =
            mapOf(
                "SettingsComputersPage.kt" to
                    listOf(
                        "settings_computers_title",
                        "SlockLucideIconName.Monitor",
                        "SlockColor.SurfaceCream",
                        "settingsComputersWebViewUrl",
                        "SettingsComputersLoadingContent()",
                    ),
                "SettingsBillingPage.kt" to
                    listOf(
                        "settings_billing_title",
                        "SlockLucideIconName.CreditCard",
                        "includeLocaleQuery = true",
                        "settingsBillingWebViewUrl",
                        "SettingsBillingLoadingContent()",
                    ),
                "SettingsMarketplacePage.kt" to
                    listOf(
                        "settings_connected_apps_title",
                        "SlockLucideIconName.Link2",
                        "SlockColor.SurfaceWhite",
                        "settingsMarketplaceWebViewUrl",
                        "SlockCenteredLoading",
                    ),
                "SettingsAdministrationPage.kt" to
                    listOf(
                        "settings_server_administration_title",
                        "SlockLucideIconName.Shield",
                        "includeLocaleQuery = true",
                        "settingsAdministrationWebViewUrl",
                        "repeat(4)",
                    ),
            )

        expectations.forEach { (fileName, requiredTokens) ->
            val source = sourceFile("shared/src/commonMain/kotlin/build/raft/app/$fileName").readText()
            requiredTokens.forEach { token ->
                assertTrue("$fileName must preserve explicit product difference: $token", source.contains(token))
            }
            assertFalse("$fileName route must not carry a Web URL", source.contains("custom_web_url"))
            assertFalse("$fileName route must not carry auth/product payload", source.contains("put(\"payload\""))
        }
    }

    @Test
    fun androidRuntimeJavaScriptIsEphemeralAndSameOriginDocumentGated() {
        val host = sourceFile("androidApp/src/main/java/build/raft/app/shared/SlockSandboxWebViewRenderView.kt").readText()
        val controller = sourceFile("shared/src/commonMain/kotlin/build/raft/app/SlockSandboxWebView.kt").readText()

        assertTrue(controller.contains("updates must never queue across detach/recreate"))
        assertTrue(controller.contains("val binding = commandBinding ?: return false"))
        assertTrue(host.contains("lastSuccessfulUrl.isBlank()"))
        assertTrue(host.contains("activeOrigin != sourceOrigin"))
        assertTrue(host.contains("pendingRuntimeJavaScript = script"))
        assertTrue(host.indexOf("evaluateInjectedJavaScript()") < host.indexOf("flushPendingRuntimeJavaScript()"))
        assertTrue(host.contains("notifyLoadSuccess(url, \"finished\", beforeCallback = ::flushPendingRuntimeJavaScript)"))
        assertTrue(host.indexOf("beforeCallback?.invoke()") < host.indexOf("loadSuccessCallback?.invoke"))
        assertTrue(host.contains("pendingRuntimeJavaScript = \"\"\n        removeDocumentStartScript()"))
        assertTrue(host.contains("targetOrigin != sourceOrigin"))
        assertTrue(host.contains("if (src.isNotBlank()) installDocumentStartScript(src)"))
        assertTrue(host.contains("MAX_RUNTIME_JAVA_SCRIPT_LENGTH"))
        assertFalse(host.contains("runtime_js script="))
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
