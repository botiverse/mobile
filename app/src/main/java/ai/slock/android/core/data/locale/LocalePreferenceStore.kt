package ai.slock.android.core.data.locale

import android.content.Context
import ai.slock.android.core.data.preferences.AndroidSharedPreferencesKeyValueStore
import ai.slock.compose.data.SlockKeyValueStore

interface LocalePreferenceStore {
    fun readLocaleSync(): AppLocale
    suspend fun readLocale(): AppLocale
    suspend fun saveLocale(locale: AppLocale)
}

class SharedPreferencesLocalePreferenceStore(
    context: Context
) : LocalePreferenceStore {
    private val store: SlockKeyValueStore =
        AndroidSharedPreferencesKeyValueStore(context, PREFERENCES_NAME)

    override fun readLocaleSync(): AppLocale =
        AppLocale.fromStorageKey(store.getString(KEY_LOCALE))

    override suspend fun readLocale(): AppLocale = readLocaleSync()

    override suspend fun saveLocale(locale: AppLocale) {
        store.putString(KEY_LOCALE, locale.storageKey)
    }

    companion object {
        private const val PREFERENCES_NAME = "slock_locale_preferences"
        private const val KEY_LOCALE = "app_locale"
    }
}
