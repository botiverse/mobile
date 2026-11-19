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

    /**
     * iPhone report (1.27): the channel page drew messages at md (16sp) after a cold start and
     * switched to the account's size seconds later, when the appearance settings load returned.
     * The last-known size is now on disk per owner and in the state before the server answers.
     */
    @Test
    fun coldStartDrawsLastKnownSizeBeforeTheServerAnswers() = runBlocking {
        val disk = InMemoryRaftKeyValueStore()
        val firstRun = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        SharedMessageBodyFontSizeStore(scope = firstRun, lastKnown = MessageBodyFontSizeLastKnownStore(disk))
            .ensureLoaded("env|user-1") { DataResult.Content("sm".toSettingsAppearanceSnapshot()) }
        firstRun.cancel()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val load = CompletableDeferred<DataResult<SettingsAppearanceSnapshot>>()
        val store = SharedMessageBodyFontSizeStore(scope = scope, lastKnown = MessageBodyFontSizeLastKnownStore(disk))
        assertEquals("sm", store.state.value.selectedKey("env|user-1"), "first frame after a cold start")

        val loadJob = launch(start = CoroutineStart.UNDISPATCHED) { store.ensureLoaded("env|user-1") { load.await() } }
        assertEquals("sm", store.state.value.selectedKey("env|user-1"), "while the server load is in flight")
        assertFalse("env|user-1" in store.state.value.loadedOwners, "a seeded size still asks the server")

        load.complete(DataResult.Content("lg".toSettingsAppearanceSnapshot()))
        loadJob.join()
        assertEquals("lg", store.state.value.selectedKey("env|user-1"))
        assertEquals("lg", MessageBodyFontSizeLastKnownStore(disk).read("env|user-1"), "the server answer is stored")
        scope.cancel()
    }

    @Test
    fun lastKnownSizeStaysWithItsAccount() = runBlocking {
        val disk = InMemoryRaftKeyValueStore()
        val firstRun = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        SharedMessageBodyFontSizeStore(scope = firstRun, lastKnown = MessageBodyFontSizeLastKnownStore(disk)).apply {
            acceptAuthoritative("env|user-2", "sm".toSettingsAppearanceSnapshot())
            acceptAuthoritative("env|user-1", "lg".toSettingsAppearanceSnapshot())
        }
        firstRun.cancel()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val load = CompletableDeferred<DataResult<SettingsAppearanceSnapshot>>()
        val store = SharedMessageBodyFontSizeStore(scope = scope, lastKnown = MessageBodyFontSizeLastKnownStore(disk))
        assertEquals("lg", store.state.value.selectedKey("env|user-1"), "the last account, from construction")
        assertEquals("md", store.state.value.selectedKey("env|unknown"), "never another account's size")

        val loadJob = launch(start = CoroutineStart.UNDISPATCHED) { store.ensureLoaded("env|user-2") { load.await() } }
        assertEquals("sm", store.state.value.selectedKey("env|user-2"), "another known account, once its load starts")
        load.complete(DataResult.Error(ScreenError(code = "offline", retryable = true)))
        loadJob.join()
        assertEquals("sm", store.state.value.selectedKey("env|user-2"), "a failed load keeps the last-known size")
        scope.cancel()
    }
}
