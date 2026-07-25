package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import build.raft.app.base.Utils
import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.components.SlockBrutalIconButton
import build.raft.app.components.SlockRaftUiEmptyState
import build.raft.app.data.AppLocale
import build.raft.app.data.KeyValueLocalePreferenceStore
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SharedPreferenceStore
import build.raft.app.data.SharedSettingsAboutSnapshot
import build.raft.app.data.SharedSettingsDestination
import build.raft.app.data.SharedSettingsIntent
import build.raft.app.data.SharedSettingsViewModel
import build.raft.app.data.kmmResourceLocaleTag
import build.raft.app.data.slockSharedPreferenceStore
import build.raft.app.markdown.SlockMarkdownBody
import build.raft.app.resources.MR
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.PaddingValues
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.zIndex
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.module.NetworkModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.lifecycle.ViewModel
import com.tencent.kuikly.lifecycle.viewModelScope
import com.tencent.kuikly.lifecycle.viewmodel.compose.viewModel
import com.tencent.tmm.kmmresource.compose.stringResource
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

const val RELEASE_NOTES_PAGE = "compose_release_notes"

@Page(RELEASE_NOTES_PAGE, supportInLocal = true)
internal class ComposeReleaseNotesPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ReleaseNotesScreen() }
    }
}

// Opened from the Settings "About" section (task #227). The optional version
// code is local app build identity, not product content. Passing it lets the
// native notes request start without rebuilding the standalone Settings store; direct/deep
// opens still fall back to loading About locally when the value is absent.
internal fun Pager.openReleaseNotesPage(
    versionCode: String = "",
    storeScope: build.raft.app.data.SlockShellStoreScope,
) {
    acquireModule<com.tencent.kuikly.core.module.RouterModule>(
        com.tencent.kuikly.core.module.RouterModule.MODULE_NAME
    ).openPage(
        RELEASE_NOTES_PAGE,
        com.tencent.kuikly.core.nvi.serialization.json.JSONObject().apply {
            put("pageName", RELEASE_NOTES_PAGE)
            put(SLOCK_SHELL_INSTANCE_ID, storeScope.shellInstanceId)
            storeScope.initialServerId?.let { put(SLOCK_SHELL_SERVER_ID, it) }
            releaseNotesSanitizeVersionCode(versionCode).takeIf { it.isNotBlank() }?.let {
                put("versionCode", it)
            }
        }
    )
}

// task #227: Release Notes is its own single-page pager (not a destination in the
// Settings child-tab pager) so it gets a full-height native viewport and clean
// lifecycle. The page owns its
// About data via the standalone settings environment, so version_code is resolved
// race-free instead of depending on the shell having loaded About.
@Composable
internal fun ReleaseNotesScreen() {
    RememberKmmResourceLocaleSeedFromLocalActivity()
    val pager = LocalActivity.current.getPager() as Pager
    val launchVersionCode =
        remember(pager.pageData.params) {
            releaseNotesVersionCodeFromPageData(pager.pageData.params)
        }
    // Keep the first-frame header contract while the direct/deep-open fallback
    // constructs its standalone Settings store. The normal About route already
    // carries versionCode and reaches the native notes body on the next frame.
    var bodyReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        bodyReady = true
    }
    Column(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceCream)) {
        Box(modifier = Modifier.fillMaxWidth().zIndex(RELEASE_NOTES_HEADER_Z_INDEX)) {
            SlockTopPageHeader(
                title = stringResource(MR.strings.release_notes_title),
                iconName = SlockLucideIconName.FileText,
                backgroundColor = SlockColor.SurfaceWhite,
                leadingContent = {
                    SlockBrutalIconButton(
                        iconName = SlockLucideIconName.ArrowLeft,
                        size = 30.dp,
                        shadowOffset = 2.dp,
                        onClick = {
                            pager.acquireModule<com.tencent.kuikly.core.module.RouterModule>(
                                com.tencent.kuikly.core.module.RouterModule.MODULE_NAME
                            ).closePage()
                        }
                    )
                }
            )
        }
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .zIndex(RELEASE_NOTES_BODY_Z_INDEX)
                    .background(SlockColor.SurfaceCream)
        ) {
            if (bodyReady) {
                if (launchVersionCode.isNotBlank()) {
                    val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
                    LaunchedEffect(launchVersionCode) {
                        bridge.log("release_notes_open versionSource=launch")
                    }
                    ReleaseNotesNativeBody(launchVersionCode)
                } else {
                    ReleaseNotesScreenBody(pager)
                }
            } else {
                ReleaseNotesLoadingCover()
            }
        }
    }
}

@Composable
private fun ReleaseNotesScreenBody(pager: Pager) {
    val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
    val environment = rememberStandaloneSettingsEnvironment(pager, bridge)
    val settingsViewModel: SharedSettingsViewModel =
        viewModel(key = "standalone-release-notes") {
            SharedSettingsViewModel(environment.dataSource)
        }
    val settingsState by settingsViewModel.state.collectAsState()
    LaunchedEffect(Unit) {
        bridge.log("release_notes_open versionSource=about")
        settingsViewModel.dispatch(SharedSettingsIntent.OpenDestination(SharedSettingsDestination.About))
    }
    StandaloneSettingsEffectHandler(pager, environment, settingsState, settingsViewModel::dispatch)

    when (val resolution = releaseNotesAboutResolution(settingsState.about)) {
        ReleaseNotesAboutResolution.Loading -> ReleaseNotesLoadingCover()
        is ReleaseNotesAboutResolution.Ready -> ReleaseNotesNativeBody(resolution.versionCode)
        ReleaseNotesAboutResolution.Failure ->
            ReleaseNotesErrorState(
                onRetry = {
                    settingsViewModel.dispatch(
                        SharedSettingsIntent.OpenDestination(SharedSettingsDestination.About)
                    )
                }
            )
    }
}

internal sealed interface ReleaseNotesAboutResolution {
    data object Loading : ReleaseNotesAboutResolution

    data class Ready(val versionCode: String) : ReleaseNotesAboutResolution

    data object Failure : ReleaseNotesAboutResolution
}

internal fun releaseNotesAboutResolution(
    state: ScreenLoadState<SharedSettingsAboutSnapshot>
): ReleaseNotesAboutResolution =
    when (state) {
        ScreenLoadState.Loading -> ReleaseNotesAboutResolution.Loading
        is ScreenLoadState.Content ->
            releaseNotesSanitizeVersionCode(state.data.buildLabel)
                .takeIf { it.isNotBlank() }
                ?.let(ReleaseNotesAboutResolution::Ready)
                ?: ReleaseNotesAboutResolution.Failure
        is ScreenLoadState.Empty,
        is ScreenLoadState.Error,
        is ScreenLoadState.NoPermission -> ReleaseNotesAboutResolution.Failure
    }

internal fun releaseNotesVersionCodeFromPageData(
    params: com.tencent.kuikly.core.nvi.serialization.json.JSONObject
): String =
    releaseNotesSanitizeVersionCode(params.optString("versionCode"))

internal fun releaseNotesSanitizeVersionCode(value: String): String =
    value.trim().takeIf { versionCode -> versionCode.all(Char::isDigit) }.orEmpty()

internal data class ReleaseNotesDocument(
    val versionCode: Long,
    val versionName: String,
    val markdown: String,
    val languageTag: String
)

internal sealed interface ReleaseNotesBodyState {
    data object Loading : ReleaseNotesBodyState

    data class Content(
        val document: ReleaseNotesDocument,
        val cached: Boolean
    ) : ReleaseNotesBodyState

    data class Error(val reason: String) : ReleaseNotesBodyState
}

internal data class ReleaseNotesNetworkResult(
    val data: JSONObject,
    val success: Boolean,
    val errorMessage: String,
    val statusCode: Int?
)

@Composable
internal fun ReleaseNotesNativeBody(versionCode: String) {
    if (versionCode.isBlank()) {
        ReleaseNotesLoadingCover()
        return
    }
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
    val linkNavigator = rememberSlockLinkNavigator(pager, bridge)
    val resolvedResourceLocaleTag = stringResource(MR.strings.app_locale_request_tag)
    val requestLocaleTag = remember(resolvedResourceLocaleTag) {
        releaseNotesRequestLocaleTag(resolvedResourceLocaleTag)
    }
    val networkModule = remember(pager) {
        pager.acquireModule<NetworkModule>(NetworkModule.MODULE_NAME)
    }
    val repository = remember(networkModule) {
        ReleaseNotesRepository(
            remoteDataSource = NetworkReleaseNotesRemoteDataSource(networkModule),
            preferenceStore = slockSharedPreferenceStore()
        )
    }
    val releaseNotesViewModel: ReleaseNotesViewModel =
        viewModel(key = "release-notes-${releaseNotesRequestIdentity(versionCode, requestLocaleTag)}") {
            ReleaseNotesViewModel(
                repository = repository,
                diagnosticsLogger = bridge::log,
                warningLogger = bridge::logWarn
            )
        }
    val state by releaseNotesViewModel.state.collectAsState()
    LaunchedEffect(versionCode, requestLocaleTag) {
        releaseNotesViewModel.dispatch(
            ReleaseNotesIntent.Load(versionCode, requestLocaleTag)
        )
    }

    when (val current = state) {
        ReleaseNotesBodyState.Loading -> ReleaseNotesLoadingCover()
        is ReleaseNotesBodyState.Content ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceCream),
                contentPadding = PaddingValues(
                    start = SlockSpacing.Md,
                    top = SlockSpacing.Md,
                    end = SlockSpacing.Md,
                    bottom = SlockSpacing.Lg + slockBottomSafeAreaPadding()
                ),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
            ) {
                item {
                    SlockMarkdownBody(
                        body = current.document.markdown,
                        modifier = Modifier.fillMaxWidth(),
                        // Top-level Kuikly Markdown nodes are otherwise
                        // adjacent. Keep a document-style section gap so an
                        // inline-code box cannot paint into the next H2/H3 at
                        // enlarged system font scales (notably zh-CN 1.3x).
                        sectionHeadingTopPadding = SlockSpacing.Sm,
                        onOpenUrl = { url -> linkNavigator.open(url) }
                    )
                }
            }
        is ReleaseNotesBodyState.Error ->
            ReleaseNotesErrorState(
                onRetry = { releaseNotesViewModel.dispatch(ReleaseNotesIntent.Retry) }
            )
    }
}

private const val RELEASE_NOTES_HEADER_Z_INDEX = 1f
private const val RELEASE_NOTES_BODY_Z_INDEX = 0f

internal fun releaseNotesRequestLocaleTag(
    resolvedSystemLanguageTag: String = requireNotNull(AppLocale.Default.kmmResourceLocaleTag)
): String? =
    try {
        when (val locale = KeyValueLocalePreferenceStore(slockSharedPreferenceStore()).readLocaleSync()) {
            AppLocale.System -> releaseNotesNormalizeLanguageTag(resolvedSystemLanguageTag)
            else -> locale.kmmResourceLocaleTag
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        AppLocale.Default.kmmResourceLocaleTag
    }

internal fun releaseNotesNormalizeLanguageTag(value: String?): String =
    value
        ?.trim()
        ?.substringBefore('.')
        ?.substringBefore('@')
        ?.replace('_', '-')
        ?.takeIf {
            it.isNotBlank() &&
                !it.equals("C", ignoreCase = true) &&
                !it.equals("POSIX", ignoreCase = true)
        }
        ?: requireNotNull(AppLocale.Default.kmmResourceLocaleTag)

internal interface ReleaseNotesRemoteDataSource {
    suspend fun fetch(versionCode: String, languageTag: String?): ReleaseNotesNetworkResult
}

internal class NetworkReleaseNotesRemoteDataSource(
    private val networkModule: NetworkModule
) : ReleaseNotesRemoteDataSource {
    override suspend fun fetch(
        versionCode: String,
        languageTag: String?
    ): ReleaseNotesNetworkResult = networkModule.fetchReleaseNotes(versionCode, languageTag)
}

private suspend fun NetworkModule.fetchReleaseNotes(
    versionCode: String,
    languageTag: String?
): ReleaseNotesNetworkResult =
    suspendCancellableCoroutine { continuation ->
        httpRequest(
            url = releaseNotesApiUrl(slockReleaseNotesAppSlug()),
            isPost = false,
            param = releaseNotesApiRequestParams(versionCode, languageTag),
            headers = JSONObject().put("Accept", "application/json"),
            timeout = 12
        ) { data, success, errorMessage, response ->
            if (continuation.isActive) {
                continuation.resume(
                    ReleaseNotesNetworkResult(
                        data = data,
                        success = success,
                        errorMessage = errorMessage,
                        statusCode = response.statusCode
                    )
                )
            }
        }
    }

internal fun releaseNotesApiUrl(appSlug: String): String =
    "https://hands.build/public/v2/apps/${appSlug.trim()}/release-notes"

internal sealed interface ReleaseNotesRepositoryResult {
    data class Success(val document: ReleaseNotesDocument) : ReleaseNotesRepositoryResult

    data class Failure(val reason: String, val statusCode: Int?) : ReleaseNotesRepositoryResult
}

internal class ReleaseNotesRepository(
    private val remoteDataSource: ReleaseNotesRemoteDataSource,
    private val preferenceStore: SharedPreferenceStore
) {
    fun cachedDocument(versionCode: String, languageTag: String?): ReleaseNotesDocument? {
        val identity = releaseNotesRequestIdentity(versionCode, languageTag)
        if (identity == null) return null
        return releaseNotesCachedDocument(
            raw = preferenceStore.getString(releaseNotesCacheKey(identity.versionCode, identity.languageTag)),
            requestedVersionCode = identity.versionCode,
            requestedLanguageTag = identity.languageTag
        )
    }

    suspend fun refresh(
        versionCode: String,
        languageTag: String?
    ): ReleaseNotesRepositoryResult {
        val identity =
            releaseNotesRequestIdentity(versionCode, languageTag)
                ?: return ReleaseNotesRepositoryResult.Failure("invalid_version_code", null)
        val result = remoteDataSource.fetch(identity.versionCode, identity.languageTag)
        val document =
            if (result.success) {
                releaseNotesDocumentFromResponse(
                    payload = result.data,
                    requestedVersionCode = identity.versionCode,
                    requestedLanguageTag = identity.languageTag
                )
            } else {
                null
            }
        if (document == null) {
            val reason =
                if (result.success) {
                    "missing_exact_release_notes"
                } else {
                    "http_${result.statusCode ?: 0}:${result.errorMessage.take(80)}"
                }
            return ReleaseNotesRepositoryResult.Failure(reason, result.statusCode)
        }
        preferenceStore.putString(
            releaseNotesCacheKey(identity.versionCode, identity.languageTag),
            releaseNotesCachedDocumentJson(
                document = document,
                requestedVersionCode = identity.versionCode,
                requestedLanguageTag = identity.languageTag
            )
        )
        return ReleaseNotesRepositoryResult.Success(document)
    }
}

internal sealed interface ReleaseNotesIntent {
    data class Load(val versionCode: String, val languageTag: String?) : ReleaseNotesIntent

    data object Retry : ReleaseNotesIntent
}

internal class ReleaseNotesStore(
    private val repository: ReleaseNotesRepository,
    private val scope: CoroutineScope,
    private val diagnosticsLogger: (String) -> Unit = {},
    private val warningLogger: (String) -> Unit = {}
) {
    private val _state = MutableStateFlow<ReleaseNotesBodyState>(ReleaseNotesBodyState.Loading)
    val state: StateFlow<ReleaseNotesBodyState> = _state

    private var activeRequest: ReleaseNotesRequestIdentity? = null
    private var requestGeneration = 0L
    private var requestJob: Job? = null

    fun dispatch(intent: ReleaseNotesIntent) {
        when (intent) {
            is ReleaseNotesIntent.Load -> load(intent.versionCode, intent.languageTag, force = false)
            ReleaseNotesIntent.Retry -> {
                val request = activeRequest ?: return
                load(request.versionCode, request.languageTag, force = true)
            }
        }
    }

    fun clear() {
        requestGeneration += 1
        requestJob?.cancel()
        requestJob = null
    }

    private fun load(versionCode: String, languageTag: String?, force: Boolean) {
        val request = releaseNotesRequestIdentity(versionCode, languageTag)
        if (request == null) {
            clear()
            activeRequest = null
            _state.value = ReleaseNotesBodyState.Error("invalid_version_code")
            warningLogger("release_notes_load_failure renderer=native reason=invalid_version_code")
            return
        }
        if (!force && request == activeRequest && requestJob?.isActive == true) return

        activeRequest = request
        requestGeneration += 1
        val generation = requestGeneration
        requestJob?.cancel()
        val cached = repository.cachedDocument(request.versionCode, request.languageTag)
        _state.value =
            cached?.let { ReleaseNotesBodyState.Content(it, cached = true) }
                ?: ReleaseNotesBodyState.Loading
        requestJob =
            scope.launch {
                when (val result = repository.refresh(request.versionCode, request.languageTag)) {
                    is ReleaseNotesRepositoryResult.Success -> {
                        if (!isCurrent(request, generation)) return@launch
                        _state.value = ReleaseNotesBodyState.Content(result.document, cached = false)
                        diagnosticsLogger(
                            "release_notes_load_success renderer=native " +
                                "versionCode=${request.versionCode} lang=${result.document.languageTag} cached=false"
                        )
                    }
                    is ReleaseNotesRepositoryResult.Failure -> {
                        if (!isCurrent(request, generation)) return@launch
                        if (_state.value !is ReleaseNotesBodyState.Content) {
                            _state.value = ReleaseNotesBodyState.Error(result.reason)
                            warningLogger(
                                "release_notes_load_failure renderer=native " +
                                    "versionCode=${request.versionCode} reason=${result.reason}"
                            )
                        } else {
                            warningLogger(
                                "release_notes_load_stale renderer=native " +
                                    "versionCode=${request.versionCode} status=${result.statusCode ?: 0}"
                            )
                        }
                    }
                }
            }
    }

    private fun isCurrent(request: ReleaseNotesRequestIdentity, generation: Long): Boolean =
        activeRequest == request && requestGeneration == generation
}

internal class ReleaseNotesViewModel(
    repository: ReleaseNotesRepository,
    diagnosticsLogger: (String) -> Unit = {},
    warningLogger: (String) -> Unit = {}
) : ViewModel() {
    private val storeLifecycle =
        ReleaseNotesStoreLifecycle {
            ReleaseNotesStore(
                repository = repository,
                scope = viewModelScope,
                diagnosticsLogger = diagnosticsLogger,
                warningLogger = warningLogger
            )
        }
    private val store: ReleaseNotesStore
        get() = storeLifecycle.value
    val state: StateFlow<ReleaseNotesBodyState>
        get() = store.state

    fun dispatch(intent: ReleaseNotesIntent) {
        store.dispatch(intent)
    }

    override fun onCleared() {
        storeLifecycle.clearIfInitialized()
        super.onCleared()
    }
}

internal class ReleaseNotesStoreLifecycle(
    factory: () -> ReleaseNotesStore
) {
    private val delegate = lazy(factory)
    val value: ReleaseNotesStore
        get() = delegate.value

    fun clearIfInitialized() {
        if (delegate.isInitialized()) delegate.value.clear()
    }
}

internal data class ReleaseNotesRequestIdentity(
    val versionCode: String,
    val languageTag: String
)

internal fun releaseNotesRequestIdentity(
    versionCode: String,
    languageTag: String?
): ReleaseNotesRequestIdentity? {
    val exactVersionCode = releaseNotesSanitizeVersionCode(versionCode)
    if (exactVersionCode.isBlank()) return null
    return ReleaseNotesRequestIdentity(
        versionCode = exactVersionCode,
        languageTag = releaseNotesNormalizeLanguageTag(languageTag)
    )
}

internal fun releaseNotesApiRequestParams(
    versionCode: String,
    languageTag: String?
): JSONObject =
    JSONObject().apply {
        releaseNotesSanitizeVersionCode(versionCode).takeIf { it.isNotBlank() }?.let {
            put("version_code", it)
        }
        put("lang", releaseNotesNormalizeLanguageTag(languageTag))
    }

internal fun releaseNotesDocumentFromResponse(
    payload: JSONObject,
    requestedVersionCode: String,
    requestedLanguageTag: String?
): ReleaseNotesDocument? {
    val exactVersion = releaseNotesSanitizeVersionCode(requestedVersionCode).toLongOrNull() ?: return null
    val releases = payload.optJSONArray("releases") ?: return null
    for (index in 0 until releases.length()) {
        val release = releases.optJSONObject(index) ?: continue
        if (release.optLong("version_code") != exactVersion) continue
        val responseLanguage =
            payload.optString("lang").takeIf { it.isNotBlank() }
                ?: requestedLanguageTag.orEmpty().takeIf { it.isNotBlank() }
                ?: "en"
        val notes = release.optJSONObject("release_notes")
        val markdown =
            notes?.optString(responseLanguage).orEmpty().ifBlank {
                val languagePrefix = responseLanguage.substringBefore('-')
                notes?.keySet()
                    ?.firstOrNull { key -> key.substringBefore('-').equals(languagePrefix, ignoreCase = true) }
                    ?.let { key -> notes.optString(key) }
                    .orEmpty()
            }.ifBlank {
                notes?.optString("en").orEmpty()
            }.ifBlank {
                release.optString("changelog")
            }.trim()
        if (markdown.isBlank()) return null
        return ReleaseNotesDocument(
            versionCode = exactVersion,
            versionName = release.optString("version"),
            markdown = markdown,
            languageTag = responseLanguage
        )
    }
    return null
}

internal fun releaseNotesCacheKey(versionCode: String, languageTag: String?): String =
    "releaseNotes.native.v2:${releaseNotesSanitizeVersionCode(versionCode)}:" +
        releaseNotesNormalizeLanguageTag(languageTag)

internal fun releaseNotesCachedDocumentJson(
    document: ReleaseNotesDocument,
    requestedVersionCode: String = document.versionCode.toString(),
    requestedLanguageTag: String? = document.languageTag
): String =
    JSONObject()
        .put("versionCode", document.versionCode)
        .put("versionName", document.versionName)
        .put("markdown", document.markdown)
        .put("languageTag", document.languageTag)
        .put("requestedVersionCode", releaseNotesSanitizeVersionCode(requestedVersionCode))
        .put("requestedLanguageTag", releaseNotesNormalizeLanguageTag(requestedLanguageTag))
        .toString()

internal fun releaseNotesCachedDocument(
    raw: String?,
    requestedVersionCode: String? = null,
    requestedLanguageTag: String? = null
): ReleaseNotesDocument? =
    raw?.takeIf { it.isNotBlank() }?.let { value ->
        try {
            val payload = JSONObject(value)
            val versionCode = payload.optLong("versionCode")
            val markdown = payload.optString("markdown").trim()
            if (versionCode <= 0 || markdown.isBlank()) return@let null
            if (requestedVersionCode != null) {
                val exactRequestedVersion = releaseNotesSanitizeVersionCode(requestedVersionCode)
                if (
                    exactRequestedVersion.isBlank() ||
                    versionCode.toString() != exactRequestedVersion ||
                    payload.optString("requestedVersionCode") != exactRequestedVersion
                ) {
                    return@let null
                }
                val expectedLanguage = releaseNotesNormalizeLanguageTag(requestedLanguageTag)
                if (payload.optString("requestedLanguageTag") != expectedLanguage) return@let null
            }
            ReleaseNotesDocument(
                versionCode = versionCode,
                versionName = payload.optString("versionName"),
                markdown = markdown,
                languageTag = payload.optString("languageTag").ifBlank { "en" }
            )
        } catch (_: Throwable) {
            null
        }
    }

@Composable
internal fun ReleaseNotesLoadingCover() {
    Box(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceCream))
}

@Composable
private fun ReleaseNotesErrorState(onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceCream),
        contentAlignment = Alignment.Center
    ) {
        SlockRaftUiEmptyState(
            title = stringResource(MR.strings.release_notes_error_title),
            description = stringResource(MR.strings.release_notes_error_body),
            modifier = Modifier.fillMaxSize(),
            action = {
                SlockBrutalActionButton(
                    label = stringResource(MR.strings.common_retry),
                    onClick = onRetry,
                    leadingIcon = SlockLucideIconName.RefreshCw,
                    backgroundColor = SlockColor.SurfaceWhite
                )
            }
        )
    }
}
