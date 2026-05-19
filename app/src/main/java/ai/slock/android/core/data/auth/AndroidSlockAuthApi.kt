package ai.slock.android.core.data.auth

import ai.slock.compose.data.AuthNetworkResponse
import ai.slock.compose.data.SlockAuthApi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class AndroidSlockAuthApi(
    private val api: AuthApi
) : SlockAuthApi {
    override suspend fun login(bodyText: String): AuthNetworkResponse =
        api.login(bodyText.toJsonBody()).toAuthNetworkResponse()

    override suspend fun currentUser(authorization: String): AuthNetworkResponse =
        api.currentUser(authorization).toAuthNetworkResponse()

    override suspend fun refresh(bodyText: String): AuthNetworkResponse =
        api.refresh(bodyText.toJsonBody()).toAuthNetworkResponse()

    override suspend fun logout(bodyText: String): AuthNetworkResponse =
        api.logout(bodyText.toJsonBody()).toAuthNetworkResponse()

    override suspend fun servers(authorization: String): AuthNetworkResponse =
        api.servers(authorization).toAuthNetworkResponse()

    override suspend fun createServer(authorization: String, bodyText: String): AuthNetworkResponse =
        api.createServer(authorization, bodyText.toJsonBody()).toAuthNetworkResponse()
}

private fun String.toJsonBody() =
    toRequestBody(JSON)

private fun ai.slock.android.core.data.network.ApiResponse.toAuthNetworkResponse(): AuthNetworkResponse =
    AuthNetworkResponse(
        code = code,
        bodyText = body?.string(),
        errorBodyText = errorBody?.string()
    )

private val JSON = "application/json; charset=utf-8".toMediaType()
