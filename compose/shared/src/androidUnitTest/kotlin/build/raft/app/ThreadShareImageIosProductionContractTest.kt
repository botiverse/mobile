package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ThreadShareImageIosProductionContractTest {
    @Test
    fun threadSelectionExportUsesTheIosProductionBridgeSelectors() {
        val threadPage = sharedSource("build/raft/app/ThreadPage.kt")
        val bridgeModule = sharedSource("build/raft/app/base/BridgeModule.kt")
        val iosBridge = iosBridgeSource()
        val exportSelector = iosBridge.objectiveCMethod("exportThreadShareImage")
        val saveSelector = iosBridge.objectiveCMethod("saveThreadShareImage")

        assertTrue(threadPage.contains("bridge.exportThreadShareImage("))
        assertTrue(threadPage.contains("bridge.saveThreadShareImage(path)"))
        assertTrue(threadPage.contains("bridge.toast(imageSavedLabel)"))
        assertTrue(threadPage.contains("bridge.toast(unableToExportImageLabel)"))
        assertTrue(bridgeModule.contains("callNativeMethod(\"exportThreadShareImage\""))
        assertTrue(bridgeModule.contains("callNativeMethod(\"saveThreadShareImage\""))
        assertTrue(exportSelector.contains("SlockThreadShareCaptureView(self.hr_rootView, params)"))
        assertTrue(exportSelector.contains("SlockThreadShareStitchFragments(fragmentPaths)"))
        assertTrue(exportSelector.contains("SlockThreadShareWriteImage(output, frame)"))
        assertTrue(saveSelector.contains("creationRequestForAssetFromImageAtFileURL:fileURL"))
        assertTrue(saveSelector.contains("callback(@{ @\"status\" : @\"ok\""))
    }

    @Test
    fun iosCaptureConvertsComposePixelsToUIKitPointsAndReturnsPixelDimensions() {
        val source = iosBridgeSource()

        assertTrue(source.contains("requestedX) / scale"))
        assertTrue(source.contains("requestedY) / scale"))
        assertTrue(source.contains("requestedWidth / scale"))
        assertTrue(source.contains("requestedHeight / scale"))
        assertTrue(source.contains("@\"width\" : @(CGImageGetWidth(cgImage))"))
        assertTrue(source.contains("@\"height\" : @(CGImageGetHeight(cgImage))"))
    }

    private fun sharedSource(relativePath: String): String =
        sourceFile(
            "src/commonMain/kotlin/$relativePath",
            "shared/src/commonMain/kotlin/$relativePath",
            "compose/shared/src/commonMain/kotlin/$relativePath"
        ).readText().replace("\r\n", "\n")

    private fun iosBridgeSource(): String =
        sourceFile(
            "../iosApp/iosApp/KuiklyExpand/Modules/HRBridgeModule.m",
            "iosApp/iosApp/KuiklyExpand/Modules/HRBridgeModule.m",
            "compose/iosApp/iosApp/KuiklyExpand/Modules/HRBridgeModule.m"
        ).readText().replace("\r\n", "\n")

    private fun sourceFile(vararg candidates: String): File =
        candidates.map(::File).firstOrNull(File::isFile)
            ?: error("Production source not found; checked ${candidates.joinToString()}")

    private fun String.objectiveCMethod(name: String): String {
        val start = indexOf("- (void)$name:(NSDictionary *)args")
        require(start >= 0) { "Missing production iOS selector $name" }
        val end = indexOf("\n- (", start + 1).takeIf { it >= 0 } ?: length
        return substring(start, end)
    }
}
