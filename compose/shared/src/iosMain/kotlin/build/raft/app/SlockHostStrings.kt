package build.raft.app

import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.MR
import com.tencent.tmm.kmmresource.resource.StringResource
import com.tencent.tmm.kmmresource.resource.desc.Resource
import com.tencent.tmm.kmmresource.resource.desc.StringDesc

/**
 * Localized strings for the NATIVE host layers (task #491).
 *
 * The iOS host (HRBridgeModule) presents UIKit alerts whose copy previously
 * lived as English literals — the app has no Localizable.strings
 * infrastructure, but the kmmresource MR bundle already ships in the app
 * (task #423), so hosts can resolve the shared resources through this object
 * instead. PUBLIC on purpose: it must export into the iOS framework for
 * Objective-C callers (`SharedSlockHostStrings.shared`).
 *
 * Resolution is plain (non-composable): `StringDesc.localeType` is a
 * Kotlin/Native @ThreadLocal, so it is (re)applied on the calling thread from
 * the process-local locale before each lookup — same contract as the compose
 * `stringResource` actual on iOS.
 */
object SlockHostStrings {
    fun feedbackPromptTitle(): String = resolve(MR.strings.settings_feedback_title)

    fun feedbackPromptMessage(): String = resolve(MR.strings.host_feedback_prompt_message)

    fun feedbackPromptPlaceholder(): String = resolve(MR.strings.host_feedback_prompt_placeholder)

    fun feedbackCancel(): String = resolve(MR.strings.common_cancel)

    fun feedbackSend(): String = resolve(MR.strings.common_send)

    fun feedbackFailedTitle(): String = resolve(MR.strings.settings_feedback_submission_failed_title)

    fun feedbackErrorUnknown(): String = resolve(MR.strings.host_feedback_error_unknown)

    fun feedbackSentTitle(): String = resolve(MR.strings.host_feedback_sent_title)

    fun feedbackCopiedMessage(): String = resolve(MR.strings.host_feedback_copied_message)

    fun ok(): String = resolve(MR.strings.common_ok)

    private fun resolve(resource: StringResource): String {
        KmmResourceLocaleRuntime.locale.value?.let { StringDesc.localeType = it.stringDescLocaleType }
        return StringDesc.Resource(resource).localized()
    }
}
