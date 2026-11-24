package build.raft.app

import build.raft.app.resources.*
import build.raft.app.data.AppLocale
import build.raft.app.data.kmmResourceLocaleTag
import build.raft.app.data.stringDescLocaleType
import build.raft.app.network.raftValidTraceIdOrNull
import org.jetbrains.compose.resources.StringResource

@RaftCoreInternalApi fun appUpdateDetailStringDesc(
    updateAvailable: Boolean,
    sizeLabel: String?,
    patchSizeLabel: String? = null
): StringDesc =
    when {
        !updateAvailable -> StringDesc.Resource(Res.string.app_update_detail_current)
        !patchSizeLabel.isNullOrBlank() && !sizeLabel.isNullOrBlank() ->
            Res.string.app_update_detail_available_with_patch_and_full_sizes
                .format(listOf(patchSizeLabel, sizeLabel))
        !patchSizeLabel.isNullOrBlank() ->
            Res.string.app_update_detail_available_with_patch_size.format(listOf(patchSizeLabel))
        sizeLabel.isNullOrBlank() -> StringDesc.Resource(Res.string.app_update_detail_available)
        else -> Res.string.app_update_detail_available_with_size.format(listOf(sizeLabel))
    }

/**
 * Localized strings for native host layers.
 *
 * Native Android/iOS surfaces resolve the same KMM resources as Compose. The
 * process-local locale is published from the MMKV-backed app-language setting,
 * so hosts use this facade instead of their platform resource locale. Public
 * on purpose: Kotlin/Native exports it as `SharedRaftHostStrings` for
 * Objective-C callers.
 */
object RaftHostStrings {
    fun feedbackPromptTitle(): String = resolve(Res.string.settings_feedback_title)

    fun feedbackPromptMessage(): String = resolve(Res.string.host_feedback_prompt_message)

    fun feedbackPromptPlaceholder(): String = resolve(Res.string.host_feedback_prompt_placeholder)

    fun feedbackCancel(): String = resolve(Res.string.common_cancel)

    fun feedbackSend(): String = resolve(Res.string.common_send)

    fun feedbackFailedTitle(): String = resolve(Res.string.settings_feedback_submission_failed_title)

    fun feedbackErrorUnknown(): String = resolve(Res.string.host_feedback_error_unknown)

    fun feedbackFailedMessage(detail: String?): String {
        return resolve(
            Res.string.host_feedback_failed_message
                .format(listOf(detail?.takeIf { it.isNotBlank() } ?: feedbackErrorUnknown()))
        )
    }

    fun feedbackSentTitle(): String = resolve(Res.string.host_feedback_sent_title)

    fun feedbackCopiedMessage(): String = resolve(Res.string.host_feedback_copied_message)

    fun appUpdateDetail(
        updateAvailable: Boolean,
        sizeLabel: String?,
        patchSizeLabel: String? = null
    ): String = resolve(appUpdateDetailStringDesc(updateAvailable, sizeLabel, patchSizeLabel))

    fun appLocaleLanguageTag(): String? =
        (KmmResourceLocaleRuntime.locale.value ?: AppLocale.Default).kmmResourceLocaleTag

    fun mobileOAuthAccountConflict(providerName: String): String =
        resolve(Res.string.auth_error_mobile_oauth_account_conflict.format(listOf(providerName)))

    fun mobileOAuthProviderConflict(providerName: String): String =
        resolve(Res.string.auth_error_mobile_oauth_provider_conflict.format(listOf(providerName)))

    fun ok(): String = resolve(Res.string.common_ok)

    /** Sign-in fallback when a sign-in or linking result carries no message. */
    fun signInFailed(): String = resolve(Res.string.auth_error_sign_in_failed)

    fun runtimeStarting(): String = resolve(Res.string.auth_error_runtime_starting)

    fun mobileOAuthLinkNoBrowser(providerName: String): String =
        resolve(Res.string.auth_error_mobile_oauth_link_no_browser.format(listOf(providerName)))

    fun mobileOAuthLinkOpenFailed(providerName: String): String =
        resolve(Res.string.auth_error_mobile_oauth_link_open_failed.format(listOf(providerName)))

    fun appUpdateDownloading(): String = resolve(Res.string.app_update_detail_downloading)

    fun appUpdateDownloaded(): String = resolve(Res.string.app_update_detail_downloaded)

    fun appUpdateInstallerOpened(): String = resolve(Res.string.app_update_detail_installer_opened)

    fun appUpdateInstallPermissionRequired(): String = resolve(Res.string.app_update_detail_install_permission_required)

    /** APK attachment Install without "Install unknown apps": Android restarts Raft when that switch changes. */
    fun apkInstallPermissionTitle(): String = resolve(Res.string.host_apk_install_permission_title)

    fun apkInstallPermissionMessage(): String = resolve(Res.string.host_apk_install_permission_message)

    fun apkInstallPermissionOpenSettings(): String = resolve(Res.string.host_apk_install_permission_open_settings)

    fun apkInstallPermissionCancel(): String = resolve(Res.string.common_cancel)

    fun appUpdateInstallerFailed(): String = resolve(Res.string.app_update_detail_installer_failed)

    fun appUpdateInstallFailed(): String = resolve(Res.string.app_update_detail_install_failed)

    /** [desc] in the app language, for shared code that hands text to a native host. */
    @RaftCoreInternalApi fun text(desc: StringDesc): String = resolve(desc)

    fun shareOpenFailed(): String = resolve(Res.string.share_open_failed)

    /** Appends the localized short `X-Trace-Id` line when [traceId] is valid, for native host notices/toasts. */
    fun errorWithTraceId(message: String, traceId: String?): String =
        raftFormatHostErrorWithTraceId(message, traceId) { shortId ->
            resolve(Res.string.error_trace_id_value.format(listOf(shortId)))
        }

    /** Normalized 32-char lowercase trace ID for clipboard copy in native host alerts, or null when invalid. */
    fun validTraceIdOrNull(traceId: String?): String? = raftValidTraceIdOrNull(traceId)

    /** Button label to copy a trace ID in native host alerts (avoid Objective-C `copy*` ARC method-family mangling). */
    fun traceIdCopyAction(): String = resolve(Res.string.error_trace_id_copy)

    private fun resolve(resource: StringResource): String {
        return resolve(StringDesc.Resource(resource))
    }

    private fun resolve(desc: StringDesc): String {
        applyRuntimeLocale()
        return desc.resolve()
    }

    private fun applyRuntimeLocale() {
        KmmResourceLocaleRuntime.locale.value?.let { StringDesc.localeType = it.stringDescLocaleType }
    }
}

/** Pure formatter backing [RaftHostStrings.errorWithTraceId], unit-testable without platform resource loading. */
@RaftCoreInternalApi fun raftFormatHostErrorWithTraceId(
    message: String,
    traceId: String?,
    formatTraceLine: (shortId: String) -> String,
): String {
    val valid = raftValidTraceIdOrNull(traceId) ?: return message
    val line = formatTraceLine(raftShortTraceId(valid))
    return if (message.isBlank()) line else "$message\n$line"
}
