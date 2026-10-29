package build.raft.app.shared

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.Settings
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

    fun gate(context: Context, attachmentIdPrefix: String): AndroidPackageInstallGate =
        AndroidPackageInstallGate(
            installPermissionDeclared = { isDeclared(context) },
            canRequestPackageInstalls = { canRequest(context) },
            openUnknownSourcesSettings = { openUnknownSourcesSettings(context) },
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
