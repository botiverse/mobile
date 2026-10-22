package build.raft.app.shared

import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

internal data class AndroidAttachmentOpenIntents(
    val targetIntent: Intent,
    val launchIntent: Intent
)

internal fun androidAttachmentCreateDocumentIntent(
    source: AndroidAttachmentContentUriSource
): Intent =
    Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = source.mimeType
        putExtra(Intent.EXTRA_TITLE, source.displayName)
        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    }

internal fun copyAndroidAttachmentToDocument(
    resolver: ContentResolver,
    source: AndroidAttachmentContentUriSource,
    destination: Uri
): Long {
    val sourceUri = Uri.parse(source.contentUri)
    require(sourceUri != destination) { "Attachment source and Save As destination must differ" }
    val input = resolver.openInputStream(sourceUri)
        ?: error("Attachment source is unavailable")
    val output = resolver.openOutputStream(destination, "w")
        ?: run {
            input.close()
            error("Attachment destination is unavailable")
        }
    return input.use { openedInput ->
        output.use { openedOutput ->
            openedInput.copyTo(openedOutput).also { openedOutput.flush() }
        }
    }
}

internal fun androidAttachmentDocumentMatchesSource(
    resolver: ContentResolver,
    source: AndroidAttachmentContentUriSource,
    destination: Uri
): Boolean {
    val sourceUri = Uri.parse(source.contentUri)
    if (sourceUri == destination) return false
    val sourceInput = resolver.openInputStream(sourceUri) ?: return false
    val destinationInput = resolver.openInputStream(destination) ?: run {
        sourceInput.close()
        return false
    }
    return sourceInput.use sourceUse@ { expected ->
        destinationInput.use destinationUse@ { actual ->
            val expectedBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
            val actualBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var comparedBytes = 0L
            while (true) {
                val expectedCount = expected.read(expectedBuffer)
                val actualCount = actual.read(actualBuffer)
                if (expectedCount != actualCount) return@destinationUse false
                if (expectedCount < 0) return@destinationUse comparedBytes == source.sizeBytes
                for (index in 0 until expectedCount) {
                    if (expectedBuffer[index] != actualBuffer[index]) return@destinationUse false
                }
                comparedBytes += expectedCount
                if (comparedBytes > source.sizeBytes) return@destinationUse false
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }
    }
}

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
            if (spec.action == AndroidAttachmentOpenIntentAction.Send) {
                type = spec.source.mimeType
                putExtra(Intent.EXTRA_STREAM, attachmentUri)
                putExtra(Intent.EXTRA_TITLE, spec.source.displayName)
            } else {
                setDataAndType(attachmentUri, spec.source.mimeType)
            }
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
