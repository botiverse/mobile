package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlockCollapsibleSectionHeaderProductionContractTest {
    @Test
    fun homeAndMembersShareTheSingleGlobalSectionHeaderImplementation() {
        val home = source("build/raft/app/SlockHomeComponents.kt")
        val members = source("build/raft/app/MembersPage.kt")

        assertEquals(4, home.count("SlockCollapsibleSectionHeader("))
        assertEquals(2, members.count("SlockCollapsibleSectionHeader("))
    }

    @Test
    fun globalCountSlotConsumesTheReactAlignedTokensWithoutExtraPadding() {
        val source = source("build/raft/app/SlockSectionHeader.kt")
        val block =
            source.substring(
                startIndex = source.indexOf("internal fun SlockCollapsibleSectionHeader(").also {
                    assertTrue(it >= 0, "Missing SlockCollapsibleSectionHeader")
                },
                endIndex = source.indexOf("internal fun SlockSectionHeaderIconAction(").also {
                    assertTrue(it >= 0, "Missing SlockSectionHeaderIconAction")
                }
            )

        assertContains(block, "Arrangement.spacedBy(SlockCollapsibleSectionContentGap)")
        assertContains(block, "style = SlockCollapsibleSectionCountTextStyle")
        assertContains(block, "color = SlockCollapsibleSectionCountColor")
        assertTrue("modifier = Modifier.padding(start = SlockSpacing.Xs)" !in block)
    }

    private fun String.count(needle: String): Int = split(needle).size - 1

    private fun source(path: String): String {
        val commonMain =
            listOf(File("src/commonMain/kotlin"), File("shared/src/commonMain/kotlin"))
                .firstOrNull(File::isDirectory)
                ?: error("Could not locate shared commonMain Kotlin sources")
        val file = File(commonMain, path)
        assertTrue(file.isFile, "Missing production source $path")
        return file.readText().replace("\r\n", "\n")
    }
}
