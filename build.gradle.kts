// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    base
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.sqldelight) apply false
}

val checkKmpCapabilityGate by tasks.registering {
    group = "verification"
    description = "Validates that the KMP migration capability parity release gate is documented and checklist-backed."

    val requiredFiles = listOf(
        "docs/kmp-capability-parity-release-checklist.md",
        "docs/kmp-migration-capability-parity-audit.md",
        "docs/kmp-home-capability-parity-audit.md",
        "docs/kmp-thread-capability-parity-audit.md",
        "docs/kmp-settings-capability-parity-audit.md",
        "docs/android-ui-shared-migration-audit.md",
    ).map { layout.projectDirectory.file(it) }

    inputs.files(requiredFiles)

    doLast {
        val requiredStatusWords = listOf(
            "migrated",
            "partial",
            "missing",
            "intentionally unsupported",
        )
        val requiredCapabilityWords = listOf(
            "commonMain",
            "MVI",
            "cache",
            "loading",
            "realtime",
            "read receipt",
            "pagination",
            "attachments",
            "Android",
            "OHOS",
            "visual",
        )

        requiredFiles.forEach { file ->
            val sourceFile = file.asFile
            require(sourceFile.isFile) { "Missing KMP capability gate file: ${sourceFile.path}" }
            val text = sourceFile.readText()
            require(text.isNotBlank()) { "KMP capability gate file is empty: ${sourceFile.path}" }
        }

        val checklist = layout.projectDirectory.file("docs/kmp-capability-parity-release-checklist.md").asFile.readText()
        listOf(
            "Release/Build Validation Command",
            "./gradlew checkKmpCapabilityGate",
            "Blocker Rules",
            "Required Checklist",
            "Copy/Paste Release Note Template",
        ).forEach { marker ->
            require(checklist.contains(marker)) {
                "KMP capability checklist is missing required marker: $marker"
            }
        }
        requiredStatusWords.forEach { marker ->
            require(checklist.contains(marker)) {
                "KMP capability checklist must mention status marker: $marker"
            }
        }
        requiredCapabilityWords.forEach { marker ->
            require(checklist.contains(marker)) {
                "KMP capability checklist must mention capability marker: $marker"
            }
        }

        val globalAudit = layout.projectDirectory.file("docs/kmp-migration-capability-parity-audit.md").asFile.readText()
        listOf(
            "## Validation Gate For Future KMP Migration PRs",
            "## Release/Build Validation",
            "docs/kmp-capability-parity-release-checklist.md",
            "checkKmpCapabilityGate",
        ).forEach { marker ->
            require(globalAudit.contains(marker)) {
                "Global KMP migration audit is missing required marker: $marker"
            }
        }
    }
}

val validateKmpSharedRelease by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates compose/shared SQLDelight Thread cache, publishes shared Android artifact to MavenLocal, and compiles the app."

    commandLine(
        layout.projectDirectory.file("scripts/validate_kmp_shared_release.sh").asFile.absolutePath,
    )
}

tasks.named("check") {
    dependsOn(checkKmpCapabilityGate)
}
