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
                .put(AUTH_SOCIAL_PROVIDERS_KEY, "google,github")

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

        assertEquals(setOf("google", "github"), initialCapabilities.socialProviderIds)
        assertEquals(AUTH_CHOOSE_SERVER_PAGE, chooseServer.pageName)
        assertFalse(chooseServer.pageData.has(AUTH_SOCIAL_PROVIDERS_KEY))
        assertFalse(loginAfterLogout.has(AUTH_SOCIAL_PROVIDERS_KEY))
        assertTrue(loginAfterLogout.optBoolean("slockReplaceCurrentPage", false))
        assertEquals(
            setOf("google", "github"),
            store.seedFromAuthPageData(chooseServer.pageData).socialProviderIds
        )
        assertEquals(
            setOf("google", "github"),
            store.seedFromAuthPageData(loginAfterLogout).socialProviderIds
        )
    }

    @Test
    fun hostSeedNormalizesReplacesAndCanExplicitlyClearProviders() {
        val store = SharedAuthRuntimeCapabilityStore()

        store.seedFromAuthPageData(
            JSONObject().put(AUTH_SOCIAL_PROVIDERS_KEY, " GitHub, GOOGLE, github ")
        )
        assertEquals(setOf("github", "google"), store.current().socialProviderIds)

        store.seedFromAuthPageData(JSONObject().put("pageName", LOGIN_PAGE))
        assertEquals(setOf("github", "google"), store.current().socialProviderIds)

        store.seedFromAuthPageData(JSONObject().put(AUTH_SOCIAL_PROVIDERS_KEY, ""))
        assertEquals(emptySet(), store.current().socialProviderIds)
    }
}
