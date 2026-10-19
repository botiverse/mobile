package build.raft.app

import build.raft.app.data.AppLocale
import build.raft.app.data.KeyValueLocalePreferenceStore
import build.raft.app.data.stringDescLocaleType
import build.raft.app.data.slockSharedPreferenceStore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Pager
import com.tencent.tmm.kmmresource.resource.desc.StringDesc
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-local projection of the persisted app language.
 *
 * Settings effects may run off the UI thread on Kotlin/Native. Publishing the
 * selected locale here makes every page root re-apply [StringDesc.localeType]
 * from its Compose thread instead of relying on a cross-thread global write.
 * Persistence remains the source of truth; this flow only invalidates the
 * active composition immediately after a successful selection.
 */
internal object KmmResourceLocaleRuntime {
    private val mutableLocale = MutableStateFlow<AppLocale?>(null)

    val locale: StateFlow<AppLocale?> = mutableLocale

    fun publish(locale: AppLocale) {
        mutableLocale.value = locale
    }

    fun seedIfEmpty(locale: AppLocale): AppLocale {
        mutableLocale.compareAndSet(expect = null, update = locale)
        return requireNotNull(mutableLocale.value)
    }

    internal fun replaceForTest(locale: AppLocale?) {
        mutableLocale.value = locale
    }
}

/**
 * task #190: platform hook invoked every time the app shell (re)applies the
 * active locale. Android/iOS resolve strings against the process-local
 * [StringDesc.localeType] directly and need no extra signal. OHOS forwards
 * the locale tag to the ets host so `resourceManager` lookups can run
 * through an override configuration; without it the host always resolves
 * against the system locale.
 *
 * Implementations must tolerate being called before the host side is ready
 * and must be cheap on repeat calls with an unchanged locale (the shell
 * re-runs the seed on every foreground tick).
 */
internal expect fun applyAppLocaleOverride(locale: AppLocale)

/**
 * #85 follow-up: seed the KmmResource `StringDesc.localeType` global
 * from the persisted `AppLocale` as early as possible in the app
 * lifecycle. The right place is the @Page entry
 * [ComposeSlockAppShellPage] -> [SlockAppShellContent], not a
 * Settings-specific data source. Otherwise the global would only
 * be seeded when the user visits Settings, and shared `StringDesc`
 * lookups would render with the runtime's default `LocaleType.System`
 * (which is the OS locale) instead of the user's persisted choice.
 *
 * The visibility tick deliberately re-runs the assignment when the app
 * returns to the foreground. `LocaleType.System` resolves the platform's
 * current locale at that point, while explicit app-language choices remain
 * pinned to their custom locale.
 */
@Composable
internal fun rememberKmmResourceLocaleSeed(pager: Pager, refreshTick: Int = 0) {
    val persisted =
        remember(pager, refreshTick) {
            val store = KeyValueLocalePreferenceStore(slockSharedPreferenceStore())
            val locale: AppLocale =
                try {
                    store.readLocaleSync()
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    // Unreadable state must not override the platform locale.
                    AppLocale.System
                }
            KmmResourceLocaleRuntime.seedIfEmpty(locale)
        }
    val runtimeLocale by KmmResourceLocaleRuntime.locale.collectAsState()
    val activeLocale = runtimeLocale ?: persisted
    StringDesc.localeType = activeLocale.stringDescLocaleType
    applyAppLocaleOverride(activeLocale)
}

/**
 * Convenience: same as [rememberKmmResourceLocaleSeed] but takes the
 * [Pager] from the current [LocalActivity]. Call this from the
 * top of [SlockAppShellContent] so subsequent `StringDesc` lookups
 * in the same composition tree resolve against the user's persisted locale.
 */
@Composable
internal fun RememberKmmResourceLocaleSeedFromLocalActivity(refreshTick: Int = 0) {
    val activity = LocalActivity.current
    val pager = activity.getPager() as Pager
    rememberKmmResourceLocaleSeed(pager, refreshTick)
}
