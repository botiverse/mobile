package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import build.raft.app.model.ServerId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Raft Agent Voice settings & Who asked (`#wg-mobile-sync:#95`).
// Wire contract verified against botiverse/slock:
//  - GET  /agents/:id/voice-settings  → { askable: boolean, callable: boolean } (404 → hidden)
//  - PUT  /agents/:id/voice-settings  → body { askable?: boolean, callable?: boolean } → { askable, callable }
//  - GET  /agents/:id/voice-questions → { questions: [{ id?, at, asker: { id, name } | null, app: { id?, name } | null }] } (404 → hidden)

private val agentVoiceJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

private val agentVoiceRequestJson = Json {
    explicitNulls = false
}

/** Whether people may ask this agent side questions and whether they may call it. */
@RaftCoreInternalApi
data class AgentVoiceSettings(
    val askable: Boolean,
    val callable: Boolean,
)

/** A current, non-retired, non-Guest server member who asked the agent a side question. */
@RaftCoreInternalApi
data class AgentVoiceQuestionAsker(
    val id: String,
    val name: String,
)

/** The App that asked the agent on the person's behalf. */
@RaftCoreInternalApi
data class AgentVoiceQuestionApp(
    val id: String? = null,
    val name: String,
)

/** One side-question audit entry (newest first); never carries the question or answer. */
@RaftCoreInternalApi
data class AgentVoiceQuestion(
    val id: String? = null,
    val at: String,
    val asker: AgentVoiceQuestionAsker? = null,
    val app: AgentVoiceQuestionApp? = null,
) {
    /** Stable key for UI lists (matches web `${question.at}-${question.asker?.id ?? "former"}` when id is absent). */
    val stableKey: String
        get() = id?.takeIf { it.isNotBlank() } ?: "$at-${asker?.id ?: "former"}"
}

@Serializable
private data class AgentVoiceSettingsDto(
    val askable: Boolean? = null,
    val callable: Boolean? = null,
)

@Serializable
private data class AgentVoiceSettingsUpdateRequestDto(
    val askable: Boolean? = null,
    val callable: Boolean? = null,
)

@Serializable
private data class AgentVoiceQuestionAskerDto(
    val id: String? = null,
    val name: String? = null,
)

@Serializable
private data class AgentVoiceQuestionAppDto(
    val id: String? = null,
    val name: String? = null,
)

@Serializable
private data class AgentVoiceQuestionDto(
    val id: String? = null,
    val at: String? = null,
    val asker: AgentVoiceQuestionAskerDto? = null,
    val app: AgentVoiceQuestionAppDto? = null,
)

@Serializable
private data class AgentVoiceQuestionsResponseDto(
    val questions: List<AgentVoiceQuestionDto>? = null,
)

@RaftCoreInternalApi
fun parseAgentVoiceSettings(bodyText: String): AgentVoiceSettings? =
    runCatching {
        val dto = agentVoiceJson.decodeFromString(AgentVoiceSettingsDto.serializer(), bodyText)
        val askable = dto.askable ?: return null
        val callable = dto.callable ?: return null
        AgentVoiceSettings(askable = askable, callable = callable)
    }.getOrNull()

@RaftCoreInternalApi
fun agentVoiceSettingsUpdateBody(askable: Boolean? = null, callable: Boolean? = null): String =
    agentVoiceRequestJson.encodeToString(
        AgentVoiceSettingsUpdateRequestDto.serializer(),
        AgentVoiceSettingsUpdateRequestDto(askable = askable, callable = callable),
    )

@RaftCoreInternalApi
fun parseAgentVoiceQuestions(bodyText: String): List<AgentVoiceQuestion>? =
    runCatching {
        val dto = agentVoiceJson.decodeFromString(AgentVoiceQuestionsResponseDto.serializer(), bodyText)
        val rawList = dto.questions ?: return null
        rawList.mapNotNull { item ->
            val at = item.at?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val asker =
                item.asker?.let { a ->
                    val askerId = a.id?.trim()?.takeIf { it.isNotEmpty() }
                    val askerName = a.name?.trim()?.takeIf { it.isNotEmpty() }
                    if (askerId != null && askerName != null) {
                        AgentVoiceQuestionAsker(id = askerId, name = askerName)
                    } else {
                        null
                    }
                }
            val app =
                item.app?.let { a ->
                    val appName = a.name?.trim()?.takeIf { it.isNotEmpty() }
                    if (appName != null) {
                        AgentVoiceQuestionApp(id = a.id?.trim()?.takeIf { it.isNotEmpty() }, name = appName)
                    } else {
                        null
                    }
                }
            AgentVoiceQuestion(
                id = item.id?.trim()?.takeIf { it.isNotEmpty() },
                at = at,
                asker = asker,
                app = app,
            )
        }
    }.getOrNull()

@RaftCoreInternalApi
interface AgentVoiceSettingsDataSource {
    /** Reads voice settings; `Content(null)` means 404 (not offered or not visible to this viewer). */
    suspend fun readSettings(agentId: String, serverId: String? = null): DataResult<AgentVoiceSettings?>

    /** Updates one or both voice settings and returns the saved values. */
    suspend fun updateSettings(
        agentId: String,
        askable: Boolean? = null,
        callable: Boolean? = null,
        serverId: String? = null,
    ): DataResult<AgentVoiceSettings>

    /** Reads recent side questions; `Content(null)` means 404 (hidden). */
    suspend fun readQuestions(agentId: String, serverId: String? = null): DataResult<List<AgentVoiceQuestion>?>
}

@RaftCoreInternalApi
class RaftAgentVoiceSettingsApi(
    private val networkClient: RaftNetworkClient,
) : AgentVoiceSettingsDataSource {
    override suspend fun readSettings(agentId: String, serverId: String?): DataResult<AgentVoiceSettings?> =
        try {
            val response =
                networkClient.request(
                    priority = DataRequestPriority.Visible,
                    path = "agents/${agentId.percentEncode()}/voice-settings",
                    method = "GET",
                    serverIdOverride = serverId?.trim()?.takeIf { it.isNotEmpty() }?.let(::ServerId),
                )
            when {
                response.code == 404 -> DataResult.Content(null)
                !response.isSuccessful -> agentVoiceErrorResult(response)
                else ->
                    parseAgentVoiceSettings(response.bodyText.orEmpty())
                        ?.let { DataResult.Content(it) }
                        ?: DataResult.Error(
                            ScreenError(
                                code = "agent_voice_settings_contract_mismatch",
                                retryable = false,
                                traceId = response.traceId,
                            )
                        )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            DataResult.Error(ScreenError("agent_voice_settings_network_error"))
        }

    override suspend fun updateSettings(
        agentId: String,
        askable: Boolean?,
        callable: Boolean?,
        serverId: String?,
    ): DataResult<AgentVoiceSettings> =
        try {
            val response =
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = "agents/${agentId.percentEncode()}/voice-settings",
                    method = "PUT",
                    serverIdOverride = serverId?.trim()?.takeIf { it.isNotEmpty() }?.let(::ServerId),
                    bodyText = agentVoiceSettingsUpdateBody(askable = askable, callable = callable),
                )
            when {
                !response.isSuccessful -> agentVoiceErrorResult(response)
                else ->
                    parseAgentVoiceSettings(response.bodyText.orEmpty())
                        ?.let { DataResult.Content(it) }
                        ?: DataResult.Error(
                            ScreenError(
                                code = "agent_voice_settings_contract_mismatch",
                                retryable = false,
                                traceId = response.traceId,
                            )
                        )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            DataResult.Error(ScreenError("agent_voice_settings_network_error"))
        }

    override suspend fun readQuestions(agentId: String, serverId: String?): DataResult<List<AgentVoiceQuestion>?> =
        try {
            val response =
                networkClient.request(
                    priority = DataRequestPriority.Visible,
                    path = "agents/${agentId.percentEncode()}/voice-questions",
                    method = "GET",
                    serverIdOverride = serverId?.trim()?.takeIf { it.isNotEmpty() }?.let(::ServerId),
                )
            when {
                response.code == 404 -> DataResult.Content(null)
                !response.isSuccessful -> agentVoiceErrorResult(response)
                else ->
                    parseAgentVoiceQuestions(response.bodyText.orEmpty())
                        ?.let { DataResult.Content(it) }
                        ?: DataResult.Error(
                            ScreenError(
                                code = "agent_voice_questions_contract_mismatch",
                                retryable = false,
                                traceId = response.traceId,
                            )
                        )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            DataResult.Error(ScreenError("agent_voice_questions_network_error"))
        }
}

private fun agentVoiceErrorResult(response: HomeNetworkResponse): DataResult<Nothing> {
    val base = agentControlsErrorResult(response)
    return if (base is DataResult.Error && base.error.traceId == null && response.traceId != null) {
        DataResult.Error(base.error.copy(traceId = response.traceId))
    } else {
        base
    }
}
