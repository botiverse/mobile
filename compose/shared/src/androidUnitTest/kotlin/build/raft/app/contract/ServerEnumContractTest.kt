package build.raft.app.contract

import build.raft.app.MemberKindWireContract
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftAgentsApi
import build.raft.app.data.RaftAgentsApiClient
import build.raft.app.data.RuntimeAuthRuntime
import build.raft.app.data.parseActivityObjectInboxItem
import build.raft.app.data.parseSharedInboxSnapshot
import build.raft.app.data.parseSharedSavedItems
import build.raft.app.data.parseSharedSearchItems
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.data.DataResult
import build.raft.app.model.MemberId
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.NotificationNoticeReceived
import build.raft.app.model.NotificationPushReceived
import build.raft.app.model.PerReceiverPinnedChanged
import build.raft.app.model.TaskStatus
import build.raft.app.parseConversationKindOrNull
import build.raft.app.strictMemberKindOrNull
import build.raft.app.strictMessageTypeOrNull
import build.raft.app.strictTaskStatusOrNull
import java.io.File
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Every enum-like value the server can send, for each domain the app maps, must reach the user:
 * either as a typed app value, or as an **explicit, logged fallback** (a generic row / notice with a
 * `projection_value_unknown … action=fallback_*` line). A silent drop fails here.
 *
 * The values are read from botiverse/slock (commit in `SOURCE_SHA`) into
 * `src/commonTest/resources/contract-cases/server-enums/server-enums.json` by
 * `scripts/refresh_server_enum_fixture.py`; see the README beside the fixture for how to refresh.
 * A refresh that brings a new server value fails this test until the app maps it (or, for socket
 * events only, this test records why mobile does not subscribe to it).
 *
 * JVM-only (androidUnitTest) because commonTest has no portable resource reader.
 */
class ServerEnumContractTest {
    private val fixture: JsonObject by lazy {
        val url =
            checkNotNull(javaClass.getResource("/contract-cases/server-enums/server-enums.json")) {
                "contract-cases/server-enums is not on the test classpath (src/commonTest/resources)"
            }
        Json.parseToJsonElement(File(url.toURI()).readText()).jsonObject
    }

    private fun values(domain: String): List<String> {
        val entry = checkNotNull(fixture.getValue("domains").jsonObject[domain]) { "fixture has no domain '$domain'" }
        val list = entry.jsonObject.getValue("values").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(list.isNotEmpty(), "domain '$domain' lists no values")
        return list
    }

    private fun List<String>.loggedFallback(): Boolean =
        any { it.startsWith("projection_value_unknown") && "action=fallback_" in it }

    @Test
    fun sourceShaIsPinned() {
        val url = checkNotNull(javaClass.getResource("/contract-cases/server-enums/SOURCE_SHA"))
        val sha = File(url.toURI()).readText().trim()
        assertTrue(sha.matches(Regex("[0-9a-f]{40}")), "SOURCE_SHA must be a full slock commit, was '$sha'")
        assertEquals(sha, fixture.getValue("sourceSha").jsonPrimitive.content, "SOURCE_SHA and the fixture disagree")
    }

    @Test
    fun everyFixtureDomainIsChecked() {
        // A domain added to the refresh script needs a check here (and the other way round).
        assertEquals(CheckedDomains.sorted(), fixture.getValue("domains").jsonObject.keys.sorted())
    }

    @Test
    fun channelTypesMapAndSavedAndSearchRowsAlwaysShow() {
        val failures = mutableListOf<String>()
        for (type in values("channel_types")) {
            if (parseConversationKindOrNull(type) == null) failures += "$type: no ConversationKind"
            val savedLogs = mutableListOf<String>()
            val saved = parseSharedSavedItems(savedBody(type), savedLogs::add)
            if (saved?.items?.size != 1) failures += "$type: saved row dropped logs=$savedLogs"
            val searchLogs = mutableListOf<String>()
            val search = parseSharedSearchItems(searchBody(type), searchLogs::add)
            if (search?.items?.size != 1) failures += "$type: search row dropped logs=$searchLogs"
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun unknownChannelTypeIsAGenericChannelRowWithALoggedFallback() {
        val logs = mutableListOf<String>()
        val saved = parseSharedSavedItems(savedBody("hologram"), logs::add)
        assertEquals(1, saved?.items?.size, "logs=$logs")
        assertEquals(
            listOf("projection_value_unknown source=saved field=channel_type value=hologram action=fallback_channel_row"),
            logs.filter { it.startsWith("projection_value_unknown") },
        )
    }

    @Test
    fun inboxKindsAlwaysShow() {
        val failures = mutableListOf<String>()
        for (kind in values("inbox_kinds")) {
            val logs = mutableListOf<String>()
            val known = kind in InboxSampleRows
            val row = InboxSampleRows[kind] ?: genericInboxRow(kind)
            val snapshot = parseSharedInboxSnapshot("""{"items":[$row],"hasMore":false}""", logs::add)
            when {
                snapshot?.items?.size != 1 -> failures += "$kind: row dropped logs=$logs"
                !known && !logs.loggedFallback() -> failures += "$kind: unknown kind shown without a logged fallback"
            }
        }
        assertEquals(emptyList(), failures)
        // A kind the app does not know (no sample row) takes the generic channel-row fallback.
        val logs = mutableListOf<String>()
        val snapshot = parseSharedInboxSnapshot("""{"items":[${genericInboxRow("future_kind")}],"hasMore":false}""", logs::add)
        assertEquals(1, snapshot?.items?.size)
        assertTrue(logs.loggedFallback(), "logs=$logs")
    }

    @Test
    fun inboxChannelTypesMap() {
        val failures = mutableListOf<String>()
        for (type in values("inbox_channel_types")) {
            val kind = if (type == "dm") "dm" else "channel"
            val row = """{"kind":"$kind","channelId":"c-$type","channelName":"n","channelType":"$type","unreadCount":1}"""
            val snapshot = parseSharedInboxSnapshot("""{"items":[$row],"hasMore":false}""")
            val item = snapshot?.items?.singleOrNull()
            if (item == null || item.channelKind == null) failures += "$type: row=$item"
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun messageSenderAndMessageTypesMap() {
        val failures = mutableListOf<String>()
        for (type in values("message_sender_types")) {
            val kind = type.strictMemberKindOrNull("contract", {}, MemberKindWireContract.MessageSender)
            if (kind == null) failures += "sender $type: null"
        }
        for (type in values("message_types")) {
            if (type.strictMessageTypeOrNull("contract", {}) == null) failures += "message type $type: null"
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun taskStatusesMapOrFallBackToALoggedUnknown() {
        val failures = mutableListOf<String>()
        for (status in values("task_statuses")) {
            val logs = mutableListOf<String>()
            when (status.strictTaskStatusOrNull("contract", logs::add)) {
                null -> failures += "$status: null"
                TaskStatus.Unknown ->
                    if (logs.none { it.startsWith("projection_value_unknown") }) failures += "$status: Unknown without a log"
                else -> Unit
            }
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun notificationSurfaceKindsReachTheUser() {
        val failures = mutableListOf<String>()
        for (kind in values("notification_surface_kinds") + "carrier-pigeon") {
            val logs = mutableListOf<String>()
            val event = RealtimeEventMapper(diagnosticsLogger = logs::add).map(RealtimeSocketEvents.NotificationPush, notificationPush(kind))
            when (event) {
                is NotificationPushReceived -> Unit
                is NotificationNoticeReceived ->
                    if (!logs.loggedFallback()) failures += "$kind: notice without a logged fallback"
                else -> failures += "$kind: dropped logs=$logs"
            }
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun runtimeProviderKindsMap() {
        val failures = mutableListOf<String>()
        for ((domain, runtime) in ProviderDomains) {
            for (kind in values(domain)) {
                val provider = runBlocking { agentDetailProvider(runtime, kind) }
                if (provider == null && kind !in ProviderKindsPendingFix) failures += "$runtime/$kind: no provider"
            }
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun runtimeAuthRuntimesMap() {
        val unknown = values("runtime_auth_runtimes").filter { RuntimeAuthRuntime.fromWire(it) == null }
        assertEquals(emptyList(), unknown)
    }

    @Test
    fun sidebarPinnedKindsSurviveARealtimeReplace() {
        val failures = mutableListOf<String>()
        for (kind in values("sidebar_pinned_kinds")) {
            val event =
                RealtimeEventMapper(diagnosticsLogger = {}).map(
                    RealtimeSocketEvents.PinnedUpdated,
                    """{"serverId":"s1","pinned":[{"kind":"$kind","id":"x1"}],"pinnedVersion":3}""",
                )
            if ((event as? PerReceiverPinnedChanged)?.pinned?.size != 1) failures += "$kind: $event"
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun agentActivityStatesMap() {
        val failures = mutableListOf<String>()
        for (state in values("agent_activity_states")) {
            val event =
                RealtimeEventMapper(diagnosticsLogger = {}).map(
                    RealtimeSocketEvents.AgentActivityState,
                    """{"agentId":"a1","state":"$state","serverSeq":1}""",
                )
            if (event !is MemberPresenceUpdated) failures += "$state: $event"
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun objectKindsMap() {
        val failures = mutableListOf<String>()
        for (kind in values("object_kinds")) {
            val logs = mutableListOf<String>()
            val row = Json.parseToJsonElement(objectRow(kind))
            if (parseActivityObjectInboxItem(row, logs::add) == null) failures += "$kind: dropped logs=$logs"
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun everyServerSocketEventIsSubscribedOrDeliberatelyNot() {
        val subscribed = RealtimeSocketEvents.ServerWireEvents.toSet()
        val undecided =
            values("socket_events").filter { event ->
                event !in subscribed && event !in SocketEventsNotSubscribed && event !in SocketEventsNotServerToClient
            }
        assertEquals(emptyList(), undecided, "subscribe to these, or record why mobile does not (SocketEventsNotSubscribed)")
    }

    @Test
    fun aDroppedRealtimeEventIsLogged() {
        val logs = mutableListOf<String>()
        val event = RealtimeEventMapper(diagnosticsLogger = logs::add).map("brand:new-event", """{"serverId":"s1"}""")
        assertEquals(null, event)
        assertEquals(
            listOf("realtime_event_dropped eventName=brand:new-event reason=unmapped keys=serverid"),
            logs.filter { it.startsWith("realtime_event_dropped") },
        )
    }

    // ── fixtures ────────────────────────────────────────────────────────────────

    private fun savedBody(type: String): String =
        """
        {"saved":[{"messageId":"m-$type","channelId":"c-$type","channelName":"n","channelType":"$type","content":"x",
          "senderType":"user","senderId":"u1","senderName":"Artin","createdAt":"2026-11-01T00:00:00Z","savedAt":"2026-11-02T00:00:00Z"}],
         "hasMore":false}
        """.trimIndent()

    private fun searchBody(type: String): String =
        """
        {"results":[{"id":"m-$type","seq":1,"channelId":"c-$type","channelName":"n","channelType":"$type",
          "parentChannelId":"p1","parentChannelName":"general","parentChannelType":"channel",
          "senderId":"u1","senderType":"user","senderName":"Artin","content":"x","snippet":"x","createdAt":"2026-11-01T00:00:00Z"}],
         "hasMore":false}
        """.trimIndent()

    private fun genericInboxRow(kind: String): String =
        """{"kind":"$kind","channelId":"c-$kind","channelName":"n","channelType":"channel","unreadCount":1}"""

    private fun notificationPush(kind: String): String =
        """
        {"serverId":"s1","kind":"$kind","channelId":"c1","threadId":"t1","parentChannelId":"c1","parentMessageId":"pm1",
         "messageId":"m1","title":"T","body":"B","tag":"tag"}
        """.trimIndent()

    private fun objectRow(kind: String): String =
        """
        {"kind":"object","objectKind":"$kind","objectId":"o1","summary":null,
         "home":{"kind":"channel","label":"general","channelId":"c1"},
         "latestEvent":{"eventKind":"comment","reason":"following","actor":{"type":"user","id":"u2","name":"kirby"},"at":"2026-10-26T10:00:00.000Z"},
         "unreadCount":1,"hasMention":false,"isFollowing":true,"lastActivityAt":"2026-10-26T10:00:00.000Z","doneFrontierSeq":"1"}
        """.trimIndent()

    private suspend fun agentDetailProvider(runtime: String, kind: String): Any? {
        val body =
            """
            {"id":"agent-1","name":"A","runtimeConfig":{"version":1,"runtime":"$runtime",
              "provider":{"kind":"$kind","apiUrl":"https://provider.invalid","apiKey":"k","providerId":"deepseek",
                "baseUrl":"https://gateway.invalid","connectionId":"11111111-1111-4111-8111-111111111111"},
              "model":{"kind":"preset","id":"m"},"mode":{"kind":"default"}}}
            """.trimIndent()
        val transport =
            Proxy.newProxyInstance(RaftAgentsApi::class.java.classLoader, arrayOf(RaftAgentsApi::class.java)) { proxy, method, args ->
                when {
                    method.declaringClass == Any::class.java ->
                        when (method.name) {
                            "equals" -> proxy === args?.firstOrNull()
                            "hashCode" -> System.identityHashCode(proxy)
                            else -> "ServerEnumContractTest.AgentsApi"
                        }
                    // `suspend fun agent(agentId)`: answering without suspending returns the value directly.
                    method.name == "agent" -> HomeNetworkResponse(code = 200, bodyText = body)
                    else -> error("unused: ${method.name}")
                }
            } as RaftAgentsApi
        val result = RaftAgentsApiClient(transport).agent(MemberId("agent-1"))
        return (result as? DataResult.Content)?.data?.runtimeProvider
    }

    private companion object {
        val CheckedDomains =
            listOf(
                "channel_types",
                "inbox_kinds",
                "inbox_channel_types",
                "message_sender_types",
                "message_types",
                "task_statuses",
                "notification_surface_kinds",
                "runtime_provider_kinds_claude",
                "runtime_provider_kinds_pi",
                "runtime_provider_kinds_builtin",
                "runtime_auth_runtimes",
                "sidebar_pinned_kinds",
                "agent_activity_states",
                "object_kinds",
                "socket_events",
            )

        /** The runtime each provider-kind domain is scoped to (slock `*RuntimeProviderConfig`). */
        val ProviderDomains =
            listOf(
                "runtime_provider_kinds_claude" to "claude",
                "runtime_provider_kinds_pi" to "pi",
                "runtime_provider_kinds_builtin" to "builtin",
            )

        /**
         * Known gaps being fixed on their own branch; allowed to map to null until then (the check
         * keeps passing once they map). Remove an entry once its fix is on main.
         * - `connection`: agent/lagoon/silent-drop-runtime-connection (adds RuntimeProviderConfig.Connection).
         */
        val ProviderKindsPendingFix = setOf("connection")

        /** One row of each Activity kind the app maps by its own arm (anything else: generic channel row). */
        val InboxSampleRows =
            mapOf(
                "channel" to """{"kind":"channel","channelId":"c1","channelName":"general","channelType":"channel","unreadCount":1}""",
                "dm" to """{"kind":"dm","channelId":"d1","channelName":"Artin","channelType":"dm","unreadCount":1}""",
                "thread" to
                    """{"kind":"thread","threadChannelId":"t1","parentChannelId":"c1","parentChannelName":"general","parentChannelType":"channel","parentMessageId":"m1","unreadCount":1}""",
                "task" to
                    """{"kind":"task","threadChannelId":"conv-1","parentMessageId":null,"parentChannelId":"c1","parentChannelName":"general","parentChannelType":"channel","taskId":"task-1","taskNumber":7,"taskTitle":"Ship","taskStatus":"in_progress","unreadCount":1}""",
                "object" to
                    """{"kind":"object","objectKind":"artifact","objectId":"o1","summary":null,"home":{"kind":"channel","label":"general","channelId":"c1"},"latestEvent":{"eventKind":"comment","reason":"following","actor":{"type":"user","id":"u2","name":"kirby"},"at":"2026-10-26T10:00:00.000Z"},"unreadCount":1,"hasMention":false,"isFollowing":true,"lastActivityAt":"2026-10-26T10:00:00.000Z","doneFrontierSeq":"1"}""",
            )

        /** Names the extractor finds that are not server → client socket events. */
        val SocketEventsNotServerToClient =
            mapOf(
                "connection" to "socket.io server-side connection handler",
                "finish" to "HTTP response stream event",
                "agent:activity:subscribe" to "client → server",
                "agent:activity:unsubscribe" to "client → server",
                "read_progress:watch" to "client → server",
                "read_progress:unwatch" to "client → server",
                "desktop:input-presence" to "client → server (desktop app)",
                "presence:activity" to "client → server (RealtimeSocketEvents.PresenceActivity is emitted, not received)",
                "features:server-changed" to "server-internal pub/sub channel",
            )

        /**
         * Server → client events mobile deliberately does not subscribe to, with the reason. An
         * entry that mobile later subscribes to is fine (the check only asks for a decision).
         */
        val SocketEventsNotSubscribed =
            mapOf(
                "actor:updated" to "joint-room agent name/avatar push; mobile reloads (audit W4, follow-up)",
                "agent:archive:updated" to "archive view is read on open",
                "agent:lifecycle" to "covered by agent:updated / agent:activity",
                "agent:migration-updated" to "migration page reads on open",
                "channel:authority-updated" to "covered by channel:members-updated",
                "external-inbox-delivered" to "external-agent delivery receipt, no mobile surface",
                "feedback_report:session_record" to "desktop feedback recorder only",
                "link_card.operation_changed" to "being added on agent/lagoon/silent-drop-* (audit W3)",
                "machine:online" to "daemon-facing; mobile uses machine:status",
                "message_display_prefs:updated" to "read on open (audit C1, follow-up)",
                "notification:desktop" to "desktop app notifications",
                "permission-updates:changed" to "permission page reads on open",
                "reaction_viewer:updated" to "covered by message:updated",
                "read_progress:detail_changed" to "web read-progress panel only",
                "read_progress:updated" to "web read-progress panel only",
                "read_progress:watched" to "web read-progress panel only",
                "rooms:join_failed" to "room joins are handled by the native transport host",
                "rooms:joined" to "handled by the native transport host (RealtimeSocketEvents.RoomsJoined), not the mapper",
                "seat-join-blocks:changed" to "billing seats page reads on open",
                "server_order:updated" to "server switcher order reads on open",
            )
    }
}
