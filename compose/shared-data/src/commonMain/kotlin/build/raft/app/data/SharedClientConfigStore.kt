package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 *
 * `privacyMode`: `true` only under `RAFT_PRIVACY_MODE` (server `publicClientConfig.ts`, slock
 * staging 72740e52e; docs/observability/privacy-mode.md); absent otherwise, never `false`.
 */
@Serializable
data class ClientConfigWireDto(
    val appHosts: List<String>? = null,
    val privacyMode: Boolean? = null,
)

/** The parsed client config. */
data class RaftClientConfig(val appHosts: List<String>)

private val clientConfigJson = Json { ignoreUnknownKeys = true }

/** Every deployment's list has the canonical host; the web falls back to it the same way. */
val CLIENT_CONFIG_FALLBACK_APP_HOSTS: List<String> = listOf("app.raft.build")

private val CLIENT_CONFIG_HOSTNAME = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$")

/** A `URL.hostname`-shaped value (lowercase, punycode, no port, path or scheme), else null. */
@RaftCoreInternalApi fun clientConfigHostnameOrNull(value: String): String? =
    value.takeIf { it.length <= 253 && CLIENT_CONFIG_HOSTNAME.matches(it) }

/** Reads a client-config answer; null when it can't be read or carries no usable host. */
fun parseRaftClientConfig(bodyText: String?): RaftClientConfig? {
    val wire = bodyText?.let { runCatching { clientConfigJson.decodeFromString(ClientConfigWireDto.serializer(), it) }.getOrNull() } ?: return null
    val hosts = wire.appHosts.orEmpty().mapNotNull(::clientConfigHostnameOrNull).distinct()
    return if (hosts.isEmpty()) null else RaftClientConfig(hosts)
}

/**
 * The privacy mode a client-config read says (privacy-mode.md): [RaftPrivacyMode.On] only for a 2xx
 * answer carrying `privacyMode: true`; any other answer, or a failed read, is [RaftPrivacyMode.Off].
 */
fun raftClientConfigPrivacyMode(response: HomeNetworkResponse?): RaftPrivacyMode {
    val body = response?.takeIf { it.isSuccessful }?.bodyText ?: return RaftPrivacyMode.Off
    val wire = runCatching { clientConfigJson.decodeFromString(ClientConfigWireDto.serializer(), body) }.getOrNull()
    return if (wire?.privacyMode == true) RaftPrivacyMode.On else RaftPrivacyMode.Off
}

@RaftCoreInternalApi interface RaftClientConfigApi {
    /** Reads [environment]'s config (its API base URL, as [SharedClientConfigStore] normalizes it). */
    suspend fun read(environment: String): HomeNetworkResponse
}

/** `<API base>/public/client-config` for [environment] (an API base URL, with or without its trailing `/`). */
@RaftCoreInternalApi fun clientConfigUrl(environment: String): String = normalizedEnvironment(environment) + "/public/client-config"

/**
 * A public read: no session, no server (the authenticated request answers 401 without a request
 * while signed out, so the read on an environment switch, which signs out, never went out and
 * nothing read it again after sign-in until the next app start), on the environment asked for.
 */
@RaftCoreInternalApi class NetworkRaftClientConfigApi(private val networkClient: RaftNetworkClient) : RaftClientConfigApi {
    override suspend fun read(environment: String): HomeNetworkResponse =
        networkClient.requestRawAbsoluteUrl(priority = DataRequestPriority.Background, url = clientConfigUrl(environment))
}

/** Declared before any store reads it (a member would still be null in the initializer). */
private val clientConfigCacheSerializer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))

private fun normalizedEnvironment(value: String): String = value.trim().trimEnd('/')

@RaftCoreInternalApi const val CLIENT_CONFIG_CACHE_KEY: String = "clientConfig.appHosts.v1"

/**
 * The client config of the current API environment: read on app start ([start]) and again on
 * every environment change, kept per environment on disk.
 *
 * [appHosts]: the last good answer for this environment (memory or disk); a failed read keeps it.
 * Never fetched on this environment: [CLIENT_CONFIG_FALLBACK_APP_HOSTS]. An answer for an
 * environment that is no longer current is stored for it but not published.
 *
 * Privacy mode ([publishPrivacyMode], process-wide [RaftTelemetryPrivacy]): unknown from each
 * environment's start until its read answers (never taken from the disk cache, so a server that
 * turned it on since is never sent to on a stale "off"); then on or off as
 * [raftClientConfigPrivacyMode] reads it. A read with no answer after
 * [RAFT_PRIVACY_MODE_READ_TIMEOUT_MILLIS] counts as off, as on the web; a later "on" still wins.
 * Each publish says how it was decided ([RaftPrivacyAnswer]): a successful response is
 * [RaftPrivacyAnswer.On] / [RaftPrivacyAnswer.Off] (no field: off); a failed read or the timeout is
 * [RaftPrivacyAnswer.Failed], which telemetry takes as off but deferrable uploads as unknown.
 */
class SharedClientConfigStore @RaftCoreInternalApi constructor(
    private val api: RaftClientConfigApi,
    private val preferences: SharedPreferenceStore,
    /** The current API environment's id (its API base URL). */
    private val currentEnvironment: () -> String,
    /** Emits the environment id on every change (and its current value first). */
    private val environmentChanges: Flow<String>,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit = {},
    private val publishPrivacyMode: (environment: String, answer: RaftPrivacyAnswer) -> Unit = RaftTelemetryPrivacy::publish,
) {
    private val mutableAppHosts =
        MutableStateFlow(
            cachedHosts(normalizedEnvironment(currentEnvironment()))?.also(RaftClientConfigAppHosts::publish)
                ?: CLIENT_CONFIG_FALLBACK_APP_HOSTS,
        )
    val appHosts: StateFlow<List<String>> = mutableAppHosts.asStateFlow()

    private var started: Job? = null

    /** The environment whose privacy mode has been answered (null while unknown). */
    private var privacyAnsweredFor: String? = null

    /** Idempotent: follows the environment from now on, reading the config for each one. */
    fun start() {
        if (started != null) return
        started =
            scope.launch {
                environmentChanges.map(::normalizedEnvironment).distinctUntilChanged().collect { environment ->
                    val cached = cachedHosts(environment)
                    // Another environment's hosts never count here; the fallback adds nothing readers lack.
                    RaftClientConfigAppHosts.publish(cached.orEmpty())
                    mutableAppHosts.value = cached ?: CLIENT_CONFIG_FALLBACK_APP_HOSTS
                    privacyAnsweredFor = null
                    publishPrivacyMode(environment, RaftPrivacyAnswer.Pending)
                    val timeout =
                        scope.launch {
                            delay(RAFT_PRIVACY_MODE_READ_TIMEOUT_MILLIS)
                            if (privacyAnsweredFor != environment) answerPrivacyMode(environment, RaftPrivacyAnswer.Failed, "timeout")
                        }
                    try {
                        refresh(environment)
                    } finally {
                        timeout.cancel()
                    }
                }
            }
    }

    /** One read for [environment]; exposed for tests and for an explicit retry. */
    @RaftCoreInternalApi suspend fun refresh(environment: String = normalizedEnvironment(currentEnvironment())) {
        val response =
            try {
                api.read(environment)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                logger("client_config read_failed reason=${error::class.simpleName ?: "unknown"}")
                answerPrivacyMode(environment, RaftPrivacyAnswer.Failed, "read_failed")
                return
            }
        val answer =
            when {
                !response.isSuccessful -> RaftPrivacyAnswer.Failed
                raftClientConfigPrivacyMode(response) == RaftPrivacyMode.On -> RaftPrivacyAnswer.On
                else -> RaftPrivacyAnswer.Off
            }
        answerPrivacyMode(environment, answer, if (response.isSuccessful) "answer" else "read_failed")
        val config = response.takeIf { it.isSuccessful }?.let { parseRaftClientConfig(it.bodyText) }
        if (config == null) {
            logger("client_config read_failed code=${response.code}")
            return
        }
        writeCachedHosts(environment, config.appHosts)
        if (normalizedEnvironment(currentEnvironment()) == environment) {
            mutableAppHosts.value = config.appHosts
            RaftClientConfigAppHosts.publish(config.appHosts)
            logger("client_config applied hosts=${config.appHosts.size}")
        } else {
            logger("client_config stale_environment_ignored")
        }
    }

    private fun answerPrivacyMode(environment: String, answer: RaftPrivacyAnswer, reason: String) {
        if (normalizedEnvironment(currentEnvironment()) != environment) return
        privacyAnsweredFor = environment
        publishPrivacyMode(environment, answer)
        logger(
            "client_config privacy_mode=${if (answer.telemetryMode == RaftPrivacyMode.On) "on" else "off"} " +
                "answered=${answer != RaftPrivacyAnswer.Failed} reason=$reason"
        )
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
