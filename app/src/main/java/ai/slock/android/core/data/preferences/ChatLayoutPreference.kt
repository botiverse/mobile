package ai.slock.android.core.data.preferences

import android.content.Context

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
    val supportedAlignments: List<MyMessageAlignmentPreference> = MyMessageAlignmentPreference.entries,
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

class SharedPreferencesChatLayoutPreferenceStore(
    context: Context
) : ChatLayoutPreferenceStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    override fun readMyMessageAlignmentSync(): MyMessageAlignmentPreference =
        MyMessageAlignmentPreference.fromStorageKey(preferences.getString(KEY_MY_MESSAGE_ALIGNMENT, null))

    override suspend fun readMyMessageAlignment(): MyMessageAlignmentPreference =
        readMyMessageAlignmentSync()

    override suspend fun saveMyMessageAlignment(alignment: MyMessageAlignmentPreference) {
        preferences.edit()
            .putString(KEY_MY_MESSAGE_ALIGNMENT, alignment.storageKey)
            .apply()
    }

    override fun readLargeScreenNavigationSync(): LargeScreenNavigationPreference =
        LargeScreenNavigationPreference.fromStorageKey(
            preferences.getString(KEY_LARGE_SCREEN_NAVIGATION, null)
        )

    override suspend fun readLargeScreenNavigation(): LargeScreenNavigationPreference =
        readLargeScreenNavigationSync()

    override suspend fun saveLargeScreenNavigation(navigation: LargeScreenNavigationPreference) {
        preferences.edit()
            .putString(KEY_LARGE_SCREEN_NAVIGATION, navigation.storageKey)
            .apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "slock_chat_layout_preferences"
        private const val KEY_MY_MESSAGE_ALIGNMENT = "my_message_alignment"
        private const val KEY_LARGE_SCREEN_NAVIGATION = "large_screen_navigation"
    }
}
