package build.raft.app

import build.raft.app.resources.*
import build.raft.app.data.AppLocale
import build.raft.app.data.kmmResourceLocaleTag
import build.raft.app.data.stringDescLocaleType
import org.jetbrains.compose.resources.StringResource

internal fun appUpdateDetailStringDesc(
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

    fun shareOpenFailed(): String = resolve(Res.string.share_open_failed)

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
