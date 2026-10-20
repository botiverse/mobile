package build.raft.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Global message-translation store, aligned with the React `translationStore`:
 * one shared entry cache per message plus the account/server translation
 * settings the conversation flow needs to decide render language.
 *
 * Settings pages stay the edit surface for the underlying preferences
 * (`PATCH /auth/me`, `PATCH /servers/:id/translation-settings`); this store is
 * the read model for message surfaces. `ensureSettingsLoaded(force = true)` is
 * the resync hook after an edit or on surface entry.
 */
data class SharedMessageTranslationSettings(
    val loaded: Boolean = false,
    val serverId: String? = null,
    val serverTranslationEnabled: Boolean = false,
    val providerAvailable: Boolean = false,
    // Client-local translation mode (settings.translationMode, MMKV-backed):
    // the single decision signal for the conversation flow. Server
    // availability below stays the hard capability gate.
    val translationMode: TranslationModePreference = TranslationModePreference.Default,
    val effectiveLanguage: String? = null,
) {
    val available: Boolean
        get() = serverTranslationEnabled && providerAvailable
}

data class SharedMessageTranslationEntry(
    val messageId: String,
    val status: SharedMessageTranslationStatus,
    val reason: String? = null,
    val translatedContent: String? = null,
    val sourceLanguage: String? = null,
    val targetLanguage: String? = null,
    val originalContent: String = "",
    val showOriginal: Boolean? = null,
)

/** Skip results the UI renders silently (no indicator row), per React `translationContract`. */
private val SilentTranslationSkipReasons =
    setOf("same_language", "own_message", "system_message", "code_or_link_only", "low_confidence")

fun SharedMessageTranslationEntry.isSilentTranslationEntry(): Boolean =
    when (status) {
        SharedMessageTranslationStatus.NotFound -> true
        SharedMessageTranslationStatus.Skipped -> reason == null || reason in SilentTranslationSkipReasons
        else -> false
    }

data class SharedMessageTranslationCandidate(
    val messageId: String,
    val body: String,
    val isOwn: Boolean = false,
    val isSystem: Boolean = false,
)

data class SharedMessageTranslationState(
    val settings: SharedMessageTranslationSettings = SharedMessageTranslationSettings(),
    val entries: Map<String, SharedMessageTranslationEntry> = emptyMap(),
)

internal const val MESSAGE_TRANSLATION_PENDING_TIMEOUT_MS = 30_000L
private const val MESSAGE_TRANSLATION_PENDING_TIMEOUT_SLACK_MS = 250L

class SharedMessageTranslationStore internal constructor(
    private val authStore: RaftAuthSessionStore,
    private val api: RaftMessageTranslationApi,
    private val scope: CoroutineScope,
    private val translationMode: () -> TranslationModePreference = { TranslationModePreference.Default },
    private val translationTargetLanguage: () -> String = { TranslationTargetLanguages.Browser },
    /** The app's current display (UI) language tag: the no-preference translation target. */
    private val displayLanguage: () -> String? = { null },
    private val diagnosticsLogger: (String) -> Unit = {},
    private val pendingTimeoutMs: Long = MESSAGE_TRANSLATION_PENDING_TIMEOUT_MS,
) {
    private val _state = raftStoreState(SharedMessageTranslationState(), "SharedMessageTranslationStore")
    val state: StateFlow<SharedMessageTranslationState> = _state.asStateFlow()

    private var settingsJob: Job? = null
    private val inFlightBatchKeys = mutableSetOf<String>()

    fun ensureSettingsLoaded(force: Boolean = false) {
        if (settingsJob?.isActive == true) return
        settingsJob = scope.launch {
            val session = readSessionOrNull() ?: return@launch
            val serverId = session.currentServerId?.takeIf { it.isNotBlank() } ?: return@launch
            val current = _state.value.settings
            if (!force && current.loaded && current.serverId == serverId) return@launch
            if (current.serverId != null && current.serverId != serverId) {
                // Server switch: cached entries belong to the previous server.
                _state.value = SharedMessageTranslationState()
            }
            val serverResponse = runCatchingNetwork { api.serverTranslationSettings(serverId) } ?: return@launch
            val serverTranslation =
                serverResponse.bodyText
                    ?.takeIf { serverResponse.isSuccessful }
                    ?.let(::parseSharedSettingsTranslation)
            if (serverTranslation == null) {
                diagnosticsLogger(
                    "message_translation_settings_load_failed server=$serverId " +
                        "serverCode=${serverResponse.code}"
                )
                return@launch
            }
            val nextSettings =
                SharedMessageTranslationSettings(
                    loaded = true,
                    serverId = serverId,
                    serverTranslationEnabled = serverTranslation?.translationEnabled ?: false,
                    providerAvailable = serverTranslation?.translationAvailable ?: false,
                    translationMode = translationMode(),
                    effectiveLanguage = normalizedTargetLanguage(),
                )
            updateState { state ->
                if (state.settings == nextSettings) state else state.copy(settings = nextSettings)
            }
            diagnosticsLogger(
                "message_translation_settings_loaded server=$serverId available=${nextSettings.available} " +
                    "mode=${nextSettings.translationMode.storageKey} language=${nextSettings.effectiveLanguage.orEmpty()}"
            )
        }
    }

    // Public entry points are called from the UI (the main thread under CMP). Their state writes and
    // the in-flight batch bookkeeping belong on the store scope (the serial store dispatcher in
    // production, see RaftStoreThreading), so each one hops there before touching either.

    fun requestAutoTranslations(candidates: List<SharedMessageTranslationCandidate>) {
        scope.launch { requestAutoTranslationsOnStoreScope(candidates) }
    }

    fun requestManualTranslation(candidate: SharedMessageTranslationCandidate) {
        scope.launch { requestTranslations(listOf(candidate), force = true) }
    }

    fun retryTranslation(candidate: SharedMessageTranslationCandidate) {
        scope.launch { requestTranslations(listOf(candidate), force = true) }
    }

    /**
     * Manual per-message entry point (message action menu). Reuses a fresh
     * translated entry when one exists; otherwise forces a `mode: "manual"`
     * batch. Either way the message flips to showing the translation.
     */
    fun translateNow(candidate: SharedMessageTranslationCandidate) {
        scope.launch { translateNowOnStoreScope(candidate) }
    }

    fun setShowOriginal(messageId: String, showOriginal: Boolean) {
        scope.launch { setShowOriginalOnStoreScope(messageId, showOriginal) }
    }

    fun reset() {
        settingsJob?.cancel()
        settingsJob = null
        scope.launch {
            inFlightBatchKeys.clear()
            _state.value = SharedMessageTranslationState()
        }
    }

    private fun requestAutoTranslationsOnStoreScope(candidates: List<SharedMessageTranslationCandidate>) {
        val settings = _state.value.settings
        if (!settings.available) return
        if (settings.translationMode != TranslationModePreference.Automatic) return
        requestTranslations(candidates, force = false)
    }

    private fun translateNowOnStoreScope(candidate: SharedMessageTranslationCandidate) {
        if (_state.value.settings.translationMode == TranslationModePreference.Original) return
        val entry = _state.value.entries[candidate.messageId]
        val hasFreshTranslation =
            entry?.status == SharedMessageTranslationStatus.Translated &&
                entry.translatedContent != null &&
                (entry.originalContent.isEmpty() || entry.originalContent == candidate.body)
        if (!hasFreshTranslation) {
            requestTranslations(listOf(candidate), force = true)
        }
        setShowOriginalOnStoreScope(candidate.messageId, false)
    }

    private fun setShowOriginalOnStoreScope(messageId: String, showOriginal: Boolean) {
        updateState { state ->
            val entry = state.entries[messageId] ?: return@updateState state
            if (entry.showOriginal == showOriginal) return@updateState state
            state.copy(entries = state.entries + (messageId to entry.copy(showOriginal = showOriginal)))
        }
    }

    private fun requestTranslations(candidates: List<SharedMessageTranslationCandidate>, force: Boolean) {
        val settings = _state.value.settings
        if (!settings.available) return
        val targetLanguage = normalizedTargetLanguage()
        if (settings.effectiveLanguage != targetLanguage) {
            updateState { state ->
                state.copy(settings = state.settings.copy(effectiveLanguage = targetLanguage))
            }
        }
        val entries = _state.value.entries
        val eligible =
            candidates.filter { candidate ->
                if (candidate.messageId.isBlank() || candidate.body.isBlank()) return@filter false
                if (candidate.isSystem || candidate.isOwn) return@filter false
                val existing = entries[candidate.messageId] ?: return@filter true
                if (existing.originalContent.isNotEmpty() && existing.originalContent != candidate.body) return@filter true
                force
            }
        if (eligible.isEmpty()) return
        val batch = eligible.take(MESSAGE_TRANSLATION_MAX_BATCH_SIZE)
        val messageIds = batch.map { it.messageId }
        val mode = if (force) MESSAGE_TRANSLATION_MODE_MANUAL else MESSAGE_TRANSLATION_MODE_AUTO
        val batchKey = "$targetLanguage:${messageIds.sorted().joinToString(",")}:$mode"
        if (!inFlightBatchKeys.add(batchKey)) return

        val originalById = batch.associate { it.messageId to it.body }
        updateState { state ->
            val nextEntries = state.entries.toMutableMap()
            for (candidate in batch) {
                nextEntries[candidate.messageId] =
                    SharedMessageTranslationEntry(
                        messageId = candidate.messageId,
                        status = SharedMessageTranslationStatus.Pending,
                        targetLanguage = targetLanguage,
                        originalContent = candidate.body,
                        showOriginal = state.entries[candidate.messageId]?.showOriginal,
                    )
            }
            state.copy(entries = nextEntries)
        }
        schedulePendingTimeout(messageIds, originalById)

        scope.launch {
            try {
                val response =
                    api.translateBatch(
                        messageIds = messageIds,
                        targetLanguage = targetLanguage,
                        mode = mode,
                        priority = if (force) DataRequestPriority.Foreground else DataRequestPriority.Visible,
                    )
                if (translationSurfaceUnavailable(response.code)) {
                    diagnosticsLogger("message_translation_batch_unavailable code=${response.code} mode=$mode count=${messageIds.size}")
                    markUnavailableAndClear(messageIds)
                    return@launch
                }
                val results =
                    response.bodyText
                        ?.takeIf { response.isSuccessful }
                        ?.let(::parseSharedMessageTranslationBatch)
                if (results == null) {
                    diagnosticsLogger("message_translation_batch_failed code=${response.code} mode=$mode count=${messageIds.size}")
                    markFailed(messageIds, originalById, targetLanguage)
                    return@launch
                }
                updateState { state ->
                    val nextEntries = state.entries.toMutableMap()
                    val resultIds = mutableSetOf<String>()
                    for (result in results) {
                        resultIds += result.messageId
                        nextEntries[result.messageId] =
                            SharedMessageTranslationEntry(
                                messageId = result.messageId,
                                status = result.status,
                                reason = result.reason,
                                translatedContent = result.translatedContent,
                                sourceLanguage = result.sourceLanguage,
                                targetLanguage = result.targetLanguage ?: targetLanguage,
                                originalContent = originalById[result.messageId].orEmpty(),
                                showOriginal = state.entries[result.messageId]?.showOriginal,
                            )
                    }
                    for (messageId in messageIds) {
                        if (messageId in resultIds) continue
                        nextEntries[messageId] =
                            SharedMessageTranslationEntry(
                                messageId = messageId,
                                status = SharedMessageTranslationStatus.NotFound,
                                reason = "not_found",
                                originalContent = originalById[messageId].orEmpty(),
                            )
                    }
                    state.copy(entries = nextEntries)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                diagnosticsLogger("message_translation_batch_error mode=$mode count=${messageIds.size} error=${error::class.simpleName}")
                markFailed(messageIds, originalById, targetLanguage)
            } finally {
                inFlightBatchKeys.remove(batchKey)
            }
        }
    }

    private fun schedulePendingTimeout(messageIds: List<String>, originalById: Map<String, String>) {
        scope.launch {
            delay(pendingTimeoutMs + MESSAGE_TRANSLATION_PENDING_TIMEOUT_SLACK_MS)
            updateState { state ->
                var changed = false
                val nextEntries = state.entries.toMutableMap()
                for (messageId in messageIds) {
                    val entry = nextEntries[messageId] ?: continue
                    if (entry.status != SharedMessageTranslationStatus.Pending) continue
                    if (entry.originalContent != originalById[messageId]) continue
                    nextEntries[messageId] = entry.copy(status = SharedMessageTranslationStatus.Failed, reason = "provider_timeout")
                    changed = true
                }
                if (changed) state.copy(entries = nextEntries) else state
            }
        }
    }

    private fun markFailed(messageIds: List<String>, originalById: Map<String, String>, targetLanguage: String) {
        updateState { state ->
            val nextEntries = state.entries.toMutableMap()
            for (messageId in messageIds) {
                nextEntries[messageId] =
                    SharedMessageTranslationEntry(
                        messageId = messageId,
                        status = SharedMessageTranslationStatus.Failed,
                        reason = "provider",
                        targetLanguage = targetLanguage,
                        originalContent = originalById[messageId].orEmpty(),
                        showOriginal = state.entries[messageId]?.showOriginal,
                    )
            }
            state.copy(entries = nextEntries)
        }
    }

    private fun markUnavailableAndClear(messageIds: List<String>) {
        updateState { state ->
            val nextEntries = state.entries.toMutableMap()
            for (messageId in messageIds) {
                nextEntries.remove(messageId)
            }
            state.copy(
                settings = state.settings.copy(loaded = true, serverTranslationEnabled = false),
                entries = nextEntries,
            )
        }
    }

    private fun translationSurfaceUnavailable(code: Int): Boolean =
        code == 403 || code == 404 || code == 501

    private fun normalizedTargetLanguage(): String =
        TranslationTargetLanguages.resolve(
            targetLanguage = translationTargetLanguage(),
            displayLanguage = displayLanguage()
        )

    private suspend fun readSessionOrNull(): StoredAuthSession? =
        try {
            authStore.readSession()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }

    private suspend fun runCatchingNetwork(block: suspend () -> HomeNetworkResponse): HomeNetworkResponse? =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            diagnosticsLogger("message_translation_settings_network_error error=${error::class.simpleName}")
            null
        }

    private inline fun updateState(transform: (SharedMessageTranslationState) -> SharedMessageTranslationState) {
        val current = _state.value
        val next = transform(current)
        if (next != current) {
            _state.value = next
        }
    }
}
