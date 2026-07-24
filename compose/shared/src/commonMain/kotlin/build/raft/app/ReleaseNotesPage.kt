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
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SharedSettingsDestination
import build.raft.app.data.SharedSettingsIntent
import build.raft.app.data.SharedSettingsViewModel
import build.raft.app.data.AppLocale
import build.raft.app.data.KeyValueLocalePreferenceStore
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
import com.tencent.kuikly.compose.ui.draw.zIndex
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.module.NetworkModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.lifecycle.viewmodel.compose.viewModel
import com.tencent.tmm.kmmresource.compose.stringResource
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
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
private fun ReleaseNotesScreen() {
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

    val versionCode =
        (settingsState.about as? ScreenLoadState.Content)?.data?.buildLabel.orEmpty()
    ReleaseNotesNativeBody(versionCode)
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

private sealed interface ReleaseNotesBodyState {
    data object Loading : ReleaseNotesBodyState

    data class Content(
        val document: ReleaseNotesDocument,
        val cached: Boolean
    ) : ReleaseNotesBodyState

    data class Error(val reason: String) : ReleaseNotesBodyState
}

private data class ReleaseNotesNetworkResult(
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
    val errorTitle = stringResource(MR.strings.release_notes_error_title)
    val errorBody = stringResource(MR.strings.release_notes_error_body)
    val retryLabel = stringResource(MR.strings.common_retry)
    var reloadKey by remember { mutableStateOf(0) }
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
    val linkNavigator = rememberSlockLinkNavigator(pager, bridge)
    val requestLocaleTag = remember { releaseNotesRequestLocaleTag() }
    val cacheKey = remember(versionCode, requestLocaleTag) {
        releaseNotesCacheKey(versionCode, requestLocaleTag)
    }
    val preferenceStore = remember { slockSharedPreferenceStore() }
    var state by remember(versionCode, requestLocaleTag) {
        mutableStateOf(
            releaseNotesCachedDocument(preferenceStore.getString(cacheKey))
                ?.let { ReleaseNotesBodyState.Content(it, cached = true) }
                ?: ReleaseNotesBodyState.Loading
        )
    }
    val networkModule = remember(pager) {
        pager.acquireModule<NetworkModule>(NetworkModule.MODULE_NAME)
    }
    LaunchedEffect(versionCode, requestLocaleTag, reloadKey) {
        val result = networkModule.fetchReleaseNotes(versionCode, requestLocaleTag)
        val document =
            if (result.success) {
                releaseNotesDocumentFromResponse(
                    payload = result.data,
                    requestedVersionCode = versionCode,
                    requestedLanguageTag = requestLocaleTag
                )
            } else {
                null
            }
        if (document != null) {
            preferenceStore.putString(cacheKey, releaseNotesCachedDocumentJson(document))
            state = ReleaseNotesBodyState.Content(document, cached = false)
            bridge.log(
                "release_notes_load_success renderer=native versionCode=$versionCode " +
                    "lang=${document.languageTag} cached=false"
            )
        } else if (state !is ReleaseNotesBodyState.Content) {
            val reason =
                if (result.success) {
                    "missing_exact_release_notes"
                } else {
                    "http_${result.statusCode ?: 0}:${result.errorMessage.take(80)}"
                }
            state = ReleaseNotesBodyState.Error(reason)
            bridge.logWarn(
                "release_notes_load_failure renderer=native versionCode=$versionCode reason=$reason"
            )
        } else {
            bridge.logWarn(
                "release_notes_load_stale renderer=native versionCode=$versionCode " +
                    "status=${result.statusCode ?: 0}"
            )
        }
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
                        onOpenUrl = { url -> linkNavigator.open(url) }
                    )
                }
            }
        is ReleaseNotesBodyState.Error ->
            Box(
                modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceCream),
                contentAlignment = Alignment.Center
            ) {
                SlockRaftUiEmptyState(
                    title = errorTitle,
                    description = errorBody,
                    iconText = "!",
                    modifier = Modifier.fillMaxSize(),
                    action = {
                        SlockBrutalActionButton(
                            label = retryLabel,
                            onClick = {
                                state = ReleaseNotesBodyState.Loading
                                reloadKey += 1
                            },
                            leadingIcon = SlockLucideIconName.RefreshCw,
                            backgroundColor = SlockColor.SurfaceWhite
                        )
                    }
                )
            }
    }
}

private const val ReleaseNotesApiUrl =
    "https://hands.build/public/v2/apps/raft-android/release-notes"
private const val RELEASE_NOTES_HEADER_Z_INDEX = 1f
private const val RELEASE_NOTES_BODY_Z_INDEX = 0f

internal fun releaseNotesRequestLocaleTag(): String? =
    try {
        when (val locale = KeyValueLocalePreferenceStore(slockSharedPreferenceStore()).readLocaleSync()) {
            AppLocale.System -> null
            else -> locale.kmmResourceLocaleTag
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        AppLocale.Default.kmmResourceLocaleTag
    }

private suspend fun NetworkModule.fetchReleaseNotes(
    versionCode: String,
    languageTag: String?
): ReleaseNotesNetworkResult =
    suspendCancellableCoroutine { continuation ->
        httpRequest(
            url = ReleaseNotesApiUrl,
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

internal fun releaseNotesApiRequestParams(
    versionCode: String,
    languageTag: String?
): JSONObject =
    JSONObject().apply {
        releaseNotesSanitizeVersionCode(versionCode).takeIf { it.isNotBlank() }?.let {
            put("version_code", it)
        }
        languageTag?.trim()?.takeIf { it.isNotBlank() }?.let { put("lang", it) }
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
    "releaseNotes.native.v1:${releaseNotesSanitizeVersionCode(versionCode)}:${languageTag ?: "system"}"

internal fun releaseNotesCachedDocumentJson(document: ReleaseNotesDocument): String =
    JSONObject()
        .put("versionCode", document.versionCode)
        .put("versionName", document.versionName)
        .put("markdown", document.markdown)
        .put("languageTag", document.languageTag)
        .toString()

internal fun releaseNotesCachedDocument(raw: String?): ReleaseNotesDocument? =
    raw?.takeIf { it.isNotBlank() }?.let { value ->
        try {
            val payload = JSONObject(value)
            val versionCode = payload.optLong("versionCode")
            val markdown = payload.optString("markdown").trim()
            if (versionCode <= 0 || markdown.isBlank()) return@let null
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
