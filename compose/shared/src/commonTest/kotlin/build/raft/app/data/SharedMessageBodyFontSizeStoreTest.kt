package build.raft.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedMessageBodyFontSizeStoreTest {
    @Test
    fun coldConversationLoadUsesServerPreference() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store =
            SharedMessageBodyFontSizeStore(
                scope = scope,
            )

        store.ensureLoaded("env|user-1") { DataResult.Content("lg".toSettingsAppearanceSnapshot()) }

        assertEquals("lg", store.state.value.selectedKey("env|user-1"))
        assertTrue("env|user-1" in store.state.value.loadedOwners)
        scope.cancel()
    }

    @Test
    fun settingsResponseWinsOverStaleConversationLoad() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val load = CompletableDeferred<DataResult<SettingsAppearanceSnapshot>>()
        val store =
            SharedMessageBodyFontSizeStore(
                scope = scope,
            )

        val loadJob = launch(start = CoroutineStart.UNDISPATCHED) { store.ensureLoaded("env|user-1") { load.await() } }
        store.acceptAuthoritative("env|user-1", "sm".toSettingsAppearanceSnapshot())
        load.complete(DataResult.Content("lg".toSettingsAppearanceSnapshot()))
        loadJob.join()

        assertEquals("sm", store.state.value.selectedKey("env|user-1"))
        assertTrue("env|user-1" in store.state.value.loadedOwners)
        scope.cancel()
    }

    @Test
    fun ownerPartitionsNeverBleedAcrossAccounts() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store =
            SharedMessageBodyFontSizeStore(
                scope = scope,
            )
        store.acceptAuthoritative("env|user-1", "lg".toSettingsAppearanceSnapshot())
        store.acceptAuthoritative("env|user-2", "sm".toSettingsAppearanceSnapshot())

        assertEquals("lg", store.state.value.selectedKey("env|user-1"))
        assertEquals("sm", store.state.value.selectedKey("env|user-2"))
        assertEquals("md", store.state.value.selectedKey("env|unknown"))
        assertFalse("env|unknown" in store.state.value.loadedOwners)
        scope.cancel()
    }

    @Test
    fun failedLoadClearsInflightAndCanRetry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = SharedMessageBodyFontSizeStore(scope = scope)

        store.ensureLoaded("env|user-1") { error("offline") }
        assertFalse("env|user-1" in store.state.value.loadingOwners)
        assertFalse("env|user-1" in store.state.value.loadedOwners)

        store.ensureLoaded("env|user-1") { DataResult.Content("lg".toSettingsAppearanceSnapshot()) }
        assertEquals("lg", store.state.value.selectedKey("env|user-1"))
        assertTrue("env|user-1" in store.state.value.loadedOwners)
        scope.cancel()
    }
}
