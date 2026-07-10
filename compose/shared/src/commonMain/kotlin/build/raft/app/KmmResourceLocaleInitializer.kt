package build.raft.app

import build.raft.app.data.AppLocale
import build.raft.app.data.KeyValueLocalePreferenceStore
import build.raft.app.data.stringDescLocaleType
import build.raft.app.data.slockSharedPreferenceStore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.core.pager.Pager
import com.tencent.tmm.kmmresource.resource.desc.StringDesc

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
    remember(pager, refreshTick) {
        val store = KeyValueLocalePreferenceStore(slockSharedPreferenceStore())
        val persisted: AppLocale =
            try {
                store.readLocaleSync()
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // Missing or unreadable state falls back to the app default.
                AppLocale.Default
            }
        StringDesc.localeType = persisted.stringDescLocaleType
        Unit
    }
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
