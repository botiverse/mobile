package build.raft.app.core.data.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentDownloadArchitectureSourceContractTest {
    @Test
    fun androidUsesProcessSystemSupervisorWhileIosAndOhosKeepCommonCoordinator() {
        val root = findRepoRoot()
        val common =
            File(root, "compose/shared/src/commonMain/kotlin/build/raft/app/network/AttachmentDownloadCoordinator.kt")
                .readText()
        val owner =
            File(root, "compose/shared/src/commonMain/kotlin/build/raft/app/AttachmentDownloadOwner.kt")
                .readText()
        val android =
            File(root, "compose/androidApp/src/main/java/build/raft/app/shared/SlockKuiklyActivity.kt")
                .readText()
        val androidEngine =
            File(root, "compose/androidApp/src/main/java/build/raft/app/shared/AndroidAttachmentSystemDownloads.kt")
                .readText()
        val androidOwner =
            File(root, "compose/androidApp/src/main/java/build/raft/app/shared/AndroidAttachmentOwnerRegistry.kt")
                .readText()
        val mediaStoreCleanup =
            File(root, "compose/androidApp/src/main/java/build/raft/app/shared/AndroidAttachmentMediaStorePendingRows.kt")
                .readText()
        val filePreview =
            File(root, "compose/shared/src/commonMain/kotlin/build/raft/app/SlockFilePreview.kt")
                .readText()
        val thread =
            File(root, "compose/shared/src/commonMain/kotlin/build/raft/app/ThreadPage.kt")
                .readText()
        val ios =
            File(root, "compose/iosApp/iosApp/KuiklyExpand/Modules/HRBridgeModule.m")
                .readText()
        val ohos =
            File(root, "compose/ohosApp/entry/src/main/ets/kuikly/modules/KRBridgeModule.ets")
                .readText()
        val jsSink =
            File(root, "compose/shared/src/jsMain/kotlin/build/raft/app/network/AttachmentDownloadAtomicSink.js.kt")
                .readText()
        val bridge =
            File(root, "compose/shared/src/commonMain/kotlin/build/raft/app/base/BridgeModule.kt")
                .readText()
        val atomicSink =
            File(root, "compose/shared/src/commonMain/kotlin/build/raft/app/network/AttachmentDownloadAtomicFileSink.kt")
                .readText()

        assertTrue(common.contains("client.downloadStream("))
        assertTrue(common.contains("object AttachmentDownloadCoordinator"))
        assertTrue(owner.contains("ProcessAttachmentAuthScopes = AttachmentAuthScopeRegistry()"))
        assertTrue(owner.contains("tombstones"))
        assertTrue(owner.contains("canonicalIdentityBytes"))
        assertTrue(android.contains("ProcessAndroidAttachmentSystemDownloads.get("))
        assertTrue(androidEngine.contains("object ProcessAndroidAttachmentSystemDownloads"))
        assertTrue(androidEngine.contains("AndroidSystemDownloadPhase.Prepared"))
        assertTrue(androidEngine.contains("backend.enqueue(current.toEnqueueRequest())"))
        assertTrue(androidEngine.contains("AndroidSystemDownloadPhase.Enqueued"))
        assertTrue(androidEngine.contains("recoveryLock(record.targetKey)"))
        assertTrue(androidEngine.contains("rows.filter { it.systemDownloadId != systemId }"))
        assertTrue(androidEngine.contains("filter { it.requestToken !in knownTokens }"))
        assertTrue(androidEngine.contains("setDescription(ANDROID_ATTACHMENT_TOKEN_PREFIX + request.requestToken)"))
        assertTrue(androidEngine.contains("setDestinationUri(Uri.fromFile(File(request.stagingPath)))"))
        assertTrue(android.contains("getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)"))
        assertTrue(androidEngine.contains("FileInputStream(staging).use"))
        assertTrue(androidEngine.contains("output.fd.sync()"))
        assertTrue(androidEngine.contains("privateStaging.renameTo(targetFile)"))
        assertTrue(androidEngine.contains("Private-cache publication is principal-owned"))
        assertTrue(androidOwner.contains("registry read/write lease -> scope gate"))
        assertTrue(androidOwner.contains("runIfCurrent"))
        assertTrue(androidOwner.contains("tombstones"))
        assertTrue(android.contains("ownerRegistry.runIfCurrent(request.owner) { state.backend.publish(uri) }") ||
            File(root, "compose/androidApp/src/main/java/build/raft/app/shared/AttachmentDownloadsExportCoordinator.kt")
                .readText().contains("ownerRegistry.runIfCurrent(request.owner) { state.backend.publish(uri) }"))
        assertTrue(android.contains("ProcessAndroidAttachmentOwners.runIfCurrent(prepared.owner)"))
        assertTrue(android.contains("ProcessAndroidAttachmentOwners.runIfCurrent(pending.owner)"))
        assertTrue(mediaStoreCleanup.contains("ANDROID_ATTACHMENT_PENDING_NAME_PREFIX"))
        assertTrue(mediaStoreCleanup.contains("MediaStore.MediaColumns.IS_PENDING"))
        assertTrue(filePreview.contains("attachmentOperationCommitAllowed"))
        assertTrue(filePreview.contains("Cached opens still cross an irreversible native launch boundary"))
        assertTrue(filePreview.contains("downloadStatus = slockFilePreviewExternalOpenAdmissionStatus()"))
        assertTrue(filePreview.contains("!slockFilePreviewCanStartExternalOperation(downloadStatus)"))
        assertTrue(thread.contains("attachmentOperationCommitAllowed"))
        assertTrue(android.contains("!it.name.contains(\".part\")"))
        assertTrue(ios.contains("SharedAttachmentDownloadCoordinator.shared"))
        assertTrue(ios.contains("attachmentDownloadOwnerChanged"))
        assertTrue(ohos.contains("slockOhosAttachmentDownloadSink.download("))
        assertTrue(ohos.contains("ATTACHMENT_DOWNLOAD_OWNER_CHANGED"))
        assertTrue(ohos.contains("attachmentDownloadOwnerChanged()"))
        assertFalse(ios.contains("NSURLSession.sharedSession downloadTaskWithURL"))
        assertFalse(ohos.contains("requestInStream"))
        assertFalse(ohos.contains("createHttp"))
        assertTrue(ohos.contains("!name.includes('.part')"))
        assertTrue(jsSink.contains("override fun begin(): Boolean = false"))
        assertTrue(bridge.contains("continuation.invokeOnCancellation"))
        assertTrue(bridge.contains("AttachmentDownloadCoordinator.control("))
        assertTrue(bridge.contains("expectedAttemptToken = activeAttemptToken"))
        assertTrue(bridge.contains("methodArgs.put(\"commonHandled\", true)"))
        assertTrue(bridge.contains("methodArgs.putAttachmentOwner(owner)"))
        assertTrue(android.contains("attachmentDownloadsExportCoordinator.control("))
        assertTrue(android.contains("AttachmentDownloadsExportRequest("))
        assertTrue(atomicSink.contains("while (offset < bytes.size)"))
        assertTrue(atomicSink.contains("ops.fsync(activeHandle)"))
        assertTrue(atomicSink.contains("ops.close(activeHandle)"))
        assertTrue(atomicSink.contains("ops.rename(stagingPath, targetPath)"))
        assertFalse(atomicSink.contains("isFile"))
        assertFalse(atomicSink.contains("exists("))
    }

    private fun findRepoRoot(): File {
        var current = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            if (File(current, "compose/settings.gradle.kts").isFile) return current
            current = current.parentFile ?: return@repeat
        }
        error("Unable to locate repository root from ${System.getProperty("user.dir")}")
    }
}
