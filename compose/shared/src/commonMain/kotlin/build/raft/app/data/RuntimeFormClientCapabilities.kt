package build.raft.app.data

/**
 * Query parameter a runtime form v2 client uses to ADVERTISE optional capabilities to the server
 * (slock `packages/runtime-form/src/v2.ts` `RUNTIME_FORM_V2_CLIENT_CAPABILITIES_QUERY`; comma-separated).
 * It rides on the form definition, edit form, option-source and v2 create/update requests. A client
 * that sends none gets the form exactly as before each capability existed.
 */
internal const val RUNTIME_FORM_V2_CLIENT_CAPABILITIES_QUERY: String = "clientCapabilities"

/**
 * The capabilities this client advertises (slock `RUNTIME_FORM_V2_ADVERTISED_CLIENT_CAPABILITIES`).
 * - `dependent_select.custom_value_with_options`: a dependent_select value with listed options that
 *   also takes a typed one renders as a combobox (RuntimeFormV2Fields `Select(allowCustom = true)`).
 *   Without it the server keeps such a value list-only (Built-in Pi with OpenRouter).
 * - `runtime_form.v3`: protocol v3 shapes on the definition, edit-form and option-source endpoints
 *   (rules, conditional required, the reasoning effort as a dependent_select, unavailableOptions; slock
 *   README "Protocol v3"), read by RuntimeFormV2Parser / RuntimeFormV3Rules. Without it the server
 *   sends v2 unchanged. Implies the one above.
 */
internal val RUNTIME_FORM_V2_ADVERTISED_CLIENT_CAPABILITIES: List<String> =
    listOf("dependent_select.custom_value_with_options", "runtime_form.v3")

/** The query pair that advertises [RUNTIME_FORM_V2_ADVERTISED_CLIENT_CAPABILITIES] on a v2 request. */
internal fun runtimeFormV2ClientCapabilityQuery(): List<Pair<String, String>> =
    listOf(RUNTIME_FORM_V2_CLIENT_CAPABILITIES_QUERY to RUNTIME_FORM_V2_ADVERTISED_CLIENT_CAPABILITIES.joinToString(","))
