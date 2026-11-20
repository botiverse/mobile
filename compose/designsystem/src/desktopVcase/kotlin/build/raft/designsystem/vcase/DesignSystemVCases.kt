package build.raft.designsystem.vcase

import dev.vcase.compose.desktop.VCaseDesktopTest

/**
 * Every @VCase in build.raft.designsystem.vcase, rendered with [RaftVCaseHost] and checked
 * against screenshots/vcase/desktop-skiko/ (zero tolerance) and screenshots/vcase/vcase.manifest.json.
 * Verified by desktopTest; recorded by recordRoborazziDesktop or -Pvcase.mode=record (CI only).
 */
class DesignSystemVCases : VCaseDesktopTest() {
    override val host = RaftVCaseHost
    override val packages = listOf("build.raft.designsystem.vcase")
    override val sourceRoot = "compose/designsystem/src/desktopVcase/kotlin"
}
