package build.raft.app.core.data.quality

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsArchiveRebrandSourceContractTest {
    @Test
    fun allHostsUseOnlyRaftDiagnosticsStorageAndArchiveNames() {
        val root = findRepoRoot()
        val android = File(root, "compose/androidApp/src/main/java/build/raft/app/core/data/diagnostics/SlockAndroidLocalDiagnostics.kt").readText()
        val androidRing = File(root, "compose/androidApp/src/main/java/build/raft/app/core/data/diagnostics/SlockGeneralLogRing.kt").readText()
        val androidAuth = File(root, "compose/androidApp/src/main/java/build/raft/app/core/data/diagnostics/SlockAuthCriticalJournal.kt").readText()
        val filePaths = File(root, "compose/androidApp/src/main/res/xml/slock_file_paths.xml").readText()
        val ios = File(root, "compose/iosApp/iosApp/KuiklyExpand/Modules/HRBridgeModule.m").readText()
        val iosAdmission = File(root, "compose/iosApp/iosApp/KuiklyExpand/Modules/RaftDiagnosticsPathAdmission.h").readText()
        val iosAdmissionTest = File(root, "compose/iosApp/scripts/test_diagnostics_path_admission.m").readText()
        val iosWorkflow = File(root, ".github/workflows/ios-release.yml").readText()
        val ohos = File(root, "compose/ohosApp/entry/src/main/ets/entryability/SlockOhosAppDiagnostics.ets").readText()

        listOf(android, ios, ohos).forEach { source ->
            assertTrue(source.contains("raft-feedback-"))
            assertTrue(source.contains("raft-diagnostics-summary.txt"))
            assertTrue(!source.contains("slock-feedback-"))
        }
        listOf(androidRing, ios, ohos).forEach { source ->
            assertTrue(source.contains("raft-diagnostics.jsonl"))
            assertTrue(!source.contains("slock-diagnostics.jsonl"))
        }
        assertTrue(androidRing.contains("raft_diagnostics_logs"))
        assertTrue(!androidRing.contains("slock_diagnostics_logs"))
        assertTrue(androidAuth.contains("raft-auth-critical.jsonl"))
        assertTrue(!androidAuth.contains("slock-auth-critical.jsonl"))
        assertTrue(filePaths.contains("path=\"raft-feedback/\""))
        assertTrue(!filePaths.contains("path=\"slock-feedback/\""))
        assertTrue(ios.contains("SlockDiagnosticsUniqueSnapshotPath"))
        assertTrue(ios.contains("SlockDiagnosticsComparePathsByMtimeThenPath"))
        assertTrue(ios.contains("SlockDiagnosticsCanonicalDescendantFilePath"))
        assertTrue(iosAdmission.contains("NSFileTypeRegular"))
        assertTrue(iosAdmission.contains("destinationOfSymbolicLinkAtPath"))
        assertTrue(iosAdmissionTest.contains("intermediate symlink must fail closed"))
        assertTrue(iosAdmissionTest.contains("FIFO must fail closed"))
        assertTrue(iosAdmissionTest.contains("socket must fail closed"))
        assertTrue(iosAdmissionTest.contains("character device must fail closed"))
        assertTrue(iosWorkflow.contains("test_diagnostics_path_admission.sh"))
        assertTrue(ios.contains("removeItemAtPath:snapshotLogPath"))
        assertTrue(ohos.contains("ohosUniqueDiagnosticsStagedName"))
        assertTrue(ohos.contains("isOhosSafeDiagnosticsArchiveComponent"))
        assertTrue(ohos.contains("fs.lstatSync"))
        assertTrue(ohos.contains("isOhosDiagnosticsDescendantPath"))
        assertTrue(ohos.contains("collectOhosDiagnosticsSourceFiles"))
        assertTrue(ohos.contains("compareOhosDiagnosticsPath"))
        assertTrue(ohos.contains("selectOhosDiagnosticsCandidateGroups"))
        assertTrue(ohos.contains("fs.unlinkSync(targetPath)"))
        assertTrue(ohos.contains("clearLocalFiles"))
        val ohosBridge = File(root, "compose/ohosApp/entry/src/main/ets/kuikly/modules/KRBridgeModule.ets").readText()
        assertTrue(ohosBridge.contains("SlockOhosAppDiagnostics.clearLocalFiles"))
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
