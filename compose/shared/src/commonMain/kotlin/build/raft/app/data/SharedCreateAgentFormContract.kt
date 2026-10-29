package build.raft.app.data


internal const val CREATE_AGENT_BUILTIN_RUNTIME_ID = "builtin"

internal data class SharedCreateAgentRuntimeCatalog(
    val machineId: String,
    val options: List<SharedCreateAgentRuntimeOption>
)

internal data class SharedCreateAgentRuntimeOption(
    val runtimeId: String,
    val capabilityStatus: String,
    val admissionStatus: String,
    val admissionReason: String?,
    val current: Boolean,
    val availableForNew: Boolean,
    val manageableForCurrentAgent: Boolean,
    val canSelectInThisContext: Boolean,
    /** The row carries the `runtimeFormV2` marker: the server has a v2 form for this runtime. */
    val runtimeFormV2: Boolean = false
) {
    val selectable: Boolean
        get() =
            canSelectInThisContext &&
                capabilityStatus == "available" &&
                admissionStatus == "available_for_new" &&
                admissionReason == null &&
                availableForNew &&
                !current
}

/** Whether the server has a runtime form v2 for a runtime, from the runtime-options rows. */
internal enum class RuntimeFormV2Availability {
    /**
     * No runtime-options answer (not loaded yet, or the request failed), or an answer with no marked
     * row at all: let the form load decide.
     */
    Unknown,
    Available,
    /** Other rows carry the marker but this one is missing or unmarked: there is no form to load. */
    Unavailable
}

/**
 * Web parity (slock #11812): a runtime whose row has no v2 marker shows "unavailable", no form.
 *
 * The server (slock runtimeAdmissionService) only marks rows when the request carries
 * `X-Raft-Runtime-Form-V2: 1`. A catalog where no row carries the marker at all is therefore
 * not proof that no runtime has a form (an older server, or a proxy that dropped the header):
 * it is [RuntimeFormV2Availability.Unknown], as when the catalog fails to load, and the form
 * load decides. Only a catalog that marks some rows makes an unmarked or missing row Unavailable.
 */
internal fun SharedCreateAgentRuntimeCatalog?.runtimeFormV2Availability(runtimeId: String): RuntimeFormV2Availability {
    val catalog = this ?: return RuntimeFormV2Availability.Unknown
    if (catalog.options.none { it.runtimeFormV2 }) return RuntimeFormV2Availability.Unknown
    val row = catalog.options.firstOrNull { it.runtimeId == runtimeId.trim() }
    return if (row?.runtimeFormV2 == true) RuntimeFormV2Availability.Available else RuntimeFormV2Availability.Unavailable
}

/**
 * [runtimeFormV2Availability] for an agent's own (saved) runtime, from the new-agent runtime-options
 * rows. The server marks a row only when the runtime has a v2 form AND is available for new use (or
 * is the current runtime), so an unmarked row is proof of "no v2 form" only when it is available for
 * new use or current. A missing row, or one only grandfathered for existing agents, is Unknown: the
 * agent's edit form load decides.
 */
internal fun SharedCreateAgentRuntimeCatalog?.currentRuntimeFormV2Availability(runtimeId: String): RuntimeFormV2Availability {
    val availability = runtimeFormV2Availability(runtimeId)
    if (availability != RuntimeFormV2Availability.Unavailable) return availability
    val row = this?.options?.firstOrNull { it.runtimeId == runtimeId.trim() }
    return if (row != null && (row.availableForNew || row.current)) {
        RuntimeFormV2Availability.Unavailable
    } else {
        RuntimeFormV2Availability.Unknown
    }
}
