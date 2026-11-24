package build.raft.app.shared

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.Settings
import build.raft.app.RaftHostStrings
import build.raft.app.core.data.diagnostics.RaftAppLog

/**
 * Platform side of [AndroidPackageInstallGate]: the merged manifest (Play builds remove
 * REQUEST_INSTALL_PACKAGES), the per-app "install unknown apps" switch, and that switch's page.
 */
internal object AndroidPackageInstallPermission {
    private const val LOG_TAG = "SlockAttachmentOpen"
    private const val EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY = "com.android.externalstorage.documents"

    fun isDeclared(context: Context): Boolean =
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                ?.contains(Manifest.permission.REQUEST_INSTALL_PACKAGES) == true
        }.getOrDefault(false)

    fun canRequest(context: Context): Boolean =
        runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    /** Opens Raft's own "install unknown apps" page; falls back to the app details page. */
    fun openUnknownSourcesSettings(context: Context): Boolean {
        val unknownSources =
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        if (runCatching { context.startActivity(unknownSources) }.isSuccess) return true
        val details =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        return runCatching { context.startActivity(details) }.isSuccess
    }

    /**
     * The explanation before the switch (a themed system dialog, as the attachment Save As
     * fallback): Android restarts Raft when "Install unknown apps" changes, so come back and tap
     * Install again. Open Settings leads on; Cancel or Back leaves things as they are.
     */
    private fun showRestartExplanation(activity: Activity?, onOpenSettings: () -> Unit): Boolean {
        val host = activity?.takeUnless { it.isFinishing || it.isDestroyed } ?: return false
        host.runOnUiThread {
            if (host.isFinishing || host.isDestroyed) return@runOnUiThread
            AlertDialog.Builder(host)
                .setTitle(RaftHostStrings.apkInstallPermissionTitle())
                .setMessage(RaftHostStrings.apkInstallPermissionMessage())
                .setPositiveButton(RaftHostStrings.apkInstallPermissionOpenSettings()) { _, _ -> onOpenSettings() }
                .setNegativeButton(RaftHostStrings.apkInstallPermissionCancel(), null)
                .show()
        }
        return true
    }

    /**
     * [AndroidUnknownSourcesSettingsPrompt] for an install that is not an attachment (Hands delta
     * updates on Alpha, Preview, and Main; [gate] covers attachments): the same explanation, then
     * Raft's switch. [beforeOpenSettings] runs just before the page opens, from the explanation's
     * confirm, or at once when there is no window to explain in.
     */
    fun requestUnknownSourcesSettings(
        context: Context,
        activity: Activity?,
        logPrefix: String,
        beforeOpenSettings: () -> Unit,
        record: (String) -> Unit
    ): Boolean =
        AndroidUnknownSourcesSettingsPrompt(
            showExplanation = { onOpenSettings -> showRestartExplanation(activity, onOpenSettings) },
            openSettings = {
                beforeOpenSettings()
                openUnknownSourcesSettings(context)
            },
            record = record,
            logPrefix = logPrefix
        ).request()

    /**
     * Opens the system file manager on `Download/Raft` (where published attachments live), else on
     * the Downloads list. Returns which surface opened, or null when neither could be started.
     * Any file manager that handles a directory document holds the install permission itself.
     */
    fun openDownloadsInFileManager(context: Context): String? {
        val folder =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    DocumentsContract.buildDocumentUri(
                        EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY,
                        "primary:$ANDROID_ATTACHMENT_DOWNLOADS_RELATIVE_PATH"
                    ),
                    DocumentsContract.Document.MIME_TYPE_DIR
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        if (runCatching { context.startActivity(folder) }.isSuccess) return "raft_folder"
        val downloads =
            Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        if (runCatching { context.startActivity(downloads) }.isSuccess) return "downloads"
        return null
    }

    fun gate(context: Context, activity: Activity?, attachmentIdPrefix: String): AndroidPackageInstallGate =
        AndroidPackageInstallGate(
            installPermissionDeclared = { isDeclared(context) },
            canRequestPackageInstalls = { canRequest(context) },
            openUnknownSourcesSettings = {
                AndroidUnknownSourcesSettingsPrompt(
                    showExplanation = { onOpenSettings -> showRestartExplanation(activity, onOpenSettings) },
                    openSettings = { openUnknownSourcesSettings(context) },
                    record = { line -> RaftAppLog.w(LOG_TAG, "$line attachmentId=$attachmentIdPrefix") }
                ).request()
            },
            openDownloadsInFileManager = { openDownloadsInFileManager(context) },
            record = { decision, detail ->
                // warn: kept in release diagnostics, unlike record(); one line per Install tap.
                RaftAppLog.w(
                    LOG_TAG,
                    "attachment_install_gate attachmentId=$attachmentIdPrefix decision=${decision.diagnosticName}" +
                        (detail?.let { " $it" } ?: "")
                )
            }
        )
}
