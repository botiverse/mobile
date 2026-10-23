package build.raft.app.data

import kotlin.math.floor

/**
 * The compact quota line a machine/runtime picker shows under each option (design-system rule,
 * #proj-mobile 3e35629b): "Claude Max · 5 小时 剩 72% · 本周 剩 40%".
 *
 * - Only the 5-hour and the weekly window, always in that order; a missing one is left out.
 * - A used-up window reads "5 小时 已用完，3 小时后恢复" (without a reset time: "已用完").
 * - Per-model windows (Claude "Current week (Opus)", a Codex model bucket) stay in the full card.
 *
 * Window ids carry no "5h / week" meaning on the wire (the Computer derives them from a hash of
 * the label: slock `packages/daemon/src/runtimeAccountUsage/oarAdapter.ts` `windowId`), so the
 * windows are recognised by their label per provider ([runtimeQuotaWindowClass]); a provider
 * whose windows match neither falls back to its tightest other window under its raw label.
 */
internal enum class RuntimeQuotaWindowKind {
    FiveHour,
    Week
}

/** What a usage window is for the compact line. */
internal sealed interface RuntimeQuotaWindowClass {
    data class Known(val kind: RuntimeQuotaWindowKind) : RuntimeQuotaWindowClass

    /** Scoped to one model: only the full usage card shows it. */
    data object PerModel : RuntimeQuotaWindowClass

    /** Not recognised: shown under its raw label only when nothing is recognised. */
    data object Other : RuntimeQuotaWindowClass
}

private val fiveHourLabels =
    Regex("""^(5\s*h|5\s*-?\s*hours?|five[\s_-]*hours?|300\s*m(in(utes)?)?)(\s+(limit|window|usage))?$""")
private val weekLabels =
    Regex("""^(weekly|week|1\s+week|7\s*-?\s*days?|7\s*d|seven[\s_-]*days?)(\s+(limit|window|usage|included usage))?$""")

/**
 * How [label] of a [provider] window reads on the compact line. The labels are the ones
 * `@botiverse/oar` 0.69 writes (runtimes/<provider>/account-usage.js):
 * - Claude: "Current session" (5 h), "Current week (all models)"; any other
 *   "Current week (…)" is per model.
 * - Codex: "<bucket> · <duration>" or "<duration>" ("5 hours", "1 week"); the default bucket
 *   is "Codex", any other bucket is a model's (e.g. "GPT-5.3-Codex-Spark · 5 hours").
 * - Kimi: "5h limit", "Weekly limit".
 * - Grok: "Weekly included usage".
 * Any provider also matches the generic spellings ("5-hour", "5 hours", "Weekly", "7 days").
 */
internal fun runtimeQuotaWindowClass(provider: RuntimeAccountUsageProvider?, label: String): RuntimeQuotaWindowClass {
    val clean = label.trim().lowercase().replace(Regex("\\s+"), " ")
    when (provider) {
        RuntimeAccountUsageProvider.Claude -> {
            if (clean == "current session") return RuntimeQuotaWindowClass.Known(RuntimeQuotaWindowKind.FiveHour)
            if (clean == "current week (all models)") return RuntimeQuotaWindowClass.Known(RuntimeQuotaWindowKind.Week)
            if (clean.startsWith("current week (")) return RuntimeQuotaWindowClass.PerModel
        }
        RuntimeAccountUsageProvider.Codex -> {
            val separator = clean.lastIndexOf(" · ")
            if (separator >= 0) {
                val bucket = clean.substring(0, separator).trim()
                val duration = clean.substring(separator + 3).trim()
                if (bucket != "codex") return RuntimeQuotaWindowClass.PerModel
                return genericRuntimeQuotaWindowClass(duration)
            }
        }
        RuntimeAccountUsageProvider.Kimi, RuntimeAccountUsageProvider.Grok, null -> Unit
    }
    return genericRuntimeQuotaWindowClass(clean)
}

private fun genericRuntimeQuotaWindowClass(clean: String): RuntimeQuotaWindowClass =
    when {
        fiveHourLabels.matches(clean) -> RuntimeQuotaWindowClass.Known(RuntimeQuotaWindowKind.FiveHour)
        weekLabels.matches(clean) -> RuntimeQuotaWindowClass.Known(RuntimeQuotaWindowKind.Week)
        else -> RuntimeQuotaWindowClass.Other
    }

/** One window on the line: its name, what is left, and whether it is used up. */
internal data class RuntimeQuotaSegment(
    /** Null: shown under [rawLabel] (the fallback when no window is recognised). */
    val kind: RuntimeQuotaWindowKind?,
    val rawLabel: String,
    val percentLeft: Int,
    val usedUp: Boolean,
    val resetsAt: String?
)

/** The facts of a compact line; [formatRuntimeQuotaLine] turns them into text. */
internal data class RuntimeQuotaSummary(
    val provider: RuntimeAccountUsageProvider,
    val planLabel: String?,
    val segments: List<RuntimeQuotaSegment>,
    /** The account needs attention (signed out, unsupported, error) and has no window to show. */
    val attention: Boolean
)

/** Web `runtimeUsagePercentLeft`: whole percent left, JavaScript rounding (floor(x + 0.5)). */
internal fun runtimeQuotaPercentLeft(usedRatio: Double): Int = 100 - floor(usedRatio.coerceIn(0.0, 1.0) * 100.0 + 0.5).toInt()

/**
 * The compact line's facts for one runtime's usage [read], or null when there is nothing
 * trustworthy to say (no read, no snapshot yet, no account, no usable window).
 * Like the web's bulk dialog it reads the first account.
 */
internal fun summarizeRuntimeQuota(
    provider: RuntimeAccountUsageProvider,
    read: RuntimeAccountUsageRead?
): RuntimeQuotaSummary? {
    val available = read as? RuntimeAccountUsageRead.Available ?: return null
    val account = available.snapshot.accounts.firstOrNull() ?: return null
    val plan = account.planLabel?.trim()?.takeIf { it.isNotEmpty() }
    val usable =
        account.windows.filter { window ->
            window.status != RuntimeAccountUsageWindowStatus.Unavailable && window.usedRatio != null
        }
    val classified = usable.map { it to runtimeQuotaWindowClass(provider, it.label) }
    val known =
        RuntimeQuotaWindowKind.entries.mapNotNull { kind ->
            classified.firstOrNull { (_, cls) -> cls == RuntimeQuotaWindowClass.Known(kind) }?.first?.toSegment(kind)
        }
    val segments =
        known.ifEmpty {
            // Nothing recognised: the tightest unrecognised window, under its own label.
            classified
                .filter { (_, cls) -> cls == RuntimeQuotaWindowClass.Other }
                .maxByOrNull { (window, _) -> window.usedRatio ?: 0.0 }
                ?.first
                ?.toSegment(kind = null)
                ?.let(::listOf)
                .orEmpty()
        }
    val healthy = account.health == RuntimeAccountUsageHealth.Ok || account.health == RuntimeAccountUsageHealth.RateLimited
    if (segments.isEmpty()) {
        return if (healthy) null else RuntimeQuotaSummary(provider, plan, emptyList(), attention = true)
    }
    return RuntimeQuotaSummary(provider, plan, segments, attention = false)
}

private fun RuntimeAccountUsageWindow.toSegment(kind: RuntimeQuotaWindowKind?): RuntimeQuotaSegment {
    val ratio = usedRatio ?: 0.0
    val usedUp = status == RuntimeAccountUsageWindowStatus.LimitReached || ratio >= 1.0
    return RuntimeQuotaSegment(
        kind = kind,
        rawLabel = label,
        percentLeft = if (usedUp) 0 else runtimeQuotaPercentLeft(ratio),
        usedUp = usedUp,
        resetsAt = resetsAt
    )
}

/** The localized words of a compact line; the picker builds it from MR strings. */
internal class RuntimeQuotaLineCopy(
    val fiveHour: String,
    val week: String,
    /** "5 小时 剩 72%": (window, "72%"). */
    val left: (window: String, percent: String) -> String,
    /** "5 小时 已用完，3 小时后恢复": (window, relative reset). */
    val usedUp: (window: String, reset: String) -> String,
    val usedUpNoReset: (window: String) -> String,
    /** "Claude Max · 需要处理": (plan). */
    val attention: (plan: String) -> String
)

/** The brand name a line starts with ("Claude Max"): never translated. */
internal fun RuntimeAccountUsageProvider.quotaBrand(): String =
    when (this) {
        RuntimeAccountUsageProvider.Claude -> "Claude"
        RuntimeAccountUsageProvider.Codex -> "Codex"
        RuntimeAccountUsageProvider.Kimi -> "Kimi"
        RuntimeAccountUsageProvider.Grok -> "Grok"
    }

/** "Claude Max" from the provider and its plan ("max" reads "Max"); the brand alone with no plan. */
internal fun runtimeQuotaPlanName(provider: RuntimeAccountUsageProvider, planLabel: String?): String {
    val brand = provider.quotaBrand()
    val plan = planLabel?.trim()?.takeIf { it.isNotEmpty() } ?: return brand
    val shown = if (plan.all { !it.isLetter() || it.isLowerCase() }) plan.replaceFirstChar { it.uppercaseChar() } else plan
    return if (shown.startsWith(brand, ignoreCase = true)) shown else "$brand $shown"
}

/**
 * The compact line: "Claude Max · 5 小时 剩 72% · 本周 剩 40%". [resetLabel] turns a reset
 * timestamp into its relative words ("3 小时后"); null or blank drops the reset.
 */
internal fun formatRuntimeQuotaLine(
    summary: RuntimeQuotaSummary,
    copy: RuntimeQuotaLineCopy,
    resetLabel: (String) -> String?
): String {
    val plan = runtimeQuotaPlanName(summary.provider, summary.planLabel)
    if (summary.segments.isEmpty()) return copy.attention(plan)
    val parts =
        summary.segments.map { segment ->
            val name =
                when (segment.kind) {
                    RuntimeQuotaWindowKind.FiveHour -> copy.fiveHour
                    RuntimeQuotaWindowKind.Week -> copy.week
                    null -> segment.rawLabel
                }
            if (segment.usedUp) {
                val reset = segment.resetsAt?.let(resetLabel)?.trim()?.takeIf { it.isNotEmpty() }
                if (reset != null) copy.usedUp(name, reset) else copy.usedUpNoReset(name)
            } else {
                copy.left(name, "${segment.percentLeft}%")
            }
        }
    return (listOf(plan) + parts).joinToString(" · ")
}

/**
 * Web `canViewMachineRuntimeAccountUsage` (slock `packages/web/src/utils/
 * machineRuntimeUsageVisibility.ts`): the person who attached the Computer, its creator, or a
 * role with `editMachines` (owner / admin, `packages/shared/src/serverPermissions.ts`). The
 * server still answers 403 to anyone else; this only keeps pickers from asking.
 */
internal fun canViewMachineRuntimeAccountUsage(
    attachedByCurrentUser: Boolean,
    creatorId: String?,
    currentUserId: String?,
    canEditMachines: Boolean
): Boolean {
    val me = currentUserId?.trim()?.takeIf { it.isNotEmpty() }
    return attachedByCurrentUser || (me != null && creatorId?.trim() == me) || canEditMachines
}

/** Who is looking: the facts of [canViewMachineRuntimeAccountUsage] that are not the machine's. */
internal data class RuntimeAccountUsageViewer(
    val currentUserId: String?,
    val canEditMachines: Boolean
)

/** The viewer of [session]'s current server: owner and admin hold `editMachines`. */
internal fun runtimeAccountUsageViewerFor(session: StoredAuthSession?): RuntimeAccountUsageViewer? {
    val current = session ?: return null
    val serverId = current.currentServerId?.trim()?.takeIf { it.isNotEmpty() }
    val role = serverId?.let { id -> current.servers.firstOrNull { it.id == id }?.role?.trim() }
    return RuntimeAccountUsageViewer(
        currentUserId = current.user.id,
        canEditMachines = role == "owner" || role == "admin"
    )
}
