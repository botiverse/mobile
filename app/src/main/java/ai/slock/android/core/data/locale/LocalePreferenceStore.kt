package ai.slock.android.core.data.locale

import android.content.Context

interface LocalePreferenceStore {
    fun readLocaleSync(): AppLocale
    suspend fun readLocale(): AppLocale
    suspend fun saveLocale(locale: AppLocale)
}

class SharedPreferencesLocalePreferenceStore(
    context: Context
) : LocalePreferenceStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    override fun readLocaleSync(): AppLocale =
        AppLocale.fromStorageKey(preferences.getString(KEY_LOCALE, null))

    override suspend fun readLocale(): AppLocale = readLocaleSync()

    override suspend fun saveLocale(locale: AppLocale) {
        preferences.edit()
            .putString(KEY_LOCALE, locale.storageKey)
            .apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "slock_locale_preferences"
        private const val KEY_LOCALE = "app_locale"
    }
}
