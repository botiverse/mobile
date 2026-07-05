package build.raft.app.shared

import android.content.Context
import build.raft.app.core.data.auth.AndroidAuthApiFactory
import build.raft.app.core.data.auth.AndroidApiServerEnvironmentStore
import build.raft.app.core.data.auth.AndroidAppScopeMMKV
import build.raft.app.core.data.auth.androidMMKVAuthKeyValueStore
import build.raft.app.core.data.diagnostics.SlockDiagnosticsLogger
import build.raft.app.core.data.realtime.SessionRealtimeRepository
import build.raft.app.core.data.realtime.SocketIoRealtimeClient
import build.raft.app.core.data.realtime.SocketIoSocketTransport
import build.raft.app.data.DataResult
import build.raft.app.data.KeyValueAuthSessionStore
import build.raft.app.data.NOTIFICATION_ENABLED_PREFERENCE_KEY
import build.raft.app.data.RealtimeSubscription
import build.raft.app.data.SharedAuthTokenProvider
import build.raft.app.data.SharedKtorSlockAuthTransport
import build.raft.app.data.SlockAuthApiClient
import build.raft.app.data.SlockAuthSessionStore
import build.raft.app.data.SlockLocalPreferenceStore
import build.raft.app.data.buildSystemUnreadNotificationRequest
import build.raft.app.data.postAndroidAppScopeEvent
import build.raft.app.data.realtime.RealtimeAuth
import build.raft.app.data.realtime.RealtimeConnectionState
import build.raft.app.data.realtime.RealtimeConnectRequest
import build.raft.app.data.realtime.SlockRealtimeAppScopePolicy
import build.raft.app.data.realtime.SlockRealtimeHostContext
import build.raft.app.data.realtime.SlockRealtimeNotifyContract
import build.raft.app.data.realtime.createSharedKtorSocketIoTransport
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.Message
import build.raft.app.model.MessageNew
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.ServerId
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal class AndroidAppScopeRealtimeHostContext private constructor(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val sessionStore: SlockAuthSessionStore,
    private val repository: SessionRealtimeRepository,
    val client: SocketIoRealtimeClient
) : SlockRealtimeHostContext {
    override val state: RealtimeConnectionState
        get() = client.state

    override suspend fun connect(
        serverId: ServerId,
        accessToken: String,
        lastSeq: Long
    ): DataResult<Unit> {
        client.connect(
            RealtimeConnectRequest(
                auth =
                    RealtimeAuth(
                        token = accessToken,
                        serverId = serverId
                    ),
                lastSeq = lastSeq
            )
        )
        return DataResult.Content(Unit)
    }

    override fun disconnect() {
        repository.disconnect()
    }

    override fun emitRoomCommand(eventName: String, payload: String) {
        client.emitRoomCommand(eventName = eventName, payload = payload)
    }

    override fun observeEvents(listener: (RealtimeEvent) -> Unit): RealtimeSubscription =
        repository.observeEvents(listener)

    override fun observeRawEvents(listener: (eventName: String, rawPayload: String) -> Unit): RealtimeSubscription =
        client.observeRawEvents { eventName, rawPayload ->
            listener(eventName, rawPayload.toRealtimeRawPayloadString())
        }

    override fun log(message: String) {
        SlockDiagnosticsLogger.d(LOG_TAG, message)
    }

    override fun fanOutRawRealtimeEvent(
        serverId: ServerId,
        eventName: String,
        rawPayload: String
    ) {
        postAndroidAppScopeEvent(
            eventName = SlockRealtimeNotifyContract.EventName,
            eventData = realtimeNotifyPayload(serverId, eventName, rawPayload)
        )
    }

    override suspend fun maybePostBackgroundUnreadNotification(
        serverId: ServerId,
        event: MessageNew,
        isAppInForeground: Boolean
    ) {
        maybePostBackgroundUnreadNotification(
            appContext = appContext,
            sessionStore = sessionStore,
            serverId = serverId,
            event = event,
            isAppInForeground = isAppInForeground
        )
    }

    override fun dispatchRealtime(block: suspend () -> Unit) {
        scope.launch {
            block()
        }
    }

    companion object {
        private const val LOG_TAG = "SlockKuiklyRealtime"

        fun create(
            context: Context,
            scope: CoroutineScope
        ): AndroidAppScopeRealtimeHostContext {
            val appContext = context.applicationContext
            val sessionStore = KeyValueAuthSessionStore(androidMMKVAuthKeyValueStore(appContext))
            val apiBaseUrl = AndroidAuthApiFactory.currentBaseUrl(appContext)
            val socketUrl = AndroidAuthApiFactory.currentSocketBaseUrl(appContext)
            val sharedTransportEnabled =
                AndroidApiServerEnvironmentStore(appContext).sharedSocketIoTransportEnabled()
            SlockDiagnosticsLogger.d(
                LOG_TAG,
                "shared_socketio_transport_selected=${if (sharedTransportEnabled) "shared" else "platform"}"
            )
            val client =
                SocketIoRealtimeClient(
                    transport =
                        if (sharedTransportEnabled) {
                            createSharedKtorSocketIoTransport(
                                socketUrl = socketUrl,
                                logger = { message ->
                                    SlockDiagnosticsLogger.d(LOG_TAG, message)
                                },
                                scope = scope
                            )
                        } else {
                            SocketIoSocketTransport(socketUrl = socketUrl)
                        },
                    logger = { message ->
                        SlockDiagnosticsLogger.d(LOG_TAG, message)
                    }
                )
            val repository =
                SessionRealtimeRepository(
                    store = sessionStore,
                    client = client,
                    tokenProvider =
                        SharedAuthTokenProvider(
                            sessionStore = sessionStore,
                            authApi =
                                SlockAuthApiClient(
                                    SharedKtorSlockAuthTransport(
                                        baseUrl = apiBaseUrl,
                                        warningLogger = { message -> SlockDiagnosticsLogger.w(LOG_TAG, message) }
                                    )
                                ),
                            clockMillis = { System.currentTimeMillis() },
                            logger = { message -> SlockDiagnosticsLogger.d(LOG_TAG, message) },
                            warningLogger = { message -> SlockDiagnosticsLogger.w(LOG_TAG, message) }
                        )
                )
            return AndroidAppScopeRealtimeHostContext(
                appContext = appContext,
                scope = scope,
                sessionStore = sessionStore,
                repository = repository,
                client = client
            )
        }

        fun shouldPostBackgroundUnreadNotification(
            hasAttachedConsumers: Boolean,
            preferenceEnabled: Boolean,
            isAppInForeground: Boolean
        ): Boolean =
            SlockRealtimeAppScopePolicy.shouldPostBackgroundUnreadNotification(
                hasAttachedConsumers = hasAttachedConsumers,
                preferenceEnabled = preferenceEnabled,
                isAppInForeground = isAppInForeground
            )

        fun backgroundUnreadNotificationConversationKind(message: Message): ConversationKind? =
            SlockRealtimeAppScopePolicy.backgroundUnreadNotificationConversationKind(message)

        private fun realtimeNotifyPayload(serverId: ServerId, eventName: String, rawPayload: Any?): JSONObject =
            JSONObject()
                .put(SlockRealtimeNotifyContract.KeyServerId, serverId.value)
                .put(SlockRealtimeNotifyContract.KeyEventName, eventName)
                .put(SlockRealtimeNotifyContract.KeyRawPayload, rawPayload.toRealtimeRawPayloadString())

        private fun Any?.toRealtimeRawPayloadString(): String =
            when (this) {
                is String -> this
                is JSONObject -> toString()
                is JSONArray -> toString()
                else -> this?.toString() ?: "{}"
            }

        private suspend fun maybePostBackgroundUnreadNotification(
            appContext: Context,
            sessionStore: SlockAuthSessionStore,
            serverId: ServerId,
            event: MessageNew,
            isAppInForeground: Boolean
        ) {
            val preferenceEnabled = isUnreadNotificationPreferenceEnabled(appContext)
            val hasAttachedConsumers = false
            SlockDiagnosticsLogger.w(
                LOG_TAG,
                "background_unread_notification_candidate serverId=${serverId.value} messageId=${event.message.id.value} preference_enabled=$preferenceEnabled foreground=$isAppInForeground"
            )
            if (!shouldPostBackgroundUnreadNotification(hasAttachedConsumers, preferenceEnabled, isAppInForeground)) {
                SlockDiagnosticsLogger.w(
                    LOG_TAG,
                    "background_unread_notification_skip reason=${backgroundUnreadNotificationSkipReason(hasAttachedConsumers, preferenceEnabled, isAppInForeground)} serverId=${serverId.value} messageId=${event.message.id.value}"
                )
                return
            }
            val session = sessionStore.readSession()
            val request =
                buildSystemUnreadNotificationRequest(
                    serverId = serverId.value,
                    message = event.message,
                    currentMemberId = session?.user?.id,
                    isVisibleThread = false,
                    conversationName = null,
                    conversationKind = event.message.backgroundNotificationConversationKind(),
                    currentMemberHandle = session?.user?.email,
                    isAppInForeground = isAppInForeground
                )
            if (request == null) {
                SlockDiagnosticsLogger.w(
                    LOG_TAG,
                    "background_unread_notification_skip reason=policy serverId=${serverId.value} messageId=${event.message.id.value}"
                )
                return
            }
            val result = AndroidUnreadNotificationPoster.postUnreadNotification(appContext, request)
            SlockDiagnosticsLogger.w(
                LOG_TAG,
                "background_unread_notification_result code=${result["code"]} status=${result["status"]} serverId=${serverId.value} messageId=${event.message.id.value}"
            )
        }

        private fun Message.backgroundNotificationConversationKind(): ConversationKind? =
            backgroundUnreadNotificationConversationKind(this)

        private fun backgroundUnreadNotificationSkipReason(
            hasAttachedConsumers: Boolean,
            preferenceEnabled: Boolean,
            isAppInForeground: Boolean
        ): String =
            SlockRealtimeAppScopePolicy.backgroundUnreadNotificationSkipReason(
                hasAttachedConsumers = hasAttachedConsumers,
                preferenceEnabled = preferenceEnabled,
                isAppInForeground = isAppInForeground
            )

        private fun isUnreadNotificationPreferenceEnabled(context: Context): Boolean =
            runCatching {
                AndroidAppScopeMMKV.ensureInitialized(context.applicationContext)
                val store = MMKV.mmkvWithID(SlockLocalPreferenceStore.KuiklyNamespace)
                val value =
                    store.decodeString(
                        AndroidRealtimeKeepAliveController.localPreferenceKey(NOTIFICATION_ENABLED_PREFERENCE_KEY),
                        null
                    ) ?: store.decodeString(NOTIFICATION_ENABLED_PREFERENCE_KEY, "true")
                value == "true"
            }.getOrElse { error ->
                SlockDiagnosticsLogger.w(
                    LOG_TAG,
                    "background_unread_notification_preference_failed message=${error.message.orEmpty()}"
                )
                false
            }
    }
}
