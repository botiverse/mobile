package ai.slock.compose

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val settingsStandaloneJson = Json { ignoreUnknownKeys = true }

@Serializable
internal data class WireSettingsHomeLaunchPayload(
    val instanceId: String = "",
    val selectedDestination: String? = null
)

@Serializable
internal data class WireSettingsAccountLaunchPayload(
    val instanceId: String = ""
)

@Serializable
internal data class WireSettingsBillingLaunchPayload(
    val instanceId: String = ""
)

@Serializable
internal data class WireSettingsStorageLaunchPayload(
    val instanceId: String = ""
)

@Serializable
internal data class WireSettingsComputersLaunchPayload(
    val instanceId: String = ""
)

@Serializable
internal data class WireSettingsComputerDetailLaunchPayload(
    val instanceId: String = "",
    val computerId: String? = null,
    val machineId: String? = null
)

internal fun parseSettingsHomePayload(
    payload: String,
    fallbackPageData: String,
    instanceId: String
): KuiklySettingsHomePayload {
    val launch = parseSettingsLaunchPayload(
        payload = payload,
        fallbackPageData = fallbackPageData,
        serializer = WireSettingsHomeLaunchPayload.serializer(),
        default = WireSettingsHomeLaunchPayload(instanceId = instanceId)
    )
    return KuiklySettingsHomePayload(instanceId = launch.instanceId.ifBlank { instanceId })
}

internal fun parseSettingsAccountPayload(
    payload: String,
    fallbackPageData: String,
    instanceId: String
): KuiklySettingsAccountPayload {
    val launch = parseSettingsLaunchPayload(
        payload = payload,
        fallbackPageData = fallbackPageData,
        serializer = WireSettingsAccountLaunchPayload.serializer(),
        default = WireSettingsAccountLaunchPayload(instanceId = instanceId)
    )
    return safeSettingsAccountLaunchPayload(launch.instanceId.ifBlank { instanceId })
}

internal fun parseSettingsBillingPayload(
    payload: String,
    fallbackPageData: String,
    instanceId: String
): KuiklySettingsBillingPayload {
    val launch = parseSettingsLaunchPayload(
        payload = payload,
        fallbackPageData = fallbackPageData,
        serializer = WireSettingsBillingLaunchPayload.serializer(),
        default = WireSettingsBillingLaunchPayload(instanceId = instanceId)
    )
    return KuiklySettingsBillingPayload(
        instanceId = launch.instanceId.ifBlank { instanceId },
        status = "loading"
    )
}

internal fun parseSettingsStoragePayload(
    payload: String,
    fallbackPageData: String,
    instanceId: String
): KuiklySettingsStoragePayload {
    val launch = parseSettingsLaunchPayload(
        payload = payload,
        fallbackPageData = fallbackPageData,
        serializer = WireSettingsStorageLaunchPayload.serializer(),
        default = WireSettingsStorageLaunchPayload(instanceId = instanceId)
    )
    return KuiklySettingsStoragePayload(instanceId = launch.instanceId.ifBlank { instanceId })
}

internal fun parseSettingsComputersPayload(
    payload: String,
    fallbackPageData: String,
    instanceId: String
): KuiklySettingsComputersPayload {
    val launch = parseSettingsLaunchPayload(
        payload = payload,
        fallbackPageData = fallbackPageData,
        serializer = WireSettingsComputersLaunchPayload.serializer(),
        default = WireSettingsComputersLaunchPayload(instanceId = instanceId)
    )
    return KuiklySettingsComputersPayload(
        instanceId = launch.instanceId.ifBlank { instanceId },
        status = "loading"
    )
}

internal fun parseSettingsComputerDetailPayload(
    payload: String,
    fallbackPageData: String,
    instanceId: String
): KuiklySettingsComputerDetailPayload {
    val launch = parseSettingsLaunchPayload(
        payload = payload,
        fallbackPageData = fallbackPageData,
        serializer = WireSettingsComputerDetailLaunchPayload.serializer(),
        default = WireSettingsComputerDetailLaunchPayload(instanceId = instanceId)
    )
    val computerId = launch.computerId?.takeIf { it.isNotBlank() } ?: launch.machineId?.takeIf { it.isNotBlank() }
    return KuiklySettingsComputerDetailPayload(
        instanceId = launch.instanceId.ifBlank { instanceId },
        computer = KuiklySettingsComputerItem(
            id = computerId.orEmpty(),
            name = "Computer"
        )
    )
}

private fun safeSettingsAccountLaunchPayload(instanceId: String): KuiklySettingsAccountPayload =
    KuiklySettingsAccountPayload(
        instanceId = instanceId,
        canEditProfile = false,
        canUploadAvatar = false,
        canChangePassword = false
    )

private fun <T> parseSettingsLaunchPayload(
    payload: String,
    fallbackPageData: String,
    serializer: kotlinx.serialization.KSerializer<T>,
    default: T
): T =
    runCatching {
        val source = payload.trim()
            .takeIf { it.isNotEmpty() }
            ?: fallbackPageData.trim().takeIf { it.isNotEmpty() }
            ?: "{}"
        settingsStandaloneJson.decodeFromString(serializer, source)
    }.getOrElse { default }
