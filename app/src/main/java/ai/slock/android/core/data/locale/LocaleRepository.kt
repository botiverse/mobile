package ai.slock.android.core.data.locale

data class LocaleSnapshot(
    val current: AppLocale,
    val supported: List<AppLocale> = AppLocale.entries
)

interface LocaleRepository {
    suspend fun getLocale(): LocaleSnapshot
    suspend fun selectLocale(locale: AppLocale): LocaleSnapshot
}

class PersistentLocaleRepository(
    private val store: LocalePreferenceStore
) : LocaleRepository {
    override suspend fun getLocale(): LocaleSnapshot =
        LocaleSnapshot(current = store.readLocale())

    override suspend fun selectLocale(locale: AppLocale): LocaleSnapshot {
        store.saveLocale(locale)
        return LocaleSnapshot(current = locale)
    }
}
