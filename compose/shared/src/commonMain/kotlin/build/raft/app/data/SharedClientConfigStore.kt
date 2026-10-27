package build.raft.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * `GET /api/public/client-config` (botiverse/slock #11421, merged into feat/workspace-artifacts at
 * 4e686d18e; `packages/server/src/routes/publicClientConfig.ts`, verified at 8a44f57be): deploy-level
 * client config. No sign-in needed, the same answer with or without credentials,
 * `Cache-Control: public, max-age=300`. Additive only: fields are never renamed or removed.
 *
 * ```json
 * { "appHosts": ["app.raft.build", "..."] }
 * ```
 *
 * `appHosts`: Raft's own app hostnames on this deployment (server `platform/links.ts` `appHosts()`:
 * the canonical host, APP_URL's, Raft's earlier hosts, APP_PERMALINK_HOSTS). Read as the web's
 * `store/raftAppHosts.ts` reads them: anything that is not a bare hostname is dropped, duplicates
 * once, the server's order kept; no list, or none left, is no answer.
 */
@Serializable
data class ClientConfigWireDto(
    val appHosts: List<String>? = null,
)

/** The parsed client config. */
data class RaftClientConfig(val appHosts: List<String>)

private val clientConfigJson = Json { ignoreUnknownKeys = true }

/** Every deployment's list has the canonical host; the web falls back to it the same way. */
val CLIENT_CONFIG_FALLBACK_APP_HOSTS: List<String> = listOf("app.raft.build")

private val CLIENT_CONFIG_HOSTNAME = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$")

/** A `URL.hostname`-shaped value (lowercase, punycode, no port, path or scheme), else null. */
internal fun clientConfigHostnameOrNull(value: String): String? =
    value.takeIf { it.length <= 253 && CLIENT_CONFIG_HOSTNAME.matches(it) }

/** Reads a client-config answer; null when it can't be read or carries no usable host. */
fun parseRaftClientConfig(bodyText: String?): RaftClientConfig? {
    val wire = bodyText?.let { runCatching { clientConfigJson.decodeFromString(ClientConfigWireDto.serializer(), it) }.getOrNull() } ?: return null
    val hosts = wire.appHosts.orEmpty().mapNotNull(::clientConfigHostnameOrNull).distinct()
    return if (hosts.isEmpty()) null else RaftClientConfig(hosts)
}

internal interface RaftClientConfigApi {
    suspend fun read(): HomeNetworkResponse
}

internal class NetworkRaftClientConfigApi(private val networkClient: RaftNetworkClient) : RaftClientConfigApi {
    override suspend fun read(): HomeNetworkResponse =
        networkClient.request(priority = DataRequestPriority.Background, path = "public/client-config")
}

/** Declared before any store reads it (a member would still be null in the initializer). */
private val clientConfigCacheSerializer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))

private fun normalizedEnvironment(value: String): String = value.trim().trimEnd('/')

internal const val CLIENT_CONFIG_CACHE_KEY: String = "clientConfig.appHosts.v1"

/**
 * The client config of the current API environment: read on app start ([start]) and again on
 * every environment change, kept per environment on disk.
 *
 * [appHosts]: the last good answer for this environment (memory or disk); a failed read keeps it.
 * Never fetched on this environment: [CLIENT_CONFIG_FALLBACK_APP_HOSTS]. An answer for an
 * environment that is no longer current is stored for it but not published.
 */
class SharedClientConfigStore internal constructor(
    private val api: RaftClientConfigApi,
    private val preferences: SharedPreferenceStore,
    /** The current API environment's id (its API base URL). */
    private val currentEnvironment: () -> String,
    /** Emits the environment id on every change (and its current value first). */
    private val environmentChanges: Flow<String>,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit = {},
) {
    private val mutableAppHosts = MutableStateFlow(cachedHosts(normalizedEnvironment(currentEnvironment())) ?: CLIENT_CONFIG_FALLBACK_APP_HOSTS)
    val appHosts: StateFlow<List<String>> = mutableAppHosts.asStateFlow()

    private var started: Job? = null

    /** Idempotent: follows the environment from now on, reading the config for each one. */
    fun start() {
        if (started != null) return
        started =
            scope.launch {
                environmentChanges.map(::normalizedEnvironment).distinctUntilChanged().collect { environment ->
                    mutableAppHosts.value = cachedHosts(environment) ?: CLIENT_CONFIG_FALLBACK_APP_HOSTS
                    refresh(environment)
                }
            }
    }

    /** One read for [environment]; exposed for tests and for an explicit retry. */
    internal suspend fun refresh(environment: String = normalizedEnvironment(currentEnvironment())) {
        val response =
            try {
                api.read()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                logger("client_config read_failed reason=${error::class.simpleName ?: "unknown"}")
                return
            }
        val config = response.takeIf { it.isSuccessful }?.let { parseRaftClientConfig(it.bodyText) }
        if (config == null) {
            logger("client_config read_failed code=${response.code}")
            return
        }
        writeCachedHosts(environment, config.appHosts)
        if (normalizedEnvironment(currentEnvironment()) == environment) {
            mutableAppHosts.value = config.appHosts
            logger("client_config applied hosts=${config.appHosts.size}")
        } else {
            logger("client_config stale_environment_ignored")
        }
    }

    private fun readCache(): Map<String, List<String>> =
        preferences.getString(CLIENT_CONFIG_CACHE_KEY)
            ?.let { runCatching { clientConfigJson.decodeFromString(clientConfigCacheSerializer, it) }.getOrNull() }
            .orEmpty()

    private fun cachedHosts(environment: String): List<String>? =
        readCache()[normalizedEnvironment(environment)]?.mapNotNull(::clientConfigHostnameOrNull)?.takeIf { it.isNotEmpty() }

    private fun writeCachedHosts(environment: String, hosts: List<String>) {
        val next = readCache() + (environment to hosts)
        preferences.putString(CLIENT_CONFIG_CACHE_KEY, clientConfigJson.encodeToString(clientConfigCacheSerializer, next))
    }
}
