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

data class ChatLayoutPreferenceSnapshot(
    val myMessageAlignment: MyMessageAlignmentPreference,
    val supportedAlignments: List<MyMessageAlignmentPreference> = MyMessageAlignmentPreference.entries
)

interface ChatLayoutPreferenceStore {
    fun readMyMessageAlignmentSync(): MyMessageAlignmentPreference
    suspend fun readMyMessageAlignment(): MyMessageAlignmentPreference
    suspend fun saveMyMessageAlignment(alignment: MyMessageAlignmentPreference)
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

    companion object {
        private const val PREFERENCES_NAME = "slock_chat_layout_preferences"
        private const val KEY_MY_MESSAGE_ALIGNMENT = "my_message_alignment"
    }
}
