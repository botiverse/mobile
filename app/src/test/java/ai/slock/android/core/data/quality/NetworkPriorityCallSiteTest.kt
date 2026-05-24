package ai.slock.android.core.data.quality

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPriorityCallSiteTest {
    @Test
    fun slockApiMethodsRequireExplicitPriorityParameter() {
        val root = findRepoRoot()
        val file = File(root, "app/src/main/java/ai/slock/android/core/data/network/SlockApi.kt")
        val lines = file.readLines()
        val offenders = mutableListOf<String>()
        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("suspend fun ")) return@forEachIndexed
            if (trimmed.contains("currentSlockApiPriority")) return@forEachIndexed
            val signature =
                    buildString {
                        for (i in index until lines.size) {
                            append(lines[i].trim()).append(' ')
                            if (lines[i].contains("): ApiResponse") ||
                                            lines[i].contains("):") ||
                                            lines[i].contains(": ApiResponse")
                            ) {
                                break
                            }
                        }
                    }
            if (!signature.contains("priority: SlockRequestPriority")) {
                offenders += "${file.relativeTo(root).path}:${index + 1}: $trimmed"
            }
        }

        assertTrue(
                "SlockApi methods must expose priority as an explicit compile-time parameter.\n" +
                        offenders.joinToString("\n"),
                offenders.isEmpty()
        )
    }

    @Test
    fun mainActivityRepositoryCallsArePriorityScoped() {
        val root = findRepoRoot()
        val file = File(root, "app/src/main/java/ai/slock/android/MainActivity.kt")
        val lines = file.readLines()
        val offenders =
                lines.mapIndexedNotNull { index, line ->
                    if (!repositoryCallRegex.containsMatchIn(line)) return@mapIndexedNotNull null
                    if (line.contains("ReadReceiptCoordinator(")) return@mapIndexedNotNull null
                    if (line.contains("remember(")) return@mapIndexedNotNull null
                    if (isInsidePriorityBlock(lines, index)) {
                        null
                    } else {
                        "${file.relativeTo(root).path}:${index + 1}: ${line.trim()}"
                    }
                }

        assertTrue(
                "MainActivity repository network calls must be wrapped in withSlockRequestPriority.\n" +
                        offenders.joinToString("\n"),
                offenders.isEmpty()
        )
    }

    @Test
    fun memberRepositoryMethodsRequireExplicitPriorityParameter() {
        val root = findRepoRoot()
        val file = File(root, "compose/shared/src/commonMain/kotlin/ai/slock/compose/data/SlockRepositories.kt")
        val lines = file.readLines()
        val start = lines.indexOfFirst { it.trim() == "interface MemberRepository {" }
        val end = lines.drop(start + 1).indexOfFirst { it.trim() == "}" }.let { start + 1 + it }
        val offenders =
                lines.subList(start, end)
                        .mapIndexedNotNull { offset, line ->
                            val trimmed = line.trim()
                            if (!trimmed.startsWith("suspend fun ")) return@mapIndexedNotNull null
                            val signature =
                                    buildString {
                                        for (i in start + offset until lines.size) {
                                            append(lines[i].trim()).append(' ')
                                            if (lines[i].contains("): DataResult<")) break
                                        }
                                    }
                            if (signature.contains("priority: DataRequestPriority")) {
                                null
                            } else {
                                "${file.relativeTo(root).path}:${start + offset + 1}: $trimmed"
                            }
                        }

        assertTrue(
                "MemberRepository methods must require priority at compile time.\n" +
                        offenders.joinToString("\n"),
                offenders.isEmpty()
        )
    }

    @Test
    fun realMemberRepositoryDoesNotUseRuntimePriorityGuard() {
        val root = findRepoRoot()
        val file = File(root, "compose/shared/src/commonMain/kotlin/ai/slock/compose/data/MemberRepositoryDataSource.kt")
        val text = file.readText()
        val start = text.indexOf("class RealMemberRepository")
        val end = text.indexOf("@Serializable", start)
        val memberRepositoryBody = text.substring(start, end)

        assertTrue(
                "RealMemberRepository must use explicit priority parameters, not currentSlockApiPriority().",
                !memberRepositoryBody.contains("currentSlockApiPriority()")
        )
    }

    @Test
    fun sharedSlockApiClientRequiresExplicitPriorityAndDoesNotUseRuntimeGuard() {
        val root = findRepoRoot()
        val file = File(root, "compose/shared/src/commonMain/kotlin/ai/slock/compose/network/SlockApiClient.kt")
        val text = file.readText()
        val publicClientMethods =
                Regex("""^\s*suspend fun (\w+)\(([^)]*)\): KmpHttpResponse""", RegexOption.MULTILINE)
                        .findAll(text)
                        .mapNotNull { match ->
                            val name = match.groupValues[1]
                            val params = match.groupValues[2]
                            if (name in setOf("get", "post", "patch", "delete", "request", "multipart")) {
                                null
                            } else if (params.contains("priority: SlockRequestPriority")) {
                                null
                            } else {
                                "${file.relativeTo(root).path}: $name($params)"
                            }
                        }
                        .toList()

        assertTrue(
                "Shared SlockApiClient methods must require explicit priority at compile time.\n" +
                        publicClientMethods.joinToString("\n"),
                publicClientMethods.isEmpty()
        )
        assertTrue(
                "Shared SlockApiClient must not fail at runtime via coroutine-context priority guard.",
                !text.contains("requireRequestPriority") &&
                        !text.contains("SlockRequestPriorityElement") &&
                        !text.contains("coroutineContext")
        )
    }

    @Test
    fun slockApiPriorityFallbackDoesNotFailAtRuntime() {
        val root = findRepoRoot()
        val file = File(root, "app/src/main/java/ai/slock/android/core/data/network/SlockApi.kt")
        val text = file.readText()
        val fallbackBody = text.substringAfter("internal suspend fun currentSlockApiPriority()")

        assertTrue(
                "currentSlockApiPriority must default to a normal priority instead of throwing runtime guard errors.",
                fallbackBody.contains("?: SlockRequestPriority.Visible") &&
                        !fallbackBody.contains("Missing SlockRequestPriority")
        )
    }

    private fun isInsidePriorityBlock(lines: List<String>, index: Int): Boolean {
        var balance = 0
        var sawPriority = false
        for (i in index downTo 0) {
            val line = lines[i]
            balance += line.count { it == '}' }
            balance -= line.count { it == '{' }
            if (line.contains("withSlockRequestPriority(")) {
                sawPriority = true
                if (balance <= 0) return true
            }
            if (sawPriority && balance > 3) return false
        }
        return false
    }

    private fun findRepoRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        var current = File(userDir).absoluteFile
        while (current.parentFile != null) {
            if (File(current, "settings.gradle.kts").exists() && File(current, "app").isDirectory) {
                return current
            }
            current = requireNotNull(current.parentFile)
        }
        error("Could not find repo root from $userDir")
    }

    private companion object {
        val repositoryCallRegex =
                Regex(
                        "\\b(?:channelRepository|conversationRepository|inboxRepository|savedRepository|" +
                                "searchRepository|readReceiptRepository|serverUnreadRepository|taskRepository|" +
                                "memberRepository|settingsRepository|serverRepository)\\.[A-Za-z0-9_]+\\s*\\("
                )
    }
}
