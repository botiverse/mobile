package ai.slock.android.core.data.quality

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPriorityCallSiteTest {
    @Test
    fun mainActivityRepositoryCallsArePriorityScoped() {
        val root = findRepoRoot()
        val file = File(root, "compose/androidApp/src/main/java/ai/slock/android/MainActivity.kt")
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
        val file = File(root, "compose/shared/src/androidMain/kotlin/ai/slock/compose/network/SlockApiClient.android.kt")
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
    fun sharedSlockNetworkClientMethodsRequireExplicitPriority() {
        val root = findRepoRoot()
        val file = File(root, "compose/shared/src/commonMain/kotlin/ai/slock/compose/data/MemberRepositoryDataSource.kt")
        val text = file.readText()
        val start = text.indexOf("interface SlockNetworkClient {")
        val end = text.indexOf("\n}\n\nprivate suspend fun", start)
        val networkClientBody = text.substring(start, end)
        val offenders =
                Regex("""^\s*suspend fun (\w+)\(([^)]*)\)""", RegexOption.MULTILINE)
                        .findAll(networkClientBody)
                        .mapNotNull { match ->
                            val name = match.groupValues[1]
                            val params = match.groupValues[2]
                            if (params.contains("priority: DataRequestPriority")) {
                                null
                            } else {
                                "${file.relativeTo(root).path}: $name($params)"
                            }
                        }
                        .toList()

        assertTrue(
                "Shared SlockNetworkClient methods must require explicit DataRequestPriority.\n" +
                        offenders.joinToString("\n"),
                offenders.isEmpty()
        )
    }

    @Test
    fun androidNetworkLayerDoesNotKeepLegacySlockApiFacade() {
        val root = findRepoRoot()
        val legacyFiles =
                listOf(
                        "compose/androidApp/src/main/java/ai/slock/android/core/data/network/SlockApi.kt",
                        "compose/androidApp/src/main/java/ai/slock/android/core/data/network/ComposeSlockApiAdapter.kt",
                        "compose/androidApp/src/main/java/ai/slock/android/core/data/network/RealSlockRepositories.kt",
                        "compose/androidApp/src/main/java/ai/slock/android/core/data/network/NetworkResponseMapper.kt",
                        "compose/androidApp/src/main/java/ai/slock/android/core/data/network/ApiResponse.kt",
                        "compose/androidApp/src/main/java/ai/slock/android/core/data/auth/AuthApi.kt",
                        "compose/androidApp/src/main/java/ai/slock/android/core/data/auth/AndroidSlockAuthApi.kt",
                        "compose/androidApp/src/main/java/ai/slock/android/core/data/auth/AuthRepository.kt",
                        "compose/androidApp/src/main/java/ai/slock/android/core/data/auth/KtorAuthRepository.kt",
                        "compose/androidApp/src/main/java/ai/slock/android/core/data/auth/AuthModels.kt",
                        "compose/androidApp/src/main/java/ai/slock/android/core/data/auth/AuthSessionStore.kt",
                        "compose/androidApp/src/main/java/ai/slock/android/core/data/auth/ComposeAuthSessionStoreAdapter.kt"
                )
                        .filter { File(root, it).exists() }

        assertTrue(
                "Android must not keep legacy API facade/repository files; API requests belong in shared DTO repositories.\n" +
                        legacyFiles.joinToString("\n"),
                legacyFiles.isEmpty()
        )
    }

    @Test
    fun androidDoesNotKeepSharedRepositoryTypealiasPackage() {
        val root = findRepoRoot()
        val repositoryDir = File(root, "compose/androidApp/src/main/java/ai/slock/android/core/data/repository")
        val remainingFiles =
                repositoryDir
                        .walkTopDown()
                        .filter { it.isFile }
                        .map { it.relativeTo(root).path }
                        .toList()

        assertTrue(
                "Android must import shared repository contracts/snapshots directly; do not reintroduce typealias shims.\n" +
                        remainingFiles.joinToString("\n"),
                remainingFiles.isEmpty()
        )
    }

    @Test
    fun sharedAuthProductsDoNotUseBridgeAuthTransport() {
        val root = findRepoRoot()
        val productFiles =
                listOf(
                        "compose/shared/src/commonMain/kotlin/ai/slock/compose/AuthPages.kt",
                        "compose/shared/src/commonMain/kotlin/ai/slock/compose/SettingsHomePage.kt"
                )

        val offenders =
                productFiles.flatMap { path ->
                    val file = File(root, path)
                    file.readLines().mapIndexedNotNull { index, line ->
                        if (line.contains("BridgeAuthApi") || line.contains("bridge.authRequest")) {
                            "${file.relativeTo(root).path}:${index + 1}: ${line.trim()}"
                        } else {
                            null
                        }
                    }
                }

        assertTrue(
                "Shared AuthPages/app shell auth gate must use KuiklySlockAuthTransport, not bridge authRequest.\n" +
                        offenders.joinToString("\n"),
                offenders.isEmpty()
        )
    }

    @Test
    fun sharedCommonMainDoesNotKeepBridgeBusinessRestHelpers() {
        val root = findRepoRoot()
        val commonMain = File(root, "compose/shared/src/commonMain/kotlin/ai/slock/compose")
        val bannedSymbols =
                listOf(
                        "AUTH_REQUEST",
                        "BridgeAuthApi",
                        "BridgeAuthenticatedRequestExecutor",
                        "BridgeSlockNetworkClient",
                        "BridgeTasksApi",
                        "BridgeThreadApi",
                        "authRequest"
                )

        val offenders =
                commonMain
                        .walkTopDown()
                        .filter { it.isFile && it.extension == "kt" }
                        .flatMap { file ->
                            file.readLines().mapIndexedNotNull { index, line ->
                                val banned = bannedSymbols.firstOrNull { line.contains(it) }
                                banned?.let { "${file.relativeTo(root).path}:${index + 1}: $it :: ${line.trim()}" }
                            }
                        }
                        .toList()

        assertTrue(
                "Business REST must not go through BridgeAuthenticatedRequestExecutor/authRequest bridge helpers. " +
                        "Use SlockNetworkClient/KuiklySlockAuthTransport; keep platform capability HTTP separate.\n" +
                        offenders.joinToString("\n"),
                offenders.isEmpty()
        )
    }

    @Test
    fun platformHostsDoNotKeepLegacyBridgeAuthRequestHandler() {
        val root = findRepoRoot()
        val hostFiles =
                listOf(
                        "compose/androidApp/src/main/java/ai/slock/android/shared/SlockKuiklyActivity.kt",
                        "compose/ohosApp/entry/src/main/ets/kuikly/modules/KRBridgeModule.ets"
                )
        val bannedSymbols =
                listOf(
                        "\"authRequest\"",
                        "AUTH_REQUEST",
                        "private fun authRequest",
                        "private async authRequest"
                )

        val offenders =
                hostFiles.flatMap { path ->
                    val file = File(root, path)
                    file.readLines().mapIndexedNotNull { index, line ->
                        val banned = bannedSymbols.firstOrNull { line.contains(it) }
                        banned?.let { "${file.relativeTo(root).path}:${index + 1}: $it :: ${line.trim()}" }
                    }
                }

        assertTrue(
                "Platform hosts must not keep the legacy bridge.authRequest business REST handler. " +
                        "Auth product flows use KuiklySlockAuthTransport and other business REST uses SlockNetworkClient.\n" +
                        offenders.joinToString("\n"),
                offenders.isEmpty()
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
            if (File(current, "AGENTS.md").isFile && File(current, "compose/settings.gradle.kts").isFile) {
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
