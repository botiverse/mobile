package ai.slock.android.core.data.auth

import ai.slock.android.core.data.repository.ChannelSnapshot
import ai.slock.android.core.data.repository.CreateServerRequest
import ai.slock.android.core.data.repository.HomeSnapshot
import ai.slock.android.core.data.repository.InboxSnapshot
import ai.slock.android.core.data.repository.ServerListSnapshot
import ai.slock.android.core.data.repository.ServerRepository
import ai.slock.android.core.data.repository.SettingsSnapshot
import ai.slock.android.core.model.ServerId
import ai.slock.android.core.state.EmptyReason
import ai.slock.android.core.state.PermissionBlocker
import ai.slock.android.core.state.ScreenError
import ai.slock.compose.data.DataResult
import ai.slock.compose.data.SharedAuthDataSource
import ai.slock.compose.data.SharedAuthErrorCode
import ai.slock.compose.data.SharedAuthResult
import ai.slock.compose.data.SlockAuthApi
import ai.slock.compose.data.StoredAuthSession

class AuthServerRepository(
    private val store: AuthSessionStore,
    private val authRepository: AuthRepository,
    api: SlockAuthApi = KtorAuthRepository.createAuthApi()
) : ServerRepository {
    private val sharedAuthDataSource =
        SharedAuthDataSource(
            api = api,
            sessionStore = ComposeAuthSessionStoreAdapter(store)
        )

    override suspend fun getServers(): DataResult<ServerListSnapshot> {
        val session = store.readSession() ?: return authRequired()
        val restored = authRepository.currentUser()
        val latest = store.readSession() ?: session

        if (latest.servers.isEmpty()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        if (restored is AuthResult.Failure && restored.code == AuthErrorCode.Network) {
            return DataResult.Error(ScreenError(code = "servers_network", retryable = true))
        }

        return DataResult.Content(latest.toServerListSnapshot())
    }

    override suspend fun selectServer(serverId: ServerId): DataResult<ServerListSnapshot> =
        when (val result = sharedAuthDataSource.selectServer(serverId.value)) {
            is SharedAuthResult.Success -> DataResult.Content(result.data.toAndroidAuthSession().toServerListSnapshot())
            SharedAuthResult.Unauthenticated -> authRequired()
            is SharedAuthResult.Failure -> DataResult.Error(ScreenError(code = "server_not_found", retryable = false))
        }

    override suspend fun createServer(request: CreateServerRequest): DataResult<ServerListSnapshot> {
        val name = request.name.trim()
        if (name.isBlank()) {
            return DataResult.Error(ScreenError(code = "server_name_required", retryable = false))
        }
        return when (
            val result =
                sharedAuthDataSource.createServer(
                    name = name,
                    slug = request.slug,
                    region = request.region
                )
        ) {
            is SharedAuthResult.Success -> DataResult.Content(result.data.toAndroidAuthSession().toServerListSnapshot())
            SharedAuthResult.Unauthenticated -> authRequired()
            is SharedAuthResult.Failure -> result.toCreateServerError()
        }
    }

    override suspend fun getHomeSnapshot(serverId: ServerId): DataResult<HomeSnapshot> =
        DataResult.Error(ScreenError(code = "server_home_not_wired", retryable = false))

    override suspend fun getInbox(serverId: ServerId): DataResult<InboxSnapshot> =
        DataResult.Error(ScreenError(code = "server_inbox_not_wired", retryable = false))

    override suspend fun getChannels(serverId: ServerId): DataResult<ChannelSnapshot> =
        DataResult.Error(ScreenError(code = "server_channels_not_wired", retryable = false))

    override suspend fun getSettings(serverId: ServerId): DataResult<SettingsSnapshot> =
        DataResult.Error(ScreenError(code = "server_settings_not_wired", retryable = false))

    private fun AuthSession.toServerListSnapshot(): ServerListSnapshot {
        val summaries = servers.map { it.toServerSummary() }
        return ServerListSnapshot(
            servers = summaries,
            selectedServer = summaries.firstOrNull { it.id.value == selectedServerId }
        )
    }

    private fun SharedAuthResult.Failure.toCreateServerError(): DataResult<ServerListSnapshot> =
        when (code) {
            SharedAuthErrorCode.Validation ->
                DataResult.Error(ScreenError(code = "server_validation", retryable = false))
            SharedAuthErrorCode.InvalidCredentials ->
                authRequired()
            SharedAuthErrorCode.Network ->
                DataResult.Error(ScreenError(code = "server_create_network", retryable = true))
            SharedAuthErrorCode.Server ->
                DataResult.Error(ScreenError(code = "server_create_unavailable", retryable = true))
            SharedAuthErrorCode.Unknown ->
                DataResult.Error(ScreenError(code = "server_create_failed", retryable = true))
        }

    private fun <T> authRequired(): DataResult<T> =
        DataResult.NoPermission(
            PermissionBlocker(
                code = "auth_required",
                requiredCapability = "auth_session"
            )
        )
}
