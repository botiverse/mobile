package build.raft.app.contract

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Runs botiverse/slock's unread contract cases (task #proj-mobile:#70) against the app: one JUnit
 * case per vendored file, named `<id>`. A case either runs through a driver in
 * [UnreadCaseDrivers] and passes or fails on what it asserts, or is reported as skipped (JUnit
 * assumption) with its reason: pending decision, app gap, a case this loader cannot read, or nothing
 * to drive it with yet. Nothing passes unchecked.
 *
 * The copy lives in `src/commonTest/resources/contract-cases/unread/` (the path the slock README
 * names) with its `SOURCE_SHA`; it is listed from the JVM classpath here because commonTest has no
 * portable resource reader. Re-vendor with `scripts/sync_slock_contract_cases.py`.
 */
@RunWith(Parameterized::class)
class UnreadContractCasesTest(private val fileId: String) {
    @Test
    fun case() {
        val decoding = decodeUnreadContractCase(fileId, caseFile(fileId).readText())
        val case =
            when (decoding) {
                is UnreadCaseDecoding.Unreadable -> skip("unreadable: ${decoding.reason}")
                is UnreadCaseDecoding.Decoded -> decoding.case
            }
        assertEquals(fileId, case.id, "id must be the file name")
        when (val plan = planUnreadCase(case)) {
            is UnreadCasePlan.Misconfigured -> fail(plan.reason)
            is UnreadCasePlan.Skip -> skip(plan.reason + gapCheck(case, plan.driver))
            is UnreadCasePlan.Run -> {
                var failures: List<String> = emptyList()
                runTest { failures = with(plan.driver) { run(case) } }
                report("ran (${plan.driver.name})", failures.isEmpty())
                if (failures.isNotEmpty()) fail("${case.id} (${case.title}):\n  " + failures.joinToString("\n  "))
            }
        }
    }

    /** For a gap the app could drive: whether it still differs, so a closed gap is noticed. */
    private fun gapCheck(case: UnreadContractCase, driver: UnreadCaseDriver?): String {
        if (driver == null) return ""
        var failures: List<String> = emptyList()
        runTest { failures = with(driver) { run(case) } }
        return if (failures.isEmpty()) {
            " [${driver.name} passes it now: the gap looks closed, drop it]"
        } else {
            " [${driver.name} confirms: ${failures.first()}]"
        }
    }

    private fun skip(reason: String): Nothing {
        report("skipped: $reason", true)
        assumeTrue("$fileId skipped: $reason", false)
        error("unreachable")
    }

    private fun report(result: String, ok: Boolean) {
        println("UNREAD_CONTRACT_CASE ${if (ok) "" else "FAILED "}$fileId: $result")
    }

    companion object {
        private val root: File by lazy {
            val url =
                checkNotNull(UnreadContractCasesTest::class.java.getResource("/contract-cases/unread/SOURCE_SHA")) {
                    "contract-cases/unread is not on the test classpath (src/commonTest/resources)"
                }
            File(url.toURI()).parentFile
        }

        internal fun caseFile(id: String) = File(root, "cases/$id.json")

        internal fun sourceSha(): String = File(root, "SOURCE_SHA").readText().trim()

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<String> {
            val ids = root.resolve("cases").listFiles { file -> file.extension == "json" }.orEmpty().map { it.nameWithoutExtension }
            assertTrue(ids.isNotEmpty(), "no vendored unread contract cases under $root")
            return ids.sorted()
        }
    }
}

/** The app-side lists and the pin stay honest as the vendored copy moves. */
class UnreadContractCaseListsTest {
    @Test
    fun appListsNameOnlyVendoredAgreedCases() {
        (AppUnreadKnownGaps.keys + AppUnreadCannotDriveYet.keys).forEach { id ->
            val file = UnreadContractCasesTest.caseFile(id)
            assertTrue(file.isFile, "$id is listed app-side but not vendored")
            val decoded = assertIs<UnreadCaseDecoding.Decoded>(decodeUnreadContractCase(id, file.readText()), id)
            assertEquals("agreed", decoded.case.status, "$id is listed app-side but is not agreed")
            assertTrue(
                decoded.case.knownGaps.none { it.client == "app" },
                "$id: slock lists the app gap now; drop the app-side entry"
            )
        }
    }

    @Test
    fun copyIsPinnedToASlockCommit() {
        assertTrue(Regex("[0-9a-f]{40}").matches(UnreadContractCasesTest.sourceSha()), "SOURCE_SHA must be a full commit sha")
    }
}
