package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ThreadPageForwardDeliveryProductionContractTest {
    @Test
    fun threadPageRoutesReturnedForwardMessagesThroughTheRealDeliveryConsumer() {
        val source = repositoryFile("compose/shared/src/commonMain/kotlin/build/raft/app/ThreadPage.kt").readText()

        assertTrue(source.contains("onForwardMessagesDelivered = { result ->"))
        assertTrue(source.contains("applyForwardMessageDeliveries("))
        assertTrue(source.contains("messageStore = sharedStores.threadMessageStore"))
        assertTrue(source.contains("onHomeRefresh = { sharedHomeStore?.dispatch(SharedHomeIntent.ForegroundRefresh) }"))
    }

    private fun repositoryFile(relativePath: String): File {
        val start = File(System.getProperty("user.dir") ?: ".")
        return generateSequence(start) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull(File::isFile)
            ?: error("Unable to resolve $relativePath from ${start.absolutePath}")
    }
}
