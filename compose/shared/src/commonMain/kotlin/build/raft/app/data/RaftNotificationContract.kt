package build.raft.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * The unified notification contract, mobile side.
 *
 * Source of truth: botiverse/slock `docs/contracts/notifications.md` and
 * `packages/shared/src/notifications.ts` (pinned at the SHA in
 * `src/commonTest/resources/contract-cases/notification-levels/SOURCE_SHA`). The server decides who
 * is notified, on which device and with what text; the app only mirrors what it needs to show
 * (the effective level, the badge sum) and to handle a push (category, route, retraction).
 */

// ---------------------------------------------------------------------------
// Levels: one enum, three scopes (server, channel incl. DM, thread incl. task)
// ---------------------------------------------------------------------------

/** `all` > `mentions` > `none`; [rank] orders them for the mute cap. */
enum class RaftNotificationLevel(val wireName: String, internal val rank: Int) {
    All("all", rank = 2),
    Mentions("mentions", rank = 1),
    None("none", rank = 0);

    companion object {
        fun fromWire(value: String?): RaftNotificationLevel? = entries.firstOrNull { it.wireName == value }
    }
}

/** A stored channel / thread setting: an explicit level, or [Default] (inherit the next wider scope). */
enum class RaftNotificationLevelSetting(val wireName: String) {
    Default("default"),
    All("all"),
    Mentions("mentions"),
    None("none");

    /** The explicit level, or null for [Default]. */
    val explicitLevel: RaftNotificationLevel?
        get() = RaftNotificationLevel.fromWire(wireName)

    companion object {
        fun fromWire(value: String?): RaftNotificationLevelSetting? = entries.firstOrNull { it.wireName == value }

        fun of(level: RaftNotificationLevel): RaftNotificationLevelSetting =
            when (level) {
                RaftNotificationLevel.All -> All
                RaftNotificationLevel.Mentions -> Mentions
                RaftNotificationLevel.None -> None
            }
    }
}

enum class RaftNotificationLevelScope(val wireName: String) {
    Server("server"),
    Channel("channel"),
    Thread("thread");

    companion object {
        fun fromWire(value: String?): RaftNotificationLevelScope? = entries.firstOrNull { it.wireName == value }
    }
}

/** The server-wide default when nothing is stored (`server_members.server_push_mode` default). */
val RAFT_DEFAULT_SERVER_NOTIFICATION_LEVEL: RaftNotificationLevel = RaftNotificationLevel.All

/**
 * `resolveEffectiveLevel`: the most specific explicit setting wins (thread > channel > server), then
 * a channel (or DM) muted in Activity caps the level at `mentions`. Must agree with every case of
 * the shared fixture `notification-levels.json`.
 */
fun resolveEffectiveNotificationLevel(
    server: RaftNotificationLevel,
    channel: RaftNotificationLevelSetting? = null,
    thread: RaftNotificationLevelSetting? = null,
    muted: Boolean = false
): RaftNotificationLevel {
    val level = thread?.explicitLevel ?: channel?.explicitLevel ?: server
    if (muted && level.rank > RaftNotificationLevel.Mentions.rank) return RaftNotificationLevel.Mentions
    return level
}

/** What a `default` setting inherits at [scope] (the "Default (<inherited>)" label). */
fun inheritedNotificationLevel(
    scope: RaftNotificationLevelScope,
    server: RaftNotificationLevel,
    channel: RaftNotificationLevelSetting? = null
): RaftNotificationLevel =
    when (scope) {
        RaftNotificationLevelScope.Thread -> channel?.explicitLevel ?: server
        else -> server
    }

// ---------------------------------------------------------------------------
// Categories
// ---------------------------------------------------------------------------

enum class RaftNotificationCategory(val wireName: String) {
    DirectMessage("dm"),
    Mention("mention"),
    TaskAssigned("task_assigned"),
    AgentAttention("agent_attention"),
    Reminder("reminder"),
    TaskStatus("task_status"),
    Thread("thread"),
    Channel("channel"),
    SystemNotice("system_notice"),
    Test("test");

    companion object {
        fun fromWire(value: String?): RaftNotificationCategory? = entries.firstOrNull { it.wireName == value }
    }
}

/** The audience kinds of the fixture's `categoryDecisions`. */
enum class RaftNotificationAudience {
    /** dm, mention, task_assigned, agent_attention, reminder, personal task_status, system_notice. */
    Personal,
    Thread,
    FollowerTaskStatus,
    Channel
}

/** Whether an event of [audience] notifies at the effective [level] (before quiet hours / pause). */
fun raftNotificationNotifies(audience: RaftNotificationAudience, level: RaftNotificationLevel): Boolean =
    when (audience) {
        RaftNotificationAudience.Channel -> level == RaftNotificationLevel.All
        else -> level != RaftNotificationLevel.None
    }

/** The Android notification channel family a category posts on (contract §4.3). */
enum class RaftAndroidNotificationChannelKind {
    /** `slock-unread-dm-v2`, HIGH. */
    DirectMessage,

    /** `slock-unread-mention-v1`, HIGH: mentions and requests. */
    Mention,

    /** `slock-unread-channel-v1`, DEFAULT: everything else. */
    Channel
}

/**
 * The channel family of a push's `category`. Besides the contract values it accepts the wire values
 * this app's own local requests carried before the contract (`direct_message`, `mention`,
 * `channel_unread`, `general_unread`); anything else (an older server's `RAFT_MESSAGE`) posts on the
 * default channel unless [mentioned] says the message is about me.
 */
fun raftAndroidNotificationChannelKind(category: String?, mentioned: Boolean = false): RaftAndroidNotificationChannelKind {
    val parsed = RaftNotificationCategory.fromWire(category)
    return when (parsed) {
        RaftNotificationCategory.DirectMessage -> RaftAndroidNotificationChannelKind.DirectMessage
        RaftNotificationCategory.Mention,
        RaftNotificationCategory.TaskAssigned,
        RaftNotificationCategory.AgentAttention,
        RaftNotificationCategory.Reminder,
        RaftNotificationCategory.SystemNotice,
        RaftNotificationCategory.Test -> RaftAndroidNotificationChannelKind.Mention
        RaftNotificationCategory.TaskStatus,
        RaftNotificationCategory.Thread,
        RaftNotificationCategory.Channel -> RaftAndroidNotificationChannelKind.Channel
        null ->
            when {
                category == "direct_message" -> RaftAndroidNotificationChannelKind.DirectMessage
                category == "mention" || mentioned -> RaftAndroidNotificationChannelKind.Mention
                else -> RaftAndroidNotificationChannelKind.Channel
            }
    }
}

// ---------------------------------------------------------------------------
// Push message types and retraction
// ---------------------------------------------------------------------------

enum class RaftPushMessageType(val wireName: String) {
    Alert("alert"),
    Sync("sync"),
    Retract("retract");

    companion object {
        /**
         * The FCM / APNs `type`. A push without one is an alert from a server that predates the
         * contract; an unknown value is null (dropped, so a future type cannot post a blank alert).
         */
        fun fromWire(value: String?): RaftPushMessageType? =
            if (value.isNullOrBlank()) Alert else entries.firstOrNull { it.wireName == value }
    }
}

/** `<serverId>:<conversationId>`, the group of every notification of one conversation. */
fun raftNotificationGroupKey(serverId: String, conversationId: String): String =
    "${serverId.trim()}:${conversationId.trim()}"

/**
 * A read moved forward in [conversationId] of [serverId]: notifications of [groupKey] whose seq is
 * at most [throughSeq] are stale; a null [throughSeq] means all of the group.
 */
data class RaftNotificationRetract(
    val serverId: String,
    val groupKey: String,
    val conversationId: String,
    val throughSeq: Long?
) {
    /**
     * Whether the notification posted for [notificationGroupKey] with [notificationSeq] is covered.
     * A notification without a seq (posted from a pre-contract push or the local path) is covered
     * by any retraction of its group: the read moved past what it showed.
     */
    fun covers(notificationGroupKey: String?, notificationSeq: Long?): Boolean {
        if (notificationGroupKey.isNullOrBlank() || notificationGroupKey != groupKey) return false
        val through = throughSeq ?: return true
        val seq = notificationSeq ?: return true
        return seq <= through
    }
}

/**
 * FCM `{type: "retract", serverId, conversationId, groupKey, throughSeq, badge}` (all strings,
 * `throughSeq` empty = all). A missing groupKey is rebuilt from serverId and conversationId.
 */
fun parseRaftFcmNotificationRetract(data: Map<String, String>): RaftNotificationRetract? {
    val serverId = data["serverId"]?.trim().orEmpty()
    val conversationId = data["conversationId"]?.trim().orEmpty()
    if (serverId.isEmpty() || conversationId.isEmpty()) return null
    val groupKey = data["groupKey"]?.trim()?.takeIf { it.isNotEmpty() } ?: raftNotificationGroupKey(serverId, conversationId)
    val rawThrough = data["throughSeq"]?.trim().orEmpty()
    val throughSeq =
        if (rawThrough.isEmpty() || rawThrough == "null") null else rawThrough.toLongOrNull() ?: return null
    return RaftNotificationRetract(serverId, groupKey, conversationId, throughSeq)
}

private val raftNotificationJson = Json { ignoreUnknownKeys = true }

/** Socket `notification:retract {serverId, groupKey, conversationId, throughSeq: number | null}`. */
fun parseRaftNotificationRetractPayload(rawPayload: String?): RaftNotificationRetract? {
    val obj = rawPayload?.let { runCatching { raftNotificationJson.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return null
    return parseRaftNotificationRetractObject(obj)
}

internal fun parseRaftNotificationRetractObject(obj: JsonObject): RaftNotificationRetract? {
    val serverId = obj.notificationStringOrNull("serverId") ?: return null
    val groupKey = obj.notificationStringOrNull("groupKey") ?: return null
    val conversationId = obj.notificationStringOrNull("conversationId") ?: return null
    val through = obj["throughSeq"]
    val throughSeq =
        when {
            through == null || through is JsonNull -> null
            through is JsonPrimitive && !through.isString -> through.longOrNull ?: through.doubleOrNull?.toLong() ?: return null
            else -> return null
        }
    return RaftNotificationRetract(serverId, groupKey, conversationId, throughSeq)
}

/** The `badge` of an FCM / APNs push (sync, retract or alert); null when absent or not a count. */
fun parseRaftPushBadge(raw: String?): Int? =
    raw?.trim()?.toIntOrNull()?.takeIf { it >= 0 }

/** The message `seq` of an alert push; null when absent (not a message) or not a number. */
fun parseRaftPushSeq(raw: String?): Long? =
    raw?.trim()?.takeIf { it.isNotEmpty() && it != "null" }?.toLongOrNull()

// ---------------------------------------------------------------------------
// Routes
// ---------------------------------------------------------------------------

/** Where a notification tap lands (`NotificationRoute`, contract §4.5). */
sealed interface RaftNotificationRoute {
    val serverId: String

    data class Channel(override val serverId: String, val channelId: String, val messageId: String?) : RaftNotificationRoute

    data class DirectMessage(override val serverId: String, val channelId: String, val messageId: String?) : RaftNotificationRoute

    data class Thread(
        override val serverId: String,
        val parentChannelId: String,
        val parentChannelIsDm: Boolean,
        val parentMessageId: String,
        val messageId: String?
    ) : RaftNotificationRoute

    data class Task(
        override val serverId: String,
        val parentChannelId: String,
        val parentChannelIsDm: Boolean,
        val taskId: String,
        val messageId: String?
    ) : RaftNotificationRoute

    data class Agent(override val serverId: String, val agentId: String) : RaftNotificationRoute
}

/** The route of a push: the FCM `route` (a JSON string) or the APNs / socket `route` object as text. */
fun parseRaftNotificationRoute(rawJson: String?): RaftNotificationRoute? {
    val text = rawJson?.trim()?.takeIf { it.startsWith("{") } ?: return null
    val obj = runCatching { raftNotificationJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
    return parseRaftNotificationRouteObject(obj)
}

/** Closed validation of a route object (any of the five types); anything else is null. */
fun parseRaftNotificationRouteObject(obj: JsonObject): RaftNotificationRoute? {
    val serverId = obj.notificationStringOrNull("serverId") ?: return null
    val messageId = obj.notificationStringOrNull("messageId")
    fun parentIsDm(): Boolean? =
        when (obj.notificationStringOrNull("parentChannelType")) {
            "channel" -> false
            "dm" -> true
            else -> null
        }
    return when (obj.notificationStringOrNull("type")) {
        "channel" -> obj.notificationStringOrNull("channelId")?.let { RaftNotificationRoute.Channel(serverId, it, messageId) }
        "dm" -> obj.notificationStringOrNull("channelId")?.let { RaftNotificationRoute.DirectMessage(serverId, it, messageId) }
        "thread" -> {
            val parent = obj.notificationStringOrNull("parentChannelId") ?: return null
            val isDm = parentIsDm() ?: return null
            val parentMessageId = obj.notificationStringOrNull("parentMessageId") ?: return null
            RaftNotificationRoute.Thread(serverId, parent, isDm, parentMessageId, messageId)
        }
        "task" -> {
            val parent = obj.notificationStringOrNull("parentChannelId") ?: return null
            val isDm = parentIsDm() ?: return null
            val taskId = obj.notificationStringOrNull("taskId") ?: return null
            RaftNotificationRoute.Task(serverId, parent, isDm, taskId, messageId)
        }
        "agent" -> obj.notificationStringOrNull("agentId")?.let { RaftNotificationRoute.Agent(serverId, it) }
        else -> null
    }
}

/**
 * The legacy open fields (`kind`, `serverId`, `channelId`, `threadId`, `parentChannelId`,
 * `parentMessageId`, `messageId`, `taskId`) a route stands for, as the existing tap handlers read
 * them. Only non-null values are listed; an agent route has no conversation and yields only the
 * server, which opens the app in that workspace.
 */
fun RaftNotificationRoute.legacyOpenFields(): Map<String, String> =
    buildMap {
        put("serverId", serverId)
        when (val route = this@legacyOpenFields) {
            is RaftNotificationRoute.Channel -> {
                put("kind", "channel")
                put("channelId", route.channelId)
                route.messageId?.let { put("messageId", it) }
            }
            is RaftNotificationRoute.DirectMessage -> {
                put("kind", "dm")
                put("channelId", route.channelId)
                route.messageId?.let { put("messageId", it) }
            }
            is RaftNotificationRoute.Thread -> {
                put("kind", "thread")
                put("parentChannelId", route.parentChannelId)
                put("parentMessageId", route.parentMessageId)
                route.messageId?.let { put("messageId", it) }
            }
            is RaftNotificationRoute.Task -> {
                put("kind", if (route.parentChannelIsDm) "dm" else "channel")
                put("channelId", route.parentChannelId)
                put("taskId", route.taskId)
                route.messageId?.let { put("taskMessageId", it) }
            }
            is RaftNotificationRoute.Agent -> put("agentId", route.agentId)
        }
    }

// ---------------------------------------------------------------------------
// Badge
// ---------------------------------------------------------------------------

/**
 * The one badge number (contract §1.4): Activity unread summed over every server whose server level
 * is not `none`. A server with no known level counts (the default is `all`).
 */
fun raftNotificationBadgeCount(servers: Iterable<Pair<RaftNotificationLevel?, Int>>): Int {
    var total = 0
    for ((level, count) in servers) {
        if (level == RaftNotificationLevel.None) continue
        if (count > 0) total += count
    }
    return total
}

/**
 * The app badge across every server (contract §1.4), the same number APNs `aps.badge` carries:
 * [currentServerCount] is the selected server's live Activity unread, [otherServerCounts] the
 * per-server Activity unread of `servers/unread-summary` (object rows included). A server's level
 * is its known level, else none when the summary says `serverPushMuted`, else the default (counts).
 */
fun raftAppBadgeAcrossServers(
    currentServerId: String?,
    currentServerCount: Int,
    otherServerCounts: Map<String, Long>,
    serverPushMuted: Map<String, Boolean>,
    levels: Map<String, RaftNotificationLevel>
): Int {
    fun levelOf(serverId: String): RaftNotificationLevel? =
        levels[serverId] ?: if (serverPushMuted[serverId] == true) RaftNotificationLevel.None else null
    val current = currentServerId?.trim()?.takeIf { it.isNotEmpty() }
    val rows =
        buildList {
            if (current != null) add(levelOf(current) to currentServerCount)
            for ((serverId, count) in otherServerCounts) {
                if (serverId == current) continue
                add(levelOf(serverId) to count.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
            }
        }
    return raftNotificationBadgeCount(rows)
}

// ---------------------------------------------------------------------------
// Presence
// ---------------------------------------------------------------------------

/** Socket event every client sends (contract §5). */
const val RAFT_PRESENCE_ACTIVITY_EVENT = "presence:activity"

/** Server -> the reader's sockets: a conversation was read (contract §5). */
const val RAFT_NOTIFICATION_RETRACT_EVENT = "notification:retract"

/** Clients re-send `presence:activity` this often while connected. */
const val RAFT_PRESENCE_HEARTBEAT_MILLIS: Long = 60_000L

/**
 * `{"active": boolean, "idleSeconds": number | null}`. Mobile reports `active: true, idleSeconds: 0`
 * in the foreground and `active: false, idleSeconds: null` when going to the background.
 */
fun buildRaftPresenceActivityPayload(active: Boolean): String =
    if (active) """{"active":true,"idleSeconds":0}""" else """{"active":false,"idleSeconds":null}"""

// ---------------------------------------------------------------------------
// JSON helpers
// ---------------------------------------------------------------------------

private fun JsonObject.notificationStringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

// ---------------------------------------------------------------------------
// Dedupe across sockets
// ---------------------------------------------------------------------------

/** The newest [capacity] keys seen; thread-safe. */
class RaftRecentKeys(private val capacity: Int) {
    private val lock = kotlinx.atomicfu.locks.SynchronizedObject()
    private val keys = LinkedHashMap<String, Unit>()

    /** True the first time [key] is seen while it is among the newest [capacity]. */
    fun firstSeen(key: String): Boolean =
        kotlinx.atomicfu.locks.synchronized(lock) {
            if (keys.containsKey(key)) {
                keys.remove(key)
                keys[key] = Unit
                false
            } else {
                keys[key] = Unit
                while (keys.size > capacity) keys.remove(keys.keys.first())
                true
            }
        }
}

/**
 * Every socket of the user is in the user room, and `notification:push` is user-scoped, so the same
 * notification can arrive once per socket (two workspaces in two windows, multi-session runtimes).
 * Each consumer (tray, in-app banner) handles an envelope `id` once; an older server's push without
 * one is keyed by its message.
 */
object RaftNotificationPushDedupe {
    private const val CAPACITY = 256
    private val tray = RaftRecentKeys(CAPACITY)
    private val banner = RaftRecentKeys(CAPACITY)

    fun firstForTray(key: String): Boolean = tray.firstSeen(key)

    fun firstForBanner(key: String): Boolean = banner.firstSeen(key)
}

/** The dedupe key of a socket push: its envelope id, else its message. */
fun build.raft.app.model.NotificationPushReceived.raftDedupeKey(): String =
    notificationId ?: "message:${serverId.value}:${messageId.value}"

fun build.raft.app.model.NotificationNoticeReceived.raftDedupeKey(): String =
    notificationId ?: "notice:$groupKey:$title:$body"

/**
 * "Retracted through" per group: an alert that arrives after the retraction that covers it (FCM
 * HIGH vs NORMAL, socket vs push) is not posted. A numeric mark covers seq <= throughSeq; a mark
 * of "all", or an alert without seq, covers only what arrives within [lateGraceMillis] of the
 * retraction (a later one is a new message). Bounded and expiring.
 */
class RaftRetractWatermarks(
    private val capacity: Int = 256,
    private val ttlMillis: Long = 3L * 24L * 60L * 60L * 1000L,
    private val lateGraceMillis: Long = 60_000L
) {
    private data class Mark(val throughSeq: Long?, val recordedAtMillis: Long)

    private val lock = kotlinx.atomicfu.locks.SynchronizedObject()
    private val marks = LinkedHashMap<String, Mark>()

    fun record(retract: RaftNotificationRetract, nowMillis: Long) {
        kotlinx.atomicfu.locks.synchronized(lock) {
            val held = marks.remove(retract.groupKey)
            val through =
                when {
                    retract.throughSeq == null -> null
                    held?.throughSeq != null -> maxOf(held.throughSeq, retract.throughSeq)
                    else -> retract.throughSeq
                }
            marks[retract.groupKey] = Mark(through, nowMillis)
            while (marks.size > capacity) marks.remove(marks.keys.first())
        }
    }

    fun clear() {
        kotlinx.atomicfu.locks.synchronized(lock) { marks.clear() }
    }

    fun covers(groupKey: String?, seq: Long?, nowMillis: Long): Boolean {
        val key = groupKey?.takeIf { it.isNotBlank() } ?: return false
        return kotlinx.atomicfu.locks.synchronized(lock) {
            val mark = marks[key] ?: return@synchronized false
            if (nowMillis - mark.recordedAtMillis > ttlMillis) {
                marks.remove(key)
                return@synchronized false
            }
            val through = mark.throughSeq
            if (through != null && seq != null) seq <= through else nowMillis - mark.recordedAtMillis <= lateGraceMillis
        }
    }
}

// ---------------------------------------------------------------------------
// Banner copy: title / subtitle / body and their localization keys (contract §1.5)
// ---------------------------------------------------------------------------

/**
 * Keys of the fixed phrases in one notification's title / subtitle / body (slock
 * `packages/shared/src/notificationCopy.ts` `NotificationLocalization`; mobile socket
 * `localization`, FCM data `titleKey` / `titleArgs` / ...). Args are positional names and counts.
 * A part without a key is plain text; a client that does not know a key shows the English text.
 */
@kotlinx.serialization.Serializable
data class RaftNotificationLocalization(
    val titleKey: String? = null,
    val titleArgs: List<String> = emptyList(),
    val subtitleKey: String? = null,
    val subtitleArgs: List<String> = emptyList(),
    val bodyKey: String? = null,
    val bodyArgs: List<String> = emptyList()
)

/**
 * Reads a `localization` object the way the server's `parseNotificationLocalization` does: a part
 * whose key is not a non-empty string is dropped, args that are not all strings become empty; null
 * when no part is left.
 */
fun parseRaftNotificationLocalizationObject(value: JsonObject?): RaftNotificationLocalization? {
    value ?: return null
    fun key(part: String): String? =
        (value["${part}Key"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
    fun args(part: String): List<String> {
        val array = value["${part}Args"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        val items = array.map { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content }
        return if (items.all { it != null }) items.filterNotNull() else emptyList()
    }
    val titleKey = key("title")
    val subtitleKey = key("subtitle")
    val bodyKey = key("body")
    if (titleKey == null && subtitleKey == null && bodyKey == null) return null
    return RaftNotificationLocalization(
        titleKey = titleKey,
        titleArgs = if (titleKey != null) args("title") else emptyList(),
        subtitleKey = subtitleKey,
        subtitleArgs = if (subtitleKey != null) args("subtitle") else emptyList(),
        bodyKey = bodyKey,
        bodyArgs = if (bodyKey != null) args("body") else emptyList()
    )
}

/**
 * The title without its context: the mobile socket (and FCM to an app before key set 3) sends
 * `title · subtitle` (`formatPushSurfaceTitle`), and an app that shows [subtitle] on its own line
 * takes the part before ` · <subtitle>` (contract §1.5). Anything else is returned as is.
 */
fun raftNotificationTitleWithoutSubtitle(title: String, subtitle: String?): String {
    if (subtitle.isNullOrBlank()) return title
    val suffix = " · $subtitle"
    return if (title.length > suffix.length && title.endsWith(suffix)) title.dropLast(suffix.length) else title
}
