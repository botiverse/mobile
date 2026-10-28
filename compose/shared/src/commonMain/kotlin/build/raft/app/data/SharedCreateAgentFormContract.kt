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
    /** No runtime-options answer (not loaded yet, or the request failed): let the form load decide. */
    Unknown,
    Available,
    /** The row is missing or carries no `runtimeFormV2` marker: there is no form to load. */
    Unavailable
}

/** Web parity (slock #11812): a runtime whose row has no v2 marker shows "unavailable", no form. */
internal fun SharedCreateAgentRuntimeCatalog?.runtimeFormV2Availability(runtimeId: String): RuntimeFormV2Availability {
    val catalog = this ?: return RuntimeFormV2Availability.Unknown
    val row = catalog.options.firstOrNull { it.runtimeId == runtimeId.trim() }
    return if (row?.runtimeFormV2 == true) RuntimeFormV2Availability.Available else RuntimeFormV2Availability.Unavailable
}
