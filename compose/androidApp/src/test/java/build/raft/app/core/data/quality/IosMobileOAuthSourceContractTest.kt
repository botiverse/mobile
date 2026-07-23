package build.raft.app.core.data.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IosMobileOAuthSourceContractTest {
    @Test
    fun iosHostUsesRetainedSystemAuthenticationSessionAndCryptographicPkce() {
        val host = sourceFile("compose/iosApp/iosApp/SlockIosMobileOAuthHost.swift").readText()

        assertTrue(host.contains("ASWebAuthenticationSession("))
        assertTrue(host.contains("private var session: ASWebAuthenticationSession?"))
        assertTrue(host.contains("authSession.presentationContextProvider = self"))
        assertTrue(host.contains("SecRandomCopyBytes"))
        assertTrue(host.contains("SHA256.hash"))
        assertTrue(host.contains("prefersEphemeralWebBrowserSession = false"))
        assertFalse(host.contains("GOOGLE_CLIENT_SECRET"))
        assertFalse(host.contains("GITHUB_CLIENT_SECRET"))
    }

    @Test
    fun callbackIsOwnedByExactRouteAcrossAuthenticationSessionAndColdWarmLaunches() {
        val host = sourceFile("compose/iosApp/iosApp/SlockIosMobileOAuthHost.swift").readText()
        val app = sourceFile("compose/iosApp/iosApp/iOSApp.swift").readText()
        val content = sourceFile("compose/iosApp/iosApp/ContentView.swift").readText()
        val plist = sourceFile("compose/iosApp/iosApp/Info.plist").readText()

        assertTrue(host.contains("[\"raft\", \"raft-dev\"].contains(scheme)"))
        assertTrue(host.contains("components.host?.lowercased() == \"oauth\""))
        assertTrue(host.contains("components.path == \"/callback\""))
        assertTrue(host.contains("expectedGeneration: generation"))
        assertTrue(host.contains("result.status == \"retry\""))
        assertTrue(host.contains("private var pendingStartGeneration: String?"))
        assertTrue(host.contains("self.pendingStartGeneration != nil || self.activeGeneration != nil"))
        assertTrue(host.contains("expectedGeneration: deferred.expectedGeneration ?? result.generation"))
        assertTrue(host.contains("error: \"runtime_not_ready\", applyRoute: true"))
        assertTrue(host.contains("self.supersededGenerations.remove(generation) == nil"))
        assertFalse(host.contains("!self.supersededGenerations.remove(generation)"))
        assertTrue(content.contains(".onOpenURL { url in"))
        assertTrue(app.contains("open url: URL"))
        assertTrue(app.contains("source: \"cold_launch\""))
        assertTrue(plist.contains("<key>CFBundleURLTypes</key>"))
        assertTrue(plist.contains("<string>raft</string>"))
        assertTrue(plist.contains("<string>raft-dev</string>"))
    }

    @Test
    fun kuiklyBridgeExposesLoginLinkAndProviderButtonsWithoutLegacyStub() {
        val bridge =
            sourceFile("compose/iosApp/iosApp/KuiklyExpand/Modules/HRBridgeModule.m")
                .readText()
        val content = sourceFile("compose/iosApp/iosApp/ContentView.swift").readText()

        assertTrue(bridge.contains("startWithProvider:provider"))
        assertTrue(bridge.contains("mode:@\"login\""))
        assertTrue(bridge.contains("[action isEqualToString:@\"mobileOAuthLink\"]"))
        assertTrue(bridge.contains("mode:@\"link\""))
        assertFalse(bridge.contains("social_auth_unavailable"))
        assertTrue(content.contains("data[\"slockSocialAuthProviders\"] = slockIosOAuthProviders"))
    }

    private fun sourceFile(relativePath: String): File {
        var current = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            File(current, relativePath).takeIf(File::isFile)?.let { return it }
            current = current.parentFile ?: return@repeat
        }
        error("Unable to locate $relativePath from ${System.getProperty("user.dir")}")
    }
}
