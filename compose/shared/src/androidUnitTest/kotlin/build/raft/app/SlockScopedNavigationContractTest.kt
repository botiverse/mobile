package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class SlockScopedNavigationContractTest {
    @Test
    fun shellOwnedConversationRoutesUseScopedNavigationFacade() {
        val commonMain = locateCommonMain()
        val directOpen = Regex(
            """\.openPage\(\s*(?:THREAD_PAGE|THREAD_MEMBERS_PAGE|MEMBER_DETAIL_PAGE|\"compose_thread\")"""
        )
        val violations =
            commonMain
                .walkTopDown()
                .filter { file -> file.isFile && file.extension == "kt" }
                .filterNot { file -> file.name == "SlockShellStoreLaunch.kt" }
                .flatMap { file ->
                    directOpen.findAll(file.readText().replace("\r\n", "\n"))
                        .map { match -> "${file.relativeTo(commonMain).invariantSeparatorsPath}: `${match.value}`" }
                }
                .toList()

        assertTrue(
            violations.isEmpty(),
            "Shell-owned Thread/Member routes must use Pager.openSlockPage so source shell ownership " +
                "cannot fall back to the process last-used server:\n${violations.joinToString("\n")}",
        )
    }

    private fun locateCommonMain(): File = requireNotNull(
        listOf(File("src/commonMain/kotlin"), File("shared/src/commonMain/kotlin"))
            .firstOrNull(File::isDirectory),
    ) { "Could not locate shared commonMain Kotlin sources" }
}
