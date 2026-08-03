package build.raft.app

import build.raft.app.data.SharedAuthRuntimeCapabilityStore
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthRuntimeCapabilityRouteTest {
    @Test
    fun loginChooseServerLogoutKeepsSocialProvidersInRuntimeStore() {
        val store = SharedAuthRuntimeCapabilityStore()
        val initialLogin =
            JSONObject()
                .put("pageName", LOGIN_PAGE)
                .put(AUTH_SOCIAL_PROVIDERS_KEY, "google,github,apple")

        val initialCapabilities = store.seedFromAuthPageData(initialLogin)
        val chooseServer =
            postAuthRouteDestination(
                StoredAuthSession(
                    user = StoredAuthUser(id = "user-1", email = "artin@raft.build"),
                    accessToken = "access",
                    refreshToken = "refresh"
                )
            )
        val loginAfterLogout = loginPageData(replaceCurrent = true)

        assertEquals(setOf("google", "github", "apple"), initialCapabilities.socialProviderIds)
        assertEquals(AUTH_CHOOSE_SERVER_PAGE, chooseServer.pageName)
        assertFalse(chooseServer.pageData.has(AUTH_SOCIAL_PROVIDERS_KEY))
        assertFalse(loginAfterLogout.has(AUTH_SOCIAL_PROVIDERS_KEY))
        assertTrue(loginAfterLogout.optBoolean("slockReplaceCurrentPage", false))
        assertEquals(
            setOf("google", "github", "apple"),
            store.seedFromAuthPageData(chooseServer.pageData).socialProviderIds
        )
        assertEquals(
            setOf("google", "github", "apple"),
            store.seedFromAuthPageData(loginAfterLogout).socialProviderIds
        )
    }

    @Test
    fun hostSeedNormalizesReplacesAndCanExplicitlyClearProviders() {
        val store = SharedAuthRuntimeCapabilityStore()

        store.seedFromAuthPageData(
            JSONObject().put(AUTH_SOCIAL_PROVIDERS_KEY, " GitHub, APPLE, GOOGLE, github ")
        )
        assertEquals(setOf("github", "apple", "google"), store.current().socialProviderIds)

        store.seedFromAuthPageData(JSONObject().put("pageName", LOGIN_PAGE))
        assertEquals(setOf("github", "apple", "google"), store.current().socialProviderIds)

        store.seedFromAuthPageData(JSONObject().put(AUTH_SOCIAL_PROVIDERS_KEY, ""))
        assertEquals(emptySet(), store.current().socialProviderIds)
    }

    @Test
    fun iosAppleStaysHiddenUntilHostAndBackendGateOrLocalOverrideAdmitsIt() {
        val store = SharedAuthRuntimeCapabilityStore()
        store.seedFromAuthPageData(JSONObject().put(AUTH_SOCIAL_PROVIDERS_KEY, "google,github,apple"))

        // Fail-closed before any backend probe: Apple stays hidden even though the
        // host advertises it; Google/GitHub stay host-driven.
        assertEquals(
            setOf(AuthProvider.Google, AuthProvider.GitHub),
            store.current().socialAuthProviders(applePlatformSupported = true)
        )

        // A backend probe without Apple keeps it hidden.
        store.setBackendEnabledSocialProviders(setOf("google", "github"))
        assertEquals(
            setOf(AuthProvider.Google, AuthProvider.GitHub),
            store.current().socialAuthProviders(applePlatformSupported = true)
        )

        // Backend platform gate opens Apple.
        store.setBackendEnabledSocialProviders(setOf("google", "github", "apple"))
        assertEquals(
            setOf(AuthProvider.Google, AuthProvider.GitHub, AuthProvider.Apple),
            store.current().socialAuthProviders(applePlatformSupported = true)
        )

        // A later probe narrowing the set closes Apple again (flag rolled back).
        store.setBackendEnabledSocialProviders(setOf("google", "github"))
        assertEquals(
            setOf(AuthProvider.Google, AuthProvider.GitHub),
            store.current().socialAuthProviders(applePlatformSupported = true)
        )

        // Backend rollout cannot invent Apple when the host did not advertise it.
        store.seedFromAuthPageData(JSONObject().put(AUTH_SOCIAL_PROVIDERS_KEY, "google,github"))
        store.setBackendEnabledSocialProviders(setOf("google", "github", "apple"))
        assertEquals(
            setOf(AuthProvider.Google, AuthProvider.GitHub),
            store.current().socialAuthProviders(applePlatformSupported = true)
        )

        // The signed-out diagnostics override forces Apple locally without any backend signal.
        store.setLocalAppleSignInOverride(true)
        assertEquals(
            setOf(AuthProvider.Google, AuthProvider.GitHub, AuthProvider.Apple),
            store.current().socialAuthProviders(applePlatformSupported = true)
        )
        store.setLocalAppleSignInOverride(false)
        assertEquals(
            setOf(AuthProvider.Google, AuthProvider.GitHub),
            store.current().socialAuthProviders(applePlatformSupported = true)
        )
    }

    @Test
    fun nonIosPlatformsNeverExposeAppleFromBackendOrDiagnosticsOverride() {
        val store = SharedAuthRuntimeCapabilityStore()
        store.seedFromAuthPageData(JSONObject().put(AUTH_SOCIAL_PROVIDERS_KEY, "google,github,apple"))

        // Exact reported path: backend mobile rollout is off, but a stale/local
        // diagnostics override must not make Apple appear on Android or OHOS.
        store.setBackendEnabledSocialProviders(setOf("google", "github"))
        store.setLocalAppleSignInOverride(true)
        assertEquals(
            setOf(AuthProvider.Google, AuthProvider.GitHub),
            store.current().socialAuthProviders(applePlatformSupported = false)
        )

        // A backend rollout cannot bypass the host platform gate either.
        store.setLocalAppleSignInOverride(false)
        store.setBackendEnabledSocialProviders(setOf("google", "github", "apple"))
        assertEquals(
            setOf(AuthProvider.Google, AuthProvider.GitHub),
            store.current().socialAuthProviders(applePlatformSupported = false)
        )
    }

    @Test
    fun localAppleOverrideSurvivesHostSeedsAndDoesNotDuplicate() {
        val store = SharedAuthRuntimeCapabilityStore()
        store.seedFromAuthPageData(JSONObject().put(AUTH_SOCIAL_PROVIDERS_KEY, "google,github"))

        store.setLocalAppleSignInOverride(true)
        assertTrue(store.current().localAppleSignInOverride)
        assertEquals(
            setOf(AuthProvider.Google, AuthProvider.GitHub, AuthProvider.Apple),
            store.current().socialAuthProviders(applePlatformSupported = true)
        )
        // Host seeds must not clobber the local diagnostics toggle.
        store.seedFromAuthPageData(JSONObject().put(AUTH_SOCIAL_PROVIDERS_KEY, "google"))
        assertEquals(setOf("google"), store.current().socialProviderIds)
        assertEquals(
            setOf(AuthProvider.Google, AuthProvider.Apple),
            store.current().socialAuthProviders(applePlatformSupported = true)
        )

        store.setLocalAppleSignInOverride(false)
        assertEquals(
            setOf(AuthProvider.Google),
            store.current().socialAuthProviders(applePlatformSupported = true)
        )

        store.seedFromAuthPageData(JSONObject().put(AUTH_SOCIAL_PROVIDERS_KEY, "google,github,apple"))
        store.setLocalAppleSignInOverride(true)
        assertEquals(
            setOf(AuthProvider.Google, AuthProvider.GitHub, AuthProvider.Apple),
            store.current().socialAuthProviders(applePlatformSupported = true)
        )
    }
}
