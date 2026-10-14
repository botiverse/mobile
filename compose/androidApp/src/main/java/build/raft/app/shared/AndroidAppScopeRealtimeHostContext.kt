package build.raft.app.shared

import android.content.Context
import build.raft.app.core.data.auth.AndroidAuthApiFactory
import build.raft.app.core.data.auth.AndroidAppScopeMMKV
import build.raft.app.core.data.auth.androidMMKVAuthKeyValueStore
import build.raft.app.core.data.diagnostics.SlockAppLog
import build.raft.app.core.data.realtime.CurlSocketIoSocketTransport
import build.raft.app.core.data.realtime.RealtimeSocketGenerationFence
import build.raft.app.core.data.realtime.SelectableRealtimeSocketTransport
import build.raft.app.core.data.realtime.SessionRealtimeRepository
import build.raft.app.core.data.realtime.SocketIoRealtimeClient
import build.raft.app.core.data.realtime.SocketIoSocketTransport
import build.raft.app.data.DataResult
import build.raft.app.data.KeyValueAuthSessionStore
import build.raft.app.data.NOTIFICATION_ENABLED_PREFERENCE_KEY
import build.raft.app.data.RealtimeSubscription
import build.raft.app.data.SharedAuthTokenProvider
import build.raft.app.data.SharedSlockAuthTransport
import build.raft.app.data.SlockAuthApiClient
import build.raft.app.data.SlockAuthSessionStore
import build.raft.app.data.SlockLocalPreferenceStore
import build.raft.app.data.buildSystemUnreadNotificationRequestFromPush
import build.raft.app.data.postAndroidAppScopeEvent
import build.raft.app.data.realtime.RealtimeAuth
import build.raft.app.data.realtime.RealtimeConnectionState
import build.raft.app.data.realtime.RealtimeConnectRequest
import build.raft.app.data.realtime.RealtimeConnectionWitnessToken
import build.raft.app.data.realtime.RealtimeSocketConnectionContext
import build.raft.app.data.realtime.SlockRealtimeAppScopePolicy
import build.raft.app.data.realtime.SlockRealtimeHostContext
import build.raft.app.data.realtime.SlockRealtimeNotifyContract
import build.raft.app.model.ChannelId
import build.raft.app.network.REALTIME_CURL_SOCKETIO_PREFERENCE_KEY
import build.raft.app.network.SlockAndroidCurlSocketIo
import build.raft.app.network.SlockCurlSocketIoPolicy
import build.raft.app.model.ConversationKind
import build.raft.app.model.Message
import build.raft.app.model.NotificationPushReceived
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.ServerId
import com.tencent.mmkv.MMKV
import java.util.concurrent.atomic.AtomicLong
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
    // Task #167: same signal iOS posts. Shared consumers (the durable message canary, agent-activity seq) need
    // to know a new socket started before they see its frames.
    // Task #168: frames also carry the epoch of the socket that is current when they are fanned out, so the
    // canary does not depend on the signal reaching it first. 0 = this socket posted no signal.
    @Volatile private var currentPresenceTransportEpoch = 0L
    private val connectedSubscription =
        client.observeConnected { context -> postPresenceTransportConnected(context) }

    private fun postPresenceTransportConnected(context: RealtimeSocketConnectionContext) {
        currentPresenceTransportEpoch = 0L
        val witness = context.witness
        if (witness.scopeLeaseId.isBlank()) return
        val epoch = presenceTransportEpochs.incrementAndGet()
        currentPresenceTransportEpoch = epoch
        log("presence_transport_connected serverId=${context.serverId.value} presenceEpoch=$epoch")
        postAndroidAppScopeEvent(
            eventName = SlockRealtimeNotifyContract.EventName,
            eventData =
                JSONObject()
                    .put(SlockRealtimeNotifyContract.KeyAction, SlockRealtimeNotifyContract.ActionPresenceTransportConnected)
                    .put(SlockRealtimeNotifyContract.KeyServerId, context.serverId.value)
                    .put(SlockRealtimeNotifyContract.KeyAuthorityScopeLeaseId, witness.scopeLeaseId)
                    .put(SlockRealtimeNotifyContract.KeyAuthorityPrincipalId, witness.principalId)
                    .put(SlockRealtimeNotifyContract.KeyAuthorityAuthGeneration, witness.authGeneration)
                    .put(SlockRealtimeNotifyContract.KeyAuthorityConnectionFamilyId, witness.connectionFamilyId)
                    .put(SlockRealtimeNotifyContract.KeyPresenceTransportEpoch, epoch)
        )
    }

    override val state: RealtimeConnectionState
        get() = client.state

    override suspend fun connect(
        serverId: ServerId,
        accessToken: String,
        lastSeq: Long,
        witness: RealtimeConnectionWitnessToken,
    ): DataResult<Unit> {
        client.connect(
            RealtimeConnectRequest(
                auth =
                    RealtimeAuth(
                        token = accessToken,
                        serverId = serverId
                    ),
                lastSeq = lastSeq,
                witness = witness,
            )
        )
        return DataResult.Content(Unit)
    }

    override fun disconnect() {
        repository.disconnect()
    }

    override fun emitRoomCommand(eventName: String, payload: String, force: Boolean) {
        client.emitRoomCommand(eventName = eventName, payload = payload, force = force)
    }

    override fun observeEvents(listener: (RealtimeEvent) -> Unit): RealtimeSubscription =
        repository.observeEvents(listener)

    override fun observeRawEvents(
        listener: (
            context: RealtimeSocketConnectionContext,
            eventName: String,
            rawPayload: String,
            transportEpoch: Long?,
        ) -> Unit
    ): RealtimeSubscription =
        client.observeRawEvents { context, eventName, rawPayload ->
            // Task #168: bind the epoch here, on the socket thread inside the transport fence, where the
            // connected listener of this socket has already run and no later socket's can have. Read later on
            // main, an old socket's frame could pick up a newer epoch and be appended as current.
            listener(
                context,
                eventName,
                rawPayload.toRealtimeRawPayloadString(),
                currentPresenceTransportEpoch.takeIf { it > 0L },
            )
        }

    override fun log(message: String) {
        SlockAppLog.d(LOG_TAG, message)
    }

    override fun fanOutRawRealtimeEvent(
        serverId: ServerId,
        witness: RealtimeConnectionWitnessToken,
        eventName: String,
        rawPayload: String,
        transportEpoch: Long?,
    ) {
        // task #354: raw server events (potentially multi-MiB) go to the
        // dedicated ingest topic consumed only by the canonical AppShell
        // store owner, so a large payload is decoded once instead of once
        // per open page. Lifecycle/action signals keep the broadcast topic.
        // The epoch was bound when the socket thread received the frame (observeRawEvents), not read now.
        postAndroidAppScopeEvent(
            eventName = SlockRealtimeNotifyContract.RawIngestEventName,
            eventData =
                realtimeNotifyPayload(serverId, witness, eventName, rawPayload)
                    .also { payload ->
                        transportEpoch
                            ?.takeIf { it > 0L }
                            ?.let { payload.put(SlockRealtimeNotifyContract.KeyPresenceTransportEpoch, it) }
                    }
        )
    }

    override suspend fun maybePostBackgroundUnreadNotification(
        serverId: ServerId,
        event: NotificationPushReceived,
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

        // Process-wide: a rebuilt host context must not restart at 1 and reuse an epoch a durable window
        // already committed under (that would read as the same connection).
        private val presenceTransportEpochs = AtomicLong(0L)

        fun create(
            context: Context,
            scope: CoroutineScope,
            pinnedServerId: ServerId? = null
        ): AndroidAppScopeRealtimeHostContext {
            val appContext = context.applicationContext
            val sessionStore = KeyValueAuthSessionStore(androidMMKVAuthKeyValueStore(appContext))
            val apiBaseUrl = AndroidAuthApiFactory.currentBaseUrl(appContext)
            val socketUrl = AndroidAuthApiFactory.currentSocketBaseUrl(appContext)
            // Task #154: the Settings switch / `mobile_curl_socketio_v0` pick the Socket.IO stack per connect.
            SlockCurlSocketIoPolicy.bindUserChoice { readCurlSocketIoUserChoice(appContext) }
            val socketFence = RealtimeSocketGenerationFence()
            val transport =
                SelectableRealtimeSocketTransport(
                    legacy = SocketIoSocketTransport(socketUrl, socketFence),
                    curl = CurlSocketIoSocketTransport(socketUrl = socketUrl, generationFence = socketFence),
                    fence = socketFence,
                    useCurl = { connection -> useCurlSocketIo(connection.serverId.value) },
                    // One line per connect (`transport_select stack=…`): recorded in release builds too,
                    // where debug/info lines are dropped, so device checks can see which stack connected.
                    logger = { message -> SlockAppLog.w(LOG_TAG, message) }
                )
            val client =
                SocketIoRealtimeClient(
                    transport = transport,
                    logger = { message ->
                        SlockAppLog.d(LOG_TAG, message)
                    }
                )
            val repository =
                SessionRealtimeRepository(
                    store = sessionStore,
                    client = client,
                    pinnedServerId = pinnedServerId,
                    tokenProvider =
                        SharedAuthTokenProvider(
                            sessionStore = sessionStore,
                            authApi =
                                SlockAuthApiClient(
                                    SharedSlockAuthTransport(
                                        baseUrl = apiBaseUrl,
                                        warningLogger = { message -> SlockAppLog.w(LOG_TAG, message) }
                                    )
                                ),
                            clockMillis = { System.currentTimeMillis() },
                            logger = { message -> SlockAppLog.auth(LOG_TAG, message) },
                            warningLogger = { message -> SlockAppLog.w(LOG_TAG, message) }
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

        private fun realtimeNotifyPayload(
            serverId: ServerId,
            witness: RealtimeConnectionWitnessToken,
            eventName: String,
            rawPayload: Any?,
        ): JSONObject =
            JSONObject()
                .put(SlockRealtimeNotifyContract.KeyServerId, serverId.value)
                .put(SlockRealtimeNotifyContract.KeyAuthorityScopeLeaseId, witness.scopeLeaseId)
                .put(SlockRealtimeNotifyContract.KeyAuthorityPrincipalId, witness.principalId)
                .put(SlockRealtimeNotifyContract.KeyAuthorityAuthGeneration, witness.authGeneration)
                .put(SlockRealtimeNotifyContract.KeyAuthorityConnectionFamilyId, witness.connectionFamilyId)
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
            event: NotificationPushReceived,
            isAppInForeground: Boolean
        ) {
            val preferenceEnabled = isUnreadNotificationPreferenceEnabled(appContext)
            val hasAttachedConsumers = false
            SlockAppLog.w(
                LOG_TAG,
                "background_unread_notification_candidate serverId=${serverId.value} messageId=${event.messageId.value} preference_enabled=$preferenceEnabled foreground=$isAppInForeground"
            )
            if (!shouldPostBackgroundUnreadNotification(hasAttachedConsumers, preferenceEnabled, isAppInForeground)) {
                SlockAppLog.w(
                    LOG_TAG,
                    "background_unread_notification_skip reason=${backgroundUnreadNotificationSkipReason(hasAttachedConsumers, preferenceEnabled, isAppInForeground)} serverId=${serverId.value} messageId=${event.messageId.value}"
                )
                return
            }
            val request =
                buildSystemUnreadNotificationRequestFromPush(
                    push = event,
                    isVisibleThread = false,
                    isAppInForeground = isAppInForeground
                )
            if (request == null) {
                SlockAppLog.w(
                    LOG_TAG,
                    "background_unread_notification_skip reason=policy serverId=${serverId.value} messageId=${event.messageId.value}"
                )
                return
            }
            val result = AndroidUnreadNotificationPoster.postUnreadNotification(appContext, request)
            SlockAppLog.w(
                LOG_TAG,
                "background_unread_notification_result code=${result["code"]} status=${result["status"]} serverId=${serverId.value} messageId=${event.messageId.value}"
            )
        }



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

        private fun useCurlSocketIo(serverId: String): Boolean {
            if (!SlockCurlSocketIoPolicy.isEnabledFor(serverId)) return false
            val unavailable = SlockAndroidCurlSocketIo.unavailableReason() ?: return true
            SlockAppLog.w(LOG_TAG, "curl_socket_io_selected_but_unavailable reason=$unavailable serverId=$serverId")
            return false
        }

        /** The Settings switch as stored by the Kuikly local preference store; null = follow the server. */
        private fun readCurlSocketIoUserChoice(context: Context): Boolean? =
            runCatching {
                AndroidAppScopeMMKV.ensureInitialized(context.applicationContext)
                val store = MMKV.mmkvWithID(SlockLocalPreferenceStore.KuiklyNamespace)
                SlockCurlSocketIoPolicy.parseUserChoice(
                    store.decodeString(
                        AndroidRealtimeKeepAliveController.localPreferenceKey(REALTIME_CURL_SOCKETIO_PREFERENCE_KEY),
                        null
                    ) ?: store.decodeString(REALTIME_CURL_SOCKETIO_PREFERENCE_KEY, null)
                )
            }.getOrElse { error ->
                SlockAppLog.w(LOG_TAG, "curl_socket_io_preference_failed message=${error.message.orEmpty()}")
                null
            }

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
                SlockAppLog.w(
                    LOG_TAG,
                    "background_unread_notification_preference_failed message=${error.message.orEmpty()}"
                )
                false
            }
    }
}
