package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageBodyFontSizeProductionContractTest {
    @Test
    fun realMessageRenderersConsumeObservablePreferenceStyle() {
        val source = repositoryFile("compose/shared/src/commonMain/kotlin/build/raft/app/ThreadPage.kt").readText()

        assertFalse(source.contains("ThreadMessageBodyTextStyle"))
        assertTrue(source.contains("messageBodyFontSizeStore.state.collectAsState()"))
        assertTrue(source.contains("messageBodyTextStyle = resolvedMessageBodyTextStyle"))
        assertTrue(source.contains("messageBodyTextStyle: TextStyle,"))
        assertTrue(source.countOccurrences("style = messageBodyTextStyle") >= 5)
        assertTrue(source.countOccurrences("textStyle = messageBodyTextStyle") >= 2)
        assertTrue(source.contains("ThreadMessageTextSelectionDialog(\n                    message = message,\n                    messageBodyTextStyle = resolvedMessageBodyTextStyle"))
        assertTrue(source.contains("ThreadForwardBundleCard(\n                                bundle = forwardBundle,\n                                messageBodyTextStyle = messageBodyTextStyle"))
    }

    private fun repositoryFile(relativePath: String): File {
        val start = File(System.getProperty("user.dir") ?: ".")
        return generateSequence(start) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull(File::isFile)
            ?: error("Unable to resolve $relativePath from ${start.absolutePath}")
    }
}

private fun String.countOccurrences(value: String): Int =
    windowed(size = value.length, step = 1, partialWindows = false).count { it == value }
