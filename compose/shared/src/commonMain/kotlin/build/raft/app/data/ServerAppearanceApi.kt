package build.raft.app.data

import build.raft.app.model.RaftServerThemeLook
import build.raft.app.model.ServerAppearanceUpdated
import build.raft.app.model.ServerId
import build.raft.app.model.isFreeThemePlan
import build.raft.app.model.raftThemeChoiceFromWire
import build.raft.designsystem.tokens.RaftThemeId
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * The member's own look on one server: theme and decorations (design/foundations/themes.md;
 * themes are chosen per member per server).
 *
 * Contract source: botiverse/slock@aed2ba714cea38507191289c3c17669b46b25b6f
 *  - routes/servers.ts:1971-1984 `GET /api/servers/:id/appearance-settings` →
 *    `{ theme: RaftThemeId | null, themeDecor: boolean }` (404 `{ error: "Server not found" }`
 *    when the caller is not a member).
 *  - routes/servers.ts:1986-2017 `PATCH` with `{ theme?, themeDecor? }` → the saved
 *    `{ theme, themeDecor }`. 400 `{ error: "invalid_theme" }` for anything that is not one of
 *    `RAFT_THEME_IDS` (null included: Raft Yellow is sent as `"raft"`, stored as null),
 *    400 when `themeDecor` is not a boolean or neither field is sent, 404 for a non-member.
 *    The server saves a Pro theme on any plan (services/serverService.ts:1741-1745); the picker
 *    offers the upgrade instead (web ServerThemeSection.tsx, `openUpgradeDialog("pro_theme")`),
 *    so the Pro gate is client side here too ([ServerAppearanceUpdateResult.ProRequired]).
 *  - On success the server emits `appearance:updated` `{ serverId, theme, themeDecor }` to the
 *    member's `user:<id>` room only (routes/servers.ts:2010-2012).
 *  - `GET /api/servers` carries the same `theme` / `themeDecor` per server
 *    (services/serverService.ts:305-307); see [StoredAuthServer.theme].
 */

private val serverAppearanceJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** `GET`/`PATCH .../appearance-settings` response, services/serverService.ts `MemberAppearance`. */
@Serializable
data class ServerAppearanceWireDto(
    val theme: String? = null,
    val themeDecor: Boolean = true,
)

/**
 * `PATCH .../appearance-settings` body. [theme] is always a wire id: the server rejects `null`
 * with `invalid_theme`, so the default goes as `"raft"`.
 */
@Serializable
data class ServerAppearancePatchBody(
    val theme: String,
    val themeDecor: Boolean,
)

/** The member's stored choice on one server; [theme] null is the default, Raft Yellow. */
data class ServerAppearance(
    val theme: RaftThemeId?,
    val themeDecor: Boolean,
) {
    /** The wire id as the server reports it (`null` for Raft Yellow), the form [StoredAuthServer.theme] keeps. */
    internal val themeWire: String?
        get() = theme?.wire
}

fun encodeServerAppearancePatchBody(theme: RaftThemeId?, themeDecor: Boolean): String =
    serverAppearanceJson.encodeToString(
        ServerAppearancePatchBody.serializer(),
        ServerAppearancePatchBody(theme = (theme ?: RaftThemeId.Raft).wire, themeDecor = themeDecor),
    )

/**
 * `PATCH .../appearance-settings` body that turns the decorations on or off and leaves the
 * theme alone (web ServerThemeSection `save({ themeDecor })`).
 */
@Serializable
data class ServerAppearanceDecorPatchBody(
    val themeDecor: Boolean,
)

fun encodeServerAppearanceDecorPatchBody(themeDecor: Boolean): String =
    serverAppearanceJson.encodeToString(
        ServerAppearanceDecorPatchBody.serializer(),
        ServerAppearanceDecorPatchBody(themeDecor = themeDecor),
    )

/** Parses the appearance-settings body; an unknown theme id reads as the default. */
fun parseSharedServerAppearance(bodyText: String): ServerAppearance? =
    runCatching {
        val wire = serverAppearanceJson.decodeFromString(ServerAppearanceWireDto.serializer(), bodyText)
        ServerAppearance(theme = raftThemeChoiceFromWire(wire.theme), themeDecor = wire.themeDecor)
    }.getOrNull()

@Serializable
private data class ServerAppearanceErrorBody(val error: String? = null)

internal fun parseSharedServerAppearanceErrorCode(bodyText: String?): String? =
    bodyText
        ?.takeIf { it.isNotBlank() }
        ?.let { text ->
            runCatching {
                serverAppearanceJson.decodeFromString(ServerAppearanceErrorBody.serializer(), text).error
            }.getOrNull()
        }
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

/** Server `{ error }` for an unknown theme id (routes/servers.ts:1990-1992). */
const val SERVER_APPEARANCE_INVALID_THEME_ERROR: String = "invalid_theme"

sealed interface ServerAppearanceLoadResult {
    data class Loaded(val appearance: ServerAppearance) : ServerAppearanceLoadResult

    /** 404: the caller is not a member of the server (or it is gone). */
    data object NotFound : ServerAppearanceLoadResult

    /** [code] 0 when the request never got an answer. */
    data class Failed(val code: Int, val serverMessage: String?) : ServerAppearanceLoadResult
}

sealed interface ServerAppearanceUpdateResult {
    data class Saved(val appearance: ServerAppearance) : ServerAppearanceUpdateResult

    /**
     * A Pro theme on a server whose stored plan is Free (web `isFreePlan`: no plan or `"free"`).
     * Nothing was sent; the caller offers the upgrade (web gate `pro_theme`).
     */
    data object ProRequired : ServerAppearanceUpdateResult

    /** 400 `invalid_theme`: the server does not know the theme id. */
    data object InvalidTheme : ServerAppearanceUpdateResult

    /** 404: the caller is not a member of the server (or it is gone). */
    data object NotFound : ServerAppearanceUpdateResult

    /** [code] 0 when the request never got an answer. */
    data class Failed(val code: Int, val serverMessage: String?) : ServerAppearanceUpdateResult
}

/** Shared API orchestration for the member's per-server theme. */
interface ServerAppearanceRepository {
    suspend fun load(serverId: ServerId): ServerAppearanceLoadResult

    /** Saves [theme] (null = Raft Yellow, sent as `"raft"`) and [themeDecor] for [serverId]. */
    suspend fun update(serverId: ServerId, theme: RaftThemeId?, themeDecor: Boolean): ServerAppearanceUpdateResult

    /** Saves only [themeDecor] for [serverId]; the PATCH body carries no theme. */
    suspend fun updateDecor(serverId: ServerId, themeDecor: Boolean): ServerAppearanceUpdateResult
}

/**
 * Network [ServerAppearanceRepository]. Every successful answer is written onto the stored
 * server (account-guarded: an answer computed for one account never lands on another), so the
 * server list, the observable look and the next cold start all follow it.
 */
class NetworkServerAppearanceRepository(
    private val networkClient: RaftNetworkClient,
    private val sessionStore: RaftAuthSessionStore,
) : ServerAppearanceRepository {
    override suspend fun load(serverId: ServerId): ServerAppearanceLoadResult {
        val accountId = sessionStore.readSession()?.user?.id
        val response =
            runCatchingPreservingCancellation {
                networkClient.request(
                    priority = DataRequestPriority.Visible,
                    path = appearancePath(serverId),
                    serverIdOverride = serverId,
                )
            }.getOrNull() ?: return ServerAppearanceLoadResult.Failed(code = 0, serverMessage = null)
        if (response.code == 404) return ServerAppearanceLoadResult.NotFound
        if (!response.isSuccessful) {
            return ServerAppearanceLoadResult.Failed(response.code, response.errorCode())
        }
        val appearance =
            response.bodyText?.let(::parseSharedServerAppearance)
                ?: return ServerAppearanceLoadResult.Failed(response.code, serverMessage = null)
        accountId?.let { sessionStore.applyStoredServerAppearance(it, serverId.value, appearance.themeWire, appearance.themeDecor) }
        return ServerAppearanceLoadResult.Loaded(appearance)
    }

    override suspend fun update(
        serverId: ServerId,
        theme: RaftThemeId?,
        themeDecor: Boolean,
    ): ServerAppearanceUpdateResult {
        val session = sessionStore.readSession()
        val plan = session?.servers?.firstOrNull { it.id == serverId.value }?.plan
        if (theme?.isPro == true && isFreeThemePlan(plan)) return ServerAppearanceUpdateResult.ProRequired
        return patch(session, serverId, encodeServerAppearancePatchBody(theme, themeDecor))
    }

    override suspend fun updateDecor(serverId: ServerId, themeDecor: Boolean): ServerAppearanceUpdateResult =
        patch(sessionStore.readSession(), serverId, encodeServerAppearanceDecorPatchBody(themeDecor))

    private suspend fun patch(
        session: StoredAuthSession?,
        serverId: ServerId,
        bodyText: String,
    ): ServerAppearanceUpdateResult {
        val response =
            runCatchingPreservingCancellation {
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = appearancePath(serverId),
                    method = "PATCH",
                    serverIdOverride = serverId,
                    bodyText = bodyText,
                )
            }.getOrNull() ?: return ServerAppearanceUpdateResult.Failed(code = 0, serverMessage = null)
        if (!response.isSuccessful) {
            val errorCode = response.errorCode()
            return when {
                response.code == 400 && errorCode == SERVER_APPEARANCE_INVALID_THEME_ERROR ->
                    ServerAppearanceUpdateResult.InvalidTheme
                response.code == 404 -> ServerAppearanceUpdateResult.NotFound
                else -> ServerAppearanceUpdateResult.Failed(response.code, errorCode)
            }
        }
        val saved =
            response.bodyText?.let(::parseSharedServerAppearance)
                ?: return ServerAppearanceUpdateResult.Failed(response.code, serverMessage = null)
        session?.user?.id?.let { accountId ->
            sessionStore.applyStoredServerAppearance(accountId, serverId.value, saved.themeWire, saved.themeDecor)
        }
        return ServerAppearanceUpdateResult.Saved(saved)
    }

    private fun appearancePath(serverId: ServerId): String = "servers/${serverId.value}/appearance-settings"

    private fun HomeNetworkResponse.errorCode(): String? =
        parseSharedServerAppearanceErrorCode(bodyText ?: errorBodyText)
}

/**
 * Writes the member's theme onto stored server [serverId] of account [accountId]. A no-op when
 * the session now belongs to another account, the server is not in the list, or nothing changes.
 * [theme] is the wire id (`null` = Raft Yellow); [themeDecor] null keeps the stored value.
 * Returns whether the stored server changed.
 */
internal suspend fun RaftAuthSessionStore.applyStoredServerAppearance(
    accountId: String,
    serverId: String,
    theme: String?,
    themeDecor: Boolean?,
): Boolean {
    var changed = false
    updateMetadata(expectedAccountId = accountId) { latest ->
        val index = latest.servers.indexOfFirst { it.id == serverId }
        if (index < 0) return@updateMetadata latest
        val current = latest.servers[index]
        val next = current.copy(theme = theme, themeDecor = themeDecor ?: current.themeDecor)
        if (next == current) return@updateMetadata latest
        changed = true
        latest.copy(servers = latest.servers.mapIndexed { position, server -> if (position == index) next else server })
    }
    return changed
}

/**
 * Applies an accepted `appearance:updated` for the account the realtime grant belongs to
 * ([principalId] is the grant's `session.user.id`).
 */
internal suspend fun RaftAuthSessionStore.applyRealtimeServerAppearance(
    principalId: String,
    event: ServerAppearanceUpdated,
): Boolean =
    applyStoredServerAppearance(
        accountId = principalId,
        serverId = event.serverId.value,
        theme = event.theme,
        themeDecor = event.themeDecor,
    )

/**
 * The persisted session, read once on collection and again after every [wakes] signal (the
 * auth store's mutation events are wake signals, not state). Reads run one at a time in signal
 * order, so the latest write is always the last value.
 */
internal fun raftStoredSessionFlow(
    wakes: Flow<Any?>,
    readSession: suspend () -> StoredAuthSession?,
): Flow<StoredAuthSession?> =
    channelFlow {
        val pending = Channel<Unit>(Channel.CONFLATED)
        // Subscribe before the first read so no mutation between the two is missed.
        launch(start = CoroutineStart.UNDISPATCHED) { wakes.collect { pending.trySend(Unit) } }
        pending.trySend(Unit)
        for (signal in pending) send(readSession())
    }

/**
 * The look of the selected server: [selectedServerId] when set, else the session's own current
 * server. Raft Yellow with decorations on while signed out or when the server is unknown.
 * Reads only the stored session, so a cold start has the theme before any network answer.
 */
internal fun raftServerThemeLookFlow(
    sessions: Flow<StoredAuthSession?>,
    selectedServerId: Flow<String?>,
): Flow<RaftServerThemeLook> =
    combine(sessions, selectedServerId) { session, selected -> raftSessionThemeLook(session, selected) }
        .distinctUntilChanged()

/**
 * The look of [selectedServerId] when set, else of [session]'s own current server; Raft Yellow
 * with decorations on while signed out or when the server is unknown.
 */
internal fun raftSessionThemeLook(session: StoredAuthSession?, selectedServerId: String?): RaftServerThemeLook {
    val serverId = selectedServerId?.trim()?.takeIf { it.isNotEmpty() } ?: session?.currentServerId
    return session?.servers?.firstOrNull { it.id == serverId }?.themeLook() ?: RaftServerThemeLook.Default
}
