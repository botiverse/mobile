package build.raft.app

import build.raft.app.data.AccountLoginSecurity
import build.raft.app.data.AccountLoginSecurityDataSource
import build.raft.app.data.DataResult
import build.raft.app.data.ScreenError
import build.raft.app.data.SharedAccountLoginSecurityIntent
import build.raft.app.data.SharedAccountLoginSecurityState
import build.raft.app.data.SharedAccountLoginSecurityStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Settings › Account › Login & security (#proj-mobile:#121): the store behind the rows (status as
 * pinned in AccountLoginSecurityApiTest) and where a row's tap goes.
 */
class SettingsAccountLoginSecurityStoreTest {
    private val on = AccountLoginSecurity(twoStepEnabled = true, passkeyCount = 2)
    private val failure = ScreenError(code = "second_factor_status_http_500", traceId = "0af7651916cd43dd8448eb211c80319c")

    private class FakeLoginSecurityDataSource(var result: DataResult<AccountLoginSecurity?>) : AccountLoginSecurityDataSource {
        var reads = 0
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun loginSecurity(): DataResult<AccountLoginSecurity?> {
            reads += 1
            gate?.await()
            return result
        }
    }

    @Test
    fun aReadShowsTheRowsAndAResumeWhileItRunsAddsNoRequest() = runTest {
        val dataSource = FakeLoginSecurityDataSource(DataResult.Content(on)).apply { gate = CompletableDeferred() }
        val store = SharedAccountLoginSecurityStore(dataSource, backgroundScope)

        store.dispatch(SharedAccountLoginSecurityIntent.Refresh)
        store.dispatch(SharedAccountLoginSecurityIntent.Refresh)
        runCurrent()
        // The first read: nothing shown yet (web: the sections render nothing while loading).
        assertTrue(store.state.value.loading)
        assertFalse(store.state.value.visible)
        dataSource.gate?.complete(Unit)
        runCurrent()

        assertEquals(1, dataSource.reads)
        assertEquals(SharedAccountLoginSecurityState(security = on), store.state.value)
        assertTrue(store.state.value.visible)
    }

    @Test
    fun aReturnFromTheBrowserReadsTheChangedCounts() = runTest {
        val dataSource = FakeLoginSecurityDataSource(DataResult.Content(on))
        val store = SharedAccountLoginSecurityStore(dataSource, backgroundScope)
        store.dispatch(SharedAccountLoginSecurityIntent.Refresh)
        runCurrent()

        val changed = AccountLoginSecurity(twoStepEnabled = false, passkeyCount = 3)
        dataSource.result = DataResult.Content(changed)
        store.dispatch(SharedAccountLoginSecurityIntent.Refresh)
        runCurrent()

        assertEquals(2, dataSource.reads)
        assertEquals(changed, store.state.value.security)
    }

    @Test
    fun aFailedReloadKeepsTheRowsAndAFailedFirstReadShowsTheErrorWithItsTraceId() = runTest {
        val dataSource = FakeLoginSecurityDataSource(DataResult.Error(failure))
        val store = SharedAccountLoginSecurityStore(dataSource, backgroundScope)
        store.dispatch(SharedAccountLoginSecurityIntent.Refresh)
        runCurrent()
        assertNull(store.state.value.security)
        assertEquals(failure.traceId, store.state.value.error?.traceId)
        assertTrue(store.state.value.visible)

        // Retry: the failure card stays (with its spinner) until the answer.
        dataSource.result = DataResult.Content(on)
        dataSource.gate = CompletableDeferred()
        store.dispatch(SharedAccountLoginSecurityIntent.Refresh)
        runCurrent()
        assertTrue(store.state.value.visible)
        assertTrue(store.state.value.loading)
        dataSource.gate?.complete(Unit)
        runCurrent()
        assertEquals(SharedAccountLoginSecurityState(security = on), store.state.value)

        dataSource.result = DataResult.Error(failure)
        store.dispatch(SharedAccountLoginSecurityIntent.Refresh)
        runCurrent()
        assertEquals(on, store.state.value.security)
        assertEquals(failure, store.state.value.error)
    }

    @Test
    fun theFeatureOffForThePersonHidesTheGroup() = runTest {
        val dataSource = FakeLoginSecurityDataSource(DataResult.Content(on))
        val store = SharedAccountLoginSecurityStore(dataSource, backgroundScope)
        store.dispatch(SharedAccountLoginSecurityIntent.Refresh)
        runCurrent()

        dataSource.result = DataResult.Content(null)
        store.dispatch(SharedAccountLoginSecurityIntent.Refresh)
        runCurrent()

        assertTrue(store.state.value.unavailable)
        assertNull(store.state.value.security)
        assertFalse(store.state.value.visible)
    }

    @Test
    fun aRowOpensItsWebSectionOnTheEnvironmentsWebUrlInTheBrowser() {
        val opened = mutableListOf<String>()
        val logs = mutableListOf<String>()
        var slug: String? = "hq"
        val open =
            settingsAccountLoginSecurityOpener(
                webBaseUrl = "https://staging.raft.build/",
                serverSlug = { slug },
                log = { logs += it },
                openInBrowser = { opened += it },
            )

        open(SettingsAccountLoginSecuritySection.TwoStep)
        open(SettingsAccountLoginSecuritySection.Passkeys)
        slug = null
        open(SettingsAccountLoginSecuritySection.Passkeys)

        assertEquals(
            listOf(
                "https://staging.raft.build/s/hq/settings/account#two-step",
                "https://staging.raft.build/s/hq/settings/account#passkeys",
                "https://staging.raft.build/settings/account#passkeys",
            ),
            opened,
        )
        assertEquals("settings_account_login_security_open section=two-step", logs.first())
    }
}
