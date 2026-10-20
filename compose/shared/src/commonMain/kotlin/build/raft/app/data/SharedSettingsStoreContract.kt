package build.raft.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Adapts an existing [SharedSettingsStore] with MVI contract enforcement
 * without modifying the store's internal implementation.
 *
 * Adds three layers on top of the raw store state:
 * 1. **No-op gate**: skips emission when the new state equals the previous
 *    state (structural equality via [MviStoreRuntime] equivalent).
 * 2. **Transition logging**: emits [MviTransition] events for every intent
 *    dispatch, including whether the state actually changed.
 * 3. **In-flight mutation guard**: wraps mutation intents with [MviInFlightGuard]
 *    for typed action-level idempotency, replacing the ad-hoc inProgress check.
 */
class SharedSettingsStoreContract(
    private val store: SharedSettingsStore,
    private val scope: CoroutineScope,
    private val transitionLogger: (MviTransition<SharedSettingsIntent>) -> Unit = {}
) {
    private val inFlightGuard = MviInFlightGuard()

    // Track last emitted state for no-op gate
    private var lastEmitted: SharedSettingsState? = null

    private val _state = MutableStateFlow(store.state.value)
    val state: StateFlow<SharedSettingsState> = _state.asStateFlow()

    private val _transitions =
        MutableSharedFlow<MviTransition<SharedSettingsIntent>>(extraBufferCapacity = 64)
    val transitions: SharedFlow<MviTransition<SharedSettingsIntent>> = _transitions.asSharedFlow()

    init {
        // Mirror the store's state through a no-op gate: skip when equivalent to last emitted
        scope.launch {
            store.state.collect { newState ->
                val previous = lastEmitted
                if (previous != null && previous == newState) return@collect
                lastEmitted = newState
                _state.value = newState
            }
        }
    }

    /**
     * Wraps the store's [SharedSettingsStore.dispatch] with MVI contract
     * enforcement: in-flight guard for mutation intents + transition logging.
     */
    fun dispatch(intent: SharedSettingsIntent) {
        // Guard mutation intents against concurrent re-submission
        val actionKey = intent.actionKeyOrNull()
        if (actionKey != null) {
            if (!inFlightGuard.tryStart(actionKey)) {
                transitionLogger(
                    MviTransition(
                        intent = intent,
                        emitted = false,
                        reason = "in-flight:${actionKey.value}"
                    )
                )
                return
            }
            // Release guard when the store's actionState clears or the intent completes
            scope.launch {
                try {
                    store.state.collect { s ->
                        if (s.actionState == null || !s.actionState!!.inProgress) {
                            inFlightGuard.finish(actionKey)
                            return@collect
                        }
                    }
                } finally {
                    inFlightGuard.finish(actionKey)
                }
            }
        }

        store.dispatch(intent)

        _transitions.tryEmit(MviTransition(intent = intent, emitted = true))
        transitionLogger(MviTransition(intent = intent, emitted = true))
    }

    fun clear() {
        store.clear()
    }

    companion object {
        /**
         * Maps a mutation intent to an action key for in-flight guarding.
         * Returns null for non-mutation intents (navigation, refresh, effect-only).
         */
        private fun SharedSettingsIntent.actionKeyOrNull(): MviActionKey? =
            when (this) {
                is SharedSettingsIntent.UpdateProfile -> MviActionKey(ACCOUNT_ACTION_PROFILE_SAVE)
                SharedSettingsIntent.RequestAccountAvatarUpload -> MviActionKey(ACCOUNT_ACTION_AVATAR_UPLOAD)
                is SharedSettingsIntent.ChangeAccountPassword -> MviActionKey(ACCOUNT_ACTION_PASSWORD_CHANGE)
                is SharedSettingsIntent.ConnectAccountProvider -> MviActionKey("${ACCOUNT_ACTION_PROVIDER_CONNECT}:${request.providerId}")
                is SharedSettingsIntent.RegisterConnectedApp -> MviActionKey(CONNECTED_APPS_ACTION_REGISTER)
                is SharedSettingsIntent.UpdateConnectedApp -> MviActionKey("${CONNECTED_APPS_ACTION_UPDATE}:${request.id}")
                is SharedSettingsIntent.RequestConnectedAppLogoUpload -> MviActionKey("${CONNECTED_APPS_ACTION_LOGO_UPLOAD}:$clientRecordId")
                is SharedSettingsIntent.ClearConnectedAppLogo -> MviActionKey("${CONNECTED_APPS_ACTION_LOGO_CLEAR}:$clientRecordId")
                is SharedSettingsIntent.DeleteConnectedApp -> MviActionKey("${CONNECTED_APPS_ACTION_DELETE}:$clientRecordId")
                is SharedSettingsIntent.RequestConnectedAppPublish -> MviActionKey("${CONNECTED_APPS_ACTION_PUBLISH}:${request.id}")
                is SharedSettingsIntent.InstallMarketplaceApp -> MviActionKey("${CONNECTED_APPS_ACTION_INSTALL}:$clientRecordId")
                is SharedSettingsIntent.UninstallMarketplaceApp -> MviActionKey("${CONNECTED_APPS_ACTION_UNINSTALL}:$clientRecordId")
                is SharedSettingsIntent.RotateComputerKey -> MviActionKey("${COMPUTER_ACTION_ROTATE_KEY}:$machineId")
                is SharedSettingsIntent.UpdateServerProfile -> MviActionKey(request.serverProfileActionKey)
                is SharedSettingsIntent.CreateServerInvite -> MviActionKey(SERVER_ACTION_INVITE_CREATE)
                is SharedSettingsIntent.RevokeServerInvite -> MviActionKey("${SERVER_ACTION_INVITE_REVOKE}:$inviteId")
                is SharedSettingsIntent.CreateServerJoinLink -> MviActionKey(SERVER_ACTION_JOIN_LINK_CREATE)
                is SharedSettingsIntent.RevokeServerJoinLink -> MviActionKey("${SERVER_ACTION_JOIN_LINK_REVOKE}:$linkId")
                is SharedSettingsIntent.UpdateServerAgreement -> MviActionKey(SERVER_ACTION_AGREEMENT_SAVE)
                is SharedSettingsIntent.UpdateServerOnboarding -> MviActionKey(SERVER_ACTION_ONBOARDING_SAVE)
                is SharedSettingsIntent.UpdateServerTranslation -> MviActionKey(SERVER_ACTION_TRANSLATION_TOGGLE)
                is SharedSettingsIntent.UpdateServerSidebarOrder -> MviActionKey(SERVER_ACTION_SIDEBAR_ORDER)
                is SharedSettingsIntent.UpdateServerMemberRole -> MviActionKey("${SERVER_ACTION_MEMBER_ROLE}:${request.memberId}")
                is SharedSettingsIntent.RemoveServerMember -> MviActionKey("${SERVER_ACTION_MEMBER_REMOVE}:$memberId")
                SharedSettingsIntent.LeaveServer -> MviActionKey(SERVER_ACTION_LEAVE)
                is SharedSettingsIntent.DeleteServer -> MviActionKey(SERVER_ACTION_DELETE)
                is SharedSettingsIntent.SetNotificationPreference -> MviActionKey(MOBILE_ACTION_NOTIFICATION_PREFERENCE)
                is SharedSettingsIntent.SetForegroundKeepAlivePreference -> MviActionKey(MOBILE_ACTION_FOREGROUND_KEEP_ALIVE)
                is SharedSettingsIntent.SetServerMute,
                is SharedSettingsIntent.SetServerNotificationMode -> MviActionKey(MOBILE_ACTION_SERVER_MUTE)
                is SharedSettingsIntent.SetPushPreviewMode -> MviActionKey(MOBILE_ACTION_PUSH_PREVIEW_MODE)
                is SharedSettingsIntent.SetLocale -> MviActionKey(MOBILE_ACTION_LANGUAGE)
                is SharedSettingsIntent.SetMessageAlignment -> MviActionKey(MOBILE_ACTION_MESSAGE_ALIGNMENT)
                is SharedSettingsIntent.SetLinkOpenMode -> MviActionKey(MOBILE_ACTION_LINK_OPEN_MODE)
                is SharedSettingsIntent.SetCompactConsecutiveMessages -> MviActionKey(MOBILE_ACTION_COMPACT_MESSAGES)
                is SharedSettingsIntent.SetLongMessageCollapseEnabled -> MviActionKey(MOBILE_ACTION_LONG_MESSAGE_COLLAPSE)
                is SharedSettingsIntent.SetSingleTapOpensThreadEnabled -> MviActionKey(MOBILE_ACTION_SINGLE_TAP_OPENS_THREAD)
                is SharedSettingsIntent.SetNewMessageMenuEnabled -> MviActionKey(MOBILE_ACTION_NEW_MESSAGE_MENU)
                is SharedSettingsIntent.SetConversationForwardGestureEnabled -> MviActionKey(MOBILE_ACTION_CONVERSATION_FORWARD_GESTURE)
                is SharedSettingsIntent.SetForegroundMessageBanners -> MviActionKey(MOBILE_ACTION_FOREGROUND_MESSAGE_BANNERS)
                is SharedSettingsIntent.SetActivityTaskStatusBadges -> MviActionKey(MOBILE_ACTION_ACTIVITY_TASK_STATUS_BADGES)
                is SharedSettingsIntent.SetComposerSendKey -> MviActionKey(MOBILE_ACTION_COMPOSER_SEND_KEY)
                is SharedSettingsIntent.SetServerDrawerEnabled -> MviActionKey(MOBILE_ACTION_SERVER_DRAWER)
                is SharedSettingsIntent.SetNewDesignSettingsEnabled -> MviActionKey(MOBILE_ACTION_NEW_DESIGN_SETTINGS)
                is SharedSettingsIntent.SetHomeServerAvatarEnabled -> MviActionKey(MOBILE_ACTION_HOME_SERVER_AVATAR)
                is SharedSettingsIntent.SetRealtimeCurlSocketIoEnabled -> MviActionKey(MOBILE_ACTION_REALTIME_CURL_SOCKETIO)
                is SharedSettingsIntent.SetHttp3Enabled -> MviActionKey(MOBILE_ACTION_HTTP3)
                is SharedSettingsIntent.SetThreadReverseLayoutEnabled -> MviActionKey(MOBILE_ACTION_THREAD_REVERSE_LAYOUT)
                is SharedSettingsIntent.UpdateAppearance -> MviActionKey(APPEARANCE_ACTION_MESSAGE_FONT_SIZE)
                is SharedSettingsIntent.SetApiEnvironment -> MviActionKey("${DEVELOPER_ACTION_API_ENVIRONMENT}:$environmentKey")
                is SharedSettingsIntent.SaveApiEnvironment -> MviActionKey("${DEVELOPER_ACTION_API_ENVIRONMENT}:${request.key}")
                SharedSettingsIntent.CheckHandsUpdate -> MviActionKey(DEVELOPER_ACTION_HANDS_UPDATE_CHECK)
                else -> null
            }
    }
}
