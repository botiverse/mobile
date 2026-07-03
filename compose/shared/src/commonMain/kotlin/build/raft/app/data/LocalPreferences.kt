package build.raft.app.data

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
 * #85 follow-up: BCP-47 tag used by KuiklyBase KmmResource's
 * `StringDesc.LocaleType.Custom(tag)` to pick a resource folder at
 * runtime. The generated resource folder parser accepts
 * BCP-47 with `r` separator, e.g. `zh-rCN`. We use the same tags
 * as the resource folders so the runtime lookup lands on the
 * actual generated folders.
 *
 * English falls through to the `base/` folder; Simplified Chinese
 * maps to the `zh-rCN/` folder (region-based; the BCP-47 matching
 * falls back from `zh-Hans` script-only to `zh-rCN` region-only by
 * language match).
 */
val AppLocale.kmmResourceLocaleTag: String
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

enum class ThreadRowAffordancePreference(val storageKey: String) {
    Reactions("reactions"),
    Saved("saved");

    companion object {
        val Default: Set<ThreadRowAffordancePreference> = setOf(Reactions)

        fun fromStorageKey(value: String?): ThreadRowAffordancePreference? =
            entries.firstOrNull { it.storageKey == value }

        fun fromStorageKeys(value: Set<String>): Set<ThreadRowAffordancePreference> =
            value.mapNotNull(::fromStorageKey).toSet()
    }
}

data class ChatLayoutPreferenceSnapshot(
    val myMessageAlignment: MyMessageAlignmentPreference,
    val supportedAlignments: List<MyMessageAlignmentPreference> =
        MyMessageAlignmentPreference.entries,
    val largeScreenNavigation: LargeScreenNavigationPreference =
        LargeScreenNavigationPreference.Default,
    val supportedLargeScreenNavigation: List<LargeScreenNavigationPreference> =
        LargeScreenNavigationPreference.entries,
    val compactConsecutiveMessages: Boolean = false,
    val imageAnnotationEnabled: Boolean = false,
    val activityTaskStatusBadgesEnabled: Boolean = false,
    val threadRowAffordances: Set<ThreadRowAffordancePreference> =
        ThreadRowAffordancePreference.Default,
    val supportedThreadRowAffordances: List<ThreadRowAffordancePreference> =
        ThreadRowAffordancePreference.entries
)

interface ChatLayoutPreferenceStore {
    fun readMyMessageAlignmentSync(): MyMessageAlignmentPreference
    suspend fun readMyMessageAlignment(): MyMessageAlignmentPreference
    suspend fun saveMyMessageAlignment(alignment: MyMessageAlignmentPreference)
    fun readLargeScreenNavigationSync(): LargeScreenNavigationPreference
    suspend fun readLargeScreenNavigation(): LargeScreenNavigationPreference
    suspend fun saveLargeScreenNavigation(navigation: LargeScreenNavigationPreference)
    fun readCompactConsecutiveMessagesSync(): Boolean
    suspend fun readCompactConsecutiveMessages(): Boolean
    suspend fun saveCompactConsecutiveMessages(enabled: Boolean)
    fun readImageAnnotationEnabledSync(): Boolean
    suspend fun readImageAnnotationEnabled(): Boolean
    suspend fun saveImageAnnotationEnabled(enabled: Boolean)
    fun readActivityTaskStatusBadgesEnabledSync(): Boolean
    suspend fun readActivityTaskStatusBadgesEnabled(): Boolean
    suspend fun saveActivityTaskStatusBadgesEnabled(enabled: Boolean)
    fun readThreadRowAffordancesSync(): Set<ThreadRowAffordancePreference>
    suspend fun readThreadRowAffordances(): Set<ThreadRowAffordancePreference>
    suspend fun saveThreadRowAffordances(affordances: Set<ThreadRowAffordancePreference>)
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

    override fun readCompactConsecutiveMessagesSync(): Boolean =
        store.getBoolean(KEY_COMPACT_CONSECUTIVE_MESSAGES, false)

    override suspend fun readCompactConsecutiveMessages(): Boolean =
        readCompactConsecutiveMessagesSync()

    override suspend fun saveCompactConsecutiveMessages(enabled: Boolean) {
        store.putBoolean(KEY_COMPACT_CONSECUTIVE_MESSAGES, enabled)
    }

    override fun readImageAnnotationEnabledSync(): Boolean =
        store.getBoolean(KEY_IMAGE_ANNOTATION_ENABLED, false)

    override suspend fun readImageAnnotationEnabled(): Boolean =
        readImageAnnotationEnabledSync()

    override suspend fun saveImageAnnotationEnabled(enabled: Boolean) {
        store.putBoolean(KEY_IMAGE_ANNOTATION_ENABLED, enabled)
    }

    override fun readActivityTaskStatusBadgesEnabledSync(): Boolean =
        store.getBoolean(KEY_ACTIVITY_TASK_STATUS_BADGES_ENABLED, false)

    override suspend fun readActivityTaskStatusBadgesEnabled(): Boolean =
        readActivityTaskStatusBadgesEnabledSync()

    override suspend fun saveActivityTaskStatusBadgesEnabled(enabled: Boolean) {
        store.putBoolean(KEY_ACTIVITY_TASK_STATUS_BADGES_ENABLED, enabled)
    }

    override fun readThreadRowAffordancesSync(): Set<ThreadRowAffordancePreference> {
        val stored = store.getString(KEY_THREAD_ROW_AFFORDANCES)
        return if (stored == null) {
            ThreadRowAffordancePreference.Default
        } else {
            ThreadRowAffordancePreference.fromStorageKeys(
                stored.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
            )
        }
    }

    override suspend fun readThreadRowAffordances(): Set<ThreadRowAffordancePreference> =
        readThreadRowAffordancesSync()

    override suspend fun saveThreadRowAffordances(
        affordances: Set<ThreadRowAffordancePreference>
    ) {
        store.putString(
            KEY_THREAD_ROW_AFFORDANCES,
            affordances
                .sortedBy { it.ordinal }
                .joinToString(",") { it.storageKey }
        )
    }

    private companion object {
        const val KEY_MY_MESSAGE_ALIGNMENT = "chatLayout:my_message_alignment"
        const val KEY_LARGE_SCREEN_NAVIGATION = "chatLayout:large_screen_navigation"
        const val KEY_COMPACT_CONSECUTIVE_MESSAGES = "chatLayout:compact_consecutive_messages"
        const val KEY_IMAGE_ANNOTATION_ENABLED = "chatLayout:image_annotation_enabled"
        const val KEY_ACTIVITY_TASK_STATUS_BADGES_ENABLED = "chatLayout:activity_task_status_badges_enabled"
        const val KEY_THREAD_ROW_AFFORDANCES = "chatLayout:thread_row_affordances"
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
    suspend fun setCompactConsecutiveMessages(enabled: Boolean): ChatLayoutPreferenceSnapshot
    suspend fun setImageAnnotationEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot
    suspend fun setActivityTaskStatusBadgesEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot
    suspend fun setThreadRowAffordance(
        affordance: ThreadRowAffordancePreference,
        enabled: Boolean
    ): ChatLayoutPreferenceSnapshot
}

class PersistentChatLayoutPreferenceRepository(
    private val store: ChatLayoutPreferenceStore
) : ChatLayoutPreferenceRepository {
    override suspend fun getChatLayoutPreference(): ChatLayoutPreferenceSnapshot =
        ChatLayoutPreferenceSnapshot(
            myMessageAlignment = store.readMyMessageAlignment(),
            largeScreenNavigation = store.readLargeScreenNavigation(),
            compactConsecutiveMessages = store.readCompactConsecutiveMessages(),
            imageAnnotationEnabled = store.readImageAnnotationEnabled(),
            activityTaskStatusBadgesEnabled = store.readActivityTaskStatusBadgesEnabled(),
            threadRowAffordances = store.readThreadRowAffordances()
        )

    override suspend fun selectMyMessageAlignment(
        alignment: MyMessageAlignmentPreference
    ): ChatLayoutPreferenceSnapshot {
        store.saveMyMessageAlignment(alignment)
        return ChatLayoutPreferenceSnapshot(
            myMessageAlignment = alignment,
            largeScreenNavigation = store.readLargeScreenNavigation(),
            compactConsecutiveMessages = store.readCompactConsecutiveMessages(),
            imageAnnotationEnabled = store.readImageAnnotationEnabled(),
            activityTaskStatusBadgesEnabled = store.readActivityTaskStatusBadgesEnabled(),
            threadRowAffordances = store.readThreadRowAffordances()
        )
    }

    override suspend fun selectLargeScreenNavigation(
        navigation: LargeScreenNavigationPreference
    ): ChatLayoutPreferenceSnapshot {
        store.saveLargeScreenNavigation(navigation)
        return ChatLayoutPreferenceSnapshot(
            myMessageAlignment = store.readMyMessageAlignment(),
            largeScreenNavigation = navigation,
            compactConsecutiveMessages = store.readCompactConsecutiveMessages(),
            imageAnnotationEnabled = store.readImageAnnotationEnabled(),
            activityTaskStatusBadgesEnabled = store.readActivityTaskStatusBadgesEnabled(),
            threadRowAffordances = store.readThreadRowAffordances()
        )
    }

    override suspend fun setCompactConsecutiveMessages(enabled: Boolean): ChatLayoutPreferenceSnapshot {
        store.saveCompactConsecutiveMessages(enabled)
        return ChatLayoutPreferenceSnapshot(
            myMessageAlignment = store.readMyMessageAlignment(),
            largeScreenNavigation = store.readLargeScreenNavigation(),
            compactConsecutiveMessages = enabled,
            imageAnnotationEnabled = store.readImageAnnotationEnabled(),
            activityTaskStatusBadgesEnabled = store.readActivityTaskStatusBadgesEnabled(),
            threadRowAffordances = store.readThreadRowAffordances()
        )
    }

    override suspend fun setImageAnnotationEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot {
        store.saveImageAnnotationEnabled(enabled)
        return ChatLayoutPreferenceSnapshot(
            myMessageAlignment = store.readMyMessageAlignment(),
            largeScreenNavigation = store.readLargeScreenNavigation(),
            compactConsecutiveMessages = store.readCompactConsecutiveMessages(),
            imageAnnotationEnabled = enabled,
            activityTaskStatusBadgesEnabled = store.readActivityTaskStatusBadgesEnabled(),
            threadRowAffordances = store.readThreadRowAffordances()
        )
    }

    override suspend fun setActivityTaskStatusBadgesEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot {
        store.saveActivityTaskStatusBadgesEnabled(enabled)
        return ChatLayoutPreferenceSnapshot(
            myMessageAlignment = store.readMyMessageAlignment(),
            largeScreenNavigation = store.readLargeScreenNavigation(),
            compactConsecutiveMessages = store.readCompactConsecutiveMessages(),
            imageAnnotationEnabled = store.readImageAnnotationEnabled(),
            activityTaskStatusBadgesEnabled = enabled,
            threadRowAffordances = store.readThreadRowAffordances()
        )
    }

    override suspend fun setThreadRowAffordance(
        affordance: ThreadRowAffordancePreference,
        enabled: Boolean
    ): ChatLayoutPreferenceSnapshot {
        val current = store.readThreadRowAffordances()
        val next =
            if (enabled) {
                current + affordance
            } else {
                current - affordance
            }
        store.saveThreadRowAffordances(next)
        return ChatLayoutPreferenceSnapshot(
            myMessageAlignment = store.readMyMessageAlignment(),
            largeScreenNavigation = store.readLargeScreenNavigation(),
            compactConsecutiveMessages = store.readCompactConsecutiveMessages(),
            imageAnnotationEnabled = store.readImageAnnotationEnabled(),
            activityTaskStatusBadgesEnabled = store.readActivityTaskStatusBadgesEnabled(),
            threadRowAffordances = next
        )
    }
}

data class RecompositionProfilerPreferenceSnapshot(
    val enabled: Boolean
)

interface RecompositionProfilerPreferenceStore {
    fun readEnabledSync(): Boolean
    suspend fun readEnabled(): Boolean
    suspend fun saveEnabled(enabled: Boolean)
}

class KeyValueRecompositionProfilerPreferenceStore(
    private val store: SharedPreferenceStore
) : RecompositionProfilerPreferenceStore {
    override fun readEnabledSync(): Boolean =
        store.getBoolean(KEY_ENABLED, false)

    override suspend fun readEnabled(): Boolean =
        readEnabledSync()

    override suspend fun saveEnabled(enabled: Boolean) {
        store.putBoolean(KEY_ENABLED, enabled)
    }

    private companion object {
        const val KEY_ENABLED = "diagnostics:recomposition_profiler_enabled"
    }
}

interface RecompositionProfilerPreferenceRepository {
    suspend fun getPreference(): RecompositionProfilerPreferenceSnapshot
    suspend fun setEnabled(enabled: Boolean): RecompositionProfilerPreferenceSnapshot
}

class PersistentRecompositionProfilerPreferenceRepository(
    private val store: RecompositionProfilerPreferenceStore
) : RecompositionProfilerPreferenceRepository {
    override suspend fun getPreference(): RecompositionProfilerPreferenceSnapshot =
        RecompositionProfilerPreferenceSnapshot(enabled = store.readEnabled())

    override suspend fun setEnabled(enabled: Boolean): RecompositionProfilerPreferenceSnapshot {
        store.saveEnabled(enabled)
        return RecompositionProfilerPreferenceSnapshot(enabled = enabled)
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
    SlockSharedKeyValueStoreProvider.create(
        pager = pager,
        namespace = SlockLocalPreferenceStore.KuiklyNamespace,
        storageName = "shared preference"
    ).store
