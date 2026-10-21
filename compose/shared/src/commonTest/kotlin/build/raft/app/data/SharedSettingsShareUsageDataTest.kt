package build.raft.app.data

import build.raft.app.data.SharedSettingsStoreTest.BlockingCall
import build.raft.app.data.SharedSettingsStoreTest.FakeSettingsRepository
import build.raft.app.englishErrorPresentationStringsForTest
import build.raft.app.toAccountPayload
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Settings › Account "Share usage data": gate, null → on, optimistic save with rollback, consent invalidation. */
class SharedSettingsShareUsageDataTest {
    @Test
    fun notChosenShowsTheSwitchOnAndTheFlagHidesIt() = runTest {
        for (flagOn in listOf(true, false)) {
            val harness = harness(choice = null, flagOn = flagOn)
            harness.store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Account))

            val payload = harness.payload()
            assertEquals(flagOn, payload.usageDataVisible, "row follows product_analytics_settings_v0 (flagOn=$flagOn)")
            if (flagOn) {
                assertNull(harness.usageData()?.choice, "the store keeps the server's 'not chosen'")
                assertTrue(payload.usageDataSharing, "not chosen is shown as on")
            } else {
                assertNull(harness.usageData())
            }
        }
    }

    @Test
    fun toggleIsOptimisticAndInvalidatesConsentOnSuccess() = runTest {
        val harness = harness(choice = null)
        harness.store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Account))
        val block = BlockingCall().also { harness.repository.block = it }
        harness.repository.result = DataResult.Content(AccountUsageDataSnapshot(choice = false))

        harness.store.dispatch(SharedSettingsIntent.SetShareUsageData(false))
        block.awaitStarted()

        assertEquals(false, harness.usageData()?.choice, "the switch moves before the server answers")
        assertTrue(harness.payload().usageDataSaving, "the switch is disabled while saving")
        harness.store.dispatch(SharedSettingsIntent.SetShareUsageData(true))
        testScheduler.runCurrent()
        assertEquals(listOf(false), harness.repository.saves, "one save at a time")
        assertEquals(0, harness.consentInvalidations, "consent is kept until the server accepts")

        block.release()
        testScheduler.runCurrent()

        assertEquals(false, harness.usageData()?.choice)
        assertFalse(harness.payload().usageDataSaving)
        assertNull(harness.store.state.value.effect)
        assertEquals(1, harness.consentInvalidations, "product-events consent is asked again after the change")
    }

    @Test
    fun failedSaveRollsBackAndAsksForTheToast() = runTest {
        val harness = harness(choice = true)
        harness.store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Account))
        val block = BlockingCall().also { harness.repository.block = it }
        harness.repository.result = DataResult.Error(ScreenError("settings_network", retryable = true))

        harness.store.dispatch(SharedSettingsIntent.SetShareUsageData(false))
        block.awaitStarted()
        assertEquals(false, harness.usageData()?.choice)

        block.release()
        testScheduler.runCurrent()

        assertEquals(true, harness.usageData()?.choice, "the previous choice is back")
        assertEquals(SharedSettingsEffect.ShareUsageDataSaveFailed, harness.store.state.value.effect)
        assertEquals("settings_network", harness.store.state.value.actionState?.errorCode)
        assertEquals(0, harness.consentInvalidations)
    }

    @Test
    fun tapMatchingTheShownValueSavesNothing() = runTest {
        val harness = harness(choice = null)
        harness.store.accept(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Account))

        harness.store.dispatch(SharedSettingsIntent.SetShareUsageData(true))
        testScheduler.runCurrent()

        assertEquals(emptyList<Boolean>(), harness.repository.saves, "null already shows on")
    }

    @Test
    fun learnMoreOpensThePrivacyPolicy() = runTest {
        val harness = harness(choice = null)

        harness.store.accept(SharedSettingsIntent.OpenShareUsageDataPrivacy)

        assertEquals(SharedSettingsEffect.OpenUrl("https://raft.build/privacy"), harness.store.state.value.effect)
    }

    private class ShareUsageDataRepository(
        private val delegate: FakeSettingsRepository
    ) : SettingsRepository by delegate {
        var block: BlockingCall? = null
        var result: DataResult<AccountUsageDataSnapshot> = DataResult.Empty(EmptyReason.NoSettingsAvailable)
        val saves = mutableListOf<Boolean>()

        override suspend fun updateShareUsageData(enabled: Boolean): DataResult<AccountUsageDataSnapshot> {
            saves += enabled
            block?.consume()
            return result
        }
    }

    private class Harness(
        val store: SharedSettingsStore,
        val repository: ShareUsageDataRepository,
        private val invalidations: () -> Int,
    ) {
        val consentInvalidations: Int get() = invalidations()

        fun usageData(): AccountUsageDataSnapshot? =
            assertIs<ScreenLoadState.Content<AccountSettingsSnapshot>>(store.state.value.account).data.usageData

        fun payload() =
            store.state.value.account.toAccountPayload(
                session = null,
                actionState = store.state.value.actionState,
                errorStrings = englishErrorPresentationStringsForTest()
            )
    }

    private fun TestScope.harness(choice: Boolean?, flagOn: Boolean = true): Harness {
        val repository =
            ShareUsageDataRepository(
                FakeSettingsRepository(accountResult = DataResult.Content(accountSnapshot(choice)))
            )
        var invalidations = 0
        val store =
            SharedSettingsStore(
                dataSource =
                    SharedSettingsDataSource(
                        sessionStore = InMemoryRaftAuthSessionStore(settingsStoreSampleSession()),
                        settingsRepository = repository,
                        shareUsageDataSettingEnabled = { flagOn },
                        onShareUsageDataChanged = { invalidations += 1 }
                    ),
                scope = backgroundScope
            )
        return Harness(store, repository) { invalidations }
    }

    private fun accountSnapshot(choice: Boolean?): AccountSettingsSnapshot =
        AccountSettingsSnapshot(
            profile =
                AccountProfileSnapshot(
                    userId = "user-1",
                    displayName = "Slock User",
                    handle = "@slock",
                    email = "user@slock.ai",
                    avatarLabel = "SU",
                    verified = true
                ),
            connectedAccounts = emptyList(),
            usageData = AccountUsageDataSnapshot(choice = choice)
        )
}
