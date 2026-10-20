package build.raft.app

import build.raft.app.data.SharedAuthRuntimeCapabilityStore
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.compat.json.JSONObject
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
    fun iosAppleIsAvailableBeforeHostSeedOrProviderProbe() {
        val store = SharedAuthRuntimeCapabilityStore()
        assertEquals(setOf(AuthProvider.Apple), store.current().socialAuthProviders(true))

        store.seedFromAuthPageData(loginPageData(replaceCurrent = true))
        assertEquals(setOf(AuthProvider.Apple), store.current().socialAuthProviders(true))
    }

    @Test
    fun appleAvailabilityDependsOnlyOnPlatformAcrossLegacyConfigurationStates() {
        for (host in listOf("", "google,github", "google,github,apple")) {
            for (backend in listOf(null, emptySet(), setOf("google", "github"), setOf("apple"))) {
                for (override in listOf(false, true)) {
                    val store = SharedAuthRuntimeCapabilityStore()
                    store.seedSocialProviders(host)
                    if (backend != null) store.setBackendEnabledSocialProviders(backend)
                    store.setLocalAppleSignInOverride(override)
                    val others = if (host.isEmpty()) emptySet() else setOf(AuthProvider.Google, AuthProvider.GitHub)
                    val context = "host=$host backend=$backend override=$override"
                    assertEquals(others + AuthProvider.Apple, store.current().socialAuthProviders(true), context)
                    assertEquals(others, store.current().socialAuthProviders(false), context)
                }
            }
        }
    }

    @Test
    fun hostRefreshAndLogoutCannotRemoveIosApple() {
        val store = SharedAuthRuntimeCapabilityStore()
        store.seedSocialProviders("google,github,apple")
        store.setBackendEnabledSocialProviders(setOf("apple"))
        store.setLocalAppleSignInOverride(true)

        store.setBackendEnabledSocialProviders(emptySet())
        store.setLocalAppleSignInOverride(false)
        store.seedSocialProviders("google")
        store.seedFromAuthPageData(loginPageData(replaceCurrent = true))
        assertEquals(setOf(AuthProvider.Google, AuthProvider.Apple), store.current().socialAuthProviders(true))

        store.seedSocialProviders("")
        assertEquals(setOf(AuthProvider.Apple), store.current().socialAuthProviders(true))
        assertEquals(emptySet(), store.current().socialAuthProviders(false))
    }
}
