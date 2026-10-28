package build.raft.app.shared

import android.app.DownloadManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HandsUpdateDownloadCleanupTest {
    private val dir = "file:///storage/emulated/0/Android/data/build.raft.app/files/Download/"

    @Test
    fun parsesTheUpdaterFileNameBack() {
        assertEquals(11601002L, handsApkDownloadVersionCode(dir + handsApkDownloadFileName(11601002L)))
        assertEquals(11601002L, handsApkDownloadVersionCode("raft-hands-11601002-1.apk"))
        assertNull(handsApkDownloadVersionCode(dir + "raft 1.16.1.apk"))
        assertNull(handsApkDownloadVersionCode(dir + "raft-hands-11601002.apk.partial"))
        assertNull(handsApkDownloadVersionCode(null))
    }

    @Test
    fun removesFinishedUpdatesForInstalledOrOlderVersions() {
        val rows =
            listOf(
                // artin's case: the 1.16.1 update APK, finished, while 1.17.0 runs.
                HandsApkDownloadRow(1, DownloadManager.STATUS_SUCCESSFUL, dir + "raft-hands-11601002.apk"),
                HandsApkDownloadRow(2, DownloadManager.STATUS_FAILED, dir + "raft-hands-11600001.apk"),
                // The running version's own APK: installed, so also obsolete.
                HandsApkDownloadRow(3, DownloadManager.STATUS_SUCCESSFUL, dir + "raft-hands-11700001.apk"),
                // A newer update not installed yet: its notification is still the way to install it.
                HandsApkDownloadRow(4, DownloadManager.STATUS_SUCCESSFUL, dir + "raft-hands-11700002.apk"),
                // Still downloading.
                HandsApkDownloadRow(5, DownloadManager.STATUS_RUNNING, dir + "raft-hands-11601003.apk"),
                // Not an updater APK.
                HandsApkDownloadRow(6, DownloadManager.STATUS_SUCCESSFUL, dir + "report.pdf"),
                HandsApkDownloadRow(7, DownloadManager.STATUS_SUCCESSFUL, null)
            )

        assertEquals(listOf(1L, 2L, 3L), staleHandsApkDownloadIds(rows, installedVersionCode = 11700001L))
    }

    @Test
    fun nothingToRemoveWhenOnlyANewerUpdateIsWaiting() {
        val rows = listOf(HandsApkDownloadRow(9, DownloadManager.STATUS_SUCCESSFUL, dir + "raft-hands-11700002.apk"))

        assertEquals(emptyList<Long>(), staleHandsApkDownloadIds(rows, installedVersionCode = 11700001L))
    }
}
