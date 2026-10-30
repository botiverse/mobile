package build.raft.app.data

import build.raft.app.model.NotificationLevelChanged
import build.raft.app.model.ServerId
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Notification settings API (slock `docs/contracts/notifications.md` §2; types in
 * `packages/shared/src/notifications.ts`, `NotificationAccountSettings`):
 *
 *  - `GET me/notification-settings` → `{settings, servers: [{serverId, level}]}`; no stored row
 *    reads as the defaults (`version: 0`).
 *  - `PATCH me/notification-settings` with any subset of `pausedUntil` (ISO, or null to resume),
 *    `quietHours` (all four fields), `previewMode`, `sound`, `version` → `{settings}`; a stale
 *    `version` → `409 {"error": "version_conflict", "settings": <current>}`.
 *  - `PUT notification-levels` `{scope, id, level}` → `{scope, id, serverId, level, effective,
 *    muted, prefsVersion}`; `server` rejects `default`.
 *  - `GET notification-levels` (server header) → `{serverId, server, channels: [{id, level}],
 *    threads: [{id, level}]}`, only explicit channel / thread values.
 *
 * Field names are exact camelCase; no aliases.
 */
private val notificationSettingsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
data class RaftNotificationQuietHours(
    val enabled: Boolean = false,
    /** Minutes after local midnight, 0-1439. start > end spans midnight. */
    val startMinute: Int = 22 * 60,
    val endMinute: Int = 8 * 60,
    /** IANA time zone. */
    val timeZone: String = "UTC"
)

/** `NotificationAccountSettings`: account-wide, applied by the server to every device. */
@Serializable
data class RaftNotificationAccountSettings(
    val pausedUntil: String? = null,
    val quietHours: RaftNotificationQuietHours = RaftNotificationQuietHours(),
    val previewMode: String = PUSH_PREVIEW_MODE_DEFAULT,
    val sound: Boolean = true,
    val version: Int = 0
) {
    /** The preview mode; an unknown value fails closed to hidden. */
    val preview: PushPreviewMode
        get() = PushPreviewMode.fromWire(previewMode) ?: PushPreviewMode.Hidden

    fun pausedUntilEpochMillis(): Long? =
        pausedUntil?.let { raw -> runCatching { Instant.parse(raw).toEpochMilliseconds() }.getOrNull() }

    fun isPaused(nowEpochMillis: Long): Boolean = (pausedUntilEpochMillis() ?: return false) > nowEpochMillis
}

@Serializable
private data class WireNotificationServerLevel(val serverId: String = "", val level: String? = null)

@Serializable
private data class WireNotificationSettingsResponse(
    val settings: RaftNotificationAccountSettings? = null,
    val servers: List<WireNotificationServerLevel> = emptyList()
)

data class RaftNotificationSettingsSnapshot(
    val settings: RaftNotificationAccountSettings,
    /** Server level per server the user is a member of. */
    val serverLevels: Map<String, RaftNotificationLevel>
)

internal fun parseRaftNotificationAccountSettingsObject(obj: JsonObject): RaftNotificationAccountSettings? =
    runCatching { notificationSettingsJson.decodeFromJsonElement(RaftNotificationAccountSettings.serializer(), obj) }
        .getOrNull()

fun parseRaftNotificationSettingsResponse(bodyText: String): RaftNotificationSettingsSnapshot? =
    runCatching {
        val wire = notificationSettingsJson.decodeFromString(WireNotificationSettingsResponse.serializer(), bodyText)
        val settings = wire.settings ?: return null
        RaftNotificationSettingsSnapshot(
            settings = settings,
            serverLevels =
                wire.servers.mapNotNull { row ->
                    val id = row.serverId.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    RaftNotificationLevel.fromWire(row.level)?.let { id to it }
                }.toMap()
        )
    }.getOrNull()

/** The `settings` of a PATCH answer, or of a 409 `version_conflict` body. */
fun parseRaftNotificationAccountSettingsEnvelope(bodyText: String?): RaftNotificationAccountSettings? =
    bodyText?.let { text ->
        runCatching { notificationSettingsJson.decodeFromString(WireNotificationSettingsResponse.serializer(), text).settings }
            .getOrNull()
    }

/** A PATCH: only the set fields are sent. [resume] sends `pausedUntil: null`. */
data class RaftNotificationAccountSettingsPatch(
    val pausedUntil: String? = null,
    val resume: Boolean = false,
    val quietHours: RaftNotificationQuietHours? = null,
    val previewMode: PushPreviewMode? = null,
    val sound: Boolean? = null
) {
    /** The settings as they read once this patch applied, for an optimistic update. */
    fun appliedTo(settings: RaftNotificationAccountSettings): RaftNotificationAccountSettings =
        settings.copy(
            pausedUntil = if (resume) null else pausedUntil ?: settings.pausedUntil,
            quietHours = quietHours ?: settings.quietHours,
            previewMode = previewMode?.wireName ?: settings.previewMode,
            sound = sound ?: settings.sound
        )
}

fun buildRaftNotificationAccountSettingsPatchBody(patch: RaftNotificationAccountSettingsPatch, version: Int?): String =
    buildJsonObject {
        when {
            patch.resume -> put("pausedUntil", JsonNull)
            patch.pausedUntil != null -> put("pausedUntil", patch.pausedUntil)
        }
        patch.quietHours?.let {
            put("quietHours", notificationSettingsJson.encodeToJsonElement(RaftNotificationQuietHours.serializer(), it))
        }
        patch.previewMode?.let { put("previewMode", it.wireName) }
        patch.sound?.let { put("sound", it) }
        version?.let { put("version", it) }
    }.toString()

@Serializable
private data class NotificationLevelRequestBody(val scope: String, val id: String, val level: String)

fun buildRaftNotificationLevelBody(scope: RaftNotificationLevelScope, id: String, level: RaftNotificationLevelSetting): String =
    notificationSettingsJson.encodeToString(
        NotificationLevelRequestBody.serializer(),
        NotificationLevelRequestBody(scope = scope.wireName, id = id, level = level.wireName)
    )

@Serializable
private data class WireNotificationLevelResponse(
    val scope: String? = null,
    val id: String? = null,
    val serverId: String? = null,
    val level: String? = null,
    val effective: String? = null,
    val muted: Boolean = false,
    val prefsVersion: Long? = null
)

data class RaftNotificationLevelUpdate(
    val scope: RaftNotificationLevelScope,
    val id: String,
    val serverId: String,
    val level: RaftNotificationLevelSetting,
    val effective: RaftNotificationLevel?,
    val muted: Boolean,
    val prefsVersion: Long?
)

fun parseRaftNotificationLevelResponse(bodyText: String): RaftNotificationLevelUpdate? =
    runCatching {
        val wire = notificationSettingsJson.decodeFromString(WireNotificationLevelResponse.serializer(), bodyText)
        RaftNotificationLevelUpdate(
            scope = RaftNotificationLevelScope.fromWire(wire.scope) ?: return null,
            id = wire.id?.takeIf { it.isNotBlank() } ?: return null,
            serverId = wire.serverId?.takeIf { it.isNotBlank() } ?: return null,
            level = RaftNotificationLevelSetting.fromWire(wire.level) ?: return null,
            effective = RaftNotificationLevel.fromWire(wire.effective),
            muted = wire.muted,
            prefsVersion = wire.prefsVersion
        )
    }.getOrNull()

@Serializable
private data class WireNotificationLevelEntry(val id: String = "", val level: String? = null)

@Serializable
private data class WireServerNotificationLevels(
    val serverId: String? = null,
    val server: String? = null,
    val channels: List<WireNotificationLevelEntry> = emptyList(),
    val threads: List<WireNotificationLevelEntry> = emptyList()
)

/** The explicit levels of one server; a channel or thread missing from the maps is `default`. */
data class RaftServerNotificationLevels(
    val serverId: String,
    val server: RaftNotificationLevel = RAFT_DEFAULT_SERVER_NOTIFICATION_LEVEL,
    val channels: Map<String, RaftNotificationLevelSetting> = emptyMap(),
    val threads: Map<String, RaftNotificationLevelSetting> = emptyMap(),
    /** The newest `prefsVersion` applied per scope id; an update not newer than it is dropped. */
    val versions: Map<String, Long> = emptyMap(),
    /** [server] came from the server (not the default of a server only known by a channel update). */
    val serverLevelKnown: Boolean = false
) {
    fun channelSetting(channelId: String): RaftNotificationLevelSetting =
        channels[channelId] ?: RaftNotificationLevelSetting.Default

    fun threadSetting(threadId: String): RaftNotificationLevelSetting =
        threads[threadId] ?: RaftNotificationLevelSetting.Default

    fun with(scope: RaftNotificationLevelScope, id: String, setting: RaftNotificationLevelSetting): RaftServerNotificationLevels =
        when (scope) {
            RaftNotificationLevelScope.Server ->
                copy(server = setting.explicitLevel ?: server, serverLevelKnown = serverLevelKnown || setting.explicitLevel != null)
            RaftNotificationLevelScope.Channel -> copy(channels = channels.withSetting(id, setting))
            RaftNotificationLevelScope.Thread -> copy(threads = threads.withSetting(id, setting))
        }

    private fun Map<String, RaftNotificationLevelSetting>.withSetting(
        id: String,
        setting: RaftNotificationLevelSetting
    ): Map<String, RaftNotificationLevelSetting> =
        if (setting == RaftNotificationLevelSetting.Default) this - id else this + (id to setting)
}

fun parseRaftServerNotificationLevels(bodyText: String, requestedServerId: String): RaftServerNotificationLevels? =
    runCatching {
        val wire = notificationSettingsJson.decodeFromString(WireServerNotificationLevels.serializer(), bodyText)
        val serverId = wire.serverId?.takeIf { it.isNotBlank() } ?: requestedServerId
        if (serverId != requestedServerId) return null
        fun List<WireNotificationLevelEntry>.toSettings(): Map<String, RaftNotificationLevelSetting> =
            mapNotNull { entry ->
                val id = entry.id.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                RaftNotificationLevelSetting.fromWire(entry.level)
                    ?.takeIf { it != RaftNotificationLevelSetting.Default }
                    ?.let { id to it }
            }.toMap()
        RaftServerNotificationLevels(
            serverId = serverId,
            server = RaftNotificationLevel.fromWire(wire.server) ?: RAFT_DEFAULT_SERVER_NOTIFICATION_LEVEL,
            channels = wire.channels.toSettings(),
            threads = wire.threads.toSettings(),
            serverLevelKnown = RaftNotificationLevel.fromWire(wire.server) != null
        )
    }.getOrNull()

/** The network half; every call names its priority. */
internal class RaftNotificationSettingsApi(private val networkClient: RaftNetworkClient) {
    suspend fun accountSettings(priority: DataRequestPriority): HomeNetworkResponse =
        networkClient.request(priority = priority, path = "me/notification-settings")

    suspend fun patchAccountSettings(bodyText: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "me/notification-settings",
            method = "PATCH",
            bodyText = bodyText
        )

    suspend fun putLevel(serverId: String, bodyText: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "notification-levels",
            method = "PUT",
            serverIdOverride = ServerId(serverId),
            bodyText = bodyText
        )

    suspend fun serverLevels(serverId: String, priority: DataRequestPriority): HomeNetworkResponse =
        networkClient.request(priority = priority, path = "notification-levels", serverIdOverride = ServerId(serverId))
}

private suspend fun <T> notificationRequest(
    block: suspend () -> HomeNetworkResponse,
    contractCode: String,
    parse: (String) -> T?
): DataResult<T> =
    try {
        val response = block()
        val body = response.bodyText
        when {
            !response.isSuccessful ->
                DataResult.Error(ScreenError(code = "notification_settings_http_${response.code}", retryable = response.code >= 500 || response.code == 0))
            body.isNullOrBlank() -> DataResult.Error(ScreenError(code = contractCode, retryable = false))
            else -> parse(body)?.let { DataResult.Content(it) } ?: DataResult.Error(ScreenError(code = contractCode, retryable = false))
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        DataResult.Error(ScreenError(code = "notification_settings_network_failed", retryable = true))
    }

/** Every server's level in one place, for the badge sum and the level pickers. */
private fun currentNotificationApi(): RaftNotificationSettingsApi? =
    RaftSharedStoreRegistry.currentOrNull()?.networkClient?.let(::RaftNotificationSettingsApi)

/**
 * Process store of the explicit notification levels, per server (contract §1.1). Fed by
 * `GET notification-levels`, by this app's own `PUT`s and by `notification_prefs:updated`; the
 * channel and thread menus read it to show "Default (<inherited>)" and the effective level.
 */
object RaftNotificationLevelStore {
    private val mutableState = MutableStateFlow<Map<String, RaftServerNotificationLevels>>(emptyMap())
    val state: StateFlow<Map<String, RaftServerNotificationLevels>> = mutableState.asStateFlow()

    /** Replaced in tests. */
    @Volatile
    internal var apiProvider: () -> RaftNotificationSettingsApi? = ::currentNotificationApi

    fun levelsFor(serverId: String): RaftServerNotificationLevels =
        mutableState.value[serverId] ?: RaftServerNotificationLevels(serverId)

    /** `GET notification-levels` for [serverId]; keeps the held copy on failure. */
    suspend fun refresh(serverId: String, priority: DataRequestPriority = DataRequestPriority.Visible): Boolean {
        val api = apiProvider() ?: return false
        val result =
            notificationRequest(
                block = { api.serverLevels(serverId, priority) },
                contractCode = "notification_levels_contract_mismatch",
                parse = { parseRaftServerNotificationLevels(it, serverId) }
            )
        val fetched = (result as? DataResult.Content)?.data ?: return false
        mutableState.update { all -> all + (serverId to fetched.copy(versions = all[serverId]?.versions.orEmpty())) }
        return true
    }

    /**
     * `PUT notification-levels`, applied at once and rolled back when the server refuses. Returns the
     * server's answer (its `effective` includes the mute cap).
     */
    suspend fun setLevel(
        serverId: String,
        scope: RaftNotificationLevelScope,
        id: String,
        level: RaftNotificationLevelSetting
    ): DataResult<RaftNotificationLevelUpdate> {
        if (scope == RaftNotificationLevelScope.Server && level == RaftNotificationLevelSetting.Default) {
            return DataResult.Error(ScreenError(code = "notification_level_server_default", retryable = false))
        }
        val api = apiProvider() ?: return DataResult.Error(ScreenError(code = "notification_settings_unavailable", retryable = true))
        val previous = levelsFor(serverId)
        mutableState.update { all -> all + (serverId to previous.with(scope, id, level)) }
        val result =
            notificationRequest(
                block = { api.putLevel(serverId, buildRaftNotificationLevelBody(scope, id, level)) },
                contractCode = "notification_level_contract_mismatch",
                parse = ::parseRaftNotificationLevelResponse
            )
        when (result) {
            is DataResult.Content -> {
                val update = result.data
                mutableState.update { all ->
                    val current = all[serverId] ?: previous
                    val versions =
                        update.prefsVersion?.let { current.versions + (update.id to it) } ?: current.versions
                    all + (serverId to current.with(update.scope, update.id, update.level).copy(versions = versions))
                }
            }
            else -> mutableState.update { all -> all + (serverId to previous) }
        }
        return result
    }

    /** `notification_prefs:updated` with `type` channel / thread. Dropped when not newer. */
    fun applyRealtime(event: NotificationLevelChanged) {
        val scope = RaftNotificationLevelScope.fromWire(event.scope) ?: return
        val setting = RaftNotificationLevelSetting.fromWire(event.level) ?: return
        applyVersioned(event.serverId.value, scope, event.scopeId, setting, event.prefsVersion)
    }

    /** `notification_prefs:updated` with the server's `serverPushMode` (type server or legacy). */
    fun applyServerLevel(serverId: String, level: RaftNotificationLevel, prefsVersion: Long?) {
        applyVersioned(serverId, RaftNotificationLevelScope.Server, serverId, RaftNotificationLevelSetting.of(level), prefsVersion)
    }

    /** The server levels of `GET me/notification-settings` (every server the user is in). */
    fun applyServerLevels(levels: Map<String, RaftNotificationLevel>) {
        if (levels.isEmpty()) return
        mutableState.update { all ->
            var next = all
            for ((serverId, level) in levels) {
                next = next + (serverId to (next[serverId] ?: RaftServerNotificationLevels(serverId)).copy(server = level, serverLevelKnown = true))
            }
            next
        }
    }

    private fun applyVersioned(
        serverId: String,
        scope: RaftNotificationLevelScope,
        id: String,
        setting: RaftNotificationLevelSetting,
        prefsVersion: Long?
    ) {
        mutableState.update { all ->
            val current = all[serverId] ?: RaftServerNotificationLevels(serverId)
            val held = current.versions[id]
            if (prefsVersion != null && held != null && prefsVersion <= held) return@update all
            val versions = prefsVersion?.let { current.versions + (id to it) } ?: current.versions
            all + (serverId to current.with(scope, id, setting).copy(versions = versions))
        }
    }

    /** Sign-out / account switch. */
    fun clear() {
        mutableState.value = emptyMap()
    }
}

data class RaftNotificationAccountSettingsState(
    val settings: RaftNotificationAccountSettings? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val errorCode: String? = null
)

/**
 * Process store of the account-wide settings (pause, quiet hours, preview, sound; contract §2.1).
 * Also mirrors the preview mode into the device's preview preference
 * ([KeyValuePushPreviewModePreferenceStore]), which
 * the Android poster and the in-app banners read for text this app builds itself.
 */
object RaftNotificationAccountSettingsStore {
    private val mutableState = MutableStateFlow(RaftNotificationAccountSettingsState())
    val state: StateFlow<RaftNotificationAccountSettingsState> = mutableState.asStateFlow()
    private val writeMutex = Mutex()

    /** The account the held state belongs to; a different signed-in account clears both stores first. */
    @Volatile
    var ownerUserId: String? = null

    @Volatile
    internal var apiProvider: () -> RaftNotificationSettingsApi? = ::currentNotificationApi

    @Volatile
    internal var previewCache: (PushPreviewMode) -> Unit = { mode ->
        runCatching { raftSharedPreferenceStore().putString(PUSH_PREVIEW_MODE_PREFERENCE_KEY, mode.wireName) }
    }

    suspend fun load(priority: DataRequestPriority = DataRequestPriority.Visible): Boolean {
        val api = apiProvider() ?: return false
        mutableState.update { it.copy(loading = true) }
        val result =
            notificationRequest(
                block = { api.accountSettings(priority) },
                contractCode = "notification_settings_contract_mismatch",
                parse = ::parseRaftNotificationSettingsResponse
            )
        return when (result) {
            is DataResult.Content -> {
                accept(result.data.settings)
                RaftNotificationLevelStore.applyServerLevels(result.data.serverLevels)
                mutableState.update { it.copy(loading = false, errorCode = null) }
                true
            }
            is DataResult.Error -> {
                mutableState.update { it.copy(loading = false, errorCode = result.error.code) }
                false
            }
            else -> {
                mutableState.update { it.copy(loading = false) }
                false
            }
        }
    }

    /**
     * Applies [patch] at once and sends it with the held version. On `version_conflict` it adopts the
     * server's current settings and sends the same change once more on top of them; any other failure
     * rolls back to what was held.
     */
    suspend fun update(patch: RaftNotificationAccountSettingsPatch): Boolean =
        writeMutex.withLock {
            val api = apiProvider() ?: return@withLock false
            val before = mutableState.value.settings ?: RaftNotificationAccountSettings()
            mutableState.update { it.copy(settings = patch.appliedTo(before), saving = true, errorCode = null) }
            var version: Int? = before.version
            repeat(2) { attempt ->
                val response =
                    try {
                        api.patchAccountSettings(buildRaftNotificationAccountSettingsPatchBody(patch, version))
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        null
                    }
                if (response != null && response.isSuccessful) {
                    val saved = parseRaftNotificationAccountSettingsEnvelope(response.bodyText) ?: patch.appliedTo(before)
                    accept(saved, force = true)
                    mutableState.update { it.copy(saving = false) }
                    return@withLock true
                }
                if (response?.code == 409 && attempt == 0) {
                    val current = parseRaftNotificationAccountSettingsEnvelope(response.errorBodyText ?: response.bodyText)
                    if (current != null) {
                        version = current.version
                        mutableState.update { it.copy(settings = patch.appliedTo(current)) }
                        return@repeat
                    }
                }
                mutableState.update {
                    it.copy(settings = before, saving = false, errorCode = "notification_settings_save_failed")
                }
                return@withLock false
            }
            mutableState.update { it.copy(settings = before, saving = false, errorCode = "notification_settings_save_failed") }
            false
        }

    /** [update], or null when no account API is available (no signed-in shared runtime). */
    suspend fun updateOrNull(patch: RaftNotificationAccountSettingsPatch): Boolean? {
        if (apiProvider() == null) return null
        return update(patch)
    }

    /** `notification_prefs:updated` `type: "account"`. Dropped when its version is not newer. */
    fun applyRealtime(settings: RaftNotificationAccountSettings) {
        accept(settings)
    }

    private fun accept(settings: RaftNotificationAccountSettings, force: Boolean = false) {
        var accepted = false
        mutableState.update { current ->
            val held = current.settings
            if (!force && held != null && settings.version <= held.version && held.version > 0) {
                current
            } else {
                accepted = true
                current.copy(settings = settings)
            }
        }
        if (accepted) previewCache(settings.preview)
    }

    fun clear() {
        mutableState.value = RaftNotificationAccountSettingsState()
    }
}

/** The device's IANA time zone, for new quiet hours. */
fun raftCurrentTimeZoneId(): String = runCatching { TimeZone.currentSystemDefault().id }.getOrDefault("UTC")

/** The pause choices of the settings page (contract §2.1 `pausedUntil`; design §4.3). */
enum class RaftNotificationPauseOption {
    Minutes30,
    Hour1,
    Hours2,
    TomorrowMorning;

    /** The ISO `pausedUntil` this choice means at [nowEpochMillis] in [timeZoneId]. */
    fun pausedUntil(nowEpochMillis: Long, timeZoneId: String = raftCurrentTimeZoneId()): String {
        val now = Instant.fromEpochMilliseconds(nowEpochMillis)
        val until =
            when (this) {
                Minutes30 -> now + Duration.parse("30m")
                Hour1 -> now + Duration.parse("1h")
                Hours2 -> now + Duration.parse("2h")
                TomorrowMorning -> {
                    val zone = runCatching { TimeZone.of(timeZoneId) }.getOrDefault(TimeZone.UTC)
                    val tomorrow = now.toLocalDateTime(zone).date.plus(1, DateTimeUnit.DAY)
                    LocalDateTime(tomorrow, LocalTime(9, 0)).toInstant(zone)
                }
            }
        return until.toString()
    }
}

/** `HH:mm` of a minute of the day, for the quiet hours rows. */
fun raftMinuteOfDayLabel(minute: Int): String {
    val clean = ((minute % 1440) + 1440) % 1440
    val hours = clean / 60
    val minutes = clean % 60
    return "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}"
}

fun raftNowEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** "HH:mm" when [epochMillis] is today in the device zone, else "MM-dd HH:mm". */
fun raftPausedUntilLabel(epochMillis: Long, nowEpochMillis: Long = raftNowEpochMillis()): String {
    val zone = runCatching { TimeZone.currentSystemDefault() }.getOrDefault(TimeZone.UTC)
    val until = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone)
    val today = Instant.fromEpochMilliseconds(nowEpochMillis).toLocalDateTime(zone).date
    val time = raftMinuteOfDayLabel(until.hour * 60 + until.minute)
    if (until.date == today) return time
    // LocalDate.toString() is ISO yyyy-MM-dd.
    return "${until.date.toString().drop(5)} $time"
}

