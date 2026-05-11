package ai.slock.android.core.data.preferences

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
