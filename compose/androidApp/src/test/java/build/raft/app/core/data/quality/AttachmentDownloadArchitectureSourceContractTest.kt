package build.raft.app.core.data.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentDownloadArchitectureSourceContractTest {
    @Test
    fun androidUsesSystemOwnerWhileIosAndOhosKeepCommonCoordinator() {
        val root = findRepoRoot()
        val common =
            File(root, "compose/shared/src/commonMain/kotlin/build/raft/app/network/AttachmentDownloadCoordinator.kt")
                .readText()
        val android =
            File(root, "compose/androidApp/src/main/java/build/raft/app/shared/SlockKuiklyActivity.kt")
                .readText()
        val androidSystem =
            File(root, "compose/androidApp/src/main/java/build/raft/app/shared/AndroidAttachmentSystemDownloads.kt")
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
        val filePreview =
            File(root, "compose/shared/src/commonMain/kotlin/build/raft/app/SlockFilePreview.kt")
                .readText()
        val thread =
            File(root, "compose/shared/src/commonMain/kotlin/build/raft/app/ThreadPage.kt")
                .readText()
        val owner =
            File(root, "compose/shared/src/commonMain/kotlin/build/raft/app/AttachmentDownloadOwner.kt")
                .readText()
        val atomicSink =
            File(root, "compose/shared/src/commonMain/kotlin/build/raft/app/network/AttachmentDownloadAtomicFileSink.kt")
                .readText()

        assertTrue(common.contains("client.downloadStream("))
        assertTrue(common.contains("object AttachmentDownloadCoordinator"))
        assertFalse(android.contains("AttachmentDownloadCoordinator.start("))
        assertFalse(android.contains("AttachmentDownloadCoordinator.beginTargetCleanup"))
        assertFalse(android.contains("AttachmentDownloadCoordinator.finishTargetCleanup"))
        assertTrue(android.contains("AndroidAttachmentSystemDownloads(appContext)"))
        assertTrue(androidSystem.contains("DownloadManager.Request("))
        assertTrue(androidSystem.contains("AndroidSharedPreferencesDownloadRecordStore"))
        assertTrue(androidSystem.contains("recordStore.write(record)"))
        assertTrue(androidSystem.contains("publishIfCurrent(record, targetFile)"))
        assertTrue(androidSystem.contains("synchronized(targetLock(record.targetKey))"))
        assertTrue(androidSystem.contains("recordStore.read(record.targetKey)"))
        assertTrue(androidSystem.contains(".put(\"ownerKey\", record.ownerKey)"))
        assertTrue(androidSystem.contains("androidAttachmentOwnerKey(ownerFingerprint"))
        assertTrue(android.contains("request.attachmentOwnerKey()"))
        assertTrue(android.contains("return \"\$ownerKey-\$safeId-\$withExtension\""))
        assertTrue(android.contains("!it.name.contains(\".part\")"))
        assertTrue(ios.contains("SharedAttachmentDownloadCoordinator.shared"))
        assertTrue(ios.contains("attachmentDownloadOwnerChanged:"))
        assertTrue(ohos.contains("slockOhosAttachmentDownloadSink.download("))
        assertTrue(ohos.contains("ATTACHMENT_OWNER_CHANGED"))
        assertFalse(ios.contains("NSURLSession.sharedSession downloadTaskWithURL"))
        assertFalse(ohos.contains("requestInStream"))
        assertFalse(ohos.contains("createHttp"))
        assertTrue(ohos.contains("!name.includes('.part')"))
        assertTrue(jsSink.contains("override fun begin(): Boolean = false"))
        assertTrue(bridge.contains("continuation.invokeOnCancellation"))
        assertTrue(bridge.contains("methodArgs.put(\"ownerFingerprint\", ownerFingerprint)"))
        assertFalse(bridge.contains("expectedAttemptToken = activeAttemptToken"))
        assertTrue(bridge.contains("methodArgs.put(\"commonHandled\", true)"))
        assertTrue(android.contains("attachmentDownloadsExportCoordinator.control("))
        assertTrue(android.contains("AttachmentDownloadsExportRequest("))
        assertTrue(android.contains("ownerKey = preparation.ownerKey"))
        assertTrue(android.contains("ownerGeneration = preparation.ownerGeneration"))
        assertTrue(android.contains("attachmentOwnerStillCurrent(preparation.ownerKey, preparation.ownerGeneration)"))
        assertTrue(android.contains("attachmentDownloadsExportCoordinator.updateOwnerBinding(ownerKey, ownerGeneration)"))
        assertTrue(android.contains("attachmentOwnerFence.runIfCurrent(prepared.ownerKey, prepared.ownerGeneration)"))
        assertTrue(filePreview.contains("attachmentOperationCommitAllowed("))
        assertTrue(filePreview.contains("currentAttachmentDownloadOwnerFingerprint() != ownerFingerprint"))
        assertTrue(thread.contains("attachmentOperationCommitAllowed("))
        assertTrue(thread.contains("attachmentOpenStates[attachment.id]?.downloadId != downloadId"))
        assertTrue(owner.contains("ownerFingerprint.encodeToByteArray()"))
        assertFalse(owner.contains("ownerFingerprint.hashCode()"))
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
