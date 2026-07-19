package build.raft.app

import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.MR
import com.tencent.tmm.kmmresource.resource.StringResource
import com.tencent.tmm.kmmresource.resource.desc.Resource
import com.tencent.tmm.kmmresource.resource.desc.StringDesc
import com.tencent.tmm.kmmresource.resource.format

internal expect fun slockResolveHostString(desc: StringDesc): String

/**
 * Localized strings for native host layers.
 *
 * Native Android/iOS surfaces resolve the same KMM resources as Compose. The
 * process-local locale is published from the MMKV-backed app-language setting,
 * so hosts use this facade instead of their platform resource locale. Public
 * on purpose: Kotlin/Native exports it as `SharedSlockHostStrings` for
 * Objective-C callers.
 */
object SlockHostStrings {
    fun feedbackPromptTitle(): String = resolve(MR.strings.settings_feedback_title)

    fun feedbackPromptMessage(): String = resolve(MR.strings.host_feedback_prompt_message)

    fun feedbackPromptPlaceholder(): String = resolve(MR.strings.host_feedback_prompt_placeholder)

    fun feedbackCancel(): String = resolve(MR.strings.common_cancel)

    fun feedbackSend(): String = resolve(MR.strings.common_send)

    fun feedbackFailedTitle(): String = resolve(MR.strings.settings_feedback_submission_failed_title)

    fun feedbackErrorUnknown(): String = resolve(MR.strings.host_feedback_error_unknown)

    fun feedbackFailedMessage(detail: String?): String {
        return resolve(
            MR.strings.host_feedback_failed_message
                .format(listOf(detail?.takeIf { it.isNotBlank() } ?: feedbackErrorUnknown()))
        )
    }

    fun feedbackSentTitle(): String = resolve(MR.strings.host_feedback_sent_title)

    fun feedbackCopiedMessage(): String = resolve(MR.strings.host_feedback_copied_message)

    fun ok(): String = resolve(MR.strings.common_ok)

    private fun resolve(resource: StringResource): String {
        return resolve(StringDesc.Resource(resource))
    }

    private fun resolve(desc: StringDesc): String {
        applyRuntimeLocale()
        return slockResolveHostString(desc)
    }

    private fun applyRuntimeLocale() {
        KmmResourceLocaleRuntime.locale.value?.let { StringDesc.localeType = it.stringDescLocaleType }
    }
}
