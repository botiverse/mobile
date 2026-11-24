package build.raft.app

import build.raft.app.data.AppLocale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-local projection of the persisted app language.
 *
 * Settings effects may run off the UI thread on Kotlin/Native. Publishing the
 * selected locale here makes every page root re-apply [build.raft.app.resources.StringDesc.localeType]
 * from its Compose thread instead of relying on a cross-thread global write.
 * Persistence remains the source of truth; this flow only invalidates the
 * active composition immediately after a successful selection.
 */
@RaftCoreInternalApi object KmmResourceLocaleRuntime {
    private val mutableLocale = MutableStateFlow<AppLocale?>(null)

    val locale: StateFlow<AppLocale?> = mutableLocale

    fun publish(locale: AppLocale) {
        mutableLocale.value = locale
    }

    fun seedIfEmpty(locale: AppLocale): AppLocale {
        mutableLocale.compareAndSet(expect = null, update = locale)
        return requireNotNull(mutableLocale.value)
    }

    @RaftCoreInternalApi fun replaceForTest(locale: AppLocale?) {
        mutableLocale.value = locale
    }
}
