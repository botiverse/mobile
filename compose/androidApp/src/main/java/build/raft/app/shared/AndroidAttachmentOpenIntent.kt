package build.raft.app.shared

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

internal data class AndroidAttachmentOpenIntents(
    val targetIntent: Intent,
    val launchIntent: Intent
)

internal fun androidAttachmentOpenIntents(
    context: Context,
    spec: AndroidAttachmentOpenLaunchSpec,
    chooserTitle: String
): AndroidAttachmentOpenIntents {
    check(spec.readGrantScope == AndroidAttachmentReadGrantScope.SelectedTarget)
    val attachmentUri = Uri.parse(spec.source.contentUri)
    val launchFlags =
        Intent.FLAG_ACTIVITY_NEW_TASK or
            if (spec.grantsReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0
    val target =
        Intent(spec.action.intentAction).apply {
            setDataAndType(attachmentUri, spec.source.mimeType)
            if (spec.placesContentUriInClipData) {
                clipData = ClipData.newUri(context.contentResolver, spec.source.displayName, attachmentUri)
            }
            addFlags(launchFlags)
            if (spec.action == AndroidAttachmentOpenIntentAction.InstallPackage) {
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_RETURN_RESULT, false)
            }
        }
    val launch =
        if (spec.launchInChooser) {
            Intent.createChooser(target, chooserTitle).apply {
                clipData = target.clipData
                addFlags(launchFlags)
            }
        } else {
            target
        }
    return AndroidAttachmentOpenIntents(targetIntent = target, launchIntent = launch)
}

/** Builds the exact private FileProvider source used by production Open-with. */
internal fun androidAttachmentPrivateOpenSource(
    context: Context,
    file: File,
    displayName: String,
    mimeType: String
): AndroidAttachmentContentUriSource? {
    if (!file.isFile || file.length() <= 0L) return null
    val uri =
        runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
                displayName
            )
        }.getOrNull() ?: return null
    return AndroidAttachmentContentUriSource(
        kind = AndroidAttachmentOpenSourceKind.PrivateCache,
        contentUri = uri.toString(),
        mimeType = mimeType,
        displayName = displayName,
        sizeBytes = file.length(),
        durableCache = false
    )
}
