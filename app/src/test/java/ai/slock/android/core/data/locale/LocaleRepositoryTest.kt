package ai.slock.android.core.data.locale

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocaleRepositoryTest {
    @Test
    fun defaultsToEnglishWhenNoPreferenceExists() {
        val repository = PersistentLocaleRepository(InMemoryLocalePreferenceStore())

        val snapshot = runSuspend { repository.getLocale() }

        assertEquals(AppLocale.English, snapshot.current)
        assertTrue(snapshot.supported.contains(AppLocale.SimplifiedChinese))
    }

    @Test
    fun persistsSelectedLocale() {
        val store = InMemoryLocalePreferenceStore()
        val repository = PersistentLocaleRepository(store)

        val selected = runSuspend { repository.selectLocale(AppLocale.SimplifiedChinese) }
        val restored = runSuspend { repository.getLocale() }

        assertEquals(AppLocale.SimplifiedChinese, selected.current)
        assertEquals(AppLocale.SimplifiedChinese, restored.current)
    }

    private class InMemoryLocalePreferenceStore(
        private var locale: AppLocale = AppLocale.Default
    ) : LocalePreferenceStore {
        override fun readLocaleSync(): AppLocale = locale

        override suspend fun readLocale(): AppLocale = locale

        override suspend fun saveLocale(locale: AppLocale) {
            this.locale = locale
        }
    }
}

private fun <T> runSuspend(block: suspend () -> T): T {
    var value: T? = null
    var failure: Throwable? = null
    block.startCoroutine(
        object : Continuation<T> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<T>) {
                result.onSuccess { value = it }
                result.onFailure { failure = it }
            }
        }
    )
    failure?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return value as T
}
