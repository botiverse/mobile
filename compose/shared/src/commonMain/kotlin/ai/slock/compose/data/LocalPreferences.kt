package ai.slock.compose.data

enum class AppLocale(
    val storageKey: String,
    val languageTag: String,
    val englishLabel: String,
    val nativeLabel: String
) {
    English(
        storageKey = "en",
        languageTag = "en",
        englishLabel = "English",
        nativeLabel = "English"
    ),
    SimplifiedChinese(
        storageKey = "zh-Hans",
        languageTag = "zh-Hans",
        englishLabel = "Simplified Chinese",
        nativeLabel = "简体中文"
    );

    companion object {
        val Default = English

        fun fromStorageKey(value: String?): AppLocale =
            entries.firstOrNull { it.storageKey == value } ?: Default
    }
}

/**
 * #85 follow-up: BCP-47 tag used by moko KBA's
 * `StringDesc.LocaleType.Custom(tag)` to pick a resource folder at
 * runtime. The `moko-resources/<lang>-<region>/` folder parser expects
 * BCP-47 with `r` separator, e.g. `zh-rCN`. We use the same tags
 * moko uses for `moko:resources-ohosarm64` and `moko:resources-android`
 * (i.e. the existing folder names) so the runtime lookup lands on the
 * actual generated folders.
 *
 * English falls through to the `base/` folder; Simplified Chinese
 * maps to the `zh-rCN/` folder (region-based; moko's BCP-47 matching
 * falls back from `zh-Hans` script-only to `zh-rCN` region-only by
 * language match).
 */
val AppLocale.mokoLocaleTag: String
    get() =
        when (this) {
            AppLocale.English -> "en"
            AppLocale.SimplifiedChinese -> "zh-CN"
        }

data class LocaleSnapshot(
    val current: AppLocale,
    val supported: List<AppLocale> = AppLocale.entries
)

interface LocalePreferenceStore {
    fun readLocaleSync(): AppLocale
    suspend fun readLocale(): AppLocale
    suspend fun saveLocale(locale: AppLocale)
}

class KeyValueLocalePreferenceStore(
    private val store: SharedPreferenceStore
) : LocalePreferenceStore {
    override fun readLocaleSync(): AppLocale =
        AppLocale.fromStorageKey(store.getString(KEY_LOCALE))

    override suspend fun readLocale(): AppLocale = readLocaleSync()

    override suspend fun saveLocale(locale: AppLocale) {
        store.putString(KEY_LOCALE, locale.storageKey)
    }

    private companion object {
        const val KEY_LOCALE = "locale:app_locale"
    }
}

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

enum class MyMessageAlignmentPreference(val storageKey: String) {
    Left("left"),
    Right("right");

    companion object {
        val Default = Left

        fun fromStorageKey(value: String?): MyMessageAlignmentPreference =
            entries.firstOrNull { it.storageKey == value } ?: Default
    }
}

enum class LargeScreenNavigationPreference(val storageKey: String) {
    LeftRail("left_rail"),
    FloatingDock("floating_dock");

    companion object {
        val Default = LeftRail

        fun fromStorageKey(value: String?): LargeScreenNavigationPreference =
            entries.firstOrNull { it.storageKey == value } ?: Default
    }
}

data class ChatLayoutPreferenceSnapshot(
    val myMessageAlignment: MyMessageAlignmentPreference,
    val supportedAlignments: List<MyMessageAlignmentPreference> =
        MyMessageAlignmentPreference.entries,
    val largeScreenNavigation: LargeScreenNavigationPreference =
        LargeScreenNavigationPreference.Default,
    val supportedLargeScreenNavigation: List<LargeScreenNavigationPreference> =
        LargeScreenNavigationPreference.entries
)

interface ChatLayoutPreferenceStore {
    fun readMyMessageAlignmentSync(): MyMessageAlignmentPreference
    suspend fun readMyMessageAlignment(): MyMessageAlignmentPreference
    suspend fun saveMyMessageAlignment(alignment: MyMessageAlignmentPreference)
    fun readLargeScreenNavigationSync(): LargeScreenNavigationPreference
    suspend fun readLargeScreenNavigation(): LargeScreenNavigationPreference
    suspend fun saveLargeScreenNavigation(navigation: LargeScreenNavigationPreference)
}

class KeyValueChatLayoutPreferenceStore(
    private val store: SharedPreferenceStore
) : ChatLayoutPreferenceStore {
    override fun readMyMessageAlignmentSync(): MyMessageAlignmentPreference =
        MyMessageAlignmentPreference.fromStorageKey(
            store.getString(KEY_MY_MESSAGE_ALIGNMENT)
        )

    override suspend fun readMyMessageAlignment(): MyMessageAlignmentPreference =
        readMyMessageAlignmentSync()

    override suspend fun saveMyMessageAlignment(alignment: MyMessageAlignmentPreference) {
        store.putString(KEY_MY_MESSAGE_ALIGNMENT, alignment.storageKey)
    }

    override fun readLargeScreenNavigationSync(): LargeScreenNavigationPreference =
        LargeScreenNavigationPreference.fromStorageKey(
            store.getString(KEY_LARGE_SCREEN_NAVIGATION)
        )

    override suspend fun readLargeScreenNavigation(): LargeScreenNavigationPreference =
        readLargeScreenNavigationSync()

    override suspend fun saveLargeScreenNavigation(navigation: LargeScreenNavigationPreference) {
        store.putString(KEY_LARGE_SCREEN_NAVIGATION, navigation.storageKey)
    }

    private companion object {
        const val KEY_MY_MESSAGE_ALIGNMENT = "chatLayout:my_message_alignment"
        const val KEY_LARGE_SCREEN_NAVIGATION = "chatLayout:large_screen_navigation"
    }
}

interface ChatLayoutPreferenceRepository {
    suspend fun getChatLayoutPreference(): ChatLayoutPreferenceSnapshot
    suspend fun selectMyMessageAlignment(
        alignment: MyMessageAlignmentPreference
    ): ChatLayoutPreferenceSnapshot
    suspend fun selectLargeScreenNavigation(
        navigation: LargeScreenNavigationPreference
    ): ChatLayoutPreferenceSnapshot
}

class PersistentChatLayoutPreferenceRepository(
    private val store: ChatLayoutPreferenceStore
) : ChatLayoutPreferenceRepository {
    override suspend fun getChatLayoutPreference(): ChatLayoutPreferenceSnapshot =
        ChatLayoutPreferenceSnapshot(
            myMessageAlignment = store.readMyMessageAlignment(),
            largeScreenNavigation = store.readLargeScreenNavigation()
        )

    override suspend fun selectMyMessageAlignment(
        alignment: MyMessageAlignmentPreference
    ): ChatLayoutPreferenceSnapshot {
        store.saveMyMessageAlignment(alignment)
        return ChatLayoutPreferenceSnapshot(
            myMessageAlignment = alignment,
            largeScreenNavigation = store.readLargeScreenNavigation()
        )
    }

    override suspend fun selectLargeScreenNavigation(
        navigation: LargeScreenNavigationPreference
    ): ChatLayoutPreferenceSnapshot {
        store.saveLargeScreenNavigation(navigation)
        return ChatLayoutPreferenceSnapshot(
            myMessageAlignment = store.readMyMessageAlignment(),
            largeScreenNavigation = navigation
        )
    }
}

interface TasksViewPreferenceRepository {
    fun getTasksViewModeSync(defaultMode: TasksViewMode): TasksViewMode
    suspend fun getTasksViewMode(defaultMode: TasksViewMode): TasksViewMode
    fun selectTasksViewModeSync(mode: TasksViewMode): TasksViewMode
    suspend fun selectTasksViewMode(mode: TasksViewMode): TasksViewMode
}

class KeyValueTasksViewPreferenceRepository(
    private val store: SharedPreferenceStore
) : TasksViewPreferenceRepository {
    override fun getTasksViewModeSync(defaultMode: TasksViewMode): TasksViewMode =
        when (store.getString(KEY_TASKS_VIEW_MODE)) {
            TASKS_VIEW_MODE_BOARD -> TasksViewMode.Board
            TASKS_VIEW_MODE_LIST -> TasksViewMode.List
            else -> defaultMode
        }

    override suspend fun getTasksViewMode(defaultMode: TasksViewMode): TasksViewMode =
        getTasksViewModeSync(defaultMode)

    override fun selectTasksViewModeSync(mode: TasksViewMode): TasksViewMode {
        store.putString(
            KEY_TASKS_VIEW_MODE,
            when (mode) {
                TasksViewMode.Board -> TASKS_VIEW_MODE_BOARD
                TasksViewMode.List -> TASKS_VIEW_MODE_LIST
            }
        )
        return mode
    }

    override suspend fun selectTasksViewMode(mode: TasksViewMode): TasksViewMode =
        selectTasksViewModeSync(mode)

    private companion object {
        const val KEY_TASKS_VIEW_MODE = "tasks:viewMode"
        const val TASKS_VIEW_MODE_BOARD = "board"
        const val TASKS_VIEW_MODE_LIST = "list"
    }
}

class KeyValueRouterPreferenceRepository(
    private val store: SharedPreferenceStore
) {
    fun saveLastRouteInput(input: String) {
        store.putString(KEY_LAST_ROUTE_INPUT, input)
    }

    fun readLastRouteInput(): String? =
        store.getString(KEY_LAST_ROUTE_INPUT)

    private companion object {
        const val KEY_LAST_ROUTE_INPUT = "router:last_input"
    }
}

fun kuiklySharedPreferenceStore(pager: com.tencent.kuikly.core.pager.Pager): SharedPreferenceStore =
    KuiklySharedPreferencesKeyValueStore(
        pager,
        SlockLocalPreferenceStore.KuiklyNamespace
    )
