package build.raft.app.data

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Settings › Account › Apps you signed in to with Raft. Bodies are the server's, botiverse/slock
 * staging 33f441958f82ce0178cc7f48232d41b20907e50c: routes/me.ts GET / DELETE /api/me/app-logins
 * (routes/oauthAppLoginRevoke.api.test.ts: camelCase, newest first, 204 on revoke).
 */
class AppLoginsTest {
    private val body =
        """[
            {"id":"consent-2","clientId":"rft_app_b","name":"Notes","logoUrl":null,"scopes":["profile"],"grantedAt":"2026-11-10T08:00:00.000Z"},
            {"id":"consent-1","clientId":"rft_app_a","name":"Calendar","logoUrl":"https://cdn.example/a.png","scopes":["profile","email"],"grantedAt":"2026-09-01T08:00:00.000Z"}
        ]"""

    @Test
    fun parsesTheListInItsOrder() {
        val logins = parseSharedAppLogins(body)!!
        assertEquals(listOf("rft_app_b", "rft_app_a"), logins.map { it.clientId })
        assertNull(logins[0].logoUrl)
        assertEquals("https://cdn.example/a.png", logins[1].logoUrl)
        assertEquals(listOf(AppLoginKind.SignIn, AppLoginKind.SignIn), logins.map { it.kind })
        assertEquals(emptyList(), parseSharedAppLogins("[]"))
        assertNull(parseSharedAppLogins("""{"logins":[]}"""), "a wrapped body is not the list")
    }

    @Test
    fun parsesCardKindAndDefaultsMissingOrUnknownKindToSignIn() {
        val mixed =
            """[
                {"id":"card-1","clientId":"rft_app_card","name":"Approvals","logoUrl":null,"scopes":[],"grantedAt":"2026-11-20T08:00:00.000Z","kind":"card"},
                {"id":"signin-1","clientId":"rft_app_signin","name":"Notes","logoUrl":null,"scopes":["profile"],"grantedAt":"2026-11-10T08:00:00.000Z","kind":"sign_in"},
                {"id":"legacy-1","clientId":"rft_app_legacy","name":"Calendar","logoUrl":null,"scopes":["profile"],"grantedAt":"2026-09-01T08:00:00.000Z"},
                {"id":"future-1","clientId":"rft_app_future","name":"Future","logoUrl":null,"scopes":[],"grantedAt":"2026-08-01T08:00:00.000Z","kind":"future_kind"}
            ]"""
        val logins = parseSharedAppLogins(mixed)!!
        assertEquals(
            listOf(AppLoginKind.Card, AppLoginKind.SignIn, AppLoginKind.SignIn, AppLoginKind.SignIn),
            logins.map { it.kind },
        )
    }

    @Test
    fun revokeRemovesTheRowAndAFailureKeepsTheDialog() = runTest {
        val network =
            ScriptedAgentControlsNetworkClient().apply {
                respond("GET", "me/app-logins", 200, body)
                respond("DELETE", "me/app-logins/rft_app_b", 500, """{"error":"Failed to revoke app login"}""")
                respond("DELETE", "me/app-logins/rft_app_b", 204, "")
            }
        val store = SharedAppLoginsStore(NetworkAppLoginsDataSource(network), this)
        store.dispatch(SharedAppLoginsIntent.Refresh)
        runCurrent()
        val notes = store.state.value.logins.first()

        store.dispatch(SharedAppLoginsIntent.OpenRevoke(notes))
        store.dispatch(SharedAppLoginsIntent.ConfirmRevoke)
        runCurrent()
        assertTrue(store.state.value.revokeFailed)
        assertEquals(notes, store.state.value.pending)
        assertEquals(2, store.state.value.logins.size)

        store.dispatch(SharedAppLoginsIntent.ConfirmRevoke)
        runCurrent()
        assertNull(store.state.value.pending)
        assertFalse(store.state.value.revokeFailed)
        assertEquals(notes, store.state.value.revoked)
        assertEquals(listOf("rft_app_a"), store.state.value.logins.map { it.clientId })
    }

    @Test
    fun aFailedReadShowsNothing() = runTest {
        val store =
            SharedAppLoginsStore(
                NetworkAppLoginsDataSource(ScriptedAgentControlsNetworkClient().apply { respond("GET", "me/app-logins", 500, "{}") }),
                this
            )
        store.dispatch(SharedAppLoginsIntent.Refresh)
        runCurrent()
        assertEquals(emptyList(), store.state.value.logins)
    }
}
