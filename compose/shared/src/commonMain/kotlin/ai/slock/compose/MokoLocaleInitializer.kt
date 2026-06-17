package ai.slock.compose

import ai.slock.compose.data.AppLocale
import ai.slock.compose.data.KeyValueLocalePreferenceStore
import ai.slock.compose.data.kuiklySharedPreferenceStore
import ai.slock.compose.data.mokoLocaleTag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.core.pager.Pager
import dev.icerock.moko.resources.desc.StringDesc

/**
 * #85 follow-up: seed the moko KBA `StringDesc.localeType` global
 * from the persisted `AppLocale` as early as possible in the app
 * lifecycle. The right place is the @Page entry
 * [ComposeSlockAppShellPage] -> [SlockAppShellContent], not a
 * Settings-specific data source. Otherwise the global would only
 * be seeded when the user visits Settings, and `MR.strings.<key>.
 * desc()` calls in other pages would render with moko's default
 * `LocaleType.System` (which is the OS locale) instead of the
 * user's persisted choice.
 *
 * We guard the init with a [remember] keyed on the [Pager] so it
 * runs exactly once per app shell instance and survives
 * recomposition. The moko global is a process-wide singleton; even
 * if [SlockAppShellContent] were re-entered (process restart,
 * logout/login), the idempotent `StringDesc.localeType = ...` set
 * is cheap and safe.
 */
@Composable
internal fun rememberMokoLocaleSeed(pager: Pager) {
    remember(pager) {
        val store = KeyValueLocalePreferenceStore(kuiklySharedPreferenceStore(pager))
        val persisted: AppLocale =
            try {
                store.readLocaleSync()
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // If the persisted read fails (corrupted KV, missing
                // platform, etc.) fall back to `AppLocale.Default`
                // (English) and let the user re-select via Settings.
                AppLocale.Default
            }
        StringDesc.localeType = StringDesc.LocaleType.Custom(persisted.mokoLocaleTag)
        Unit
    }
}

/**
 * Convenience: same as [rememberMokoLocaleSeed] but takes the
 * [Pager] from the current [LocalActivity]. Call this from the
 * top of [SlockAppShellContent] so any subsequent `MR.strings.<key>.
 * desc()` in the same composition tree resolves against the
 * user's persisted locale.
 */
@Composable
internal fun RememberMokoLocaleSeedFromLocalActivity() {
    val activity = LocalActivity.current
    val pager = activity.getPager() as Pager
    rememberMokoLocaleSeed(pager)
}
