package build.raft.app

// GENERATED FILE - DO NOT EDIT.
// Source of truth: packages/visual-testing/shared/fixtureData.json in the React repo.
// Regenerate: slock-visual generate-fixture-data --android-repo-dir /path/to/mobile
// Android visual fixture pages must read identity/copy/time values from this
// object so React and Android captures render identical fixture data.
@Suppress("unused", "MemberVisibilityCanBePrivate")
object VisualFixtureData {
    object Server {
        const val ID: String = "visual-server"
        const val NAME: String = "Visual Server"
        const val SLUG: String = "visual"
        const val PLAN: String = "free"
        const val OWNER_HUMAN_KEY: String = "owner"
    }

    object Humans {
        object Owner {
            const val ID: String = "visual-user"
            const val NAME: String = "artin"
            const val DISPLAY_NAME: String = "artin"
            const val HANDLE: String = "@artin"
            const val EMAIL: String = "artin@slock.ai"
            const val AVATAR_LABEL: String = "AR"
            const val ROLE: String = "owner"
            const val ROLE_LABEL: String = "Owner"
            const val DESCRIPTION: String = "Owner"
            const val TIMEZONE: String = "Asia/Shanghai"
            const val TIME_FORMAT: String = "24h"
            const val EMAIL_VERIFIED: Boolean = true
        }
        object Designer {
            const val ID: String = "visual-human-designer"
            const val NAME: String = "designer"
            const val DISPLAY_NAME: String = "Designer"
            const val HANDLE: String = "@designer"
            const val EMAIL: String = "designer@slock.ai"
            const val AVATAR_LABEL: String = "DE"
            const val ROLE: String = "member"
            const val ROLE_LABEL: String = "Member"
            const val DESCRIPTION: String = "Product design"
        }
        object Jiacheng {
            const val ID: String = "visual-human-jiacheng"
            const val NAME: String = "jiacheng"
            const val DISPLAY_NAME: String = "Jiacheng"
            const val HANDLE: String = "@jiacheng"
            const val EMAIL: String = "jiacheng@slock.ai"
            const val AVATAR_LABEL: String = "J"
            const val ROLE: String = "member"
            const val ROLE_LABEL: String = "Member"
            const val DESCRIPTION: String = "Android"
        }
    }

    object Agents {
        object Cindy {
            const val ID: String = "agent-cindy"
            const val NAME: String = "Cindy"
            const val DISPLAY_NAME: String = "Cindy"
            const val AVATAR: String = "pixel:robot"
            const val AVATAR_LABEL: String = "CI"
            const val DESCRIPTION: String = "Keeps visual testing release notes aligned."
            const val STATUS: String = "active"
            const val SERVER_ROLE: String = "member"
            const val ACTIVITY: String = "working"
            const val ACTIVITY_DETAIL: String = "Capturing visual testing baselines"
            const val MODEL: String = "gpt-5-codex"
            const val RUNTIME: String = "codex"
            const val REASONING_EFFORT: String = "medium"
            const val EXECUTION_MODE: String = "byoc"
            const val SESSION_ID: String = "session-cindy"
            const val MACHINE_KEY: String = "primary"
        }
        object ProductUx {
            const val ID: String = "agent-product-ux"
            const val NAME: String = "Product-UX-Designer"
            const val DISPLAY_NAME: String = "Product UX Designer"
            const val AVATAR: String = "pixel:robot"
            const val AVATAR_LABEL: String = "PU"
            const val DESCRIPTION: String = "Turns product feedback, screenshots, and release notes into actionable UX specs."
            const val STATUS: String = "active"
            const val SERVER_ROLE: String = "member"
            const val ACTIVITY: String = "working"
            const val ACTIVITY_DETAIL: String = "Capturing deterministic Activity tab state"
            const val MODEL: String = "gpt-5-codex"
            const val RUNTIME: String = "codex"
            const val REASONING_EFFORT: String = "medium"
            const val EXECUTION_MODE: String = "byoc"
            const val SESSION_ID: String = "session-product-ux"
            const val MACHINE_KEY: String = "primary"
            val ENV_VAR_NAMES: List<String> = listOf("RAFT_PROFILE", "SLOCK_VISUAL_PROVIDER")
            const val CREATED_AT_ISO: String = "2026-06-01T09:15:00.000Z"
            object CreatedAgents1 {
                const val ID: String = "agent-visual-qa"
                const val NAME: String = "visual-qa"
                const val DISPLAY_NAME: String = "Visual QA"
                const val RUNTIME: String = "codex"
                const val STATUS: String = "inactive"
            }
            val CREATED_AGENTS: List<Map<String, Any?>> = listOf(
                mapOf<String, Any?>("id" to "agent-visual-qa", "name" to "visual-qa", "displayName" to "Visual QA", "runtime" to "codex", "status" to "inactive"),
            )
        }
        object AndroidDev4 {
            const val ID: String = "agent-android-dev-4"
            const val NAME: String = "Android-Developer-4"
            const val DISPLAY_NAME: String = "Android Developer 4"
            const val AVATAR: String = "pixel:robot"
            const val AVATAR_LABEL: String = "AD"
            const val DESCRIPTION: String = "Implements shared Android/KMP UI fixes."
            const val STATUS: String = "active"
            const val SERVER_ROLE: String = "member"
            const val ACTIVITY: String = "working"
            const val ACTIVITY_DETAIL: String = "Running Android visual captures"
            const val MODEL: String = "gpt-5-codex"
            const val RUNTIME: String = "codex"
            const val REASONING_EFFORT: String = "medium"
            const val EXECUTION_MODE: String = "byoc"
            const val SESSION_ID: String = "session-android-dev-4"
            const val MACHINE_KEY: String = "primary"
        }
        object Builder {
            const val ID: String = "agent-builder"
            const val NAME: String = "Builder"
            const val DISPLAY_NAME: String = "Builder"
            const val AVATAR: String = "pixel:robot"
            const val AVATAR_LABEL: String = "B"
            const val DESCRIPTION: String = "File and repo automation"
            const val STATUS: String = "active"
            const val SERVER_ROLE: String = "member"
            const val ACTIVITY: String = "working"
            const val ACTIVITY_DETAIL: String = "Automating file workflows"
            const val MODEL: String = "gpt-5-codex"
            const val RUNTIME: String = "codex"
            const val REASONING_EFFORT: String = "medium"
            const val EXECUTION_MODE: String = "byoc"
            const val SESSION_ID: String = "session-builder"
            const val MACHINE_KEY: String = "primary"
        }
        object KmpExpert {
            const val ID: String = "agent-kmp-expert"
            const val NAME: String = "KMP-专家"
            const val DISPLAY_NAME: String = "KMP-专家"
            const val AVATAR: String = "pixel:robot"
            const val AVATAR_LABEL: String = "KM"
            const val DESCRIPTION: String = "Shared KMP architecture and runtime adaptation."
            const val STATUS: String = "active"
            const val SERVER_ROLE: String = "member"
            const val ACTIVITY: String = "online"
            const val ACTIVITY_DETAIL: String = "Reviewing shared state"
            const val MODEL: String = "gpt-5-codex"
            const val RUNTIME: String = "codex"
            const val REASONING_EFFORT: String = "medium"
            const val EXECUTION_MODE: String = "byoc"
            const val SESSION_ID: String = "session-kmp-expert"
            const val MACHINE_KEY: String = "primary"
        }
        object CcWow {
            const val ID: String = "agent-cc-wow"
            const val NAME: String = "CC-Wow"
            const val DISPLAY_NAME: String = "CC-Wow"
            const val AVATAR: String = "pixel:robot"
            const val AVATAR_LABEL: String = "CW"
            const val DESCRIPTION: String = "Visual testing analysis and fixes."
            const val STATUS: String = "active"
            const val SERVER_ROLE: String = "member"
            const val ACTIVITY: String = "working"
            const val ACTIVITY_DETAIL: String = "Analyzing visual parity"
            const val MODEL: String = "claude-sonnet-5"
            const val RUNTIME: String = "claude"
            const val REASONING_EFFORT: String = "medium"
            const val EXECUTION_MODE: String = "byoc"
            const val SESSION_ID: String = "session-cc-wow"
            const val MACHINE_KEY: String = "primary"
        }
        object ComputerOffline {
            const val ID: String = "agent-computer-offline"
            const val NAME: String = "Computer-Offline-Probe"
            const val DISPLAY_NAME: String = "Computer Offline Probe"
            const val AVATAR: String = "pixel:robot"
            const val AVATAR_LABEL: String = "CO"
            const val DESCRIPTION: String = "Bound to a computer the store knows and reports offline: web shows a gray dot and Offline."
            const val STATUS: String = "active"
            const val SERVER_ROLE: String = "member"
            const val ACTIVITY: String = "working"
            const val ACTIVITY_DETAIL: String = "Holding a fixed Computer-row state for the visual oracle"
            const val MODEL: String = "gpt-5-codex"
            const val RUNTIME: String = "codex"
            const val REASONING_EFFORT: String = "medium"
            const val EXECUTION_MODE: String = "byoc"
            const val SESSION_ID: String = "session-computer-offline"
            const val MACHINE_KEY: String = "studio"
            val ENV_VAR_NAMES: List<String> = listOf("RAFT_PROFILE", "SLOCK_VISUAL_PROVIDER")
            const val CREATED_AT_ISO: String = "2026-06-01T09:15:00.000Z"
            val CREATED_AGENTS: List<String> = listOf()
        }
        object ComputerMissing {
            const val ID: String = "agent-computer-missing"
            const val NAME: String = "Computer-Missing-Probe"
            const val DISPLAY_NAME: String = "Computer Missing Probe"
            const val AVATAR: String = "pixel:robot"
            const val AVATAR_LABEL: String = "CM"
            const val DESCRIPTION: String = "machineId points at a computer the store has never seen: web shows No computer assigned, no dot."
            const val STATUS: String = "active"
            const val SERVER_ROLE: String = "member"
            const val ACTIVITY: String = "working"
            const val ACTIVITY_DETAIL: String = "Holding a fixed Computer-row state for the visual oracle"
            const val MODEL: String = "gpt-5-codex"
            const val RUNTIME: String = "codex"
            const val REASONING_EFFORT: String = "medium"
            const val EXECUTION_MODE: String = "byoc"
            const val SESSION_ID: String = "session-computer-missing"
            const val MACHINE_KEY: String = "missing"
            val ENV_VAR_NAMES: List<String> = listOf("RAFT_PROFILE", "SLOCK_VISUAL_PROVIDER")
            const val CREATED_AT_ISO: String = "2026-06-01T09:15:00.000Z"
            val CREATED_AGENTS: List<String> = listOf()
        }
        object NoComputer {
            const val ID: String = "agent-no-computer"
            const val NAME: String = "No-Computer-Probe"
            const val DISPLAY_NAME: String = "No Computer Probe"
            const val AVATAR: String = "pixel:robot"
            const val AVATAR_LABEL: String = "NC"
            const val DESCRIPTION: String = "No machineId at all: web shows No computer assigned, no dot."
            const val STATUS: String = "active"
            const val SERVER_ROLE: String = "member"
            const val ACTIVITY: String = "working"
            const val ACTIVITY_DETAIL: String = "Holding a fixed Computer-row state for the visual oracle"
            const val MODEL: String = "gpt-5-codex"
            const val RUNTIME: String = "codex"
            const val REASONING_EFFORT: String = "medium"
            const val EXECUTION_MODE: String = "byoc"
            const val SESSION_ID: String = "session-no-computer"
            val MACHINE_KEY: String? = null
            val ENV_VAR_NAMES: List<String> = listOf("RAFT_PROFILE", "SLOCK_VISUAL_PROVIDER")
            const val CREATED_AT_ISO: String = "2026-06-01T09:15:00.000Z"
            val CREATED_AGENTS: List<String> = listOf()
        }
        object LongMachine {
            const val ID: String = "agent-long-machine"
            const val NAME: String = "Long-Machine-Name-Probe"
            const val DISPLAY_NAME: String = "Long Machine Name Probe"
            const val AVATAR: String = "pixel:robot"
            const val AVATAR_LABEL: String = "LM"
            const val DESCRIPTION: String = "Bound to a computer whose name cannot fit one 390dp line, so wrap vs truncate is observable."
            const val STATUS: String = "active"
            const val SERVER_ROLE: String = "admin"
            const val ACTIVITY: String = "working"
            const val ACTIVITY_DETAIL: String = "Holding a fixed Computer-row state for the visual oracle"
            const val MODEL: String = "gpt-5-codex"
            const val RUNTIME: String = "codex"
            const val REASONING_EFFORT: String = "medium"
            const val EXECUTION_MODE: String = "byoc"
            const val SESSION_ID: String = "session-long-machine"
            const val MACHINE_KEY: String = "longName"
            val ENV_VAR_NAMES: List<String> = listOf("RAFT_PROFILE", "SLOCK_VISUAL_PROVIDER")
            const val CREATED_AT_ISO: String = "2026-06-01T09:15:00.000Z"
            val CREATED_AGENTS: List<String> = listOf()
        }
        object DaemonOnly {
            const val ID: String = "agent-daemon-only"
            const val NAME: String = "Daemon-Only-Probe"
            const val DISPLAY_NAME: String = "Daemon Only Probe"
            const val AVATAR: String = "pixel:robot"
            const val AVATAR_LABEL: String = "DO"
            const val DESCRIPTION: String = "Bound to a bare daemon (not a managed Computer): web shows Connected · legacy vX, never a computer version."
            const val STATUS: String = "active"
            const val SERVER_ROLE: String = "member"
            const val ACTIVITY: String = "working"
            const val ACTIVITY_DETAIL: String = "Holding a fixed Computer-row state for the visual oracle"
            const val MODEL: String = "gpt-5-codex"
            const val RUNTIME: String = "codex"
            const val REASONING_EFFORT: String = "medium"
            const val EXECUTION_MODE: String = "byoc"
            const val SESSION_ID: String = "session-daemon-only"
            const val MACHINE_KEY: String = "daemonOnly"
            val ENV_VAR_NAMES: List<String> = listOf("RAFT_PROFILE", "SLOCK_VISUAL_PROVIDER")
            const val CREATED_AT_ISO: String = "2026-06-01T09:15:00.000Z"
            val CREATED_AGENTS: List<String> = listOf()
        }
        object NoMembership {
            const val ID: String = "agent-no-membership"
            const val NAME: String = "No-Membership-Probe"
            const val DISPLAY_NAME: String = "No Membership Probe"
            const val AVATAR: String = "pixel:robot"
            const val AVATAR_LABEL: String = "NM"
            const val DESCRIPTION: String = "Has no server membership row, so the wire omits serverRole (artin 2026-09-03 16:54: hide the role chip)."
            const val STATUS: String = "active"
            const val ACTIVITY: String = "working"
            const val ACTIVITY_DETAIL: String = "Holding a fixed Computer-row state for the visual oracle"
            const val MODEL: String = "gpt-5-codex"
            const val RUNTIME: String = "codex"
            const val REASONING_EFFORT: String = "medium"
            const val EXECUTION_MODE: String = "byoc"
            const val SESSION_ID: String = "session-no-membership"
            const val MACHINE_KEY: String = "primary"
            val ENV_VAR_NAMES: List<String> = listOf("RAFT_PROFILE", "SLOCK_VISUAL_PROVIDER")
            const val CREATED_AT_ISO: String = "2026-06-01T09:15:00.000Z"
            val CREATED_AGENTS: List<String> = listOf()
        }
    }

    object Machines {
        object Primary {
            const val ID: String = "computer-mbp"
            const val NAME: String = "Jiachengs-MacBook-Pro"
            const val HOSTNAME: String = "Jiachengs-MacBook-Pro.local"
            const val OS: String = "darwin"
            const val STATUS: String = "online"
            const val STATUS_VERSION: Int = 7
            const val API_KEY_PREFIX: String = "slk_vis"
            val RUNTIMES: List<String> = listOf("codex", "claude")
            const val DAEMON_VERSION: String = "0.65.0"
            const val COMPUTER_VERSION: String = "0.0.48"
            const val IS_COMPUTER: Boolean = true
            const val LAST_HEARTBEAT_ISO: String = "2026-06-19T12:28:00.000Z"
            const val CREATED_AT_ISO: String = "2026-06-18T00:00:00.000Z"
        }
        object Studio {
            const val ID: String = "computer-studio"
            const val NAME: String = "Studio Test Rig"
            const val HOSTNAME: String = "studio-test-rig.local"
            const val OS: String = "linux"
            const val STATUS: String = "offline"
            const val STATUS_VERSION: Int = 3
            const val API_KEY_PREFIX: String = "slk_off"
            val RUNTIMES: List<String> = listOf("codex")
            const val DAEMON_VERSION: String = "0.65.0"
            const val COMPUTER_VERSION: String = "0.0.50"
            const val IS_COMPUTER: Boolean = true
            val LAST_HEARTBEAT_ISO: String? = null
            const val CREATED_AT_ISO: String = "2026-06-18T00:00:00.000Z"
        }
        object LongName {
            const val ID: String = "computer-long-name"
            const val NAME: String = "ci-visual-capture-runner-us-west-2b_node17_macbookpro_m4max_buildhost_2026"
            const val HOSTNAME: String = "ci-visual-capture-runner-us-west-2b.internal"
            const val OS: String = "darwin"
            const val STATUS: String = "online"
            const val STATUS_VERSION: Int = 1
            const val API_KEY_PREFIX: String = "slk_lng"
            val RUNTIMES: List<String> = listOf("codex")
            const val DAEMON_VERSION: String = "0.65.0"
            const val COMPUTER_VERSION: String = "0.0.48"
            const val IS_COMPUTER: Boolean = true
            const val LAST_HEARTBEAT_ISO: String = "2026-06-19T12:28:00.000Z"
            const val CREATED_AT_ISO: String = "2026-06-18T00:00:00.000Z"
        }
        object DaemonOnly {
            const val ID: String = "computer-daemon-only"
            const val NAME: String = "build-box-bare-daemon"
            const val HOSTNAME: String = "build-box.local"
            const val OS: String = "linux"
            const val STATUS: String = "online"
            const val STATUS_VERSION: Int = 1
            const val API_KEY_PREFIX: String = "slk_dmn"
            val RUNTIMES: List<String> = listOf("codex")
            const val DAEMON_VERSION: String = "0.65.0"
            val COMPUTER_VERSION: String? = null
            const val IS_COMPUTER: Boolean = false
            const val LAST_HEARTBEAT_ISO: String = "2026-06-19T12:28:00.000Z"
            const val CREATED_AT_ISO: String = "2026-06-18T00:00:00.000Z"
        }
    }

    object LatestVersions {
        const val DAEMON: String = "0.65.0"
        const val COMPUTER: String = "0.0.50"
    }

    object Channels {
        object Design {
            const val ID: String = "channel-design"
            const val NAME: String = "design"
            const val DESCRIPTION: String = "Product and UI decisions"
            const val TYPE: String = "channel"
            const val MEMBER_COUNT: Int = 18
            const val CREATED_AT_ISO: String = "2026-06-18T00:00:00.000Z"
        }
        object AndroidArtifacts {
            const val ID: String = "channel-android"
            const val NAME: String = "android-artifacts"
            const val DESCRIPTION: String = "Builds and screenshots"
            const val TYPE: String = "channel"
            const val MEMBER_COUNT: Int = 8
            const val CREATED_AT_ISO: String = "2026-06-18T00:00:00.000Z"
        }
        object ComposerHost {
            const val ID: String = "visual-thread-composer"
            const val NAME: String = "design"
            const val DESCRIPTION: String = "Product and UI decisions"
            const val TYPE: String = "channel"
            const val CREATED_AT_ISO: String = "2026-06-18T00:00:00.000Z"
        }
    }

    object Locale {
        const val LANGUAGE: String = "zh-CN"
        const val TIMEZONE: String = "Asia/Shanghai"
        const val UTC_OFFSET_MINUTES: Int = 480
        const val TIME_FORMAT: String = "24h"
        const val TODAY_EPOCH_DAY: Int = 20623
        const val NOW_EPOCH_MILLIS: Long = 1781872200000L
    }

    object Times {
        const val MEMBER_JOINED_AT_ISO: String = "2026-06-18T00:00:00.000Z"
        const val ENTITY_CREATED_AT_ISO: String = "2026-06-18T00:00:00.000Z"
        const val MACHINE_HEARTBEAT_AT_ISO: String = "2026-06-19T12:28:00.000Z"
        const val RECENT_ACTIVITY_AT_ISO: String = "2026-06-19T12:24:00.000Z"
        const val THREAD_MESSAGE_AT_ISO: String = "2026-06-22T02:30:00.000Z"
        const val THREAD_REPLY_AT_ISO: String = "2026-06-22T02:35:00.000Z"
        const val FILE_IMAGE_AT_ISO: String = "2026-06-25T10:24:00.000Z"
        const val FILE_PDF_AT_ISO: String = "2026-06-25T10:17:00.000Z"
        const val FILE_ZIP_AT_ISO: String = "2026-06-25T10:08:00.000Z"
    }

    object RegisterForm {
        const val NAME: String = "Product UX Designer"
        const val EMAIL: String = "new@slock.ai"
        const val PASSWORD: String = "correct horse battery staple"
    }

    object Messages {
        const val THREAD_DEFAULT: String = "Captured the Android visual artifact for #product:f8e569cb and queued React parity."
        const val THREAD_DELETED_HUMAN: String = "Captured the previous visual baseline before leaving the workspace."
        const val COMPOSER_SEED: String = "Review the Android composer crop before release."
        const val COMPOSER_IMAGE_PREVIEW_SEED: String = "Uploading the design crop for review."
        const val COMPOSER_FILE_PREVIEWS_SEED: String = "Attaching build artifacts and logs."
        const val COMPOSER_PLACEHOLDER: String = "Message design"
        const val DM_LAST_PREVIEW: String = "201继续推进"
    }

    object Files {
        object Image {
            const val FILENAME: String = "composer-preview.png"
            const val MIME_TYPE: String = "image/png"
            const val SIZE_BYTES: Int = 131072
        }
        object Pdf {
            const val FILENAME: String = "release-notes.pdf"
            const val MIME_TYPE: String = "application/pdf"
            const val SIZE_BYTES: Int = 438272
        }
        object Zip {
            const val FILENAME: String = "android-smoke-log.zip"
            const val MIME_TYPE: String = "application/zip"
            const val SIZE_BYTES: Int = 1887436
        }
    }

    object HomeChannelList {
        const val COMMENT: String = "Phone Home rows for screens.home.channel-list and screens.home.channel-list.dark (same rows, light and dark). The one source for both providers: the React host (VisualTestingCases.tsx primeHomeChannelListStores, the playwright stubs) reads it here; Android reads the generated VisualFixtureData.HomeChannelList (CHANNELS / DIRECT_MESSAGES row lists) in HomeChannelListVisualHost.kt. Server id/slug are fixtureData.server. Every row carries every field so both sides map rows uniformly. mention (the @ sticker) and followedThreadsWithNewReplies (the [thread] N count) are drawn on both; the Android app does not load them from the server yet, only this fixture sets them. No row is selected: a phone Home root never shows one."
        const val SERVER_NAME: String = "Raft Design"
        const val SAVED_TOTAL: Int = 5
        const val ACTIVITY_UNREAD_COUNT: Int = 0
        val PINNED_CHANNEL_IDS: List<String> = listOf("channel-announcements")
        object Channels1 {
            const val ID: String = "channel-announcements"
            const val NAME: String = "announcements"
            const val TYPE: String = "channel"
            const val UNREAD: Int = 3
            const val MENTION: Boolean = false
            const val FOLLOWED_THREADS_WITH_NEW_REPLIES: Int = 0
            const val MUTED: Boolean = false
            const val DRAFT: Boolean = false
        }
        object Channels2 {
            const val ID: String = "channel-partners-sync"
            const val NAME: String = "partners-sync"
            const val TYPE: String = "joint"
            const val UNREAD: Int = 2
            const val MENTION: Boolean = false
            const val FOLLOWED_THREADS_WITH_NEW_REPLIES: Int = 0
            const val MUTED: Boolean = false
            const val DRAFT: Boolean = false
        }
        object Channels3 {
            const val ID: String = "channel-release"
            const val NAME: String = "release"
            const val TYPE: String = "channel"
            const val UNREAD: Int = 5
            const val MENTION: Boolean = true
            const val FOLLOWED_THREADS_WITH_NEW_REPLIES: Int = 0
            const val MUTED: Boolean = false
            const val DRAFT: Boolean = false
        }
        object Channels4 {
            const val ID: String = "channel-mobile"
            const val NAME: String = "mobile"
            const val TYPE: String = "channel"
            const val UNREAD: Int = 0
            const val MENTION: Boolean = false
            const val FOLLOWED_THREADS_WITH_NEW_REPLIES: Int = 2
            const val MUTED: Boolean = false
            const val DRAFT: Boolean = false
        }
        object Channels5 {
            const val ID: String = "channel-ops-alerts"
            const val NAME: String = "ops-alerts"
            const val TYPE: String = "channel"
            const val UNREAD: Int = 12
            const val MENTION: Boolean = false
            const val FOLLOWED_THREADS_WITH_NEW_REPLIES: Int = 0
            const val MUTED: Boolean = true
            const val DRAFT: Boolean = false
        }
        object Channels6 {
            const val ID: String = "channel-roadmap"
            const val NAME: String = "roadmap"
            const val TYPE: String = "private"
            const val UNREAD: Int = 0
            const val MENTION: Boolean = false
            const val FOLLOWED_THREADS_WITH_NEW_REPLIES: Int = 0
            const val MUTED: Boolean = false
            const val DRAFT: Boolean = true
        }
        object Channels7 {
            const val ID: String = "channel-long-name"
            const val NAME: String = "visual-parity-and-release-readiness-for-every-phone"
            const val TYPE: String = "channel"
            const val UNREAD: Int = 0
            const val MENTION: Boolean = false
            const val FOLLOWED_THREADS_WITH_NEW_REPLIES: Int = 0
            const val MUTED: Boolean = false
            const val DRAFT: Boolean = false
        }
        val CHANNELS: List<Map<String, Any?>> = listOf(
            mapOf<String, Any?>("id" to "channel-announcements", "name" to "announcements", "type" to "channel", "unread" to 3, "mention" to false, "followedThreadsWithNewReplies" to 0, "muted" to false, "draft" to false),
            mapOf<String, Any?>("id" to "channel-partners-sync", "name" to "partners-sync", "type" to "joint", "unread" to 2, "mention" to false, "followedThreadsWithNewReplies" to 0, "muted" to false, "draft" to false),
            mapOf<String, Any?>("id" to "channel-release", "name" to "release", "type" to "channel", "unread" to 5, "mention" to true, "followedThreadsWithNewReplies" to 0, "muted" to false, "draft" to false),
            mapOf<String, Any?>("id" to "channel-mobile", "name" to "mobile", "type" to "channel", "unread" to 0, "mention" to false, "followedThreadsWithNewReplies" to 2, "muted" to false, "draft" to false),
            mapOf<String, Any?>("id" to "channel-ops-alerts", "name" to "ops-alerts", "type" to "channel", "unread" to 12, "mention" to false, "followedThreadsWithNewReplies" to 0, "muted" to true, "draft" to false),
            mapOf<String, Any?>("id" to "channel-roadmap", "name" to "roadmap", "type" to "private", "unread" to 0, "mention" to false, "followedThreadsWithNewReplies" to 0, "muted" to false, "draft" to true),
            mapOf<String, Any?>("id" to "channel-long-name", "name" to "visual-parity-and-release-readiness-for-every-phone", "type" to "channel", "unread" to 0, "mention" to false, "followedThreadsWithNewReplies" to 0, "muted" to false, "draft" to false),
        )
        object DirectMessages1 {
            const val ID: String = "dm-agent-cindy"
            const val PEER_TYPE: String = "agent"
            const val PEER_ID: String = "agent-cindy"
            const val NAME: String = "Cindy"
            const val AVATAR_LABEL: String = "CI"
            const val UNREAD: Int = 0
            const val RUNTIME: String = "codex"
            const val ACTIVITY: String = "working"
            const val ACTIVITY_DETAIL: String = "Capturing visual testing baselines"
        }
        object DirectMessages2 {
            const val ID: String = "dm-agent-builder"
            const val PEER_TYPE: String = "agent"
            const val PEER_ID: String = "agent-builder"
            const val NAME: String = "Builder"
            const val AVATAR_LABEL: String = "BU"
            const val UNREAD: Int = 4
            const val RUNTIME: String = "claude"
            const val ACTIVITY: String = "online"
            val ACTIVITY_DETAIL: String? = null
        }
        object DirectMessages3 {
            const val ID: String = "dm-agent-product-ux"
            const val PEER_TYPE: String = "agent"
            const val PEER_ID: String = "agent-product-ux"
            const val NAME: String = "Product UX"
            const val AVATAR_LABEL: String = "PR"
            const val UNREAD: Int = 0
            const val RUNTIME: String = "claude"
            const val ACTIVITY: String = "online"
            val ACTIVITY_DETAIL: String? = null
        }
        object DirectMessages4 {
            const val ID: String = "dm-human-jiacheng"
            const val PEER_TYPE: String = "user"
            const val PEER_ID: String = "visual-human-jiacheng"
            const val NAME: String = "Jiacheng from the infrastructure reliability team"
            const val AVATAR_LABEL: String = "JI"
            const val UNREAD: Int = 1
            val RUNTIME: String? = null
            val ACTIVITY: String? = null
            val ACTIVITY_DETAIL: String? = null
        }
        val DIRECT_MESSAGES: List<Map<String, Any?>> = listOf(
            mapOf<String, Any?>("id" to "dm-agent-cindy", "peerType" to "agent", "peerId" to "agent-cindy", "name" to "Cindy", "avatarLabel" to "CI", "unread" to 0, "runtime" to "codex", "activity" to "working", "activityDetail" to "Capturing visual testing baselines"),
            mapOf<String, Any?>("id" to "dm-agent-builder", "peerType" to "agent", "peerId" to "agent-builder", "name" to "Builder", "avatarLabel" to "BU", "unread" to 4, "runtime" to "claude", "activity" to "online", "activityDetail" to null),
            mapOf<String, Any?>("id" to "dm-agent-product-ux", "peerType" to "agent", "peerId" to "agent-product-ux", "name" to "Product UX", "avatarLabel" to "PR", "unread" to 0, "runtime" to "claude", "activity" to "online", "activityDetail" to null),
            mapOf<String, Any?>("id" to "dm-human-jiacheng", "peerType" to "user", "peerId" to "visual-human-jiacheng", "name" to "Jiacheng from the infrastructure reliability team", "avatarLabel" to "JI", "unread" to 1, "runtime" to null, "activity" to null, "activityDetail" to null),
        )
    }
}
